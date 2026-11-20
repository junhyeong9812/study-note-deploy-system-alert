package xyz.junproject.alert

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.Resource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.mail.javamail.JavaMailSender
import tools.jackson.databind.ObjectMapper
import xyz.junproject.alert.event.EventIntake
import xyz.junproject.alert.incident.IncidentStore
import xyz.junproject.alert.monitor.HttpMonitorProbe
import xyz.junproject.alert.monitor.MonitorRound
import xyz.junproject.alert.monitor.MonitorTracker
import xyz.junproject.alert.monitor.MonitorsFile
import xyz.junproject.alert.notify.DiscordChannel
import xyz.junproject.alert.notify.FallbackNotifier
import xyz.junproject.alert.notify.MailChannel
import xyz.junproject.alert.notify.NotificationChannel
import xyz.junproject.alert.notify.SlackChannel
import java.io.File
import java.nio.charset.StandardCharsets

/** 조립 — 환경변수에서 연결 정보를 읽어 구성 요소를 엮는다. 채널 주소 같은 비밀값은 로그에 내지 않는다. */
@Configuration
class AlertConfiguration {
    private val log = LoggerFactory.getLogger("alert")

    /** 설정된 채널만 순서대로: 디스코드 → 슬랙 → 메일. 하나도 없으면 뜨지 않는다(알릴 수 없는 알림 서버는 의미가 없다). */
    @Bean
    fun notificationChannels(
        objectMapper: ObjectMapper,
        mailSender: ObjectProvider<JavaMailSender>,
        @Value("\${alert.discord-webhook-url:}") discordWebhookUrl: String,
        @Value("\${alert.slack-webhook-url:}") slackWebhookUrl: String,
        @Value("\${alert.mail-from:}") mailFrom: String,
        @Value("\${alert.mail-to:}") mailTo: String,
    ): List<NotificationChannel> {
        val channels = mutableListOf<NotificationChannel>()
        if (discordWebhookUrl.isNotBlank()) channels += DiscordChannel(discordWebhookUrl, objectMapper)
        if (slackWebhookUrl.isNotBlank()) channels += SlackChannel(slackWebhookUrl, objectMapper)
        val sender = mailSender.ifAvailable
        if (mailFrom.isNotBlank() && mailTo.isNotBlank() && sender != null) channels += MailChannel(sender, mailFrom, mailTo)
        check(channels.isNotEmpty()) { "발송 채널이 하나도 설정되지 않았다(DISCORD_WEBHOOK_URL·SLACK_WEBHOOK_URL·MAIL_*)" }
        log.info("alert:alert:channels in order: {}", channels.joinToString(" → ") { it.name })
        return channels
    }

    @Bean
    fun fallbackNotifier(channels: List<NotificationChannel>) = FallbackNotifier(channels)

    /** 기록용 파일 DB. 열지 못하면 기록 없이 뜬다 — 감시와 발송이 먼저다. */
    @Bean
    fun incidentStore(@Value("\${alert.data-path:data/alert.db}") dataPath: String): StoreHolder = try {
        File(dataPath).absoluteFile.parentFile?.mkdirs()
        val dataSource = SingleConnectionDataSource("jdbc:sqlite:$dataPath", true)
        StoreHolder(IncidentStore(JdbcTemplate(dataSource)))
    } catch (error: Exception) {
        log.error("alert:alert:incident store unavailable — running without history: {}", error.message)
        StoreHolder(null)
    }

    @Bean
    fun monitorRound(
        objectMapper: ObjectMapper,
        notifier: FallbackNotifier,
        storeHolder: StoreHolder,
        @Value("\${alert.monitors-file:classpath:monitors.yml}") monitorsFile: Resource,
    ): MonitorRound {
        // 형식이 틀리거나 가리킨 환경변수가 없으면 여기서 예외 → 기동 실패(조용히 대상을 빼지 않는다)
        val definitions = MonitorsFile.parse(monitorsFile.getContentAsString(StandardCharsets.UTF_8), System::getenv)
        log.info("alert:alert:watching {} monitors: {}", definitions.size, definitions.joinToString(", ") { it.name })
        return MonitorRound(definitions, HttpMonitorProbe(objectMapper), MonitorTracker(), notifier, storeHolder.store)
    }

    @Bean
    fun eventIntake(notifier: FallbackNotifier, storeHolder: StoreHolder) = EventIntake(notifier, storeHolder.store)
}

/** 기록 저장소가 없을 수도 있다는 것을 타입으로 드러낸다 */
class StoreHolder(val store: IncidentStore?)
