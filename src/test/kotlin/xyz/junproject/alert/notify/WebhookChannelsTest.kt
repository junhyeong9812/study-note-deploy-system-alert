package xyz.junproject.alert.notify

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class WebhookChannelsTest {
    private var server: HttpServer? = null
    private val release = CountDownLatch(1)
    private val bodies = CopyOnWriteArrayList<String>()
    private val secretPath = "/api/webhooks/123/s3cr3t-token"

    @AfterEach
    fun stop() { release.countDown(); server?.stop(0) }

    private fun serve(status: Int, stall: Boolean = false): String {
        val created = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        created.createContext("/") { exchange ->
            bodies += exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            if (stall) release.await()
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        created.executor = Executors.newCachedThreadPool()
        created.start()
        server = created
        return "http://127.0.0.1:${created.address.port}$secretPath"
    }

    private val alarm = Notification("응답 없음: llm", "status 503", Notification.Severity.CRITICAL)
    private val mapper = ObjectMapper()
    private fun failure(block: () -> Unit) = assertThrows(Exception::class.java, block).message!!

    @Test
    fun `디스코드는 content 로, 슬랙은 text 로 보내고 2xx 면 성공`() {
        DiscordChannel(serve(204), mapper).send(alarm)
        assertEquals("[장애] 응답 없음: llm\nstatus 503", mapper.readTree(bodies.single()).path("content").asString())
        stop(); bodies.clear()
        SlackChannel(serve(200), mapper).send(alarm)
        assertEquals("[장애] 응답 없음: llm\nstatus 503", mapper.readTree(bodies.single()).path("text").asString())
    }

    @Test
    fun `2xx 가 아니면 실패하고 오류 문구에 주소가 없다`() {
        val message = failure { DiscordChannel(serve(500), mapper).send(alarm) }
        assertEquals("discord webhook status 500", message)
    }

    @Test
    fun `응답이 시간 제한을 넘으면 실패하고 오류 문구에 주소가 없다`() {
        val channel = SlackChannel(serve(200, stall = true), mapper, Duration.ofMillis(300))
        val started = System.nanoTime()
        val message = failure { channel.send(alarm) }
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(3))
        assertFalse(message.contains("s3cr3t") || message.contains("127.0.0.1"), message)
    }

    @Test
    fun `연결이 안 되면 실패하고 오류 문구에 주소가 없다`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val message = failure { DiscordChannel("http://127.0.0.1:$closedPort$secretPath", mapper).send(alarm) }
        assertFalse(message.contains("s3cr3t") || message.contains("127.0.0.1"), message)
    }

    @Test
    fun `주소 형식이 틀리면 만들 때 실패하고 그 문구에도 주소가 없다`() {
        for (broken in listOf("https://discord.example/api/webhooks/1/s3cr3t token", "s3cr3t-without-scheme", "ftp://host/s3cr3t")) {
            val message = failure { DiscordChannel(broken, mapper) }
            assertFalse(message.contains("s3cr3t"), message)
            assertTrue(message.contains("discord"), message)
        }
    }
}
