package net.perfectdreams.loritta.socialrelayer.twitter.trackers.publish

import kotlinx.serialization.Serializable

@Serializable
sealed class PollingResult(
    val statusCode: Int,
    val polledAt: Long
)