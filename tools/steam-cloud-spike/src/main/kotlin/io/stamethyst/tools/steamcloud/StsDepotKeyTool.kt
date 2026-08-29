package io.stamethyst.tools.steamcloud

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import top.apricityx.workshop.steam.protocol.OkHttpSteamCmSession
import top.apricityx.workshop.steam.protocol.SteamAccountSession
import top.apricityx.workshop.steam.protocol.SteamAuthSessionDetails
import top.apricityx.workshop.steam.protocol.SteamAuthenticationClient
import top.apricityx.workshop.steam.protocol.SteamCredentialAuthSession
import top.apricityx.workshop.steam.protocol.SteamDirectoryClient
import top.apricityx.workshop.steam.protocol.SteamGuardChallenge
import top.apricityx.workshop.steam.protocol.SteamGuardChallengeType
import top.apricityx.workshop.steam.protocol.SteamPacketCodec
import top.apricityx.workshop.steam.protocol.SteamProtocolException
import top.apricityx.workshop.steam.protocol.newDefaultOkHttpClient
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserverUserstats
import `in`.dragonbra.javasteam.types.KeyValue
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.Base64
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.system.exitProcess

private const val DEFAULT_STS_APP_ID = 646570u
private const val DEFAULT_STS_DESKTOP_DEPOT_ID = 877621u
private const val DEFAULT_LOGIN_TIMEOUT_MINUTES = 5L
private const val DEFAULT_DESKTOP_SESSION_FILE = "agent-tmp/steam-desktop-session.env"

fun main(args: Array<String>) {
    val parsed = ParsedArgs.parse(args)
    if (parsed.help) {
        printUsage()
        return
    }

    runCatching {
        runBlocking {
            when (parsed.command) {
                ToolCommand.DepotKey -> StsDepotKeyTool(parsed).run()
                ToolCommand.RefreshToken -> SteamRefreshTokenTool(parsed).run()
                ToolCommand.AchievementUnlock -> SteamAchievementMutationTool(parsed, AchievementMutation.Unlock).run()
                ToolCommand.AchievementLock -> SteamAchievementMutationTool(parsed, AchievementMutation.Lock).run()
            }
        }
    }.onFailure { error ->
        System.err.println("Failed to ${parsed.command.failureDescription}: ${error.message ?: error::class.java.name}")
        if (parsed.debug) {
            error.printStackTrace(System.err)
        }
        exitProcess(1)
    }
}

private enum class ToolCommand(
    val failureDescription: String,
) {
    DepotKey("fetch Steam depot key"),
    RefreshToken("retrieve Steam refresh token"),
    AchievementUnlock("run the restricted Steam achievement test"),
    AchievementLock("run the restricted Steam achievement lock test"),
}

private class StsDepotKeyTool(
    private val args: ParsedArgs,
) {
    suspend fun run() {
        val envFileValues = readCredentialEnvFiles(args)
        val merged = MergedConfig(args, envFileValues)
        val appId = merged.uint("app-id", "STS_STEAM_APP_ID") ?: DEFAULT_STS_APP_ID
        val depotId = merged.uint("depot-id", "STS_STEAM_DEPOT_ID") ?: DEFAULT_STS_DESKTOP_DEPOT_ID
        val output = merged.outputPath(appId, depotId)
        val debugLogger: ((String) -> Unit)? = if (args.debug) {
            { line -> System.err.println(line) }
        } else {
            null
        }

        val client = buildHttpClient(merged.proxyUrl())
        val directoryClient = SteamDirectoryClient(client)
        val account = merged.accountSession(directoryClient, client, debugLogger)
        val key = OkHttpSteamCmSession(client).use { session ->
            val servers = directoryClient.loadServers()
            session.connectWithRefreshToken(servers, account)
            session.requestDepotDecryptionKey(appId, depotId)
        }

        val hexKey = key.toHex()
        val base64Key = Base64.getEncoder().encodeToString(key)
        val result = DepotKeyResult(
            appId = appId,
            depotId = depotId,
            account = account,
            depotKeyHex = hexKey,
            depotKeyBase64 = base64Key,
            guardData = merged.guardData,
        )

        if (output != null) {
            output.parent?.let(Files::createDirectories)
            output.writeText(result.toEnvFile(), Charsets.UTF_8)
            println("Depot key written to: $output")
        }

        println("appId=$appId depotId=$depotId account=${account.accountName} steamId64=${account.steamId}")
        if (args.printKey) {
            println("depotKeyHex=$hexKey")
            println("depotKeyBase64=$base64Key")
        } else {
            println("Depot key fetched. Re-run with --print-key if you need to print the secret to the terminal.")
        }
    }
}

