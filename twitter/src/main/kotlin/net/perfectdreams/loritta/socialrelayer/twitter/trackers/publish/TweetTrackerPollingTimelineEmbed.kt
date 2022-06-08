package net.perfectdreams.loritta.socialrelayer.twitter.trackers.publish

import io.ktor.client.*
import io.ktor.client.engine.apache.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
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
        logger.info { "Polling $screenName's Timeline..." }
        // Sadly we can't rely on the "Last-Modified-Date" header to avoid unnecessary parsing of the body
        //
        // We (ab)use the "min_position" parameter, to only pull new tweets (if we have already pulled another tweet before)
        val pollingBody = withContext(Dispatchers.IO) {
            http.get("https://cdn.syndication.twimg.com/timeline/profile?callback=__twttr.callbacks.tl_i0_profile_${screenName}_old&dnt=true&domain=htmledit.squarefree.com&lang=en&tweet_limit=20&screen_name=$screenName&suppress_response_codes=true&t=${System.currentTimeMillis() / 1_000}&tz=GMT-0300&with_replies=false") {
                if (lastReceivedTweetId != -1L)
                    parameter("min_position", lastReceivedTweetId)

                header("Referer", "https://htmledit.squarefree.com/")
            }.bodyAsText()
        }

        val json = Json.parseToJsonElement(
            pollingBody.substringAfter("(")
                .removeSuffix(");")
        ).jsonObject

        val statusCode = json["headers"]!!.jsonObject["status"]!!.jsonPrimitive.int

        val polledTweets = mutableListOf<PolledTweet>()

        if (statusCode == 200) {
            val htmlBody = json["body"]!!.jsonPrimitive.content
            if (htmlBody == "\n") {
                logger.info { "No new tweets were found when polling $screenName's timeline, so we will return a noop result..." }
                return NoopPollingResult(statusCode, System.currentTimeMillis())
            }

            // println("https://cdn.syndication.twimg.com/timeline/profile?callback=__twttr.callbacks.tl_i0_profile_${screenName}_old&dnt=true&domain=htmledit.squarefree.com&lang=en&screen_name=$screenName&suppress_response_codes=true&t=${System.currentTimeMillis() / 1_000}&tz=GMT-0300&with_replies=false")
            // println(json)

            val jsoup = Jsoup.parse(htmlBody)

            // println(jsoup.body())

            val allTweetsFromTimeline = jsoup.getElementsByClass("timeline-Tweet")
                .filter { it.hasAttr("data-click-to-open-target") }

            /* println(
            allTweetsFromTimeline.first().text()
        ) */

            for (tweet in allTweetsFromTimeline) {
                val tweetUrl = tweet.attr("data-click-to-open-target")

                val tweetId = tweetUrl.split("/").last()
                    .toLong()

                if (tweet.hasClass("timeline-Tweet--isRetweet")) {
                    // Used to track user activity
                    polledTweets.add(
                        PolledUserRetweet(
                            tweetId,
                            LocalDateTime.parse(
                                tweet.selectFirst("time")
                                    .attr("datetime")
                                    .substringBefore("+")
                            )
                        )
                    )
                } else {
                    // Add the tweet from the results to our list
                    polledTweets.add(
                        PolledUserTweet(
                            tweetId,
                            LocalDateTime.parse(
                                tweet.selectFirst("time")
                                    .attr("datetime")
                                    .substringBefore("+")
                            )
                        )
                    )
                }
            }

            val allUserTweetsFromTimeline = polledTweets.filterIsInstance<PolledUserTweet>()

            if (allUserTweetsFromTimeline.isNotEmpty()) {
                val firstTweet = allUserTweetsFromTimeline.first()

                // Store newly received tweet ID
                lastReceivedTweetId = firstTweet.tweetId

                // Store date too
                lastTweetReceivedAt = firstTweet.sentAt

                // Yay!
            }

            logger.info { "Successfully polled $screenName's timeline! $polledTweets" }

            // Yay! Everything went pretty much ok!
            return SuccessfulPollingResult(
                statusCode,
                System.currentTimeMillis(),
                polledTweets
            )
        } else {
            // {"headers":{"status":403,"message":"Content unavailable."}}
            logger.warn { "Header is not success while checking $screenName's timeline! $json"}
        }

        return FailPollingResult(
            statusCode,
            System.currentTimeMillis()
        )
    }
}