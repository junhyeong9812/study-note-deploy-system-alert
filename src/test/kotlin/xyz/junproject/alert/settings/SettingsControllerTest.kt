package xyz.junproject.alert.settings

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import xyz.junproject.alert.StoreHolder
import xyz.junproject.alert.incident.Incident
import xyz.junproject.alert.monitor.ActiveSlot
import xyz.junproject.alert.notify.NotificationChannel
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 설정 보기(`GET /settings`)와 시험 발송(`POST /settings/test-send`) — HTTP 경계에서.
 * 채널은 진짜 디스코드 · 슬랙 채널 부품이 로컬 가짜 받는 쪽으로, 메일은 가짜 발신으로 보낸다(실제 발송 없음).
 * 시험 발송의 시계와 자리 판정은 테스트가 주입한다(@Primary 빈).
 */
@SpringBootTest(properties = [
    "alert.monitors-file=classpath:monitors-settings-test.yml",
    "alert.round-initial-delay-ms=3600000",      // 감시 바퀴가 돌지 않게(대상 주소는 표식일 뿐이다)
    "alert.round-interval-ms=45000",             // 규칙의 '지금 값'을 코드 기본값과 다르게 — 설정을 읽어 내는지 본다
    "alert.summary-cron=0 0 8 * * *",
])
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)   // 실패 때 요청 머리글(비밀값)을 찍어 다음 테스트의 로그 검사를 흐리지 않게
@ExtendWith(OutputCaptureExtension::class)
class SettingsControllerTest {
    @TestConfiguration
    class Fakes {
        @Bean fun recordingMailSender(): JavaMailSender = mail

        @Bean @Primary
        fun testActiveSlot() = ActiveSlot("blue", { servingSlot })

        @Bean @Primary
        fun clockedTestSend(channels: List<NotificationChannel>, storeHolder: StoreHolder) =
            TestSend(channels, storeHolder.store, Duration.ofSeconds(30)) { now }
    }

    companion object {
        const val SETTINGS_HEADER = "X-Alert-Settings-Secret"
        const val REQUESTED_BY_LIMIT = 200


        val discord = FakeWebhookReceiver(SettingsMarkers.DISCORD_PATH, okStatus = 204)
        val slack = FakeWebhookReceiver(SettingsMarkers.SLACK_PATH)
        val mail = RecordingMailSender()
        @Volatile var servingSlot: String? = "blue"
        @Volatile var now: Instant = Instant.parse("2026-11-25T01:15:30Z")
        private val dataPath = Files.createTempDirectory("alert-settings-test").resolve("alert.db")   // 실행마다 새 파일 — 지난 실행의 간격 기록이 남지 않게

        @JvmStatic
        @DynamicPropertySource
        fun receivers(registry: DynamicPropertyRegistry) {
            registry.add("alert.secret") { SettingsMarkers.ALERT_SECRET }
            registry.add("alert.settings-secret") { SettingsMarkers.SETTINGS_SECRET }
            registry.add("alert.discord-webhook-url") { discord.url }
            registry.add("alert.slack-webhook-url") { slack.url }
            registry.add("alert.mail-from") { SettingsMarkers.MAIL_FROM }
            registry.add("alert.mail-to") { SettingsMarkers.MAIL_TO }
            registry.add("spring.mail.host") { SettingsMarkers.MAIL_HOST }
            registry.add("spring.mail.username") { SettingsMarkers.MAIL_USERNAME }
            registry.add("spring.mail.password") { SettingsMarkers.MAIL_PASSWORD }
            registry.add("alert.data-path") { dataPath.toString() }
        }
    }

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var storeHolder: StoreHolder
    @Autowired lateinit var activeSlot: ActiveSlot
    private val mapper = ObjectMapper()

    @BeforeEach
    fun clean() {
        discord.reset(); slack.reset(); mail.reset()
        servingSlot = "blue"; activeSlot.refresh()
        now = now.plus(Duration.ofMinutes(1))          // 테스트마다 간격 밖에서 시작한다
    }

    private data class Reply(val status: Int, val text: String)

    private fun call(request: MockHttpServletRequestBuilder): Reply {
        val response = mockMvc.perform(request).andReturn().response
        return Reply(response.status, response.getContentAsString(Charsets.UTF_8))
    }

    private fun json(reply: Reply): JsonNode = mapper.readTree(reply.text)