private class SteamRefreshTokenTool(
    private val args: ParsedArgs,
) {
    suspend fun run() {
        val envFileValues = readCredentialEnvFiles(args)
        val merged = MergedConfig(args, envFileValues)
        val debugLogger: ((String) -> Unit)? = if (args.debug) {
            { line -> System.err.println(line) }
        } else {
            null
        }
        val client = buildHttpClient(merged.proxyUrl())
        val account = merged.accountSession(SteamDirectoryClient(client), client, debugLogger)
        val result = RefreshTokenResult(account, merged.guardData)
        val output = merged.refreshTokenOutputPath(account.steamId)

        if (output != null) {
            writeSensitiveEnvFile(output, result.toEnvFile())
            println("Refresh token written to: $output")
        }
        println("account=${account.accountName} steamId64=${account.steamId}")
        if (args.printToken) {
            println("refreshToken=${account.refreshToken}")
        } else {
            println("Refresh token acquired. Re-run with --print-token only when terminal output is required.")
        }
    }
}

private enum class AchievementMutation(
    val commandLabel: String,
    val confirmationFlag: String,
    val targetBitSet: Boolean,
) {
    Unlock("achievementUnlock", "--confirm-shrug-it-off", true),
    Lock("achievementLock", "--confirm-lock-shrug-it-off", false),
}

