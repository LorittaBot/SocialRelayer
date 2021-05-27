package net.perfectdreams.loritta.socialrelayer.twitch.utils

import io.ktor.application.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.content.*
import io.ktor.http.*
import io.ktor.request.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.perfectdreams.loritta.socialrelayer.twitch.data.SubscriptionCreateRequest
import net.perfectdreams.loritta.socialrelayer.twitch.data.SubscriptionListResponse
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object TwitchRequestUtils {
    private const val MESSAGE_SIGNATURE_HEADER = "Twitch-Eventsub-Message-Signature"
    private const val MESSAGE_ID_HEADER = "Twitch-Eventsub-Message-Id"
    private const val MESSAGE_TIMESTAMP_HEADER = "Twitch-Eventsub-Message-Timestamp"

    fun verifyRequest(
        call: ApplicationCall,
        response: String,
        webhookSecret: String
    ) = verifyRequest(
        call.request.header(MESSAGE_SIGNATURE_HEADER) ?: error("Missing Signature Header!"),
        call.request.header(MESSAGE_ID_HEADER) ?: error("Missing Message ID Header!"),
        call.request.header(MESSAGE_TIMESTAMP_HEADER) ?: error("Missing Message Timestamp Header!"),
        response,
        webhookSecret
    )

    fun verifyRequest(
        messageSignature: String,
        messageId: String,
        messageTimestamp: String,
        response: String,
        webhookSecret: String
    ): Boolean {
        // Signature Verification: https://dev.twitch.tv/docs/eventsub#verify-a-signature
        val hmacMessage = messageId + messageTimestamp + response
        val signingKey = SecretKeySpec(webhookSecret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(signingKey)
        val doneFinal = mac.doFinal(hmacMessage.toByteArray(Charsets.UTF_8))
        val expectedSignatureHeader = "sha256=" + doneFinal.bytesToHex()

        return messageSignature == expectedSignatureHeader
    }

    /**
     * Converts a ByteArray to a hexadecimal string
     *
     * @return the byte array in hexadecimal format
     */
    fun ByteArray.bytesToHex(): String {
        val hexString = StringBuffer()
        for (i in this.indices) {
            val hex = Integer.toHexString(0xff and this[i].toInt())
            if (hex.length == 1) {
                hexString.append('0')
            }
            hexString.append(hex)
        }
        return hexString.toString()
    }

    suspend fun loadAllSubscriptions(twitch: TwitchAPI): List<SubscriptionListResponse> {
        val subscriptions = mutableListOf<SubscriptionListResponse>()

        var cursor: String? = null
        var first = true
        while (first || cursor != null) {
            val subscriptionListData = loadSubscriptions(twitch, cursor)
            cursor = subscriptionListData.pagination.cursor
            first = false
            subscriptions.add(subscriptionListData)
        }

        return subscriptions
    }

    suspend fun loadSubscriptions(twitch: TwitchAPI, cursor: String? = null): SubscriptionListResponse {
        return twitch.makeTwitchApiRequest("https://api.twitch.tv/helix/eventsub/subscriptions") {
            method = HttpMethod.Get
            if (cursor != null)
                parameter("after", cursor)
        }
            .readText()
            .let { Json.decodeFromString(it) }
    }

    suspend fun deleteSubscription(twitch: TwitchAPI, subscriptionId: String) {
        twitch.makeTwitchApiRequest("https://api.twitch.tv/helix/eventsub/subscriptions?id=$subscriptionId") {
            method = HttpMethod.Delete
        }
    }

    suspend fun createSubscription(twitch: TwitchAPI, subscriptionRequest: SubscriptionCreateRequest) {
        twitch.makeTwitchApiRequest("https://api.twitch.tv/helix/eventsub/subscriptions") {
            method = HttpMethod.Post

            body = TextContent(
                Json.encodeToString(subscriptionRequest), ContentType.Application.Json)
        }
    }
}