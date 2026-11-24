package xyz.junproject.alert.settings

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import tools.jackson.databind.ObjectMapper

/** 설정되지 않은 채널도 종류는 보이고 '설정 안 됨' · 순서 없음으로 나온다(디스코드만 설정 — 이 테스트는 보내지 않는다) */
@SpringBootTest(properties = [
    "alert.secret=test-secret",
    "alert.settings-secret=view-channels-settings-secret",
    "alert.discord-webhook-url=http://127.0.0.1:1/unused",
    "alert.monitors-file=classpath:monitors-test.yml",
    "alert.data-path=build/tmp/alert-settings-view-channels-test.db",
    "alert.round-initial-delay-ms=3600000",
])
@AutoConfigureMockMvc
class SettingsViewChannelsTest {
    @Autowired lateinit var mockMvc: MockMvc

    @Test
    fun `설정되지 않은 채널은 configured false 이고 순서가 없다`() {
        val response = mockMvc.perform(get("/settings").header(SettingsControllerTest.SETTINGS_HEADER, "view-channels-settings-secret"))
            .andReturn().response
        assertEquals(200, response.status)
        val channels = ObjectMapper().readTree(response.getContentAsString(Charsets.UTF_8)).path("data").path("channels")
        val byKind = (0 until channels.size()).associate { channels.get(it).path("kind").asString() to channels.get(it) }
        assertEquals(listOf("discord", "slack", "mail"), byKind.keys.toList())
        assertTrue(byKind.getValue("discord").path("configured").asBoolean())
        assertEquals(1, byKind.getValue("discord").path("order").asInt())
        for (kind in listOf("slack", "mail")) {
            assertFalse(byKind.getValue(kind).path("configured").asBoolean(true), kind)
            val order = byKind.getValue(kind).path("order")
            assertTrue(order.isNull || order.isMissingNode, "$kind 는 설정되지 않았는데 순서가 있다: $order")
        }
    }
}
