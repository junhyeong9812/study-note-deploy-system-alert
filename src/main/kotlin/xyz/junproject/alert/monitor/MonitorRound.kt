package xyz.junproject.alert.monitor

import org.slf4j.LoggerFactory
import xyz.junproject.alert.incident.Incident
import xyz.junproject.alert.incident.IncidentStore
import xyz.junproject.alert.incident.MonitorChange
import xyz.junproject.alert.notify.FallbackNotifier
import xyz.junproject.alert.notify.Notification
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 감시 한 바퀴 — 모든 대상을 동시에 확인하고, 생긴 변화를 알린다.
 * 대상 하나가 느려도 다른 대상의 확인을 기다리게 하지 않고, 발송이 느려도 다음 바퀴를 붙잡지 않는다(발송은 발송기의 스레드가 한다).
 * 활성 자리일 때만 돈다([ActiveSlot]).
 */
class MonitorRound(
    private val definitions: List<MonitorDefinition>,
    private val probe: MonitorProbe,
    private val tracker: MonitorTracker,
    private val notifier: FallbackNotifier,
    private val store: IncidentStore?,
    private val roundBudget: Duration = Duration.ofSeconds(10),
    private val activeSlot: ActiveSlot = ActiveSlot.always(),
    private val clock: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger("alert")
    private val states = ConcurrentHashMap<String, MonitorState>()
    private var holding = false            // 지금 감시를 맡고 있는가(대기 → 활성으로 바뀐 바퀴에 이어받기를 한다)
    private var restoredUndelivered = false
    @Volatile var lastRoundAt: Instant? = null
        private set

    val active: Boolean get() = activeSlot.active

    /** 배포의 정리 알림 — 대기로 내려간다(프록시가 여전히 이 자리로 보내면 다음 바퀴에 다시 맡는다). */
    fun standDown() = activeSlot.standDown()

    fun snapshot(): Map<String, MonitorState> = definitions.associate { it.name to (states[it.name] ?: MonitorState()) }

    fun run() {
        if (!activeSlot.refresh()) {
            if (holding) log.info("alert:alert:standing by — another slot is serving")
            holding = false
            return
        }
        val now = clock()
        if (!holding) takeOver(now)
        val results = probeAll()
        if (!activeSlot.active) return   // 확인하는 사이 정리 알림을 받았다 — 이 결과로는 알리지 않는다
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
        recordChanges(transitions)
        announce(transitions, now)
        notifier.retryUndelivered()
    }

    /**
     * 감시를 이어받는다(기동 직후, 또는 배포로 이 자리가 활성이 된 때).
     * 직전까지 DOWN 이던 대상은 기록에서 이어 받고 — 같은 장애를 또 알리지 않는다 — 나머지는 모름에서 다시 센다.
     * 앞 자리가 끝내 못 보낸 알림은 한 번만 보관함에 다시 올린다.
     */
    private fun takeOver(now: Instant) {
        holding = true
        states.clear()
        runCatching { store?.loadDownStates() }
            .onFailure { log.warn("alert:alert:previous states not loaded: {}", it.message) }
            .getOrNull()?.forEach { (name, state) -> if (definitions.any { it.name == name }) states[name] = state }
        log.info("alert:alert:taking over monitoring — {} monitors, {} already down", definitions.size, states.size)
        val history = store ?: return
        if (restoredUndelivered) return
        restoredUndelivered = true
        runCatching { history.undeliveredSince(now.minus(Duration.ofDays(1)), 200) }
            .onFailure { log.warn("alert:alert:undelivered notifications not restored: {}", it.message) }
            .getOrNull()?.forEach { (id, incident) ->
                notifier.restore(Notification(incident.title, incident.detail, severityOf(incident.kind))) { channel -> markDelivered(id, channel) }
            }
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

    /** 대상마다 한 줄 — 알림을 묶어 보내더라도 하루 요약이 대상별 횟수와 시간을 셀 수 있게. */
    private fun recordChanges(transitions: List<MonitorTransition>) {
        for (transition in transitions) {
            val change = when (transition) {
                is MonitorTransition.WentDown -> MonitorChange(transition.at, transition.monitor, MonitorStatus.DOWN, null)
                is MonitorTransition.Recovered -> MonitorChange(transition.at, transition.monitor, MonitorStatus.UP, transition.downFor.seconds)
                is MonitorTransition.StillDown -> continue
            }
            runCatching { store?.recordChange(change) }
                .onFailure { log.warn("alert:alert:change not recorded for {}: {}", change.monitor, it.message) }
        }
    }

    /** 같은 바퀴에 DOWN 이 된 대상은 한 알림으로 묶는다(서버 한 대가 꺼졌을 때 알림이 쏟아지지 않게). */
    private fun announce(transitions: List<MonitorTransition>, now: Instant) {
        val wentDown = transitions.filterIsInstance<MonitorTransition.WentDown>()
        if (wentDown.size > 1) {
            val body = wentDown.joinToString("\n") { "- ${it.monitor}: ${it.reason}" }
            send("monitor_down", wentDown.joinToString(",") { it.monitor },
                Notification("응답 없음: 대상 ${wentDown.size}개", body, severityOf("monitor_down")), now)
        } else wentDown.forEach {
            send("monitor_down", it.monitor,
                Notification("응답 없음: ${it.monitor}", it.reason, severityOf("monitor_down")), now)
        }
        transitions.filterIsInstance<MonitorTransition.Recovered>().forEach {
            send("monitor_up", it.monitor,
                Notification("복구: ${it.monitor}", "끊긴 시간 ${readable(it.downFor)}", severityOf("monitor_up")), now)
        }
        transitions.filterIsInstance<MonitorTransition.StillDown>().forEach {
            send("monitor_still_down", it.monitor,
                Notification("아직 응답 없음: ${it.monitor} (${readable(it.downFor)}째)", it.reason, severityOf("monitor_still_down")), now)
        }
    }

    /** 먼저 기록하고(발송 전에는 채널이 비어 있다) 발송을 맡긴다. 나가면 그 행에 채널을 적는다. */
    private fun send(kind: String, key: String, notification: Notification, now: Instant) {
        val id = runCatching { store?.record(Incident(now, kind, key, notification.title, notification.body, null)) }
            .onFailure { log.warn("alert:alert:incident not recorded ({}): {}", kind, it.message) }.getOrNull()
        notifier.submit(notification) { channel -> if (id != null) markDelivered(id, channel) }
    }

    private fun markDelivered(id: Long, channel: String) {
        runCatching { store?.markDelivered(id, channel) }
            .onFailure { log.warn("alert:alert:delivery not recorded (incident {}): {}", id, it.message) }
    }

    private fun severityOf(kind: String) = when (kind) {
        "monitor_down", "monitor_still_down" -> Notification.Severity.CRITICAL
        "monitor_up" -> Notification.Severity.INFO
        else -> Notification.Severity.WARNING
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
