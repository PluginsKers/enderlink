pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        maven("https://maven.kikugie.dev/releases")
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.8"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "enderlink"

stonecutter {
    kotlinController = true
    create(rootProject) {
        versions("1.21.11", "26.1.2", "26.2", "26.3")
        vcsVersion = "26.3"
    }
}
