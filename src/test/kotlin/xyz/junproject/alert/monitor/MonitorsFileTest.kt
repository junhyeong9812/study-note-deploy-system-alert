package xyz.junproject.alert.monitor

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MonitorsFileTest {
    private val environment = mapOf("MONITOR_LLM_URL" to "http://llm.internal:8000", "EMPTY" to "")
    private fun parse(yaml: String) = MonitorsFile.parse(yaml, environment::get)
    private fun failure(yaml: String) = assertThrows(IllegalArgumentException::class.java) { parse(yaml) }.message!!

    @Test
    fun `환경변수로 주소를 채우고 기대값을 읽는다`() {
        val definitions = parse("""
            monitors:
              - { name: llm, url: "${'$'}{MONITOR_LLM_URL}/health" }
              - { name: backend-deps, url: "http://b/api/health/dependencies", expect: components }
        """.trimIndent())
        assertEquals(MonitorDefinition("llm", "http://llm.internal:8000/health", MonitorDefinition.Expectation.STATUS), definitions[0])
        assertEquals(MonitorDefinition.Expectation.COMPONENTS, definitions[1].expect)
    }

    @Test
    fun `가리킨 환경변수가 없거나 비어 있으면 조용히 빼지 않고 실패한다`() {
        assertTrue(failure("""monitors: [ { name: front, url: "${'$'}{MONITOR_FRONT_URL}/" } ]""").contains("MONITOR_FRONT_URL"))
        assertTrue(failure("""monitors: [ { name: front, url: "${'$'}{EMPTY}/" } ]""").contains("EMPTY"))
    }

    @Test
    fun `형식이 틀리면 실패한다`() {
        assertTrue(failure("monitors: []").contains("하나도 없다"))
        assertTrue(failure("other: 1").contains("monitors 목록"))
        assertTrue(failure("""monitors: [ { name: "Front Page", url: "http://f/" } ]""").contains("name"))
        assertTrue(failure("""monitors: [ { name: front } ]""").contains("url"))
        assertTrue(failure("""monitors: [ { name: front, url: "ftp://f/" } ]""").contains("http"))
        assertTrue(failure("""monitors: [ { name: front, url: "http://f/", expect: fast } ]""").contains("expect"))
        assertTrue(failure("""monitors: [ { name: front, url: "http://f/" }, { name: front, url: "http://g/" } ]""").contains("겹친다"))
    }

    @Test
    fun `저장소의 monitors yml 은 주소를 직접 적지 않는다`() {
        val text = javaClass.getResource("/monitors.yml")!!.readText()
        assertFalse(Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}""").containsMatchIn(text), "monitors.yml 에 IP 가 있다")
        val names = Regex("""\$\{([A-Z0-9_]+)}""").findAll(text).map { it.groupValues[1] }.toSet()
        val definitions = MonitorsFile.parse(text) { name -> if (name in names) "http://example.invalid" else null }
        assertEquals(9, definitions.size)
        assertEquals(MonitorDefinition.Expectation.COMPONENTS, definitions.single { it.name == "backend-deps" }.expect)
        assertEquals(MonitorDefinition("observe", "http://example.invalid/health", MonitorDefinition.Expectation.STATUS), definitions.single { it.name == "observe" })
    }
}
