package xyz.junproject.alert.notify

import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** 웹훅으로 JSON 한 덩어리를 보내는 채널의 공통 부분 — 시간 제한 5초, 2xx가 아니면 실패 */
abstract class WebhookChannel(
    private val webhookUrl: String,
    private val objectMapper: ObjectMapper,
    private val timeout: Duration = Duration.ofSeconds(5),
) : NotificationChannel {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    protected abstract fun payload(notification: Notification): Map<String, Any>

    override fun send(notification: Notification) {
        val request = HttpRequest.newBuilder(URI.create(webhookUrl))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload(notification))))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        // 주소(비밀값)는 오류 문구에 넣지 않는다
        check(response.statusCode() in 200..299) { "$name webhook status ${response.statusCode()}" }
    }
}

/** 디스코드 웹훅 — content 는 2000자 제한 */
class DiscordChannel(webhookUrl: String, objectMapper: ObjectMapper) : WebhookChannel(webhookUrl, objectMapper) {
    override val name = "discord"
    override fun payload(notification: Notification) =
        mapOf("content" to "${marker(notification)} ${notification.asText()}".take(1900))
}

/** 슬랙 인커밍 웹훅 */
class SlackChannel(webhookUrl: String, objectMapper: ObjectMapper) : WebhookChannel(webhookUrl, objectMapper) {
    override val name = "slack"
    override fun payload(notification: Notification) =
        mapOf("text" to "${marker(notification)} ${notification.asText()}".take(3000))
}

internal fun marker(notification: Notification): String = when (notification.severity) {
    Notification.Severity.CRITICAL -> "[장애]"
    Notification.Severity.WARNING -> "[경고]"
    Notification.Severity.INFO -> "[알림]"
}
