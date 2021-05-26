plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.google.cloud.tools.jib") version "3.0.0"
}

group = "net.perfectdreams.loritta.socialrelayer.twitter"
version = "1.0.0"

repositories {
    maven("https://oss.sonatype.org/content/repositories/snapshots")
    mavenCentral()
    jcenter()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(project(":common"))

    api("org.twitter4j:twitter4j-core:4.0.7")
    api("org.twitter4j:twitter4j-stream:4.0.7")

    // Used to query Loritta's DBs
    api("org.postgresql:postgresql:42.2.18")
    api("com.zaxxer:HikariCP:3.4.5")

    api("org.jsoup:jsoup:1.13.1")

    // Async Appender is broke in alpha5
    // https://stackoverflow.com/questions/58742485/logback-error-no-attached-appenders-found
    implementation("ch.qos.logback:logback-classic:1.3.0-alpha4")
    implementation("io.github.microutils:kotlin-logging:1.8.3")

    api("com.github.ben-manes.caffeine:caffeine:2.8.8")
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