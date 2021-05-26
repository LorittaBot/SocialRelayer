package net.perfectdreams.loritta.socialrelayer.twitter.dao

import net.perfectdreams.loritta.socialrelayer.twitter.tables.CachedTwitterAccounts
import net.perfectdreams.loritta.socialrelayer.twitter.tables.InvalidTwitterIds
import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID

class InvalidTwitterId(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<InvalidTwitterId>(InvalidTwitterIds)

    var accountId by InvalidTwitterIds.id
    var retrievedAt by InvalidTwitterIds.retrievedAt
}