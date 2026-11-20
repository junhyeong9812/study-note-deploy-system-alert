package xyz.junproject.alert.notify

import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender

/** 메일 — 웹훅 채널이 모두 실패했을 때의 마지막 수단이자, 하루 한 번 요약의 두 번째 수신처 */
class MailChannel(
    private val sender: JavaMailSender,
    private val from: String,
    private val to: String,
) : NotificationChannel {
    override val name = "mail"
    override fun send(notification: Notification) {
        sender.send(SimpleMailMessage().apply {
            from = this@MailChannel.from
            setTo(this@MailChannel.to)
            subject = "${marker(notification)} ${notification.title}".take(200)
            text = notification.asText()
        })
    }
}
