group = "com.mustafadakhel.kodex"
version = libs.versions.kodex.get()

plugins {
    kotlin("jvm") version libs.versions.kotlin.get()
    id("com.vanniktech.maven.publish") version "0.33.0"
}

allprojects {
    repositories {
        mavenCentral()
        google()
    }
}
