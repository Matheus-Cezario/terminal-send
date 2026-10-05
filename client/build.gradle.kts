plugins {
    application
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":protocol"))
    implementation(libs.jackson.databind)
    implementation(libs.lanterna)
    implementation(libs.sqlite.jdbc)
}

application {
    mainClass = "dev.terminalsend.client.Main"
    applicationName = "terminal-send"
}

tasks.shadowJar {
    archiveBaseName = "terminal-send"
    archiveClassifier = ""
    archiveVersion = ""
    mergeServiceFiles()
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
