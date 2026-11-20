package xyz.junproject.alert.notify

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
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
    private val worker: Executor = boundedWorker(),
) {
    private class Pending(val notification: Notification, val onDelivered: (String) -> Unit)

    private val log = LoggerFactory.getLogger("alert")
    private val undelivered = ConcurrentLinkedQueue<Pending>()
    private val retryQueued = AtomicBoolean(false)

    val undeliveredCount: Int get() = undelivered.size

    /** 발송을 맡기고 바로 돌아온다. 나가면 [onDelivered]에 나간 채널 이름을 알려 준다(나중에 재시도로 나간 경우도). */
    fun submit(notification: Notification, onDelivered: (String) -> Unit = {}) {
        try {
            worker.execute {
                runCatching { notify(notification, onDelivered) }
                    .onFailure { log.error("alert:alert:send failed unexpectedly for '{}': {}", notification.title, it.javaClass.simpleName) }
            }
        } catch (_: RejectedExecutionException) {
            // 발송이 밀려 대기열이 찼다 — 버리지 않고 보관함으로(보관함도 상한이 있고, 넘치면 그 사실을 남긴다)
            log.error("alert:alert:send queue full — kept for retry: {}", notification.title)
            keep(Pending(notification, onDelivered))
        }
    }

    /**
     * 보관 중인 미발송 알림을 내려놓는다(이 자리가 대기로 내려갈 때). 기록에는 미발송으로 남아 있으므로
     * 감시를 이어받은 자리가 기록에서 되살려 보낸다 — 두 자리가 같은 알림을 각자 다시 보내지 않게. 내려놓은 건수를 돌려준다.
     */
    fun abandonUndelivered(): Int {
        var abandoned = 0
        while (undelivered.poll() != null) abandoned++
        return abandoned
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

    companion object {
        const val SEND_QUEUE_LIMIT = 500

        /** 발송 스레드 하나 + 상한이 있는 대기열 — 유입이 발송보다 빨라도 메모리가 끝없이 늘지 않는다. */
        fun boundedWorker(queueLimit: Int = SEND_QUEUE_LIMIT): Executor = ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(queueLimit),
            { task -> Thread(task, "alert-sender").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy(),
        )
    }
}
