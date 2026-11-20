package xyz.junproject.alert.incident

import org.springframework.jdbc.core.JdbcTemplate
import xyz.junproject.alert.monitor.MonitorState
import xyz.junproject.alert.monitor.MonitorStatus
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** 알린 일(또는 알리려던 일) 한 건 */
data class Incident(
    val at: Instant,
    val kind: String,          // monitor_down · monitor_up · monitor_still_down · event · summary
    val key: String,           // 대상 이름 또는 이벤트 key
    val title: String,
    val detail: String,
    val deliveredBy: String?,  // 나간 채널, 못 나갔으면 null
    val suppressed: Boolean = false,
)

/** 대상 하나의 상태 변화 — 알림을 묶어 보냈더라도 대상마다 한 줄씩 남긴다(하루 요약의 대상별 횟수·시간에 쓴다). */
data class MonitorChange(
    val at: Instant,
    val monitor: String,
    val status: MonitorStatus,   // DOWN 이 됐다 / UP 으로 돌아왔다
    val downSeconds: Long?,      // UP 일 때: 끊겨 있던 시간
)

/**
 * 기록 — 파일 DB(SQLite). 감시와 발송은 이 기록이 실패해도 계속돼야 하므로 호출하는 쪽이 예외를 삼킨다.
 * 연결 하나를 여러 스레드(감시 바퀴·발송·HTTP 요청)가 함께 쓰므로 모든 접근을 한 번에 하나씩으로 묶는다 —
 * 한 스레드가 읽는 도중에 다른 스레드가 같은 연결로 쓰면 SQLite 가 쓰기를 거절한다(실측: SQLITE_BUSY_SNAPSHOT).
 * 배포 중에는 두 자리(두 프로세스)가 같은 파일을 연다 — 잠긴 동안은 [BUSY_TIMEOUT_MS] 까지 기다린다.
 */
class IncidentStore(private val jdbc: JdbcTemplate) {
    init {
        jdbc.execute("PRAGMA busy_timeout = $BUSY_TIMEOUT_MS")
        // WAL: 쓰는 쪽이 읽는 쪽을 막지 않고 커밋이 짧다 — 두 자리가 겹치는 동안의 잠금 실패를 줄인다
        jdbc.execute("PRAGMA journal_mode = WAL")
        jdbc.execute("PRAGMA synchronous = NORMAL")
        jdbc.execute(
            """CREATE TABLE IF NOT EXISTS incident (
                 id INTEGER PRIMARY KEY AUTOINCREMENT, at TEXT NOT NULL, kind TEXT NOT NULL, key TEXT NOT NULL,
                 title TEXT NOT NULL, detail TEXT NOT NULL, delivered_by TEXT, suppressed INTEGER NOT NULL DEFAULT 0)"""
        )
        jdbc.execute("CREATE INDEX IF NOT EXISTS incident_at ON incident(at)")
        jdbc.execute(
            """CREATE TABLE IF NOT EXISTS monitor_state (
                 name TEXT PRIMARY KEY, status TEXT NOT NULL, consecutive_failures INTEGER NOT NULL,
                 down_since TEXT, last_alert_at TEXT, last_reason TEXT NOT NULL, updated_at TEXT NOT NULL)"""
        )
        jdbc.execute(
            """CREATE TABLE IF NOT EXISTS monitor_change (
                 id INTEGER PRIMARY KEY AUTOINCREMENT, at TEXT NOT NULL, monitor TEXT NOT NULL, status TEXT NOT NULL,
                 down_seconds INTEGER)"""
        )
        jdbc.execute("CREATE INDEX IF NOT EXISTS monitor_change_at ON monitor_change(at)")
    }