    private fun viewSettings(secret: String? = SettingsMarkers.SETTINGS_SECRET, headerName: String = SETTINGS_HEADER) =
        call(get("/settings").apply { secret?.let { header(headerName, it) } })

    private fun testSend(body: String = """{"requested_by":"jun"}""", secret: String? = SettingsMarkers.SETTINGS_SECRET,
                         headerName: String = SETTINGS_HEADER) =
        call(post("/settings/test-send").contentType(MediaType.APPLICATION_JSON).content(body)
            .apply { secret?.let { header(headerName, it) } })

    private fun requestedBy(name: String) = mapper.writeValueAsString(mapOf("requested_by" to name))

    private fun receivedCounts() = listOf(discord.bodies.size, slack.bodies.size, mail.sent.size)

    private fun results(reply: Reply): List<JsonNode> {
        assertEquals(200, reply.status, reply.text)
        val results = json(reply).path("data").path("results")
        return (0 until results.size()).map { results.get(it) }
    }

    private fun assertDelivered(result: JsonNode) {
        assertTrue(result.path("delivered").asBoolean(), "$result")
        assertTrue(result.path("reason").isMissingNode || result.path("reason").isNull, "성공에 사유가 실렸다: $result")
    }

    private fun assertMaskedFailure(result: JsonNode) {
        assertFalse(result.path("delivered").asBoolean(), "$result")
        val reason = result.path("reason").asString()
        assertTrue(SettingsMarkers.maskedReason.matches(reason), "사유가 가린 꼴이 아니다: '$reason'")
    }

    private val forbiddenInResponses: List<String>
        get() = listOf("127.0.0.1", discord.port.toString(), slack.port.toString(), "203.0.113.77", "localhost",
            "47613", "47614", "47615", ".invalid") + listOfNotNull(System.getenv("HOME")?.takeIf { it.length > 1 })

    private fun shownAt(instant: Instant) =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Seoul")).format(instant)

    // ---------------------------------------------------------------- 인증

    @Test
    fun `설정 보기와 시험 발송은 새 비밀값으로 열린다`() {
        assertEquals(200, viewSettings().status)
        assertEquals(200, testSend().status)
    }

    @Test
    fun `여럿이 쓰는 ALERT_SECRET 으로는 두 경로가 열리지 않는다`() {
        for (headerName in listOf("X-Alert-Secret", SETTINGS_HEADER)) {
            assertEquals(401, viewSettings(SettingsMarkers.ALERT_SECRET, headerName).status, headerName)
            assertEquals(401, testSend(secret = SettingsMarkers.ALERT_SECRET, headerName = headerName).status, headerName)
        }
        assertEquals(401, viewSettings(SettingsMarkers.SETTINGS_SECRET, "X-Alert-Secret").status, "새 비밀값은 새 머리글로만")
        assertEquals(listOf(0, 0, 0), receivedCounts())
    }

    @Test
    fun `틀린 값 · 없는 값 · 빈 값의 응답이 같고 거절된 요청은 보내지도 간격을 쓰지도 않는다`() {
        val refusedView = listOf(
            viewSettings(null), viewSettings("wrong"), viewSettings(""), viewSettings("   "),
            viewSettings(SettingsMarkers.SETTINGS_SECRET + "x"), viewSettings(SettingsMarkers.SETTINGS_SECRET.dropLast(1)),
        )
        val refusedSend = listOf(
            testSend(secret = null), testSend(secret = "wrong"), testSend(secret = ""),
            testSend(body = """{"requested_by":""}""", secret = null),          // 인증이 입력 검사보다 먼저
        )
        for (reply in refusedView + refusedSend) assertEquals(401, reply.status, reply.text)
        assertEquals(1, (refusedView + refusedSend).map { it.text }.toSet().size, "거절 응답이 서로 다르다: ${(refusedView + refusedSend).map { it.text }.toSet()}")
        assertEquals("unauthorized", json(refusedView[0]).path("error").path("code").asString())
        assertEquals(listOf(0, 0, 0), receivedCounts(), "거절한 요청이 보냈다")
        assertEquals(200, testSend().status, "거절된 요청이 간격을 차지했다")
    }

    // ---------------------------------------------------------------- 설정 보기

