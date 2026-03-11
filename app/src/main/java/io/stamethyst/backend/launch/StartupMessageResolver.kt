package io.stamethyst.backend.launch

import android.content.Context
import androidx.annotation.StringRes
import io.stamethyst.R
import java.util.Locale

internal object StartupMessageResolver {
    private const val MESSAGE_PREFIX = "@amethyst.startup/"

    fun resolveProgress(
        context: Context,
        message: String?,
        @StringRes fallbackResId: Int
    ): String = resolve(context, message, fallbackResId, failure = false)

    fun resolveFailure(
        context: Context,
        message: String?,
        @StringRes fallbackResId: Int
    ): String = resolve(context, message, fallbackResId, failure = true)

    private fun resolve(
        context: Context,
        message: String?,
        @StringRes fallbackResId: Int,
        failure: Boolean
    ): String {
        val normalized = message?.trim().orEmpty()
        if (normalized.isEmpty()) {
            return context.progressText(fallbackResId)
        }
        return resolveEncoded(context, normalized, failure)
            ?: resolveLegacy(context, normalized, failure)
            ?: normalized
    }

    private fun resolveEncoded(context: Context, message: String, failure: Boolean): String? {
        if (!message.startsWith(MESSAGE_PREFIX)) {
            return null
        }
        val body = message.removePrefix(MESSAGE_PREFIX)
        val key = body.substringBefore(':')
        val payload = body.substringAfter(':', "")
        return when (key) {
            "boot_bridge_started" -> context.progressText(R.string.startup_progress_boot_bridge_started)
            "launching_modthespire" -> context.progressText(R.string.startup_progress_launching_modthespire)
            "launching_game" -> context.progressText(R.string.startup_progress_launching_game)
            "attaching_mts_progress_bridge" -> context.progressText(R.string.startup_progress_attaching_mts_progress_bridge)
            "mts_progress_bridge_unavailable" -> context.progressText(R.string.startup_progress_mts_progress_bridge_unavailable)
            "searching_workshop_items" -> context.progressText(R.string.startup_progress_searching_workshop_items)
            "begin_patching" -> context.progressText(R.string.boot_overlay_stage_begin_patching)
            "patching_enums" -> context.progressText(R.string.boot_overlay_stage_patching_enums)
            "finding_core_patches" -> context.progressText(R.string.boot_overlay_stage_finding_core_patches)
            "finding_patches" -> context.progressText(R.string.boot_overlay_stage_finding_patches)
            "setting_modded_state" -> context.progressText(R.string.startup_progress_setting_modded_state)
            "adding_modthespire_version" -> context.progressText(R.string.boot_overlay_stage_adding_modthespire_version)
            "initializing_mods" -> context.progressText(R.string.boot_overlay_stage_initializing_mods)
            "starting_game_entry" -> context.progressText(R.string.boot_overlay_stage_starting_game_entry)
            "game_main_boot" -> context.progressText(R.string.startup_progress_loading_game_world)
            "main_menu_ready" -> context.progressText(R.string.startup_progress_main_menu_ready)
            "game_splash" -> context.progressText(R.string.startup_progress_showing_game_splash)
            "game_ready" -> context.progressText(R.string.startup_progress_game_ready)
            "mts_phase_percent" -> context.progressText(
                R.string.startup_progress_mts_phase_percent,
                payload.toIntOrNull() ?: 0
            )
            "mts_error" -> context.progressText(
                R.string.startup_failure_mts_error_with_detail,
                payload.ifBlank { context.progressText(R.string.startup_failure_unknown_reason) }
            )
            "steam_installation_not_found" -> context.progressText(R.string.startup_failure_steam_installation_not_found)
            "forced_crash_requested" -> context.progressText(R.string.startup_failure_forced_crash_requested)
            "uncaught_exception" -> context.progressText(
                R.string.startup_failure_uncaught_exception_with_detail,
                payload.ifBlank { context.progressText(R.string.startup_failure_unknown_reason) }
            )
            "delegate_crashed" -> context.progressText(
                R.string.startup_failure_delegate_crashed_with_detail,
                payload.ifBlank { context.progressText(R.string.startup_failure_unknown_reason) }
            )
            "delegate_start_failed" -> context.progressText(
                R.string.startup_failure_delegate_start_failed_with_detail,
                payload.ifBlank { context.progressText(R.string.startup_failure_unknown_reason) }
            )
            else -> if (failure) {
                context.progressText(R.string.startup_failure_boot_bridge_signaled)
            } else {
                null
            }
        }
    }

    private fun resolveLegacy(context: Context, message: String, failure: Boolean): String? {
        val trimmed = message.trim()
        val lower = trimmed.lowercase(Locale.ROOT)
        return if (failure) {
            resolveLegacyFailure(context, trimmed, lower)
        } else {
            resolveLegacyProgress(context, trimmed, lower)
        }
    }

