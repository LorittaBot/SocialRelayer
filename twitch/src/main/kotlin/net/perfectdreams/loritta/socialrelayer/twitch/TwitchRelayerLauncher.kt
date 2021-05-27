package net.perfectdreams.loritta.socialrelayer.twitch

import com.typesafe.config.ConfigFactory
import kotlinx.serialization.hocon.Hocon
import kotlinx.serialization.hocon.decodeFromConfig
import net.perfectdreams.loritta.socialrelayer.twitch.config.SocialRelayerTwitchConfig
import java.io.File

object TwitchRelayerLauncher {
    @JvmStatic
    fun main(args: Array<String>) {
        val config = Hocon.decodeFromConfig<SocialRelayerTwitchConfig>(ConfigFactory.parseFile(File(System.getProperty("config.path") ?: "./app.conf")))

        val relay = TwitchRelayer(config)
        relay.start()
    }
}