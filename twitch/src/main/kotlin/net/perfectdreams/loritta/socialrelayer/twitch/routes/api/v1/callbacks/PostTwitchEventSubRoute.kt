package net.perfectdreams.loritta.socialrelayer.twitch.routes.api.v1.callbacks

import club.minnced.discord.webhook.send.AllowedMentions
import club.minnced.discord.webhook.send.WebhookMessageBuilder
import io.ktor.application.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import net.perfectdreams.loritta.socialrelayer.common.utils.Constants
import net.perfectdreams.loritta.socialrelayer.common.utils.MessageUtils
import net.perfectdreams.loritta.socialrelayer.twitch.TwitchRelayer
import net.perfectdreams.loritta.socialrelayer.twitch.data.SubscriptionData
import net.perfectdreams.loritta.socialrelayer.twitch.data.VerificationRequest
import net.perfectdreams.loritta.socialrelayer.twitch.data.events.TwitchEventRequest
import net.perfectdreams.loritta.socialrelayer.twitch.data.events.TwitchStreamOnlineEventRequest
import net.perfectdreams.loritta.socialrelayer.twitch.tables.TrackedTwitchAccounts
import net.perfectdreams.loritta.socialrelayer.twitch.tables.TwitchEventSubEvents
import net.perfectdreams.loritta.socialrelayer.twitch.utils.TwitchRequestUtils
import net.perfectdreams.sequins.ktor.BaseRoute
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.json.JSONException

class PostTwitchEventSubRoute(val twitchRelayer: TwitchRelayer) : BaseRoute("/api/v1/callbacks/twitch") {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override suspend fun onRequest(call: ApplicationCall) {
        val response = withContext(Dispatchers.IO) {
            call.receiveStream().bufferedReader(charset = Charsets.UTF_8).readText()
        }

        logger.info { "Received Request from Twitch! $response" }

        val result = TwitchRequestUtils.verifyRequest(call, response, twitchRelayer.config.webhookSecret)
        if (!result) {
            logger.warn { "Failed to verify Twitch request!" }
            call.respondText("", ContentType.Application.Json, HttpStatusCode.Unauthorized)
            return
        }

        val jsonResponse = Json.parseToJsonElement(response)
            .jsonObject

        val challenge = jsonResponse.jsonObject["challenge"]

        if (challenge != null) {
            val challengeResponse = Json.decodeFromJsonElement<VerificationRequest>(jsonResponse)

            // If the challenge field is not null, then it means Twitch wants to verify a webhook!
            logger.info { "Verifying Twitch webhook EventSub request..." }
            call.respondText(challengeResponse.challenge)
        } else {
            // If not, then it is a EventSub event!
            logger.info { "Received a EventSub Request from Twitch! Inserting to database..." }
            withContext(Dispatchers.IO) {
                transaction(twitchRelayer.lorittaDatabase) {
                    TwitchEventSubEvents.insert {
                        it[event] = jsonResponse.toString()
                    }
                }
            }

            when (val eventRequest = TwitchEventRequest.from(jsonResponse)) {
                is TwitchStreamOnlineEventRequest -> {
                    GlobalScope.launch {
                        val event = eventRequest.event
                        val broadcasterUserIdAsLong = event.broadcasterUserId

                        val trackedEntries = withContext(Dispatchers.IO) {
                            transaction(twitchRelayer.lorittaDatabase) {
                                TrackedTwitchAccounts.select {
                                    TrackedTwitchAccounts.twitchUserId eq broadcasterUserIdAsLong.toLong()
                                }.toList()
                            }
                        }

                        logger.info { "$event is being tracked by ${trackedEntries.size} different tracking entries (wow!)" }

                        launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    twitchRelayer.webhook.send(
                                        WebhookMessageBuilder()
                                            .setContent("https://www.twitch.tv/${event.broadcasterUserLogin} (${trackedEntries.size} guilds)")
                                            .setAllowedMentions(AllowedMentions.none())
                                            .build()
                                    ).await()
                                }
                            } catch (e: JSONException) {
                                // Workaround for https://github.com/MinnDevelopment/discord-webhooks/issues/34
                                // Please remove this later!
                            }
                        }

                        for (tracked in trackedEntries) {
                            val guildId = tracked[TrackedTwitchAccounts.guildId]
                            val channelId = tracked[TrackedTwitchAccounts.channelId]

                            logger.info { "Guild $guildId is tracking $event in $channelId" }

                            val message = MessageUtils.generateMessage(
                                tracked[TrackedTwitchAccounts.message],
                                listOf(),
                                mapOf(
                                    // TODO: Game
                                    // TODO: Title
                                    // "game" to (gameInfo?.name ?: "???"),
                                    // "title" to title,
                                    "link" to "https://www.twitch.tv/${event.broadcasterUserLogin}"
                                )
                            ) ?: run {
                                logger.warn { "Failed to create a WebhookMessageBuilder for message ${tracked[TrackedTwitchAccounts.message]} to relay $event of guild $guildId in channel $channelId, defaulting to the Twitch channel URL..." }
                                WebhookMessageBuilder().append("https://www.twitch.tv/${event.broadcasterUserLogin}")
                            }

                            val result = twitchRelayer.webhookManager.sendMessageViaWebhook(
                                channelId,
                                message
                                    .setUsername("${Constants.NAME} \uD83C\uDFA4️")
                                    .setAvatarUrl(Constants.AVATAR_URL)
                                    .build()
                            )

                            if (result) {
                                logger.info { "Successfully sent Twitch notification of $event to $guildId in $channelId!" }
                            } else {
                                logger.info { "Something went wrong while trying to send Twitch notification of $event to $guildId in $channelId!" }
                            }
                        }
                    }
                }
                else -> error("I don't know how to handle a ${eventRequest}!")
            }

            call.respondText("", ContentType.Application.Json, HttpStatusCode.OK) // yay!
        }
    }
}