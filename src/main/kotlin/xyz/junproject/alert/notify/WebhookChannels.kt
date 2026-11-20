package xyz.junproject.alert.notify

import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * 웹훅으로 JSON 한 덩어리를 보내는 채널의 공통 부분 — 시간 제한 5초, 2xx가 아니면 실패.
 * 주소는 비밀값이다: 형식 오류든 연결 오류든 예외 문구에 주소가 들어가지 않게 고정 문구만 쓴다.
 */
abstract class WebhookChannel(
    final override val name: String,
    webhookUrl: String,
    private val objectMapper: ObjectMapper,
    private val timeout: Duration = Duration.ofSeconds(5),
) : NotificationChannel {
    private val uri: URI = runCatching { URI.create(webhookUrl.trim()) }.getOrNull()
        ?.takeIf { (it.scheme == "http" || it.scheme == "https") && !it.host.isNullOrBlank() }
        ?: throw IllegalArgumentException("$name webhook 주소의 형식이 틀렸다(http(s) 주소여야 한다)")
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    protected abstract fun payload(notification: Notification): Map<String, Any>

    override fun send(notification: Notification) {
        val request = HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload(notification))))
            .build()
        val status = try {
            // 본문까지 포함해 시간 제한 안에 끝나야 한다
            client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).get(timeout.toMillis(), TimeUnit.MILLISECONDS).statusCode()
        } catch (error: InterruptedException) {
            throw error
        } catch (error: Exception) {
            val cause = error.cause ?: error
            throw IllegalStateException("$name webhook 에 닿지 못했다(${cause.javaClass.simpleName})")
        }
        check(status in 200..299) { "$name webhook status $status" }
    }
}

/** 디스코드 웹훅 — content 는 2000자 제한 */
class DiscordChannel(webhookUrl: String, objectMapper: ObjectMapper, timeout: Duration = Duration.ofSeconds(5)) :
    WebhookChannel("discord", webhookUrl, objectMapper, timeout) {
    override fun payload(notification: Notification) =
        mapOf("content" to "${marker(notification)} ${notification.asText()}".take(1900))
}

/** 슬랙 인커밍 웹훅 */
class SlackChannel(webhookUrl: String, objectMapper: ObjectMapper, timeout: Duration = Duration.ofSeconds(5)) :
    WebhookChannel("slack", webhookUrl, objectMapper, timeout) {
    override fun payload(notification: Notification) =
        mapOf("text" to "${marker(notification)} ${notification.asText()}".take(3000))
}

internal fun marker(notification: Notification): String = when (notification.severity) {
    Notification.Severity.CRITICAL -> "[장애]"
    Notification.Severity.WARNING -> "[경고]"
    Notification.Severity.INFO -> "[알림]"
}
