package net.perfectdreams.loritta.socialrelayer.twitch.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SubscriptionListResponse(
    val total: Int,
    val data: List<SubscriptionData>,
    @SerialName("max_total_cost")
    val maxTotalCost: Int,
    @SerialName("total_cost")
    val totalCost: Int,
    val pagination: Pagination
)