package xyz.junproject.alert.notify

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FallbackNotifierTest {
    private class RecordingChannel(override val name: String, var failuresLeft: Int = 0) : NotificationChannel {
        val received = mutableListOf<Notification>()
        var attempts = 0
        override fun send(notification: Notification) {
            attempts++
            if (failuresLeft != 0) {
                if (failuresLeft > 0) failuresLeft--
                throw IllegalStateException("$name down")
            }
            received += notification
        }
    }

    private val alarm = Notification("llm DOWN", "status 503", Notification.Severity.CRITICAL)
    private fun notifier(vararg channels: NotificationChannel) = FallbackNotifier(channels.toList(), pause = {})

    @Test
    fun `첫 채널이 되면 다음 채널은 부르지 않는다`() {
        val discord = RecordingChannel("discord"); val slack = RecordingChannel("slack")
        val result = notifier(discord, slack).notify(alarm)
        assertEquals("discord", result.deliveredBy)
        assertEquals(1, discord.received.size)
        assertEquals(0, slack.attempts)
    }

    @Test
    fun `한 채널을 세 번 시도한 뒤 다음 채널로 넘어간다`() {
        val discord = RecordingChannel("discord", failuresLeft = -1); val slack = RecordingChannel("slack")
        val mail = RecordingChannel("mail")
        val result = notifier(discord, slack, mail).notify(alarm)
        assertEquals("slack", result.deliveredBy)
        assertEquals(3, discord.attempts)
        assertEquals(0, mail.attempts)
        assertEquals(3, result.failures.size)
    }

    @Test
    fun `잠깐 실패한 채널은 재시도로 나간다`() {
        val discord = RecordingChannel("discord", failuresLeft = 2); val slack = RecordingChannel("slack")
        val result = notifier(discord, slack).notify(alarm)
        assertEquals("discord", result.deliveredBy)
        assertEquals(0, slack.attempts)
    }

    @Test
    fun `모든 채널이 실패하면 버리지 않고 보관했다가 다시 보낸다`() {
        val discord = RecordingChannel("discord", failuresLeft = -1); val slack = RecordingChannel("slack", failuresLeft = -1)
        val notifier = notifier(discord, slack)
        val result = notifier.notify(alarm)
        assertFalse(result.delivered)
        assertEquals(6, result.failures.size)
        assertEquals(1, notifier.undeliveredCount)

        assertEquals(0, notifier.retryUndelivered())        // 아직 둘 다 죽어 있다
        assertEquals(1, notifier.undeliveredCount)

        slack.failuresLeft = 0                              // 슬랙이 돌아왔다
        assertEquals(1, notifier.retryUndelivered())
        assertEquals(0, notifier.undeliveredCount)
        assertEquals(listOf(alarm), slack.received)
    }

    @Test
    fun `보관 상한을 넘으면 오래된 것부터 버린다`() {
        val dead = RecordingChannel("discord", failuresLeft = -1)
        val notifier = FallbackNotifier(listOf(dead), attemptsPerChannel = 1, undeliveredLimit = 2, pause = {})
        repeat(5) { notifier.notify(Notification("alarm $it", "")) }
        assertEquals(2, notifier.undeliveredCount)
        dead.failuresLeft = 0
        notifier.retryUndelivered()
        assertEquals(listOf("alarm 3", "alarm 4"), dead.received.map { it.title })
    }
}
