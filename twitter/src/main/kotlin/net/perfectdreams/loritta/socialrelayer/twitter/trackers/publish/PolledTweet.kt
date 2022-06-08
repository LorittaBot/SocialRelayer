package net.perfectdreams.loritta.socialrelayer.twitter.trackers.publish

import java.time.LocalDateTime

sealed class PolledTweet {
    abstract val tweetId: Long
    abstract val sentAt: LocalDateTime
}

data class PolledUserTweet(override val tweetId: Long, override val sentAt: LocalDateTime) : PolledTweet()
data class PolledUserRetweet(override val tweetId: Long, override val sentAt: LocalDateTime) : PolledTweet()