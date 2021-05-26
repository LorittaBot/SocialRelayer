package net.perfectdreams.loritta.socialrelayer.twitter

import club.minnced.discord.webhook.send.WebhookMessageBuilder
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import net.perfectdreams.loritta.socialrelayer.common.utils.MessageUtils
import net.perfectdreams.loritta.socialrelayer.twitter.tables.TrackedTwitterAccounts
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.time.ExperimentalTime

class RelayToLorittaTrackersWebhook(val tweetRelayer: TweetRelayer) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    val mutex = Mutex()

    @OptIn(ExperimentalTime::class)
    fun receivedNewTweet(tweetInfo: TweetInfo) {
        GlobalScope.launch {
            // Avoid ratelimiting
            mutex.withLock {
                val trackedEntries = transaction {
                    TrackedTwitterAccounts.select {
                        TrackedTwitterAccounts.twitterAccountId eq tweetInfo.userId
                    }.toList()
                }

                logger.info { "$tweetInfo user is being tracked by ${trackedEntries.size} different tracking entries (wow!)" }

                for (tracked in trackedEntries) {
                    val guildId = tracked[TrackedTwitterAccounts.guildId]
                    val channelId = tracked[TrackedTwitterAccounts.channelId]

                    val message = MessageUtils.generateMessage(
                        tracked[TrackedTwitterAccounts.message],
                        listOf(),
                        mapOf(
                            "link" to "https://twitter.com/${tweetInfo.screenName}/status/${tweetInfo.tweetId}"
                        )
                    ) ?: run {
                        logger.warn { "Failed to create a WebhookMessageBuilder for message ${tracked[TrackedTwitterAccounts.message]} to relay $tweetInfo of guild $guildId in channel $channelId, defaulting to the tweet URL..." }
                        WebhookMessageBuilder().append("https://twitter.com/${tweetInfo.screenName}/status/${tweetInfo.tweetId}")
                    }
                        .setUsername("Loritta \uD83D\uDC26")
                        .setAvatarUrl("https://cdn.discordapp.com/attachments/617182204212150316/797476909923041365/original.png")

                    async {
                        val result = tweetRelayer.webhookManager.sendMessageViaWebhook(
                            channelId,
                            message.build()
                        )

                        if (result) {
                            logger.info { "Successfully sent tweet message of $tweetInfo to $guildId in $channelId!" }
                        } else {
                            logger.info { "Something went wrong while trying to send tweet message of $tweetInfo to $guildId in $channelId!" }
                        }
                    }.invokeOnCompletion {
                        if (it != null) {
                            logger.warn(it) { "Something went wrong while sending tweet message of $tweetInfo to $guildId in $channelId!" }
                        }
                    }
                }
            }
        }
    }
}