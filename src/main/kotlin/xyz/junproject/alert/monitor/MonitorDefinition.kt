package xyz.junproject.alert.monitor

import org.yaml.snakeyaml.Yaml

/** 감시 대상 하나 — 어디를 부르고 무엇을 정상으로 볼지 */
data class MonitorDefinition(
    val name: String,
    val url: String,
    /** status: 200이면 정상 / components: 200이고 본문의 components 가 전부 UP 이어야 정상 */
    val expect: Expectation = Expectation.STATUS,
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
            MonitorDefinition(name, url, expect)
        }
        require(definitions.isNotEmpty()) { "monitors.yml: 감시 대상이 하나도 없다" }
        val duplicated = definitions.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicated.isEmpty()) { "monitors.yml: 이름이 겹친다 $duplicated" }
        return definitions
    }

    /** 이름 → 주소 틀(환경 변수를 채우기 전의 글). 설정 보기가 가린 주소를 만들 때 쓴다 — 형식은 [parse] 가 기동 때 이미 검사했다. */
    fun addressTemplates(yamlText: String): Map<String, String> =
        ((Yaml().load<Any?>(yamlText) as? Map<*, *>)?.get("monitors") as? List<*>).orEmpty()
            .mapNotNull { entry ->
                val fields = entry as? Map<*, *> ?: return@mapNotNull null
                val name = fields["name"] as? String ?: return@mapNotNull null
                val template = fields["url"] as? String ?: return@mapNotNull null
                name to template
            }.toMap()
}
