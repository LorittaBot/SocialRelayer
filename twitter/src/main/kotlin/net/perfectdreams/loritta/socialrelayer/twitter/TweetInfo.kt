package net.perfectdreams.loritta.socialrelayer.twitter

import kotlinx.serialization.Serializable
import net.perfectdreams.loritta.socialrelayer.twitter.trackers.TrackerSource

@Serializable
data class TweetInfo(
    val trackerSource: TrackerSource,
    val userId: Long,
    val screenName: String,
    val tweetId: Long
)