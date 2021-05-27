package net.perfectdreams.loritta.socialrelayer.twitch.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.*

@Serializable
data class SubscriptionData(
    val id: String,
    val status: String,
    val type: String,
    val version: String,
    val cost: Int,
    val condition: Map<String, String>,
    val transport: SubTransport,
    @SerialName("created_at")
    val createdAt: String
)