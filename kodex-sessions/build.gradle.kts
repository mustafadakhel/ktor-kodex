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
    implementation(libs.bundles.ktor.client)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
    testImplementation(project(":kodex-ratelimit-inmemory"))
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    configure(KotlinJvm(javadocJar = JavadocJar.Javadoc(), sourcesJar = true))
    coordinates(group as String, project.name, version as String)
    pom {
        name.set(project.name)
        description.set("Session management module for Kodex authentication library")
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
