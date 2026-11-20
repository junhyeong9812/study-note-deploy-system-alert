package xyz.junproject.alert.incident

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import xyz.junproject.alert.monitor.MonitorStatus
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class IncidentStoreTest {
    private fun open(directory: Path) =
        IncidentStore(JdbcTemplate(SingleConnectionDataSource("jdbc:sqlite:${directory.resolve("alert.db")}", true)))
    private val base = Instant.parse("2026-11-20T00:00:00Z")
    private fun incident(at: Instant, kind: String = "monitor_down", key: String = "llm", deliveredBy: String? = null, suppressed: Boolean = false) =
        Incident(at, kind, key, "제목 $key", "내용", deliveredBy, suppressed)

    @Test
    fun `기록한 행의 id 로 나간 채널을 나중에 적는다`(@TempDir directory: Path) {
        val store = open(directory)
        val first = store.record(incident(base))
        val second = store.record(incident(base.plusSeconds(1), key = "front"))
        assertNotEquals(first, second)
        store.markDelivered(second, "slack")
        assertEquals(listOf("slack", null), store.recent(10).map { it.deliveredBy })
    }

    @Test
    fun `못 나간 알림만 오래된 것부터 돌려준다 - 억제된 사건과 요약과 창 밖은 뺀다`(@TempDir directory: Path) {
        val store = open(directory)
        store.record(incident(base.minusSeconds(100_000), key = "too-old"))
        store.record(incident(base, key = "a"))
        store.record(incident(base.plusSeconds(1), key = "sent", deliveredBy = "discord"))
        store.record(incident(base.plusSeconds(2), kind = "event", key = "repeated", suppressed = true))
        store.record(incident(base.plusSeconds(3), kind = "summary", key = "daily"))
        store.record(incident(base.plusSeconds(4), kind = "event", key = "b"))
        assertEquals(listOf("a", "b"), store.undeliveredSince(base.minusSeconds(86_400), 200).map { it.second.key })
        assertEquals(listOf("b"), store.undeliveredSince(base.minusSeconds(86_400), 1).map { it.second.key }, "상한이면 최근 것을 남긴다")
    }

    @Test
    fun `시각 비교는 소수 자릿수가 달라도 시간 순서를 따른다`(@TempDir directory: Path) {
        val store = open(directory)
        store.record(incident(Instant.parse("2026-11-20T00:00:00Z"), key = "whole"))
        store.record(incident(Instant.parse("2026-11-20T00:00:00.500Z"), key = "half"))
        assertEquals(listOf("half"), store.since(Instant.parse("2026-11-20T00:00:00.250Z")).map { it.key })
        assertTrue(store.eventSentSince("none", base).not())
    }

    @Test
    fun `30일 삭제는 알림 기록과 상태 변화를 함께 지운다`(@TempDir directory: Path) {
        val store = open(directory)
        store.record(incident(base.minusSeconds(40L * 86_400)))
        store.record(incident(base))
        store.recordChange(MonitorChange(base.minusSeconds(40L * 86_400), "llm", MonitorStatus.DOWN, null))
        store.recordChange(MonitorChange(base, "llm", MonitorStatus.UP, 90))
        assertEquals(2, store.deleteOlderThan(base.minusSeconds(30L * 86_400)))
        assertEquals(1, store.recent(10).size)
        assertEquals(listOf(90L), store.changesSince(base.minusSeconds(86_400)).map { it.downSeconds })
    }

    @Test
    fun `여러 스레드와 두 연결(배포 중의 두 자리)이 같은 파일에 함께 써도 잃거나 실패하지 않는다`(@TempDir directory: Path) {
        val blue = open(directory)
        val green = open(directory)          // 같은 파일을 여는 두 번째 연결 — 두 프로세스가 겪는 파일 잠금과 같다
        val failures = CopyOnWriteArrayList<Throwable>()
        val ids = CopyOnWriteArrayList<Long>()
        val executor = Executors.newFixedThreadPool(8)
        repeat(8) { worker ->
            executor.submit {
                val store = if (worker % 2 == 0) blue else green
                repeat(50) { turn ->
                    runCatching {
                        val id = store.record(incident(base.plusSeconds(turn.toLong()), key = "w$worker-$turn"))
                        store.markDelivered(id, "discord")
                        store.recordChange(MonitorChange(base, "m$worker", MonitorStatus.DOWN, null))
                        store.recent(5)
                        ids += id
                    }.onFailure(failures::add)
                }
            }
        }
        executor.shutdown()
        assertTrue(executor.awaitTermination(60, TimeUnit.SECONDS))
        assertTrue(failures.isEmpty(), "쓰기 실패 ${failures.size}건: ${failures.firstOrNull()}")
        assertEquals(400, ids.toSet().size, "id 가 겹쳤다")
        val all = blue.since(base.minusSeconds(1))
        assertEquals(400, all.size)
        assertTrue(all.all { it.deliveredBy == "discord" }, "다른 행에 채널을 적었다")
        assertEquals(400, green.changesSince(base.minusSeconds(1)).size)
    }
}
