package io.stamethyst.backend.workshop

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.stamethyst.backend.steamcloud.SteamCloudAuthStore
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import top.apricityx.workshop.steam.protocol.STEAM_LANGUAGE_ENGLISH
import top.apricityx.workshop.steam.protocol.STEAM_LANGUAGE_SIMPLIFIED_CHINESE
import top.apricityx.workshop.steam.protocol.SteamAccountSession
import top.apricityx.workshop.steam.protocol.SteamAuthenticationClient
import top.apricityx.workshop.steam.protocol.SteamDirectoryClient

enum class SteamLanguagePreference(
    val storageValue: String,
    val requestValue: String,
    val acceptLanguageValue: String,
    val protocolLanguage: Int,
    val displayName: String,
) {
    SimplifiedChinese(
        storageValue = "schinese",
        requestValue = "schinese",
        acceptLanguageValue = "zh-CN,zh;q=0.9",
        protocolLanguage = STEAM_LANGUAGE_SIMPLIFIED_CHINESE,
        displayName = "简体中文",
    ),
    English(
        storageValue = "english",
        requestValue = "english",
        acceptLanguageValue = "en-US,en;q=0.9",
        protocolLanguage = STEAM_LANGUAGE_ENGLISH,
        displayName = "English",
    );

    companion object {
        fun fromStorageValue(value: String?): SteamLanguagePreference =
            entries.firstOrNull { it.storageValue == value?.trim() } ?: SimplifiedChinese
    }
}

