package net.perfectdreams.loritta.socialrelayer.twitter.trackers.v2

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.apache.*
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
import net.perfectdreams.loritta.socialrelayer.twitter.trackers.v2.TweetTrackerStream.Companion.MAX_RULE_LENGTH
import org.apache.http.HttpEntity
import org.apache.http.client.config.CookieSpecs
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.CloseableHttpResponse
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.utils.URIBuilder
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClients
import java.io.BufferedReader
import java.io.EOFException
import java.io.IOException
import java.io.InputStreamReader
import kotlin.concurrent.thread


class TweetTrackerStream(val tweetRelayer: TweetRelayer) {
    companion object {
        private val logger = KotlinLogging.logger {}
        val http = HttpClient(Apache) {
            expectSuccess = false
        }
        val streamHttp = HttpClient(Apache) {
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
            // From https://github.com/twitterdev/Twitter-API-v2-sample-code/blob/main/Filtered-Stream/FilteredStreamDemo.java#L43
            // Because we were having issues of "connection timeout" or "unexpected chunk size" when the connection was trying to reconnect, I tried
            // copying the code from Twitter's sample code
            // So if it is broken... then it is their own fault!
            val httpClient = HttpClients.custom()
                .setDefaultRequestConfig(
                    RequestConfig.custom()
                        .setCookieSpec(CookieSpecs.STANDARD).build()
                )
                .build()

            var isActive = true

            thread {
                while (isActive) {
                    if (lastHeartbeat != Long.MIN_VALUE) {
                        val hb = System.currentTimeMillis() - lastHeartbeat
                        logger.info { "Last stream heartbeat happened ${hb}ms ago" }

                        // Every heartbeat should happen every 20s, so if a heartbeat took more than 30s to be received, then we will close the stream
                        if (hb >= 30_000) {
                            logger.info { "Stream heartbeat happened ${hb}ms ago! Closing HttpClient..." }
                            httpClient.close()
                        }
                    } else {
                        logger.info { "Checking for heartbeat failed because we haven't received a heartbeat check yet" }
                    }

                    Thread.sleep(1_000)
                }
            }

            val uriBuilder = URIBuilder("https://api.twitter.com/2/tweets/search/stream")
                .addParameter("expansions", "author_id")

            val httpGet = HttpGet(uriBuilder.build())
            httpGet.setHeader("Authorization", java.lang.String.format("Bearer %s", token))

            val response = httpClient.execute(httpGet)
            if (response.statusLine.statusCode == HttpStatusCode.TooManyRequests.value) {
                logger.warn { "It seems like we are sending too many requests to Twitter... Leaving request scope" }
            } else {
                val entity: HttpEntity? = response.entity

                if (entity != null) {
                    logger.info { "Connected to Twitter Stream! Status: ${response.statusLine.statusCode}" }
                    val reader = BufferedReader(InputStreamReader(entity.content))
                    reader.use {
                        while (true) {
                            try {
                                logger.info { "Inside the while true loop..." }
                                val line = reader.readLine()

                                if (line == null) {
                                    logger.info { "Received null line! The connection has been closed!!" }
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
                            } catch (e: IOException) {
                                logger.warn(e) { "IOException while processing stream! Leaving while true..." }
                                break
                            } catch (e: EOFException) {
                                logger.warn(e) { "EOFException while processing stream! Leaving while true..." }
                                break
                            }  catch (e: Exception) {
                                logger.warn(e) { "Something went wrong while processing stream!" }
                                continue
                            }
                        }
                    }
                    logger.info { "Left while true loop!" }
                } else {
                    logger.warn { "HttpEntity is null! Bug? Status code: ${response.statusLine.statusCode}" }
                }
            }

            isActive = false
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