package net.perfectdreams.loritta.socialrelayer.twitter.trackers.publish

class SuccessfulPollingResult(
    statusCode: Int,
    polledAt: Long,
    val tweets: List<PolledTweet>
) : PollingResult(statusCode, polledAt)