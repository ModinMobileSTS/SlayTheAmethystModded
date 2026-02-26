package io.stamethyst.backend.mods

import android.content.Context
import io.stamethyst.backend.core.RuntimePaths
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal object ModCompatibilityDiagnosticsLogger {
    fun appendCompatLog(context: Context?, message: String?) {
        if (context == null || message == null) {
            return
        }
        try {
            val logFile = RuntimePaths.latestLog(context)
            val parent = logFile.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            if (!logFile.exists()) {
                logFile.createNewFile()
            }
            val line = COMPAT_LOG_PREFIX + message + "\n"
            FileOutputStream(logFile, true).use { output ->
                output.write(line.toByteArray(StandardCharsets.UTF_8))
            }
        } catch (_: Throwable) {
        }
    }

    fun appendCompatDiagnostics(context: Context, stage: String?) {
        val safeStage = normalizeDiagStage(stage)
        try {
            appendCompatLog(
                context,
                "diag[$safeStage] setting original_fbo=" +
                    CompatibilitySettings.isOriginalFboPatchEnabled(context) +
                    ", downfall_fbo=" +
                    CompatibilitySettings.isDownfallFboPatchEnabled(context)
            )
        } catch (error: Throwable) {
            appendCompatLog(
                context,
                "diag[$safeStage] setting read failed: ${error.javaClass.simpleName}: ${error.message.toString()}"
            )
        }

        try {
            val installedMods = ModCompatibilityPatchCoordinator.findInstalledModsById(context)
            appendCompatLog(context, "diag[$safeStage] installed mods=${installedMods.keys}")
            appendClassPatchStatus(
                context = context,
                stage = safeStage,
                label = "STS GLFrameBuffer",
                targetJar = RuntimePaths.importedStsJar(context),
                patchJar = RuntimePaths.gdxPatchJar(context),
                classEntry = STS_PATCH_GL_FRAMEBUFFER_CLASS
            )

            val baseModJar = RuntimePaths.importedBaseModJar(context)
            appendClassPatchStatus(
                context = context,
                stage = safeStage,
                label = "BaseMod glow",
                targetJar = baseModJar,
                patchJar = File(RuntimePaths.gdxPatchDir(context), BASEMOD_GLOW_PATCH_JAR),
                classEntry = BASEMOD_GLOW_PATCH_CLASS
            )
            appendClassPatchStatus(
                context = context,
                stage = safeStage,
                label = "BaseMod ApplyScreenPostProcessor",
                targetJar = baseModJar,
                patchJar = File(RuntimePaths.gdxPatchDir(context), BASEMOD_POSTPROCESS_PATCH_JAR),
                classEntry = BASEMOD_POSTPROCESS_PATCH_CLASS
            )

            val downfallJar = installedMods[DOWNFALL_MOD_ID]
            if (downfallJar == null) {
                appendCompatLog(
                    context,
                    "diag[$safeStage] Downfall not installed (modid=$DOWNFALL_MOD_ID)"
                )
            } else {
                val downfallPatchJar = File(RuntimePaths.gdxPatchDir(context), DOWNFALL_FBO_PATCH_JAR)
                appendClassPatchStatus(
                    context = context,
                    stage = safeStage,
                    label = "Downfall DoubleEnergyOrb",
                    targetJar = downfallJar,
                    patchJar = downfallPatchJar,
                    classEntry = DOWNFALL_FBO_PATCH_CLASS
                )
                appendClassPatchStatus(
                    context = context,
                    stage = safeStage,
                    label = "Downfall CustomAnimatedNPC",
                    targetJar = downfallJar,
                    patchJar = downfallPatchJar,
                    classEntry = DOWNFALL_NPC_FBO_PATCH_CLASS
                )
                val backupFile = File(downfallJar.absolutePath + ".amethyst.downfall_fbo.backup")
                appendCompatLog(
                    context,
                    "diag[$safeStage] Downfall backup exists=${backupFile.isFile}, file=${backupFile.name}"
                )
            }
        } catch (error: Throwable) {
            appendCompatLog(
                context,
                "diag[$safeStage] failed: ${error.javaClass.simpleName}: ${error.message.toString()}"
            )
        }
    }

    private fun normalizeDiagStage(stage: String?): String {
        if (stage == null) {
            return "unknown"
        }
        val normalized = stage.trim()
        return if (normalized.isEmpty()) "unknown" else normalized
    }

    private fun appendClassPatchStatus(
        context: Context,
        stage: String,
        label: String,
        targetJar: File?,
        patchJar: File?,
        classEntry: String
    ) {
        if (targetJar == null || !targetJar.isFile) {
            appendCompatLog(
                context,
                "diag[$stage] $label target missing: ${targetJar?.absolutePath ?: "null"}"
            )
            return
        }
        if (patchJar == null || !patchJar.isFile) {
            appendCompatLog(
                context,
                "diag[$stage] $label patch missing: ${patchJar?.absolutePath ?: "null"}"
            )
            return
        }

        val targetBytes = JarFileIoUtils.readJarEntryBytes(targetJar, classEntry)
        val patchBytes = JarFileIoUtils.readJarEntryBytes(patchJar, classEntry)
        val patched = targetBytes != null && patchBytes != null && targetBytes.contentEquals(patchBytes)
        appendCompatLog(
            context,
            "diag[$stage] $label patched=$patched" +
                ", targetJar=${targetJar.name}" +
                ", targetSize=${targetBytes?.size ?: -1}" +
                ", targetHash=${digestShort(targetBytes)}" +
                ", patchJar=${patchJar.name}" +
                ", patchSize=${patchBytes?.size ?: -1}" +
                ", patchHash=${digestShort(patchBytes)}" +
                ", class=$classEntry"
        )
    }

    private fun digestShort(data: ByteArray?): String {
        if (data == null || data.isEmpty()) {
            return "missing"
        }
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(data)
            val text = StringBuilder(24)
            for (i in hash.indices) {
                if (i >= 6) {
                    break
                }
                val value = hash[i].toInt() and 0xFF
                if (value < 0x10) {
                    text.append('0')
                }
                text.append(Integer.toHexString(value))
            }
            text.toString()
        } catch (_: Throwable) {
            "hash_error"
        }
    }
}
