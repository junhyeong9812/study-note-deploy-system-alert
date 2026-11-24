package xyz.junproject.alert.settings

import org.slf4j.LoggerFactory
import org.springframework.mail.MailAuthenticationException
import org.springframework.mail.MailException
import xyz.junproject.alert.incident.IncidentStore
import xyz.junproject.alert.notify.Notification
import xyz.junproject.alert.notify.NotificationChannel
import xyz.junproject.alert.notify.WebhookFailure
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 시험 발송 — 설정된 채널마다 따로 한 번씩 시험 알림을 보내고 채널별 결과를 돌려준다.
 * 사건 기록 · 감시 상태 · 미발송 보관함에 섞이지 않는다. [interval] 안에 한 번만(두 자리가 [store] 로 함께 센다).
 *
 * 채널 부품을 직접 부른다 — 발송기(FallbackNotifier)는 앞 채널이 되면 멈추고, 다시 시도하고, 실패를 보관했다가
 * 나중에 진짜 알림처럼 다시 보낸다. 시험은 채널마다 한 번만 시도하고(응답 없는 채널 하나가 요청을 오래 붙잡지 않게) 아무것도 남기지 않는다.
 */
class TestSend(
    private val channels: List<NotificationChannel>,
    private val store: IncidentStore?,
    val interval: Duration = Duration.ofSeconds(30),
    private val clock: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger("alert")
    private var lastSentHere: Instant? = null   // 이 자리가 마지막으로 차지한 시각 — DB 를 쓸 수 없을 때의 제한([claim] 안에서만 읽고 쓴다)

    fun send(requestedBy: String): TestSendResult {
        val now = clock()
        val (waitFor, scope) = claim(now)
        if (waitFor != null) return TestSendResult.TooSoon(waitFor, scope)
        val notification = notificationFor(requestedBy, now)
        val outcomes = channels.map { channel -> attempt(channel, notification) }
        log.info("alert:alert:test send by '{}': {}", neutralized(requestedBy),
            outcomes.joinToString(", ") { "${it.channel}=${it.reason ?: "delivered"}" })
        return TestSendResult.Sent(outcomes, scope)
    }

    /**
     * 발송을 시작하기 전에 간격 자리를 차지한다. 차지했으면 기다릴 시간 null, 아니면 남은 시간.
     * 두 자리가 함께 보는 DB 로 세고, DB 를 쓸 수 없으면 이 자리의 기억으로만 센다.
     * 단순화: DB 를 쓸 수 없는 동안은 두 자리가 각자 센다 — 배포로 두 자리가 함께 떠 있으면 간격 하나에 최대 두 번 나간다. DB 가 돌아오면 다시 함께 센다.
     */
    @Synchronized
    private fun claim(now: Instant): Pair<Duration?, RateLimitScope> {
        val history = store
        if (history != null) {
            val shared = runCatching { history.claimTestSend(now, interval) }
                .onFailure { log.warn("alert:alert:test send limit not shared — database unavailable ({})", it.javaClass.simpleName) }
            if (shared.isSuccess) {
                val lastSent = shared.getOrNull() ?: run { lastSentHere = now; return null to RateLimitScope.ALL_SLOTS }
                return remaining(lastSent, now) to RateLimitScope.ALL_SLOTS
            }
        }
        val lastSent = lastSentHere
        if (lastSent != null && now.isBefore(lastSent.plus(interval))) return remaining(lastSent, now) to RateLimitScope.THIS_SLOT
        lastSentHere = now
        return null to RateLimitScope.THIS_SLOT
    }

    private fun remaining(lastSent: Instant, now: Instant): Duration =
        Duration.between(now, lastSent.plus(interval)).coerceIn(Duration.ofMillis(1), interval)

    private fun attempt(channel: NotificationChannel, notification: Notification): ChannelOutcome = try {
        channel.send(notification)
        ChannelOutcome(channel.name, true, null)
    } catch (error: InterruptedException) {
        throw error
    } catch (error: Exception) {
        val reason = maskedReason(error)
        log.warn("alert:alert:test send failed on {}: {}", channel.name, reason)
        ChannelOutcome(channel.name, false, reason)
    }

    /** 밖에 내도 되는 사유만 — 예외 문구에는 메일 서버 · 계정이 들어갈 수 있어 쓰지 않는다 */
    private fun maskedReason(error: Exception): String = when (error) {
        is WebhookFailure -> error.reason
        is MailAuthenticationException -> "auth_failed"
        is MailException -> "mail_failed"
        else -> "failed"
    }

    private fun notificationFor(requestedBy: String, now: Instant) = Notification(
        "시험 알림",
        "알림 설정 화면에서 보낸 시험입니다 — 장애가 아닙니다.\n보낸 사람: ${neutralized(requestedBy)}\n시각: ${shownAt.format(now)} (KST)",
        Notification.Severity.INFO,
    )

    companion object {
        private val shownAt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Seoul"))

        /**
         * 요청에서 온 이름을 채널의 꾸밈 문법으로 읽히지 않게 한다 — 한 글자를 한 글자로 바꿔 길이는 그대로.
         * 줄바꿈 · 제어 문자 → 빈칸(가짜 줄 · 메일 제목 머리글), `<` `>` → `‹` `›`(슬랙 `<!channel>` · `<url|글>` · 디스코드 `<@id>`),
         * `@` → `＠`(디스코드 `@everyone` · `@here`), `[` `]` → `［` `］`(디스코드 `[글](주소)` 숨은 링크).
         */
        fun neutralized(name: String): String = buildString(name.length) {
            for (character in name) append(when {
                Character.isISOControl(character) || character == ' ' || character == ' ' -> ' '
                character == '<' -> '‹'
                character == '>' -> '›'
                character == '@' -> '＠'
                character == '[' -> '［'
                character == ']' -> '］'
                else -> character
            })
        }
    }
}

