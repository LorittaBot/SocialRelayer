package net.perfectdreams.loritta.socialrelayer.twitter

import club.minnced.discord.webhook.WebhookClientBuilder
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.util.concurrent.ConcurrentLinkedQueue

class RelayToDiscordWebhook(val tweetRelayer: TweetRelayer) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    val webhook = WebhookClientBuilder(tweetRelayer.config.tweetWebhook)
            .build()
    val pendingTwitterNotifications = ConcurrentLinkedQueue<TweetInfo>()

    fun start() {
        GlobalScope.launch {
            while (true) {
                logger.info { "Relaying ${pendingTwitterNotifications.size} new tweets to Discord Webhook..." }

                if (pendingTwitterNotifications.isNotEmpty()) {
                    val builder = StringBuilder()

                    for (tweetInfo in pendingTwitterNotifications) {
                        builder.append("**`${tweetInfo.trackerSource}`** Tweet by `${tweetInfo.screenName.replace("`", "")} (${tweetInfo.userId})`: https://twitter.com/${tweetInfo.screenName}/status/${tweetInfo.tweetId}")
                        builder.append("\n")
                    }

                    try {
                        webhook.send(builder.toString())
                                .await()
                        logger.info { "Successfully relayed tweets to Discord!" }
                    } catch (e: Exception) {
                        logger.warn(e) { "Exception while sending message via webhook" }
                    }
                }

                pendingTwitterNotifications.clear()

                delay(5_000)
            }
        }
    }

    fun receivedNewTweet(tweetInfo: TweetInfo) {
        pendingTwitterNotifications.add(tweetInfo)
    }
}