package net.perfectdreams.loritta.socialrelayer.twitter.trackers.publish

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import mu.KotlinLogging
import net.perfectdreams.loritta.socialrelayer.twitter.TweetInfo
import net.perfectdreams.loritta.socialrelayer.twitter.TweetRelayer
import net.perfectdreams.loritta.socialrelayer.twitter.trackers.TrackerSource
import java.time.LocalDateTime
import java.util.*

class TweetTrackerPollingManager(val tweetRelayer: TweetRelayer) {
    companion object {
        private val logger = KotlinLogging.logger {}
        private val random = SplittableRandom()
    }

    val semaphore = Semaphore(8)
    val mutex = Mutex()
    val trackerPollings = mutableMapOf<TweetTrackerPollingTimelineEmbed, PollingResult?>()

    suspend fun start() {
        GlobalScope.launch {
            while (true) {
                try {
                    val start = System.currentTimeMillis()

                    // Clone the list to avoid holding the mutex for too long
                    val trackerPollings = mutex.withLock { trackerPollings.toMap() }

                    val brokenPollings = trackerPollings.entries.filter {
                        it.value == null
                    }

                    val randomBrokenPolling = try {
                        brokenPollings.random()
                    } catch (e: Exception) {} // Can throw a exception if the list is empty

                    logger.info { "Checking accounts to be polled... There are ${trackerPollings.size} tracked polling accounts!" }

                    val jobs = mutableListOf<Deferred<*>>()
                    val screenNamesToBePolled = mutableSetOf<String>()

                    // First
                    for ((trackerPolling, result) in trackerPollings) {
                        try {
                            var shouldCheckNow = false
                            if (result != null) {
                                val polledAt = result.polledAt

                                if (result is SuccessfulPollingResult) {
                                    // logger.info { "${trackerPolling.screenName} last poll was a sucess!" }

                                    // If it was a poll success...
                                    // (We don't care if it was a retweet or not, because we care about user activity)
                                    val mostRecentTweet = result.tweets.firstOrNull()

                                    logger.info { "${trackerPolling.screenName} most recent tweet is $mostRecentTweet" }

                                    // To avoid spamming Twitter's embed URL, we are going to apply a small randomness to the value to cause the embed check to shift a little bit
                                    fun valueWithRandomOffset(value: Long) = value + random.nextLong(-15_000, 15_000)

                                    if (mostRecentTweet == null) {
                                        // If there isn't a most recent tweet (maybe the user retweets a lot?)
                                        // We are going to check after 15m
                                        shouldCheckNow = (System.currentTimeMillis() - polledAt) >= valueWithRandomOffset(900_000)
                                    } else {
                                        // If there is a most recent tweet, we are going to base around when the most recent tweet was sent
                                        val sentAt = mostRecentTweet.sentAt
                                        val now = LocalDateTime.now()

                                        // logger.info { "Diff: ${(System.currentTimeMillis() - polledAt)}" }

                                        shouldCheckNow = when {
                                            sentAt.isAfter(now.minusDays(3)) -> {
                                                logger.info { "${trackerPolling.screenName} will use 1 minute delay" }
                                                // Sent the tweet in the last 3 days, so we are going to check every 1 minute
                                                (System.currentTimeMillis() - polledAt) >= valueWithRandomOffset(60_000)
                                            }
                                            sentAt.isAfter(now.minusDays(7)) -> {
                                                logger.info { "${trackerPolling.screenName} will use 2.5m delay" }
                                                // Sent the tweet in the last 7 days, so we are going to check every 2 minutes
                                                (System.currentTimeMillis() - polledAt) >= valueWithRandomOffset(120_000)
                                            }
                                            sentAt.isAfter(now.minusDays(14)) -> {
                                                logger.info { "${trackerPolling.screenName} will use 5m delay" }
                                                // Sent the tweet in the last 14 days, so we are going to check every 5m
                                                (System.currentTimeMillis() - polledAt) >= valueWithRandomOffset(300_000)
                                            }
                                            else -> {
                                                logger.info { "${trackerPolling.screenName} will use 15m delay" }
                                                // anything else, check every 15m
                                                (System.currentTimeMillis() - polledAt) >= valueWithRandomOffset(900_000)
                                            }
                                        }
                                    }
                                } else {
                                    logger.info { "${trackerPolling.screenName} had a poll failure!" }
                                    // If it was a poll failure, we are going to check again after 24 hours
                                    // (example: user privated their account)
                                    shouldCheckNow = (System.currentTimeMillis() - polledAt) >= 86_400_000
                                }
                            } else {
                                // This can happen if the connection throws an exception
                                // But this is pretty bad... we shouldn't check it now...
                                // But what we can do now? Hope for the best?
                                //
                                // Before we did check if it was the first failing poll and, if it was, execute it
                                // But this had a nasty bug that if the first ALWAYS fails, the other maybe-not-broken polls would also not work
                                // So we select a random broken poll and hope for the best, if it was a maybe-not-broken poll, it will leave the broken polls, yay!
                                shouldCheckNow = trackerPolling == randomBrokenPolling
                                // logger.warn { "$trackerPolling for some reason doesn't has a polling result! Bug? Are we going to recheck it? ${shouldCheckNow}" }
                            }

                            logger.info { "Screen Name: ${trackerPolling.screenName}, should check now? $shouldCheckNow" }

                            if (shouldCheckNow) {
                                screenNamesToBePolled.add(trackerPolling.screenName)

                                var wasSuccessful = false
                                var previousMostRecentUserTweet: PolledUserTweet? = null
                                var previousMostRecentTweet: PolledTweet? = null
                                // Now HERE we only care about user tweets
                                if (result is SuccessfulPollingResult) {
                                    wasSuccessful = true
                                    previousMostRecentTweet = result.tweets.firstOrNull()
                                    previousMostRecentUserTweet = result.tweets.filterIsInstance<PolledUserTweet>().firstOrNull()
                                }

                                val previousMostRecentTweetId = previousMostRecentUserTweet?.tweetId
                                logger.info { "${trackerPolling.screenName} last tweet was $previousMostRecentUserTweet (any tweet type: $previousMostRecentTweet), was it successful? $wasSuccessful" }

                                jobs += GlobalScope.async {
                                    val newResult = check(trackerPolling)
                                    logger.info { "Polled ${trackerPolling.screenName}! Result: $newResult" }

                                    if (newResult is SuccessfulPollingResult) {
                                        for (tweet in newResult.tweets.filterIsInstance<PolledUserTweet>()) {
                                            if (previousMostRecentTweetId != null && previousMostRecentTweetId >= tweet.tweetId)
                                                break

                                            trackerPolling.tweetRelayer.receivedNewTweet(
                                                TweetInfo(
                                                    TrackerSource.PUBLISH_POLLING_TIMELINE_EMBED,
                                                    tweetRelayer.retrieveTwitterAccountDataFromDatabaseByScreenName(
                                                        trackerPolling.screenName
                                                    )?.id?.value ?: -1L,
                                                    trackerPolling.screenName, // Fix later
                                                    tweet.tweetId
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        } catch (e: Throwable) {
                            logger.warn(e) { "Something went wrong while polling ${trackerPolling.screenName}'s tweets!" }
                        }
                    }

                    logger.info { "Polling ${jobs.size} users' timelines... Screen Names to be polled: $screenNamesToBePolled" }
                    jobs.awaitAll()
                    val finish = System.currentTimeMillis()
                    logger.info { "Finished polling ${jobs.size} users' timelines! Took ${finish - start}ms - Polled Screen Names: $screenNamesToBePolled" }

                    printStats()
                } catch (e: Throwable) {
                    logger.warn(e) { "Something went wrong while polling user's timelines!" }
                }

                delay(5_000)
            }
        }
    }

    fun printStats() {
        val copy = trackerPollings.toMap()

        val invalidPoll = copy.values.count {
            it !is SuccessfulPollingResult
        }

        val noTweetsAvailable = copy.values.count {
            (it is SuccessfulPollingResult) && it.tweets.count() == 0
        }

        val now = LocalDateTime.now()

        val tweeted3DaysAgo = copy.values.count {
            (it is SuccessfulPollingResult) && it.tweets.count() != 0 && it.tweets.first().sentAt.isAfter(now.minusDays(3))
        }

        val tweeted7DaysAgo = copy.values.count {
            (it is SuccessfulPollingResult) && it.tweets.count() != 0 && it.tweets.first().sentAt.isAfter(now.minusDays(7))
        }

        val tweeted14DaysAgo = copy.values.count {
            (it is SuccessfulPollingResult) && it.tweets.count() != 0 && it.tweets.first().sentAt.isAfter(now.minusDays(14))
        }

        val tweeted30DaysAgo = copy.values.count {
            (it is SuccessfulPollingResult) && it.tweets.count() != 0 && it.tweets.first().sentAt.isAfter(now.minusDays(30))
        }

        val tweeted60DaysAgo = copy.values.count {
            (it is SuccessfulPollingResult) && it.tweets.count() != 0 && it.tweets.first().sentAt.isAfter(now.minusDays(60))
        }

        val tweeted90DaysAgo = copy.values.count {
            (it is SuccessfulPollingResult) && it.tweets.count() != 0 && it.tweets.first().sentAt.isAfter(now.minusDays(90))
        }

        logger.info { "Invalid Poll Status (private account): $invalidPoll" }
        logger.info { "No Tweets Recently Available: $noTweetsAvailable" }
        logger.info { "Tweeted less than 3 Days Ago: $tweeted3DaysAgo" }
        logger.info { "Tweeted less than 14 Days Ago: ${tweeted14DaysAgo - tweeted7DaysAgo}" }
        logger.info { "Tweeted less than 30 Days Ago: ${tweeted30DaysAgo - tweeted14DaysAgo}" }
        logger.info { "Tweeted less than 60 Days Ago: ${tweeted60DaysAgo - tweeted30DaysAgo}" }
        logger.info { "Tweeted less than 80 Days Ago: ${tweeted90DaysAgo - tweeted60DaysAgo}" }
    }

    suspend fun addToPolling(screenName: String, doCheck: Boolean): PollingResult? {
        val existingPoll = getScreenNamePolling(screenName)

        if (existingPoll != null) {
            logger.debug { "$screenName is already being polled! Ignoring request to add to polling..." }
            return existingPoll.value
        }

        val ttpt = TweetTrackerPollingTimelineEmbed(tweetRelayer, screenName)

        // First we are going to do a "clean" check, just so we can store polling results & stuff
        // And then in future jobs we are going to *correctly* check for new tweets, yay!
        if (doCheck) {
            return check(ttpt)
        } else {
            // This is used when Twitter's API is breaking a lot, so we don't want to spam Twitter's endpoints
            mutex.withLock {
                trackerPollings[ttpt] = null
            }
            return null
        }
    }

    suspend fun check(ttpt: TweetTrackerPollingTimelineEmbed): PollingResult? {
        return semaphore.withPermit {
            try {
                withTimeout(15_000) {
                    val result = ttpt.check()

                    mutex.withLock {
                        // If it is a no-op polling result, we will keep the old polling result
                        if (result !is NoopPollingResult) {
                            trackerPollings[ttpt] = result
                        }
                    }

                    result
                }
            } catch (e: Exception) {
                logger.warn(e) { "Exception while checking ${ttpt.screenName}'s status via polling" }
                null
            }
        }
    }

    suspend fun removeFromPolling(screenName: String) {
        val existingPoll = getScreenNamePolling(screenName)
            ?.key

        if (existingPoll != null) {
            mutex.withLock {
                trackerPollings.remove(existingPoll)
            }
        }
    }

    suspend fun getScreenNamePolling(screenName: String) = mutex.withLock {
        trackerPollings.entries.firstOrNull { it.key.screenName == screenName }
    }
}