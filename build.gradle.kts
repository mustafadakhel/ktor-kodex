group = "com.mustafadakhel.kodex"
version = libs.versions.kodex.get()

plugins {
    kotlin("jvm") version "2.1.21" apply false
    kotlin("plugin.serialization") version "2.1.21" apply false
    id("com.vanniktech.maven.publish") version "0.33.0" apply false
}

allprojects {
    repositories {
        mavenCentral()
    }

    group = "com.mustafadakhel.kodex"
}
