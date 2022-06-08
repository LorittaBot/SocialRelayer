package net.perfectdreams.loritta.socialrelayer.twitter.trackers.publish

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.LocalDateTime

@Serializable
data class PolledTweet(
    val tweetId: Long,
    @Contextual
    val sentAt: LocalDateTime
)