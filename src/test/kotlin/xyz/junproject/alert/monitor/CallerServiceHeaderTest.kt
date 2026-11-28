package xyz.junproject.alert.monitor

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import xyz.junproject.alert.AlertController
import xyz.junproject.alert.StoreHolder
import xyz.junproject.alert.event.EventIntake
import xyz.junproject.alert.notify.DiscordChannel
import xyz.junproject.alert.notify.FallbackNotifier
import xyz.junproject.alert.notify.Notification
import xyz.junproject.alert.notify.SlackChannel
import java.net.InetSocketAddress
import java.time.Duration
import java.util.Collections
import java.util.concurrent.Executors

/**
 * alert 가 **우리 서비스**를 부르는 호출에 자기 규약 이름을 `X-Caller-Service: alert` 로 싣는다(⑬ T4b · 통신 규약 §2.1 · §2.2 · §7.1 의 T4 줄).
 * - 우리 서비스로 가는 호출 = 감시의 확인(`monitors.yml` 의 대상 가운데 규약 §2.1 의 서비스 — front · backend · llm · 배포 마스터 · 에이전트 · observe · admin)과
 *   활성 자리 확인(alert 자신의 nginx). 머리글은 **정확히 하나** · 값은 `alert`(소문자 · 코드의 상수). 바퀴마다 다시 볼 때도.
 * - 들어온 요청의 `X-Caller-Service` 를 옮겨 싣지 않는다 · 설정(`SERVER_NAME` — 테스트에서는 `front` 로 둔다)에서 읽지 않는다.
 * - 바깥에는 싣지 않는다 — 발송 채널(디스코드 · 슬랙 웹훅)과, 규약 §2.1 의 목록에 없고 앞에 nginx 가 없는 Claude 실행 자리의 감시(`claude-runner`).
 * - 그 밖의 머리글(요청 ID) · 감시의 판정은 그대로.
 * 가짜는 실제 소켓의 JDK `HttpServer` — 받은 머리글을 그대로 본다.
 */
class CallerServiceHeaderTest {
    private val servers = mutableListOf<HttpServer>()

    @AfterEach
    fun close() = servers.forEach { it.stop(0) }

    /** 받은 요청 하나 — 경로와 머리글(소문자 이름) */
    data class Received(val path: String, val headers: Map<String, List<String>>)

    /** 모든 경로에 200 · 구성 요소가 모두 UP 인 본문과 `X-Deploy-Slot: blue` 로 답하는 가짜 서버(`expect: components` 대상도 정상) */
    private inner class FakeServer {
        val received: MutableList<Received> = Collections.synchronizedList(mutableListOf())
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 16).also { server ->
            server.executor = Executors.newCachedThreadPool()
            server.createContext("/") { exchange ->
                exchange.requestBody.readAllBytes()
                received += Received(exchange.requestURI.path, exchange.requestHeaders.mapKeys { it.key.lowercase() })
                val bytes = """{"status":"UP","components":{"redis":{"status":"UP"}}}""".toByteArray()
                exchange.responseHeaders.add("X-Deploy-Slot", "blue")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            servers += server
        }
        fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"
    }

    private val probe = HttpMonitorProbe(ObjectMapper(), Duration.ofSeconds(2))

    private fun assertCallerIsAlert(received: Received, what: String) {
        assertEquals(listOf("alert"), received.headers["x-caller-service"], "$what: X-Caller-Service 가 정확히 하나의 alert 가 아니다")
    }

    private fun assertRequestIdKept(received: Received, what: String) {
        val requestIds = received.headers["x-request-id"].orEmpty()
        assertEquals(1, requestIds.size, "$what: 요청 ID 머리글이 바뀌었다 $requestIds")
        assertTrue(Regex("^[0-9a-f]{32}$").matches(requestIds.single()), "$what: 요청 ID 의 꼴이 바뀌었다 ${requestIds.single()}")
    }

    /** 실제 `monitors.yml` 을 읽고, 가리킨 환경변수마다 가짜 서버의 `/<변수 이름>` 을 준다 */
    private fun realMonitors(server: FakeServer): List<MonitorDefinition> {
        val text = javaClass.getResource("/monitors.yml")!!.readText()
        return MonitorsFile.parse(text) { variable -> server.url("/$variable") }
    }

