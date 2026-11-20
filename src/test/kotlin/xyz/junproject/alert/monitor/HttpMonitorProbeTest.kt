package xyz.junproject.alert.monitor

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class HttpMonitorProbeTest {
    private var server: HttpServer? = null
    private val release = CountDownLatch(1)

    @AfterEach
    fun stop() { release.countDown(); server?.stop(0) }

    private fun serve(status: Int, body: String, stall: Boolean = false): String {
        val created = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        created.createContext("/") { exchange ->
            if (stall) release.await()
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        created.executor = Executors.newCachedThreadPool()
        created.start()
        server = created
        return "http://127.0.0.1:${created.address.port}/"
    }

    private val probe = HttpMonitorProbe(ObjectMapper(), Duration.ofMillis(500))
    private fun check(url: String, expect: MonitorDefinition.Expectation = MonitorDefinition.Expectation.STATUS) =
        probe.check(MonitorDefinition("target", url, expect))

    @Test
    fun `200 이면 정상`() = assertEquals(ProbeResult.HEALTHY, check(serve(200, """{"status":"ok"}""")))

    @Test
    fun `200 이 아니면 실패하고 본문의 status 를 이유에 덧붙인다`() {
        assertEquals(ProbeResult.failed("status 503 (model_missing)"), check(serve(503, """{"status":"model_missing","model":"m"}""")))
    }

    @Test
    fun `components 가 모두 UP 이어야 정상이고 아닌 것을 이유로 든다`() {
        val allUp = """{"components":{"elasticsearch":{"status":"UP"},"redis":{"status":"UP"}}}"""
        assertEquals(ProbeResult.HEALTHY, check(serve(200, allUp), MonitorDefinition.Expectation.COMPONENTS))
        stop()
        val oneDown = """{"components":{"elasticsearch":{"status":"DOWN"},"embedding":{"status":"UP"},"redis":{"status":"DOWN"}}}"""
        val result = check(serve(200, oneDown), MonitorDefinition.Expectation.COMPONENTS)
        assertFalse(result.healthy)
        assertTrue(result.reason.contains("elasticsearch") && result.reason.contains("redis") && !result.reason.contains("embedding"), result.reason)
    }

    @Test
    fun `components 를 기대했는데 본문이 다르면 실패`() {
        assertFalse(check(serve(200, """{"status":"UP"}"""), MonitorDefinition.Expectation.COMPONENTS).healthy)
        stop()
        assertFalse(check(serve(200, "not json"), MonitorDefinition.Expectation.COMPONENTS).healthy)
    }

    @Test
    fun `응답이 시간 제한을 넘으면 예외 없이 실패로 돌려준다`() {
        val started = System.nanoTime()
        val result = check(serve(200, "{}", stall = true))
        assertFalse(result.healthy)
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(3))
    }

    @Test
    fun `헤더만 보내고 본문을 끄는 대상도 시간 제한 안에 실패로 끝난다`() {
        val created = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        created.createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, 0)                 // 길이를 정하지 않고 헤더부터 보낸다
            exchange.responseBody.write("{".toByteArray()); exchange.responseBody.flush()
            release.await()
            exchange.close()
        }
        created.executor = Executors.newCachedThreadPool()
        created.start()
        server = created
        val started = System.nanoTime()
        val result = check("http://127.0.0.1:${created.address.port}/")
        assertFalse(result.healthy)
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(2), "본문을 기다렸다")
        assertTrue(result.reason.contains("응답이 끝나지 않았다"), result.reason)
    }

    @Test
    fun `연결이 안 되면 예외 없이 실패로 돌려준다`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        assertFalse(check("http://127.0.0.1:$closedPort/").healthy)
    }
}