private class SteamAchievementMutationTool(
    private val args: ParsedArgs,
    private val mutation: AchievementMutation,
) {
    suspend fun run() {
        require(args.isConfirmed(mutation) || args.inspectAchievementSchema) {
            "Refusing achievement mutation without ${mutation.confirmationFlag}."
        }
        val envFileValues = readCredentialEnvFiles(args)
        val merged = MergedConfig(args, envFileValues)
        val client = buildHttpClient(merged.proxyUrl())
        val directoryClient = SteamDirectoryClient(client)
        val account = merged.accountSession(directoryClient, client, debugLogger = null)

        OkHttpSteamCmSession(client).use { session ->
            session.connectWithRefreshToken(directoryClient.loadServers(), account)
            println("${mutation.commandLabel}.stage=initial_read")
            val initial = getUserStats(session, account.steamId)
            if (args.inspectAchievementSchema) {
                println("achievementSchemaInspection=completed steamId64=${account.steamId}")
                return
            }
            val target = initial.schemaAchievementStats[SHRUG_IT_OFF_API_NAME]
                ?: throw IllegalStateException(
                    "Steam CM schema does not define $SHRUG_IT_OFF_API_NAME. " +
                        "Re-run with --inspect-achievement-schema to perform a read-only schema inspection.",
                )

            // Steam omits untouched/default-valued stats from GetUserStats. Preserve all unrelated
            // bits and modify only the explicitly confirmed achievement bit.
            val currentValue = initial.statValues[target.statId] ?: 0
            val requestedValue = if (mutation.targetBitSet) {
                currentValue or target.mask
            } else {
                currentValue and target.mask.inv()
            }
            if (requestedValue == currentValue) {
                val status = if (mutation.targetBitSet) "target_bit_already_set" else "target_bit_already_clear"
                println("achievement=$SHRUG_IT_OFF_API_NAME status=$status steamId64=${account.steamId}")
                return
            }

            println(
                "${mutation.commandLabel}.stage=store_request statId=${target.statId} " +
                    "previousValue=$currentValue requestedValue=$requestedValue",
            )
            val storeResponse = storeUserStat(
                session = session,
                steamId = account.steamId,
                crcStats = initial.crcStats,
                statId = target.statId,
                statValue = requestedValue,
            )
            println("${mutation.commandLabel}.stage=store_response")
            require(!storeResponse.hasEresult() || storeResponse.eresult == EResult.OK.code()) {
                "Steam CM StoreUserStats failed: ${storeResponse.eresult}"
            }
            require(!storeResponse.statsOutOfDate) {
                "Steam CM StoreUserStats rejected stale statistics."
            }
            require(storeResponse.statsFailedValidationCount == 0) {
                "Steam CM StoreUserStats validation failed for stat ${storeResponse.getStatsFailedValidation(0).statId}."
            }

            println("${mutation.commandLabel}.stage=verification_read")
            val verified = getUserStats(session, account.steamId)
            val targetBitSet = (verified.statValues[target.statId] ?: 0) and target.mask != 0
            require(targetBitSet == mutation.targetBitSet) {
                "Steam CM accepted the write but did not confirm the $SHRUG_IT_OFF_API_NAME stat bit state."
            }
            val status = if (targetBitSet) "target_bit_confirmed" else "target_bit_clear_confirmed"
            println("achievement=$SHRUG_IT_OFF_API_NAME status=$status steamId64=${account.steamId}")
        }
    }

    private suspend fun getUserStats(
        session: OkHttpSteamCmSession,
        steamId: Long,
    ): UserStatsSnapshot {
        val response = withTimeout(CM_REQUEST_TIMEOUT_MS) {
            session.sendClientMessage(
                SteamPacketCodec.emsgClientGetUserStats,
                SteammessagesClientserverUserstats.CMsgClientGetUserStats.newBuilder()
                    .setGameId(STS_APP_ID)
                    .setSteamIdForUser(steamId)
                    .setSchemaLocalVersion(0)
                    .setCrcStats(0)
                    .build(),
                SteamPacketCodec.emsgClientGetUserStatsResponse,
                SteammessagesClientserverUserstats.CMsgClientGetUserStatsResponse.parser(),
                STS_APP_ID.toUInt(),
            )
        }
        require(!response.hasEresult() || response.eresult == EResult.OK.code()) {
            "Steam CM GetUserStats failed: ${response.eresult}"
        }
        return UserStatsSnapshot(
            crcStats = response.crcStats,
            schemaAchievementStats = parseAchievementStats(response.schema.toByteArray(), args.inspectAchievementSchema),
            statValues = response.statsList.associate { stat -> stat.statId to stat.statValue },
        )
    }

    private suspend fun storeUserStat(
        session: OkHttpSteamCmSession,
        steamId: Long,
        crcStats: Int,
        statId: Int,
        statValue: Int,
    ): SteammessagesClientserverUserstats.CMsgClientStoreUserStatsResponse = withTimeout(CM_REQUEST_TIMEOUT_MS) {
        val stat = SteammessagesClientserverUserstats.CMsgClientStoreUserStats2.Stats
            .newBuilder()
            .setStatId(statId)
            .setStatValue(statValue)
            .build()
        session.sendClientMessage(
            SteamPacketCodec.emsgClientStoreUserStats2,
            SteammessagesClientserverUserstats.CMsgClientStoreUserStats2.newBuilder()
                .setGameId(STS_APP_ID)
                .setSettorSteamId(steamId)
                .setSetteeSteamId(steamId)
                .setCrcStats(crcStats)
                .setExplicitReset(false)
                .addStats(stat)
                .build(),
            SteamPacketCodec.emsgClientStoreUserStatsResponse,
            SteammessagesClientserverUserstats.CMsgClientStoreUserStatsResponse.parser(),
            STS_APP_ID.toUInt(),
        )
    }
}

