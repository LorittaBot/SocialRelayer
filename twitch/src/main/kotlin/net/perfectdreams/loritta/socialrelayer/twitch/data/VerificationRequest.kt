package net.perfectdreams.loritta.socialrelayer.twitch.data

import kotlinx.serialization.Serializable

@Serializable
data class VerificationRequest(
    val challenge: String,
    val subscription: SubscriptionData
)