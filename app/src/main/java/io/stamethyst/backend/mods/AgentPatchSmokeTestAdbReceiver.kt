package io.stamethyst.backend.mods

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.stamethyst.BuildConfig
import io.stamethyst.config.RuntimePaths
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Debug-only adb entry point for [AgentPatchSmokeTest], so the invisible smoke test can be run and
 * regression-checked without driving the AI editor through a language model.
 *
 * It runs in the launcher process, which the smoke test requires, and in debug builds only. A release
 * build ignores the broadcast entirely.
 *
 * Usage:
 * ```
 * adb shell am broadcast -a io.stamethyst.action.RUN_AGENT_PATCH_SMOKE_TEST \
 *     -n io.stamethyst/.backend.mods.AgentPatchSmokeTestAdbReceiver \
 *     --es parent_mod_id necromod --es patch_id patch-1789968492734-aaf1fd93 --ei timeout_seconds 300
 *     --es mod_ids necromod,stslib   # optional: extra optional mods to enable for the run
 * ```
 *
 * The verdict is written to `sts/agent_smoke_test/adb_result.json` and logged under
 * [BASE_LOG_TAG].
 */
class AgentPatchSmokeTestAdbReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RUN) return
        if (BuildConfig.BUILD_TYPE != "debug") {
            Log.w(BASE_LOG_TAG, "ignored: this entry point is debug-only")
            return
        }
        val appContext = context.applicationContext
        val parentModId = intent.getStringExtra(EXTRA_PARENT_MOD_ID).orEmpty().trim()
        val patchId = intent.getStringExtra(EXTRA_PATCH_ID).orEmpty().trim()
        val timeoutSeconds = intent.getIntExtra(EXTRA_TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS.toInt())
            .toLong()
            .coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)
        val selectedModIds = intent.getStringExtra(EXTRA_MOD_IDS).orEmpty()
            .split(',', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        // onReceive runs on the main thread, and the smoke test blocks for minutes.
        Thread({
            val resultJson = runCatching {
                runSmokeTest(appContext, parentModId, patchId, timeoutSeconds, selectedModIds)
            }.getOrElse { error ->
                Log.e(BASE_LOG_TAG, "run failed", error)
                JSONObject()
                    .put("status", "adb_trigger_failed")
                    .put("passed", false)
                    .put("reason", error.message ?: error.javaClass.simpleName)
            }
            writeResult(appContext, resultJson)
            Log.i(BASE_LOG_TAG, "RESULT $resultJson")
        }, "AgentSmokeTestAdb").start()
    }

    private fun runSmokeTest(
        context: Context,
        parentModId: String,
        patchId: String,
        timeoutSeconds: Long,
        selectedModIds: List<String>,
    ): JSONObject {
        if (parentModId.isEmpty()) {
            return JSONObject().put("status", "invalid_arguments").put("passed", false)
                .put("reason", "parent_mod_id is required")
        }
        val installed = ModManager.listInstalledMods(context)
        val parentMod = installed.firstOrNull {
            ModManager.normalizeModId(it.modId) == ModManager.normalizeModId(parentModId)
        } ?: return JSONObject().put("status", "parent_mod_not_found").put("passed", false)
            .put("reason", "No installed mod with id $parentModId")

        // Patch ids are `patch-<epochMillis>-<random>`, so the newest sorts last.
        val packaged = AgentPatchModManager.listPackaged(context, parentMod.modId)
        val patch = packaged.firstOrNull { it.patchId == patchId }
            ?: if (patchId.isEmpty()) {
                packaged.maxByOrNull { it.patchId }
            } else {
                null
            }
            ?: return JSONObject().put("status", "patch_not_found").put("passed", false)
                .put("reason", "No packaged patch for ${parentMod.modId} (patch_id=$patchId)")

        val result = AgentPatchSmokeTest.run(
            context = context,
            parentModId = parentMod.modId,
            parentJar = parentMod.jarFile,
            patchJar = patch.jarFile,
            timeoutMs = timeoutSeconds * 1_000L,
            selectedModIds = selectedModIds,
        )
        return JSONObject()
            .put("status", result.status)
            .put("passed", result.passed)
            .put("reached_main_menu", result.reachedMainMenu)
            .put("reason", result.reason)
            .put("duration_ms", result.durationMs)
            .put("parent_mod_id", ModManager.normalizeModId(parentMod.modId))
            .put("patch_id", patch.patchId)
            .put("launch_mod_ids", org.json.JSONArray(result.launchModIds))
            .put("unresolved_dependencies", org.json.JSONArray(result.unresolvedDependencies))
            .put("unknown_mod_ids", org.json.JSONArray(result.unknownModIds))
            .put("requested_mod_ids", org.json.JSONArray(selectedModIds))
            .put("mod_set_verified", result.modSetVerified)
            .put("loaded_mod_jars", org.json.JSONArray(result.loadedModJarPaths))
            // The excerpt is raw JVM stdout; strip control characters so the written JSON stays
            // parseable by tools that read this file.
            .put("log_excerpt", result.logExcerpt.takeLast(2_000).sanitizedForJson())
    }

    private fun writeResult(context: Context, json: JSONObject) {
        runCatching {
            val file = RuntimePaths.agentSmokeTestAdbResultFile(context)
            file.parentFile?.mkdirs()
            file.writeText(json.toString(2), StandardCharsets.UTF_8)
        }
    }

    companion object {
        const val ACTION_RUN = "io.stamethyst.action.RUN_AGENT_PATCH_SMOKE_TEST"
        const val EXTRA_PARENT_MOD_ID = "parent_mod_id"
        const val EXTRA_PATCH_ID = "patch_id"
        const val EXTRA_TIMEOUT_SECONDS = "timeout_seconds"
        const val EXTRA_MOD_IDS = "mod_ids"
        private const val BASE_LOG_TAG = "AgentSmokeAdb"
        private const val DEFAULT_TIMEOUT_SECONDS = 240L
        private const val MIN_TIMEOUT_SECONDS = 15L
        private const val MAX_TIMEOUT_SECONDS = 900L

        /** Where the last adb-triggered run wrote its verdict. */
        fun resultFile(context: Context): File = RuntimePaths.agentSmokeTestAdbResultFile(context)

        private fun String.sanitizedForJson(): String = filter { it == '\n' || it == '\t' || it >= ' ' }
    }
}
