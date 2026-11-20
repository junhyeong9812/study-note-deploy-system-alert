package xyz.junproject.alert.event

import org.slf4j.LoggerFactory
import xyz.junproject.alert.incident.Incident
import xyz.junproject.alert.incident.IncidentStore
import xyz.junproject.alert.notify.FallbackNotifier
import xyz.junproject.alert.notify.Notification
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** 다른 서버가 알려 온 일 */
data class AlertEvent(
    val source: String,
    val kind: String,
    val severity: Notification.Severity,
    val key: String,
    val title: String,
    val detail: String,
)

/**
 * 받은 일을 알린다. 같은 key 는 [suppressFor] 안에 한 번만 발송한다(같은 일이 연달아 올 때 알림이 쏟아지지 않게).
 * 발송하지 않은 것도 접수 사실은 전부 기록한다. 발송은 맡기기만 하고 바로 돌아온다(보낸 쪽을 기다리게 하지 않는다).
 *
 * 요청 하나는 한 자리에만 도착하므로 받은 자리가 처리한다 — 배포로 대기 중인 자리에 도착한 사건도 버리지 않는다.
 */
class EventIntake(
    private val notifier: FallbackNotifier,
    private val store: IncidentStore?,
    private val suppressFor: Duration = Duration.ofMinutes(10),
    private val clock: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger("alert")
    private val lastSentAt = ConcurrentHashMap<String, Instant>()

    /** 발송을 맡겼으면 true, 반복이라 억제했으면 false */
    fun accept(event: AlertEvent): Boolean {
        val now = clock()
        lastSentAt.entries.removeIf { now.isAfter(it.value.plus(suppressFor)) }   // 지난 것은 잊는다(메모리 상한)
        // 실제로 발송을 맡길 때만 시각을 적는다(억제된 접수가 억제 창을 늘리지 않게)
        val suppressed = synchronized(lastSentAt) {
            val repeated = lastSentAt.containsKey(event.key) || sentByAnotherSlot(event.key, now)
            if (!repeated) lastSentAt[event.key] = now
            repeated
        }
        val notification = Notification("[${event.source}] ${event.title}", event.detail, event.severity)
        val id = runCatching { store?.record(Incident(now, "event", event.key, notification.title, event.detail, null, suppressed)) }
            .onFailure { log.warn("alert:alert:event not recorded ({}): {}", event.key, it.message) }.getOrNull()
        if (!suppressed) notifier.submit(notification) { channel ->
            if (id != null) runCatching { store?.markDelivered(id, channel) }
                .onFailure { log.warn("alert:alert:delivery not recorded (incident {}): {}", id, it.message) }
        }
        return !suppressed
    }

    /** 이 자리의 메모리에는 없지만 기록에 최근 발송이 있다 — 재시작했거나 배포로 자리가 바뀐 경우에도 억제를 잇는다. */
    private fun sentByAnotherSlot(key: String, now: Instant): Boolean =
        runCatching { store?.eventSentSince(key, now.minus(suppressFor)) }.getOrNull() == true
}
