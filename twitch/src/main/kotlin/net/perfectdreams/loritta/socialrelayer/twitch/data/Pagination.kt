package net.perfectdreams.loritta.socialrelayer.twitch.data

import kotlinx.serialization.Serializable

@Serializable
data class Pagination(
    val cursor: String? = null
)