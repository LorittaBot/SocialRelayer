package net.perfectdreams.loritta.socialrelayer.common.tables

import net.perfectdreams.loritta.socialrelayer.common.utils.webhooks.WebhookState
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column

object CachedDiscordWebhooks : IdTable<Long>("")  {
    override val id: Column<EntityID<Long>> = long("channel").entityId()
    override val primaryKey by lazy { super.primaryKey ?: PrimaryKey(id) }

    val webhookId = long("webhook_id").nullable()
    val webhookToken = text("webhook_token").nullable()
    val state = enumeration("state", WebhookState::class)
    val updatedAt = long("updated_at")
    val lastSuccessfullyExecutedAt = long("last_successfully_executed_at").nullable()
}