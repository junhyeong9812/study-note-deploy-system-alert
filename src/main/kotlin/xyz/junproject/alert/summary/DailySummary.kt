package xyz.junproject.alert.summary

import xyz.junproject.alert.incident.Incident
import xyz.junproject.alert.incident.MonitorChange
import xyz.junproject.alert.monitor.MonitorState
import xyz.junproject.alert.monitor.MonitorStatus
import xyz.junproject.alert.monitor.readable
import xyz.junproject.alert.notify.Notification
import java.time.Duration
import java.time.Instant

/** 하루 한 번 보내는 요약 — 오지 않으면 그것이 알림 서버(와 엣지)가 죽었다는 신호다. */
object DailySummary {
    /**
     * [changes]·[lastDay] 는 지난 24시간의 기록이다. 기록을 읽지 못했으면 null 을 넘긴다 — 0건으로 꾸미지 않고 못 읽었다고 적는다.
     * 대상별 끊긴 시간 = 그 사이 복구된 장애의 길이 합 + (지금도 장애면) 시작부터 지금까지 — 모두 지난 24시간 안의 몫만.
     */
    fun compose(states: Map<String, MonitorState>, changes: List<MonitorChange>?, lastDay: List<Incident>?,
                undelivered: Int, now: Instant, from: Instant = now.minus(Duration.ofDays(1))): Notification {
        val down = states.filterValues { it.status == MonitorStatus.DOWN }
        val unknown = states.filterValues { it.status == MonitorStatus.UNKNOWN }
        val lines = mutableListOf<String>()
        lines += "대상 ${states.size}개: 정상 ${states.size - down.size - unknown.size}, 장애 ${down.size}, 확인 전 ${unknown.size}"
        for ((name, state) in states) {
            val current = when (state.status) {
                MonitorStatus.UP -> "정상"
                MonitorStatus.UNKNOWN -> "확인 전"
                MonitorStatus.DOWN -> "장애 ${state.downSince?.let { readable(Duration.between(it, now)) } ?: "?"}째 — ${state.lastReason}"
            }
            val mine = changes?.filter { it.monitor == name }.orEmpty()
            val downCount = mine.count { it.status == MonitorStatus.DOWN }
            // 창([from]~[now]) 밖에서 시작한 장애는 창 안의 몫만 센다
            val downSeconds = mine.sumOf { minOf(it.downSeconds ?: 0, Duration.between(from, it.at).seconds) } +
                (state.downSince?.takeIf { state.status == MonitorStatus.DOWN }
                    ?.let { Duration.between(maxOf(it, from), now).seconds } ?: 0)
            val history = if (changes != null && (downCount > 0 || downSeconds > 0)) " · 24시간 DOWN ${downCount}회, 합계 ${readable(Duration.ofSeconds(downSeconds))}" else ""
            lines += "- $name: $current$history"
        }
        if (changes == null || lastDay == null) {
            lines += "지난 24시간 기록을 읽지 못했다 — 횟수·시간·발송 실패 수를 알 수 없다"
        } else {
            val events = lastDay.filter { it.kind == "event" }
            lines += "지난 24시간: DOWN ${changes.count { it.status == MonitorStatus.DOWN }}회, 복구 ${changes.count { it.status == MonitorStatus.UP }}회, " +
                "접수한 사건 ${events.size}건(반복이라 발송 안 함 ${events.count { it.suppressed }}건)"
            lines += "끝내 못 보낸 알림 ${lastDay.count { it.deliveredBy == null && !it.suppressed && it.kind != "summary" }}건"
        }
        lines += "지금 보관 중인 미발송 알림 ${undelivered}건"
        val severity = if (down.isEmpty()) Notification.Severity.INFO else Notification.Severity.WARNING
        return Notification("하루 요약 — ${if (down.isEmpty()) "모두 정상" else "장애 ${down.size}개"}", lines.joinToString("\n"), severity)
    }
}
