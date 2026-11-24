package xyz.junproject.alert.settings

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import tools.jackson.databind.ObjectMapper
import xyz.junproject.alert.incident.Incident
import xyz.junproject.alert.incident.IncidentStore
import xyz.junproject.alert.incident.MonitorChange
import xyz.junproject.alert.monitor.MonitorState
import xyz.junproject.alert.monitor.MonitorStatus
import xyz.junproject.alert.notify.DiscordChannel
import xyz.junproject.alert.notify.MailChannel
import xyz.junproject.alert.notify.NotificationChannel
import xyz.junproject.alert.notify.SlackChannel
import java.net.ServerSocket
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/** 시험 발송 — 채널마다 따로 · 가린 실패 사유 · 간격 제한(두 자리 흉내) · 사건 · 상태에 섞이지 않음 */
@ExtendWith(OutputCaptureExtension::class)
class TestSendTest {
    private val mapper = ObjectMapper()
    private val discord = FakeWebhookReceiver(SettingsMarkers.DISCORD_PATH, okStatus = 204)
    private val slack = FakeWebhookReceiver(SettingsMarkers.SLACK_PATH)
    private val mail = RecordingMailSender()
    private var now: Instant = Instant.parse("2026-11-25T01:15:30Z")
    private val interval = Duration.ofSeconds(30)

    @AfterEach
    fun stop() { discord.close(); slack.close() }

    private fun channels(timeout: Duration = Duration.ofSeconds(2), discordUrl: String = discord.url): List<NotificationChannel> = listOf(
        DiscordChannel(discordUrl, mapper, timeout),
        SlackChannel(slack.url, mapper, timeout),
        MailChannel(mail, SettingsMarkers.MAIL_FROM, SettingsMarkers.MAIL_TO),
    )

    private fun testSend(store: IncidentStore? = null, channels: List<NotificationChannel> = channels()) =
        TestSend(channels, store, interval) { now }

    /** 두 자리가 같은 파일을 여는 것과 같은 꼴 — 연결을 따로 연다. [broken] 을 켜면 그 뒤로 DB 를 쓸 수 없다. */
    private class BreakableDataSource(private val target: DataSource) : DataSource by target {
        @Volatile var broken = false
        override fun getConnection(): Connection = if (broken) throw SQLException("database unavailable") else target.connection
    }

    private fun open(directory: Path, dataSource: BreakableDataSource = dataSource(directory)) = IncidentStore(JdbcTemplate(dataSource))
    private fun dataSource(directory: Path) =
        BreakableDataSource(SingleConnectionDataSource("jdbc:sqlite:${directory.resolve("alert.db")}", true))

    private fun sent(result: TestSendResult): TestSendResult.Sent {
        assertInstanceOf(TestSendResult.Sent::class.java, result, "보내야 할 때 보내지 않았다: $result")
        return result as TestSendResult.Sent
    }

    private fun tooSoon(result: TestSendResult): TestSendResult.TooSoon {
        assertInstanceOf(TestSendResult.TooSoon::class.java, result, "간격 안인데 보냈다: $result")
        return result as TestSendResult.TooSoon
    }

    private fun receivedCounts() = listOf(discord.bodies.size, slack.bodies.size, mail.sent.size)

    private fun assertMaskedFailure(outcome: ChannelOutcome) {
        assertFalse(outcome.delivered, "${outcome.channel} 는 실패여야 한다")
        val reason = outcome.reason
        assertNotNull(reason, "${outcome.channel} 실패에 사유가 없다")
        assertTrue(SettingsMarkers.maskedReason.matches(reason!!), "${outcome.channel} 의 사유가 가린 꼴이 아니다: '$reason'")
        assertEquals(emptyList<String>(), SettingsMarkers.leaks(reason, listOf("127.0.0.1", discord.port.toString(), slack.port.toString())))
    }

