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
    implementation(project(":kodex-tokens"))

    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.datetime)

    implementation(libs.bundles.ktor.server)

    implementation(libs.kotlinx.datetime)

    implementation("dev.turingcomplete:kotlin-onetimepassword:2.4.1")
    implementation("io.github.g0dkar:qrcode-kotlin-jvm:4.1.1")
    implementation(libs.bouncycastle.bcprov)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
}
