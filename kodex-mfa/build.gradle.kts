import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

version = libs.versions.kodex.get()

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.vanniktech.maven.publish")
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

    implementation(libs.kotlin.otp)
    implementation(libs.qrcode.kotlin)
    implementation(libs.bouncycastle.bcprov)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
    testImplementation(project(":kodex-ratelimit-inmemory"))

    integrationTestImplementation(libs.h2.database)
    integrationTestImplementation(libs.ktor.server.test.host)
    integrationTestImplementation(libs.ktor.server.content.negotiation)
    integrationTestImplementation(libs.ktor.serialization.kotlinx.json)
    integrationTestImplementation(libs.logback.classic)
    integrationTestImplementation(project(":kodex-ratelimit-inmemory"))
    integrationTestImplementation(project(":kodex"))
    integrationTestImplementation(libs.bundles.kotest)
    integrationTestImplementation(libs.kotlinx.serialization.json)
    integrationTestImplementation(libs.kotlin.otp)
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    configure(KotlinJvm(javadocJar = JavadocJar.Javadoc(), sourcesJar = true))
    coordinates(group as String, project.name, version as String)
    pom {
        name.set(project.name)
        description.set("Multi-factor authentication module for Kodex authentication library")
        url.set("https://github.com/mustafadakhel/ktor-kodex")
        licenses {
            license {
                name.set("The Apache Software License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/mustafadakhel/ktor-kodex.git")
            developerConnection.set("scm:git:ssh://github.com/mustafadakhel/ktor-kodex.git")
            url.set("https://github.com/mustafadakhel/ktor-kodex")
        }
        developers {
            developer {
                id.set("mustafadakhel")
                name.set("Mustafa M. Dakhel")
                email.set("mstfdakhel@gmail.com")
            }
        }
    }
}
