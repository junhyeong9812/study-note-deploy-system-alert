package xyz.junproject.alert.settings

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

/**
 * 새 비밀값을 여럿이 쓰는 ALERT_SECRET 과 같은 값으로 둔 서버 — 두 경로는 늘 닫히고(다른 거절과 같은 응답),
 * 뜰 때 error 로그를 한 줄 남기되 값은 싣지 않는다. 앱과 기존 경로는 그 값으로 그대로 돈다. 이 테스트는 보내지 않는다.
 */
@SpringBootTest(properties = [
    "alert.secret=" + SettingsSecretSameAsAlertSecretTest.SHARED_VALUE,
    "alert.settings-secret=" + SettingsSecretSameAsAlertSecretTest.SHARED_VALUE,
    "alert.discord-webhook-url=http://127.0.0.1:1/unused",
    "alert.monitors-file=classpath:monitors-test.yml",
    "alert.data-path=build/tmp/alert-settings-same-secret-test.db",
    "alert.round-initial-delay-ms=3600000",
])
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ExtendWith(OutputCaptureExtension::class)
class SettingsSecretSameAsAlertSecretTest {
    companion object {
        const val SHARED_VALUE = "MARKER-shared-secret-Yh5q"
    }

    @Autowired lateinit var mockMvc: MockMvc

    private fun call(request: MockHttpServletRequestBuilder) =
        mockMvc.perform(request).andReturn().response.let { it.status to it.getContentAsString(Charsets.UTF_8) }

    private fun testSendWith(secret: String) = post("/settings/test-send").contentType(MediaType.APPLICATION_JSON)
        .content("""{"requested_by":"jun"}""").header(SettingsControllerTest.SETTINGS_HEADER, secret)

    @Test
    fun `두 비밀값이 같으면 그 값으로도 두 경로가 열리지 않고 다른 거절과 같은 응답이다`() {
        val refusedWithShared = listOf(
            call(get("/settings").header(SettingsControllerTest.SETTINGS_HEADER, SHARED_VALUE)),
            call(testSendWith(SHARED_VALUE)),
        )
        val refusedWithWrong = call(get("/settings").header(SettingsControllerTest.SETTINGS_HEADER, "wrong"))
        assertEquals(401, refusedWithWrong.first)
        for (reply in refusedWithShared) assertEquals(refusedWithWrong, reply, "같은 값이 두 경로를 열었거나 거절 응답이 다르다")
    }

    @Test
    fun `두 비밀값이 같아도 앱이 뜨고 기존 경로는 그 값으로 그대로 돈다`() {
        assertEquals(200, call(get("/health")).first)
        assertEquals(200, call(get("/monitors").header("X-Alert-Secret", SHARED_VALUE)).first)
        val event = post("/events").contentType(MediaType.APPLICATION_JSON).header("X-Alert-Secret", SHARED_VALUE)
            .content("""{"source":"ci-cd","kind":"same_secret_test","key":"same-secret:${System.nanoTime()}","title":"x"}""")
        assertEquals(202, call(event).first)
    }

    @Test
    fun `두 비밀값이 같으면 뜰 때 error 로그를 한 줄 남기고 거기 값은 없다`(output: CapturedOutput) {
        val errorLines = output.toString().lines().filter { it.contains("ERROR") && it.contains("ALERT_SETTINGS_SECRET") }
        assertEquals(1, errorLines.size, "ALERT_SETTINGS_SECRET 을 말하는 error 로그가 한 줄이 아니다: $errorLines")
        assertEquals(emptyList<String>(), SettingsMarkers.leaks(output.toString(), listOf(SHARED_VALUE)), "로그에 비밀값이 나왔다")
    }
}
