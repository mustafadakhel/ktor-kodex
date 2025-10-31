plugins {
    kotlin("jvm")
    application
}

application {
    mainClass.set("com.mustafadakhel.kodex.sample.ApplicationKt")
}

dependencies {
    implementation(libs.ktor.server.netty)
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
}
