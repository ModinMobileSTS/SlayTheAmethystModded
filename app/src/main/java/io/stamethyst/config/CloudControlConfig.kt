package io.stamethyst.config

import android.content.Context
import android.util.Log
import io.stamethyst.BuildConfig
import io.stamethyst.backend.github.WattToolkitAcceleratedHttp
import io.stamethyst.backend.network.NetworkAccelerationPolicy
import io.stamethyst.backend.update.GithubMirrorFallback
import io.stamethyst.backend.update.UpdateMirrorManager
import io.stamethyst.backend.update.UpdateSource
import io.stamethyst.backend.update.toGithubMirrorHttpException
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import okhttp3.OkHttpClient
import okhttp3.Request

private const val DEFAULT_QQ_GROUP_NUMBER_VALUE = "1029305387"
private const val STEAM_DEPOT_KEY_BYTES = 32
private const val DEFAULT_CLOUD_CONTROL_ASSET_NAME = "cloud-control.json"
private const val LOCAL_TEST_CLOUD_CONTROL_ASSET_NAME = "cloud-control-test.json"
private const val LOCAL_TEST_CLOUD_CONTROL_CONFIG_URL =
    "http://10.126.126.2:3001/cloud-control.json"
private const val DEFAULT_EASYTIER_CONNECT_TIMEOUT_SECONDS = 12
private const val DEFAULT_EASYTIER_STATUS_POLL_INTERVAL_SECONDS = 5
private const val DEFAULT_EASYTIER_DEFAULT_MODE = "room"
private const val CLOUD_CONTROL_CACHE_DIRECTORY_NAME = "cloud-control"
private const val CLOUD_CONTROL_CACHE_FILE_NAME = "remote.json"
private const val LOCAL_TEST_CLOUD_CONTROL_CACHE_FILE_NAME = "local-test.json"

data class CloudControlSettings(
    val heartbeatIntervalSeconds: Int,
    val heartbeatWsUrl: String,
    val qqGroupNumber: String = DEFAULT_QQ_GROUP_NUMBER_VALUE,
    val steamDepotKeys: List<CloudControlSteamDepotKey> = emptyList(),
    val easyTier: CloudControlEasyTierSettings = CloudControlEasyTierSettings(),
) {
    val heartbeatIntervalMs: Long
        get() = heartbeatIntervalSeconds * 1000L

    val qqGroupUrl: String
        get() = CloudControlConfig.qqGroupUrlFor(qqGroupNumber)

    fun steamDepotKeyBytes(appId: UInt, depotId: UInt): ByteArray? =
        steamDepotKeys
            .firstOrNull { key ->
                key.appId == appId.toLong() && key.depotId == depotId.toLong()
            }
            ?.decodeKeyBytes()
}

data class CloudControlEasyTierSettings(
    val enabled: Boolean = false,
    val minimumOnlineLobbyCompatibleVersion: String = "",
    val roomApiBaseUrl: String = "",
    val webConsoleApiBaseUrl: String = "",
    val configServerUrl: String = "",
    val entryNodeUrl: String = "",
    val connectTimeoutSeconds: Int = DEFAULT_EASYTIER_CONNECT_TIMEOUT_SECONDS,
    val statusPollIntervalSeconds: Int = DEFAULT_EASYTIER_STATUS_POLL_INTERVAL_SECONDS,
    val allowSharedCommunityNetwork: Boolean = false,
    val defaultMode: String = DEFAULT_EASYTIER_DEFAULT_MODE,
) {
    val isConfigured: Boolean
        get() = enabled && entryNodeUrl.isNotBlank()
}

data class CloudControlSteamDepotKey(
    val appId: Long,
    val depotId: Long,
    val keyHex: String
) {
    fun decodeKeyBytes(): ByteArray? =
        CloudControlConfig.decodeSteamDepotKeyHex(keyHex)
}

data class CloudControlRemoteConfigText(
    val sourceDisplayName: String,
    val requestUrl: String,
    val rawText: String
)

private data class CachedCloudControlSettings(
    val settings: CloudControlSettings,
    val rawText: String,
)

object CloudControlConfig {
    const val DEFAULT_HEARTBEAT_INTERVAL_SECONDS = 30
    const val DEFAULT_HEARTBEAT_WS_URL = "wss://heartbeat.nas.apricityx.top:23163/api/presence/ws"
    const val DEFAULT_QQ_GROUP_NUMBER = DEFAULT_QQ_GROUP_NUMBER_VALUE
    const val MIN_HEARTBEAT_INTERVAL_SECONDS = 30
    const val MAX_HEARTBEAT_INTERVAL_SECONDS = 3_600
    const val MIN_EASYTIER_CONNECT_TIMEOUT_SECONDS = 1
    const val MAX_EASYTIER_CONNECT_TIMEOUT_SECONDS = 300
    const val MIN_EASYTIER_STATUS_POLL_INTERVAL_SECONDS = 1
    const val MAX_EASYTIER_STATUS_POLL_INTERVAL_SECONDS = 300

