package net.perfectdreams.loritta.socialrelayer.twitter

import com.typesafe.config.ConfigFactory
import kotlinx.serialization.hocon.Hocon
import kotlinx.serialization.hocon.decodeFromConfig
import net.perfectdreams.loritta.socialrelayer.twitter.config.SocialRelayerTwitterConfig
import java.io.File

object TweetRelayerLauncher {
    @JvmStatic
    fun main(args: Array<String>) {
        val config = Hocon.decodeFromConfig<SocialRelayerTwitterConfig>(ConfigFactory.parseFile(File("./app.conf")))

        val relay = TweetRelayer(config)
        relay.start()
    }
}