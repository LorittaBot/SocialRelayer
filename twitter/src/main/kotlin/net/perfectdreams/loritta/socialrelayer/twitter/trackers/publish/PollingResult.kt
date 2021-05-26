package net.perfectdreams.loritta.socialrelayer.twitter.trackers.publish

import kotlinx.serialization.Serializable

@Serializable
open class PollingResult(
    val statusCode: Int,
    val polledAt: Long
)