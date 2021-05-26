package net.perfectdreams.loritta.socialrelayer.twitter.config

import kotlinx.serialization.Serializable
import net.perfectdreams.loritta.socialrelayer.common.config.DiscordConfig
import net.perfectdreams.loritta.socialrelayer.common.config.LorittaDatabaseConfig

@Serializable
class SocialRelayerTwitterConfig(
    val discord: DiscordConfig,
    val database: LorittaDatabaseConfig,
    val twitter: TwitterConfig
)