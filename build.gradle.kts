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
    testImplementation(kotlin("test"))
    testImplementation("io.papermc.paper:paper-api:26.2.build.111-stable")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v26.2:4.116.1")
}

paperweight {
    addServerDependencyTo = configurations.named("compileOnly").map { setOf(it) }
    reobfArtifactConfiguration.set(ReobfArtifactConfiguration.MOJANG_PRODUCTION)
}

val nativeTest = sourceSets.create("nativeTest")
configurations[nativeTest.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[nativeTest.compileOnlyConfigurationName].extendsFrom(configurations.compileOnly.get())
nativeTest.compileClasspath += sourceSets.main.get().output
tasks.register<Jar>("nativeTestJar") {
    dependsOn(tasks.named(nativeTest.classesTaskName))
    archiveClassifier.set("native-test")
    from(nativeTest.output)
}

tasks {
    test { useJUnitPlatform() }
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
