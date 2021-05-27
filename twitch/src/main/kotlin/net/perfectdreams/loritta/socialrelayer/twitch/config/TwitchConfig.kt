package net.perfectdreams.loritta.socialrelayer.twitch.config

import kotlinx.serialization.Serializable

@Serializable
data class TwitchConfig(
    val clientId: String,
    val clientSecret: String
)