private data class UserStatsSnapshot(
    val crcStats: Int,
    val schemaAchievementStats: Map<String, AchievementStatTarget>,
    val statValues: Map<Int, Int>,
)

private data class AchievementStatTarget(
    val statId: Int,
    val bitIndex: Int,
) {
    init {
        require(bitIndex in 0..30) { "Achievement bit index must fit a signed stat value." }
    }

    val mask: Int = 1 shl bitIndex
}

private class MergedConfig(
    private val args: ParsedArgs,
    private val envFileValues: Map<String, String>,
) {
    var guardData: String? = value("guard-data", "STEAM_GUARD_DATA")
        private set

    suspend fun accountSession(
        directoryClient: SteamDirectoryClient,
        client: OkHttpClient,
        debugLogger: ((String) -> Unit)?,
    ): SteamAccountSession {
        val refreshToken = if (args.reauthenticate) null else value("refresh-token", "STEAM_REFRESH_TOKEN")
        if (!refreshToken.isNullOrBlank()) {
            val accountName = value("account-name", "STEAM_ACCOUNT_NAME")
                ?: value("username", "STEAM_USERNAME")
                ?: promptLine("Steam account name: ")
            val steamId = long("steam-id", "STEAM_STEAM_ID64")
                ?: refreshToken.extractSteamId64()
                ?: promptLine("SteamID64: ").toLongOrNull()
                ?: throw IllegalArgumentException("SteamID64 is required when using a refresh token")
            return SteamAccountSession(
                accountName = accountName,
                steamId = steamId,
                refreshToken = refreshToken,
            )
        }

        val username = value("username", "STEAM_USERNAME") ?: promptLine("Steam username: ")
        val password = value("password", "STEAM_PASSWORD") ?: promptSecret("Steam password: ")
        val authClient = SteamAuthenticationClient(
            directoryClient = directoryClient,
            sessionFactory = { OkHttpSteamCmSession(client) },
        )
        val result = withTimeout(args.loginTimeoutMinutes * 60_000L) {
            val authSession = authClient.beginAuthSession(
                details = SteamAuthSessionDetails(
                    username = username,
                    password = password,
                    guardData = guardData,
                    isPersistentSession = true,
                ),
                debugLogger = debugLogger,
            )
            authSession.useSuspending {
                completeSteamGuardIfNeeded(authSession)
                authSession.awaitResult()
            }
        }

        guardData = result.newGuardData ?: guardData
        return SteamAccountSession(
            accountName = result.accountName,
            steamId = result.steamId,
            refreshToken = result.refreshToken,
        )
    }

    fun uint(optionName: String, envName: String): UInt? =
        value(optionName, envName)?.toUIntOrNull()

    fun long(optionName: String, envName: String): Long? =
        value(optionName, envName)?.toLongOrNull()

    fun outputPath(appId: UInt, depotId: UInt): Path? {
        if (args.noOutput) {
            return null
        }
        val raw = args.options["output"]
            ?: envFileValues["STS_DEPOT_KEY_OUTPUT"]
            ?: "agent-tmp/steam-depot-key-$appId-$depotId.env"
        return Path.of(raw)
    }

    fun refreshTokenOutputPath(steamId: Long): Path? {
        if (args.noOutput) {
            return null
        }
        val raw = args.options["output"]
            ?: envFileValues["STS_REFRESH_TOKEN_OUTPUT"]
            ?: DEFAULT_DESKTOP_SESSION_FILE
        return Path.of(raw)
    }

    fun proxyUrl(): String? =
        args.options["proxy-url"]
            ?: System.getenv("STEAM_PROXY_URL")
            ?: System.getenv("HTTPS_PROXY")
            ?: System.getenv("HTTP_PROXY")

    private suspend fun completeSteamGuardIfNeeded(authSession: SteamCredentialAuthSession) {
        val challenges = authSession.challenges
        if (challenges.isEmpty() || challenges.any { it.type == SteamGuardChallengeType.None }) {
            return
        }

        val deviceCode = challenges.firstOrNull { it.type == SteamGuardChallengeType.DeviceCode }
        if (deviceCode != null) {
            val code = value("2fa-code", "STEAM_2FA_CODE")
                ?: promptLine(deviceCode.prompt("Steam mobile authenticator code: "))
            authSession.submitGuardCode(SteamGuardChallengeType.DeviceCode, code.trim())
            return
        }

        val emailCode = challenges.firstOrNull { it.type == SteamGuardChallengeType.EmailCode }
        if (emailCode != null) {
            val code = value("email-code", "STEAM_EMAIL_CODE")
                ?: promptLine(emailCode.prompt("Steam email guard code: "))
            authSession.submitGuardCode(SteamGuardChallengeType.EmailCode, code.trim())
            return
        }

        if (challenges.any { it.type == SteamGuardChallengeType.DeviceConfirmation }) {
            println("Approve this login in the Steam mobile app, then wait for polling to finish.")
            return
        }

        if (challenges.any { it.type == SteamGuardChallengeType.EmailConfirmation }) {
            println("Complete the Steam email confirmation, then wait for polling to finish.")
            return
        }

        throw SteamProtocolException(
            "Unsupported Steam Guard challenge(s): ${challenges.joinToString { it.type.name }}",
        )
    }

    private fun value(optionName: String, envName: String): String? =
        args.options[optionName]
            ?: System.getenv(envName)
            ?: envFileValues[envName]
}

