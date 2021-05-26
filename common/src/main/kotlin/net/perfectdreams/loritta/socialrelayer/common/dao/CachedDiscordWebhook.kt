package net.perfectdreams.loritta.socialrelayer.common.dao

import net.perfectdreams.loritta.socialrelayer.common.tables.CachedDiscordWebhooks
import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID

class CachedDiscordWebhook(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<CachedDiscordWebhook>(CachedDiscordWebhooks)

    var channelId by CachedDiscordWebhooks.id
    var webhookToken by CachedDiscordWebhooks.webhookToken
    var state by CachedDiscordWebhooks.state
    var updatedAt by CachedDiscordWebhooks.updatedAt
    var lastSuccessfullyExecutedAt by CachedDiscordWebhooks.lastSuccessfullyExecutedAt
}