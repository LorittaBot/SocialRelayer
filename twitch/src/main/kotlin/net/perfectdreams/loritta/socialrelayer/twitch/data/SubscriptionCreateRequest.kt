package net.perfectdreams.loritta.socialrelayer.twitch.data

import kotlinx.serialization.Serializable

@Serializable
data class SubscriptionCreateRequest(
    val type: String,
    val version: String,
    val condition: Map<String, String>,
    val transport: SubTransportCreate
)