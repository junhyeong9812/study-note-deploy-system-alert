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
    private fun authorized(request: HttpServletRequest): Boolean {
        val provided = request.getHeader("X-Alert-Secret") ?: return false
        // 설정이 비어 있으면 항상 거절(fail-closed), 비교는 상수 시간
        return secret.isNotBlank() && MessageDigest.isEqual(provided.toByteArray(), secret.toByteArray())
    }

    private fun unauthorized() = ResponseEntity.status(401).body(mapOf("success" to false, "error" to mapOf("code" to "unauthorized")))
    private fun invalid(detail: String) =
        ResponseEntity.status(422).body(mapOf("success" to false, "error" to mapOf("code" to "invalid_request", "detail" to detail)))

    /** 감시 루프가 살아 있는가 — 마지막 한 바퀴가 2분 안이면 정상. 엣지의 감시자와 배포가 본다. */
    @GetMapping("/health")
    fun health(): ResponseEntity<Map<String, Any>> {
        val lastRoundAt = round.lastRoundAt
        val alive = round.draining || (lastRoundAt != null && Duration.between(lastRoundAt, Instant.now()) < Duration.ofMinutes(2))
        val status = when { alive -> "ok"; lastRoundAt == null -> "starting"; else -> "stalled" }
        val body = mapOf("status" to status, "last_round_at" to (lastRoundAt?.toString() ?: ""),
            "draining" to round.draining)
        return if (alive || lastRoundAt == null) ResponseEntity.ok(body) else ResponseEntity.status(503).body(body)
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

    /** 무중단 배포의 정리 알림 — 구 자리는 확인과 발송을 멈춘다(새 자리가 이어받는다). 자리의 로컬 포트로만 부른다. */
    @PostMapping("/admin/drain")
    fun drain(): Map<String, Any> {
        round.draining = true
        return mapOf("success" to true)
    }
}
