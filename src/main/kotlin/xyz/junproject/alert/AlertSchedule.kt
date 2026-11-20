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

    @Scheduled(cron = "\${alert.summary-cron:0 0 9 * * *}", zone = "Asia/Seoul")
    fun sendDailySummary() {
        if (round.draining) return
        val now = Instant.now()
        val lastDay = runCatching { storeHolder.store?.since(now.minus(Duration.ofDays(1))) }.getOrNull() ?: emptyList()
        val summary = DailySummary.compose(round.snapshot(), lastDay, notifier.undeliveredCount, now)
        val result = notifier.notify(summary)
        // 요약은 메일로도 보낸다(첫 채널이 메일이 아니었다면)
        if (result.deliveredBy != "mail") channels.filterIsInstance<MailChannel>().firstOrNull()?.let { mail ->
            runCatching { mail.send(summary) }.onFailure { log.warn("alert:alert:summary mail failed: {}", it.message) }
        }
        runCatching { storeHolder.store?.record(Incident(now, "summary", "daily", summary.title, summary.body, result.deliveredBy)) }
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Seoul")
    fun deleteOldIncidents() {
        runCatching { storeHolder.store?.deleteOlderThan(Instant.now().minus(Duration.ofDays(30))) }
            .onSuccess { log.info("alert:alert:deleted {} incidents older than 30 days", it ?: 0) }
            .onFailure { log.warn("alert:alert:retention failed: {}", it.message) }
    }
}
