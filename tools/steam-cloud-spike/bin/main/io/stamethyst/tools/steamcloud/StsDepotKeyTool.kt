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
import top.apricityx.workshop.steam.protocol.SteamProtocolException
import top.apricityx.workshop.steam.protocol.newDefaultOkHttpClient
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.system.exitProcess

private const val DEFAULT_STS_APP_ID = 646570u
private const val DEFAULT_STS_DESKTOP_DEPOT_ID = 877621u
private const val DEFAULT_LOGIN_TIMEOUT_MINUTES = 5L

fun main(args: Array<String>) {
    val parsed = ParsedArgs.parse(args)
    if (parsed.help) {
        printUsage()
        return
    }

    runCatching {
        runBlocking {
            StsDepotKeyTool(parsed).run()
        }
    }.onFailure { error ->
        System.err.println("Failed to fetch Steam depot key: ${error.message ?: error::class.java.name}")
        if (parsed.debug) {
            error.printStackTrace(System.err)
        }
        exitProcess(1)
    }
}

private class StsDepotKeyTool(
    private val args: ParsedArgs,
) {
    suspend fun run() {
        val envFileValues = args.envFile?.let(::readEnvFile).orEmpty()
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
        val refreshToken = value("refresh-token", "STEAM_REFRESH_TOKEN")
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

private data class ParsedArgs(
    val options: Map<String, String>,
    val help: Boolean,
    val debug: Boolean,
    val printKey: Boolean,
    val noOutput: Boolean,
    val envFile: Path?,
    val loginTimeoutMinutes: Long,
) {
    companion object {
        fun parse(args: Array<String>): ParsedArgs {
            val options = linkedMapOf<String, String>()
            val flags = mutableSetOf<String>()
            var index = 0
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
                options = options,
                help = "help" in flags || "h" in flags,
                debug = "debug" in flags,
                printKey = "print-key" in flags,
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
            "no-output",
        )
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

private fun printUsage() {
    println(
        """
        Usage:
          .\gradlew.bat :tools:steam-cloud-spike:depotKey --args="--app-id 646570 --depot-id 877621"

        Defaults:
          --app-id 646570
          --depot-id 877621
          --output agent-tmp/steam-depot-key-<app>-<depot>.env

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
          --print-key                    also print depot key to terminal
          --no-output                    do not write the env file
          --debug                        print protocol debug logs
        """.trimIndent(),
    )
}
