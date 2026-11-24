package xyz.junproject.alert.settings

import xyz.junproject.alert.incident.IncidentStore
import xyz.junproject.alert.notify.NotificationChannel
import java.time.Duration
import java.time.Instant

// 뼈대 — 테스트가 컴파일되게 시그니처만 둔다. 구현 워커가 채운다(TODO 를 남기지 않는다).

/**
 * 시험 발송 — 설정된 채널마다 따로 한 번씩 시험 알림을 보내고 채널별 결과를 돌려준다.
 * 사건 기록 · 감시 상태 · 미발송 보관함에 섞이지 않는다. [interval] 안에 한 번만(두 자리가 [store] 로 함께 센다).
 */
class TestSend(
    private val channels: List<NotificationChannel>,
    private val store: IncidentStore?,
    private val interval: Duration = Duration.ofSeconds(30),
    private val clock: () -> Instant = Instant::now,
) {
    fun send(requestedBy: String): TestSendResult = TODO("구현 워커가 채운다")
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
    fun mask(urlTemplate: String): String = TODO("구현 워커가 채운다")
}
