package xyz.junproject.alert.incident

import org.springframework.jdbc.core.JdbcTemplate
import xyz.junproject.alert.monitor.MonitorState
import xyz.junproject.alert.monitor.MonitorStatus
import java.time.Instant

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

/** 기록 — 파일 DB(SQLite). 감시와 발송은 이 기록이 실패해도 계속돼야 하므로 호출하는 쪽이 예외를 삼킨다. */
class IncidentStore(private val jdbc: JdbcTemplate) {
    init {
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
    }

    fun record(incident: Incident) {
        jdbc.update(
            "INSERT INTO incident (at, kind, key, title, detail, delivered_by, suppressed) VALUES (?,?,?,?,?,?,?)",
            incident.at.toString(), incident.kind, incident.key, incident.title, incident.detail.take(4000),
            incident.deliveredBy, if (incident.suppressed) 1 else 0,
        )
    }

    fun recent(limit: Int): List<Incident> = jdbc.query(
        "SELECT at, kind, key, title, detail, delivered_by, suppressed FROM incident ORDER BY id DESC LIMIT ?",
        { row, _ ->
            Incident(Instant.parse(row.getString("at")), row.getString("kind"), row.getString("key"), row.getString("title"),
                row.getString("detail"), row.getString("delivered_by"), row.getInt("suppressed") == 1)
        }, limit,
    )

    fun since(from: Instant): List<Incident> = jdbc.query(
        "SELECT at, kind, key, title, detail, delivered_by, suppressed FROM incident WHERE at >= ? ORDER BY id",
        { row, _ ->
            Incident(Instant.parse(row.getString("at")), row.getString("kind"), row.getString("key"), row.getString("title"),
                row.getString("detail"), row.getString("delivered_by"), row.getInt("suppressed") == 1)
        }, from.toString(),
    )

    fun saveState(name: String, state: MonitorState, now: Instant) {
        jdbc.update(
            """INSERT INTO monitor_state (name, status, consecutive_failures, down_since, last_alert_at, last_reason, updated_at)
               VALUES (?,?,?,?,?,?,?)
               ON CONFLICT(name) DO UPDATE SET status=excluded.status, consecutive_failures=excluded.consecutive_failures,
                 down_since=excluded.down_since, last_alert_at=excluded.last_alert_at, last_reason=excluded.last_reason,
                 updated_at=excluded.updated_at""",
            name, state.status.name, state.consecutiveFailures, state.downSince?.toString(), state.lastAlertAt?.toString(),
            state.lastReason, now.toString(),
        )
    }

    /** 재시작 때 이어 받을 상태 — DOWN 이던 대상만 의미가 있다(나머지는 모름에서 다시 시작) */
    fun loadDownStates(): Map<String, MonitorState> = jdbc.query(
        "SELECT name, consecutive_failures, down_since, last_alert_at, last_reason FROM monitor_state WHERE status = 'DOWN'",
    ) { row, _ ->
        row.getString("name") to MonitorState(
            MonitorStatus.DOWN, row.getInt("consecutive_failures"),
            row.getString("down_since")?.let(Instant::parse), row.getString("last_alert_at")?.let(Instant::parse),
            row.getString("last_reason"),
        )
    }.toMap()

    fun deleteOlderThan(cutoff: Instant): Int = jdbc.update("DELETE FROM incident WHERE at < ?", cutoff.toString())
}
