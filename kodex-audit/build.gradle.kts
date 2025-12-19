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
    // Depend on core kodex module
    api(project(":kodex"))

    // Ktor dependencies (for KtorDsl annotation)
    implementation(libs.bundles.ktor.server)

    // Exposed dependencies for database access
    implementation(libs.bundles.exposed)
    implementation(libs.hikari)

    // kotlinx.datetime for timestamp handling
    implementation(libs.kotlinx.datetime)

    // Test dependencies
    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
    testImplementation(libs.h2.database)
    testImplementation(libs.kotlinx.coroutines.test)
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    configure(KotlinJvm(javadocJar = JavadocJar.Javadoc(), sourcesJar = true))
    coordinates(group as String, project.name, version as String)
    pom {
        name.set(project.name)
        description.set("Audit logging module for Kodex authentication library")
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
