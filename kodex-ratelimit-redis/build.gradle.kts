import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

version = libs.versions.kodex.get()

plugins {
    kotlin("jvm")
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

    implementation("io.lettuce:lettuce-core:6.3.0.RELEASE")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactive:1.7.3")
    implementation(libs.kotlinx.datetime)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("org.testcontainers:testcontainers:1.19.3")
    testImplementation("org.testcontainers:junit-jupiter:1.19.3")
    testImplementation(project(":kodex-ratelimit-inmemory"))
}