    private val outsideMonitors = setOf("claude-runner")

    // ---- 우리 서비스: 감시 · 자리 확인 ----

    @Test
    fun `감시의 확인은 X-Caller-Service alert 를 하나 싣고 요청 ID 와 판정은 그대로다`() {
        val server = FakeServer()
        assertEquals(ProbeResult.HEALTHY, probe.check(MonitorDefinition("front", server.url("/"))), "판정이 바뀌었다")
        val request = server.received.single()
        assertCallerIsAlert(request, "front 감시")
        assertRequestIdKept(request, "front 감시")
    }

    @Test
    fun `자리 확인은 X-Caller-Service alert 를 하나 싣고 요청 ID 와 읽은 자리는 그대로다`() {
        val server = FakeServer()
        assertEquals("blue", HttpServingSlotCheck(server.url("/health"), Duration.ofSeconds(2)).servingSlot(), "읽은 자리가 바뀌었다")
        val request = server.received.single()
        assertCallerIsAlert(request, "자리 확인")
        assertRequestIdKept(request, "자리 확인")
    }

    @Test
    fun `실제 감시 대상 가운데 우리 서비스에는 싣고 Claude 실행 자리에는 싣지 않는다`() {
        val server = FakeServer()
        val definitions = realMonitors(server)
        assertTrue(definitions.any { it.name == "claude-runner" }, "준비: monitors.yml 에 claude-runner 가 없다")
        definitions.forEach { definition ->
            val before = server.received.size
            assertEquals(ProbeResult.HEALTHY, probe.check(definition), "준비: ${definition.name} 를 정상으로 보지 못했다")
            val request = server.received.drop(before).single()
            if (definition.name in outsideMonitors) {
                assertNull(request.headers["x-caller-service"], "${definition.name}(규약의 목록 밖 · nginx 없음)에 실었다")
            } else {
                assertCallerIsAlert(request, definition.name)
            }
            assertRequestIdKept(request, definition.name)
        }
    }

    @Test
    fun `자리를 확인하는 감시 바퀴를 두 번 돌면 자리 확인과 우리 서비스의 감시 모두에 싣는다`() {
        val server = FakeServer()
        val definitions = realMonitors(server)
        val inbox = object : xyz.junproject.alert.notify.NotificationChannel {
            override val name = "discord"
            val received = Collections.synchronizedList(mutableListOf<Notification>())
            override fun send(notification: Notification) { received += notification }
        }
        val notifier = FallbackNotifier(listOf(inbox), pause = {}, worker = { it.run() })
        val activeSlot = ActiveSlot("blue", HttpServingSlotCheck(server.url("/slot"), Duration.ofSeconds(2)))
        val round = MonitorRound(definitions, probe, MonitorTracker(), notifier, null, Duration.ofSeconds(5), activeSlot)

        round.run()
        round.run()

        val calls = server.received.toList()
        val slotCalls = calls.filter { it.path == "/slot" }
        assertEquals(2, slotCalls.size, "준비: 바퀴마다 자리를 확인하지 않았다")
        assertEquals(2 + definitions.size * 2, calls.size, "준비: 두 바퀴에 모든 대상을 보지 않았다 ${calls.map { it.path }}")
        assertTrue(inbox.received.isEmpty(), "준비: 모두 정상인데 알렸다 ${inbox.received}")
        slotCalls.forEach { assertCallerIsAlert(it, "자리 확인") }
        val runnerPath = java.net.URI.create(definitions.single { it.name == "claude-runner" }.url).path
        assertEquals(2, calls.count { it.path == runnerPath }, "준비: Claude 실행 자리를 두 바퀴 보지 않았다")
        calls.filter { it.path != "/slot" }.forEach { request ->
            if (request.path == runnerPath) {
                assertNull(request.headers["x-caller-service"], "Claude 실행 자리의 감시에 실었다")
            } else {
                assertCallerIsAlert(request, "감시 ${request.path}")
            }
        }
    }

    // ---- 들어온 값을 옮기지 않는다 · 설정에서 읽지 않는다 ----

