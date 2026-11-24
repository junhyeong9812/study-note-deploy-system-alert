package xyz.junproject.alert

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import xyz.junproject.alert.incident.Incident
import xyz.junproject.alert.monitor.MonitorRound
import xyz.junproject.alert.notify.FallbackNotifier
import xyz.junproject.alert.notify.MailChannel
import xyz.junproject.alert.notify.NotificationChannel
import xyz.junproject.alert.summary.DailySummary
import java.time.Duration
import java.time.Instant

/** 주기 작업 — 30초마다 한 바퀴, 매일 09:00(KST) 요약, 하루 한 번 오래된 기록 삭제 */
@Component
class AlertSchedule(
    private val round: MonitorRound,
    private val notifier: FallbackNotifier,
    private val channels: List<NotificationChannel>,
    private val storeHolder: StoreHolder,
) {
    private val log = LoggerFactory.getLogger("alert")

    @Scheduled(fixedDelayString = "\${alert.round-interval-ms:30000}", initialDelayString = "\${alert.round-initial-delay-ms:5000}")
    fun runRound() {
        try { round.run() } catch (error: Exception) { log.error("alert:alert:round failed: {}", error.message, error) }
    }

    @Scheduled(cron = "\${alert.summary-cron:0 0 9 * * *}", zone = SUMMARY_ZONE)
    fun sendDailySummary() {
        if (!round.active) return   // 대기 자리는 보내지 않는다 — 활성 자리 하나만
        val now = Instant.now()
        val store = storeHolder.store
        val from = now.minus(Duration.ofDays(1))
        // 기록을 못 읽으면 null — 요약이 0건으로 꾸미지 않고 "읽지 못했다"고 적는다
        val changes = runCatching { store?.changesSince(from) }.getOrNull()
        val lastDay = runCatching { store?.since(from) }.getOrNull()
        val summary = DailySummary.compose(round.snapshot(), changes, lastDay, notifier.undeliveredCount, now, from)
        val id = runCatching { store?.record(Incident(now, "summary", "daily", summary.title, summary.body, null, severity = summary.severity.name)) }.getOrNull()
        notifier.submit(summary) { channel ->
            if (id != null) runCatching { store?.markDelivered(id, channel) }
            // 요약은 메일로도 보낸다(나간 채널이 메일이 아니었다면)
            if (channel != "mail") channels.filterIsInstance<MailChannel>().firstOrNull()?.let { mail ->
                runCatching { mail.send(summary) }.onFailure { log.warn("alert:alert:summary mail failed: {}", it.javaClass.simpleName) }
            }
        }
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Seoul")
    fun deleteOldIncidents() {
        runCatching { storeHolder.store?.deleteOlderThan(Instant.now().minus(Duration.ofDays(30))) }
            .onSuccess { log.info("alert:alert:deleted {} incidents older than 30 days", it ?: 0) }
            .onFailure { log.warn("alert:alert:retention failed: {}", it.message) }
    }

    companion object {
        /** 하루 요약의 시간대 — 설정 보기도 이 값을 낸다 */
        const val SUMMARY_ZONE = "Asia/Seoul"
    }
}
