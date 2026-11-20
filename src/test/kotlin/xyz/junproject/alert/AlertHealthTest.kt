package xyz.junproject.alert

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import xyz.junproject.alert.event.EventIntake
import xyz.junproject.alert.monitor.ActiveSlot
import xyz.junproject.alert.monitor.MonitorDefinition
import xyz.junproject.alert.monitor.MonitorProbe
import xyz.junproject.alert.monitor.MonitorRound
import xyz.junproject.alert.monitor.MonitorTracker
import xyz.junproject.alert.monitor.ProbeResult
import xyz.junproject.alert.notify.FallbackNotifier
import xyz.junproject.alert.notify.Notification
import xyz.junproject.alert.notify.NotificationChannel
import java.time.Instant

/** 헬스가 "아무도 감시하지 않는 상태"를 정상으로 보이게 하지 않는가 */
class AlertHealthTest {
    private var serving: String? = "blue"
    private val channel = object : NotificationChannel {
        override val name = "discord"
        override fun send(notification: Notification) {}
    }
    private val notifier = FallbackNotifier(listOf(channel), pause = {}, worker = { it.run() })
    private val round = MonitorRound(listOf(MonitorDefinition("llm", "http://unused/")), MonitorProbe { ProbeResult.HEALTHY },
        MonitorTracker(), notifier, null, activeSlot = ActiveSlot("blue", { serving }))
    private val controller = AlertController(round, EventIntake(notifier, null), notifier, StoreHolder(null), "secret")
    private fun health(at: Instant = Instant.now()) = controller.health(at).let { it.statusCode.value() to it.body!!["status"] }

    @Test
    fun `활성이고 방금 한 바퀴를 돌았으면 ok`() {
        round.run()
        assertEquals(200 to "ok", health())
    }

    @Test
    fun `대기 자리는 200 이지만 standby 라고 밝힌다 - 정리 알림 직후에도`() {
        serving = "green"
        round.run()
        assertEquals(200 to "standby", health())
        serving = "blue"; round.run()
        controller.drain()
        assertEquals(200 to "standby", health())
        round.run()                                   // 프록시는 여전히 blue → 다시 맡는다
        assertEquals(200 to "ok", health())
    }

    @Test
    fun `활성인데 바퀴가 2분 넘게 돌지 않으면 503 stalled`() {
        round.run()
        assertEquals(503 to "stalled", health(Instant.now().plusSeconds(180)))
    }

    @Test
    fun `첫 바퀴 전에는 starting 이지만 2분이 지나도 못 돌면 503`() {
        val neverRan = AlertController(
            MonitorRound(listOf(MonitorDefinition("llm", "http://unused/")), MonitorProbe { ProbeResult.HEALTHY }, MonitorTracker(), notifier, null),
            EventIntake(notifier, null), notifier, StoreHolder(null), "secret")
        assertEquals(200, neverRan.health(Instant.now()).statusCode.value())
        assertEquals("starting", neverRan.health(Instant.now()).body!!["status"])
        assertEquals(503, neverRan.health(Instant.now().plusSeconds(180)).statusCode.value())
    }
}
