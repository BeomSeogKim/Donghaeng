plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"
    kotlin("plugin.jpa") version "2.2.21"
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jlleitschuh.gradle.ktlint") version "13.1.0"
}

group = "com.donghaeng"
version = "0.0.1-SNAPSHOT"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

// Without this, entities stay final and @ManyToOne(fetch = LAZY) silently degrades to eager.
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Brings spring-boot-starter-security with it, so the filter chain in
    // com.donghaeng.auth.SecurityConfig is not optional decoration — without that
    // bean, Boot's default chain would demand authentication for every request and
    // contradict the decision that the gate is our resolver
    // (notes/2026-08-10-decision-auth-gate-and-sequence.md).
    //
    // The point of the starter is the parts nobody should hand-write: the `state`
    // check, PKCE, and full ID-token validation — signature via JWKS, `iss`, `aud`,
    // `exp`. "Any one of these missing is an account-takeover path"
    // (notes/2026-07-30-decision-network-security.md).
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // The seam: springdoc exposes /v3/api-docs, which web/ generates its TS types from.
    // notes/2026-08-08-decision-build-workflow.md
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:2.9.0")

    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}

tasks.withType<Test> {
    useJUnitPlatform()

    // Flyway is disabled in application.yml for every environment — it runs in
    // the tests and nowhere else (notes/2026-08-09-decision-schema-ownership.md).
    // This is where the tests opt back in, so the suite keeps building its schema
    // from the migration files, which are also the text the founder applies by
    // hand. One copy of the DDL, checked by the same tests that use it.
    //
    // A system property rather than a yml, and the alternatives are why:
    // src/test/resources/application.yml would SHADOW the committed base file on
    // the test classpath, which is precisely what RealConfigurationBootTest
    // exists to prevent, and there is deliberately no `test` profile (README,
    // Profiles).
    //
    // Exactly what this reaches, stated precisely because it is easy to get
    // wrong: EVERY JVM forked by a `Test` task, wherever that task runs. That
    // includes both CI jobs that run one — `api` (./gradlew build) and
    // `prod-boot` (./gradlew test --tests '...ProfileBootTest'). So `prod-boot`
    // boots the committed prod configuration with Flyway ENABLED, and does not
    // rehearse the hand-applied schema that a real deploy gets. Whether it
    // should is a decision for the founder, not something to paper over here.
    //
    // What it does NOT reach: `bootRun`, the packaged jar, and therefore CI's
    // `docker` job, which starts the real image. Those are the JVMs
    // SchemaOwnershipGuard refuses to start, and the two directions are
    // asserted by ProfileConfigurationTest, RealConfigurationBootTest and
    // SchemaOwnershipGuardTest.
    systemProperty("spring.flyway.enabled", "true")
}

// The Dockerfile copies build/libs/*.jar. With the plain jar enabled, the glob
// matches two files the day anything runs `build` instead of `bootJar` — and
// the plain jar is not runnable. Nothing consumes this project as a library.
tasks.jar {
    enabled = false
}
