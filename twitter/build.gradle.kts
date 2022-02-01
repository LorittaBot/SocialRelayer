plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.google.cloud.tools.jib") version Versions.JIB
}

group = "net.perfectdreams.loritta.socialrelayer.twitter"
version = "1.0.0"

dependencies {
    implementation(kotlin("stdlib"))
    implementation(project(":common"))

    api("org.twitter4j:twitter4j-core:4.0.7")
    api("org.twitter4j:twitter4j-stream:4.0.7")

    api("org.jsoup:jsoup:1.13.1")
}

jib {
    to {
        image = "ghcr.io/lorittabot/socialrelayer-twitter"

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
        "net.perfectdreams.loritta.socialrelayer.twitter.TweetRelayerLauncher",
        mapOf()
    )

    "build" {
        dependsOn(runnableJar)
    }
}