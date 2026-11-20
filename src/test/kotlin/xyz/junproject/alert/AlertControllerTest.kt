package xyz.junproject.alert

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import xyz.junproject.alert.notify.Notification
import xyz.junproject.alert.notify.NotificationChannel
import java.util.concurrent.CopyOnWriteArrayList

@SpringBootTest(properties = [
    "alert.secret=test-secret",
    "alert.discord-webhook-url=http://127.0.0.1:1/unused",   // 채널이 하나도 없으면 뜨지 않는다 — 실제 발송은 아래 가짜로 바꾼다
    "alert.monitors-file=classpath:monitors-test.yml",
    "alert.data-path=build/tmp/alert-controller-test.db",
    "alert.round-initial-delay-ms=3600000",     // 테스트 중에는 감시 루프가 돌지 않게
])
@AutoConfigureMockMvc
class AlertControllerTest {
    @TestConfiguration
    class Channels {
        @Bean @Primary
        fun testNotifier() = xyz.junproject.alert.notify.FallbackNotifier(listOf(inbox), pause = {}, worker = { it.run() })
    }

    companion object {
        val received = CopyOnWriteArrayList<Notification>()
        val inbox = object : NotificationChannel {
            override val name = "discord"
            override fun send(notification: Notification) { received += notification }
        }
    }

    @Autowired lateinit var mockMvc: MockMvc

    private fun event(body: String, secret: String? = "test-secret") =
        post("/events").contentType(MediaType.APPLICATION_JSON).content(body).apply { secret?.let { header("X-Alert-Secret", it) } }

    @Test
    fun `비밀값이 없거나 틀리면 접수와 조회를 거절한다`() {
        mockMvc.perform(event("""{"source":"ci-cd","kind":"deploy_failed","title":"x"}""", secret = null)).andExpect(status().isUnauthorized)
        mockMvc.perform(event("""{"source":"ci-cd","kind":"deploy_failed","title":"x"}""", secret = "wrong")).andExpect(status().isUnauthorized)
        mockMvc.perform(get("/monitors")).andExpect(status().isUnauthorized)
        mockMvc.perform(get("/incidents")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `사건을 받으면 발송하고 같은 key 의 반복은 발송하지 않는다`() {
        received.clear()
        val key = "controller-test:${System.nanoTime()}"     // 기록 파일이 실행 사이에 남으므로 실행마다 다른 key
        val body = """{"source":"ci-cd","kind":"deploy_failed","severity":"critical","key":"$key","title":"배포 실패 llm","detail":"단계 ci_test"}"""
        mockMvc.perform(event(body)).andExpect(status().isAccepted).andExpect(jsonPath("$.data.sent").value(true))
        mockMvc.perform(event(body)).andExpect(status().isAccepted).andExpect(jsonPath("$.data.sent").value(false))
        assertEquals(listOf("[ci-cd] 배포 실패 llm"), received.map { it.title })
        assertEquals(Notification.Severity.CRITICAL, received[0].severity)
        mockMvc.perform(get("/incidents").header("X-Alert-Secret", "test-secret"))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.incidents[0].key").value(key))
    }

    @Test
    fun `형식이 틀린 사건은 422`() {
        mockMvc.perform(event("""{"kind":"deploy_failed","title":"x"}""")).andExpect(status().isUnprocessableEntity)
        mockMvc.perform(event("""{"source":"ci-cd","kind":"deploy_failed"}""")).andExpect(status().isUnprocessableEntity)
        mockMvc.perform(event("""{"source":"ci-cd","kind":"deploy_failed","title":"x","severity":"loud"}""")).andExpect(status().isUnprocessableEntity)
        mockMvc.perform(event("""{"source":"CI CD","kind":"deploy_failed","title":"x"}""")).andExpect(status().isUnprocessableEntity)
    }

    @Test
    fun `헬스는 비밀값 없이 열려 있고 대상 목록은 설정 파일의 것이다`() {
        mockMvc.perform(get("/health")).andExpect(status().isOk).andExpect(jsonPath("$.status").exists())
        mockMvc.perform(get("/monitors").header("X-Alert-Secret", "test-secret"))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.monitors.length()").value(2))
            .andExpect(jsonPath("$.data.monitors[0].status").value("UNKNOWN"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("example.invalid"))))
    }

    @Test
    fun `정리 알림은 단독 실행(자리 이름 없음)에서는 감시를 끄지 않는다`() {
        mockMvc.perform(post("/admin/drain")).andExpect(status().isOk)
        mockMvc.perform(get("/health")).andExpect(jsonPath("$.active").value(true))
    }
}
