package net.perfectdreams.loritta.socialrelayer.twitter.trackers.publish

class NoopPollingResult(
    statusCode: Int,
    polledAt: Long
) : PollingResult(statusCode, polledAt)