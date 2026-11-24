package xyz.junproject.alert.monitor

import java.time.Duration
import java.time.Instant

/** 한 번 확인한 결과. 실패면 사람이 읽을 이유를 담는다. */
data class ProbeResult(val healthy: Boolean, val reason: String = "") {
    companion object {
        val HEALTHY = ProbeResult(true)
        fun failed(reason: String) = ProbeResult(false, reason)
    }
}

enum class MonitorStatus { UNKNOWN, UP, DOWN }

/** 감시 대상 하나의 현재 상태 — 기록에 남기고 재시작 뒤 이어 받는다. */
data class MonitorState(
    val status: MonitorStatus = MonitorStatus.UNKNOWN,
    val consecutiveFailures: Int = 0,
    val downSince: Instant? = null,
    val lastAlertAt: Instant? = null,
    val lastReason: String = "",
)

/** 알릴 일이 생긴 상태 변화 */
sealed interface MonitorTransition {
    val monitor: String

    /** 연속 실패가 기준을 넘어 DOWN이 됐다 */
    data class WentDown(override val monitor: String, val reason: String, val at: Instant) : MonitorTransition

    /** DOWN이던 대상이 돌아왔다 */
    data class Recovered(override val monitor: String, val downFor: Duration, val at: Instant) : MonitorTransition

    /** DOWN이 이어져 다시 알릴 때가 됐다 */
    data class StillDown(override val monitor: String, val reason: String, val downFor: Duration, val at: Instant) :
        MonitorTransition
}

/**
 * 판정 규칙 — 한 번의 실패로는 알리지 않는다.
 * [failureThreshold]번 연속 실패하면 DOWN(알림 1회), 돌아오면 복구 알림, DOWN이 이어지면 [remindEvery]마다 다시 알린다.
 */
class MonitorTracker(
    val failureThreshold: Int = 3,
    val remindEvery: Duration = Duration.ofHours(1),
) {
    /** 새 확인 결과를 반영한 다음 상태와, 알릴 일이 있으면 그 변화를 돌려준다. */
    fun advance(monitor: String, state: MonitorState, result: ProbeResult, now: Instant): Pair<MonitorState, MonitorTransition?> {
        if (result.healthy) {
            val transition = if (state.status == MonitorStatus.DOWN && state.downSince != null) {
                MonitorTransition.Recovered(monitor, Duration.between(state.downSince, now), now)
            } else null
            return MonitorState(status = MonitorStatus.UP) to transition
        }
        val failures = state.consecutiveFailures + 1
        if (state.status == MonitorStatus.DOWN) {
            val downSince = state.downSince ?: now
            val dueForReminder = state.lastAlertAt == null || !now.isBefore(state.lastAlertAt.plus(remindEvery))
            return if (dueForReminder) {
                state.copy(consecutiveFailures = failures, lastAlertAt = now, lastReason = result.reason) to
                    MonitorTransition.StillDown(monitor, result.reason, Duration.between(downSince, now), now)
            } else {
                state.copy(consecutiveFailures = failures, lastReason = result.reason) to null
            }
        }
        if (failures >= failureThreshold) {
            return MonitorState(MonitorStatus.DOWN, failures, downSince = now, lastAlertAt = now, lastReason = result.reason) to
                MonitorTransition.WentDown(monitor, result.reason, now)
        }
        // 아직 기준 미달 — 상태(UNKNOWN·UP)는 그대로 두고 실패 횟수만 센다
        return state.copy(consecutiveFailures = failures, lastReason = result.reason) to null
    }
}
