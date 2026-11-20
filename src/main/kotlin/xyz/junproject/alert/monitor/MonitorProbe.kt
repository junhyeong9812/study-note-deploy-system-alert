package xyz.junproject.alert.monitor

import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** 대상 하나를 한 번 확인한다 */
fun interface MonitorProbe {
    fun check(definition: MonitorDefinition): ProbeResult
}

/** HTTP 로 확인한다 — 시간 제한을 넘기면 실패. 어떤 예외도 밖으로 내보내지 않고 실패 결과로 돌려준다. */
class HttpMonitorProbe(
    private val objectMapper: ObjectMapper,
    private val timeout: Duration = Duration.ofSeconds(5),
) : MonitorProbe {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    override fun check(definition: MonitorDefinition): ProbeResult = try {
        val request = HttpRequest.newBuilder(URI.create(definition.url)).timeout(timeout).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        when {
            response.statusCode() != 200 -> ProbeResult.failed("status ${response.statusCode()}${describe(response.body())}")
            definition.expect == MonitorDefinition.Expectation.COMPONENTS -> componentsResult(response.body())
            else -> ProbeResult.HEALTHY
        }
    } catch (error: Exception) {
        ProbeResult.failed(error.message?.take(120) ?: error.javaClass.simpleName)
    }

    /** 실패 응답 본문에 status 문자열이 있으면 이유로 덧붙인다(예: llm 의 model_missing·ollama_unreachable) */
    private fun describe(body: String): String = try {
        objectMapper.readTree(body).path("status").asString("").takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
    } catch (_: Exception) { "" }

    /** `{"components": {"elasticsearch": {"status": "UP"}, ...}}` — UP 이 아닌 것을 모두 이유로 든다 */
    private fun componentsResult(body: String): ProbeResult = try {
        val components = objectMapper.readTree(body).path("components")
        val unhealthy = components.properties().filter { it.value.path("status").asString("") != "UP" }.map { it.key }
        when {
            components.isEmpty -> ProbeResult.failed("components 가 비어 있다")
            unhealthy.isEmpty() -> ProbeResult.HEALTHY
            else -> ProbeResult.failed("정상이 아닌 구성 요소: ${unhealthy.joinToString(", ")}")
        }
    } catch (_: Exception) { ProbeResult.failed("components 본문을 읽을 수 없다") }
}
