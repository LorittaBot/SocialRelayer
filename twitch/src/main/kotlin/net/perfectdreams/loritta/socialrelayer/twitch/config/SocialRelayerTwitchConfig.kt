package net.perfectdreams.loritta.socialrelayer.twitch.config

import kotlinx.serialization.Serializable
import net.perfectdreams.loritta.socialrelayer.common.config.DiscordConfig
import net.perfectdreams.loritta.socialrelayer.common.config.LorittaDatabaseConfig

@Serializable
class SocialRelayerTwitchConfig(
    val discord: DiscordConfig,
    val database: LorittaDatabaseConfig,
    val twitch: List<TwitchConfig>,
    val webhookUrl: String,
    val webhookSecret: String,
    val discordTwitchWebhook: String
)