/**
 * Pure Kotlin/JVM — deliberately NOT an Android library. The clinical model and
 * the whole spoken-command pipeline live here, so the compiler (not discipline)
 * guarantees no Android dependency can leak in, and the unit tests run without
 * building the app.
 */
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)   // runBlocking in tests
}
