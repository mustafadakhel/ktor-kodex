import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

version = libs.versions.kodex.get()

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.vanniktech.maven.publish")
    jacoco
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
        finalizedBy(jacocoTestReport)
    }

    jacocoTestReport {
        dependsOn(test)
        reports {
            xml.required.set(true)
            html.required.set(true)
            csv.required.set(false)
        }
    }

    jacocoTestCoverageVerification {
        violationRules {
            rule {
                limit {
                    minimum = "0.80".toBigDecimal()
                }
            }
        }
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
    implementation(libs.h2.database)
    api(libs.hikari)
    api(libs.kotlinx.datetime)
    implementation(project(":kodex-tokens"))
    api(libs.slf4j.api)
    implementation(libs.bundles.exposed)
    implementation(libs.bundles.ktor.server)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.flyway.core)
    compileOnly(libs.micrometer.core)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.micrometer.core)
    testImplementation(libs.logback.classic)

    integrationTestImplementation(libs.h2.database)
    integrationTestImplementation(libs.ktor.server.test.host)
    integrationTestImplementation(libs.logback.classic)
}

mavenPublishing {
    publishToMavenCentral()

    signAllPublications()
    configure(
        KotlinJvm(
            javadocJar = JavadocJar.Javadoc(),
            sourcesJar = true
        )
    )
    coordinates(rootProject.group as String?, project.name, project.version as String?)
    pom {
        name.set(project.name)
        description.set("A Ktor plugin that provides user management and JWT based authentication")
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

afterEvaluate {
    tasks.named("generateMetadataFileForMavenPublication").configure {
        dependsOn("plainJavadocJar")
    }
}