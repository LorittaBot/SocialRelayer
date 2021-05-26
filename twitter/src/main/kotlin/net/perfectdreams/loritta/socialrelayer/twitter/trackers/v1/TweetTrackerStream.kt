package net.perfectdreams.loritta.socialrelayer.twitter.trackers.v1

import mu.KotlinLogging
import net.perfectdreams.loritta.socialrelayer.twitter.TweetInfo
import net.perfectdreams.loritta.socialrelayer.twitter.TweetRelayer
import net.perfectdreams.loritta.socialrelayer.twitter.trackers.TrackerSource
import twitter4j.*
import twitter4j.conf.Configuration

class TweetTrackerStream(val tweetRelayer: TweetRelayer, val configuration: Configuration, val userIds: List<Long>) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    var stream: TwitterStream? = null
    var isClosed = false

    fun start() {
        val stream = TwitterStreamFactory(configuration).instance
        this.stream = stream

        stream.addListener(object : StatusListener {
            override fun onTrackLimitationNotice(p0: Int) {
                logger.warn(p0.toString())
            }

            override fun onStallWarning(p0: StallWarning) {
                logger.warn(p0.toString())
            }

            override fun onException(p0: Exception) {
                logger.warn(p0) { "" }
            }

            override fun onDeletionNotice(p0: StatusDeletionNotice) {}

            override fun onStatus(p0: Status) {
                if (p0.isRetweet) // It is a retweet, just ignore it
                    return

                if (p0.user.id !in userIds) // User ID is not in our list, so we don't need to relay it (this can happen when replying to a tweet!)
                    return

                if (isClosed) // Sometimes closing a stream keeps it open for *some reason*, so let's just ignore if this happens
                    return

                logger.info { "Received status ${p0.id} from ${p0.user.screenName} (${p0.user.id})" }

                tweetRelayer.receivedNewTweet(
                    TweetInfo(
                        TrackerSource.v1_STREAM,
                        p0.user.id,
                        p0.user.screenName,
                        p0.id
                    )
                )
            }

            override fun onScrubGeo(p0: Long, p1: Long) {}
        })

        val tweetFilterQuery = FilterQuery()
        tweetFilterQuery.follow(
            *userIds.toLongArray()
        )

        stream.filter(tweetFilterQuery)
    }

    fun stop() {
        stream?.clearListeners()
        stream?.cleanUp()
        isClosed = true
    }
}