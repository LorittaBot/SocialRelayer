plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.google.cloud.tools.jib") version Versions.JIB
}

group = "net.perfectdreams.loritta.socialrelayer.twitch"
version = "1.0.0"

dependencies {
    implementation(kotlin("stdlib"))
    implementation(project(":common"))

    implementation("com.github.ben-manes.caffeine:caffeine:3.0.2")
    implementation("io.ktor:ktor-server-core:${Versions.KTOR}")
    implementation("io.ktor:ktor-server-netty:${Versions.KTOR}")
    implementation("net.perfectdreams.sequins.ktor:base-route:1.0.4")
}

jib {
    container {
        ports = listOf("8000")
    }

    to {
        image = "ghcr.io/lorittabot/socialrelayer-twitch"

        auth {
            username = System.getProperty("DOCKER_USERNAME") ?: System.getenv("DOCKER_USERNAME")
            password = System.getProperty("DOCKER_PASSWORD") ?: System.getenv("DOCKER_PASSWORD")
        }
    }

    from {
        image = "openjdk:15.0.2-slim-buster"
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
    kotlinOptions.javaParameters = true
}

tasks {
    val runnableJar = runnableJarTask(
        DEFAULT_SHADED_WITHIN_JAR_LIBRARIES,
        configurations.runtimeClasspath.get(),
        jar.get(),
        "net.perfectdreams.loritta.socialrelayer.twitch.TwitchRelayerLauncher",
        mapOf()
    )

    "build" {
        dependsOn(runnableJar)
    }
}