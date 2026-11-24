package xyz.junproject.alert.settings

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.nio.charset.StandardCharsets

/**
 * 새 비밀값(ALERT_SETTINGS_SECRET)이 서버에 없을 때 — 두 경로는 늘 닫히고(빈 머리글과 빈 설정이 맞지 않는다),
 * 앱은 뜨고 기존 경로는 그대로 돈다. alert.settings-secret 을 일부러 주지 않는다.
 */
@SpringBootTest(properties = [
    "alert.secret=test-secret",
    "alert.discord-webhook-url=http://127.0.0.1:1/unused",   // 채널이 하나도 없으면 뜨지 않는다 — 이 테스트는 보내지 않는다
    "alert.monitors-file=classpath:monitors-test.yml",
    "alert.data-path=build/tmp/alert-settings-secret-missing-test.db",
    "alert.round-initial-delay-ms=3600000",
])
@AutoConfigureMockMvc
class SettingsSecretMissingTest {
    @Autowired lateinit var mockMvc: MockMvc

    private fun call(request: MockHttpServletRequestBuilder) =
        mockMvc.perform(request).andReturn().response.let { it.status to it.getContentAsString(Charsets.UTF_8) }

    private fun withHeader(request: MockHttpServletRequestBuilder, value: String?) =
        request.apply { value?.let { header(SettingsControllerTest.SETTINGS_HEADER, it) } }

    @Test
    fun `새 비밀값이 없으면 두 경로는 어떤 머리글로도 열리지 않는다 - 빈 머리글 포함`() {
        val replies = listOf(null, "", "   ", "anything", "test-secret").flatMap { value ->
            listOf(
                call(withHeader(get("/settings"), value)),
                call(withHeader(post("/settings/test-send").contentType(MediaType.APPLICATION_JSON).content("""{"requested_by":"jun"}"""), value)),
            )
        } + call(get("/settings").header("X-Alert-Secret", "test-secret"))
        assertTrue(replies.all { it.first == 401 }, "$replies")
        assertEquals(1, replies.map { it.second }.toSet().size, "거절 응답이 서로 다르다: ${replies.toSet()}")
    }

    @Test
    fun `새 비밀값이 없어도 앱이 뜨고 기존 경로는 그대로 돈다`() {
        assertEquals(200, call(get("/health")).first)
        assertEquals(200, call(get("/monitors").header("X-Alert-Secret", "test-secret")).first)
        assertEquals(200, call(get("/incidents").header("X-Alert-Secret", "test-secret")).first)
        assertEquals(401, call(get("/monitors")).first)
    }

    @Test
    fun `새 비밀값은 환경 변수 ALERT_SETTINGS_SECRET 에서 오고 없으면 비어 있다`() {
        val applicationYaml = ClassPathResource("application.yml").getContentAsString(StandardCharsets.UTF_8)
        assertTrue(Regex("""settings-secret:\s*\$\{ALERT_SETTINGS_SECRET:}""").containsMatchIn(applicationYaml),
            "application.yml 의 alert.settings-secret 이 \${ALERT_SETTINGS_SECRET:} 를 가리키지 않는다")
    }
}