private data class DepotKeyResult(
    val appId: UInt,
    val depotId: UInt,
    val account: SteamAccountSession,
    val depotKeyHex: String,
    val depotKeyBase64: String,
    val guardData: String?,
) {
    fun toEnvFile(): String = buildString {
        appendLine("# Sensitive Steam credentials. Do not commit or share this file.")
        appendEnv("STEAM_ACCOUNT_NAME", account.accountName)
        appendEnv("STEAM_STEAM_ID64", account.steamId.toString())
        appendEnv("STEAM_REFRESH_TOKEN", account.refreshToken)
        if (!guardData.isNullOrBlank()) {
            appendEnv("STEAM_GUARD_DATA", guardData)
        }
        appendEnv("STS_STEAM_APP_ID", appId.toString())
        appendEnv("STS_STEAM_DEPOT_ID", depotId.toString())
        appendEnv("STS_DEPOT_KEY_${appId}_${depotId}_HEX", depotKeyHex)
        appendEnv("STS_DEPOT_KEY_${appId}_${depotId}_BASE64", depotKeyBase64)
    }
}

private data class RefreshTokenResult(
    val account: SteamAccountSession,
    val guardData: String?,
) {
    fun toEnvFile(): String = buildString {
        appendLine("# Sensitive Steam credentials. Do not commit or share this file.")
        appendEnv("STEAM_ACCOUNT_NAME", account.accountName)
        appendEnv("STEAM_STEAM_ID64", account.steamId.toString())
        appendEnv("STEAM_REFRESH_TOKEN", account.refreshToken)
        if (!guardData.isNullOrBlank()) {
            appendEnv("STEAM_GUARD_DATA", guardData)
        }
    }
}

