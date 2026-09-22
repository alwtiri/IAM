// iam-worker — provider execution plane (ADR-0010, ADR-0018, PHASE-3-DESIGN §3).
// Depends on the SPI and provider plugins only; never on core (MODULE-BOUNDARIES §1). No database access.
plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(project(":shared-kernel"))
    implementation(project(":provider-spi"))

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-webmvc") // health/metrics endpoint only
    implementation("org.springframework.boot:spring-boot-starter-amqp")

    // Provider plugins are loaded with ServiceLoader from the runtime classpath.
    runtimeOnly(project(":providers:generic-rest"))
    runtimeOnly(project(":providers:linux-ssh"))
    runtimeOnly(project(":providers:active-directory"))
    runtimeOnly(project(":providers:windows-winrm"))
    runtimeOnly(project(":providers:postgresql"))

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("iam-worker.jar")
}
