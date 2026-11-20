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

        assertEquals(0, notifier.retryNow())        // 아직 둘 다 죽어 있다
        assertEquals(1, notifier.undeliveredCount)

        slack.failuresLeft = 0                              // 슬랙이 돌아왔다
        assertEquals(1, notifier.retryNow())
        assertEquals(0, notifier.undeliveredCount)
        assertEquals(listOf(alarm), slack.received)
    }

    @Test
    fun `다시 보내다 한 건이 또 전부 실패하면 거기서 멈춘다 - 죽은 채널에 보관함 전체를 돌리지 않는다`() {
        val dead = RecordingChannel("discord", failuresLeft = -1)
        val notifier = FallbackNotifier(listOf(dead), attemptsPerChannel = 2, pause = {})
        repeat(5) { notifier.notify(Notification("alarm $it", "")) }
        val attemptsBefore = dead.attempts
        assertEquals(0, notifier.retryNow())
        assertEquals(2, dead.attempts - attemptsBefore, "첫 건만 시도해야 한다")
        assertEquals(5, notifier.undeliveredCount)
    }

    @Test
    fun `나중에 다시 보내 나간 것도 나간 채널을 알려 준다`() {
        val discord = RecordingChannel("discord", failuresLeft = -1)
        val notifier = notifier(discord)
        val deliveredBy = mutableListOf<String>()
        notifier.notify(alarm) { deliveredBy += it }
        notifier.restore(Notification("앞 자리가 못 보낸 것", "")) { deliveredBy += "restored:$it" }
        assertTrue(deliveredBy.isEmpty())
        discord.failuresLeft = 0
        assertEquals(2, notifier.retryNow())
        assertEquals(listOf("discord", "restored:discord"), deliveredBy)
    }

    @Test
    fun `발송을 맡기는 쪽은 느린 채널을 기다리지 않고 알림은 맡긴 순서대로 나간다`() {
        val received = java.util.concurrent.CopyOnWriteArrayList<String>()
        val slow = object : NotificationChannel {
            override val name = "discord"
            override fun send(notification: Notification) { Thread.sleep(300); received += notification.title }
        }
        val notifier = FallbackNotifier(listOf(slow), pause = {})          // 실제 발송 스레드
        val started = System.nanoTime()
        repeat(3) { notifier.submit(Notification("alarm $it", "")) }
        notifier.retryUndelivered()
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMillis < 200, "맡기는 쪽이 발송을 기다렸다: ${elapsedMillis}ms")
        val deadline = System.currentTimeMillis() + 5_000
        while (received.size < 3 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(listOf("alarm 0", "alarm 1", "alarm 2"), received)
    }

    @Test
    fun `보관 상한을 넘으면 오래된 것부터 버린다`() {
        val dead = RecordingChannel("discord", failuresLeft = -1)
        val notifier = FallbackNotifier(listOf(dead), attemptsPerChannel = 1, undeliveredLimit = 2, pause = {})
        repeat(5) { notifier.notify(Notification("alarm $it", "")) }
        assertEquals(2, notifier.undeliveredCount)
        dead.failuresLeft = 0
        notifier.retryNow()
        assertEquals(listOf("alarm 3", "alarm 4"), dead.received.map { it.title })
    }
}
