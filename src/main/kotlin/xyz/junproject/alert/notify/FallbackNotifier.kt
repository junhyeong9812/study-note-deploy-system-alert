package xyz.junproject.alert.notify

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 채널을 순서대로 시도한다 — 앞 채널이 실패하면 다음 채널로.
 * 모든 채널이 실패한 알림은 버리지 않고 보관했다가 [retryUndelivered]에서 다시 보낸다.
 *
 * 발송은 전용 스레드 하나([worker])에서 한다 — 채널이 느리거나 죽어도 감시 한 바퀴와 사건 접수를 붙잡지 않는다.
 * 알림은 넣은 순서대로 나간다(앞의 발송이 느리면 뒤도 기다린다 — 어차피 같은 채널을 쓴다).
 */
class FallbackNotifier(
    private val channels: List<NotificationChannel>,
    private val attemptsPerChannel: Int = 3,          // 처음 1회 + 재시도 2회
    private val undeliveredLimit: Int = 200,           // 보관 상한 — 넘으면 오래된 것부터 버리고 그 사실을 남긴다
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    private val worker: Executor = Executors.newSingleThreadExecutor { task -> Thread(task, "alert-sender").apply { isDaemon = true } },
) {
    private class Pending(val notification: Notification, val onDelivered: (String) -> Unit)

    private val log = LoggerFactory.getLogger("alert")
    private val undelivered = ConcurrentLinkedQueue<Pending>()
    private val retryQueued = AtomicBoolean(false)

    val undeliveredCount: Int get() = undelivered.size

    /** 발송을 맡기고 바로 돌아온다. 나가면 [onDelivered]에 나간 채널 이름을 알려 준다(나중에 재시도로 나간 경우도). */
    fun submit(notification: Notification, onDelivered: (String) -> Unit = {}) {
        worker.execute {
            runCatching { notify(notification, onDelivered) }
                .onFailure { log.error("alert:alert:send failed unexpectedly for '{}': {}", notification.title, it.javaClass.simpleName) }
        }
    }

    /** 그 자리에서 보낸다(발송 스레드가 부른다). 모든 채널이 실패하면 보관한다. */
    fun notify(notification: Notification, onDelivered: (String) -> Unit = {}): DeliveryResult {
        val result = deliver(notification)
        val channel = result.deliveredBy
        if (channel != null) {
            runCatching { onDelivered(channel) }
        } else {
            keep(Pending(notification, onDelivered))
            log.error("alert:alert:all channels failed for '{}': {}", notification.title, result.failures)
        }
        return result
    }

    /** 재시작 전에 못 보낸 알림을 다시 보관함에 올린다(기록에서 읽어 온 것). */
    fun restore(notification: Notification, onDelivered: (String) -> Unit) = keep(Pending(notification, onDelivered))

    private fun keep(pending: Pending) {
        undelivered.add(pending)
        while (undelivered.size > undeliveredLimit) {
            val dropped = undelivered.poll() ?: break
            log.error("alert:alert:undelivered queue full — dropped: {}", dropped.notification.title)
        }
    }

    /** 못 보낸 알림의 재시도를 발송 스레드에 맡긴다(이미 맡겨 둔 재시도가 있으면 겹쳐 넣지 않는다). */
    fun retryUndelivered() {
        if (undelivered.isEmpty() || !retryQueued.compareAndSet(false, true)) return
        worker.execute { try { retryNow() } finally { retryQueued.set(false) } }
    }

    /**
     * 못 보낸 알림을 오래된 것부터 다시 보낸다. 한 건이 또 모든 채널에서 실패하면 거기서 멈춘다 —
     * 채널이 죽어 있는 동안 보관함 전체를 돌며 시간을 쓰지 않는다. 보낸 건수를 돌려준다.
     */
    fun retryNow(): Int {
        var delivered = 0
        while (true) {
            val pending = undelivered.peek() ?: break
            val channel = deliver(pending.notification).deliveredBy ?: break
            undelivered.remove(pending)
            runCatching { pending.onDelivered(channel) }
            delivered++
        }
        return delivered
    }

    private fun deliver(notification: Notification): DeliveryResult {
        val failures = mutableListOf<String>()
        for (channel in channels) {
            repeat(attemptsPerChannel) { attempt ->
                try {
                    channel.send(notification)
                    return DeliveryResult(channel.name, failures)
                } catch (error: Exception) {
                    failures += "${channel.name}#${attempt + 1}: ${error.message ?: error.javaClass.simpleName}"
                    if (attempt < attemptsPerChannel - 1) pause(500L * (attempt + 1))
                }
            }
        }
        return DeliveryResult(null, failures)
    }
}