    @Test
    fun `설정된 채널마다 따로 한 번씩 보내고 채널 순서대로 결과를 답한다`(@TempDir directory: Path) {
        val result = sent(testSend(open(directory)).send("jun"))
        assertEquals(listOf("discord", "slack", "mail"), result.outcomes.map { it.channel })
        assertTrue(result.outcomes.all { it.delivered && it.reason == null }, "${result.outcomes}")
        // 앞 채널이 성공해도 뒤 채널에 보낸다(평소 발송의 '앞이 되면 멈춤'과 다르다) · 채널마다 한 번
        assertEquals(listOf(1, 1, 1), receivedCounts())
        assertEquals(RateLimitScope.ALL_SLOTS, result.limitScope)
    }

    @Test
    fun `시험 알림의 글은 시험임이 분명하고 일으킨 이름과 시각(KST)을 싣는다`() {
        sent(testSend().send("jun-admin"))
        val shownAt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Seoul")).format(now)   // 2026-11-25 10:15:30
        val discordText = mapper.readTree(discord.bodies.single()).path("content").asString()
        val slackText = mapper.readTree(slack.bodies.single()).path("text").asString()
        val mailMessage = mail.sent.single()
        for ((channel, text) in listOf("discord" to discordText, "slack" to slackText, "mail" to "${mailMessage.subject}\n${mailMessage.text}")) {
            assertTrue(text.contains("시험"), "$channel 글에 '시험'이 없다: $text")
            assertTrue(text.contains("jun-admin"), "$channel 글에 일으킨 이름이 없다: $text")
            assertTrue(text.contains(shownAt), "$channel 글에 시각($shownAt)이 없다: $text")
            assertFalse(text.contains("[장애]") || text.contains("[경고]"), "$channel 시험 알림이 장애 · 경고처럼 보인다: $text")
        }
        assertTrue(mailMessage.subject!!.contains("시험"), "메일 제목에 '시험'이 없다: ${mailMessage.subject}")
    }

    @Test
    fun `한 채널이 오류 상태로 답해도 나머지는 보내지고 사유는 상태 코드뿐이다`(output: CapturedOutput) {
        discord.mode = FakeWebhookReceiver.Mode.ERROR
        val result = sent(testSend().send("jun"))
        val (discordOutcome, slackOutcome, mailOutcome) = result.outcomes
        assertMaskedFailure(discordOutcome)
        assertEquals("status 500", discordOutcome.reason)
        assertTrue(slackOutcome.delivered && mailOutcome.delivered, "${result.outcomes}")
        assertEquals(listOf(1, 1, 1), receivedCounts(), "실패한 채널을 다시 시도했거나 나머지를 건너뛰었다")
        assertEquals(emptyList<String>(), SettingsMarkers.leaks(output.toString()), "로그에 주소 · 응답 본문이 나왔다")
    }

    @Test
    fun `한 채널의 연결이 거부돼도 나머지는 보내지고 사유에 주소가 없다`(output: CapturedOutput) {
        val closedPort = ServerSocket(0).use { it.localPort }
        val refusedUrl = "http://127.0.0.1:$closedPort${SettingsMarkers.DISCORD_PATH}"
        val result = sent(testSend(channels = channels(discordUrl = refusedUrl)).send("jun"))
        assertMaskedFailure(result.outcomes[0])
        assertFalse(result.outcomes[0].reason!!.contains(closedPort.toString()))
        assertTrue(result.outcomes[1].delivered && result.outcomes[2].delivered, "${result.outcomes}")
        assertEquals(emptyList<String>(), SettingsMarkers.leaks(output.toString(), listOf(":$closedPort")), "로그에 주소가 나왔다")
    }

    @Test
    fun `한 채널이 응답하지 않아도 그 채널의 시간 제한 안에 끝나고 나머지는 보내진다`() {
        slack.mode = FakeWebhookReceiver.Mode.STALL
        val started = System.nanoTime()
        val result = sent(testSend(channels = channels(timeout = Duration.ofMillis(500))).send("jun"))
        val elapsed = Duration.ofNanos(System.nanoTime() - started)
        assertTrue(elapsed < Duration.ofSeconds(4), "응답 없는 채널에 매달렸다: $elapsed")
        assertMaskedFailure(result.outcomes[1])
        assertTrue(result.outcomes[0].delivered && result.outcomes[2].delivered, "${result.outcomes}")
        assertEquals(1, slack.bodies.size, "시험 발송은 채널마다 한 번만 시도한다")
    }

