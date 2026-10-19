import io.papermc.paperweight.userdev.PaperweightUserDependenciesExtension
import io.papermc.paperweight.userdev.ReobfArtifactConfiguration

plugins {
    kotlin("jvm") version "2.3.21"
    id("com.gradleup.shadow") version "8.3.5"
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
}

kotlin { jvmToolchain(25) }

repositories {
    maven("https://maven.aliyun.com/repository/public")
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    the<PaperweightUserDependenciesExtension>().foliaDevBundle("26.2.build.4-beta")
}

paperweight {
    addServerDependencyTo = configurations.named("compileOnly").map { setOf(it) }
    reobfArtifactConfiguration.set(ReobfArtifactConfiguration.MOJANG_PRODUCTION)
}

tasks.withType<Jar>().configureEach {
    into("META-INF") { from("LICENSE", "NOTICE") }
}

tasks {
    processResources {
        inputs.property("version", project.version)
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") { expand("version" to project.version) }
    }
    jar { enabled = false }
    shadowJar {
        archiveClassifier.set("")
        mergeServiceFiles()
    }
    build { dependsOn(shadowJar) }
}
