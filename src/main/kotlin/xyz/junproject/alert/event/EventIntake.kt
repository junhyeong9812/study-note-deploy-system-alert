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
 * 발송하지 않은 것도 접수 사실은 전부 기록한다.
 */
class EventIntake(
    private val notifier: FallbackNotifier,
    private val store: IncidentStore?,
    private val suppressFor: Duration = Duration.ofMinutes(10),
    private val clock: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger("alert")
    private val lastSentAt = ConcurrentHashMap<String, Instant>()

    /** 발송했으면 true, 반복이라 억제했으면 false */
    fun accept(event: AlertEvent): Boolean {
        val now = clock()
        lastSentAt.entries.removeIf { now.isAfter(it.value.plus(suppressFor)) }   // 지난 것은 잊는다(메모리 상한)
        val suppressed = lastSentAt.putIfAbsent(event.key, now) != null
        val notification = Notification("[${event.source}] ${event.title}", event.detail, event.severity)
        val deliveredBy = if (suppressed) null else notifier.notify(notification).deliveredBy
        runCatching { store?.record(Incident(now, "event", event.key, notification.title, event.detail, deliveredBy, suppressed)) }
            .onFailure { log.warn("alert:alert:event not recorded ({}): {}", event.key, it.message) }
        return !suppressed
    }
}
