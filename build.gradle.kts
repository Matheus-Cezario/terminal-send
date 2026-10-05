plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.shadow) apply false
}

subprojects {
    group = "dev.terminalsend"
    version = "0.1.0-SNAPSHOT"

    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion = JavaLanguageVersion.of(21)
    }

    repositories {
        mavenCentral()
    }

    val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")
    dependencies {
        // Spring Boot BOM pins versions for every module (including protocol and client).
        "implementation"(platform(libs.findLibrary("spring-boot-bom").get()))
        "testImplementation"(platform(libs.findLibrary("spring-boot-bom").get()))
        "testImplementation"(libs.findLibrary("junit-jupiter").get())
        "testImplementation"(libs.findLibrary("assertj").get())
        "testRuntimeOnly"(libs.findLibrary("junit-launcher").get())
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all", "-Xlint:-processing"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
