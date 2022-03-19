package net.perfectdreams.loritta.socialrelayer.twitter.trackers.publish

import io.ktor.client.*
import io.ktor.client.engine.apache.*
import io.ktor.client.request.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import net.perfectdreams.loritta.socialrelayer.twitter.TweetRelayer
import org.jsoup.Jsoup
import java.time.LocalDateTime

/**
 * Tracks new tweets from [screenName] by scrapping the Twitter Publish embed.
 */
class TweetTrackerPollingTimelineEmbed(val tweetRelayer: TweetRelayer, val screenName: String) {
    companion object {
        val http = HttpClient(Apache) {
            expectSuccess = false
        }

        private val logger = KotlinLogging.logger {}
    }

    var lastReceivedTweetId = -1L
    var lastTweetReceivedAt: LocalDateTime? = null

    suspend fun check(): PollingResult {
        // logger.info { "Polling $screenName's Timeline..." }
        val aaa = withContext(Dispatchers.IO) {
            http.get<String>("https://cdn.syndication.twimg.com/timeline/profile?callback=__twttr.callbacks.tl_i0_profile_${screenName}_old&dnt=true&domain=htmledit.squarefree.com&lang=en&screen_name=$screenName&suppress_response_codes=true&t=${System.currentTimeMillis() / 1_000}&tz=GMT-0300&with_replies=false") {
                header("Referer", "https://htmledit.squarefree.com/")
            }
        }

        val json = Json.parseToJsonElement(
            aaa.substringAfter("(")
                .removeSuffix(");")
        ).jsonObject

        val statusCode = json["headers"]!!.jsonObject["status"]!!.jsonPrimitive.int

        val polledTweet = mutableListOf<PolledTweet>()

        if (statusCode == 200) {
            // println("https://cdn.syndication.twimg.com/timeline/profile?callback=__twttr.callbacks.tl_i0_profile_${screenName}_old&dnt=true&domain=htmledit.squarefree.com&lang=en&screen_name=$screenName&suppress_response_codes=true&t=${System.currentTimeMillis() / 1_000}&tz=GMT-0300&with_replies=false")
            // println(json)

            val jsoup = Jsoup.parse(
                json["body"]!!.jsonPrimitive.content
            )

            // println(jsoup.body())

            val allTweetsFromTimeline = jsoup.getElementsByClass("timeline-Tweet")
                .filterNot { it.hasClass("timeline-Tweet--isRetweet") }
                .filter { it.hasAttr("data-click-to-open-target") }

            /* println(
            allTweetsFromTimeline.first().text()
        ) */

            for (tweet in allTweetsFromTimeline) {
                val tweetUrl = tweet.attr("data-click-to-open-target")

                val tweetId = tweetUrl.split("/").last()
                    .toLong()

                // Add the tweet from the results to our list
                polledTweet.add(
                    PolledTweet(
                        tweetId,
                        LocalDateTime.parse(
                            tweet.selectFirst("time")
                                .attr("datetime")
                                .substringBefore("+")
                        )
                    )
                )
            }

            if (allTweetsFromTimeline.isNotEmpty()) {
                val firstTweet = allTweetsFromTimeline.first()

                // Store newly received tweet ID
                lastReceivedTweetId = firstTweet
                    .attr("data-click-to-open-target")
                    .split("/")
                    .last()
                    .toLong()

                // Store date too
                lastTweetReceivedAt = LocalDateTime.parse(
                    firstTweet.selectFirst("time")
                        .attr("datetime")
                        .substringBefore("+")
                )

                // Yay!
            }
            // Yay! Everything went pretty much ok!
            return SuccessfulPollingResult(
                statusCode,
                System.currentTimeMillis(),
                polledTweet
            )
        } else {
            // {"headers":{"status":403,"message":"Content unavailable."}}
            logger.warn { "Header is not success while checking $screenName's timeline! $json"}
        }

        return PollingResult(
            statusCode,
            System.currentTimeMillis()
        )
    }
}