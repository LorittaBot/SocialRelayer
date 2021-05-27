package net.perfectdreams.loritta.socialrelayer.twitch.data.events

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import net.perfectdreams.loritta.socialrelayer.twitch.data.SubscriptionData

sealed class TwitchEventRequest<T>(
    val subscription: SubscriptionData,
    val event: T
) {
    companion object {
        fun from(json: JsonObject): TwitchEventRequest<*> {
            val subData = Json.decodeFromJsonElement<SubscriptionData>(json["subscription"]!!)
            val event = json["event"]!!
            val subDataType = subData.type

            when (subDataType) {
                "stream.online" -> {
                    return TwitchStreamOnlineEventRequest(
                        subData,
                        Json.decodeFromJsonElement(event)
                    )
                }
                else -> error("I don't know how to handle a $subDataType!")
            }
        }
    }
}

class TwitchStreamOnlineEventRequest(
    subscription: SubscriptionData,
    event: StreamOnlineEvent
) : TwitchEventRequest<StreamOnlineEvent>(subscription, event)