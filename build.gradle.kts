plugins {
    kotlin("jvm") version Versions.KOTLIN
    kotlin("plugin.serialization") version Versions.KOTLIN
}

group = "net.perfectdreams.loritta.socialrelayer"
version = Versions.SOCIAL_RELAYER

allprojects {
    repositories {
        mavenCentral()
        maven("https://oss.sonatype.org/content/repositories/snapshots")
        maven("https://repo.perfectdreams.net/")
    }
}

dependencies {
    implementation(kotlin("stdlib"))
}