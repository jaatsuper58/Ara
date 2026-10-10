package com.ara.core.net.auth

import com.ara.core.net.BuildConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlinx.serialization.encodeToString

/** Attaches the bearer token to API calls (skipped for login/account endpoints). */
class AuthInterceptor @Inject constructor(
    private val tokenStore: TokenStore,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val isAuthPath = request.url.encodedPath.contains("/session") ||
            request.url.encodedPath.contains("/account")
        val token = runBlocking { tokenStore.accessToken() }
        val authorized = if (isAuthPath || token == null) request else
            request.newBuilder().header("Authorization", "Bearer $token").build()
        return chain.proceed(authorized)
    }
}

@Serializable
private data class RefreshRequest(val araId: String, val refreshToken: String)

@Serializable
private data class RefreshResponse(
    val araId: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long = 0,
)

/**
 * On 401, exchanges the refresh token once for a new session, then retries.
 *
 * Deliberately does NOT depend on the Retrofit [com.ara.core.net.api.AuthApi]:
 * that would create a Dagger cycle (Api → Retrofit → OkHttpClient → this).
 * The refresh call uses a bare client with the same TLS/pin config minus the
 * auth interceptor.
 */
class TokenAuthenticator @Inject constructor(
    private val tokenStore: TokenStore,
) : Authenticator {

    private val json = Json { ignoreUnknownKeys = true }
    private val bareClient = OkHttpClient()

    override fun authenticate(route: okhttp3.Route?, response: Response): Request? {
        if (responseCount(response) >= 2) return null

        val araId = runBlocking { tokenStore.current()?.araId } ?: return null
        val refreshToken = runBlocking { tokenStore.refreshToken() } ?: return null

        val refreshed = runCatching {
            val body = json.encodeToString(
                RefreshRequest.serializer(),
                RefreshRequest(araId, refreshToken),
            ).toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(BuildConfig.API_BASE_URL + "session/refresh")
                .post(body)
                .build()
            bareClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val parsed = json.decodeFromString(RefreshResponse.serializer(), resp.body!!.string())
                // The refreshed session must belong to the signed-in account.
                parsed.takeIf { it.araId == araId }
            }
        }.getOrNull() ?: return null

        runBlocking { tokenStore.updateTokens(refreshed.accessToken, refreshed.refreshToken) }
        return response.request.newBuilder()
            .header("Authorization", "Bearer ${refreshed.accessToken}")
            .build()
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
