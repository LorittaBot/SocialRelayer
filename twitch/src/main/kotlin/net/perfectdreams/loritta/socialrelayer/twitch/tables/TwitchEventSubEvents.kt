package net.perfectdreams.loritta.socialrelayer.twitch.tables

import net.perfectdreams.loritta.socialrelayer.common.utils.exposed.jsonb
import org.jetbrains.exposed.dao.id.LongIdTable

object TwitchEventSubEvents : LongIdTable() {
    val event = jsonb("event")
}