plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Java 17 to match what the Android modules compile against — this artifact is consumed by them
// directly, so it must not emit anything newer than they can read.
kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
}