private data class ParsedArgs(
    val command: ToolCommand,
    val options: Map<String, String>,
    val help: Boolean,
    val debug: Boolean,
    val printKey: Boolean,
    val printToken: Boolean,
    val confirmShrugItOff: Boolean,
    val confirmLockShrugItOff: Boolean,
    val inspectAchievementSchema: Boolean,
    val reauthenticate: Boolean,
    val noOutput: Boolean,
    val envFile: Path?,
    val loginTimeoutMinutes: Long,
) {
    companion object {
        fun parse(args: Array<String>): ParsedArgs {
            val options = linkedMapOf<String, String>()
            val flags = mutableSetOf<String>()
            var command = ToolCommand.DepotKey
            var index = if (args.firstOrNull()?.startsWith("--") != false) {
                0
            } else {
                command = when (args[0]) {
                    "depotKey" -> ToolCommand.DepotKey
                    "refreshToken" -> ToolCommand.RefreshToken
                    "achievementUnlock" -> ToolCommand.AchievementUnlock
                    "achievementLock" -> ToolCommand.AchievementLock
                    else -> throw IllegalArgumentException("Unknown command: ${args[0]}")
                }
                1
            }
            while (index < args.size) {
                val token = args[index]
                if (!token.startsWith("--")) {
                    throw IllegalArgumentException("Unexpected argument: $token")
                }
                val withoutPrefix = token.removePrefix("--")
                val equalsIndex = withoutPrefix.indexOf('=')
                if (equalsIndex >= 0) {
                    options[withoutPrefix.substring(0, equalsIndex)] = withoutPrefix.substring(equalsIndex + 1)
                    index += 1
                    continue
                }
                val name = withoutPrefix
                if (name in booleanFlags) {
                    flags += name
                    index += 1
                    continue
                }
                val value = args.getOrNull(index + 1)
                    ?: throw IllegalArgumentException("Missing value for --$name")
                options[name] = value
                index += 2
            }

            val envFile = options["env-file"]
                ?: System.getenv("STS_DEPOT_KEY_ENV_FILE")
            return ParsedArgs(
                command = command,
                options = options,
                help = "help" in flags || "h" in flags,
                debug = "debug" in flags,
                printKey = "print-key" in flags,
                printToken = "print-token" in flags,
                confirmShrugItOff = "confirm-shrug-it-off" in flags,
                confirmLockShrugItOff = "confirm-lock-shrug-it-off" in flags,
                inspectAchievementSchema = "inspect-achievement-schema" in flags,
                reauthenticate = "reauthenticate" in flags,
                noOutput = "no-output" in flags,
                envFile = envFile?.let { Path.of(it) },
                loginTimeoutMinutes = options["login-timeout-minutes"]?.toLongOrNull()
                    ?: DEFAULT_LOGIN_TIMEOUT_MINUTES,
            )
        }

        private val booleanFlags = setOf(
            "help",
            "h",
            "debug",
            "print-key",
            "print-token",
            "confirm-shrug-it-off",
            "confirm-lock-shrug-it-off",
            "inspect-achievement-schema",
            "reauthenticate",
            "no-output",
        )
    }

    fun isConfirmed(mutation: AchievementMutation): Boolean = when (mutation) {
        AchievementMutation.Unlock -> confirmShrugItOff
        AchievementMutation.Lock -> confirmLockShrugItOff
    }
}

private fun SteamGuardChallenge.prompt(fallback: String): String {
    val message = message?.takeIf(String::isNotBlank)
    return if (message == null) {
        fallback
    } else {
        "$fallback [$message] "
    }
}

private suspend fun <T> SteamCredentialAuthSession.useSuspending(block: suspend () -> T): T =
    try {
        block()
    } finally {
        close()
    }

private fun readEnvFile(path: Path): Map<String, String> {
    if (!path.exists()) {
        throw IllegalArgumentException("Env file does not exist: $path")
    }
    return path.readLines(Charsets.UTF_8)
        .asSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) {
                null
            } else {
                line.substring(0, separator) to line.substring(separator + 1)
            }
        }
        .toMap()
}

private fun readCredentialEnvFiles(args: ParsedArgs): Map<String, String> {
    val values = linkedMapOf<String, String>()
    val defaultSession = Path.of(DEFAULT_DESKTOP_SESSION_FILE)
    if (defaultSession.exists()) {
        values.putAll(readEnvFile(defaultSession))
    }
    args.envFile?.let { values.putAll(readEnvFile(it)) }
    return values
}

private fun promptLine(prompt: String): String {
    print(prompt)
    return readLine()?.trim().orEmpty().ifBlank {
        throw IllegalArgumentException("Required input was empty")
    }
}

