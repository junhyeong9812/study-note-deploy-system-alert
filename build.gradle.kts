plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "xyz.junproject"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework:spring-jdbc")   // 자동 구성 없이 JdbcTemplate 만 — 연결은 직접 연다
	implementation("org.springframework.boot:spring-boot-starter-mail")
	implementation("org.xerial:sqlite-jdbc:3.47.1.0")   // 기록용 파일 DB — 외부 DB 서버에 기대지 않는다
	implementation("org.yaml:snakeyaml")                 // monitors.yml
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
	environment("SERVER_NAME", "front")   // 호출자 이름은 코드의 상수 — 설정에서 읽으면 테스트가 알아채게 다른 값을 둔다
}
