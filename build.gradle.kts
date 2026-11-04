import io.papermc.paperweight.userdev.PaperweightUserDependenciesExtension
import io.papermc.paperweight.userdev.ReobfArtifactConfiguration

plugins {
    kotlin("jvm") version "2.3.21"
    id("com.gradleup.shadow") version "8.3.5"
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
}

val minecraftVersion = stonecutter.current.version
val legacy = stonecutter.eval(minecraftVersion, "<26.1")
val javaVersion = if (legacy) 21 else 25
version = "${providers.gradleProperty("version").get()}+mc$minecraftVersion"
base { archivesName.set("enderlink") }

stonecutter.replacements {
    string(legacy) { replace("\"packetListener\"", "\"handle\"") }
}

kotlin { jvmToolchain(javaVersion) }

repositories {
    maven("https://maven.aliyun.com/repository/public")
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    the<PaperweightUserDependenciesExtension>().foliaDevBundle(property("folia_version").toString())
}

paperweight {
    addServerDependencyTo = configurations.named("compileOnly").map { setOf(it) }
    reobfArtifactConfiguration.set(ReobfArtifactConfiguration.MOJANG_PRODUCTION)
}

tasks.withType<Jar>().configureEach {
    into("META-INF") { from(rootProject.file("LICENSE"), rootProject.file("NOTICE")) }
}

tasks {
    processResources {
        val properties = mapOf("version" to project.version, "minecraft" to minecraftVersion)
        inputs.properties(properties)
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") { expand(properties) }
    }
    jar { enabled = false }
    shadowJar {
        archiveClassifier.set("")
        mergeServiceFiles()
    }
    assemble { dependsOn(shadowJar) }
}