    @Test
    fun `설정 보기는 감시 대상 · 채널 · 규칙의 지금 값과 맡은 자리 여부를 낸다`() {
        val reply = viewSettings()
        assertEquals(200, reply.status, reply.text)
        val root = json(reply)
        assertTrue(root.path("success").asBoolean())
        val data = root.path("data")

        val monitors = data.path("monitors")
        assertEquals(listOf("front", "backend-deps", "llm"), (0 until monitors.size()).map { monitors.get(it).path("name").asString() })
        assertEquals(listOf("status", "components", "status"), (0 until monitors.size()).map { monitors.get(it).path("expect").asString() })
        assertEquals(listOf("/", "/api/health/dependencies", "\${HOME}/health"),
            (0 until monitors.size()).map { monitors.get(it).path("address").asString() })

        val channels = data.path("channels")
        assertEquals(listOf("discord", "slack", "mail"), (0 until channels.size()).map { channels.get(it).path("kind").asString() })
        assertEquals(listOf(true, true, true), (0 until channels.size()).map { channels.get(it).path("configured").asBoolean() })
        assertEquals(listOf(1, 2, 3), (0 until channels.size()).map { channels.get(it).path("order").asInt() })

        val rules = data.path("rules")
        assertEquals(3, rules.path("failure_threshold").asInt())
        assertEquals(3600, rules.path("remind_every_seconds").asInt())
        assertEquals(600, rules.path("event_suppress_seconds").asInt())
        assertEquals(45, rules.path("round_interval_seconds").asInt())
        assertEquals("0 0 8 * * *", rules.path("summary_cron").asString())
        assertEquals("Asia/Seoul", rules.path("summary_zone").asString())

        assertTrue(data.path("active").isBoolean, "active 가 없다: $data")
        assertTrue(data.path("active").asBoolean(), "프록시가 이 자리로 보내는데 맡지 않았다고 한다")
    }

    @Test
    fun `설정 보기 응답 전문에 비밀값 · 호스트 · IP · 포트가 없다 - 일부와 길이도`() {
        val reply = viewSettings()
        assertEquals(200, reply.status, reply.text)
        assertEquals(emptyList<String>(), SettingsMarkers.leaks(reply.text, forbiddenInResponses), reply.text)
        val secretLengths = (SettingsMarkers.secretValues + listOf(discord.url, slack.url, SettingsMarkers.MAIL_HOST))
            .map { it.length.toLong() }.toSet()
        val numbers = SettingsMarkers.numbersIn(json(reply))
        assertEquals(emptyList<Long>(), numbers.filter { it in secretLengths }, "비밀값의 길이로 보이는 숫자가 있다: $numbers")
    }

    @Test
    fun `설정 보기는 아무것도 바꾸지 않는다`() {
        val store = storeHolder.store!!
        store.record(Incident(now, "event", "settings-view-test", "기록 하나", "", "discord"))
        val monitorsBefore = call(get("/monitors").header("X-Alert-Secret", SettingsMarkers.ALERT_SECRET)).text
        val incidentsBefore = store.recent(1000)
        repeat(3) { assertEquals(200, viewSettings().status) }
        assertEquals(incidentsBefore, store.recent(1000))
        assertEquals(monitorsBefore, call(get("/monitors").header("X-Alert-Secret", SettingsMarkers.ALERT_SECRET)).text)
        assertEquals(listOf(0, 0, 0), receivedCounts(), "보기가 무언가를 보냈다")
    }

    // ---------------------------------------------------------------- 시험 발송

    @Test
    fun `시험 발송은 설정된 채널마다 따로 보내고 채널별 결과와 간격 제한의 범위를 답한다`() {
        val reply = testSend()
        val results = results(reply)
        assertEquals(listOf("discord", "slack", "mail"), results.map { it.path("channel").asString() })
        results.forEach(::assertDelivered)
        assertEquals(listOf(1, 1, 1), receivedCounts())
        val rateLimit = json(reply).path("data").path("rate_limit")
        assertEquals(30, rateLimit.path("interval_seconds").asInt())
        assertEquals("all_slots", rateLimit.path("scope").asString())
    }

    @Test
    fun `시험 알림의 글에 시험과 일으킨 이름과 시각이 있다`() {
        results(testSend(requestedBy("jun-admin"))).forEach(::assertDelivered)
        val discordText = mapper.readTree(discord.bodies.single()).path("content").asString()
        val slackText = mapper.readTree(slack.bodies.single()).path("text").asString()
        val mailText = mail.sent.single().let { "${it.subject}\n${it.text}" }
        for (text in listOf(discordText, slackText, mailText)) {
            assertTrue(text.contains("시험") && text.contains("jun-admin") && text.contains(shownAt(now)), text)
        }
    }

