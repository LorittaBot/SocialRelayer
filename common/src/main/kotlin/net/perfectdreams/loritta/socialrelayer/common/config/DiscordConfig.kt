package net.perfectdreams.loritta.socialrelayer.common.config

import kotlinx.serialization.Serializable

@Serializable
data class DiscordConfig(
    val token: String
)