internal class WorkshopSteamWebSession(
    context: Context,
    /**
     * Accelerated client for the Steam directory lookup.
     *
     * `ISteamDirectory/GetCMListForConnect` is plain HTTPS to api.steampowered.com,
     * so it must keep acceleration. Passing the bare protocol client here left the
     * request that gates every logged-in market load unaccelerated.
     */
    private val directoryClient: OkHttpClient,
    /** Accelerated client for CM websocket sessions, including steamserver.net routes. */
    private val cmHttpClient: OkHttpClient,
    private val identity: WorkshopSteamClientIdentity,
) {
    private val appContext = context.applicationContext

    val cookieJar: SteamWebSessionCookieJar = SteamWebSessionCookieJar(
        projectedCookiesProvider = ::projectedCookiesFor,
        sessionScopeProvider = { currentScope },
    )

    private val lock = Any()

    /**
     * Serializes the priming body. Without this, two concurrent callers (for example a background
     * warm-up and a browse request) both pass the [primedScope] fast-path check while priming is
     * still in flight, each generate their own `sessionid`, and the last writer's context wins.
     * The `sessionid` cookie then no longer matches the session Steam established during
     * [primeUrl], so Steam answers with the logged-out view.
     */
    private val primeMutex = Mutex()
    private var currentScope: String? = null
    private var primedScope: String? = null
    private var webLoginContext: SteamWebLoginContext? = null

    fun currentSessionId(): String? = synchronized(lock) {
        webLoginContext?.sessionId
    }

    private fun isPrimedFor(scope: String, nowMs: Long): Boolean = synchronized(lock) {
        primedScope == scope && webLoginContext?.isUsableAt(nowMs) == true
    }

    suspend fun ensurePrimed(
        account: SteamAccountSession?,
        client: OkHttpClient,
        languagePreference: SteamLanguagePreference,
    ) {
        val primeStartedAtMs = SystemClock.elapsedRealtime()
        if (account == null) {
            synchronized(lock) {
                currentScope = null
                primedScope = null
                webLoginContext = null
            }
            Log.i(PERF_TAG, "ensurePrimed skip noAccount elapsedMs=${SystemClock.elapsedRealtime() - primeStartedAtMs}")
            return
        }
        val scope = "${account.steamId}:${account.refreshToken.hashCode()}"
        if (isPrimedFor(scope, System.currentTimeMillis())) {
            Log.i(PERF_TAG, "ensurePrimed skip alreadyPrimed elapsedMs=${SystemClock.elapsedRealtime() - primeStartedAtMs}")
            return
        }

        primeMutex.withLock {
            // Re-check inside the critical section: a concurrent caller may have completed
            // priming for this scope while we were waiting for the mutex.
            if (isPrimedFor(scope, System.currentTimeMillis())) {
                Log.i(PERF_TAG, "ensurePrimed skip alreadyPrimedAfterWait elapsedMs=${SystemClock.elapsedRealtime() - primeStartedAtMs}")
                return
            }
            primeLocked(
                account = account,
                scope = scope,
                client = client,
                languagePreference = languagePreference,
                primeStartedAtMs = primeStartedAtMs,
            )
        }
    }

    private suspend fun primeLocked(
        account: SteamAccountSession,
        scope: String,
        client: OkHttpClient,
        languagePreference: SteamLanguagePreference,
        primeStartedAtMs: Long,
    ) {
        val tokenStartedAtMs = SystemClock.elapsedRealtime()
        val cachedToken = SteamCloudAuthStore.readCachedWebAccessToken(
            context = appContext,
            steamId = account.steamId,
            refreshToken = account.refreshToken,
            minimumRemainingLifetimeMs = STEAM_WEB_ACCESS_TOKEN_REFRESH_SKEW_MS,
        )
        val webAccessToken = cachedToken?.toWorkshopWebAccessToken() ?: withContext(Dispatchers.IO) {
            val accessToken = SteamAuthenticationClient(
                directoryClient = SteamDirectoryClient(directoryClient),
                sessionFactory = { SharedSteamCmSessions.forProcess(appContext).asCmSession() },
            ).generateAccessTokenForApp(
                account = account,
                allowRenewal = false,
            ).accessToken
            val jwtExpirationMs = accessToken.expirationMillisOrNull()
            SteamWebAccessToken(
                value = accessToken,
                expiresAtMs = jwtExpirationMs
                    ?: (System.currentTimeMillis() + STEAM_WEB_ACCESS_TOKEN_FALLBACK_LIFETIME_MS),
                wasCached = false,
                hasJwtExpiration = jwtExpirationMs != null,
            )
        }
        if (
            !webAccessToken.wasCached &&
            webAccessToken.hasJwtExpiration &&
            webAccessToken.expiresAtMs > System.currentTimeMillis() + STEAM_WEB_ACCESS_TOKEN_REFRESH_SKEW_MS
        ) {
            SteamCloudAuthStore.cacheWebAccessToken(
                context = appContext,
                steamId = account.steamId,
                refreshToken = account.refreshToken,
                accessToken = webAccessToken.value,
                expiresAtMs = webAccessToken.expiresAtMs,
            )
        }
        val tokenMs = SystemClock.elapsedRealtime() - tokenStartedAtMs

        synchronized(lock) {
            currentScope = scope
            webLoginContext = SteamWebLoginContext(
                steamId = account.steamId,
                accessToken = webAccessToken.value,
                sessionId = generateSteamWebSessionId(),
                accessTokenExpiresAtMs = webAccessToken.expiresAtMs,
            )
        }

        val urls = listOf(
            "https://store.steampowered.com/account/preferences/",
            "https://steamcommunity.com/login/home/?goto=workshop%2F",
        )
        // Keep these sequential. The steamcommunity login request depends on the cookies that the
        // store request stores in the shared cookie jar; running them concurrently makes the second
        // request miss those cookies and Steam then answers later browses with the logged-out view.
        withContext(Dispatchers.IO) {
            urls.forEach { url ->
                val urlStartedAtMs = SystemClock.elapsedRealtime()
                val result = runCatching { primeUrl(client, url, languagePreference) }
                Log.i(
                    PERF_TAG,
                    "ensurePrimed primeUrl host=${url.substringAfter("https://").substringBefore("/")} ok=${result.isSuccess} elapsedMs=${SystemClock.elapsedRealtime() - urlStartedAtMs}",
                )
            }
        }
        synchronized(lock) {
            primedScope = scope
        }
        Log.i(
            PERF_TAG,
            "ensurePrimed done tokenSource=${if (webAccessToken.wasCached) "cache" else "cm"} tokenMs=$tokenMs totalMs=${SystemClock.elapsedRealtime() - primeStartedAtMs}",
        )
    }

    private fun projectedCookiesFor(url: HttpUrl): List<Cookie> {
        if (!url.host.isSteamDomain()) return emptyList()
        val context = synchronized(lock) { webLoginContext } ?: return emptyList()
        val domain = when {
            url.host == "steamcommunity.com" || url.host.endsWith(".steamcommunity.com") -> "steamcommunity.com"
            url.host == "steampowered.com" || url.host.endsWith(".steampowered.com") -> "steampowered.com"
            else -> url.host
        }
        return listOf(
            Cookie.Builder()
                .name("steamLoginSecure")
                .value("${context.steamId}%7C%7C${context.accessToken}")
                .domain(domain)
                .path("/")
                .secure()
                .build(),
            Cookie.Builder()
                .name("sessionid")
                .value(context.sessionId)
                .domain(domain)
                .path("/")
                .secure()
                .build(),
        )
    }

    private fun primeUrl(
        client: OkHttpClient,
        url: String,
        languagePreference: SteamLanguagePreference,
    ) {
        client.newCall(
            Request.Builder()
                .url(url)
                .header("Accept-Language", languagePreference.acceptLanguageValue)
                .header("User-Agent", STEAM_WEB_SESSION_USER_AGENT)
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) error("Steam web session prime failed: ${response.code}")
        }
    }
}