    /** 기록하고 그 행의 id 를 돌려준다(발송이 끝난 뒤 [markDelivered]로 채널을 적는다). */
    @Synchronized
    fun record(incident: Incident): Long {
        jdbc.update(
            "INSERT INTO incident (at, kind, key, title, detail, delivered_by, suppressed) VALUES (?,?,?,?,?,?,?)",
            stamp(incident.at), incident.kind, incident.key, incident.title, incident.detail.take(4000),
            incident.deliveredBy, if (incident.suppressed) 1 else 0,
        )
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long::class.java)!!
    }

    @Synchronized
    fun markDelivered(id: Long, channel: String) {
        jdbc.update("UPDATE incident SET delivered_by = ? WHERE id = ?", channel, id)
    }

    private fun incidentOf(row: java.sql.ResultSet) = Incident(
        Instant.parse(row.getString("at")), row.getString("kind"), row.getString("key"), row.getString("title"),
        row.getString("detail"), row.getString("delivered_by"), row.getInt("suppressed") == 1,
    )

    @Synchronized
    fun recent(limit: Int): List<Incident> = jdbc.query(
        "SELECT at, kind, key, title, detail, delivered_by, suppressed FROM incident ORDER BY id DESC LIMIT ?",
        { row, _ -> incidentOf(row) }, limit,
    )

    @Synchronized
    fun since(from: Instant): List<Incident> = jdbc.query(
        "SELECT at, kind, key, title, detail, delivered_by, suppressed FROM incident WHERE at >= ? ORDER BY id",
        { row, _ -> incidentOf(row) }, stamp(from),
    )

    /** 아직 어느 채널로도 나가지 못한 알림(요약 제외) — 오래된 것부터, 최근 [limit]건까지. */
    @Synchronized
    fun undeliveredSince(from: Instant, limit: Int): List<Pair<Long, Incident>> = jdbc.query(
        """SELECT id, at, kind, key, title, detail, delivered_by, suppressed FROM incident
           WHERE at >= ? AND delivered_by IS NULL AND suppressed = 0 AND kind <> 'summary' ORDER BY id DESC LIMIT ?""",
        { row, _ -> row.getLong("id") to incidentOf(row) }, stamp(from), limit,
    ).reversed()

    /** 같은 key 의 사건을 [from] 뒤에 발송한 적이 있는가 — 자리가 바뀌어도 반복 억제가 이어지게 기록에서 본다. */
    @Synchronized
    fun eventSentSince(key: String, from: Instant): Boolean = jdbc.queryForObject(
        "SELECT COUNT(*) FROM incident WHERE kind = 'event' AND key = ? AND suppressed = 0 AND at >= ?",
        Int::class.java, key, stamp(from),
    )!! > 0

    @Synchronized
    fun recordChange(change: MonitorChange) {
        jdbc.update(
            "INSERT INTO monitor_change (at, monitor, status, down_seconds) VALUES (?,?,?,?)",
            stamp(change.at), change.monitor, change.status.name, change.downSeconds,
        )
    }

    @Synchronized
    fun changesSince(from: Instant): List<MonitorChange> = jdbc.query(
        "SELECT at, monitor, status, down_seconds FROM monitor_change WHERE at >= ? ORDER BY id",
        { row, _ ->
            MonitorChange(Instant.parse(row.getString("at")), row.getString("monitor"), MonitorStatus.valueOf(row.getString("status")),
                row.getLong("down_seconds").takeUnless { row.wasNull() })
        }, stamp(from),
    )

    @Synchronized
    fun saveState(name: String, state: MonitorState, now: Instant) {
        jdbc.update(
            """INSERT INTO monitor_state (name, status, consecutive_failures, down_since, last_alert_at, last_reason, updated_at)
               VALUES (?,?,?,?,?,?,?)
               ON CONFLICT(name) DO UPDATE SET status=excluded.status, consecutive_failures=excluded.consecutive_failures,
                 down_since=excluded.down_since, last_alert_at=excluded.last_alert_at, last_reason=excluded.last_reason,
                 updated_at=excluded.updated_at""",
            name, state.status.name, state.consecutiveFailures, state.downSince?.toString(), state.lastAlertAt?.toString(),
            state.lastReason, stamp(now),
        )
    }

    /** 재시작·자리 교대 때 이어 받을 상태 — DOWN 이던 대상만 의미가 있다(나머지는 모름에서 다시 시작) */
    @Synchronized
    fun loadDownStates(): Map<String, MonitorState> = jdbc.query(
        "SELECT name, consecutive_failures, down_since, last_alert_at, last_reason FROM monitor_state WHERE status = 'DOWN'",
    ) { row, _ ->
        row.getString("name") to MonitorState(
            MonitorStatus.DOWN, row.getInt("consecutive_failures"),
            row.getString("down_since")?.let(Instant::parse), row.getString("last_alert_at")?.let(Instant::parse),
            row.getString("last_reason"),
        )
    }.toMap()

    /** 지운 행 수(알림 기록 + 상태 변화) */
    @Synchronized
    fun deleteOlderThan(cutoff: Instant): Int =
        jdbc.update("DELETE FROM incident WHERE at < ?", stamp(cutoff)) +
            jdbc.update("DELETE FROM monitor_change WHERE at < ?", stamp(cutoff))

    companion object {
        const val BUSY_TIMEOUT_MS = 5000
        // 글자 순서가 시간 순서와 같도록 자릿수를 고정한다(Instant.toString 은 소수 자릿수가 달라진다)
        private val fixedWidth = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
        fun stamp(instant: Instant): String = fixedWidth.format(instant)
    }
}
