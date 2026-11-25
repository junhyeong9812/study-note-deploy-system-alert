package xyz.junproject.alert.settings

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import tools.jackson.databind.ObjectMapper

/**
 * 감시 바퀴 간격을 ISO 꼴(PT30S)로 둔 서버 — @Scheduled 는 이 꼴을 받는다(Spring 의 DurationFormatterUtils.detectAndParse).
 * 설정 보기가 이 값을 숫자로 읽지 못한다고 앱의 기동을 막지 않고, 그 칸만 비워 낸다.
 */
@SpringBootTest(properties = [
    "alert.secret=test-secret",
    "alert.settings-secret=rule-unreadable-settings-secret",
    "alert.discord-webhook-url=http://127.0.0.1:1/unused",
    "alert.monitors-file=classpath:monitors-test.yml",
    "alert.data-path=build/tmp/alert-settings-rule-unreadable-test.db",
    "alert.round-interval-ms=PT30S",
    "alert.round-initial-delay-ms=3600000",
])
@AutoConfigureMockMvc
class SettingsRuleUnreadableTest {
    @Autowired lateinit var mockMvc: MockMvc

    @Test
    fun `규칙 값을 숫자로 읽지 못해도 앱이 뜨고 설정 보기는 그 칸만 비워 낸다`() {
        val response = mockMvc.perform(get("/settings").header(SettingsControllerTest.SETTINGS_HEADER, "rule-unreadable-settings-secret"))
            .andReturn().response
        assertEquals(200, response.status)
        val rules = ObjectMapper().readTree(response.getContentAsString(Charsets.UTF_8)).path("data").path("rules")
        assertTrue(rules.path("round_interval_seconds").isNull, "읽지 못한 값이 null 이 아니다: $rules")
        assertEquals(3, rules.path("failure_threshold").asInt(), "다른 칸까지 비웠다: $rules")
        assertEquals("0 0 9 * * *", rules.path("summary_cron").asString())
    }
}
