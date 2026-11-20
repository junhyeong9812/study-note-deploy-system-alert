package xyz.junproject.alert.monitor

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import xyz.junproject.alert.incident.IncidentStore
import xyz.junproject.alert.notify.FallbackNotifier
import xyz.junproject.alert.notify.Notification
import xyz.junproject.alert.notify.NotificationChannel
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

class MonitorRoundTest {
    private class Inbox : NotificationChannel {
        override val name = "discord"
        val received = CopyOnWriteArrayList<Notification>()
        override fun send(notification: Notification) { received += notification }
    }

    private val inbox = Inbox()
    private val notifier = FallbackNotifier(listOf(inbox), pause = {})
    private var now: Instant = Instant.parse("2026-11-20T00:00:00Z")
    private val unhealthy = mutableSetOf<String>()
    private val probe = MonitorProbe { definition ->
        if (definition.name in unhealthy) ProbeResult.failed("status 503") else ProbeResult.HEALTHY
    }

    private fun definitions(vararg names: String) = names.map { MonitorDefinition(it, "http://unused/$it") }
    private fun round(definitions: List<MonitorDefinition>, probe: MonitorProbe = this.probe, store: IncidentStore? = null,
                      budget: Duration = Duration.ofSeconds(2)) =
        MonitorRound(definitions, probe, MonitorTracker(), notifier, store, budget) { now }

    private fun MonitorRound.runTimes(times: Int) = repeat(times) { run(); now = now.plusSeconds(30) }

    @Test
    fun `세 바퀴 연속 실패한 대상만 알리고 복구되면 끊긴 시간을 알린다`() {
        val round = round(definitions("front", "llm"))
        unhealthy += "llm"
        round.runTimes(2)
        assertTrue(inbox.received.isEmpty(), "두 번 실패로 알렸다")
        round.runTimes(1)
        assertEquals(listOf("응답 없음: llm"), inbox.received.map { it.title })
        unhealthy.clear()
        round.runTimes(1)
        assertEquals("복구: llm", inbox.received.last().title)
        assertTrue(inbox.received.last().body.contains("30초"), inbox.received.last().body)
    }

    @Test
    fun `같은 바퀴에 여럿이 죽으면 알림 하나로 묶는다`() {
        val round = round(definitions("backend", "backend-deps", "front"))
        unhealthy += listOf("backend", "backend-deps")
        round.runTimes(3)
        assertEquals(1, inbox.received.size)
        assertEquals("응답 없음: 대상 2개", inbox.received[0].title)
        assertTrue(inbox.received[0].body.contains("backend-deps") && inbox.received[0].body.contains("- backend:"))
    }

    @Test
    fun `느린 대상 하나가 다른 대상의 확인과 한 바퀴의 끝을 붙잡지 않는다`() {
        val slowProbe = MonitorProbe { definition ->
            if (definition.name == "slow") { Thread.sleep(30_000); ProbeResult.HEALTHY } else ProbeResult.failed("status 503")
        }
        val round = round(definitions("slow", "llm"), probe = slowProbe, budget = Duration.ofMillis(300))
        val started = System.nanoTime()
        round.runTimes(3)
        val elapsed = Duration.ofNanos(System.nanoTime() - started)
        assertTrue(elapsed < Duration.ofSeconds(5), "느린 대상을 기다렸다: $elapsed")
        // 느린 대상은 확인을 못 끝냈으므로 실패로 세고, 다른 대상도 제때 판정된다
        assertEquals(1, inbox.received.size)
        assertEquals("응답 없음: 대상 2개", inbox.received[0].title)
        assertTrue(inbox.received[0].body.contains("확인을 끝내지 못했다"), inbox.received[0].body)
    }

    @Test
    fun `확인 중 예외가 나도 한 바퀴는 끝나고 그 대상은 실패로 센다`() {
        val throwingProbe = MonitorProbe { definition ->
            if (definition.name == "broken") throw IllegalStateException("boom") else ProbeResult.HEALTHY
        }
        val round = round(definitions("broken", "front"), probe = throwingProbe)
        round.runTimes(3)
        assertEquals(listOf("응답 없음: broken"), inbox.received.map { it.title })
        assertEquals(MonitorStatus.UP, round.snapshot()["front"]?.status)
    }

    @Test
    fun `정리 중인 자리는 확인도 발송도 하지 않는다`() {
        val round = round(definitions("llm"))
        unhealthy += "llm"
        round.draining = true
        round.runTimes(5)
        assertTrue(inbox.received.isEmpty())
        assertNull(round.lastRoundAt)
    }

    @Test
    fun `재시작해도 이미 알린 장애를 다시 알리지 않고 복구는 원래 시각부터 센다`(@TempDir directory: Path) {
        val store = IncidentStore(JdbcTemplate(SingleConnectionDataSource("jdbc:sqlite:${directory.resolve("alert.db")}", true)))
        unhealthy += "llm"
        round(definitions("llm"), store = store).runTimes(3)          // 60초 시점에 DOWN 알림
        assertEquals(1, inbox.received.size)

        val restarted = round(definitions("llm"), store = store)       // 재시작
        restarted.runTimes(3)
        assertEquals(1, inbox.received.size, "재시작했다고 같은 장애를 또 알렸다: ${inbox.received.map { it.title }}")
        unhealthy.clear()
        restarted.runTimes(1)
        assertEquals("복구: llm", inbox.received.last().title)
        assertTrue(inbox.received.last().body.contains("2분 0초"), inbox.received.last().body)   // 60초 → 180초
        assertEquals(listOf("monitor_up", "monitor_down"), store.recent(10).map { it.kind })
    }

    @Test
    fun `기록에 실패해도 감시와 발송은 계속한다`(@TempDir directory: Path) {
        val dataSource = SingleConnectionDataSource("jdbc:sqlite:${directory.resolve("alert.db")}", true)
        val store = IncidentStore(JdbcTemplate(dataSource))
        val round = round(definitions("llm"), store = store)
        dataSource.destroy()                                           // 기록 저장소가 죽었다
        unhealthy += "llm"
        round.runTimes(3)
        assertEquals(listOf("응답 없음: llm"), inbox.received.map { it.title })
    }
}
