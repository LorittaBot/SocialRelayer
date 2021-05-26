package net.perfectdreams.loritta.socialrelayer.common.utils

import club.minnced.discord.webhook.send.WebhookMessageBuilder
import com.github.salomonbrys.kotson.*
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

object MessageUtils {
    fun generateMessage(message: String, sources: List<Any>?, customTokens: Map<String, String> = mutableMapOf(), safe: Boolean = true): WebhookMessageBuilder? {
        val jsonObject = try {
            JsonParser.parseString(message).obj
        } catch (ex: Exception) {
            null
        }

        val tokens = mutableMapOf<String, String?>()
        tokens.putAll(customTokens)

        val messageBuilder = WebhookMessageBuilder()
        if (jsonObject != null) {
            // alterar tokens
            handleJsonTokenReplacer(jsonObject, sources ?: listOf(), tokens)
            val jsonEmbed = jsonObject["embed"].nullObj
            if (jsonEmbed != null) {
                try {
                    val parallaxEmbed = Gson().fromJson<ParallaxEmbed>(jsonObject["embed"])
                    messageBuilder.addEmbeds(parallaxEmbed.toDiscordEmbed(safe))
                } catch (e: Exception) {
                    // Creating a empty embed can cause errors, so we just wrap it in a try .. catch block and hope
                    // for the best!
                }
            }
            messageBuilder.append(jsonObject.obj["content"].nullString ?: " ")
        } else {
            messageBuilder.append(replaceTokens(message, sources, tokens).substringIfNeeded())
        }
        if (messageBuilder.isEmpty)
            return null
        return messageBuilder
    }

    private fun handleJsonTokenReplacer(jsonObject: JsonObject, sources: List<Any>, customTokens: Map<String, String?> = mutableMapOf()) {
        for ((key, value) in jsonObject.entrySet()) {
            when {
                value.isJsonPrimitive && value.asJsonPrimitive.isString -> {
                    jsonObject[key] = replaceTokens(value.string, sources, customTokens)
                }
                value.isJsonObject -> {
                    handleJsonTokenReplacer(value.obj, sources, customTokens)
                }
                value.isJsonArray -> {
                    val array = JsonArray()
                    for (it in value.array) {
                        if (it.isJsonPrimitive && it.asJsonPrimitive.isString) {
                            array.add(replaceTokens(it.string, sources, customTokens))
                            continue
                        } else if (it.isJsonObject) {
                            handleJsonTokenReplacer(it.obj, sources, customTokens)
                        }
                        array.add(it)
                    }
                    jsonObject[key] = array
                }
            }
        }
    }

    private fun replaceTokens(text: String, sources: List<Any>?, customTokens: Map<String, String?> = mutableMapOf()): String {
        var message = text

        for ((token, value) in customTokens)
            message = message.replace("{$token}", value ?: "\uD83E\uDD37")

        return message
    }
}