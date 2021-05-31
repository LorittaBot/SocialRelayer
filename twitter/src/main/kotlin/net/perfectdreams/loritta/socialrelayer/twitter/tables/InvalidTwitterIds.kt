package net.perfectdreams.loritta.socialrelayer.twitter.tables

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column

object InvalidTwitterIds : IdTable<Long>("")  {
    override val id: Column<EntityID<Long>> = long("twitter_id").entityId()
    override val primaryKey by lazy { super.primaryKey ?: PrimaryKey(id) }

    val retrievedAt = long("retrieved_at")
}