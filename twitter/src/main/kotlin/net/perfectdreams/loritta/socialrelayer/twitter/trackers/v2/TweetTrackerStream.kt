package net.perfectdreams.loritta.socialrelayer.twitter.trackers.v2

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import mu.KotlinLogging
import net.perfectdreams.loritta.socialrelayer.twitter.TweetInfo
import net.perfectdreams.loritta.socialrelayer.twitter.TweetRelayer
import net.perfectdreams.loritta.socialrelayer.twitter.trackers.TrackerSource

class TweetTrackerStream(val tweetRelayer: TweetRelayer) {
    companion object {
        private val logger = KotlinLogging.logger {}
        val http = HttpClient {
            expectSuccess = false
        }

        const val MAX_RULE_LENGTH = 512
        const val MAX_RULES = 25
    }

    private val token = tweetRelayer.config.twitter.twitterV2Token
    private var lastHeartbeat = Long.MIN_VALUE

    suspend fun updateRules(rules: List<CreatedRule>) {
        logger.info { "Getting Tracked Twitter Stream V2 Rules..." }
        val response = http.get<HttpResponse>("https://api.twitter.com/2/tweets/search/stream/rules") {
            header("Authorization", "Bearer $token")
        }

        val rulePayload = response.readText(Charsets.UTF_8)

        val currentTrackedRules = Json.decodeFromJsonElement(ListSerializer(StreamRule.serializer()), Json.parseToJsonElement(rulePayload).jsonObject.get("data") ?: buildJsonArray {})
            .sortedBy { it.tag }

        logger.info { "Current Tracked Twitter Stream V2 Rules: $currentTrackedRules" }

        // Now we need to build the new rules, if any of them doesn't match, we are going to replace them
        var needsUpdate = currentTrackedRules.size != rules.size

        for ((index, trackedRule) in currentTrackedRules.withIndex()) {
            val storedRule = rules.getOrNull(index)

            if (storedRule == null || storedRule.value != trackedRule.value) {
                logger.info { "Rule $storedRule doesn't match with $trackedRule!" }
                needsUpdate = true
                break
            }
        }

        if (needsUpdate) {
            logger.info { "Deleting existing stream rules..." }
            // Remove all existing rules
            val deletionResponse = http.post<String>("https://api.twitter.com/2/tweets/search/stream/rules") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)

                body = buildJsonObject {
                    putJsonObject("delete") {
                        putJsonArray("ids") {
                            for (rule in currentTrackedRules) {
                                add(rule.id)
                            }
                        }
                    }
                }.toString()
            }

            // And then create them again!
            logger.info { "Creating new stream rules..." }
            val creationResponse = http.post<String>("https://api.twitter.com/2/tweets/search/stream/rules") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)

                body = buildJsonObject {
                    putJsonArray("add") {
                        for ((index, rule) in rules.withIndex()) {
                            addJsonObject {
                                put("value", rule.value)
                                put("tag", "Loritta Stream Rule #${index + 1}")
                            }
                        }
                    }
                }.toString()
            }

            logger.info  { "Rules were successfully updated!" }
            logger.debug { "Creation Response: $creationResponse" }
        } else {
            logger.info  { "Rules doesn't need to be updated, skipping..." }
        }
    }

    suspend fun start() {
        lastHeartbeat = Long.MIN_VALUE

        // Create the stream
        logger.info { "Starting the Twitter Stream!" }
        try {
            http.get<HttpStatement>("https://api.twitter.com/2/tweets/search/stream?expansions=author_id") {
                header("Authorization", "Bearer $token")
            }.execute {
                logger.info { "Connected to Twitter Stream!" }

                // Response is not downloaded here.
                val channel = it.receive<ByteReadChannel>()

                while (!channel.isClosedForRead) {
                    try {
                        logger.info { "Inside the while true loop..." }

                        val line = channel.readUTF8Line()

                        if (line == null) {
                            // If null then it means that the connection is closed
                            logger.info { "Received null line! The connection has been been closed!!" }
                            break
                        }

                        if (line.replace("\n", "").replace("\r", "").isEmpty()) {
                            // heart beat
                            if (lastHeartbeat == Long.MIN_VALUE) {
                                logger.info { "Received Heartbeat" }
                            } else {
                                logger.info { "Received Heartbeat - Last heartbeat was received ${System.currentTimeMillis() - lastHeartbeat}ms ago" }
                            }
                            lastHeartbeat = System.currentTimeMillis()
                            continue
                        }

                        logger.info { "Received data from Stream! $line" }

                        // {"data":{"id":"1346101396458844162","text":"RT @geert_talsma: @fionaantonella3 @t_leung Double Cat https://t.co/nSSlWNSdWl"},"matching_rules":[{"id":1346100829099581440,"tag":"cats with images"}]}
                        val data = Json.parseToJsonElement(line).jsonObject["data"]!!.jsonObject
                        val authorId = data["author_id"]!!.jsonPrimitive.content.toLong()
                        val tweetId = data["id"]!!.jsonPrimitive.content.toLong()

                        tweetRelayer.receivedNewTweet(
                            TweetInfo(
                                TrackerSource.v2_STREAM,
                                authorId,
                                tweetRelayer.retrieveTwitterAccountDataFromDatabaseById(authorId)?.screenName ?: "x",
                                tweetId
                            )
                        )
                    } catch (e: Exception) {
                        logger.warn(e) { "Something went wrong while processing stream!" }
                        break
                    }
                }

                logger.info { "Left while true loop! Is channel closed for read? ${channel.isClosedForRead}" }
            }
        } catch (e: Exception) {
            logger.info(e) { "Something went wrong while trying to connect to Twitter Stream v2..." }
        }

        logger.info { "Stream Finished... Waiting 30s until starting stream again..." }

        delay(30_000)

        start()
    }

    @Serializable
    data class StreamRule(
        val id: String,
        val value: String,
        val tag: String
    )

    data class CreatedRule(
        val value: String,
        val screenNames: List<String>,
        val userId: List<Long>
    )

    class RuleListBuilder {
        val builtRules = mutableListOf<CreatedRule>()
        private val builder = StringBuilder()
        private val matchedScreenNames = mutableListOf<String>()
        private val matchedUserIds = mutableListOf<Long>()
        var first = true

        init {
            startNewRule()
        }

        fun startNewRule() {
            builder.clear()
            builder.append("-is:retweet -is:reply ")
            matchedScreenNames.clear()
            matchedUserIds.clear()
            first = true
        }

        fun add(userId: Long, screenName: String) {
            if (builder.length + " OR ".length + "from:$screenName".length > MAX_RULE_LENGTH) {
                builtRules.add(
                    CreatedRule(
                        builder.toString(),
                        matchedScreenNames.toList(),
                        matchedUserIds.toList()
                    )
                )

                startNewRule()
                first = true
            }

            if (!first)
                builder.append(" OR ")

            matchedScreenNames.add(screenName)
            matchedUserIds.add(userId)
            builder.append("from:$screenName")
            first = false
        }
    }
}