package net.perfectdreams.loritta.socialrelayer.twitch.utils

import com.github.salomonbrys.kotson.long
import com.github.salomonbrys.kotson.obj
import com.github.salomonbrys.kotson.string
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.client.*
import io.ktor.client.engine.apache.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging

class TwitchAPI(val clientId: String,
                val clientSecret: String,
                var accessToken: String? = null,
    // https://discuss.dev.twitch.tv/t/id-token-missing-when-using-id-twitch-tv-oauth2-token-with-grant-type-refresh-token/18263/3
    // var refreshToken: String? = null,
                var expiresIn: Long? = null,
                var generatedAt: Long? = null
) {
    companion object {
        private const val PREFIX = "https://id.twitch.tv"
        private const val TOKEN_BASE_URL = "$PREFIX/oauth2/token"
        private const val USER_AGENT = "SocialRelayer-Loritta-Morenitta-Twitch-Auth/1.0"
        private val logger = KotlinLogging.logger {}
        val http = HttpClient(Apache) {
            this.expectSuccess = false
        }
    }

    private val mutex = Mutex()

    suspend fun doTokenExchange(): JsonObject {
        logger.info { "doTokenExchange()" }

        val parameters = Parameters.build {
            append("client_id", clientId)
            append("client_secret", clientSecret)
            append("grant_type", "client_credentials")
        }

        return doStuff(checkForRefresh = false) {
            val result = http.post {
                url(TOKEN_BASE_URL)
                userAgent(USER_AGENT)

                setBody(TextContent(parameters.formUrlEncode(), ContentType.Application.FormUrlEncoded))
            }.bodyAsText()

            logger.info { result }

            val tree = JsonParser.parseString(result).asJsonObject

            if (tree.has("error"))
                throw TokenExchangeException("Error while exchanging token: ${tree["error"].asString}")

            readTokenPayload(tree)

            tree
        }
    }

    suspend fun refreshToken() {
        logger.info { "refreshToken()" }
        // https://discuss.dev.twitch.tv/t/id-token-missing-when-using-id-twitch-tv-oauth2-token-with-grant-type-refresh-token/18263/3
        doTokenExchange()
    }

    private suspend fun refreshTokenIfNeeded() {
        logger.info { "refreshTokenIfNeeded()" }
        if (accessToken == null)
            throw NeedsRefreshException()

        val generatedAt = generatedAt
        val expiresIn = expiresIn

        if (generatedAt != null && expiresIn != null) {
            if (System.currentTimeMillis() >= generatedAt + (expiresIn * 1000))
                throw NeedsRefreshException()
        }

        return
    }

    private suspend fun <T> doStuff(checkForRefresh: Boolean = true, callback: suspend () -> (T)): T {
        logger.info { "doStuff(...) mutex locked? ${mutex.isLocked}" }
        return try {
            if (checkForRefresh)
                refreshTokenIfNeeded()

            mutex.withLock {
                callback.invoke()
            }
        } catch (e: RateLimitedException) {
            logger.info { "rate limited exception! locked? ${mutex.isLocked}" }
            doStuff(checkForRefresh, callback)
        } catch (e: NeedsRefreshException) {
            logger.info { "refresh exception!" }
            refreshToken()
            doStuff(checkForRefresh, callback)
        } catch (e: TokenUnauthorizedException) {
            logger.info { "Unauthorized token exception! Doing token exchange again and retrying..." }
            doTokenExchange()
            doStuff(checkForRefresh, callback)
        }
    }

    private fun readTokenPayload(payload: JsonObject) {
        accessToken = payload["access_token"].string
        // https://discuss.dev.twitch.tv/t/id-token-missing-when-using-id-twitch-tv-oauth2-token-with-grant-type-refresh-token/18263/3
        // refreshToken = payload["refresh_token"].string
        expiresIn = payload["expires_in"].long
        generatedAt = System.currentTimeMillis()
    }

    private suspend fun checkForRateLimit(element: JsonElement): Boolean {
        if (element.isJsonObject) {
            val asObject = element.obj
            if (asObject.has("retry_after")) {
                val retryAfter = asObject["retry_after"].long

                logger.info { "Got rate limited, oof! Retry After: $retryAfter" }
                // oof, ratelimited!
                delay(retryAfter)
                throw RateLimitedException()
            }
        }

        return false
    }

    private suspend fun checkIfRequestWasValid(response: HttpResponse): HttpResponse {
        if (response.status.value == 401)
            throw TokenUnauthorizedException(response.status)

        return response
    }

    suspend fun makeTwitchApiRequest(url: String, httpRequestBuilderBlock: HttpRequestBuilder.() -> (Unit)): HttpResponse {
        return doStuff {
            val result = checkIfRequestWasValid(
                http.request(url) {
                    userAgent(USER_AGENT)
                    header("Authorization", "Bearer $accessToken")
                    header("Client-ID", clientId)

                    httpRequestBuilderBlock.invoke(this)
                }
            )
            result
        }
    }

    class TokenUnauthorizedException(status: HttpStatusCode) : RuntimeException()
    class TokenExchangeException(message: String) : RuntimeException(message)
    private class RateLimitedException : RuntimeException()
    private class NeedsRefreshException : RuntimeException()
}