plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

group = "net.perfectdreams.loritta.socialrelayer.common"
version = Versions.SOCIAL_RELAYER

repositories {
    mavenCentral()
    jcenter()
}

dependencies {
    implementation(kotlin("stdlib"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:${Versions.KOTLIN_COROUTINES}")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:${Versions.KOTLIN_COROUTINES}")

    api("net.perfectdreams.loritta.platforms.discord:db-tables:0.0.1-SNAPSHOT")

    // Used to query Loritta's DBs
    api("org.postgresql:postgresql:42.2.18")
    api("com.zaxxer:HikariCP:3.4.5")

    api("org.jetbrains.exposed:exposed-core:${Versions.EXPOSED}")
    api("org.jetbrains.exposed:exposed-jdbc:${Versions.EXPOSED}")
    api("org.jetbrains.exposed:exposed-dao:${Versions.EXPOSED}")
    api("pw.forst:exposed-upsert:1.1.0")

    api("io.ktor:ktor-client-core:${Versions.KTOR}")
    api("io.ktor:ktor-client-apache:${Versions.KTOR}")
    api("io.ktor:ktor-client-cio:${Versions.KTOR}")

    api("org.jetbrains.kotlinx:kotlinx-serialization-json:${Versions.KOTLIN_SERIALIZATION}")
    api("org.jetbrains.kotlinx:kotlinx-serialization-hocon:${Versions.KOTLIN_SERIALIZATION}")

    api("com.github.ben-manes.caffeine:caffeine:3.0.2")

    // Used for REST
    // https://github.com/kordlib/kord/issues/278
    api("dev.kord:kord-rest:kotlin-1.5-SNAPSHOT") {
        version {
            strictly("kotlin-1.5-SNAPSHOT")
        }
    }

    // Used for embed parsing
    api("com.google.code.gson:gson:2.8.6")
    api("com.github.salomonbrys.kotson:kotson:2.5.0")

    // Used for webhooks
    api("club.minnced:discord-webhooks:0.5.7")

    // Async Appender is broke in alpha5
    // https://stackoverflow.com/questions/58742485/logback-error-no-attached-appenders-found
    api("ch.qos.logback:logback-classic:1.3.0-alpha4")
    api("io.github.microutils:kotlin-logging-jvm:2.0.6")

    // Graylog GELF (Logback)
    api("de.siegmar:logback-gelf:3.0.0")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
    kotlinOptions.javaParameters = true
}