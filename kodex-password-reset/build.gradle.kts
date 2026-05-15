import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

version = libs.versions.kodex.get()

plugins {
    kotlin("jvm")
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
    // Core dependencies
    api(project(":kodex"))
    implementation(project(":kodex-tokens"))

    // Database dependencies (Exposed)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.datetime)

    // Ktor dependencies (for KtorDsl annotation)
    implementation(libs.bundles.ktor.server)

    // Datetime library
    implementation(libs.kotlinx.datetime)

    // Test dependencies
    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
    testImplementation(project(":kodex-ratelimit-inmemory"))

    // Integration test dependencies
    integrationTestImplementation(libs.h2.database)
    integrationTestImplementation(libs.bundles.kotest)
    integrationTestImplementation(project(":kodex-ratelimit-inmemory"))
    integrationTestImplementation(project(":kodex"))
    integrationTestImplementation(project(":kodex-tokens"))
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    configure(KotlinJvm(javadocJar = JavadocJar.Javadoc(), sourcesJar = true))
    coordinates(group as String, project.name, version as String)
    pom {
        name.set(project.name)
        description.set("Password reset module for Kodex authentication library")
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
