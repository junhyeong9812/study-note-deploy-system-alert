package xyz.junproject.alert.summary

import xyz.junproject.alert.incident.Incident
import xyz.junproject.alert.monitor.MonitorState
import xyz.junproject.alert.monitor.MonitorStatus
import xyz.junproject.alert.monitor.readable
import xyz.junproject.alert.notify.Notification
import java.time.Duration
import java.time.Instant

/** 하루 한 번 보내는 요약 — 오지 않으면 그것이 알림 서버(와 엣지)가 죽었다는 신호다. */
object DailySummary {
    fun compose(states: Map<String, MonitorState>, lastDay: List<Incident>, undelivered: Int, now: Instant): Notification {
        val down = states.filterValues { it.status == MonitorStatus.DOWN }
        val unknown = states.filterValues { it.status == MonitorStatus.UNKNOWN }.keys
        val lines = mutableListOf<String>()
        lines += "대상 ${states.size}개: 정상 ${states.size - down.size - unknown.size}, 장애 ${down.size}, 확인 전 ${unknown.size}"
        down.forEach { (name, state) ->
            val since = state.downSince?.let { readable(Duration.between(it, now)) } ?: "?"
            lines += "- 장애: $name (${since}째) ${state.lastReason}"
        }
        if (unknown.isNotEmpty()) lines += "- 확인 전: ${unknown.joinToString(", ")}"
        val downs = lastDay.filter { it.kind == "monitor_down" }
        val events = lastDay.filter { it.kind == "event" }
        lines += "지난 24시간: 장애 알림 ${downs.size}건, 복구 ${lastDay.count { it.kind == "monitor_up" }}건, " +
            "접수한 사건 ${events.size}건(반복이라 발송 안 함 ${events.count { it.suppressed }}건)"
        val failedToSend = lastDay.count { it.deliveredBy == null && !it.suppressed && it.kind != "summary" }
        lines += "발송 실패 ${failedToSend}건, 아직 못 보낸 알림 ${undelivered}건"
        val severity = if (down.isEmpty()) Notification.Severity.INFO else Notification.Severity.WARNING
        return Notification("하루 요약 — ${if (down.isEmpty()) "모두 정상" else "장애 ${down.size}개"}", lines.joinToString("\n"), severity)
    }
}