    @Test
    fun `메일 발신이 실패해도 사유에 메일 서버 · 계정 · 비밀번호가 없다`(output: CapturedOutput) {
        mail.failing = true
        val result = sent(testSend().send("jun"))
        assertMaskedFailure(result.outcomes[2])
        assertTrue(result.outcomes[0].delivered && result.outcomes[1].delivered)
        assertEquals(emptyList<String>(), SettingsMarkers.leaks(output.toString()), "로그에 메일 서버 · 계정이 나왔다")
    }

    @Test
    fun `30초 안의 둘째 요청은 보내지 않고 거절하며 30초가 지나면 다시 된다`(@TempDir directory: Path) {
        val testSend = testSend(open(directory))
        sent(testSend.send("jun"))
        now = now.plusSeconds(29)
        val rejected = tooSoon(testSend.send("jun"))
        assertEquals(listOf(1, 1, 1), receivedCounts(), "거절한 요청이 보냈다")
        assertTrue(rejected.retryAfter > Duration.ZERO && rejected.retryAfter <= interval, "${rejected.retryAfter}")
        now = now.plusSeconds(1)                     // 첫 발송에서 정확히 30초 — 거절한 요청이 창을 늘리지 않는다
        sent(testSend.send("jun"))
        assertEquals(listOf(2, 2, 2), receivedCounts())
    }

    @Test
    fun `두 자리가 같은 DB 를 보면 한쪽에서 보낸 직후 다른 쪽이 거절한다`(@TempDir directory: Path) {
        val blue = testSend(open(directory))
        val green = testSend(open(directory))        // 같은 파일을 따로 여는 둘째 자리
        assertEquals(RateLimitScope.ALL_SLOTS, sent(blue.send("jun")).limitScope)
        now = now.plusSeconds(10)
        val rejected = tooSoon(green.send("jun"))
        assertEquals(RateLimitScope.ALL_SLOTS, rejected.limitScope)
        assertEquals(listOf(1, 1, 1), receivedCounts(), "다른 자리가 간격 안에 또 보냈다")
        now = now.plusSeconds(20)
        sent(green.send("jun"))
        now = now.plusSeconds(10)
        tooSoon(blue.send("jun"))                    // 이번엔 green 이 보낸 것을 blue 가 본다
        assertEquals(listOf(2, 2, 2), receivedCounts())
    }

