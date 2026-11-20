package xyz.junproject.alert.monitor

import org.slf4j.LoggerFactory
import xyz.junproject.alert.incident.Incident
import xyz.junproject.alert.incident.IncidentStore
import xyz.junproject.alert.notify.FallbackNotifier
import xyz.junproject.alert.notify.Notification
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 감시 한 바퀴 — 모든 대상을 동시에 확인하고, 생긴 변화를 알린다.
 * 대상 하나가 느려도 다른 대상의 확인과 발송은 기다리지 않는다.
 */
class MonitorRound(
    private val definitions: List<MonitorDefinition>,
    private val probe: MonitorProbe,
    private val tracker: MonitorTracker,
    private val notifier: FallbackNotifier,
    private val store: IncidentStore?,
    private val roundBudget: Duration = Duration.ofSeconds(10),
    private val clock: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger("alert")
    private val states = ConcurrentHashMap<String, MonitorState>()
    @Volatile var lastRoundAt: Instant? = null
        private set
    @Volatile var draining: Boolean = false   // 교체 중인 구 자리 — 확인도 발송도 하지 않는다(새 자리가 맡는다)

    init {
        // 재시작 전부터 DOWN 이던 대상은 이어 받는다 — 같은 장애를 재시작했다고 또 알리지 않는다
        runCatching { store?.loadDownStates() }.getOrNull()?.forEach { (name, state) ->
            if (definitions.any { it.name == name }) states[name] = state
        }
    }

    fun snapshot(): Map<String, MonitorState> = definitions.associate { it.name to (states[it.name] ?: MonitorState()) }

    fun run() {
        if (draining) return
        val now = clock()
        val results = probeAll()
        if (draining) return   // 확인하는 사이 교체가 시작됐다 — 이 결과로는 알리지 않는다
        val transitions = mutableListOf<MonitorTransition>()
        for (definition in definitions) {
            val result = results[definition.name] ?: ProbeResult.failed("${roundBudget.seconds}초 안에 확인을 끝내지 못했다")
            val (next, transition) = tracker.advance(definition.name, states[definition.name] ?: MonitorState(), result, now)
            states[definition.name] = next
            runCatching { store?.saveState(definition.name, next, now) }
                .onFailure { log.warn("alert:alert:state not recorded for {}: {}", definition.name, it.message) }
            transition?.let(transitions::add)
        }
        lastRoundAt = now
        announce(transitions, now)
        notifier.retryUndelivered()
    }

    private fun probeAll(): Map<String, ProbeResult> {
        val results = ConcurrentHashMap<String, ProbeResult>()
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        try {
            definitions.forEach { definition ->
                executor.submit { results[definition.name] = runCatching { probe.check(definition) }.getOrElse { ProbeResult.failed(it.message ?: "확인 실패") } }
            }
            executor.shutdown()
            executor.awaitTermination(roundBudget.toMillis(), TimeUnit.MILLISECONDS)
        } finally {
            executor.shutdownNow()   // 예산을 넘긴 확인은 버린다 — 결과가 없는 대상은 실패로 센다
        }
        return results
    }

    /** 같은 바퀴에 DOWN 이 된 대상은 한 알림으로 묶는다(서버 한 대가 꺼졌을 때 알림이 쏟아지지 않게). */
    private fun announce(transitions: List<MonitorTransition>, now: Instant) {
        val wentDown = transitions.filterIsInstance<MonitorTransition.WentDown>()
        if (wentDown.size > 1) {
            val body = wentDown.joinToString("\n") { "- ${it.monitor}: ${it.reason}" }
            send("monitor_down", wentDown.joinToString(",") { it.monitor },
                Notification("응답 없음: 대상 ${wentDown.size}개", body, Notification.Severity.CRITICAL), now)
        } else wentDown.forEach {
            send("monitor_down", it.monitor,
                Notification("응답 없음: ${it.monitor}", it.reason, Notification.Severity.CRITICAL), now)
        }
        transitions.filterIsInstance<MonitorTransition.Recovered>().forEach {
            send("monitor_up", it.monitor,
                Notification("복구: ${it.monitor}", "끊긴 시간 ${readable(it.downFor)}", Notification.Severity.INFO), now)
        }
        transitions.filterIsInstance<MonitorTransition.StillDown>().forEach {
            send("monitor_still_down", it.monitor,
                Notification("아직 응답 없음: ${it.monitor} (${readable(it.downFor)}째)", it.reason, Notification.Severity.CRITICAL), now)
        }
    }

    private fun send(kind: String, key: String, notification: Notification, now: Instant) {
        val result = notifier.notify(notification)
        runCatching { store?.record(Incident(now, kind, key, notification.title, notification.body, result.deliveredBy)) }
            .onFailure { log.warn("alert:alert:incident not recorded ({}): {}", kind, it.message) }
    }
}

fun readable(duration: Duration): String {
    val seconds = duration.seconds
    return when {
        seconds < 60 -> "${seconds}초"
        seconds < 3600 -> "${seconds / 60}분 ${seconds % 60}초"
        else -> "${seconds / 3600}시간 ${(seconds % 3600) / 60}분"
    }
}
