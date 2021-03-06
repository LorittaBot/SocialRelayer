package net.perfectdreams.loritta.socialrelayer.twitch

import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import net.perfectdreams.loritta.socialrelayer.twitch.data.SubTransportCreate
import net.perfectdreams.loritta.socialrelayer.twitch.data.SubscriptionCreateRequest
import net.perfectdreams.loritta.socialrelayer.twitch.data.SubscriptionData
import net.perfectdreams.loritta.socialrelayer.twitch.tables.TrackedTwitchAccounts
import net.perfectdreams.loritta.socialrelayer.twitch.utils.TwitchAPI
import net.perfectdreams.loritta.socialrelayer.twitch.utils.TwitchRequestUtils
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.collections.List
import kotlin.collections.any
import kotlin.collections.filter
import kotlin.collections.first
import kotlin.collections.firstOrNull
import kotlin.collections.flatMap
import kotlin.collections.map
import kotlin.collections.mapOf
import kotlin.collections.mutableListOf
import kotlin.collections.mutableMapOf
import kotlin.collections.set
import kotlin.collections.toMutableList

class RegisterTwitchEventSubsTask(val twitchRelayer: TwitchRelayer) : Runnable {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    private var lastCheckedChannelIds: List<Long>? = null

    override fun run() {
        runBlocking {
            try {
                logger.info { "Checking if there is any new channels that needs to be registered..." }

                val allChannelIds = transaction(twitchRelayer.lorittaDatabase) {
                    TrackedTwitchAccounts.slice(TrackedTwitchAccounts.twitchUserId)
                        .selectAll()
                        .groupBy(TrackedTwitchAccounts.twitchUserId)
                        .toMutableList()
                }.map { it[TrackedTwitchAccounts.twitchUserId] }

                logger.info { "There are ${allChannelIds.size} tracked channels in the database!" }

                // Check if both lists are the same and, if they are, we aren't going to check all subscriptions in all accounts
                // This allows us to avoid querying Twitch's API just to check the sub list
                val lastCheckedChannelIds = lastCheckedChannelIds
                if (lastCheckedChannelIds != null && lastCheckedChannelIds.containsAll(allChannelIds) && allChannelIds.containsAll(lastCheckedChannelIds)) {
                    logger.info { "No channels were added or removed, we aren't going to check the subscriptions list..." }
                    return@runBlocking
                }

                this@RegisterTwitchEventSubsTask.lastCheckedChannelIds = allChannelIds

                // We will use multiple twitch accounts due to the stupid 10k limit (sorry Twitch xoxo please give a higher limit pls pls)
                // So we will go ahead and try registering until all of our account limit is full
                //
                // First we will do a full clean up of every Twitch EventSub Subscription, to remove unused Twitch accounts
                val allSubscriptions = mutableListOf<SubscriptionData>()
                val totalCostPerTwitchAPI = mutableMapOf<TwitchAPI, TwitchCost>()

                for (twitch in twitchRelayer.twitchAccounts) {
                    logger.info { "Loading subscriptions for $twitch (${twitch.clientId})..." }
                    val subscriptionsData = TwitchRequestUtils.loadAllSubscriptions(twitch)
                    val subscriptions = subscriptionsData.flatMap { it.data }
                    val totalCost = subscriptionsData.first().totalCost
                    val maxTotalCost = subscriptionsData.first().maxTotalCost
                    var changedTotalCost = totalCost // We are going to use this one to "remove from the budget" outdated Twitch channels

                    allSubscriptions.addAll(subscriptions)

                    // Remove all out of date twitch channels (so channels that once were in Loritta, but aren't anymore)
                    val toBeRemovedDueToNobodyTrackingSubscriptions = subscriptions.filter { it.condition["broadcaster_user_id"]!!.toLong() !in allChannelIds || it.transport.callback != twitchRelayer.config.webhookUrl }

                    for (toBeRemovedSubscription in toBeRemovedDueToNobodyTrackingSubscriptions) {
                        logger.info { "Deleting subscription $toBeRemovedSubscription from $twitch (${twitch.clientId}) because there isn't any guilds tracking them..." }
                        TwitchRequestUtils.deleteSubscription(twitch, toBeRemovedSubscription.id)
                        allSubscriptions.remove(toBeRemovedSubscription)

                        if (toBeRemovedSubscription.status == "enabled")
                            changedTotalCost-- // Only "enabled" webhooks seems to affect the cost, so let's just decrease our total cost if needed
                    }

                    // Remove all subscriptions that aren't using our callback URL
                    val toBeRemovedDueToInvalidCallbackURLSubscriptions = subscriptions.filter { it.transport.callback != twitchRelayer.config.webhookUrl }

                    for (toBeRemovedSubscription in toBeRemovedDueToInvalidCallbackURLSubscriptions) {
                        logger.info { "Deleting subscription $toBeRemovedSubscription from $twitch (${twitch.clientId}) because the callback URL doesn't match..." }
                        TwitchRequestUtils.deleteSubscription(twitch, toBeRemovedSubscription.id)
                        allSubscriptions.remove(toBeRemovedSubscription)

                        if (toBeRemovedSubscription.status == "enabled")
                            changedTotalCost-- // Only "enabled" webhooks seems to affect the cost, so let's just decrease our total cost if needed
                    }

                    // Remove all subscriptions that are with a invalid state
                    val toBeRemovedDueToInvalidStatusSubscriptions = subscriptions.filter { it.status != "enabled" }

                    for (toBeRemovedSubscription in toBeRemovedDueToInvalidStatusSubscriptions) {
                        val createdAtAsInstant = OffsetDateTime.parse (toBeRemovedSubscription.createdAt)
                        val nowMinusMinutes = Instant.now().atOffset(ZoneOffset.UTC).minusMinutes(15)

                        if (nowMinusMinutes.isAfter(createdAtAsInstant)) {
                            logger.info { "Deleting subscription $toBeRemovedSubscription from $twitch (${twitch.clientId}) because the state is \"${toBeRemovedSubscription.status}\" and not \"enabled\"..." }
                            TwitchRequestUtils.deleteSubscription(twitch, toBeRemovedSubscription.id)
                            allSubscriptions.remove(toBeRemovedSubscription)
                        } else {
                            logger.info { "Subscription $toBeRemovedSubscription from $twitch (${twitch.clientId}) is kinda sus because the state is \"${toBeRemovedSubscription.status}\" and not \"enabled\", however we will wait a lil bit more before removing it..." }
                        }
                    }

                    val totalAlreadySubscribed = subscriptions.count { it.condition["broadcaster_user_id"]!!.toLong() in allChannelIds && it.transport.callback == twitchRelayer.config.webhookUrl }
                    logger.info { "Total subscribers in $twitch (${twitch.clientId}): $totalAlreadySubscribed" }
                    logger.info { "Total Cost for $twitch (${twitch.clientId}): $changedTotalCost" }

                    totalCostPerTwitchAPI[twitch] = TwitchCost(
                        changedTotalCost,
                        maxTotalCost
                    )

                    // If the sub list is empty (so..., zero cost) we are going to not check any other sub lists, because they will probably be empty and, if they aren't, they
                    // probably won't cause issues to us anyway
                    if (totalCost == 0) {
                        logger.info { "Total Cost for $twitch (${twitch.clientId}) is 0, we aren't going to check other accounts then..." }
                        break
                    }
                }

                val channelsThatNeedsToBeRegistered =
                    allChannelIds.filter { channelId -> !allSubscriptions.any { it.condition["broadcaster_user_id"]!!.toLong() == channelId } }

                logger.info { "Creating subscriptions for $channelsThatNeedsToBeRegistered channels" }

                for ((index, channel) in channelsThatNeedsToBeRegistered.withIndex()) {
                    val bestTwitchAPIToBeUsed = totalCostPerTwitchAPI.entries.firstOrNull {
                        it.value.maxTotalCost > it.value.totalCost
                    }

                    if (bestTwitchAPIToBeUsed == null) {
                        logger.error { "All Twitch accounts are full and can't be used anymore! Not registering $channel... Channel Index: ${index + 1}/${channelsThatNeedsToBeRegistered.size}" }
                        continue
                    }

                    logger.info { "Creating subscription for $channel in ${bestTwitchAPIToBeUsed.key} (${bestTwitchAPIToBeUsed.key.clientId})... Channel Index: ${index + 1}/${channelsThatNeedsToBeRegistered.size}" }
                    // Let's register the subscription!
                    TwitchRequestUtils.createSubscription(
                        bestTwitchAPIToBeUsed.key,
                        SubscriptionCreateRequest(
                            "stream.online",
                            "1",
                            mapOf(
                                "broadcaster_user_id" to channel.toString()
                            ),
                            SubTransportCreate(
                                "webhook",
                                twitchRelayer.config.webhookUrl,
                                twitchRelayer.config.webhookSecret
                            )
                        )
                    )
                    logger.info { "Successfully created subscription for $channel in ${bestTwitchAPIToBeUsed.key} (${bestTwitchAPIToBeUsed.key.clientId})! Channel Index: ${index + 1}/${channelsThatNeedsToBeRegistered.size}" }

                    // Update with the new cost
                    totalCostPerTwitchAPI[bestTwitchAPIToBeUsed.key] = bestTwitchAPIToBeUsed.value.copy(
                        totalCost = bestTwitchAPIToBeUsed.value.totalCost + 1
                    )
                }

                logger.info { "Done! Successfully updated all Twitch subscriptions! :3" }

                for (twitchAPIPlusCost in totalCostPerTwitchAPI)
                    logger.info { "${twitchAPIPlusCost.key} (${twitchAPIPlusCost.key.clientId}) final costs: ${twitchAPIPlusCost.value.totalCost}" }
            } catch (e: Exception) {
                logger.warn(e) { "Something went wrong while trying to update Twitch subscriptions!" }
            }
        }
    }

    data class TwitchCost(
        val totalCost: Int,
        val maxTotalCost: Int
    )
}