    @Test
    fun `보내는 중에 온 둘째 요청은 보내지 않고 거절한다`(@TempDir directory: Path) {
        discord.mode = FakeWebhookReceiver.Mode.STALL
        val testSend = testSend(open(directory), channels(timeout = Duration.ofSeconds(3)))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit<TestSendResult> { testSend.send("jun") }
            assertTrue(discord.awaitArrival(), "첫 요청이 디스코드에 닿지 않았다")
            tooSoon(testSend.send("jun"))            // 첫 발송이 아직 끝나지 않았다
            discord.releaseStalled()
            sent(first.get(10, TimeUnit.SECONDS))
            assertEquals(1, discord.bodies.size, "보내는 중에 또 보냈다")
        } finally {
            discord.releaseStalled(); executor.shutdownNow()
        }
    }

    @Test
    fun `기록 저장소가 없으면 자리 안에서만 제한하고 그렇다고 답한다`() {
        val testSend = testSend(store = null)
        assertEquals(RateLimitScope.THIS_SLOT, sent(testSend.send("jun")).limitScope)
        now = now.plusSeconds(10)
        assertEquals(RateLimitScope.THIS_SLOT, tooSoon(testSend.send("jun")).limitScope)
        now = now.plusSeconds(20)
        sent(testSend.send("jun"))
        assertEquals(listOf(2, 2, 2), receivedCounts())
    }

    @Test
    fun `DB 를 쓸 수 없게 되면 자리 안의 기억으로 제한을 잇고 범위를 이 자리로 밝힌다`(@TempDir directory: Path) {
        val dataSource = dataSource(directory)
        val testSend = testSend(open(directory, dataSource))
        assertEquals(RateLimitScope.ALL_SLOTS, sent(testSend.send("jun")).limitScope)
        dataSource.broken = true
        now = now.plusSeconds(10)
        tooSoon(testSend.send("jun"))                // DB 를 못 읽어도 이 자리가 보낸 것은 기억한다
        now = now.plusSeconds(20)
        val again = sent(testSend.send("jun"))
        assertEquals(RateLimitScope.THIS_SLOT, again.limitScope)
        assertTrue(again.outcomes.all { it.delivered }, "DB 가 없다고 발송을 막았다: ${again.outcomes}")
    }

    @Test
    fun `시험 발송은 사건 · 감시 상태 · 상태 변화 기록을 바꾸지 않는다 - 실패한 채널이 있어도`(@TempDir directory: Path) {
        val store = open(directory)
        store.record(Incident(now.minusSeconds(600), "monitor_down", "llm", "응답 없음: llm", "status 503", "discord", severity = "CRITICAL"))
        val downState = MonitorState(MonitorStatus.DOWN, 3, now.minusSeconds(600), now.minusSeconds(600), "status 503")
        store.saveState("llm", downState, now.minusSeconds(600))
        store.recordChange(MonitorChange(now.minusSeconds(600), "llm", MonitorStatus.DOWN, null))
        val raw = JdbcTemplate(SingleConnectionDataSource("jdbc:sqlite:${directory.resolve("alert.db")}", true))
        fun snapshot() = listOf(
            store.recent(100), store.loadDownStates(), store.changesSince(Instant.EPOCH),
            raw.queryForList("SELECT * FROM monitor_state ORDER BY name"),
        )
        val before = snapshot()

        discord.mode = FakeWebhookReceiver.Mode.ERROR
        mail.failing = true
        sent(testSend(store).send("jun"))

        assertEquals(before, snapshot(), "시험 발송이 사건 · 상태 기록을 바꿨다")
        assertEquals(1, store.recent(100).size)
        assertEquals(downState, store.loadDownStates()["llm"], "다시 알림 시각 · 상태가 바뀌었다")
    }

    @Test
    fun `시계가 뒤로 가면 마지막 시각을 낡은 값으로 보고 허용한다 - DB 가 있을 때`(@TempDir directory: Path) {
        val testSend = testSend(open(directory))
        sent(testSend.send("jun"))
        now = now.minus(Duration.ofHours(1))         // 시계가 한 시간 뒤로 — 마지막 시각이 미래가 됐다
        sent(testSend.send("jun"))
        now = now.plusSeconds(10)
        tooSoon(testSend.send("jun"))                // 허용한 뒤로는 다시 간격을 센다
        assertEquals(listOf(2, 2, 2), receivedCounts())
    }

    @Test
    fun `시계가 뒤로 가면 마지막 시각을 낡은 값으로 보고 허용한다 - DB 가 없을 때`() {
        val testSend = testSend(store = null)
        sent(testSend.send("jun"))
        now = now.minus(Duration.ofHours(1))
        assertEquals(RateLimitScope.THIS_SLOT, sent(testSend.send("jun")).limitScope)
        now = now.plusSeconds(10)
        tooSoon(testSend.send("jun"))
        assertEquals(listOf(2, 2, 2), receivedCounts())
    }

    @Test
    fun `DB 장애 중에 보낸 직후 DB 가 돌아와도 30초 안의 둘째 요청은 거절한다`(@TempDir directory: Path) {
        val dataSource = dataSource(directory)
        val testSend = testSend(open(directory, dataSource))
        sent(testSend.send("jun"))                   // DB 에 남은 마지막 시각 — 곧 오래된 값이 된다
        now = now.plus(Duration.ofHours(1))
        dataSource.broken = true
        assertEquals(RateLimitScope.THIS_SLOT, sent(testSend.send("jun")).limitScope)   // 장애 중 — 이 자리의 기억에만 남는다
        now = now.plusSeconds(10)
        dataSource.broken = false                    // DB 가 돌아왔다(DB 의 마지막 시각은 한 시간 전)
        tooSoon(testSend.send("jun"))
        assertEquals(listOf(2, 2, 2), receivedCounts(), "DB 가 돌아오자마자 간격 안에 또 보냈다")
        now = now.plusSeconds(20)
        sent(testSend.send("jun"))
    }
}