internal class SteamWebSessionCookieJar(
    private val projectedCookiesProvider: (HttpUrl) -> List<Cookie> = { emptyList() },
    private val sessionScopeProvider: (() -> String?)? = null,
) : CookieJar {
    private val lock = Any()
    private val cookies = linkedMapOf<StoredCookieKey, Cookie>()
    private var isScopeInitialized = false
    private var currentScope: String? = null

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (!url.host.isSteamDomain()) return
        syncScope()
        val now = System.currentTimeMillis()
        synchronized(lock) {
            cookies.forEach { cookie ->
                val key = cookie.storageKey()
                if (cookie.expiresAt <= now) {
                    this.cookies.remove(key)
                } else {
                    this.cookies[key] = cookie
                }
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!url.host.isSteamDomain()) return emptyList()
        syncScope()
        val persistedCookies = synchronized(lock) {
            val now = System.currentTimeMillis()
            val expiredKeys = mutableListOf<StoredCookieKey>()
            val matchingCookies = mutableListOf<Cookie>()
            cookies.forEach { (key, cookie) ->
                when {
                    cookie.expiresAt <= now -> expiredKeys += key
                    cookie.matches(url) -> matchingCookies += cookie
                }
            }
            expiredKeys.forEach(cookies::remove)
            matchingCookies
        }
        if (persistedCookies.isEmpty()) return projectedCookiesProvider(url)
        val merged = linkedMapOf<StoredCookieKey, Cookie>()
        persistedCookies.forEach { cookie -> merged[cookie.storageKey()] = cookie }
        projectedCookiesProvider(url).forEach { cookie -> merged[cookie.storageKey()] = cookie }
        return merged.values.toList()
    }

    private fun syncScope() {
        val provider = sessionScopeProvider ?: return
        val nextScope = provider()
        synchronized(lock) {
            if (!isScopeInitialized || currentScope != nextScope) {
                cookies.clear()
                currentScope = nextScope
                isScopeInitialized = true
            }
        }
    }
}

internal class SteamLanguageInterceptor(
    private val languagePreferenceProvider: () -> SteamLanguagePreference,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.host.isSteamDomain()) return chain.proceed(request)
        return chain.proceed(
            request.newBuilder()
                .header("Accept-Language", languagePreferenceProvider().acceptLanguageValue)
                .build()
        )
    }
}

private data class StoredCookieKey(
    val name: String,
    val domain: String,
    val path: String,
)

private fun Cookie.storageKey(): StoredCookieKey = StoredCookieKey(
    name = name.lowercase(),
    domain = domain,
    path = path,
)

private data class SteamWebLoginContext(
    val steamId: Long,
    val accessToken: String,
    val sessionId: String,
    val accessTokenExpiresAtMs: Long,
)

private data class SteamWebAccessToken(
    val value: String,
    val expiresAtMs: Long,
    val wasCached: Boolean,
    val hasJwtExpiration: Boolean,
)

private fun SteamCloudAuthStore.CachedWebAccessToken.toWorkshopWebAccessToken(): SteamWebAccessToken =
    SteamWebAccessToken(
        value = accessToken,
        expiresAtMs = expiresAtMs,
        wasCached = true,
        hasJwtExpiration = true,
    )

private fun SteamWebLoginContext.isUsableAt(nowMs: Long): Boolean =
    accessTokenExpiresAtMs > nowMs + STEAM_WEB_ACCESS_TOKEN_REFRESH_SKEW_MS

internal fun String.expirationMillisOrNull(): Long? {
    val payload = split('.').getOrNull(1) ?: return null
    val decodedPayload = runCatching {
        String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
    }.getOrNull() ?: return null
    val expirationSeconds = runCatching {
        Json.parseToJsonElement(decodedPayload)
            .jsonObject["exp"]
            ?.jsonPrimitive
            ?.longOrNull
    }.getOrNull() ?: return null
    if (expirationSeconds <= 0L || expirationSeconds > Long.MAX_VALUE / 1_000L) return null
    return expirationSeconds * 1_000L
}

internal fun String.isSteamDomain(): Boolean {
    val normalizedHost = lowercase()
    return normalizedHost == "steamcommunity.com" ||
        normalizedHost.endsWith(".steamcommunity.com") ||
        normalizedHost == "steampowered.com" ||
        normalizedHost.endsWith(".steampowered.com")
}

private fun generateSteamWebSessionId(): String {
    val bytes = ByteArray(12)
    steamWebSessionRandom.nextBytes(bytes)
    val result = StringBuilder(bytes.size * 2)
    bytes.forEach { byte ->
        val value = byte.toInt() and 0xFF
        result.append(HEX_CHARS[value ushr 4])
        result.append(HEX_CHARS[value and 0x0F])
    }
    return result.toString()
}

private val steamWebSessionRandom = SecureRandom()
private const val PERF_TAG = "WorkshopPerf"
private const val STEAM_WEB_ACCESS_TOKEN_REFRESH_SKEW_MS = 5 * 60 * 1_000L
private const val STEAM_WEB_ACCESS_TOKEN_FALLBACK_LIFETIME_MS = 45 * 60 * 1_000L
private const val STEAM_WEB_SESSION_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
private val HEX_CHARS = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f')
