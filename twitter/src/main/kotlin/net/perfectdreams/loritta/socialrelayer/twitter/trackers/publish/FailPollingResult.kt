package net.perfectdreams.loritta.socialrelayer.twitter.trackers.publish

class FailPollingResult(
    statusCode: Int,
    polledAt: Long
) : PollingResult(statusCode, polledAt)