plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

application {
    mainClass.set("com.mustafadakhel.kodex.sample.ApplicationKt")
}

dependencies {
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // HTTP client for sessions module geolocation
    implementation(libs.bundles.ktor.client)
    implementation(libs.kotlinx.serialization.json)

    // Logging
    implementation(libs.logback.classic)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    implementation(project(":kodex"))
    implementation(project(":kodex-validation"))
    implementation(project(":kodex-lockout"))
    implementation(project(":kodex-audit"))
    implementation(project(":kodex-metrics"))
    implementation(project(":kodex-verification"))
    implementation(project(":kodex-password-reset"))
}