sealed interface TestSendResult {
    /** 보냈다 — 채널 순서대로 결과 하나씩 */
    data class Sent(val outcomes: List<ChannelOutcome>, val limitScope: RateLimitScope) : TestSendResult

    /** 간격 안의 요청 — 보내지 않았다 */
    data class TooSoon(val retryAfter: Duration, val limitScope: RateLimitScope) : TestSendResult
}

/** 채널 하나의 시험 발송 결과. 실패면 가린 사유(`status 500` 또는 소문자 갈래 이름) — 주소 · 응답 본문은 싣지 않는다. */
data class ChannelOutcome(val channel: String, val delivered: Boolean, val reason: String?)

/** 간격 제한이 어디까지 걸렸나 — 두 자리 모두(DB 로 함께 셈) / 이 자리 안에서만(DB 를 쓸 수 없었다) */
enum class RateLimitScope { ALL_SLOTS, THIS_SLOT }

/** 감시 대상 주소를 가린 꼴 — 환경 변수의 이름과 경로만 남기고 스킴 · 호스트 · IP · 포트 · 계정은 뺀다. */
object MonitorAddressMask {
    private val placeholder = Regex("""\$\{[A-Z0-9_]+}""")   // MonitorsFile 이 채우는 꼴과 같다

    /**
     * 질의 문자열과 조각을 먼저 뗀다(비밀값이 실릴 수 있다). 경로 앞(스킴 · 계정 · 호스트 · 포트 자리)에서는 환경 변수의 이름만 남기고,
     * 경로는 그대로 둔다. 스킴이 없는 틀(`${MONITOR_LLM_URL}/health`)은 첫 `/` 앞이 주소 자리다.
     */
    fun mask(urlTemplate: String): String {
        val withoutQuery = urlTemplate.substringBefore('?').substringBefore('#')
        val schemeEnd = withoutQuery.indexOf("://")
        val authorityStart = if (schemeEnd >= 0) schemeEnd + 3 else 0
        val pathStart = withoutQuery.indexOf('/', authorityStart).takeIf { it >= 0 } ?: withoutQuery.length
        val names = placeholder.findAll(withoutQuery.substring(0, pathStart)).joinToString("") { it.value }
        return names + withoutQuery.substring(pathStart)
    }
}
