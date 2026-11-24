package xyz.junproject.alert.settings

import com.sun.net.httpserver.HttpServer
import org.springframework.mail.MailSendException
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSenderImpl
import tools.jackson.databind.JsonNode
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * 테스트 설정에 넣는 표식 값 — 응답 · 로그에서 이 글자가 하나라도 보이면 새어 나간 것이다.
 * 모든 비밀값 · 호스트에 "marker" 가 들어 있어 대소문자 무시 "mark" 한 번으로 일부(앞 몇 글자)까지 찾는다.
 */
object SettingsMarkers {
    const val ALERT_SECRET = "MARKER-alert-secret-Qe7w"
    const val SETTINGS_SECRET = "MARKER-settings-secret-Zk4r9xxq7"
    const val DISCORD_PATH = "/api/webhooks/9876/MARKER-discord-token-Hn2x"
    const val SLACK_PATH = "/services/T000/B000/MARKER-slack-token-Lp8v"
    const val MAIL_HOST = "marker-smtp-host.invalid"
    const val MAIL_USERNAME = "marker-mail-user@example.invalid"
    const val MAIL_PASSWORD = "MARKER-mail-password-Wd5s"
    const val MAIL_FROM = "marker-mail-from@example.invalid"
    const val MAIL_TO = "marker-mail-to-q@example.invalid"
    const val RECEIVER_ERROR_BODY = "MARKER-receiver-body-Tt3c {\"message\":\"Unknown Webhook\"}"
    const val LEAK_FRAGMENT = "mark"

    val secretValues = listOf(ALERT_SECRET, SETTINGS_SECRET, DISCORD_PATH, SLACK_PATH, MAIL_USERNAME, MAIL_PASSWORD, MAIL_FROM, MAIL_TO)

    /** 실패 사유의 꼴 — 상태 코드(`status 500`)나 소문자 갈래 이름(`timeout` · `unreachable` …)뿐. 주소의 `:` `/` `.` 이 들어갈 자리가 없다. */
    val maskedReason = Regex("^(status [0-9]{3}|[a-z_]{1,40})$")

    /** 글 전체(응답 · 로그)에서 표식의 흔적을 찾는다 */
    fun leaks(text: String, alsoForbidden: List<String> = emptyList()): List<String> {
        val found = mutableListOf<String>()
        if (text.lowercase().contains(LEAK_FRAGMENT)) found += LEAK_FRAGMENT
        alsoForbidden.filter { it.isNotEmpty() && text.contains(it) }.forEach { found += it }
        return found
    }

    /** JSON 안의 모든 숫자 — 비밀값의 길이가 숫자로 실려 있지 않은지 볼 때 쓴다 */
    fun numbersIn(node: JsonNode): List<Long> = when {
        node.isNumber -> listOf(node.asLong())
        node.isArray -> (0 until node.size()).flatMap { numbersIn(node.get(it)) }
        node.isObject -> node.properties().flatMap { numbersIn(it.value) }
        else -> emptyList()
    }
}

/**
 * 가짜 웹훅 받는 쪽(로컬 HTTP) — 실제 디스코드 · 슬랙으로는 보내지 않는다. 기존 WebhookChannelsTest 의 HttpServer 꼴을 따른다.
 * 받은 본문을 모두 남기고, 모드에 따라 성공 · 오류 상태(본문에 표식) · 응답 없음으로 답한다.
 */
class FakeWebhookReceiver(private val secretPath: String, private val okStatus: Int = 200) : AutoCloseable {
    enum class Mode { OK, ERROR, STALL }

    @Volatile var mode = Mode.OK
    val bodies = CopyOnWriteArrayList<String>()
    private val arrivals = Semaphore(0)
    @Volatile private var release = CountDownLatch(1)

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            bodies += exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val modeNow = mode
            val stalledOn = release
            arrivals.release()
            try {
                when (modeNow) {
                    Mode.OK -> exchange.sendResponseHeaders(okStatus, -1)
                    Mode.ERROR -> {
                        val body = SettingsMarkers.RECEIVER_ERROR_BODY.toByteArray()
                        exchange.sendResponseHeaders(500, body.size.toLong())
                        exchange.responseBody.write(body)
                    }
                    Mode.STALL -> { stalledOn.await(); exchange.sendResponseHeaders(okStatus, -1) }
                }
            } catch (_: Exception) {
                // 보낸 쪽이 시간 제한으로 끊었다 — 받는 쪽에서는 할 일이 없다
            } finally {
                exchange.close()
            }
        }
        executor = Executors.newCachedThreadPool { task -> Thread(task, "fake-webhook").apply { isDaemon = true } }
        start()
    }

    val port: Int get() = server.address.port
    val url: String get() = "http://127.0.0.1:$port$secretPath"

    /** 요청이 하나 도착할 때까지 기다린다(응답 전에 도착을 안다 — 발송 중인 때를 잡을 때 쓴다) */
    fun awaitArrival(seconds: Long = 5): Boolean = arrivals.tryAcquire(seconds, TimeUnit.SECONDS)

    fun releaseStalled() { val stalled = release; release = CountDownLatch(1); stalled.countDown() }

    fun reset() { releaseStalled(); mode = Mode.OK; bodies.clear(); arrivals.drainPermits() }

    override fun close() { release.countDown(); server.stop(0) }
}

/** 가짜 메일 발신 — 실제 메일 서버에 닿지 않는다. 실패 모드면 호스트 · 계정 · 비밀번호가 든 예외를 던진다(그 문구가 새면 안 된다). */
class RecordingMailSender : JavaMailSenderImpl() {
    @Volatile var failing = false
    val sent = CopyOnWriteArrayList<SimpleMailMessage>()

    override fun send(vararg simpleMessages: SimpleMailMessage) {
        if (failing) throw MailSendException(
            "Couldn't connect to host ${SettingsMarkers.MAIL_HOST}:2525 as ${SettingsMarkers.MAIL_USERNAME} / ${SettingsMarkers.MAIL_PASSWORD}")
        sent += simpleMessages
    }

    fun reset() { failing = false; sent.clear() }
}
