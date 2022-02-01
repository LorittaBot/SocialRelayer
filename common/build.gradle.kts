plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

group = "net.perfectdreams.loritta.socialrelayer.common"
version = Versions.SOCIAL_RELAYER

dependencies {
    implementation(kotlin("stdlib"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:${Versions.KOTLIN_COROUTINES}")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:${Versions.KOTLIN_COROUTINES}")

    api("net.perfectdreams.loritta.platforms.discord:db-tables:0.0.1-SNAPSHOT")

    // Used to query Loritta's DBs
    api("org.postgresql:postgresql:42.3.1")
    api("com.zaxxer:HikariCP:5.0.1")

    api("org.jetbrains.exposed:exposed-core:${Versions.EXPOSED}")
    api("org.jetbrains.exposed:exposed-jdbc:${Versions.EXPOSED}")
    api("org.jetbrains.exposed:exposed-dao:${Versions.EXPOSED}")
    api("pw.forst:exposed-upsert:1.1.0")

    api("io.ktor:ktor-client-core:${Versions.KTOR}")
    api("io.ktor:ktor-client-java:${Versions.KTOR}")

    api("org.jetbrains.kotlinx:kotlinx-serialization-json:${Versions.KOTLIN_SERIALIZATION}")
    api("org.jetbrains.kotlinx:kotlinx-serialization-hocon:${Versions.KOTLIN_SERIALIZATION}")

    api("com.github.ben-manes.caffeine:caffeine:3.0.5")

    // Used for REST
    api("dev.kord:kord-rest:0.8.x-SNAPSHOT")

    // Used for embed parsing
    api("com.google.code.gson:gson:2.8.9")
    api("com.github.salomonbrys.kotson:kotson:2.5.0")

    // Used for webhooks
    api("club.minnced:discord-webhooks:0.7.4")

    // Async Appender is broke in alpha5
    // https://stackoverflow.com/questions/58742485/logback-error-no-attached-appenders-found
    api("ch.qos.logback:logback-classic:1.3.0-alpha4")
    api("io.github.microutils:kotlin-logging-jvm:2.1.21")

    // Graylog GELF (Logback)
    api("de.siegmar:logback-gelf:4.0.2")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
    kotlinOptions.javaParameters = true
}