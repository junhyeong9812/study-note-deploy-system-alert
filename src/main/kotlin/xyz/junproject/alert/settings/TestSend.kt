package xyz.junproject.alert.settings

import org.slf4j.LoggerFactory
import org.springframework.mail.MailAuthenticationException
import org.springframework.mail.MailException
import xyz.junproject.alert.incident.IncidentStore
import xyz.junproject.alert.notify.Notification
import xyz.junproject.alert.notify.NotificationChannel
import xyz.junproject.alert.notify.WebhookFailure
import java.text.Normalizer
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
    private var lastSentHere: Instant? = null   // 이 자리가 마지막으로 차지한 시각([claim] 안에서만 읽고 쓴다)
    private var lastScopeHere = RateLimitScope.THIS_SLOT   // 그때 간격을 어디까지 걸었나 — 이 자리의 기억으로 거절할 때 그대로 밝힌다

    fun send(requestedBy: String): TestSendResult {
        val (now, waitFor, scope) = claim()
        if (waitFor != null) return TestSendResult.TooSoon(waitFor, scope)
        val notification = notificationFor(requestedBy, now)
        val outcomes = channels.map { channel -> attempt(channel, notification) }
        log.info("alert:alert:test send by '{}': {}", neutralized(requestedBy),
            outcomes.joinToString(", ") { "${it.channel}=${it.reason ?: "delivered"}" })
        return TestSendResult.Sent(outcomes, scope)
    }

    /**
     * 발송을 시작하기 전에 간격 자리를 차지한다. 차지했으면 기다릴 시간 null, 아니면 남은 시간.
     * 이 자리의 기억을 먼저 본다(DB 장애 중에 보낸 것은 DB 에 없다 — 그리고 DB 를 먼저 차지한 뒤 기억으로 거절하면
     * 거절된 요청의 시각이 DB 에 남아 창이 늘어난다). 그다음 두 자리가 함께 보는 DB 로 세고, DB 를 쓸 수 없으면 기억으로만 센다.
     * 지금 시각은 이 잠금 안에서 읽는다 — 먼저 읽고 늦게 들어온 요청에게 앞선 발송이 미래로 보이지 않게.
     * 마지막 시각이 지금 + 간격보다 더 미래일 때만(시계가 크게 뒤로 감) 낡은 값으로 보고 허용한다. 조금 미래인 것(몇 초 어긋난 두 자리의 시계)은 간격 안이다.
     * 단순화: DB 를 쓸 수 없는 동안은 두 자리가 각자 센다 — 배포로 두 자리가 함께 떠 있으면 간격 하나에 최대 두 번 나간다. DB 가 돌아오면 다시 함께 센다.
     */
    @Synchronized
    private fun claim(): Triple<Instant, Duration?, RateLimitScope> {
        val now = clock()
        val lastSent = lastSentHere
        if (lastSent != null && withinInterval(lastSent, now)) return Triple(now, remaining(lastSent, now), lastScopeHere)
        val history = store
        if (history != null) {
            val shared = runCatching { history.claimTestSend(now, interval) }
                .onFailure { log.warn("alert:alert:test send limit not shared — database unavailable ({})", it.javaClass.simpleName) }
            if (shared.isSuccess) {
                val sharedLastSent = shared.getOrNull() ?: return remember(now, RateLimitScope.ALL_SLOTS)
                return Triple(now, remaining(sharedLastSent, now), RateLimitScope.ALL_SLOTS)
            }
        }
        return remember(now, RateLimitScope.THIS_SLOT)
    }

    private fun remember(now: Instant, scope: RateLimitScope): Triple<Instant, Duration?, RateLimitScope> {
        lastSentHere = now
        lastScopeHere = scope
        return Triple(now, null, scope)
    }

    /**
     * 간격 안인가 — 지금 + 간격보다 더 미래인 시각만 낡은 값으로 친다(같으면 아직 간격 안).
     * [IncidentStore.claimTestSend] 의 조건과 같은 판정이다 — 한쪽을 바꾸면 다른 쪽도 바꾼다.
     */
    private fun withinInterval(lastSent: Instant, now: Instant) =
        !lastSent.isAfter(now.plus(interval)) && now.isBefore(lastSent.plus(interval))

    // 마지막 시각이 조금 미래면 실제로 남은 시간은 간격보다 길 수 있다 — 답에는 간격까지만 싣는다(그때 다시 물으면 남은 만큼 또 답한다)
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
         * 요청에서 온 이름에서 허용할 글자만 남긴다. 먼저 조합형(NFC)으로 맞추고(분해형 `e`+U+0301 · 첫가끝 한글이 한 글자가 되게),
         * 글자 · 숫자 · 빈칸 · `.` · `_` · `-` 는 그대로, 나머지는 한 글자(코드 포인트)씩 `·` 로 바꾼다 —
         * 맨 URL · 꾸밈 문법(`*` · 백틱 · 괄호) · 멘션(`@` · `<…>`) · 줄바꿈 · 방향 제어 · 0폭 문자는 모두 `·` 가 된다.
         * 보이지 않는 한글 채움 문자는 `isLetter` 가 글자로 보지만 글자로 치지 않는다. 막을 글자를 늘어놓지 않는다 — 늘어놓으면 빠지는 것이 생긴다.
         */
        fun neutralized(name: String): String {
            val composed = Normalizer.normalize(name, Normalizer.Form.NFC)
            return buildString(composed.length) {
                composed.codePoints().forEach { codePoint ->
                    val kept = (Character.isLetter(codePoint) && codePoint !in HANGUL_FILLERS) || Character.isDigit(codePoint) || codePoint in ALLOWED_MARKS
                    if (kept) appendCodePoint(codePoint) else append('·')
                }
            }
        }

        /** 한글 채움 문자(초성 · 중성 · 호환 · 반각) — 글자 칸에 들지만 보이지 않는다 */
        private val HANGUL_FILLERS = setOf(0x115F, 0x1160, 0x3164, 0xFFA0)

        /** 글자 · 숫자 밖에서 그대로 두는 것: 빈칸 · 점 · 밑줄 · 하이픈 */
        private val ALLOWED_MARKS = setOf(' '.code, '.'.code, '_'.code, '-'.code)
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
     * 갈라 낸 경로에 `//` 나 `@` 가 남는 이상한 꼴이면 환경 변수 이름만(없으면 빈 값) 낸다.
     */
    fun mask(urlTemplate: String): String {
        val withoutQuery = urlTemplate.substringBefore('?').substringBefore('#')
        val schemeEnd = withoutQuery.indexOf("://")
        val authorityStart = if (schemeEnd >= 0) schemeEnd + 3 else 0
        val pathStart = withoutQuery.indexOf('/', authorityStart).takeIf { it >= 0 } ?: withoutQuery.length
        val names = placeholder.findAll(withoutQuery.substring(0, pathStart)).joinToString("") { it.value }
        val path = withoutQuery.substring(pathStart)
        // 경로에 `//` 나 `@` 가 남으면 주소 자리를 잘못 갈랐다(스킴을 환경 변수로 · 비밀번호에 `/`) — 경로를 내지 않는다
        if (path.contains("//") || path.contains('@')) return names
        return names + path
    }
}