private fun promptSecret(prompt: String): String {
    val console = System.console()
    if (console != null) {
        return String(console.readPassword(prompt)).ifBlank {
            throw IllegalArgumentException("Required input was empty")
        }
    }
    print("$prompt(input will be visible) ")
    return readLine()?.trim().orEmpty().ifBlank {
        throw IllegalArgumentException("Required input was empty")
    }
}

private fun String.extractSteamId64(): Long? =
    runCatching {
        val parts = split('.')
        if (parts.size < 2) {
            return@runCatching null
        }
        val payload = String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
        val match = Regex("\"(?:sub|steamid|steam_id)\"\\s*:\\s*\"?(\\d{15,20})\"?").find(payload)
        match?.groupValues?.get(1)?.toLongOrNull()
    }.getOrNull()

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun buildHttpClient(proxyUrl: String?): OkHttpClient {
    val base = newDefaultOkHttpClient()
    if (proxyUrl.isNullOrBlank()) {
        return base
    }
    return base.newBuilder()
        .proxy(proxyUrl.toProxy())
        .build()
}

private fun String.toProxy(): Proxy {
    val uri = URI.create(this)
    val host = uri.host ?: throw IllegalArgumentException("Proxy URL is missing host: $this")
    val port = when {
        uri.port > 0 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        uri.scheme.equals("http", ignoreCase = true) -> 80
        uri.scheme.equals("socks", ignoreCase = true) -> 1080
        uri.scheme.equals("socks5", ignoreCase = true) -> 1080
        else -> throw IllegalArgumentException("Unsupported proxy scheme: ${uri.scheme}")
    }
    val type = when {
        uri.scheme.equals("socks", ignoreCase = true) -> Proxy.Type.SOCKS
        uri.scheme.equals("socks5", ignoreCase = true) -> Proxy.Type.SOCKS
        uri.scheme.equals("http", ignoreCase = true) -> Proxy.Type.HTTP
        uri.scheme.equals("https", ignoreCase = true) -> Proxy.Type.HTTP
        else -> throw IllegalArgumentException("Unsupported proxy scheme: ${uri.scheme}")
    }
    return Proxy(type, InetSocketAddress(host, port))
}

private fun StringBuilder.appendEnv(key: String, value: String) {
    append(key)
    append('=')
    appendLine(value)
}

private fun writeSensitiveEnvFile(path: Path, contents: String) {
    path.parent?.let(Files::createDirectories)
    Files.writeString(path, contents, Charsets.UTF_8)
    runCatching {
        Files.setPosixFilePermissions(
            path,
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        )
    }
}

private fun parseAchievementStats(
    schema: ByteArray,
    inspectTarget: Boolean,
): Map<String, AchievementStatTarget> {
    if (schema.isEmpty()) return emptyMap()
    val root = KeyValue()
    if (!root.tryReadAsBinary(schema.inputStream())) return emptyMap()
    val result = linkedMapOf<String, AchievementStatTarget>()

    fun collect(node: KeyValue, insideAchievements: Boolean, path: List<String>) {
        val nodeName = node.name.orEmpty()
        val isAchievementsContainer = nodeName.equals("achievements", ignoreCase = true)
        val currentPath = path + nodeName
        if (nodeName.equals("achievement", ignoreCase = true) || insideAchievements) {
            val name = node.get("name")
            val id = node.get("id")
            val achievementId = if (id == KeyValue.INVALID) nodeName.toIntOrNull() else id.asInteger(-1)
            if (achievementId != null && achievementId >= 0 && name != KeyValue.INVALID) {
                name.asString().trim().takeIf(String::isNotEmpty)?.let { apiName ->
                    result[apiName.lowercase()] = AchievementStatTarget(achievementId, 0)
                }
            }
        }
        val name = node.get("name")
        val statsIndex = currentPath.indexOfLast { it.equals("stats", ignoreCase = true) }
        val bitsIndex = currentPath.indexOfLast { it.equals("bits", ignoreCase = true) }
        if (name != KeyValue.INVALID && statsIndex >= 0 && bitsIndex > statsIndex && bitsIndex + 1 < currentPath.size) {
            val statId = currentPath.getOrNull(statsIndex + 1)?.toIntOrNull()
            val bitIndex = currentPath.getOrNull(bitsIndex + 1)?.toIntOrNull()
            val apiName = name.asString().orEmpty().trim()
            if (statId != null && bitIndex != null && bitIndex in 0..30 && apiName.isNotEmpty()) {
                result[apiName.lowercase()] = AchievementStatTarget(statId, bitIndex)
            }
        }
        node.children.forEach { child -> collect(child, isAchievementsContainer, currentPath) }
    }

    collect(root, false, emptyList())
    if (inspectTarget) {
        printAchievementSchemaTargetPaths(root)
    }
    return result
}

