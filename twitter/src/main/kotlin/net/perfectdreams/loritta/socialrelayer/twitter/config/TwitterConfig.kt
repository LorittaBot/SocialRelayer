package net.perfectdreams.loritta.socialrelayer.twitter.config

import kotlinx.serialization.Serializable

@Serializable
data class TwitterConfig(
    val twitterV2Token: String,
    val oAuthConsumerKey: String,
    val oAuthConsumerSecret: String,
    val oAuthAccessToken: String,
    val oAuthAccessTokenSecret: String
)