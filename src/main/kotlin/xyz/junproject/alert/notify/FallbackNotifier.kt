package xyz.junproject.alert.notify

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 채널을 순서대로 시도한다 — 앞 채널이 실패하면 다음 채널로.
 * 모든 채널이 실패한 알림은 버리지 않고 보관했다가 [retryUndelivered]에서 다시 보낸다.
 */
class FallbackNotifier(
    private val channels: List<NotificationChannel>,
    private val attemptsPerChannel: Int = 3,          // 처음 1회 + 재시도 2회
    private val undeliveredLimit: Int = 200,           // 보관 상한 — 넘으면 오래된 것부터 버리고 그 사실을 남긴다
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val log = LoggerFactory.getLogger("alert")
    private val undelivered = ConcurrentLinkedQueue<Notification>()

    val undeliveredCount: Int get() = undelivered.size

    fun notify(notification: Notification): DeliveryResult {
        val result = deliver(notification)
        if (!result.delivered) {
            undelivered.add(notification)
            while (undelivered.size > undeliveredLimit) {
                val dropped = undelivered.poll() ?: break
                log.error("alert:alert:undelivered queue full — dropped: {}", dropped.title)
            }
            log.error("alert:alert:all channels failed for '{}': {}", notification.title, result.failures)
        }
        return result
    }

    /** 못 보낸 알림을 다시 시도한다. 이번에도 못 보낸 것은 그대로 남는다. 보낸 건수를 돌려준다. */
    fun retryUndelivered(): Int {
        var delivered = 0
        repeat(undelivered.size) {
            val notification = undelivered.poll() ?: return delivered
            if (deliver(notification).delivered) delivered++ else undelivered.add(notification)
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
