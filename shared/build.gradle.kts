plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        // The Android modules consume this artifact directly, so keep it free of
        // anything that would not run on Android's JVM.
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
}