private fun printAchievementSchemaTargetPaths(root: KeyValue) {
    val matches = mutableListOf<String>()

    fun collect(node: KeyValue, parentPath: String) {
        val nodeName = node.name.orEmpty()
        val path = if (parentPath.isEmpty()) nodeName else "$parentPath/$nodeName"
        val value = runCatching { node.asString().orEmpty() }.getOrDefault("")
        if (
            nodeName.contains("shrug", ignoreCase = true) ||
            value.contains(SHRUG_IT_OFF_API_NAME, ignoreCase = true)
        ) {
            matches += "$path value=${value.take(160)} children=${node.children.size}"
        }
        node.children.forEach { child -> collect(child, path) }
    }

    collect(root, "")
    println("achievementSchemaTargetMatches=${matches.size}")
    matches.forEach(::println)
}

private const val STS_APP_ID = 646570L
private const val SHRUG_IT_OFF_API_NAME = "shrug_it_off"
private const val CM_REQUEST_TIMEOUT_MS = 30_000L

private fun printUsage() {
    println(
        """
        Usage:
          .\gradlew.bat :tools:steam-cloud-spike:depotKey --args="--app-id 646570 --depot-id 877621"
          .\gradlew.bat :tools:steam-cloud-spike:refreshToken
          .\gradlew.bat :tools:steam-cloud-spike:achievementUnlock --args="--confirm-shrug-it-off"
          .\gradlew.bat :tools:steam-cloud-spike:achievementLock --args="--confirm-lock-shrug-it-off"

        Defaults:
          --app-id 646570
          --depot-id 877621
          --output agent-tmp/steam-depot-key-<app>-<depot>.env
          refreshToken output: agent-tmp/steam-desktop-session.env

        Login inputs:
          --username <name>              or STEAM_USERNAME
          --password <password>          or STEAM_PASSWORD
          --guard-data <data>            or STEAM_GUARD_DATA
          --2fa-code <code>              or STEAM_2FA_CODE
          --email-code <code>            or STEAM_EMAIL_CODE

        Refresh-token inputs:
          --env-file <path>              or STS_DEPOT_KEY_ENV_FILE, read a previous output file
          --account-name <name>          or STEAM_ACCOUNT_NAME
          --steam-id <steamid64>         or STEAM_STEAM_ID64
          --refresh-token <token>        or STEAM_REFRESH_TOKEN

        Output options:
          --output <path>
          --proxy-url <url>              or STEAM_PROXY_URL / HTTPS_PROXY / HTTP_PROXY
          --no-proxy                     force direct connections and ignore proxy environment variables
          --print-key                    also print depot key to terminal
          --print-token                  also print refresh token to terminal
           --reauthenticate               ignore the saved desktop session and sign in again
           --confirm-shrug-it-off         required for the one experimental achievement mutation
           --confirm-lock-shrug-it-off    required to clear the one experimental achievement bit
          --inspect-achievement-schema   print read-only schema paths containing shrug_it_off
          --no-output                    do not write the env file
          --debug                        print protocol debug logs
        """.trimIndent(),
    )
}
