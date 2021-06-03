package net.perfectdreams.loritta.socialrelayer.common.config

import kotlinx.serialization.Serializable

@Serializable
data class DiscordConfig(
    val applicationId: Long,
    val token: String
)