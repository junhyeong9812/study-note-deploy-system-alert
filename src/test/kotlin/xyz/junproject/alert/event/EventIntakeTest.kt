package xyz.junproject.alert.event

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

class EventIntakeTest {
    private val received = mutableListOf<Notification>()
    private val channel = object : NotificationChannel {
        override val name = "discord"
        override fun send(notification: Notification) { received += notification }
    }
    private var now: Instant = Instant.parse("2026-11-20T00:00:00Z")
    private fun event(key: String = "deploy-failed:llm", title: String = "배포 실패 llm") =
        AlertEvent("ci-cd", "deploy_failed", Notification.Severity.WARNING, key, title, "단계 ci_test")

    @Test
    fun `같은 key 는 10분 안에 한 번만 발송하고 접수는 전부 기록한다`(@TempDir directory: Path) {
        val store = IncidentStore(JdbcTemplate(SingleConnectionDataSource("jdbc:sqlite:${directory.resolve("alert.db")}", true)))
        val intake = EventIntake(FallbackNotifier(listOf(channel), pause = {}, worker = { it.run() }), store, Duration.ofMinutes(10)) { now }

        assertTrue(intake.accept(event()))
        now = now.plusSeconds(60)
        assertFalse(intake.accept(event()), "같은 일이 1분 뒤에 또 발송됐다")
        assertTrue(intake.accept(event(key = "deploy-failed:front", title = "배포 실패 front")), "다른 key 는 발송돼야 한다")
        now = now.plusSeconds(600)
        assertTrue(intake.accept(event()), "10분이 지나면 다시 발송돼야 한다")

        assertEquals(listOf("[ci-cd] 배포 실패 llm", "[ci-cd] 배포 실패 front", "[ci-cd] 배포 실패 llm"), received.map { it.title })
        val recorded = store.recent(10).reversed()
        assertEquals(4, recorded.size)
        assertEquals(listOf(false, true, false, false), recorded.map { it.suppressed })
        assertEquals(listOf("discord", null, "discord", "discord"), recorded.map { it.deliveredBy })
    }

    @Test
    fun `자리가 바뀌어도(새 프로세스) 같은 key 의 억제가 기록으로 이어진다`(@TempDir directory: Path) {
        val store = IncidentStore(JdbcTemplate(SingleConnectionDataSource("jdbc:sqlite:${directory.resolve("alert.db")}", true)))
        val notifier = FallbackNotifier(listOf(channel), pause = {}, worker = { it.run() })
        assertTrue(EventIntake(notifier, store, Duration.ofMinutes(10)) { now }.accept(event()))
        now = now.plusSeconds(120)
        val afterSwitch = EventIntake(notifier, store, Duration.ofMinutes(10)) { now }      // 메모리가 비어 있는 새 자리
        assertFalse(afterSwitch.accept(event()), "자리가 바뀌었다고 2분 만에 다시 발송했다")
        now = now.plusSeconds(600)
        assertTrue(afterSwitch.accept(event()))
        assertEquals(2, received.size)
    }

    @Test
    fun `기록 저장소가 없어도 발송한다`() {
        val intake = EventIntake(FallbackNotifier(listOf(channel), pause = {}, worker = { it.run() }), store = null) { now }
        assertTrue(intake.accept(event()))
        assertEquals(1, received.size)
    }
}
