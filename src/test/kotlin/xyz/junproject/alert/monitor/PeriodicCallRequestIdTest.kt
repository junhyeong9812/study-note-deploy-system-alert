package xyz.junproject.alert.monitor

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import xyz.junproject.alert.notify.FallbackNotifier
import xyz.junproject.alert.notify.Notification
import xyz.junproject.alert.notify.NotificationChannel
import java.net.InetSocketAddress
import java.time.Duration
import java.util.Collections
import java.util.concurrent.Executors

/**
 * 들어온 요청 없이 alert 가 스스로 시작하는 주기 호출(통신 규약 §1.2 "들어온 요청 없이 시작되는 호출" · §7.1 T2 alert 줄)은
 * 호출마다 새 추적 ID 를 머리글 `X-Request-Id` 로 싣는다 — 받는 쪽 nginx 의 접속 기록이 그 값을 남긴다.
 * - 감시의 확인([HttpMonitorProbe]) · 활성 자리 확인([HttpServingSlotCheck]) 모두.
 * - 값은 머리글 하나에 하나, 꼴은 16진수 소문자 32자(규약의 꼴 `^[A-Za-z0-9_-]{1,64}$` 안 — 처음 받는 쪽이 만드는 꼴과 같다).
 * - 호출마다 다르다: 같은 대상을 다시 부를 때 · 같은 바퀴의 대상마다 · 바퀴마다 · 자리 확인과 감시 사이.
 * 가짜 대상은 이 컴퓨터의 실제 소켓(JDK `HttpServer`)이고, 받은 머리글을 값으로 본다.
 */
class PeriodicCallRequestIdTest {
    private val servers = mutableListOf<HttpServer>()

    @AfterEach
    fun close() = servers.forEach { it.stop(0) }

    /** 받은 요청 하나 — 경로와 `X-Request-Id` 머리글의 값들(없으면 빈 목록) */
    data class Received(val path: String, val requestIds: List<String>)

    /** 모든 경로에 200 · `{"status":"ok"}` 와 `X-Deploy-Slot: blue` 로 답하는 가짜 대상 */
    private inner class FakeTarget {
        val received: MutableList<Received> = Collections.synchronizedList(mutableListOf())
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 16).also { server ->
            server.executor = Executors.newCachedThreadPool()
            server.createContext("/") { exchange ->
                received += Received(exchange.requestURI.path, exchange.requestHeaders["X-Request-Id"].orEmpty())
                val bytes = """{"status":"ok"}""".toByteArray()
                exchange.responseHeaders.add("X-Deploy-Slot", "blue")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            servers += server
        }
        fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"
    }

    private val protocolForm = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val createdForm = Regex("^[0-9a-f]{32}$")

    /** 요청 하나에 머리글이 꼭 하나 있고 꼴이 맞는지 본 뒤 그 값을 돌려준다 */
    private fun requestIdOf(received: Received): String {
        assertEquals(1, received.requestIds.size, "${received.path} 의 X-Request-Id 머리글 수: ${received.requestIds}")
        val requestId = received.requestIds.single()
        assertTrue(protocolForm.matches(requestId), "규약의 꼴이 아니다: $requestId")
        assertTrue(createdForm.matches(requestId), "16진수 소문자 32자가 아니다: $requestId")
        return requestId
    }

    private fun assertAllDistinct(requestIds: List<String>, what: String) =
        assertEquals(requestIds.size, requestIds.toSet().size, "$what 에서 같은 ID 를 다시 썼다: $requestIds")

    private val probe = HttpMonitorProbe(ObjectMapper(), Duration.ofSeconds(2))

    @Test
    fun `감시의 확인은 X-Request-Id 를 하나 싣고 꼴은 16진수 소문자 32자다`() {
        val target = FakeTarget()
        assertEquals(ProbeResult.HEALTHY, probe.check(MonitorDefinition("front", target.url("/"))), "준비: 가짜 대상을 정상으로 보지 못했다")
        requestIdOf(target.received.single())
    }

    @Test
    fun `같은 대상을 다시 확인할 때마다 새 ID 를 싣는다`() {
        val target = FakeTarget()
        val definition = MonitorDefinition("front", target.url("/"))
        repeat(3) { probe.check(definition) }
        assertEquals(3, target.received.size, "준비: 세 번 부르지 않았다")
        assertAllDistinct(target.received.map(::requestIdOf), "같은 대상의 세 번의 확인")
    }

    @Test
    fun `자리 확인은 X-Request-Id 를 하나 싣고 부를 때마다 새 ID 다`() {
        val target = FakeTarget()
        val check = HttpServingSlotCheck(target.url("/health"), Duration.ofSeconds(2))
        repeat(3) { assertEquals("blue", check.servingSlot(), "준비: 가짜 프록시의 자리를 읽지 못했다") }
        assertEquals(3, target.received.size)
        assertAllDistinct(target.received.map(::requestIdOf), "세 번의 자리 확인")
    }

    @Test
    fun `자리를 확인하는 감시 바퀴에서 자리 확인과 대상마다 바퀴마다 ID 가 모두 다르다`() {
        val target = FakeTarget()
        val definitions = listOf("front", "backend", "llm").map { MonitorDefinition(it, target.url("/$it/health")) }
        val inbox = object : NotificationChannel {
            override val name = "discord"
            val received = Collections.synchronizedList(mutableListOf<Notification>())
            override fun send(notification: Notification) { received += notification }
        }
        val notifier = FallbackNotifier(listOf(inbox), pause = {}, worker = { it.run() })
        val activeSlot = ActiveSlot("blue", HttpServingSlotCheck(target.url("/slot"), Duration.ofSeconds(2)))
        val round = MonitorRound(definitions, probe, MonitorTracker(), notifier, null, Duration.ofSeconds(5), activeSlot)

        round.run()
        round.run()

        val calls = target.received.toList()
        assertEquals(2, calls.count { it.path == "/slot" }, "준비: 바퀴마다 자리를 확인하지 않았다 ${calls.map { it.path }}")
        definitions.forEach { definition ->
            assertEquals(2, calls.count { it.path == "/${definition.name}/health" }, "준비: ${definition.name} 를 두 바퀴 확인하지 않았다")
        }
        assertTrue(inbox.received.isEmpty(), "준비: 모두 정상인데 알렸다 ${inbox.received}")
        assertAllDistinct(calls.map(::requestIdOf), "두 바퀴의 자리 확인 2번과 감시 6번")
    }
}