    private const val TAG = "STS-CloudControl"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 8_000
    private val USER_AGENT = "SlayTheAmethyst/${BuildConfig.VERSION_NAME}"
    private val QQ_GROUP_NUMBER_REGEX = Regex("[1-9][0-9]{4,19}")
    private val STEAM_DEPOT_KEY_HEX_REGEX = Regex("[0-9a-f]{64}")
    private val HEX_DIGITS = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    )

    private val startupRefreshStarted = AtomicBoolean(false)
    private val refreshGeneration = AtomicLong(0L)
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    @Volatile
    private var bundledDefaultSettings: CloudControlSettings? = null

    @Volatile
    private var bundledLocalTestSettings: CloudControlSettings? = null

    @Volatile
    private var startupRefreshCompleted = false

    @Volatile
    private var currentSettings: CloudControlSettings = defaultSettings()

    @JvmStatic
    fun current(): CloudControlSettings = currentSettings

    @JvmStatic
    fun heartbeatIntervalSeconds(): Int = current().heartbeatIntervalSeconds

    @JvmStatic
    fun heartbeatWsUrl(): String = current().heartbeatWsUrl

    @JvmStatic
    fun qqGroupNumber(): String = current().qqGroupNumber

    @JvmStatic
    fun qqGroupUrl(): String = current().qqGroupUrl

    @JvmStatic
    fun easyTier(): CloudControlEasyTierSettings = current().easyTier

    fun steamDepotKeyBytes(appId: UInt, depotId: UInt): ByteArray? =
        current().steamDepotKeyBytes(appId, depotId)

    @JvmStatic
    fun isStartupRefreshCompleted(): Boolean = startupRefreshCompleted

    @JvmStatic
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    @JvmStatic
    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    @JvmStatic
    fun defaultSettings(): CloudControlSettings =
        CloudControlSettings(
            heartbeatIntervalSeconds = DEFAULT_HEARTBEAT_INTERVAL_SECONDS,
            heartbeatWsUrl = defaultHeartbeatWsUrl(),
            qqGroupNumber = DEFAULT_QQ_GROUP_NUMBER
        )

    @JvmStatic
    fun defaultSettings(context: Context): CloudControlSettings =
        bundledDefaultSettings
            ?: synchronized(this) {
                bundledDefaultSettings
                    ?: readBundledDefaultSettings(context.applicationContext)
                        .also { bundledDefaultSettings = it }
            }

    private fun localTestSettings(context: Context): CloudControlSettings =
        bundledLocalTestSettings
            ?: synchronized(this) {
                bundledLocalTestSettings
                    ?: readBundledSettings(
                        context = context.applicationContext,
                        assetName = LOCAL_TEST_CLOUD_CONTROL_ASSET_NAME,
                    ).also { bundledLocalTestSettings = it }
            }

    @JvmStatic
    fun defaultHeartbeatWsUrl(): String =
        DEFAULT_HEARTBEAT_WS_URL

    @JvmStatic
    fun qqGroupUrlFor(groupNumber: String): String =
        "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=" +
            groupNumber +
            "&card_type=group&source=qrcode"

    private fun readBundledDefaultSettings(context: Context): CloudControlSettings {
        return readBundledSettings(context, DEFAULT_CLOUD_CONTROL_ASSET_NAME)
    }

    private fun readBundledSettings(context: Context, assetName: String): CloudControlSettings {
        val emergencyDefaults = defaultSettings()
        val responseText = try {
            context.assets
                .open(assetName)
                .bufferedReader(StandardCharsets.UTF_8)
                .use { reader -> reader.readText() }
        } catch (error: Throwable) {
            Log.w(
                TAG,
                "Bundled cloud control config '$assetName' read failed; using emergency defaults: " +
                    "${error.javaClass.simpleName}: ${error.message ?: "no message"}"
            )
            return emergencyDefaults
        }

        return parseSettings(responseText, defaults = emergencyDefaults)
            ?: emergencyDefaults.also {
                Log.w(TAG, "Bundled cloud control config '$assetName' is invalid; using emergency defaults")
            }
    }

    fun fetchRemoteConfigText(context: Context): CloudControlRemoteConfigText {
        val configUrl = selectedConfigUrl(context.applicationContext)
        if (configUrl.isEmpty()) {
            throw IOException("Cloud control config URL is empty.")
        }
        return fetchRemoteConfigText(context.applicationContext, configUrl)
    }

    @JvmStatic
    fun refreshOnAppStart(context: Context) {
        if (!startupRefreshStarted.compareAndSet(false, true)) {
            return
        }
        val appContext = context.applicationContext
        val defaults = selectedDefaultSettings(appContext)
        currentSettings = readCachedSettings(
            cacheFile = selectedCacheFile(appContext),
            defaults = defaults,
        )?.withSelectedChannelEndpoints(appContext) ?: defaults
        startupRefreshCompleted = false
        refreshAsync(appContext)
    }

    @JvmStatic
    fun refreshAsync(context: Context) {
        val appContext = context.applicationContext
        val generation = refreshGeneration.incrementAndGet()
        Thread({
            refreshSelectedChannel(appContext, generation)
        }, "STS-CloudControlFetch").apply {
            isDaemon = true
            start()
        }
    }

    @JvmStatic
    fun refreshBlocking(context: Context): CloudControlSettings {
        val appContext = context.applicationContext
        val generation = refreshGeneration.incrementAndGet()
        return refreshSelectedChannel(appContext, generation)
    }

    @JvmStatic
    fun setLocalTestChannelEnabled(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        LauncherConfig.setLocalTestCloudControlEnabled(appContext, enabled)
        val defaults = selectedDefaultSettings(appContext)
        currentSettings = readCachedSettings(
            cacheFile = selectedCacheFile(appContext),
            defaults = defaults,
        )?.withSelectedChannelEndpoints(appContext) ?: defaults
        startupRefreshCompleted = false
        refreshAsync(appContext)
    }

    fun updateLocalTestEndpoints(
        context: Context,
        onlineServiceBaseUrl: String,
        configServerUrl: String,
        entryNodeUrl: String,
    ): Boolean {
        val normalizedOnlineServiceBaseUrl =
            normalizeLocalTestOnlineServiceBaseUrl(onlineServiceBaseUrl) ?: return false
        val normalizedConfigServerUrl = normalizeEndpointUrl(configServerUrl) ?: return false
        val normalizedEntryNodeUrl = normalizeEndpointUrl(entryNodeUrl) ?: return false
        val appContext = context.applicationContext
        LauncherConfig.saveLocalTestOnlineServiceBaseUrl(appContext, normalizedOnlineServiceBaseUrl)
        LauncherConfig.saveLocalTestConfigServerUrl(appContext, normalizedConfigServerUrl)
        LauncherConfig.saveLocalTestEntryNodeUrl(appContext, normalizedEntryNodeUrl)
        if (LauncherConfig.isLocalTestCloudControlEnabled(appContext)) {
            currentSettings = currentSettings.withLocalTestEndpoints(appContext)
            refreshAsync(appContext)
        }
        return true
    }

    private fun refreshSelectedChannel(context: Context, generation: Long): CloudControlSettings {
        val defaults = selectedDefaultSettings(context)
        val configUrl = selectedConfigUrl(context)
        return try {
            if (configUrl.isEmpty()) {
                Log.i(TAG, "Cloud control config URL is empty; using defaults")
                updateCurrentSettingsIfCurrent(generation, defaults)
                return defaults
            }
            val fetched = fetchRemoteSettings(context, configUrl)
            updateCurrentSettingsAndCacheIfCurrent(
                context = context,
                generation = generation,
                settings = fetched.settings,
                rawText = fetched.rawText,
            )
            Log.i(TAG, "Cloud control config loaded from $configUrl")
            fetched.settings
        } catch (error: Throwable) {
            val fallback = currentSettingsForFetchFailure(defaults)
            updateCurrentSettingsIfCurrent(generation, fallback)
            Log.w(
                TAG,
                "Cloud control config fetch failed; using bundled/current settings: " +
                    "${error.javaClass.simpleName}: ${error.message ?: "no message"}"
            )
            fallback
        }
    }

    private fun selectedDefaultSettings(context: Context): CloudControlSettings =
        if (LauncherConfig.isLocalTestCloudControlEnabled(context)) {
            localTestSettings(context).withLocalTestEndpoints(context)
        } else {
            defaultSettings(context)
        }

    private fun selectedConfigUrl(context: Context): String =
        if (LauncherConfig.isLocalTestCloudControlEnabled(context)) {
            LauncherConfig.readLocalTestOnlineServiceBaseUrl(context)
                .trimEnd('/')
                .ifBlank { LOCAL_TEST_CLOUD_CONTROL_CONFIG_URL.removeSuffix("/cloud-control.json") }
                .let { "$it/cloud-control.json" }
        } else {
            BuildConfig.CLOUD_CONTROL_CONFIG_URL.trim()
        }

    private fun selectedCacheFile(context: Context): File {
        val fileName = if (LauncherConfig.isLocalTestCloudControlEnabled(context)) {
            LOCAL_TEST_CLOUD_CONTROL_CACHE_FILE_NAME
        } else {
            CLOUD_CONTROL_CACHE_FILE_NAME
        }
        return File(File(context.filesDir, CLOUD_CONTROL_CACHE_DIRECTORY_NAME), fileName)
    }

    private fun updateCurrentSettings(settings: CloudControlSettings) {
        currentSettings = settings
        startupRefreshCompleted = true
        for (listener in listeners) {
            try {
                listener()
            } catch (_: Throwable) {
            }
        }
    }

    private fun updateCurrentSettingsIfCurrent(generation: Long, settings: CloudControlSettings) {
        if (generation == refreshGeneration.get()) {
            updateCurrentSettings(settings)
        }
    }

    private fun updateCurrentSettingsAndCacheIfCurrent(
        context: Context,
        generation: Long,
        settings: CloudControlSettings,
        rawText: String,
    ) {
        synchronized(this) {
            if (generation != refreshGeneration.get()) {
                return
            }
            try {
                writeCachedSettings(selectedCacheFile(context), rawText)
            } catch (error: Throwable) {
                Log.w(
                    TAG,
                    "Cloud control config loaded but could not be cached: " +
                        "${error.javaClass.simpleName}: ${error.message ?: "no message"}"
                )
            }
            updateCurrentSettings(settings)
        }
    }

    private fun currentSettingsForFetchFailure(bundledDefaults: CloudControlSettings): CloudControlSettings {
        val current = currentSettings
        if (current == bundledDefaults) {
            return bundledDefaults
        }
        return if (current.steamDepotKeys.isEmpty() && bundledDefaults.steamDepotKeys.isNotEmpty()) {
            current.copy(steamDepotKeys = bundledDefaults.steamDepotKeys)
        } else {
            current
        }
    }

    internal fun parseSettings(
        responseText: String,
        defaults: CloudControlSettings = defaultSettings()
    ): CloudControlSettings? {
        val root = parseJsonObject(responseText) ?: return null
        val heartbeatObject = root.optJSONObject("heartbeat")
        val qqGroupObject = root.optJSONObject("qqGroup")
            ?: root.optJSONObject("officialQqGroup")
        val qqObject = root.optJSONObject("qq")

        val intervalSeconds = normalizeHeartbeatIntervalSeconds(
            firstPositiveInt(
                root,
                heartbeatObject,
                "heartbeatIntervalSeconds",
                "heartbeatFrequencySeconds",
                "presenceHeartbeatIntervalSeconds",
                "intervalSeconds",
                "heartbeat_interval_seconds",
                "heartbeat_frequency_seconds"
            ) ?: defaults.heartbeatIntervalSeconds
        )
        val wsUrl = normalizeHeartbeatWsUrl(
            firstNonBlankString(
                root,
                heartbeatObject,
                "heartbeatWsUrl",
                "presenceHeartbeatWsUrl",
                "heartbeatWebSocketUrl",
                "presenceHeartbeatWebSocketUrl",
                "wsUrl",
                "websocketUrl",
                "heartbeat_ws_url",
                "presence_heartbeat_ws_url",
                "heartbeat_websocket_url"
            ) ?: defaults.heartbeatWsUrl,
            defaults.heartbeatWsUrl
        )
        val qqGroupNumber = normalizeQqGroupNumber(
            firstNonBlankString(
                root,
                "qqGroupNumber",
                "officialQqGroupNumber",
                "qq_group_number",
                "official_qq_group_number"
            )
                ?: firstNonBlankString(
                    qqGroupObject,
                    "number",
                    "groupNumber",
                    "qqGroupNumber",
                    "uin"
                )
                ?: firstNonBlankString(
                    qqObject,
                    "groupNumber",
                    "qqGroupNumber",
                    "number",
                    "uin"
                )
                ?: defaults.qqGroupNumber,
            defaults.qqGroupNumber
        )
        val steamDepotKeys = parseSteamDepotKeys(root)
            .ifEmpty { defaults.steamDepotKeys }
        val easyTier = parseEasyTier(root, defaults.easyTier)

        return CloudControlSettings(
            heartbeatIntervalSeconds = intervalSeconds,
            heartbeatWsUrl = wsUrl,
            qqGroupNumber = qqGroupNumber,
            steamDepotKeys = steamDepotKeys,
            easyTier = easyTier,
        )
    }

    private fun parseEasyTier(
        root: JSONObject,
        defaults: CloudControlEasyTierSettings,
    ): CloudControlEasyTierSettings {
        val easyTierObject = root.optJSONObject("easyTier")
            ?: root.optJSONObject("easytier")
        val enabled = firstBoolean(
            root = root,
            nested = easyTierObject,
            rootNames = arrayOf("easyTierEnabled", "easytierEnabled"),
            nestedNames = arrayOf("enabled", "isEnabled"),
        ) ?: defaults.enabled
        val minimumOnlineLobbyCompatibleVersion = firstNonBlankString(
            root,
            "easyTierMinimumOnlineLobbyCompatibleVersion",
            "easytierMinimumOnlineLobbyCompatibleVersion",
            "minimumOnlineLobbyCompatibleVersion",
        ) ?: firstNonBlankString(
            easyTierObject,
            "minimumOnlineLobbyCompatibleVersion",
            "minimum_online_lobby_compatible_version",
        ) ?: defaults.minimumOnlineLobbyCompatibleVersion
        val roomApiBaseUrl = normalizeHttpUrl(
            firstNonBlankString(root, "easyTierRoomApiBaseUrl", "easytierRoomApiBaseUrl")
                ?: firstNonBlankString(
                    easyTierObject,
                    "roomApiBaseUrl",
                    "room_api_base_url",
                )
        ) ?: defaults.roomApiBaseUrl
        val webConsoleApiBaseUrl = normalizeHttpUrl(
            firstNonBlankString(
                root,
                "easyTierWebConsoleApiBaseUrl",
                "easytierWebConsoleApiBaseUrl",
            ) ?: firstNonBlankString(
                easyTierObject,
                "webConsoleApiBaseUrl",
                "web_console_api_base_url",
            )
        ) ?: defaults.webConsoleApiBaseUrl
        val configServerUrl = normalizeEndpointUrl(
            firstNonBlankString(
                root,
                "easyTierConfigServerUrl",
                "easytierConfigServerUrl",
            ) ?: firstNonBlankString(
                easyTierObject,
                "configServerUrl",
                "config_server_url",
            )
        ) ?: defaults.configServerUrl
        val entryNodeUrl = normalizeEndpointUrl(
            firstNonBlankString(
                root,
                "easyTierEntryNodeUrl",
                "easytierEntryNodeUrl",
            ) ?: firstNonBlankString(
                easyTierObject,
                "entryNodeUrl",
                "entry_node_url",
            )
        ) ?: defaults.entryNodeUrl
        val connectTimeoutSeconds = normalizeEasyTierConnectTimeoutSeconds(
            firstPositiveInt(
                root,
                "easyTierConnectTimeoutSeconds",
                "easytierConnectTimeoutSeconds",
            ) ?: firstPositiveInt(
                easyTierObject,
                "connectTimeoutSeconds",
                "connect_timeout_seconds",
            ) ?: defaults.connectTimeoutSeconds
        )
        val statusPollIntervalSeconds = normalizeEasyTierStatusPollIntervalSeconds(
            firstPositiveInt(
                root,
                "easyTierStatusPollIntervalSeconds",
                "easytierStatusPollIntervalSeconds",
            ) ?: firstPositiveInt(
                easyTierObject,
                "statusPollIntervalSeconds",
                "status_poll_interval_seconds",
            ) ?: defaults.statusPollIntervalSeconds
        )
        val allowSharedCommunityNetwork = firstBoolean(
            root = root,
            nested = easyTierObject,
            rootNames = arrayOf(
                "easyTierAllowSharedCommunityNetwork",
                "easytierAllowSharedCommunityNetwork",
            ),
            nestedNames = arrayOf(
                "allowSharedCommunityNetwork",
                "allow_shared_community_network",
            ),
        ) ?: defaults.allowSharedCommunityNetwork
        val defaultMode = normalizeEasyTierDefaultMode(
            firstNonBlankString(root, "easyTierDefaultMode", "easytierDefaultMode")
                ?: firstNonBlankString(
                    easyTierObject,
                    "defaultMode",
                    "default_mode",
                )
                ?: defaults.defaultMode
        )

        return CloudControlEasyTierSettings(
            enabled = enabled,
            minimumOnlineLobbyCompatibleVersion = minimumOnlineLobbyCompatibleVersion,
            roomApiBaseUrl = roomApiBaseUrl,
            webConsoleApiBaseUrl = webConsoleApiBaseUrl,
            configServerUrl = configServerUrl,
            entryNodeUrl = entryNodeUrl,
            connectTimeoutSeconds = connectTimeoutSeconds,
            statusPollIntervalSeconds = statusPollIntervalSeconds,
            allowSharedCommunityNetwork = allowSharedCommunityNetwork,
            defaultMode = defaultMode,
        )
    }

    private fun fetchRemoteSettings(
        context: Context,
        configUrl: String
    ): CachedCloudControlSettings {
        val responseText = fetchRemoteConfigText(context, configUrl).rawText

        val settings = parseSettings(responseText, defaults = selectedDefaultSettings(context))
            ?.let { parsed ->
                if (LauncherConfig.isLocalTestCloudControlEnabled(context)) {
                    parsed.withLocalTestEndpoints(context)
                } else {
                    parsed
                }
            }
            ?: throw IOException("Cloud control response is not a JSON object.")
        return CachedCloudControlSettings(settings = settings, rawText = responseText)
    }

    private fun CloudControlSettings.withLocalTestEndpoints(context: Context): CloudControlSettings {
        return applyLocalTestEndpoints(
            settings = this,
            onlineServiceBaseUrl = LauncherConfig.readLocalTestOnlineServiceBaseUrl(context),
            configServerUrl = LauncherConfig.readLocalTestConfigServerUrl(context),
            entryNodeUrl = LauncherConfig.readLocalTestEntryNodeUrl(context),
        )
    }

    private fun CloudControlSettings.withSelectedChannelEndpoints(context: Context): CloudControlSettings =
        if (LauncherConfig.isLocalTestCloudControlEnabled(context)) {
            withLocalTestEndpoints(context)
        } else {
            this
        }

    internal fun applyLocalTestEndpoints(
        settings: CloudControlSettings,
        onlineServiceBaseUrl: String,
        configServerUrl: String,
        entryNodeUrl: String,
    ): CloudControlSettings {
        val normalizedBaseUrl = onlineServiceBaseUrl.trimEnd('/')
        val parsedBaseUrl = URI(normalizedBaseUrl)
        val heartbeatScheme = if (parsedBaseUrl.scheme.equals("https", ignoreCase = true)) "wss" else "ws"
        val heartbeatAuthority = parsedBaseUrl.rawAuthority
        val heartbeatPathPrefix = parsedBaseUrl.rawPath.trimEnd('/')
        return settings.copy(
            heartbeatWsUrl = "$heartbeatScheme://$heartbeatAuthority$heartbeatPathPrefix/api/presence/ws",
            easyTier = settings.easyTier.copy(
                roomApiBaseUrl = normalizedBaseUrl,
                webConsoleApiBaseUrl = normalizedBaseUrl,
                configServerUrl = configServerUrl,
                entryNodeUrl = entryNodeUrl,
            ),
        )
    }

    internal fun readCachedSettings(
        cacheFile: File,
        defaults: CloudControlSettings,
    ): CloudControlSettings? {
        if (!cacheFile.isFile) {
            return null
        }
        return try {
            val rawText = cacheFile.readText(StandardCharsets.UTF_8)
            parseSettings(rawText, defaults)
                ?: throw IOException("Cached cloud control config is not a JSON object.")
        } catch (error: Throwable) {
            logCacheReadFailure(error)
            null
        }
    }

    private fun logCacheReadFailure(error: Throwable) {
        try {
            Log.w(
                TAG,
                "Cached cloud control config read failed; ignoring cache: " +
                    "${error.javaClass.simpleName}: ${error.message ?: "no message"}"
            )
        } catch (_: Throwable) {
            // Local JVM tests use an Android Log stub which throws instead of writing a log.
        }
    }

    internal fun writeCachedSettings(cacheFile: File, rawText: String) {
        val parent = cacheFile.parentFile
            ?: throw IOException("Cloud control cache has no parent directory.")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Could not create cloud control cache directory.")
        }

        val temporaryFile = File(parent, ".${cacheFile.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temporaryFile).use { output ->
                output.write(rawText.toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            if (!temporaryFile.renameTo(cacheFile)) {
                throw IOException("Could not atomically replace cloud control cache.")
            }
        } finally {
            if (temporaryFile.exists()) {
                temporaryFile.delete()
            }
        }
    }

    private fun fetchRemoteConfigText(
        context: Context,
        configUrl: String
    ): CloudControlRemoteConfigText {
        val clients = WattToolkitAcceleratedHttp.createClientPair(
            context = context,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
            followRedirects = true
        )

        if (UpdateSource.isMirrorableGithubUrl(configUrl)) {
            val preferredSource = UpdateMirrorManager.current(context)
            val bypassAcceleratedLinks = NetworkAccelerationPolicy.shouldBypassAcceleratedLinks(context)
            return GithubMirrorFallback.run(
                preferredUserSource = preferredSource,
                bypassAcceleratedLinks = bypassAcceleratedLinks
            ) { source ->
                val requestUrl = source.buildUrl(configUrl)
                CloudControlRemoteConfigText(
                    sourceDisplayName = source.displayName,
                    requestUrl = requestUrl,
                    rawText = requestText(
                        client = clients.pick(source.usesGithubAcceleration),
                        requestUrl = requestUrl
                    )
                )
            }.value
        }

        return CloudControlRemoteConfigText(
            sourceDisplayName = "Direct",
            requestUrl = configUrl,
            rawText = requestText(
                client = clients.plainClient,
                requestUrl = configUrl
            )
        )
    }

    private fun requestText(
        client: OkHttpClient,
        requestUrl: String
    ): String {
        val request = Request.Builder()
            .url(requestUrl)
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw response.toGithubMirrorHttpException()
            }
            return response.body.bytes().toString(StandardCharsets.UTF_8)
        }
    }

    private fun normalizeHeartbeatIntervalSeconds(value: Int): Int =
        value.coerceIn(
            MIN_HEARTBEAT_INTERVAL_SECONDS,
            MAX_HEARTBEAT_INTERVAL_SECONDS
        )

    private fun normalizeEasyTierConnectTimeoutSeconds(value: Int): Int =
        value.coerceIn(
            MIN_EASYTIER_CONNECT_TIMEOUT_SECONDS,
            MAX_EASYTIER_CONNECT_TIMEOUT_SECONDS,
        )

    private fun normalizeEasyTierStatusPollIntervalSeconds(value: Int): Int =
        value.coerceIn(
            MIN_EASYTIER_STATUS_POLL_INTERVAL_SECONDS,
            MAX_EASYTIER_STATUS_POLL_INTERVAL_SECONDS,
        )

    private fun normalizeQqGroupNumber(
        value: String,
        fallback: String = DEFAULT_QQ_GROUP_NUMBER
    ): String {
        val normalized = value.trim()
        return if (QQ_GROUP_NUMBER_REGEX.matches(normalized)) {
            normalized
        } else {
            fallback
        }
    }

    private fun parseSteamDepotKeys(root: JSONObject): List<CloudControlSteamDepotKey> {
        val steamObject = root.optJSONObject("steam")
        val keys = ArrayList<CloudControlSteamDepotKey>()
        parseSteamDepotKeyArray(root.optJSONArray("steamDepotKeys"), keys)
        parseSteamDepotKeyArray(root.optJSONArray("depotKeys"), keys)
        parseSteamDepotKeyArray(steamObject?.optJSONArray("depotKeys"), keys)
        parseSteamDepotKeyArray(steamObject?.optJSONArray("steamDepotKeys"), keys)
        return keys.distinctBy { key -> key.appId to key.depotId }
    }

    private fun parseSteamDepotKeyArray(
        array: JSONArray?,
        output: MutableList<CloudControlSteamDepotKey>
    ) {
        if (array == null) {
            return
        }
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val appId = firstPositiveLong(
                item,
                "appId",
                "appID",
                "app_id",
                "app"
            ) ?: continue
            val depotId = firstPositiveLong(
                item,
                "depotId",
                "depotID",
                "depot_id",
                "depot"
            ) ?: continue
            val keyHex = normalizeSteamDepotKeyHex(
                firstNonBlankString(
                    item,
                    "keyHex",
                    "depotKeyHex",
                    "depot_key_hex",
                    "hex",
                    "key"
                )
            ) ?: normalizeSteamDepotKeyBase64(
                firstNonBlankString(
                    item,
                    "keyBase64",
                    "depotKeyBase64",
                    "depot_key_base64",
                    "base64"
                )
            ) ?: continue
            output += CloudControlSteamDepotKey(
                appId = appId,
                depotId = depotId,
                keyHex = keyHex
            )
        }
    }

    private fun firstPositiveLong(
        json: JSONObject?,
        vararg names: String
    ): Long? {
        if (json == null) {
            return null
        }
        for (name in names) {
            val value = optionalPositiveLong(json, name)
            if (value != null) {
                return value
            }
        }
        return null
    }

    private fun optionalPositiveLong(json: JSONObject, name: String): Long? {
        if (!json.has(name)) {
            return null
        }
        val rawValue = json.opt(name) ?: return null
        val parsed = when (rawValue) {
            is Number -> rawValue.toLong()
            is String -> rawValue.trim().toLongOrNull()
            else -> null
        }
        return parsed?.takeIf { it > 0L }
    }

    private fun normalizeSteamDepotKeyHex(value: String?): String? {
        val normalized = value
            ?.trim()
            ?.removePrefix("0x")
            ?.removePrefix("0X")
            ?.filterNot(Char::isWhitespace)
            ?.lowercase(Locale.ROOT)
            ?: return null
        return normalized.takeIf { STEAM_DEPOT_KEY_HEX_REGEX.matches(it) }
    }

    private fun normalizeSteamDepotKeyBase64(value: String?): String? =
        runCatching {
            val decoded = Base64.getDecoder().decode(value?.trim().orEmpty())
            decoded.takeIf { it.size == STEAM_DEPOT_KEY_BYTES }?.toHexString()
        }.getOrNull()

    internal fun decodeSteamDepotKeyHex(value: String): ByteArray? {
        val normalized = normalizeSteamDepotKeyHex(value) ?: return null
        return ByteArray(STEAM_DEPOT_KEY_BYTES) { index ->
            normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun ByteArray.toHexString(): String {
        val chars = CharArray(size * 2)
        for (index in indices) {
            val unsigned = this[index].toInt() and 0xff
            chars[index * 2] = HEX_DIGITS[unsigned ushr 4]
            chars[index * 2 + 1] = HEX_DIGITS[unsigned and 0x0f]
        }
        return String(chars)
    }

    private fun normalizeHttpUrl(value: String?): String? {
        val normalized = value?.trim().orEmpty()
        if (normalized.isEmpty()) {
            return null
        }
        if (normalized.startsWith("/")) {
            return BuildConfig.FEEDBACK_BASE_URL.trim().trimEnd('/') + normalized
        }
        val parsed = try {
            URL(normalized)
        } catch (_: Throwable) {
            return null
        }
        return when (parsed.protocol.lowercase()) {
            "http", "https" -> normalized
            else -> null
        }
    }

    internal fun normalizeLocalTestOnlineServiceBaseUrl(value: String?): String? {
        val normalized = value?.trim().orEmpty()
        if (normalized.isEmpty()) {
            return null
        }
        val parsed = try {
            URI(normalized)
        } catch (_: Throwable) {
            return null
        }
        val scheme = parsed.scheme?.lowercase(Locale.ROOT)
        if (scheme !in setOf("http", "https") || parsed.host.isNullOrBlank()) {
            return null
        }
        if (parsed.userInfo != null || parsed.query != null || parsed.fragment != null) {
            return null
        }
        return URI(
            scheme,
            null,
            parsed.host,
            parsed.port,
            parsed.path?.trimEnd('/').orEmpty(),
            null,
            null,
        ).toString()
    }

    private fun normalizeEndpointUrl(value: String?): String? {
        val normalized = value?.trim().orEmpty()
        if (normalized.isEmpty()) {
            return null
        }
        val parsed = try {
            URI(normalized)
        } catch (_: Throwable) {
            return null
        }
        val scheme = parsed.scheme?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return if (scheme.isNotEmpty() && !parsed.host.isNullOrBlank()) {
            normalized
        } else {
            null
        }
    }

    private fun normalizeEasyTierDefaultMode(value: String): String {
        return when (value.trim().lowercase(Locale.ROOT)) {
            "room" -> "room"
            "community", "shared", "shared-community" -> "community"
            else -> DEFAULT_EASYTIER_DEFAULT_MODE
        }
    }

    private fun normalizeHeartbeatWsUrl(
        value: String,
        fallback: String = defaultHeartbeatWsUrl()
    ): String {
        val normalized = value.trim()
        if (normalized.isEmpty()) {
            return fallback
        }
        if (normalized.startsWith("/")) {
            return httpUrlToWebSocketUrl(BuildConfig.FEEDBACK_BASE_URL.trim().trimEnd('/') + normalized)
        }
        val scheme = try {
            URI(normalized).scheme?.lowercase().orEmpty()
        } catch (_: Throwable) {
            return fallback
        }
        return when (scheme) {
            "ws", "wss" -> if (hasNetworkHost(normalized)) normalized else fallback
            "http", "https" -> normalizeHttpUrl(normalized)
                ?.let(::httpUrlToWebSocketUrl)
                ?: fallback
            else -> fallback
        }
    }

    private fun hasNetworkHost(value: String): Boolean =
        try {
            !URI(value).host.isNullOrBlank()
        } catch (_: Throwable) {
            false
        }

    private fun httpUrlToWebSocketUrl(value: String): String =
        when {
            value.startsWith("https://", ignoreCase = true) ->
                "wss://" + value.substringAfter("://")
            value.startsWith("http://", ignoreCase = true) ->
                "ws://" + value.substringAfter("://")
            else -> value
        }

    private fun firstPositiveInt(
        root: JSONObject,
        nested: JSONObject?,
        vararg names: String
    ): Int? {
        for (name in names) {
            val rootValue = optionalPositiveInt(root, name)
            if (rootValue != null) {
                return rootValue
            }
            val nestedValue = if (nested != null) {
                optionalPositiveInt(nested, name)
            } else {
                null
            }
            if (nestedValue != null) {
                return nestedValue
            }
        }
        return null
    }

    private fun optionalPositiveInt(json: JSONObject, name: String): Int? {
        if (!json.has(name)) {
            return null
        }
        val rawValue = json.opt(name) ?: return null
        val parsed = when (rawValue) {
            is Number -> rawValue.toInt()
            is String -> rawValue.trim().toIntOrNull()
            else -> null
        }
        return parsed?.takeIf { it > 0 }
    }

    private fun firstPositiveInt(
        json: JSONObject?,
        vararg names: String,
    ): Int? {
        if (json == null) {
            return null
        }
        for (name in names) {
            val value = optionalPositiveInt(json, name)
            if (value != null) {
                return value
            }
        }
        return null
    }

    private fun firstNonBlankString(
        root: JSONObject,
        nested: JSONObject?,
        vararg names: String
    ): String? {
        for (name in names) {
            val rootValue = optionalNonBlankString(root, name)
            if (rootValue != null) {
                return rootValue
            }
            val nestedValue = if (nested != null) {
                optionalNonBlankString(nested, name)
            } else {
                null
            }
            if (nestedValue != null) {
                return nestedValue
            }
        }
        return null
    }

    private fun firstNonBlankString(
        json: JSONObject?,
        vararg names: String
    ): String? {
        if (json == null) {
            return null
        }
        for (name in names) {
            val value = optionalNonBlankString(json, name)
            if (value != null) {
                return value
            }
        }
        return null
    }

    private fun optionalNonBlankString(json: JSONObject, name: String): String? {
        if (!json.has(name)) {
            return null
        }
        return json.optString(name).trim().ifEmpty { null }
    }

    private fun firstBoolean(
        root: JSONObject,
        nested: JSONObject?,
        rootNames: Array<String>,
        nestedNames: Array<String> = rootNames,
    ): Boolean? {
        for (name in rootNames) {
            val value = optionalBoolean(root, name)
            if (value != null) {
                return value
            }
        }
        if (nested != null) {
            for (name in nestedNames) {
                val value = optionalBoolean(nested, name)
                if (value != null) {
                    return value
                }
            }
        }
        return null
    }

    private fun optionalBoolean(json: JSONObject, name: String): Boolean? {
        if (!json.has(name)) {
            return null
        }
        return when (val rawValue = json.opt(name)) {
            is Boolean -> rawValue
            is Number -> rawValue.toInt() != 0
            is String -> when (rawValue.trim().lowercase(Locale.ROOT)) {
                "true", "1", "yes", "on" -> true
                "false", "0", "no", "off" -> false
                else -> null
            }
            else -> null
        }
    }

    private fun parseJsonObject(text: String): JSONObject? {
        val normalized = text.trim()
        if (normalized.isEmpty()) {
            return null
        }
        return try {
            JSONTokener(normalized).nextValue() as? JSONObject
        } catch (_: Throwable) {
            null
        }
    }

}
