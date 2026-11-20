package xyz.junproject.alert.monitor

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class MonitorTrackerTest {
    private val tracker = MonitorTracker(failureThreshold = 3, remindEvery = Duration.ofHours(1))
    private val start: Instant = Instant.parse("2026-11-20T00:00:00Z")
    private fun at(seconds: Long): Instant = start.plusSeconds(seconds)
    private val failed = ProbeResult.failed("status 503")

    /** 결과를 30초 간격으로 차례로 넣고, 생긴 변화만 모은다 */
    private fun run(results: List<ProbeResult>, initial: MonitorState = MonitorState()): Pair<MonitorState, List<MonitorTransition>> {
        var state = initial
        val transitions = mutableListOf<MonitorTransition>()
        results.forEachIndexed { index, result ->
            val (next, transition) = tracker.advance("llm", state, result, at(index * 30L))
            state = next
            transition?.let(transitions::add)
        }
        return state to transitions
    }

    @Test
    fun `한두 번의 실패로는 알리지 않는다`() {
        val (state, transitions) = run(listOf(ProbeResult.HEALTHY, failed, failed, ProbeResult.HEALTHY))
        assertTrue(transitions.isEmpty())
        assertEquals(MonitorStatus.UP, state.status)
        assertEquals(0, state.consecutiveFailures)
    }

    @Test
    fun `세 번 연속 실패하면 DOWN 알림이 한 번 나간다`() {
        val (state, transitions) = run(listOf(ProbeResult.HEALTHY, failed, failed, failed, failed, failed))
        assertEquals(listOf<MonitorTransition>(MonitorTransition.WentDown("llm", "status 503", at(90))), transitions)
        assertEquals(MonitorStatus.DOWN, state.status)
        assertEquals(at(90), state.downSince)
    }

    @Test
    fun `복구되면 끊긴 시간과 함께 알린다`() {
        val (state, transitions) = run(listOf(failed, failed, failed, failed, ProbeResult.HEALTHY))
        assertEquals(2, transitions.size)
        assertEquals(MonitorTransition.Recovered("llm", Duration.ofSeconds(60), at(120)), transitions[1])
        assertEquals(MonitorStatus.UP, state.status)
    }

    @Test
    fun `DOWN이 이어지면 한 시간마다 다시 알린다`() {
        val results = List(3 + 125) { failed }           // DOWN 뒤로 125번(62분 30초) 더 실패
        val (_, transitions) = run(results)
        assertEquals(2, transitions.size)
        val reminder = transitions[1] as MonitorTransition.StillDown
        assertEquals(Duration.ofHours(1), reminder.downFor)
    }

    @Test
    fun `재시작 직후에는 모름에서 시작해 세 번 확인 전에는 알리지 않는다`() {
        val (state, transitions) = run(listOf(failed, failed))
        assertTrue(transitions.isEmpty())
        assertEquals(MonitorStatus.UNKNOWN, state.status)
    }

    @Test
    fun `재시작 전부터 DOWN이던 대상은 다시 알리지 않고 이어 간다`() {
        val carried = MonitorState(MonitorStatus.DOWN, consecutiveFailures = 10, downSince = at(-600), lastAlertAt = at(-600))
        val (state, transitions) = run(listOf(failed, failed, failed), initial = carried)
        assertTrue(transitions.isEmpty(), "이미 알린 장애를 재시작했다고 또 알렸다: $transitions")
        assertEquals(at(-600), state.downSince)
    }

    @Test
    fun `재시작 전부터 DOWN이던 대상이 돌아오면 원래 시각부터 센 시간으로 복구를 알린다`() {
        val carried = MonitorState(MonitorStatus.DOWN, consecutiveFailures = 10, downSince = at(-600), lastAlertAt = at(-600))
        val (_, transitions) = run(listOf(ProbeResult.HEALTHY), initial = carried)
        assertEquals(listOf<MonitorTransition>(MonitorTransition.Recovered("llm", Duration.ofSeconds(600), at(0))), transitions)
    }

    @Test
    fun `모름에서 바로 정상이면 알리지 않는다`() {
        val (state, transitions) = run(listOf(ProbeResult.HEALTHY))
        assertTrue(transitions.isEmpty())
        assertEquals(MonitorStatus.UP, state.status)
    }
}
