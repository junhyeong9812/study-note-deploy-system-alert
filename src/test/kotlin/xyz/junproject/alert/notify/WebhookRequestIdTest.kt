package xyz.junproject.alert.notify

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.Executors

/**
 * 바깥 서비스(디스코드 · 슬랙의 웹훅)로 가는 발송에는 `X-Request-Id` 를 싣지 않는다 — 추적의 ID 는 우리 서비스 사이를 잇는 값이다
 * (통신 규약 §1.2 "다른 서비스를 부를 때" · §2.2 "바깥을 부를 때는 싣지 않는다"). 가짜 웹훅(JDK `HttpServer`)이 받은 머리글을 본다.
 */
class WebhookRequestIdTest {
    private var server: HttpServer? = null
    private val receivedRequestIds: MutableList<List<String>?> = Collections.synchronizedList(mutableListOf())

    @AfterEach
    fun stop() { server?.stop(0) }

    private fun serve(): String {
        val created = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        created.createContext("/") { exchange ->
            exchange.requestBody.readAllBytes()
            receivedRequestIds += exchange.requestHeaders["X-Request-Id"]
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        created.executor = Executors.newCachedThreadPool()
        created.start()
        server = created
        return "http://127.0.0.1:${created.address.port}/api/webhooks/1/token"
    }

    private val alarm = Notification("응답 없음: llm", "status 503", Notification.Severity.CRITICAL)

    @Test
    fun `디스코드와 슬랙 웹훅으로 보낼 때 X-Request-Id 를 싣지 않는다`() {
        val url = serve()
        DiscordChannel(url, ObjectMapper()).send(alarm)
        SlackChannel(url, ObjectMapper()).send(alarm)
        assertEquals(2, receivedRequestIds.size, "준비: 두 채널이 보내지 않았다")
        receivedRequestIds.forEach { assertNull(it, "바깥 웹훅에 추적 ID 를 실었다: $it") }
    }
}
