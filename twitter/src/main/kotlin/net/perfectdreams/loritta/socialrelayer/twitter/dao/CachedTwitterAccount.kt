package net.perfectdreams.loritta.socialrelayer.twitter.dao

import net.perfectdreams.loritta.socialrelayer.twitter.tables.CachedTwitterAccounts
import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID

class CachedTwitterAccount(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<CachedTwitterAccount>(CachedTwitterAccounts)

    var accountId by CachedTwitterAccounts.id
    var screenName by CachedTwitterAccounts.screenName
    var retrievedAt by CachedTwitterAccounts.retrievedAt
}