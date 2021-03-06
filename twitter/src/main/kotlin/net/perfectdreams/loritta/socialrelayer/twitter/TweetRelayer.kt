package net.perfectdreams.loritta.socialrelayer.twitter

import dev.kord.rest.service.RestClient
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import mu.KotlinLogging
import net.perfectdreams.loritta.common.exposed.tables.CachedDiscordWebhooks
import net.perfectdreams.loritta.socialrelayer.common.utils.DatabaseUtils
import net.perfectdreams.loritta.socialrelayer.common.utils.webhooks.WebhookManager
import net.perfectdreams.loritta.socialrelayer.twitter.config.SocialRelayerTwitterConfig
import net.perfectdreams.loritta.socialrelayer.twitter.dao.CachedTwitterAccount
import net.perfectdreams.loritta.socialrelayer.twitter.dao.InvalidTwitterId
import net.perfectdreams.loritta.socialrelayer.twitter.tables.CachedTwitterAccounts
import net.perfectdreams.loritta.socialrelayer.twitter.tables.InvalidTwitterIds
import net.perfectdreams.loritta.socialrelayer.twitter.tables.TrackedTwitterAccounts
import net.perfectdreams.loritta.socialrelayer.twitter.trackers.publish.TweetTrackerPollingManager
import net.perfectdreams.loritta.socialrelayer.twitter.trackers.v2.TweetTrackerStream
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import pw.forst.exposed.insertOrUpdate
import twitter4j.TwitterException
import twitter4j.TwitterFactory
import twitter4j.conf.Configuration
import twitter4j.conf.ConfigurationBuilder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.system.measureTimeMillis

class TweetRelayer(val config: SocialRelayerTwitterConfig) {
    companion object {
        private val logger = KotlinLogging.logger {}
        private const val CATALYSTDRIFTER_ID = 1166037075264753664L
    }

    val twitter4j = TwitterFactory(buildTwitterConfig()).instance

    val lorittaDatabase = DatabaseUtils.connectToDatabase(config.database)
    private val rest = RestClient(config.discord.token)

    val webhookManager = WebhookManager(
        rest,
        lorittaDatabase
    )

    // Trackers
    private val tweetTrackerStreamsv1 = mutableListOf<net.perfectdreams.loritta.socialrelayer.twitter.trackers.v1.TweetTrackerStream>()
    private var tweetTrackerStreamv2: TweetTrackerStream? = null
    private val tweetTrackerPollingManager = TweetTrackerPollingManager(this)
    private val updateTrackersMutex = Mutex()

    private val relayToDiscordWebhook = RelayToDiscordWebhook(this)
    private val relayToLorittaTrackersWebhook = RelayToLorittaTrackersWebhook(this)

    fun start() {
        transaction(lorittaDatabase) {
            // We aren't going to create anything that is handled by Loritta
            SchemaUtils.createMissingTablesAndColumns(
                CachedDiscordWebhooks,
                CachedTwitterAccounts,
                InvalidTwitterIds
            )
        }

        thread {
            while (true) {
                Thread.sleep(Long.MAX_VALUE)
            }
        }

        GlobalScope.launch {
            while (true) {
                try {
                    updateTrackers()
                } catch (e: Exception) {
                    logger.warn(e) { "Something went wrong while updating trackers!" }
                }
                delay(60_000)
            }
        }

        relayToDiscordWebhook.start()
    }

