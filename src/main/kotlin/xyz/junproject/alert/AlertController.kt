package xyz.junproject.alert

import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import xyz.junproject.alert.event.AlertEvent
import xyz.junproject.alert.event.EventIntake
import xyz.junproject.alert.monitor.MonitorRound
import xyz.junproject.alert.notify.FallbackNotifier
import xyz.junproject.alert.notify.Notification
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/** HTTP 경계 — 사건 접수와 조회는 서버 간 비밀값, 헬스는 누구나. */
@RestController
class AlertController(
    private val round: MonitorRound,
    private val intake: EventIntake,
    private val notifier: FallbackNotifier,
    private val storeHolder: StoreHolder,
    @Value("\${alert.secret:}") private val secret: String,
) {
    private val startedAt = Instant.now()

    private fun authorized(request: HttpServletRequest): Boolean {
        val provided = request.getHeader("X-Alert-Secret") ?: return false
        // 설정이 비어 있으면 항상 거절(fail-closed), 비교는 상수 시간
        return secret.isNotBlank() && MessageDigest.isEqual(provided.toByteArray(), secret.toByteArray())
    }

    private fun unauthorized() = ResponseEntity.status(401).body(mapOf("success" to false, "error" to mapOf("code" to "unauthorized")))
    private fun invalid(detail: String) =
        ResponseEntity.status(422).body(mapOf("success" to false, "error" to mapOf("code" to "invalid_request", "detail" to detail)))

    /**
     * 감시 루프가 살아 있는가 — 엣지의 감시자와 배포가 본다.
     * - ok: 활성 자리이고 마지막 한 바퀴가 2분 안
     * - starting: 뜬 지 2분이 안 됐고 아직 첫 바퀴 전
     * - standby: 대기 자리(다른 자리가 감시한다). 프로세스는 정상이므로 200 — 다만 밖에서 이 값이 계속 보이면
     *   아무도 감시하지 않는다는 뜻이라 엣지의 감시자는 이것을 실패로 센다
     * - stalled(503): 활성인데 감시가 돌지 않는다
     */
    @GetMapping("/health")
    fun health(): ResponseEntity<Map<String, Any>> = health(Instant.now())

    internal fun health(now: Instant): ResponseEntity<Map<String, Any>> {
        val lastRoundAt = round.lastRoundAt
        val fresh = lastRoundAt != null && Duration.between(lastRoundAt, now) < Duration.ofMinutes(2)
        val status = when {
            !round.active -> "standby"
            fresh -> "ok"
            lastRoundAt == null && Duration.between(startedAt, now) < Duration.ofMinutes(2) -> "starting"
            else -> "stalled"
        }
        val body = mapOf("status" to status, "last_round_at" to (lastRoundAt?.toString() ?: ""), "active" to round.active)
        return if (status == "stalled") ResponseEntity.status(503).body(body) else ResponseEntity.ok(body)
    }

    data class EventBody(val source: String? = null, val kind: String? = null, val severity: String? = null,
                         val key: String? = null, val title: String? = null, val detail: String? = null)

    @PostMapping("/events")
    fun postEvent(request: HttpServletRequest, @RequestBody body: EventBody): ResponseEntity<out Any> {
        if (!authorized(request)) return unauthorized()
        val source = body.source?.takeIf { it.matches(Regex("^[a-z0-9-]{1,40}$")) } ?: return invalid("source")
        val kind = body.kind?.takeIf { it.matches(Regex("^[a-z0-9_]{1,40}$")) } ?: return invalid("kind")
        val title = body.title?.trim()?.takeIf { it.isNotEmpty() }?.take(200) ?: return invalid("title")
        val severity = when (body.severity ?: "warning") {
            "info" -> Notification.Severity.INFO
            "warning" -> Notification.Severity.WARNING
            "critical" -> Notification.Severity.CRITICAL
            else -> return invalid("severity")
        }
        val key = (body.key?.take(120)?.takeIf { it.isNotBlank() }) ?: "$source:$kind"
        val sent = intake.accept(AlertEvent(source, kind, severity, key, title, (body.detail ?: "").take(1500)))
        return ResponseEntity.accepted().body(mapOf("success" to true, "data" to mapOf("sent" to sent, "key" to key)))
    }

    @GetMapping("/monitors")
    fun monitors(request: HttpServletRequest): ResponseEntity<out Any> {
        if (!authorized(request)) return unauthorized()
        val monitors = round.snapshot().map { (name, state) ->
            mapOf("name" to name, "status" to state.status.name, "down_since" to (state.downSince?.toString() ?: ""),
                "reason" to state.lastReason)
        }
        return ResponseEntity.ok(mapOf("success" to true, "data" to mapOf("monitors" to monitors,
            "last_round_at" to (round.lastRoundAt?.toString() ?: ""), "undelivered" to notifier.undeliveredCount)))
    }

    @GetMapping("/incidents")
    fun incidents(request: HttpServletRequest, @RequestParam(defaultValue = "50") limit: Int): ResponseEntity<out Any> {
        if (!authorized(request)) return unauthorized()
        val store = storeHolder.store ?: return ResponseEntity.status(503)
            .body(mapOf("success" to false, "error" to mapOf("code" to "history_unavailable")))
        val incidents = store.recent(limit.coerceIn(1, 200)).map {
            mapOf("at" to it.at.toString(), "kind" to it.kind, "key" to it.key, "title" to it.title, "detail" to it.detail,
                "delivered_by" to (it.deliveredBy ?: ""), "suppressed" to it.suppressed)
        }
        return ResponseEntity.ok(mapOf("success" to true, "data" to mapOf("incidents" to incidents)))
    }

    /**
     * 무중단 배포의 정리 알림 — 구 자리는 곧바로 감시와 발송을 내려놓는다(새 자리가 이어받는다). 자리의 로컬 포트로만 부른다.
     * 프록시가 여전히 이 자리로 보내고 있으면(전환이 되돌려졌거나 정지가 보류된 경우) 다음 바퀴에 다시 맡는다.
     */
    @PostMapping("/admin/drain")
    fun drain(): Map<String, Any> {
        round.standDown()
        return mapOf("success" to true)
    }
}
