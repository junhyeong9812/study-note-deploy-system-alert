package xyz.junproject.alert.settings

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** 감시 대상 주소를 가린 꼴 — 환경 변수의 이름과 경로만, 호스트 · IP · 포트 · 계정은 없다 */
class MonitorAddressMaskTest {
    @Test
    fun `환경 변수로 가리킨 주소는 그 이름과 경로만 남긴다`() {
        assertEquals("\${MONITOR_LLM_URL}/health", MonitorAddressMask.mask("\${MONITOR_LLM_URL}/health"))
        assertEquals("\${MONITOR_FRONT_URL}/", MonitorAddressMask.mask("\${MONITOR_FRONT_URL}/"))
        assertEquals("\${MONITOR_BACKEND_URL}/api/health/dependencies",
            MonitorAddressMask.mask("\${MONITOR_BACKEND_URL}/api/health/dependencies"))
    }

    @Test
    fun `직접 적은 주소는 경로만 남기고 호스트 · IP · 포트를 뺀다`() {
        assertEquals("/api/health/dependencies", MonitorAddressMask.mask("http://203.0.113.77:47614/api/health/dependencies"))
        assertEquals("/", MonitorAddressMask.mask("https://marker-host.invalid:8443/"))
        assertEquals("/health", MonitorAddressMask.mask("http://localhost:47615/health"))
    }

    @Test
    fun `호스트나 포트 자리에 쓴 환경 변수도 이름만 남고 직접 적은 IP · 포트 · 계정은 없다`() {
        val masked = MonitorAddressMask.mask("http://\${MONITOR_HOST}:47616/health")
        assertTrue(masked.contains("MONITOR_HOST") && masked.endsWith("/health"), masked)
        assertFalse(masked.contains("47616") || masked.contains("http"), masked)
        val withAccount = MonitorAddressMask.mask("http://marker-user:marker-pass@198.51.100.9:9000/health")
        assertEquals("/health", withAccount)
        val pathPlaceholder = MonitorAddressMask.mask("http://localhost:47615\${HOME}/health")
        assertEquals("\${HOME}/health", pathPlaceholder)
    }

    @Test
    fun `질의 문자열과 조각은 비밀값이 실릴 수 있어 뺀다`() {
        assertEquals("/health", MonitorAddressMask.mask("http://203.0.113.77:1/health?token=MARKER-query-token-Vb6n"))
        assertEquals("\${MONITOR_X_URL}/health", MonitorAddressMask.mask("\${MONITOR_X_URL}/health?key=MARKER-query-key-Jc4m"))
        assertEquals("/health", MonitorAddressMask.mask("http://localhost:47615/health#MARKER-fragment-Rr8t"))
        assertEquals("\${MONITOR_X_URL}/", MonitorAddressMask.mask("\${MONITOR_X_URL}/?a=1&token=MARKER-query-Gg2w#MARKER-frag"))
    }
}
