package xyz.junproject.alert.monitor

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class ActiveSlotTest {
    private var now: Instant = Instant.parse("2026-11-20T00:00:00Z")
    private var serving: String? = null
    private fun slot(name: String) = ActiveSlot(name, { serving }, clock = { now })

    @Test
    fun `자리 이름이 없으면 항상 활성이고 정리 알림도 내려놓지 않는다`() {
        val single = ActiveSlot.always()
        assertTrue(single.active)
        single.standDown()
        assertTrue(single.active && single.refresh())
    }

    @Test
    fun `프록시가 보내는 자리와 같을 때만 활성`() {
        val blue = slot("blue")
        assertFalse(blue.active, "확인 전에는 대기")
        serving = "green"; assertFalse(blue.refresh())
        serving = "blue"; assertTrue(blue.refresh())
        serving = "green"; assertFalse(blue.refresh())
    }

    @Test
    fun `프록시에 닿지 못하면 직전 판단을 유지한다`() {
        val blue = slot("blue")
        serving = "blue"; assertTrue(blue.refresh())
        serving = null
        now = now.plusSeconds(3600)
        assertTrue(blue.refresh())
        serving = "green"; blue.refresh(); serving = null
        now = now.plusSeconds(3600)
        assertFalse(blue.refresh(), "한 번이라도 판정했으면 시간이 지나도 스스로 맡지 않는다")
    }

    @Test
    fun `뜬 뒤 한 번도 판정하지 못하면 2분 뒤에 맡는다`() {
        val blue = slot("blue")
        now = now.plusSeconds(119); assertFalse(blue.refresh())
        now = now.plusSeconds(1); assertTrue(blue.refresh())
    }
}
