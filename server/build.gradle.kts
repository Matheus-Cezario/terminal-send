plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":protocol"))

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.websocket)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.mail)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.postgresql)
    // Required by Spring Security's Argon2PasswordEncoder.
    implementation(libs.bouncycastle)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.greenmail)
    // Relay tests exchange real E2E-encrypted envelopes using the client's crypto.
    testImplementation(project(":client"))
}

tasks.bootJar {
    archiveFileName = "server.jar"
}

tasks.jar {
    enabled = false
}
