plugins {
    `java-library`
}

dependencies {
    // Annotations only: the protocol stays free of any serialization runtime.
    api(libs.jackson.annotations)

    testImplementation(libs.jackson.databind)
}
