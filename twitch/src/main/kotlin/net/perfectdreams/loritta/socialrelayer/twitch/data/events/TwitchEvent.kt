package net.perfectdreams.loritta.socialrelayer.twitch.data.events

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
    data class StreamOnlineEvent(
    val id: String,
    @SerialName("broadcaster_user_id")
    val broadcasterUserId: String,
    @SerialName("broadcaster_user_login")
    val broadcasterUserLogin: String,
    @SerialName("broadcaster_user_name")
    val broadcasterUserName: String,
    val type: String,
    val startedAt: String
)