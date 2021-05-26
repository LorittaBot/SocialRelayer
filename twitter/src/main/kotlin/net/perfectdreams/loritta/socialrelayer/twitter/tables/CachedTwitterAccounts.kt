package net.perfectdreams.loritta.socialrelayer.twitter.tables

import net.perfectdreams.loritta.socialrelayer.common.tables.CachedDiscordWebhooks
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column

object CachedTwitterAccounts : IdTable<Long>("")  {
    override val id: Column<EntityID<Long>> = long("twitter_id").entityId()
    override val primaryKey by lazy { super.primaryKey ?: PrimaryKey(id) }

    val screenName = text("screen_name").index()
    val retrievedAt = long("retrieved_at")
}