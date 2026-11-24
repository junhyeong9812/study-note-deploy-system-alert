package xyz.junproject.alert.settings

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import xyz.junproject.alert.AlertSchedule
import xyz.junproject.alert.event.EventIntake
import xyz.junproject.alert.monitor.MonitorRound
import xyz.junproject.alert.monitor.MonitorsFile
import xyz.junproject.alert.notify.NotificationChannel
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 지금 설정 보기와 시험 발송 — 어드민의 "알림 설정" 화면이 부른다. 여럿이 쓰는 `ALERT_SECRET` 이 아니라
 * 이 두 경로만의 비밀값(`alert.settings-secret`)으로 연다. 비어 있거나 `alert.secret` 과 같은 값이면 늘 거절한다.
 * 어떤 비밀값(웹훅 주소 · 메일 계정 · 비밀값)도, 감시 대상의 호스트 · IP · 포트도 내지 않는다.
 */
@RestController
class SettingsController(
    private val round: MonitorRound,
    private val intake: EventIntake,
    private val channels: List<NotificationChannel>,
    private val testSend: TestSend,
    private val objectMapper: ObjectMapper,
    @Value("\${alert.monitors-file:classpath:monitors.yml}") monitorsFile: Resource,
    @Value("\${alert.settings-secret:}") private val settingsSecret: String,
    @Value("\${alert.secret:}") alertSecret: String,
    // 글로 받는다 — @Scheduled 는 `PT30S` 같은 기간 글도 받는데, 숫자로 바인딩하면 그 값에서 앱 전체가 뜨지 않는다
    @Value("\${alert.round-interval-ms:30000}") private val roundInterval: String,
    @Value("\${alert.summary-cron:0 0 9 * * *}") private val summaryCron: String,
) {
    /** 뜰 때 읽은 감시 대상의 주소 틀(가린 꼴) — 감시 대상과 같이 뜰 때 한 번 읽는다 */
    private val maskedAddresses: Map<String, String> =
        MonitorsFile.addressTemplates(monitorsFile.getContentAsString(StandardCharsets.UTF_8)).mapValues { MonitorAddressMask.mask(it.value) }

    /**
     * 두 경로를 열 수 있는가 — 뜰 때 한 번 정한다. 비었으면 닫는다(빈 값끼리 맞지 않는다).
     * 여럿이 쓰는 `alert.secret` 과 같은 값이면 따로 둔 뜻이 없으므로 닫고 error 로그를 한 줄 남긴다(값은 싣지 않는다).
     */
    private val settingsOpen: Boolean = when {
        settingsSecret.isBlank() -> false
        settingsSecret == alertSecret -> {
            LoggerFactory.getLogger("alert").error(
                "alert:alert:ALERT_SETTINGS_SECRET is the same as ALERT_SECRET — /settings routes stay closed until it is set to a different value")
            false
        }
        else -> true
    }

    private fun authorized(request: HttpServletRequest): Boolean {
        val provided = request.getHeader(SETTINGS_HEADER) ?: return false
        // 닫혀 있으면 항상 거절, 비교는 상수 시간
        return settingsOpen && MessageDigest.isEqual(provided.toByteArray(), settingsSecret.toByteArray())
    }

    /** 틀린 값 · 없는 값 · 설정 안 됨 모두 같은 응답 — 어느 쪽인지 드러내지 않는다 */
    private fun unauthorized() = ResponseEntity.status(401).body(mapOf("success" to false, "error" to mapOf("code" to "unauthorized")))
    private fun invalid(detail: String) =
        ResponseEntity.status(422).body(mapOf("success" to false, "error" to mapOf("code" to "invalid_request", "detail" to detail)))

    @GetMapping("/settings")
    fun view(request: HttpServletRequest): ResponseEntity<out Any> {
        if (!authorized(request)) return unauthorized()
        val monitors = round.definitions.map {
            mapOf("name" to it.name, "expect" to it.expect.name.lowercase(), "address" to (maskedAddresses[it.name] ?: ""))
        }
        val configured = channels.map { it.name }
        val channelViews = CHANNEL_KINDS.map { kind ->
            val order = configured.indexOf(kind).takeIf { it >= 0 }?.plus(1)
            mapOf("kind" to kind, "configured" to (order != null), "order" to order)
        }
        val rules = mapOf(
            "failure_threshold" to round.tracker.failureThreshold,
            "remind_every_seconds" to round.tracker.remindEvery.seconds,
            "event_suppress_seconds" to intake.suppressFor.seconds,
            "round_interval_seconds" to roundInterval.trim().toLongOrNull()?.div(1000),   // 숫자(ms)로 읽히지 않으면 이 칸만 null
            "summary_cron" to summaryCron,
            "summary_zone" to AlertSchedule.SUMMARY_ZONE,
        )
        return ResponseEntity.ok(mapOf("success" to true, "data" to mapOf(
            "monitors" to monitors, "channels" to channelViews, "rules" to rules, "active" to round.active)))
    }

    /** 본문을 읽는 것은 스프링이 먼저 한다(글 그대로) — JSON 해석과 입력 검사는 인증 뒤에 한다 */
    @PostMapping("/settings/test-send")
    fun testSend(request: HttpServletRequest, @RequestBody(required = false) body: String?): ResponseEntity<out Any> {
        if (!authorized(request)) return unauthorized()
        val requestedBy = requestedByIn(body) ?: return invalid("requested_by")
        return when (val result = testSend.send(requestedBy)) {
            is TestSendResult.Sent -> ResponseEntity.ok(mapOf("success" to true, "data" to mapOf(
                "results" to result.outcomes.map { mapOf("channel" to it.channel, "delivered" to it.delivered, "reason" to it.reason) },
                "rate_limit" to rateLimit(result.limitScope))))
            is TestSendResult.TooSoon -> {
                val seconds = ((result.retryAfter.toMillis() + 999) / 1000).coerceAtLeast(1)
                ResponseEntity.status(429).header("Retry-After", seconds.toString()).body(mapOf("success" to false,
                    "error" to mapOf("code" to "too_soon", "retry_after_seconds" to seconds),
                    "data" to mapOf("rate_limit" to rateLimit(result.limitScope))))
            }
        }
    }

    /** `{"requested_by": "…"}` — 앞뒤 빈칸을 뗀 뒤 비었거나 [REQUESTED_BY_LIMIT] 자를 넘으면 null(조용히 자르지 않는다) */
    private fun requestedByIn(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val node = runCatching { objectMapper.readTree(body) }.getOrNull() ?: return null
        val value = node.path("requested_by").takeIf { it.isString }?.asString()?.trim() ?: return null
        return value.takeIf { it.isNotEmpty() && it.codePointCount(0, it.length) <= REQUESTED_BY_LIMIT }
    }

    private fun rateLimit(scope: RateLimitScope) =
        mapOf("interval_seconds" to testSend.interval.seconds, "scope" to scope.name.lowercase())

    companion object {
        const val SETTINGS_HEADER = "X-Alert-Settings-Secret"
        const val REQUESTED_BY_LIMIT = 200
        /** 채널의 종류 — 설정되지 않은 것도 늘 이 순서로 낸다(발송 순서와 같다) */
        val CHANNEL_KINDS = listOf("discord", "slack", "mail")
    }
}
