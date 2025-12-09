plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

application {
    mainClass.set("com.mustafadakhel.kodex.sample.ApplicationKt")
}

dependencies {
    val ktorVersion = "3.2.1"

    implementation(libs.ktor.server.netty)
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    // HTTP client for sessions module geolocation
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.4.14")

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