    private fun resolveLegacyProgress(context: Context, trimmed: String, lower: String): String? {
        return when {
            lower.startsWith("starting jvm") -> context.progressText(R.string.boot_overlay_status_starting_jvm)
            lower.startsWith("launching modthespire") -> context.progressText(R.string.startup_progress_launching_modthespire)
            lower.startsWith("launching game") -> context.progressText(R.string.startup_progress_launching_game)
            lower == "loading..." -> context.progressText(R.string.startup_progress_loading)
            lower.startsWith("searching for workshop items") -> context.progressText(R.string.startup_progress_searching_workshop_items)
            lower.startsWith("begin patching") -> context.progressText(R.string.boot_overlay_stage_begin_patching)
            lower.startsWith("patching enums") ||
                lower.startsWith("busting enums") ||
                lower.startsWith("bust enums") ||
                lower.contains("enumbuster") -> context.progressText(R.string.boot_overlay_stage_patching_enums)
            lower.startsWith("finding core patches") -> context.progressText(R.string.boot_overlay_stage_finding_core_patches)
            lower.startsWith("finding patches") -> context.progressText(R.string.boot_overlay_stage_finding_patches)
            lower.startsWith("setting ismodded = true") -> context.progressText(R.string.startup_progress_setting_modded_state)
            lower.startsWith("adding modthespire to version") -> context.progressText(R.string.boot_overlay_stage_adding_modthespire_version)
            lower.startsWith("initializing mods") -> context.progressText(R.string.boot_overlay_stage_initializing_mods)
            lower.startsWith("starting game") ||
                lower.contains("desktoplauncher> launching application") -> context.progressText(R.string.boot_overlay_stage_starting_game_entry)
            lower.contains("cardcrawlgame> no migration") ||
                lower.contains("loading character stats") ||
                lower.contains("generating seeds:") ||
                lower.contains("cardcrawlgame.create") -> context.progressText(R.string.startup_progress_loading_game_world)
            lower == "console ready hint" ||
                lower.startsWith("main menu scene:") -> context.progressText(R.string.startup_progress_main_menu_ready)
            lower.startsWith("game splash") -> context.progressText(R.string.startup_progress_showing_game_splash)
            lower.startsWith("game state ready:") ||
                lower.startsWith("startup reached interactive phase") ||
                lower.startsWith("game ready") -> context.progressText(R.string.startup_progress_game_ready)
            lower.startsWith("boot bridge started") -> context.progressText(R.string.startup_progress_boot_bridge_started)
            lower.startsWith("starting com.evacipated.cardcrawl.modthespire.loader") -> context.progressText(R.string.startup_progress_launching_modthespire)
            lower.startsWith("starting com.megacrit.cardcrawl.desktop.desktoplauncher") -> context.progressText(R.string.startup_progress_launching_game)
            lower.startsWith("attached mts progress bridge") -> context.progressText(R.string.startup_progress_attaching_mts_progress_bridge)
            lower.startsWith("mts progress bridge unavailable") -> context.progressText(R.string.startup_progress_mts_progress_bridge_unavailable)
            lower.startsWith("mts phase ") -> {
                val percent = lower.removePrefix("mts phase ")
                    .substringBefore('%')
                    .trim()
                    .toIntOrNull() ?: return null
                context.progressText(R.string.startup_progress_mts_phase_percent, percent)
            }
            lower.startsWith("mts:") -> resolveLegacyMtsProgress(context, trimmed.substringAfter("MTS:", "").trim())
            else -> null
        }
    }

    private fun resolveLegacyFailure(context: Context, trimmed: String, lower: String): String? {
        return when {
            lower.startsWith("mts:") -> resolveLegacyMtsFailure(context, trimmed.substringAfter("MTS:", "").trim())
            lower.startsWith("forced crash requested") -> context.progressText(R.string.startup_failure_forced_crash_requested)
            lower.startsWith("uncaught exception on ") -> context.progressText(
                R.string.startup_failure_uncaught_exception_with_detail,
                trimmed.substringAfter(": ", "").ifBlank {
                    context.progressText(R.string.startup_failure_unknown_reason)
                }
            )
            lower.startsWith("delegate crashed:") -> context.progressText(
                R.string.startup_failure_delegate_crashed_with_detail,
                trimmed.substringAfter(":", "").trim().ifBlank {
                    context.progressText(R.string.startup_failure_unknown_reason)
                }
            )
            lower.startsWith("delegate start failed:") -> context.progressText(
                R.string.startup_failure_delegate_start_failed_with_detail,
                trimmed.substringAfter(":", "").trim().ifBlank {
                    context.progressText(R.string.startup_failure_unknown_reason)
                }
            )
            lower.startsWith("boot bridge signaled failure") -> context.progressText(R.string.startup_failure_boot_bridge_signaled)
            else -> null
        }
    }

    private fun resolveLegacyMtsProgress(context: Context, detail: String): String {
        val mapped = resolveLegacyProgress(context, detail, detail.lowercase(Locale.ROOT))
        return mapped ?: context.progressText(R.string.startup_progress_launching_modthespire)
    }

    private fun resolveLegacyMtsFailure(context: Context, detail: String): String {
        val lower = detail.lowercase(Locale.ROOT)
        if (lower.startsWith("error: failed to find steam installation.")) {
            return context.progressText(R.string.startup_failure_steam_installation_not_found)
        }
        return context.progressText(
            R.string.startup_failure_mts_error_with_detail,
            detail.ifBlank { context.progressText(R.string.startup_failure_unknown_reason) }
        )
    }
}
