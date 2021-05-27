package net.perfectdreams.loritta.socialrelayer.twitch.data

import kotlinx.serialization.Serializable

@Serializable
data class SubTransportCreate(
    val method: String,
    val callback: String,
    val secret: String
)