package net.perfectdreams.loritta.socialrelayer.common.utils.webhooks

import club.minnced.discord.webhook.WebhookClientBuilder
import club.minnced.discord.webhook.send.WebhookMessage
import club.minnced.discord.webhook.send.WebhookMessageBuilder
import dev.kord.common.entity.DiscordWebhook
import dev.kord.common.entity.Snowflake
import dev.kord.common.entity.WebhookType
import dev.kord.rest.json.JsonErrorCode
import dev.kord.rest.request.KtorRequestException
import dev.kord.rest.service.RestClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import net.perfectdreams.loritta.socialrelayer.common.dao.CachedDiscordWebhook
import net.perfectdreams.loritta.socialrelayer.common.tables.CachedDiscordWebhooks
import net.perfectdreams.loritta.socialrelayer.common.utils.MessageUtils
import okhttp3.OkHttpClient
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import pw.forst.exposed.insertOrUpdate
import java.util.concurrent.Executors
import kotlin.time.DurationUnit
import kotlin.time.ExperimentalTime
import kotlin.time.toDuration

/**
 * Creates, manages and cache webhooks across the application.
 *
 * @param rest     Kord's REST Client
 * @param database where the webhooks are being stored
 */
class WebhookManager(private val rest: RestClient, private val database: Database) {
    companion object {
        private val logger = KotlinLogging.logger {}

        @OptIn(ExperimentalTime::class)
        private val MISSING_PERMISSIONS_COOLDOWN = 15.0.toDuration(DurationUnit.MINUTES)
    }

    private val webhookExecutor = Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors())
    private val webhookOkHttpClient = OkHttpClient()

    /**
     * Sends the [message] to the [channelId] via a webhook, the webhook may be created or pulled from the guild if it is needed
     *
     * @param channelId where the message will be sent
     * @param message   the message that will be sent
     * @return if the message was successfully sent
     */
    @OptIn(ExperimentalTime::class)
    suspend fun sendMessageViaWebhook(channelId: Long, message: WebhookMessage): Boolean {
        logger.info { "Trying to retrieve a webhook to be used in $channelId" }

        val alreadyCachedWebhookFromDatabase = withContext(Dispatchers.IO) {
            transaction(database) {
                CachedDiscordWebhook.findById(channelId)
            }
        }

        if (alreadyCachedWebhookFromDatabase != null) {
            val shouldIgnoreDueToUnknownChannel = alreadyCachedWebhookFromDatabase.state == WebhookState.UNKNOWN_CHANNEL
            val shouldIgnoreDueToMissingPermissions = alreadyCachedWebhookFromDatabase.state == WebhookState.MISSING_PERMISSION && MISSING_PERMISSIONS_COOLDOWN.toLong(DurationUnit.MILLISECONDS) >= (System.currentTimeMillis() - alreadyCachedWebhookFromDatabase.updatedAt)
            val shouldIgnore = shouldIgnoreDueToUnknownChannel || shouldIgnoreDueToMissingPermissions

            if (shouldIgnore) {
                logger.warn { "Ignoring webhook retrieval for $channelId because I wasn't able to create a webhook for it before... Webhook State: ${alreadyCachedWebhookFromDatabase.state}" }
                return false
            }
        }

        var guildWebhookFromDatabase = alreadyCachedWebhookFromDatabase

        // Okay, so we don't have any webhooks available OR the last time we tried checking it, it was a "MISSING_PERMISSION"... let's try pulling them from Discord and then register them!
        if (guildWebhookFromDatabase == null || guildWebhookFromDatabase.state == WebhookState.MISSING_PERMISSION) {
            logger.info { "First available webhook of $channelId to send a message is missing, trying to pull webhooks from the channel..." }

            try {
                // Try pulling the already created webhooks...
                val webhooks = rest.webhook.getChannelWebhooks(Snowflake(channelId))

                val firstAvailableWebhook = webhooks.firstOrNull { it.type == WebhookType.Incoming }
                var createdWebhook: DiscordWebhook? = null

                // Oh no, there isn't any webhooks available, let's create one!
                if (firstAvailableWebhook == null) {
                    logger.info { "No available webhooks in $channelId to send the message, creating a new webhook..." }

                    val kordWebhook = rest.webhook.createWebhook(
                        Snowflake(channelId),
                        "Loritta (Social Relay)"
                    ) {}

                    createdWebhook = kordWebhook
                }

                val webhook = firstAvailableWebhook ?: createdWebhook ?: error("No webhook was found!")

                // Store the newly found webhook in our database!
                guildWebhookFromDatabase = transaction(database) {
                    CachedDiscordWebhook.wrapRow(
                        CachedDiscordWebhooks.insertOrUpdate(CachedDiscordWebhooks.id) {
                            it[id] = channelId
                            it[webhookToken] = webhook.token.value!! // I doubt that the token can be null so let's just force null, heh
                            it[state] = WebhookState.SUCCESS
                            it[updatedAt] = System.currentTimeMillis()
                        }.resultedValues!!.first()
                    )
                }
            } catch (e: Exception) {
                if (e is KtorRequestException) {
                    if (e.error?.code == JsonErrorCode.UnknownChannel) {
                        logger.warn(e) { "Unknown channel: $channelId; We are WILL NEVER check this channel again!" }

                        transaction(database) {
                            CachedDiscordWebhooks.insertOrUpdate(CachedDiscordWebhooks.id) {
                                it[id] = channelId
                                // We don't replace the webhook token here... there is no pointing in replacing it.
                                it[state] = WebhookState.UNKNOWN_CHANNEL
                                it[updatedAt] = System.currentTimeMillis()
                            }.resultedValues!!.first()
                        }
                        return false
                    }
                }
                logger.warn(e) { "Failed to get webhook in channel $channelId" }

                transaction(database) {
                    CachedDiscordWebhooks.insertOrUpdate(CachedDiscordWebhooks.id) {
                        it[id] = channelId
                        // We don't replace the webhook token here... there is no pointing in replacing it.
                        it[state] = WebhookState.MISSING_PERMISSION
                        it[updatedAt] = System.currentTimeMillis()
                    }.resultedValues!!.first()
                }
                return false
            }
        }

        val webhook = guildWebhookFromDatabase

        logger.info { "Sending $message in $channelId... Using webhook $webhook" }

        WebhookClientBuilder("https://discord.com/api/webhooks/${webhook.channelId.value}/${webhook.webhookToken}")
            .setHttpClient(webhookOkHttpClient)
            .setExecutorService(webhookExecutor)
            .build()
            .send(message).await()

        logger.info { "Everything went well when sending $message in $channelId using webhook $webhook, updating last used time..." }

        withContext(Dispatchers.IO) {
            transaction(database) {
                webhook.lastSuccessfullyExecutedAt = System.currentTimeMillis()
            }
        }

        return true // yay! :smol_gessy:
    }
}