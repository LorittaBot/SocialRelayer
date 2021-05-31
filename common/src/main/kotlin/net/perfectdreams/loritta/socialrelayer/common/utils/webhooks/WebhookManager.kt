package net.perfectdreams.loritta.socialrelayer.common.utils.webhooks

import club.minnced.discord.webhook.WebhookClientBuilder
import club.minnced.discord.webhook.exception.HttpException
import club.minnced.discord.webhook.send.WebhookMessage
import com.github.benmanes.caffeine.cache.Caffeine
import dev.kord.common.entity.DiscordWebhook
import dev.kord.common.entity.Snowflake
import dev.kord.common.entity.WebhookType
import dev.kord.rest.json.JsonErrorCode
import dev.kord.rest.request.KtorRequestException
import dev.kord.rest.service.RestClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import net.perfectdreams.loritta.common.exposed.dao.CachedDiscordWebhook
import net.perfectdreams.loritta.common.exposed.tables.CachedDiscordWebhooks
import net.perfectdreams.loritta.common.utils.webhooks.WebhookState
import okhttp3.OkHttpClient
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.json.JSONException
import pw.forst.exposed.insertOrUpdate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

        @OptIn(ExperimentalTime::class)
        private val UNKNOWN_WEBHOOK_PHASE_2_COOLDOWN = 15.0.toDuration(DurationUnit.MINUTES)
    }

    private val webhookExecutor = Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors())
    private val webhookOkHttpClient = OkHttpClient()

    // This is used to avoid spamming Discord with the same webhook creation request, so we synchronize access to the method
    private val webhookRetrievalMutex = Caffeine.newBuilder().expireAfterAccess(1, TimeUnit.MINUTES).build<Long, Mutex>()
        .asMap()

    /**
     * Sends the [message] to the [channelId] via a webhook, the webhook may be created or pulled from the guild if it is needed
     *
     * @param channelId where the message will be sent
     * @param message   the message that will be sent
     * @return if the message was successfully sent
     */
    @OptIn(ExperimentalTime::class)
    suspend fun sendMessageViaWebhook(channelId: Long, message: WebhookMessage): Boolean {
        val mutex = webhookRetrievalMutex.getOrPut(channelId) { Mutex() }
        logger.info { "Retrieving webhook to be used in $channelId, is mutex locked? ${mutex.isLocked}" }
        return mutex.withLock {
            _sendMessageViaWebhook(channelId, message)
        }
    }

    /**
     * Sends the [message] to the [channelId] via a webhook, the webhook may be created or pulled from the guild if it is needed
     *
     * @param channelId where the message will be sent
     * @param message   the message that will be sent
     * @return if the message was successfully sent
     */
    @OptIn(ExperimentalTime::class)
    // This is doesn't wrap in a mutex, that's why it is private
    // The reason it starts with a underscore is because it is a private method and I can't find another good name for it
    // The reason this is a separate method is to avoid deadlocking due to accessing an already locked mutex when trying to retrieve the webhook
    private suspend fun _sendMessageViaWebhook(channelId: Long, message: WebhookMessage): Boolean {
        logger.info { "Trying to retrieve a webhook to be used in $channelId" }

        val alreadyCachedWebhookFromDatabase = withContext(Dispatchers.IO) {
            transaction(database) {
                CachedDiscordWebhook.findById(channelId)
            }
        }

        if (alreadyCachedWebhookFromDatabase != null) {
            val shouldIgnoreDueToUnknownChannel = alreadyCachedWebhookFromDatabase.state == WebhookState.UNKNOWN_CHANNEL
            val shouldIgnoreDueToMissingPermissions = alreadyCachedWebhookFromDatabase.state == WebhookState.MISSING_PERMISSION && MISSING_PERMISSIONS_COOLDOWN.toLong(DurationUnit.MILLISECONDS) >= (System.currentTimeMillis() - alreadyCachedWebhookFromDatabase.updatedAt)
            val shouldIgnoreDueToUnknownWebhookPhaseTwo = alreadyCachedWebhookFromDatabase.state == WebhookState.UNKNOWN_WEBHOOK_PHASE_2 && UNKNOWN_WEBHOOK_PHASE_2_COOLDOWN.toLong(DurationUnit.MILLISECONDS) >= (System.currentTimeMillis() - alreadyCachedWebhookFromDatabase.updatedAt)
            val shouldIgnore = shouldIgnoreDueToUnknownChannel || shouldIgnoreDueToMissingPermissions || shouldIgnoreDueToUnknownWebhookPhaseTwo

            if (shouldIgnore) {
                logger.warn { "Ignoring webhook retrieval for $channelId because I wasn't able to create a webhook for it before... Webhook State: ${alreadyCachedWebhookFromDatabase.state}" }
                return false
            }
        }

        var guildWebhookFromDatabase = alreadyCachedWebhookFromDatabase

        // Okay, so we don't have any webhooks available OR the last time we tried checking it, it wasn't "SUCCESS"... let's try pulling them from Discord and then register them!
        if (guildWebhookFromDatabase == null || guildWebhookFromDatabase.state != WebhookState.SUCCESS) {
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

                val webhook = createdWebhook ?: firstAvailableWebhook ?: error("No webhook was found!")

                logger.info { "Successfully found webhook in $channelId!" }

                // Store the newly found webhook in our database!
                guildWebhookFromDatabase = withContext(Dispatchers.IO) {
                    transaction(database) {
                        CachedDiscordWebhook.wrapRow(
                            CachedDiscordWebhooks.insertOrUpdate(CachedDiscordWebhooks.id) {
                                it[id] = channelId
                                it[webhookId] = webhook.id.value
                                it[webhookToken] = webhook.token.value!! // I doubt that the token can be null so let's just force null, heh
                                it[state] = WebhookState.SUCCESS
                                it[updatedAt] = System.currentTimeMillis()
                            }.resultedValues!!.first()
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is KtorRequestException) {
                    if (e.error?.code == JsonErrorCode.UnknownChannel) {
                        logger.warn(e) { "Unknown channel: $channelId; We are WILL NEVER check this channel again!" }

                        withContext(Dispatchers.IO) {
                            transaction(database) {
                                CachedDiscordWebhooks.insertOrUpdate(CachedDiscordWebhooks.id) {
                                    it[id] = channelId
                                    // We don't replace the webhook token here... there is no pointing in replacing it.
                                    it[state] = WebhookState.UNKNOWN_CHANNEL
                                    it[updatedAt] = System.currentTimeMillis()
                                }.resultedValues!!.first()
                            }
                        }
                        return false
                    }
                }
                logger.warn(e) { "Failed to get webhook in channel $channelId" }

                withContext(Dispatchers.IO) {
                    transaction(database) {
                        CachedDiscordWebhooks.insertOrUpdate(CachedDiscordWebhooks.id) {
                            it[id] = channelId
                            // We don't replace the webhook token here... there is no pointing in replacing it.
                            it[state] = WebhookState.MISSING_PERMISSION
                            it[updatedAt] = System.currentTimeMillis()
                        }.resultedValues!!.first()
                    }
                }
                return false
            }
        }

        val webhook = guildWebhookFromDatabase

        logger.info { "Sending $message in $channelId... Using webhook $webhook" }

        try {
            withContext(Dispatchers.IO) {
                WebhookClientBuilder("https://discord.com/api/webhooks/${webhook.webhookId}/${webhook.webhookToken}")
                    .setHttpClient(webhookOkHttpClient)
                    .setExecutorService(webhookExecutor)
                    .setWait(true) // We want to wait to check if the webhook still exists!
                    .build()
                    .send(message)
                    .await()
            }
        } catch (e: JSONException) {
            // Workaround for https://github.com/MinnDevelopment/discord-webhooks/issues/34
            // Please remove this later!
        } catch (e: HttpException) {
            val statusCode = e.code

            return if (statusCode == 404) {
                // So, this actually depends on what was the last phase
                //
                // Some DUMB people love using bots that automatically delete webhooks to avoid "users abusing them"... well why not manage your permissions correctly then?
                // So we have two phases: Phase 1 and Phase 2
                // Phase 1 means that the webhook should be retrieved again without any issues
                // Phase 2 means that SOMEONE is deleting the bot so it should just be ignored for a few minutes
                logger.warn(e) { "Webhook $webhook in $channelId does not exist! Current state is ${webhook.state}..." }
                withContext(Dispatchers.IO) {
                    transaction(database) {
                        webhook.state = if (webhook.state == WebhookState.UNKNOWN_WEBHOOK_PHASE_1) WebhookState.UNKNOWN_WEBHOOK_PHASE_2 else WebhookState.UNKNOWN_WEBHOOK_PHASE_1
                        webhook.updatedAt = System.currentTimeMillis()
                    }
                }
                logger.warn(e) { "Webhook $webhook in $channelId does not exist and its state was updated to ${webhook.state}!" }

                _sendMessageViaWebhook(channelId, message)
            } else {
                logger.warn(e) { "Something went wrong while sending the webhook message $message in $channelId using webhook $webhook!" }
                false
            }
        }

        logger.info { "Everything went well when sending $message in $channelId using webhook $webhook, updating last used time..." }

        withContext(Dispatchers.IO) {
            transaction(database) {
                webhook.lastSuccessfullyExecutedAt = System.currentTimeMillis()
            }
        }

        return true // yay! :smol_gessy:
    }
}