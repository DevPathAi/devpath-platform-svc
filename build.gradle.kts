plugins {
	java
	id("org.springframework.boot") version "4.0.7"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "ai.devpath"
version = "0.0.1-SNAPSHOT"
description = "DevPath AI platform services (user/auth, github collector, notification)"

val devpathSharedVersion = providers.gradleProperty("devpathSharedVersion").get()
val devpathSharedCoordinate = "ai.devpath:devpath-shared:$devpathSharedVersion"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	providers.gradleProperty("immutableSharedRepository").orNull?.let { repository ->
		maven { url = uri(repository) }
	}
	mavenCentral()
	maven {
		url = uri("https://maven.pkg.github.com/DevPathAi/devpath-shared")
		credentials {
			username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
			password = providers.gradleProperty("gpr.token").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
		}
	}
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-restclient")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
	implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
	implementation("org.springframework.boot:spring-boot-starter-data-redis")
	implementation("com.google.crypto.tink:tink:1.18.0")
	implementation(devpathSharedCoordinate)
	// P2 avatar: shared storage는 s3를 compileOnly로 제공 → 소비 svc가 런타임 s3 제공(MinIO 호환)
	runtimeOnly(platform("software.amazon.awssdk:bom:2.28.0"))
	runtimeOnly("software.amazon.awssdk:s3")
	runtimeOnly("org.postgresql:postgresql")
	compileOnly("org.projectlombok:lombok")
	annotationProcessor("org.projectlombok:lombok")
	implementation("org.springframework.kafka:spring-kafka")
	implementation("org.springframework.boot:spring-boot-kafka")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.kafka:spring-kafka-test")
	// 테스트 전용: shared jar의 db/migration(classpath)으로 테스트 DB 스키마 프로비저닝(ddl-auto: validate 선행).
	// 런타임/배포는 중앙 마이그레이션 잡이 담당하므로 main에는 flyway 미포함(flyway: enabled=false).
	// Boot 4는 autoconfigure가 모듈 분리됨 → Flyway 자동구성(FlywayAutoConfiguration)은 spring-boot-flyway에 있다.
	testImplementation("org.springframework.boot:spring-boot-flyway")
	testImplementation("org.flywaydb:flyway-core")
	testImplementation("org.flywaydb:flyway-database-postgresql")
	testCompileOnly("org.projectlombok:lombok")
	testImplementation("org.awaitility:awaitility")
	testImplementation("no.nav.security:mock-oauth2-server:2.1.10")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	testAnnotationProcessor("org.projectlombok:lombok")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

val verifyProductionRestClientRuntime by tasks.registering {
	doLast {
		val hasRestClientAutoConfiguration = configurations.runtimeClasspath.get()
			.resolvedConfiguration.resolvedArtifacts
			.any { artifact ->
				artifact.moduleVersion.id.group == "org.springframework.boot"
					&& artifact.name == "spring-boot-restclient"
			}
		check(hasRestClientAutoConfiguration) {
			"Production runtime must include spring-boot-restclient for RestClient.Builder auto-configuration"
		}
	}
}

tasks.named("check") {
	dependsOn(verifyProductionRestClientRuntime)
}
