import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

version = libs.versions.kodex.get()

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.1.21"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks {
    test {
        useJUnitPlatform()
    }
    compileKotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            explicitApiMode = ExplicitApiMode.Strict
            allWarningsAsErrors = true
        }
    }

    compileTestKotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}

dependencies {
    api(project(":kodex"))

    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.datetime)
    implementation(libs.kotlinx.datetime)
    implementation(libs.bundles.ktor.server)

    // HTTP client for geolocation API
    implementation("io.ktor:ktor-client-core:2.3.6")
    implementation("io.ktor:ktor-client-cio:2.3.6")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.6")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.6")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
    testImplementation(project(":kodex-ratelimit-inmemory"))
}
