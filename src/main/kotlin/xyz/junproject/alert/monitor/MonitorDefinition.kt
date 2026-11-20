package xyz.junproject.alert.monitor

import org.yaml.snakeyaml.Yaml

/** 감시 대상 하나 — 어디를 부르고 무엇을 정상으로 볼지 */
data class MonitorDefinition(
    val name: String,
    val url: String,
    /** status: 200이면 정상 / components: 200이고 본문의 components 가 전부 UP 이어야 정상 */
    val expect: Expectation = Expectation.STATUS,
    /** 같은 서버에 있는 대상끼리 묶는 이름 — 알림 문구에 쓴다 */
    val host: String = "",
) {
    enum class Expectation { STATUS, COMPONENTS }
}

/**
 * monitors.yml 을 읽는다. 주소는 repo 에 적지 않고 `${ENV_NAME}` 으로 가리킨다.
 * 형식이 틀리거나 가리킨 환경변수가 없으면 예외 — 조용히 대상을 빼고 뜨지 않는다.
 */
object MonitorsFile {
    private val placeholder = Regex("""\$\{([A-Z0-9_]+)}""")

    fun parse(yamlText: String, environment: (String) -> String?): List<MonitorDefinition> {
        val root = Yaml().load<Any?>(yamlText) as? Map<*, *>
            ?: throw IllegalArgumentException("monitors.yml: 최상위는 monitors 목록을 가진 맵이어야 한다")
        val entries = root["monitors"] as? List<*>
            ?: throw IllegalArgumentException("monitors.yml: monitors 목록이 없다")
        val definitions = entries.mapIndexed { index, entry ->
            val fields = entry as? Map<*, *> ?: throw IllegalArgumentException("monitors.yml: ${index + 1}번째 항목이 맵이 아니다")
            val name = (fields["name"] as? String)?.takeIf { it.matches(Regex("^[a-z0-9-]{1,40}$")) }
                ?: throw IllegalArgumentException("monitors.yml: ${index + 1}번째 항목의 name 이 없거나 형식이 다르다(소문자·숫자·하이픈)")
            val rawUrl = fields["url"] as? String ?: throw IllegalArgumentException("monitors.yml: $name 의 url 이 없다")
            val url = placeholder.replace(rawUrl) { match ->
                environment(match.groupValues[1])?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("monitors.yml: $name 이 가리키는 환경변수 ${match.groupValues[1]} 가 없다")
            }
            require(url.startsWith("http://") || url.startsWith("https://")) { "monitors.yml: $name 의 url 이 http(s) 주소가 아니다" }
            val expect = when (val value = fields["expect"] ?: "status") {
                "status" -> MonitorDefinition.Expectation.STATUS
                "components" -> MonitorDefinition.Expectation.COMPONENTS
                else -> throw IllegalArgumentException("monitors.yml: $name 의 expect '$value' 를 모른다(status·components)")
            }
            MonitorDefinition(name, url, expect, host = fields["host"] as? String ?: "")
        }
        require(definitions.isNotEmpty()) { "monitors.yml: 감시 대상이 하나도 없다" }
        val duplicated = definitions.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicated.isEmpty()) { "monitors.yml: 이름이 겹친다 $duplicated" }
        return definitions
    }
}
