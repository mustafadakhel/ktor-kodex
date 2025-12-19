group = "com.mustafadakhel.kodex"
version = libs.versions.kodex.get()

plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
    id("com.vanniktech.maven.publish") version "0.33.0"
}

allprojects {
    repositories {
        mavenCentral()
        google()
    }

    group = "com.mustafadakhel.kodex"
}
