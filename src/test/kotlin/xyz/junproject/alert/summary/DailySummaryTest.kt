package xyz.junproject.alert.summary

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import xyz.junproject.alert.incident.Incident
import xyz.junproject.alert.incident.MonitorChange
import xyz.junproject.alert.monitor.MonitorState
import xyz.junproject.alert.monitor.MonitorStatus
import xyz.junproject.alert.notify.Notification
import java.time.Instant

class DailySummaryTest {
    private val now = Instant.parse("2026-11-21T00:00:00Z")
    private val states = linkedMapOf(
        "front" to MonitorState(MonitorStatus.UP),
        "llm" to MonitorState(MonitorStatus.DOWN, 9, downSince = now.minusSeconds(600), lastReason = "status 503 (ollama_unreachable)"),
        "backend" to MonitorState(MonitorStatus.UNKNOWN),
    )
    private val changes = listOf(
        MonitorChange(now.minusSeconds(7200), "llm", MonitorStatus.DOWN, null),
        MonitorChange(now.minusSeconds(7080), "llm", MonitorStatus.UP, 120),
        MonitorChange(now.minusSeconds(3600), "front", MonitorStatus.DOWN, null),
        MonitorChange(now.minusSeconds(3510), "front", MonitorStatus.UP, 90),
        MonitorChange(now.minusSeconds(600), "llm", MonitorStatus.DOWN, null),
    )
    private val incidents = listOf(
        Incident(now.minusSeconds(7200), "monitor_down", "llm", "응답 없음: llm", "", "discord"),
        Incident(now.minusSeconds(5000), "event", "deploy-failed:llm", "[ci-cd] 배포 실패", "", "slack"),
        Incident(now.minusSeconds(4900), "event", "deploy-failed:llm", "[ci-cd] 배포 실패", "", null, suppressed = true),
        Incident(now.minusSeconds(600), "monitor_down", "llm", "응답 없음: llm", "", null),
    )

    @Test
    fun `대상마다 현재 상태와 지난 24시간의 DOWN 횟수·합계 시간을 적는다`() {
        val summary = DailySummary.compose(states, changes, incidents, undelivered = 1, now = now)
        assertEquals("하루 요약 — 장애 1개", summary.title)
        assertEquals(Notification.Severity.WARNING, summary.severity)
        val lines = summary.body.lines()
        assertEquals("대상 3개: 정상 1, 장애 1, 확인 전 1", lines[0])
        assertEquals("- front: 정상 · 24시간 DOWN 1회, 합계 1분 30초", lines[1])
        // llm: 복구된 장애 120초 + 지금 이어지는 장애 600초
        assertEquals("- llm: 장애 10분 0초째 — status 503 (ollama_unreachable) · 24시간 DOWN 2회, 합계 12분 0초", lines[2])
        assertEquals("- backend: 확인 전", lines[3])
        assertEquals("지난 24시간: DOWN 3회, 복구 2회, 접수한 사건 2건(반복이라 발송 안 함 1건)", lines[4])
        assertEquals("끝내 못 보낸 알림 1건", lines[5])
        assertEquals("지금 보관 중인 미발송 알림 1건", lines[6])
    }

    @Test
    fun `모두 정상이고 일이 없던 날`() {
        val summary = DailySummary.compose(mapOf("front" to MonitorState(MonitorStatus.UP)), emptyList(), emptyList(), 0, now)
        assertEquals("하루 요약 — 모두 정상", summary.title)
        assertEquals(Notification.Severity.INFO, summary.severity)
        assertTrue(summary.body.contains("- front: 정상\n"), summary.body)
        assertTrue(summary.body.contains("DOWN 0회"))
    }

    @Test
    fun `기록을 읽지 못한 날은 0건이라고 하지 않고 읽지 못했다고 적는다`() {
        val summary = DailySummary.compose(states, null, null, 0, now)
        assertTrue(summary.body.contains("지난 24시간 기록을 읽지 못했다"), summary.body)
        assertFalse(summary.body.contains("0회"), summary.body)
        assertFalse(summary.body.contains("끝내 못 보낸 알림"), summary.body)
        assertTrue(summary.body.contains("- llm: 장애 10분 0초째"))      // 현재 상태는 메모리에서 나온다
    }
}