    /** 들어온 요청 안에서 감시와 자리 확인을 부르는 이 테스트만의 컨트롤러 */
    @RestController
    class CallingController(private val targetUrl: String, private val slotUrl: String) {
        @GetMapping("/test/call-monitors")
        fun call(): String {
            HttpMonitorProbe(ObjectMapper(), Duration.ofSeconds(2)).check(MonitorDefinition("front", targetUrl))
            HttpServingSlotCheck(slotUrl, Duration.ofSeconds(2)).servingSlot()
            return "ok"
        }
    }

    @Test
    fun `들어온 요청의 X-Caller-Service 를 옮겨 싣지 않고 자기 이름을 싣는다`() {
        listOf("front", "edge", "outside", "admin", "ALERT", "alert, front").forEach { incoming ->
            val server = FakeServer()
            val mockMvc = MockMvcBuilders.standaloneSetup(CallingController(server.url("/front"), server.url("/slot"))).build()
            val response = mockMvc.perform(get("/test/call-monitors").header("X-Caller-Service", incoming)).andReturn().response
            assertEquals(200, response.status, "준비: $incoming")
            assertEquals(2, server.received.size, "준비: $incoming")
            server.received.forEach { request -> assertCallerIsAlert(request, "들어온 값 $incoming · ${request.path}") }
        }
    }

    @Test
    fun `설정의 이름을 다르게 두어도 alert 다`() {
        assertEquals("front", System.getenv("SERVER_NAME"), "준비: 테스트의 환경변수 SERVER_NAME 이 front 가 아니다(build.gradle.kts)")
        val previous = System.getProperty("SERVER_NAME")
        System.setProperty("SERVER_NAME", "front")
        try {
            val server = FakeServer()
            probe.check(MonitorDefinition("front", server.url("/front")))
            HttpServingSlotCheck(server.url("/slot"), Duration.ofSeconds(2)).servingSlot()
            assertEquals(2, server.received.size, "준비")
            server.received.forEach { request -> assertCallerIsAlert(request, request.path) }
        } finally {
            if (previous == null) System.clearProperty("SERVER_NAME") else System.setProperty("SERVER_NAME", previous)
        }
    }

    // ---- 바깥에는 싣지 않는다 ----

    @Test
    fun `디스코드와 슬랙 웹훅에는 싣지 않는다`() {
        val webhook = FakeServer()
        val alarm = Notification("응답 없음: llm", "status 503", Notification.Severity.CRITICAL)
        DiscordChannel(webhook.url("/api/webhooks/1/token"), ObjectMapper()).send(alarm)
        SlackChannel(webhook.url("/services/T/B/token"), ObjectMapper()).send(alarm)
        assertEquals(2, webhook.received.size, "준비: 두 채널이 보내지 않았다")
        webhook.received.forEach { request ->
            assertNull(request.headers["x-caller-service"], "바깥 웹훅(${request.path})에 실었다")
            assertTrue(request.headers["content-type"]?.single()?.startsWith("application/json") == true, "다른 머리글이 바뀌었다 ${request.headers}")
        }
    }

    @Test
    fun `X-Caller-Service front 로 들어온 사건 접수가 내는 웹훅에도 싣지 않는다`() {
        val webhook = FakeServer()
        val notifier = FallbackNotifier(listOf(DiscordChannel(webhook.url("/api/webhooks/1/token"), ObjectMapper())), pause = {}, worker = { it.run() })
        val round = MonitorRound(listOf(MonitorDefinition("front", "http://unused/")), { ProbeResult.HEALTHY }, MonitorTracker(), notifier, null)
        val controller = AlertController(round, EventIntake(notifier, null), notifier, StoreHolder(null), "caller-test-secret")
        val mockMvc = MockMvcBuilders.standaloneSetup(controller).build()
        val response = mockMvc.perform(post("/events").header("X-Alert-Secret", "caller-test-secret").header("X-Caller-Service", "front")
            .contentType(MediaType.APPLICATION_JSON).content("""{"source":"ci-cd","kind":"deploy_failed","title":"배포 실패"}""")).andReturn().response
        assertEquals(202, response.status, "준비: ${response.contentAsString}")
        assertEquals(1, webhook.received.size, "준비: 웹훅으로 보내지 않았다")
        assertNull(webhook.received.single().headers["x-caller-service"], "들어온 사건의 웹훅에 실었다")
    }
}
