package xyz.junproject.alert.monitor

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/** 지금 프록시(nginx)가 요청을 보내는 자리의 이름. 알 수 없으면 null. */
fun interface ServingSlotCheck {
    fun servingSlot(): String?
}

/** 프록시의 응답 헤더 `X-Deploy-Slot` 으로 확인한다(프록시는 뒤의 앱이 죽어 있어도 이 헤더를 붙인다). */
class HttpServingSlotCheck(probeUrl: String, private val timeout: Duration = Duration.ofSeconds(3)) : ServingSlotCheck {
    private val uri = URI.create(probeUrl)
    private val client = HttpClient.newBuilder().connectTimeout(timeout).build()

    override fun servingSlot(): String? = try {
        val request = HttpRequest.newBuilder(uri).timeout(timeout).GET().build()
        client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
            .headers().firstValue("X-Deploy-Slot").orElse(null)?.takeIf { it.isNotBlank() }
    } catch (error: InterruptedException) {
        throw error
    } catch (_: Exception) { null }
}

/**
 * 무중단 배포에서는 두 자리(blue·green)가 잠시 함께 떠 있다. 감시·발송·요약은 **활성 자리 하나만** 한다 —
 * 같은 알림이 두 번 가지 않게, 그리고 어느 자리도 맡지 않는 공백이 생기지 않게.
 *
 * 누가 활성인지는 프록시가 실제로 요청을 보내는 자리로 정한다(매 바퀴 확인).
 * - 내 자리면 활성, 다른 자리면 대기.
 * - 프록시에 닿지 못하면 직전 판단을 유지한다(전환은 프록시를 거쳐야만 일어난다).
 * - 뜬 뒤 [unknownGrace] 동안 한 번도 판정하지 못하면 활성으로 본다 — 알리지 않는 쪽보다 알리는 쪽으로 실패한다.
 * 자리 이름이 없으면(단독 실행) 항상 활성이다.
 */
class ActiveSlot(
    private val ownSlot: String,
    private val check: ServingSlotCheck,
    private val unknownGrace: Duration = Duration.ofMinutes(2),
    private val clock: () -> Instant = Instant::now,
) {
    private val startedAt = clock()
    private var determined = false
    @Volatile var active: Boolean = ownSlot.isBlank()
        private set

    fun refresh(): Boolean {
        if (ownSlot.isBlank()) return true
        val serving = check.servingSlot()
        if (serving != null) {
            determined = true
            active = serving == ownSlot
        } else if (!determined && Duration.between(startedAt, clock()) >= unknownGrace) {
            active = true
        }
        return active
    }

    /** 배포의 정리 알림 — 곧바로 대기로 내려간다. 프록시가 여전히 이 자리로 보내고 있으면 다음 확인에서 다시 활성이 된다. */
    fun standDown() {
        if (ownSlot.isNotBlank()) active = false
    }

    companion object {
        fun always() = ActiveSlot("", { null })
    }
}
