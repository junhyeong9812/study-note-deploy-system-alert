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
import xyz.junproject.alert.monitor.ActiveSlot
import xyz.junproject.alert.monitor.HttpMonitorProbe
import xyz.junproject.alert.monitor.HttpServingSlotCheck
import xyz.junproject.alert.monitor.MonitorRound
import xyz.junproject.alert.monitor.MonitorTracker
import xyz.junproject.alert.monitor.MonitorsFile
import xyz.junproject.alert.notify.DiscordChannel
import xyz.junproject.alert.notify.FallbackNotifier
import xyz.junproject.alert.notify.MailChannel
import xyz.junproject.alert.notify.NotificationChannel
import xyz.junproject.alert.notify.SlackChannel
import xyz.junproject.alert.settings.TestSend
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
        val dataSource = SingleConnectionDataSource("jdbc:sqlite:$dataPath", true)   // 연결 하나 — 접근은 IncidentStore 가 한 번에 하나씩으로 묶는다
        StoreHolder(IncidentStore(JdbcTemplate(dataSource)))
    } catch (error: Exception) {
        log.error("alert:alert:incident store unavailable — running without history: {}", error.message)
        StoreHolder(null)
    }

    /**
     * 무중단 배포의 자리 이름(compose 가 준다)과, 지금 어느 자리가 요청을 받는지 물어볼 프록시 주소.
     * 자리 이름이 없으면 단독 실행으로 보고 항상 활성. 이름은 있는데 물어볼 주소가 없으면 뜨지 않는다.
     */
    @Bean
    fun activeSlot(
        @Value("\${alert.slot:}") slot: String,
        @Value("\${alert.slot-probe-url:}") slotProbeUrl: String,
    ): ActiveSlot {
        if (slot.isBlank()) return ActiveSlot.always()
        require(slotProbeUrl.startsWith("http://") || slotProbeUrl.startsWith("https://")) {
            "ALERT_SLOT 이 설정됐는데 ALERT_SLOT_PROBE_URL 이 http(s) 주소가 아니다"
        }
        log.info("alert:alert:slot {} — active only while the proxy serves this slot", slot)
        return ActiveSlot(slot, HttpServingSlotCheck(slotProbeUrl))
    }

    @Bean
    fun monitorRound(
        objectMapper: ObjectMapper,
        notifier: FallbackNotifier,
        storeHolder: StoreHolder,
        activeSlot: ActiveSlot,
        @Value("\${alert.monitors-file:classpath:monitors.yml}") monitorsFile: Resource,
    ): MonitorRound {
        // 형식이 틀리거나 가리킨 환경변수가 없으면 여기서 예외 → 기동 실패(조용히 대상을 빼지 않는다)
        val definitions = MonitorsFile.parse(monitorsFile.getContentAsString(StandardCharsets.UTF_8), System::getenv)
        log.info("alert:alert:watching {} monitors: {}", definitions.size, definitions.joinToString(", ") { it.name })
        return MonitorRound(definitions, HttpMonitorProbe(objectMapper), MonitorTracker(), notifier, storeHolder.store,
            activeSlot = activeSlot)
    }

    @Bean
    fun eventIntake(notifier: FallbackNotifier, storeHolder: StoreHolder) = EventIntake(notifier, storeHolder.store)

    /** 시험 발송 — 같은 채널 부품을 쓰되 발송기(FallbackNotifier)를 거치지 않는다(실패한 시험 알림이 미발송 보관함에 들어가지 않게). */
    @Bean
    fun testSend(channels: List<NotificationChannel>, storeHolder: StoreHolder) = TestSend(channels, storeHolder.store)
}

/** 기록 저장소가 없을 수도 있다는 것을 타입으로 드러낸다 */
class StoreHolder(val store: IncidentStore?)
