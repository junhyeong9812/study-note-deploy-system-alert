package xyz.junproject.alert.notify

/** 사람에게 보낼 알림 한 건 */
data class Notification(
    val title: String,
    val body: String,
    val severity: Severity = Severity.WARNING,
) {
    enum class Severity { INFO, WARNING, CRITICAL }

    /** 채널이 글자만 받을 때 쓰는 한 덩어리 */
    fun asText(): String = if (body.isBlank()) title else "$title\n$body"
}

/** 발송 채널 하나(디스코드·슬랙·메일). 실패는 예외로 알린다. */
interface NotificationChannel {
    val name: String
    fun send(notification: Notification)
}

/** 어느 채널로 나갔는지, 또는 왜 못 나갔는지 */
data class DeliveryResult(val deliveredBy: String?, val failures: List<String>) {
    val delivered: Boolean get() = deliveredBy != null
}
