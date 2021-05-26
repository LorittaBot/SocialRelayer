package net.perfectdreams.loritta.socialrelayer.common.utils

import club.minnced.discord.webhook.send.WebhookEmbed
import club.minnced.discord.webhook.send.WebhookEmbedBuilder
import com.google.gson.annotations.SerializedName
import java.awt.Color
import java.net.URL

class ParallaxEmbed {
	var rgb: ParallaxColor? = null
	var color: Int? = null
	var hex: String? = null
	var title: String? = null
	var url: String?  = null
	var description: String?  = null
	var author: ParallaxEmbedAuthor?  = null
	var thumbnail: ParallaxEmbedImage?  = null
	var image: ParallaxEmbedImage? = null
	var footer: ParallaxEmbedFooter? = null
	var fields: MutableList<ParallaxEmbedField>? = null

	@JvmOverloads
	fun addBlankField(inline: Boolean = false): ParallaxEmbed {
		if (fields == null)
			fields = mutableListOf()
		fields!!.add(ParallaxEmbedField(" ", " ", inline))
		return this
	}

	@JvmOverloads
	fun addField(name: String, value: String, inline: Boolean = false): ParallaxEmbed {
		if (fields == null)
			fields = mutableListOf()
		fields!!.add(ParallaxEmbedField(name, value, inline))
		return this
	}

	// TODO: attachFile
	// TODO: attachFiles

	@JvmOverloads
	fun setAuthor(name: String, icon: String? = null, url: String? = null): ParallaxEmbed {
		author = ParallaxEmbedAuthor(name, icon, url)
		return this
	}

	// TODO: setColor
	fun setColor(color: Color): ParallaxEmbed {
		this.color = (color.rgb + 16777216)
		return this
	}

	fun setDescription(description: String): ParallaxEmbed {
		this.description = description
		return this
	}

	@JvmOverloads
	fun setFooter(text: String, icon: String? = null): ParallaxEmbed {
		this.footer = ParallaxEmbedFooter(text, icon)
		return this
	}

	fun setImage(url: String): ParallaxEmbed {
		this.image = ParallaxEmbedImage(url)
		return this
	}

	fun setThumbnail(url: String): ParallaxEmbed {
		this.thumbnail = ParallaxEmbedImage(url)
		return this
	}

	// TODO: setTimestamp

	fun setTitle(title: String): ParallaxEmbed {
		this.title = title
		return this
	}

	fun setURL(url: String): ParallaxEmbed {
		this.url = url
		return this
	}

	fun String.isValidUrl(): Boolean = try { URL(this); true } catch (e: Exception) { false }

	fun toDiscordEmbed(safe: Boolean = false): WebhookEmbed {
		val embed = WebhookEmbedBuilder()

		fun processString(text: String?, maxSize: Int): String? {
			if (safe && text != null) {
				return text.substringIfNeeded(0 until maxSize)
			}
			return text
		}

		fun processImageUrl(url: String?): String? {
			if (safe && url != null) {
				if (!url.isValidUrl())
					return "" // Constants.INVALID_IMAGE_URL
			}
			return url
		}

		fun processUrl(url: String?): String? {
			if (safe && url != null) {
				if (!url.isValidUrl())
					return null
			}
			return url
		}

		if (color != null) {
			val red = color!! shr 16 and 0xFF
			val green = color!! shr 8 and 0xFF
			val blue = color!! and 0xFF
			embed.setColor(Color(red, green, blue).rgb)
		}

		if (rgb != null) {
			val rgb = rgb!!
			embed.setColor(Color(rgb.r, rgb.b, rgb.g).rgb)
		}

		if (hex != null) {
			embed.setColor(Color.decode(hex).rgb)
		}

		if (description != null) {
			embed.setDescription(processString(description!!, 2048))
		}

		if (title != null) {
			embed.setTitle(WebhookEmbed.EmbedTitle(processString(title, 256) ?: " ", processUrl(url)))
		}

		if (author != null) {
			embed.setAuthor(WebhookEmbed.EmbedAuthor(processString(author!!.name, 256) ?: " ", processUrl(author!!.url), processImageUrl(author!!.iconUrl)))
		}

		if (footer != null) {
			embed.setFooter(WebhookEmbed.EmbedFooter(processString(footer!!.text, 256) ?: " ", processImageUrl(footer!!.iconUrl)))
		}

		if (image != null) {
			embed.setImageUrl(processImageUrl(image!!.url))
		}

		if (thumbnail != null) {
			embed.setThumbnailUrl(processImageUrl(thumbnail!!.url))
		}

		if (fields != null) {
			fields!!.forEach {
				embed.addField(WebhookEmbed.EmbedField(it.inline, it.name ?: " ", it.value ?: " "))
			}
		}

		return embed.build()
	}

	class ParallaxEmbedAuthor(
			var name: String?,
			var url: String?,
			@SerializedName("icon_url")
			var iconUrl: String?
	)

	class ParallaxEmbedImage(
			var url: String?
	)

	class ParallaxEmbedFooter(
			var text: String?,
			@SerializedName("icon_url")
			var iconUrl: String?
	)

	class ParallaxEmbedField(
			var name: String?,
			var value: String?,
			var inline: Boolean = false
	)
}