    @Test
    fun `한 채널이 실패해도 나머지는 보내지고 사유는 가린 꼴이며 응답과 로그에 주소 · 본문이 없다`(output: CapturedOutput) {
        discord.mode = FakeWebhookReceiver.Mode.ERROR
        mail.failing = true
        val reply = testSend()
        val (discordResult, slackResult, mailResult) = results(reply)
        assertMaskedFailure(discordResult)
        assertEquals("status 500", discordResult.path("reason").asString())
        assertDelivered(slackResult)
        assertMaskedFailure(mailResult)
        assertEquals(listOf(1, 1, 0), receivedCounts())
        assertEquals(emptyList<String>(), SettingsMarkers.leaks(reply.text, forbiddenInResponses), reply.text)
        assertEquals(emptyList<String>(), SettingsMarkers.leaks(output.toString(), listOf(discord.url, slack.url)), "로그에 새었다")
    }

    @Test
    fun `모든 채널이 실패해도 미발송 보관함 · 사건 기록 · 감시 상태에 섞이지 않는다`() {
        val store = storeHolder.store!!
        store.record(Incident(now, "event", "settings-test-send", "기록 하나", "", "discord"))
        val monitorsBefore = call(get("/monitors").header("X-Alert-Secret", SettingsMarkers.ALERT_SECRET)).text
        val incidentsBefore = store.recent(1000)
        val changesBefore = store.changesSince(Instant.EPOCH)
        val downStatesBefore = store.loadDownStates()

        discord.mode = FakeWebhookReceiver.Mode.ERROR
        slack.mode = FakeWebhookReceiver.Mode.ERROR
        mail.failing = true
        results(testSend()).forEach(::assertMaskedFailure)

        val monitorsAfter = call(get("/monitors").header("X-Alert-Secret", SettingsMarkers.ALERT_SECRET))
        assertEquals(monitorsBefore, monitorsAfter.text, "감시 상태 · 미발송 수가 바뀌었다")
        assertEquals(0, json(monitorsAfter).path("data").path("undelivered").asInt(), "시험 알림이 미발송 보관함에 들어갔다(나중에 진짜 알림처럼 다시 나간다)")
        assertEquals(incidentsBefore, store.recent(1000), "시험 발송이 사건을 남겼다")
        assertEquals(changesBefore, store.changesSince(Instant.EPOCH))
        assertEquals(downStatesBefore, store.loadDownStates())
    }

    @Test
    fun `요청의 이름은 멘션 · 링크 · 줄바꿈으로 읽히지 않는다`() {
        val hostile = "jun @everyone @here <@123456789012345678> <@&987654321098765432> <!channel> <!here> <@U024BE7LH> " +
            "[클릭](https://evil.example/x) <https://evil.example/y|여기>\n[장애] backend 응답 없음"
        val reply = testSend(requestedBy(hostile))
        results(reply).forEach(::assertDelivered)

        // 디스코드 — allowed_mentions 로 멘션을 끄든 글자를 무력화하든, 받는 쪽에서 멘션이 일어나지 않아야 한다
        val discordPayload = mapper.readTree(discord.bodies.single())
        val discordText = discordPayload.path("content").asString()
        val allowed = discordPayload.path("allowed_mentions")
        fun emptyOrMissing(node: JsonNode) = node.isMissingNode || (node.isArray && node.size() == 0)
        val mentionsOff = allowed.isObject && allowed.path("parse").isArray && allowed.path("parse").size() == 0 &&
            emptyOrMissing(allowed.path("users")) && emptyOrMissing(allowed.path("roles"))
        if (!mentionsOff) {
            assertFalse(discordText.contains("@everyone") || discordText.contains("@here"), discordText)
            assertFalse(Regex("<@[!&]?[0-9]+>").containsMatchIn(discordText), discordText)
        }
        assertFalse(discordText.contains("[클릭](https://evil.example/x)"), "마크다운 링크가 그대로다: $discordText")
        assertFalse(discordText.contains("\n[장애] backend"), "요청 글이 새 줄을 만들었다: $discordText")
        assertTrue(discordText.contains("jun"), discordText)

        // 슬랙 — <!channel> · <@U…> · <url|글> 이 제어 문법으로 남지 않는다
        val slackPayload = mapper.readTree(slack.bodies.single())
        val slackText = slackPayload.path("text").asString()
        assertFalse(Regex("<[!@#]").containsMatchIn(slackText), slackText)
        assertFalse(slackText.contains("<http"), slackText)
        assertFalse(slackPayload.has("link_names"), "link_names 가 멘션을 켠다: $slackPayload")
        assertFalse(slackText.contains("\n[장애] backend"), slackText)

        // 메일 — 제목에 줄바꿈이 없고 본문에 가짜 줄이 생기지 않는다
        val message = mail.sent.single()
        assertFalse(message.subject!!.contains('\n') || message.subject!!.contains('\r'), message.subject)
        assertFalse(message.text!!.contains("\n[장애] backend"), message.text)
    }

