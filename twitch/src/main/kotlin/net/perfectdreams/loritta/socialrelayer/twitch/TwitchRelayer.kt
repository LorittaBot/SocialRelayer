package net.perfectdreams.loritta.socialrelayer.twitch

import club.minnced.discord.webhook.WebhookClientBuilder
import dev.kord.rest.service.RestClient
import io.ktor.application.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import net.perfectdreams.loritta.socialrelayer.common.utils.DatabaseUtils
import net.perfectdreams.loritta.socialrelayer.common.utils.webhooks.WebhookManager
import net.perfectdreams.loritta.socialrelayer.twitch.config.SocialRelayerTwitchConfig
import net.perfectdreams.loritta.socialrelayer.twitch.routes.api.v1.callbacks.PostTwitchEventSubRoute
import net.perfectdreams.loritta.socialrelayer.twitch.tables.TwitchEventSubEvents
import net.perfectdreams.loritta.socialrelayer.twitch.utils.TwitchAPI
import net.perfectdreams.sequins.ktor.BaseRoute
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TwitchRelayer(val config: SocialRelayerTwitchConfig) {
    val lorittaDatabase = DatabaseUtils.connectToDatabase(config.database)
    private val rest = RestClient(config.discord.token)

    val webhookManager = WebhookManager(
        rest,
        lorittaDatabase
    )

    private val routes = listOf<BaseRoute>(
        PostTwitchEventSubRoute(this)
    )

    val twitchAccounts = config.twitch.map {
        TwitchAPI(it.clientId, it.clientSecret)
    }

    val webhook = WebhookClientBuilder(config.discordTwitchWebhook)
        .build()

    fun start() {
        transaction(lorittaDatabase) {
            // We aren't going to create anything that is handled by Loritta
            SchemaUtils.createMissingTablesAndColumns(
                TwitchEventSubEvents
            )
        }

        Executors.newScheduledThreadPool(1).scheduleWithFixedDelay(RegisterTwitchEventSubsTask(this), 0L, 1L, TimeUnit.MINUTES)

        embeddedServer(Netty, port = 8000) {
            routing {
                get ("/") {
                    call.respondText("Hello, world!")
                }

                for (route in routes) {
                    route.register(this)
                }
            }
        }.start(wait = true)
    }
}