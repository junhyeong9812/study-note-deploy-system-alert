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
    private val notifier = FallbackNotifier(listOf(inbox), pause = {}, worker = { it.run() })   // 테스트에서는 그 자리에서 보낸다
    private var servingSlot: String? = "blue"                                                  // 프록시가 지금 보내는 자리
    private var now: Instant = Instant.parse("2026-11-20T00:00:00Z")
    private val unhealthy = mutableSetOf<String>()
    private val probe = MonitorProbe { definition ->
        if (definition.name in unhealthy) ProbeResult.failed("status 503") else ProbeResult.HEALTHY
    }

    private fun definitions(vararg names: String) = names.map { MonitorDefinition(it, "http://unused/$it") }
    private fun round(definitions: List<MonitorDefinition>, probe: MonitorProbe = this.probe, store: IncidentStore? = null,
                      budget: Duration = Duration.ofSeconds(2), slot: String = "", notifier: FallbackNotifier = this.notifier) =
        MonitorRound(definitions, probe, MonitorTracker(), notifier, store, budget,
            if (slot.isBlank()) ActiveSlot.always() else ActiveSlot(slot, { servingSlot }, clock = { now })) { now }

    private fun store(directory: Path) =
        IncidentStore(JdbcTemplate(SingleConnectionDataSource("jdbc:sqlite:${directory.resolve("alert.db")}", true)))

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
    fun `대기 자리는 확인도 발송도 하지 않고 프록시가 이 자리로 보내기 시작하면 이어받는다`(@TempDir directory: Path) {
        val store = store(directory)
        val blue = round(definitions("llm"), store = store, slot = "blue")
        val green = round(definitions("llm"), store = store, slot = "green")      // 배포로 미리 떠 있는 새 자리
        unhealthy += "llm"
        repeat(3) { blue.run(); green.run(); now = now.plusSeconds(30) }
        assertEquals(listOf("응답 없음: llm"), inbox.received.map { it.title }, "두 자리가 함께 알렸다")
        assertFalse(green.active); assertNull(green.lastRoundAt)

        servingSlot = "green"                                                       // 전환
        repeat(3) { blue.run(); green.run(); now = now.plusSeconds(30) }
        assertFalse(blue.active); assertTrue(green.active)
        assertEquals(1, inbox.received.size, "새 자리가 이미 알린 장애를 또 알렸다: ${inbox.received.map { it.title }}")

        unhealthy.clear()
        blue.run(); green.run()
        assertEquals(listOf("응답 없음: llm", "복구: llm"), inbox.received.map { it.title })
        assertTrue(inbox.received.last().body.contains("2분 0초"), inbox.received.last().body)   // 60초에 DOWN → 180초에 복구
    }

    @Test
    fun `앞 자리가 이미 알린 복구를 새 자리가 다시 알리지 않는다`(@TempDir directory: Path) {
        val store = store(directory)
        val blue = round(definitions("llm"), store = store, slot = "blue")
        val green = round(definitions("llm"), store = store, slot = "green")
        unhealthy += "llm"
        repeat(3) { blue.run(); green.run(); now = now.plusSeconds(30) }           // green 은 llm 이 DOWN 일 때부터 떠 있었다
        unhealthy.clear()
        blue.run(); now = now.plusSeconds(30)                                       // 복구는 blue 가 알렸다
        servingSlot = "green"
        repeat(2) { blue.run(); green.run(); now = now.plusSeconds(30) }
        assertEquals(listOf("응답 없음: llm", "복구: llm"), inbox.received.map { it.title })
    }

    @Test
    fun `정리 알림을 받으면 곧바로 내려놓지만 프록시가 여전히 이 자리로 보내면 다음 바퀴에 다시 맡는다`() {
        val blue = round(definitions("llm"), slot = "blue")
        unhealthy += "llm"
        blue.runTimes(2)
        blue.standDown()
        assertFalse(blue.active)
        blue.runTimes(1)                                                            // 프록시는 여전히 blue — 감시가 꺼진 채 남지 않는다
        assertTrue(blue.active)
        assertEquals(listOf("응답 없음: llm"), inbox.received.map { it.title }, "세던 실패 횟수를 잃었다")

        servingSlot = "green"                                                       // 실제로 전환된 뒤의 정리 알림
        blue.standDown()
        unhealthy.clear()
        blue.runTimes(3)
        assertFalse(blue.active)
        assertEquals(1, inbox.received.size, "대기 자리가 알렸다")
    }

    @Test
    fun `프록시에 물어볼 수 없으면 직전 판단을 유지하고 한 번도 못 물었으면 2분 뒤 스스로 맡는다`() {
        servingSlot = null
        val blue = round(definitions("llm"), slot = "blue")
        unhealthy += "llm"
        blue.runTimes(4)                                                            // 0·30·60·90초 — 아직 대기
        assertFalse(blue.active); assertTrue(inbox.received.isEmpty())
        blue.runTimes(3)                                                            // 120초부터 맡는다 → 180초에 세 번째 실패
        assertTrue(blue.active)
        assertEquals(listOf("응답 없음: llm"), inbox.received.map { it.title })
    }

    @Test
    fun `발송이 느려도 다음 바퀴가 밀리지 않는다`() {
        val delivered = CopyOnWriteArrayList<String>()
        val slowChannel = object : NotificationChannel {
            override val name = "discord"
            override fun send(notification: Notification) { Thread.sleep(1_500); delivered += notification.title }
        }
        val round = round(definitions("llm"), notifier = FallbackNotifier(listOf(slowChannel), pause = {}))   // 실제 발송 스레드
        unhealthy += "llm"
        round.runTimes(3)                                                           // 세 번째 바퀴가 발송을 맡긴다
        val started = System.nanoTime()
        unhealthy.clear()
        round.runTimes(2)
        val elapsed = Duration.ofNanos(System.nanoTime() - started)
        assertTrue(elapsed < Duration.ofMillis(800), "바퀴가 발송을 기다렸다: $elapsed")
        val deadline = System.currentTimeMillis() + 8_000
        while (delivered.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(listOf("응답 없음: llm", "복구: llm"), delivered)
    }

    @Test
    fun `알림을 묶어 보내도 상태 변화는 대상마다 남긴다`(@TempDir directory: Path) {
        val store = store(directory)
        val round = round(definitions("backend", "backend-deps"), store = store)
        unhealthy += listOf("backend", "backend-deps")
        round.runTimes(3)
        unhealthy -= "backend"
        round.runTimes(1)
        assertEquals(1, inbox.received.count { it.title.startsWith("응답 없음") })
        val changes = store.changesSince(Instant.parse("2026-11-19T00:00:00Z"))
        assertEquals(listOf("backend" to MonitorStatus.DOWN, "backend-deps" to MonitorStatus.DOWN, "backend" to MonitorStatus.UP),
            changes.map { it.monitor to it.status })
        assertEquals(30L, changes.last().downSeconds)
    }

    @Test
    fun `모든 채널이 실패한 알림은 재시작 뒤에도 기록에서 되살려 다시 보낸다`(@TempDir directory: Path) {
        val store = store(directory)
        val dead = object : NotificationChannel {
            override val name = "discord"
            override fun send(notification: Notification) = throw IllegalStateException("discord down")
        }
        unhealthy += "llm"
        round(definitions("llm"), store = store, notifier = FallbackNotifier(listOf(dead), pause = {}, worker = { it.run() })).runTimes(3)
        assertNull(store.recent(1).single().deliveredBy)

        val restarted = round(definitions("llm"), store = store)                    // 재시작 — 채널이 돌아왔다
        restarted.runTimes(1)
        assertEquals(listOf("응답 없음: llm"), inbox.received.map { it.title })
        assertEquals("discord", store.recent(1).single().deliveredBy)
        restarted.runTimes(2)
        assertEquals(1, inbox.received.size, "되살린 알림을 또 보냈다")
    }

    @Test
    fun `재시작해도 이미 알린 장애를 다시 알리지 않고 복구는 원래 시각부터 센다`(@TempDir directory: Path) {
        val store = store(directory)
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