    @Test
    fun `이름이 없거나 비면 422 로 거절하고 보내지 않는다`() {
        for (body in listOf("""{}""", """{"requested_by":""}""", """{"requested_by":"   "}""")) {
            val reply = testSend(body)
            assertEquals(422, reply.status, "$body → ${reply.text}")
            assertEquals("invalid_request", json(reply).path("error").path("code").asString())
        }
        assertEquals(listOf(0, 0, 0), receivedCounts())
        assertEquals(200, testSend().status, "거절된 요청이 간격을 차지했다")
    }

    @Test
    fun `이름은 200자까지 받아 그대로 싣는다`() {
        val longest = "가".repeat(REQUESTED_BY_LIMIT - 1) + "끝"
        results(testSend(requestedBy(longest))).forEach(::assertDelivered)
        assertTrue(mapper.readTree(discord.bodies.single()).path("content").asString().contains(longest))
        assertTrue(mail.sent.single().text!!.contains(longest))
    }

    @Test
    fun `200자를 넘는 이름은 422 로 거절하고 보내지도 간격을 쓰지도 않는다`() {
        val reply = testSend(requestedBy("가".repeat(REQUESTED_BY_LIMIT) + "X"))
        assertEquals(422, reply.status, reply.text)
        assertEquals("invalid_request", json(reply).path("error").path("code").asString())
        assertEquals(listOf(0, 0, 0), receivedCounts(), "200자를 넘는 이름으로 보냈다(조용히 자르지 않는다)")
        assertEquals(200, testSend().status, "거절된 요청이 간격을 차지했다")
    }

    @Test
    fun `30초 안의 둘째 요청은 보내지 않고 429 로 거절한다`() {
        assertEquals(200, testSend().status)
        now = now.plusSeconds(5)
        val reply = testSend()
        assertEquals(429, reply.status, reply.text)
        val root = json(reply)
        assertFalse(root.path("success").asBoolean())
        assertEquals("too_soon", root.path("error").path("code").asString())
        assertTrue(root.path("error").path("retry_after_seconds").asInt() in 1..30, reply.text)
        assertEquals("all_slots", root.path("data").path("rate_limit").path("scope").asString(), reply.text)
        assertEquals(listOf(1, 1, 1), receivedCounts(), "거절한 요청이 보냈다")
    }

    @Test
    fun `대기 자리에서도 설정 보기와 시험 발송이 같은 결과를 낸다`() {
        servingSlot = "green"
        assertFalse(activeSlot.refresh())
        val view = viewSettings()
        assertEquals(200, view.status, view.text)
        assertFalse(json(view).path("data").path("active").asBoolean(true), "대기 자리인데 맡았다고 한다")
        results(testSend()).forEach(::assertDelivered)
        assertEquals(listOf(1, 1, 1), receivedCounts(), "대기 자리라고 보내지 않았다")
    }

    @Test
    fun `채널이 응답하지 않아도 요청이 오래 매달리지 않는다`() {
        discord.mode = FakeWebhookReceiver.Mode.STALL
        val started = System.nanoTime()
        val reply = testSend()
        val elapsed = Duration.ofNanos(System.nanoTime() - started)
        discord.releaseStalled()
        assertTrue(elapsed < Duration.ofSeconds(15), "응답 없는 채널에 매달렸다: $elapsed")   // 기존 발송의 시간 제한 5초 — 넉넉히
        val (discordResult, slackResult, mailResult) = results(reply)
        assertMaskedFailure(discordResult)
        assertDelivered(slackResult); assertDelivered(mailResult)
        assertEquals(1, discord.bodies.size, "시험 발송은 채널마다 한 번만 시도한다")
    }
}
