package net.perfectdreams.loritta.socialrelayer.common.config

import kotlinx.serialization.Serializable

@Serializable
class LorittaDatabaseConfig(
    val databaseName: String,
    val address: String,
    val username: String,
    val password: String
)