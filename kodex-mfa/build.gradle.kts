import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

version = libs.versions.kodex.get()

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

sourceSets {
    create("integrationTest") {
        kotlin {
            srcDir("src/integrationTest/kotlin")
        }
        resources {
            srcDir("src/integrationTest/resources")
        }
        compileClasspath += sourceSets["main"].output + sourceSets["test"].output
        runtimeClasspath += sourceSets["main"].output + sourceSets["test"].output
    }
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

    val integrationTest = register<Test>("integrationTest") {
        description = "Runs integration tests."
        group = "verification"

        testClassesDirs = sourceSets["integrationTest"].output.classesDirs
        classpath = sourceSets["integrationTest"].runtimeClasspath

        useJUnitPlatform()
        shouldRunAfter(test)
    }

    named<Task>("compileIntegrationTestKotlin") {
        (this as org.jetbrains.kotlin.gradle.tasks.KotlinCompile).compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xfriend-paths=${project.layout.buildDirectory.get()}/classes/kotlin/main")
        }
    }

    named<Task>("check") {
        dependsOn(integrationTest)
    }
}

val integrationTestImplementation by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
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
    testImplementation(project(":kodex-ratelimit-inmemory"))

    integrationTestImplementation(libs.h2.database)
    integrationTestImplementation(libs.ktor.server.test.host)
    integrationTestImplementation("io.ktor:ktor-server-content-negotiation:3.2.1")
    integrationTestImplementation("io.ktor:ktor-serialization-kotlinx-json:3.2.1")
    integrationTestImplementation(libs.logback.classic)
    integrationTestImplementation(project(":kodex-ratelimit-inmemory"))
    integrationTestImplementation(project(":kodex"))
    integrationTestImplementation(libs.bundles.kotest)
    integrationTestImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    integrationTestImplementation("dev.turingcomplete:kotlin-onetimepassword:2.4.1")
}