    private suspend fun updateTrackers() {
        updateTrackersMutex.withLock {
            val trackedTwitterAccounts = transaction(lorittaDatabase) {
                val countField = TrackedTwitterAccounts.twitterAccountId.count()

                TrackedTwitterAccounts.slice(
                    TrackedTwitterAccounts.twitterAccountId,
                    countField
                ).selectAll()
                    .groupBy(TrackedTwitterAccounts.twitterAccountId)
                    .orderBy(
                        Pair(countField, SortOrder.DESC),
                        Pair(TrackedTwitterAccounts.twitterAccountId, SortOrder.ASC)
                    )
                    .toList()
            }

            logger.info { "There is ${trackedTwitterAccounts.size} accounts that needs to be tracked!" }

            var copy = trackedTwitterAccounts.toList()
                .map { it[TrackedTwitterAccounts.twitterAccountId] }
                .toMutableList()
            copy.remove(CATALYSTDRIFTER_ID)

            val oldStreamUserIds = copy.take(10_000)

            // Check if the IDs are correct
            val allTracked = tweetTrackerStreamsv1.flatMap { it.userIds }

            val doNotUpdateOldStream =
                allTracked.containsAll(oldStreamUserIds) && oldStreamUserIds.containsAll(allTracked)

            logger.debug { "Registering $oldStreamUserIds for Stream V1" }
            if (!doNotUpdateOldStream) {
                logger.info { "Tweet Tracker Stream V1 doesn't match! Recreating stream..." }
                tweetTrackerStreamsv1.forEach { it.stop() }
                tweetTrackerStreamsv1.clear()

                oldStreamUserIds.chunked(5_000).forEach {
                    val stream = net.perfectdreams.loritta.socialrelayer.twitter.trackers.v1.TweetTrackerStream(
                        this,
                        buildTwitterConfig(),
                        it
                    )
                    stream.start()
                    tweetTrackerStreamsv1.add(stream)
                }
            }

            copy = copy.drop(10_000)
                .toMutableList()

            // TODO: I'm not sure but Twitter Stream v2 ALWAYS RANDOMLY FAILS!! It never reconnects
            // And this annoys me so much :(
            // Something went wrong while trying to connect to Twitter Stream v2...
            //java.io.IOException: chunked transfer encoding, state: READING_LENGTH
            //	at java.net.http/jdk.internal.net.http.common.Utils.wrapWithExtraDetail(Utils.java:337)
            //	at java.net.http/jdk.internal.net.http.Http1Response$BodyReader.onReadError(Http1Response.java:759)
            //	at java.net.http/jdk.internal.net.http.Http1AsyncReceiver.checkForErrors(Http1AsyncReceiver.java:302)
            //	at java.net.http/jdk.internal.net.http.Http1AsyncReceiver.flush(Http1AsyncReceiver.java:268)
            //	at java.net.http/jdk.internal.net.http.common.SequentialScheduler$SynchronizedRestartableTask.run(SequentialScheduler.java:175)
            //	at java.net.http/jdk.internal.net.http.common.SequentialScheduler$CompleteRestartableTask.run(SequentialScheduler.java:147)
            //	at java.net.http/jdk.internal.net.http.common.SequentialScheduler$SchedulableTask.run(SequentialScheduler.java:198)
            //	at java.net.http/jdk.internal.net.http.HttpClientImpl$DelegatingExecutor.execute(HttpClientImpl.java:155)
            //	at java.net.http/jdk.internal.net.http.common.SequentialScheduler.runOrSchedule(SequentialScheduler.java:273)
            //	at java.net.http/jdk.internal.net.http.common.SequentialScheduler.runOrSchedule(SequentialScheduler.java:242)
            //	at java.net.http/jdk.internal.net.http.Http1AsyncReceiver.onReadError(Http1AsyncReceiver.java:511)
            //	at java.net.http/jdk.internal.net.http.Http1AsyncReceiver$Http1TubeSubscriber.onComplete(Http1AsyncReceiver.java:596)
            //	at java.net.http/jdk.internal.net.http.common.SSLTube$DelegateWrapper.onComplete(SSLTube.java:276)
            //	at java.net.http/jdk.internal.net.http.common.SSLTube$SSLSubscriberWrapper.complete(SSLTube.java:440)
            //	at java.net.http/jdk.internal.net.http.common.SSLTube$SSLSubscriberWrapper.onComplete(SSLTube.java:541)
            //	at java.net.http/jdk.internal.net.http.common.SubscriberWrapper.checkCompletion(SubscriberWrapper.java:472)
            //	at java.net.http/jdk.internal.net.http.common.SubscriberWrapper$DownstreamPusher.run1(SubscriberWrapper.java:334)
            //	at java.net.http/jdk.internal.net.http.common.SubscriberWrapper$DownstreamPusher.run(SubscriberWrapper.java:259)
            //	at java.net.http/jdk.internal.net.http.common.SequentialScheduler$SynchronizedRestartableTask.run(SequentialScheduler.java:175)
            //	at java.net.http/jdk.internal.net.http.common.SequentialScheduler$CompleteRestartableTask.run(SequentialScheduler.java:147)
            //	at java.net.http/jdk.internal.net.http.common.SequentialScheduler$SchedulableTask.run(SequentialScheduler.java:198)
            //	at java.net.http/jdk.internal.net.http.common.SequentialScheduler.runOrSchedule(SequentialScheduler.java:271)
            //	at java.net.http/jdk.internal.net.http.common.SequentialScheduler.runOrSchedule(SequentialScheduler.java:224)
            //	at java.net.http/jdk.internal.net.http.common.SubscriberWrapper.outgoing(SubscriberWrapper.java:232)
            //	at java.net.http/jdk.internal.net.http.common.SSLFlowDelegate$Reader.processData(SSLFlowDelegate.java:513)
            //	at java.net.http/jdk.internal.net.http.common.SSLFlowDelegate$Reader$ReaderDownstreamPusher.run(SSLFlowDelegate.java:268)
            //	at java.net.http/jdk.internal.net.http.common.SequentialScheduler$SynchronizedRestartableTask.run(SequentialScheduler.java:175)
            //	at java.net.http/jdk.internal.net.http.common.SequentialScheduler$CompleteRestartableTask.run(SequentialScheduler.java:147)
            //	at java.net.http/jdk.internal.net.http.common.SequentialScheduler$SchedulableTask.run(SequentialScheduler.java:198)
            //	at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1130)
            //	at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:630)
            //	at java.base/java.lang.Thread.run(Thread.java:832)
            //Caused by: java.io.EOFException: EOF reached while reading
            //	... 21 common frames omitted
            // So we disabled the V2 Tweet Stream for now
            /* val builder = TweetTrackerStream.RuleListBuilder()
            while (TweetTrackerStream.MAX_RULES >= builder.builtRules.size) {
                val oneHundred = copy.take(100)
                copy = copy.drop(100)

                val screenNames = retrieveScreenNamesFromIds(
                    oneHundred.toList()
                )

                logger.debug { "Queried: $screenNames" }
                logger.info  { "Total Rules: ${builder.builtRules.size}" }

                for ((id, cached) in screenNames) {
                    if (cached != null) {
                        builder.add(id, cached)
                    } else {
                        logger.warn { "I don't know who $id is! Skipping..." }
                    }
                }
            }

            val rules = builder.builtRules
                .take(25)

            logger.info { "Created Stream V2 rules (${rules.size}):" }
            for (rule in rules) {
                logger.info { "Rule: $rule" }
            }

            val stream = tweetTrackerStreamv2 ?: TweetTrackerStream(this@TweetRelayer)
            try {
                // According to the docs, we don't need to create the stream again when updating the rules (yay?)
                stream.updateRules(rules)

                if (tweetTrackerStreamv2 == null) {
                    logger.info { "Tweet Tracker Stream V2 does not exist! Creating stream..." }
                    GlobalScope.launch {
                        stream.start()
                    }
                    tweetTrackerStreamv2 = stream
                } else {
                    logger.info { "Tweet Tracker Stream V2 already exists! We will just not restart the stream then..." }
                }
            } catch (e: Exception) {
                logger.warn(e) { "Something went wrong while trying to update Tweet Tracker Stream V2 rules!" }
            }

            // Add overflowing users back to the list
            copy = builder.builtRules.drop(25).flatMap { it.userId } + copy */
            copy.add(CATALYSTDRIFTER_ID)
            logger.info { "Polling: ${copy.size}" }

            val pollScreenNames = retrieveScreenNamesFromIds(copy)

            val jobs = mutableListOf<Deferred<*>>()

            val firstPollingLoad = tweetTrackerPollingManager.trackerPollings.isEmpty()

            // Remove pollings that are removed/not tracked anymore
            tweetTrackerPollingManager.mutex.withLock {
                val beingPolledButDoesntNeedToBeAnymore = tweetTrackerPollingManager.trackerPollings.filter { it.key.screenName !in pollScreenNames.map { it.value } }

                for ((key, value) in beingPolledButDoesntNeedToBeAnymore) {
                    logger.info { "Removing ${key.screenName} from polling because we are not tracking them anymore" }
                    tweetTrackerPollingManager.trackerPollings.remove(key)
                }
            }

            val failCount = AtomicInteger()
            val didntEvenCheckCount = AtomicInteger()

            // We need to use semaphores because we need to get the failCount/didntEvenCheckCount
            val semaphore = Semaphore(4)

            for ((key, value) in pollScreenNames) {
                if (value != null) {
                    jobs += GlobalScope.async {
                        semaphore.withPermit {
                            logger.info { "Starting poll for ${value}... Finished jobs: ${jobs.count { it.isCompleted }}/${jobs.size}; Failed polls: $failCount; Didn't even check polls: $didntEvenCheckCount" }

                            val doCheck = 100 > failCount.get()
                            val result = tweetTrackerPollingManager.addToPolling(value, doCheck)

                            if (firstPollingLoad) {
                                if (result != null) {
                                    logger.info { "Initial poll for ${value} complete! Finished jobs: ${jobs.count { it.isCompleted }}/${jobs.size}; Failed polls: $failCount; Didn't even check polls: $didntEvenCheckCount" }
                                } else {
                                    if (doCheck) {
                                        logger.warn { "Initial poll for ${value} complete but it failed! Finished jobs: ${jobs.count { it.isCompleted }}/${jobs.size}; Failed polls: $failCount; Didn't even check polls: $didntEvenCheckCount" }
                                        failCount.incrementAndGet()
                                    } else {
                                        logger.warn { "Initial poll for ${value} was skipped! Finished jobs: ${jobs.count { it.isCompleted }}/${jobs.size}; Failed polls: $failCount; Didn't even check polls: $didntEvenCheckCount" }
                                        didntEvenCheckCount.incrementAndGet()
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val finish = measureTimeMillis {
                jobs.awaitAll()
            }

            logger.info { "Took ${finish}ms to poll everything!" }
            if (firstPollingLoad) {
                logger.info { "Starting Polling..." }
                tweetTrackerPollingManager.start()
            }
        }
    }

    /**
     * Retrieves cached Twitter account data from the database
     *
     * @param screenName the ID of the account you want information about
     * @return the cached twitter account data, if present
     */
    suspend fun retrieveTwitterAccountDataFromDatabaseById(id: Long): CachedTwitterAccount? {
        return withContext(Dispatchers.IO) {
            transaction(lorittaDatabase) {
                CachedTwitterAccount.findById(id)
            }
        }
    }

    /**
     * Retrieves cached Twitter account data from the database
     *
     * @param screenName the screen name you want information about
     * @return the cached twitter account data, if present
     */
    suspend fun retrieveTwitterAccountDataFromDatabaseByScreenName(screenName: String): CachedTwitterAccount? {
        return withContext(Dispatchers.IO) {
            transaction(lorittaDatabase) {
                CachedTwitterAccount.find {
                    CachedTwitterAccounts.screenName eq screenName
                }.firstOrNull()
            }
        }
    }

    /**
     * Retrieves cached Twitter account data from the database
     *
     * @param userIds a list of Twitter Account IDs
     * @return list of cached twitter account data
     */
    private suspend fun retrieveTwitterAccountsDataFromDatabaseById(userIds: List<Long>): List<CachedTwitterAccount> {
        return withContext(Dispatchers.IO) {
            transaction(lorittaDatabase) {
                CachedTwitterAccount.find {
                    CachedTwitterAccounts.id inList userIds
                }.toList()
            }
        }
    }

    /**
     * Retrieves Invalid Twitter IDs, only accounts in the [userIds] list will be matched
     *
     * @param userIds a list of Twitter Account IDs that will be checked
     * @return list of invalid accounts
     */
    private suspend fun retrieveInvalidIdsFromDatabaseById(userIds: List<Long>): List<InvalidTwitterId> {
        return withContext(Dispatchers.IO) {
            transaction(lorittaDatabase) {
                InvalidTwitterId.find {
                    InvalidTwitterIds.id inList userIds
                }.toList()
            }
        }
    }

    private suspend fun retrieveScreenNamesFromIds(userIds: List<Long>): Map<Long, String?> {
        val cachedScreenNames = retrieveTwitterAccountsDataFromDatabaseById(userIds)

        // Only query cached screen names again after 1 day
        val filteredCachedScreenNames = cachedScreenNames.filter { it.retrievedAt + 86_400_000 >= System.currentTimeMillis() }

        val invalidIds = retrieveInvalidIdsFromDatabaseById(userIds).map { it.accountId.value }
        val needToRetrieveScreenNames = (userIds - invalidIds)
            .toMutableList()

        // Remove all screen names that are already cached
        needToRetrieveScreenNames.removeIf { accountId ->
            filteredCachedScreenNames.any { it.accountId.value == accountId }
        }

        val associateWithCachedScreenNames = filteredCachedScreenNames.associate {
            it.accountId.value to it.screenName
        }

        if (needToRetrieveScreenNames.isEmpty()) {
            // yay, everything is cached! :3
            return associateWithCachedScreenNames
        }

        val screenNames = associateWithCachedScreenNames.toMutableMap<Long, String?>()

        // oof, not cached, we need to pull it from the API
        val chunkedIds = needToRetrieveScreenNames.chunked(100)

        for (chunked in chunkedIds) {
            val users = try {
                withContext(Dispatchers.IO) {
                    twitter4j.users()
                        .lookupUsers(*chunked.toLongArray())
                }
            } catch (e: TwitterException) {
                if (e.statusCode == 404) {
                    val retrievedAt = System.currentTimeMillis()

                    withContext(Dispatchers.IO) {
                        transaction(lorittaDatabase) {
                            // Yet another InvalidTwitterIds check here
                            val invalidTwitterIdsThatArePresentInTheDatabase = InvalidTwitterIds.select { InvalidTwitterIds.id inList chunked }.map { it[InvalidTwitterIds.id].value }
                            val invalidTwitterIdsThatArentPresentInTheDatabase = (chunked - invalidTwitterIdsThatArePresentInTheDatabase.toSet())

                            if (invalidTwitterIdsThatArentPresentInTheDatabase.isNotEmpty()) {
                                // By using shouldReturnGeneratedValues, the database won't need to synchronize on each insert
                                // this increases insert performance A LOT and, because we don't need the IDs, it is very useful to make the query be VERY fast
                                InvalidTwitterIds.batchInsert(
                                    invalidTwitterIdsThatArentPresentInTheDatabase,
                                    shouldReturnGeneratedValues = false
                                ) {
                                    this[InvalidTwitterIds.id] = it
                                    this[InvalidTwitterIds.retrievedAt] = retrievedAt
                                }
                            }
                        }
                    }

                    chunked.forEach {
                        screenNames[it] = null
                    }
                    continue
                } else throw e
            }

            val retrievedAt = System.currentTimeMillis()

            for (user in users) {
                screenNames[user.id] = user.screenName

                withContext(Dispatchers.IO) {
                    transaction(lorittaDatabase) {
                        CachedTwitterAccounts.insertOrUpdate(CachedTwitterAccounts.id) {
                            it[CachedTwitterAccounts.id] = user.id
                            it[CachedTwitterAccounts.screenName] = user.screenName
                            it[CachedTwitterAccounts.retrievedAt] = retrievedAt
                        }
                    }
                }
            }
        }

        val missingUsers = userIds - screenNames.keys

        // If it wasn't able to retrieve them, then it is a invalid ID
        val retrievedAt = System.currentTimeMillis()

        withContext(Dispatchers.IO) {
            transaction(lorittaDatabase) {
                // Yet another InvalidTwitterIds check here
                val invalidTwitterIdsThatArePresentInTheDatabase = InvalidTwitterIds.select { InvalidTwitterIds.id inList missingUsers }.map { it[InvalidTwitterIds.id].value }
                val invalidTwitterIdsThatArentPresentInTheDatabase = (missingUsers - invalidTwitterIdsThatArePresentInTheDatabase.toSet())

                if (invalidTwitterIdsThatArentPresentInTheDatabase.isNotEmpty()) {
                    // By using shouldReturnGeneratedValues, the database won't need to synchronize on each insert
                    // this increases insert performance A LOT and, because we don't need the IDs, it is very useful to make the query be VERY fast
                    InvalidTwitterIds.batchInsert(
                        invalidTwitterIdsThatArentPresentInTheDatabase,
                        shouldReturnGeneratedValues = false
                    ) {
                        this[InvalidTwitterIds.id] = it
                        this[InvalidTwitterIds.retrievedAt] = retrievedAt
                    }
                }
            }
        }

        return screenNames
    }

    fun buildTwitterConfig(): Configuration {
        val cb = ConfigurationBuilder()
        cb.setDebugEnabled(true)
            .setOAuthConsumerKey(config.twitter.oAuthConsumerKey)
            .setOAuthConsumerSecret(config.twitter.oAuthConsumerSecret)
            .setOAuthAccessToken(config.twitter.oAuthAccessToken)
            .setOAuthAccessTokenSecret(config.twitter.oAuthAccessTokenSecret)

        return cb.build()
    }

    fun receivedNewTweet(tweetInfo: TweetInfo) {
        logger.info { "Received New Tweet! $tweetInfo" }

        relayToDiscordWebhook.receivedNewTweet(tweetInfo)
        relayToLorittaTrackersWebhook.receivedNewTweet(tweetInfo)
    }
}