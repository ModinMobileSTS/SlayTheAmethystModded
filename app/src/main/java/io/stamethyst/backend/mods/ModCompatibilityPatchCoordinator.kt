package io.stamethyst.backend.mods

import android.content.Context
import io.stamethyst.backend.core.RuntimePaths
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal object ModCompatibilityPatchCoordinator {
    private data class AtlasFilterCompatScanResult(
        val atlasEntries: Int,
        val mipmapFilterEntries: List<String>
    )

    private val ATLAS_FILTER_MIPMAP_LINE_REGEX = Regex(
        "^\\s*filter\\s*:\\s*.*mipmap.*$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
    )

    @Throws(IOException::class)
    fun applyCompatPatchRules(context: Context) {
        val installedModsById = findInstalledModsById(context)
        for (rule in COMPAT_PATCH_RULES) {
            val ruleEnabled = isCompatRuleEnabled(context, rule)
            if (rule.applyWhenInstalled) {
                val targetJar = installedModsById[rule.modId]
                if (targetJar == null) {
                    ModCompatibilityDiagnosticsLogger.appendCompatLog(
                        context,
                        "rule skip not installed: ${rule.label} (modid=${rule.modId})"
                    )
                    continue
                }
                if (!ruleEnabled) {
                    ModCompatibilityDiagnosticsLogger.appendCompatLog(
                        context,
                        "rule disabled by user setting: ${rule.label}"
                    )
                    tryRestoreCompatRule(context, rule, targetJar)
                    continue
                }
                applyCompatRuleToJar(context, rule, targetJar)
            } else {
                if (!ruleEnabled) {
                    ModCompatibilityDiagnosticsLogger.appendCompatLog(
                        context,
                        "rule disabled by user setting: ${rule.label}"
                    )
                    continue
                }
                val targetJar = resolveFixedTargetJar(context, rule)
                if (targetJar == null || !targetJar.isFile) {
                    ModCompatibilityDiagnosticsLogger.appendCompatLog(
                        context,
                        "rule missing required target: ${rule.label} (jar=${targetJar?.absolutePath ?: "null"})"
                    )
                    throw IOException("${rule.label} target jar not found")
                }
                applyCompatRuleToJar(context, rule, targetJar)
            }
        }
        applyGlobalAtlasFilterCompat(context, installedModsById)
    }

    fun findInstalledModsById(context: Context): Map<String, File> {
        val modsById: MutableMap<String, File> = HashMap()
        val modFiles = RuntimePaths.modsDir(context).listFiles() ?: return modsById
        for (modFile in modFiles) {
            if (!modFile.isFile) {
                continue
            }
            val name = modFile.name
            if (!name.lowercase(Locale.ROOT).endsWith(".jar")) {
                continue
            }
            try {
                val modId = ModJarManifestParser.normalizeModId(ModJarManifestParser.resolveModId(modFile))
                if (modId.isNotEmpty() && !modsById.containsKey(modId)) {
                    modsById[modId] = modFile
                }
            } catch (_: Throwable) {
            }
        }
        return modsById
    }

    private fun isCompatRuleEnabled(context: Context?, rule: CompatPatchRule?): Boolean {
        if (context == null || rule == null) {
            return true
        }
        if (DOWNFALL_MOD_ID == rule.modId && DOWNFALL_FBO_PATCH_JAR == rule.patchJarName) {
            return CompatibilitySettings.isDownfallFboPatchEnabled(context)
        }
        return true
    }

    private fun applyGlobalAtlasFilterCompat(context: Context, installedModsById: Map<String, File>) {
        if (!CompatibilitySettings.isGlobalAtlasFilterCompatEnabled(context)) {
            ModCompatibilityDiagnosticsLogger.appendCompatLog(
                context,
                "global atlas filter compat skip: disabled by user setting (runtime fallback disabled)"
            )
            return
        }
        if (installedModsById.isEmpty()) {
            ModCompatibilityDiagnosticsLogger.appendCompatLog(
                context,
                "global atlas filter compat skip: no installed mods"
            )
            return
        }

        var scannedMods = 0
        var modsWithAtlas = 0
        var modsWithMipmapFilters = 0
        var failed = 0
        var totalAtlasEntries = 0
        var totalMipmapEntries = 0

        val sortedModIds: MutableList<String> = ArrayList(installedModsById.keys)
        sortedModIds.sort()
        for (modId in sortedModIds) {
            val targetJar = installedModsById[modId] ?: continue
            scannedMods++
            if (!targetJar.isFile) {
                failed++
                ModCompatibilityDiagnosticsLogger.appendCompatLog(
                    context,
                    "global atlas filter compat skip: mod=$modId jar missing (${targetJar.absolutePath})"
                )
                continue
            }
            try {
                val scan = scanAtlasFilterCandidates(targetJar)
                totalAtlasEntries += scan.atlasEntries
                totalMipmapEntries += scan.mipmapFilterEntries.size
                if (scan.atlasEntries > 0) {
                    modsWithAtlas++
                }
                if (scan.mipmapFilterEntries.isNotEmpty()) {
                    modsWithMipmapFilters++
                    ModCompatibilityDiagnosticsLogger.appendCompatLog(
                        context,
                        "global atlas filter compat runtime fallback armed: mod=$modId jar=${targetJar.name}, " +
                            "atlasEntries=${scan.atlasEntries}, mipmapEntries=${scan.mipmapFilterEntries.size}, " +
                            "sample=${scan.mipmapFilterEntries.take(6).joinToString(",")}"
                    )
                } else if (scan.atlasEntries > 0) {
                    ModCompatibilityDiagnosticsLogger.appendCompatLog(
                        context,
                        "global atlas filter compat runtime fallback no mipmap filters: mod=$modId jar=${targetJar.name}, " +
                            "atlasEntries=${scan.atlasEntries}"
                    )
                } else {
                    ModCompatibilityDiagnosticsLogger.appendCompatLog(
                        context,
                        "global atlas filter compat runtime fallback no atlas entries: mod=$modId jar=${targetJar.name}"
                    )
                }
            } catch (error: Throwable) {
                failed++
                ModCompatibilityDiagnosticsLogger.appendCompatLog(
                    context,
                    "global atlas filter compat scan failed: mod=$modId jar=${targetJar.name}, " +
                        "reason=${error.javaClass.simpleName}: ${error.message.toString()}"
                )
            }
        }

        ModCompatibilityDiagnosticsLogger.appendCompatLog(
            context,
            "global atlas filter compat summary: mode=runtime_only, scannedMods=$scannedMods, " +
                "modsWithAtlas=$modsWithAtlas, modsWithMipmapFilters=$modsWithMipmapFilters, " +
                "failed=$failed, atlasEntries=$totalAtlasEntries, mipmapEntries=$totalMipmapEntries"
        )
    }

    @Throws(IOException::class)
    private fun scanAtlasFilterCandidates(targetJar: File): AtlasFilterCompatScanResult {
        var atlasEntries = 0
        val mipmapFilterEntries: MutableList<String> = ArrayList()
        ZipFile(targetJar).use { zipFile ->
            val entries = zipFile.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) {
                    continue
                }
                val name = entry.name
                if (!name.lowercase(Locale.ROOT).endsWith(".atlas")) {
                    continue
                }
                atlasEntries++
                val atlasText = JarFileIoUtils.readEntry(zipFile, entry)
                if (ATLAS_FILTER_MIPMAP_LINE_REGEX.containsMatchIn(atlasText)) {
                    mipmapFilterEntries.add(name)
                }
            }
        }
        return AtlasFilterCompatScanResult(
            atlasEntries = atlasEntries,
            mipmapFilterEntries = mipmapFilterEntries
        )
    }

    @Throws(IOException::class)
    private fun applyCompatRuleToJar(context: Context, rule: CompatPatchRule, targetJar: File) {
        val patchJar = File(RuntimePaths.gdxPatchDir(context), rule.patchJarName)
        ModCompatibilityDiagnosticsLogger.appendCompatLog(
            context,
            "rule matched: ${rule.label} target=${targetJar.name} class=${rule.targetClassEntry}"
        )
        tryBackupCompatRule(context, rule, targetJar, patchJar)
        try {
            val result = ensureJarClassCompat(targetJar, patchJar, rule.targetClassEntry, rule.label)
            if (result == CompatPatchApplyResult.ALREADY_PATCHED) {
                ModCompatibilityDiagnosticsLogger.appendCompatLog(context, "rule already patched: ${rule.label}")
            } else {
                ModCompatibilityDiagnosticsLogger.appendCompatLog(context, "rule patched successfully: ${rule.label}")
            }
        } catch (error: Throwable) {
            if (shouldSkipMissingDownfallCompatClass(rule, error)) {
                ModCompatibilityDiagnosticsLogger.appendCompatLog(
                    context,
                    "rule skip missing patch class: ${rule.label} class=${rule.targetClassEntry}" +
                        " reason=${error.javaClass.simpleName}: ${error.message.toString()}"
                )
                return
            }
            ModCompatibilityDiagnosticsLogger.appendCompatLog(
                context,
                "rule failed: ${rule.label} reason=${error.javaClass.simpleName}: ${error.message.toString()}"
            )
            if (error is IOException) {
                throw error
            }
            throw IOException("${rule.label} compat apply failed", error)
        }
    }

    private fun shouldSkipMissingDownfallCompatClass(rule: CompatPatchRule, error: Throwable): Boolean {
        if (!isDownfallFboRule(rule) || error !is IOException) {
            return false
        }
        val message = error.message.toString()
        return message.contains("compat patch is missing required class")
    }

    private fun tryBackupCompatRule(
        context: Context,
        rule: CompatPatchRule,
        targetJar: File?,
        patchJar: File
    ) {
        if (!isDownfallFboRule(rule) || targetJar == null || !targetJar.isFile) {
            return
        }
        try {
            val backupFile = resolveCompatRuleBackupFile(rule, targetJar) ?: return
            if (backupFile.isFile && backupFile.length() > 0) {
                ModCompatibilityDiagnosticsLogger.appendCompatLog(
                    context,
                    "rule backup keep existing: ${rule.label} -> ${backupFile.name}"
                )
                return
            }
            val patchEntries = loadSinglePatchEntry(patchJar, rule.targetClassEntry, rule.label)
            if (isJarClassCompatPatched(targetJar, rule.targetClassEntry, patchEntries)) {
                ModCompatibilityDiagnosticsLogger.appendCompatLog(
                    context,
                    "rule backup skip (already patched): ${rule.label}"
                )
                return
            }
            JarFileIoUtils.copyFileReplacing(targetJar, backupFile)
            ModCompatibilityDiagnosticsLogger.appendCompatLog(
                context,
                "rule backup updated: ${rule.label} -> ${backupFile.name}"
            )
        } catch (error: Throwable) {
            ModCompatibilityDiagnosticsLogger.appendCompatLog(
                context,
                "rule backup failed: ${rule.label} reason=${error.javaClass.simpleName}: ${error.message.toString()}"
            )
        }
    }

    private fun tryRestoreCompatRule(
        context: Context,
        rule: CompatPatchRule,
        targetJar: File?
    ) {
        if (!isDownfallFboRule(rule) || targetJar == null || !targetJar.isFile) {
            return
        }
        val patchJar = File(RuntimePaths.gdxPatchDir(context), rule.patchJarName)
        if (!patchJar.isFile) {
            ModCompatibilityDiagnosticsLogger.appendCompatLog(
                context,
                "rule restore skip, patch jar missing: ${patchJar.absolutePath}"
            )
            return
        }
        try {
            val patchEntries = loadSinglePatchEntry(patchJar, rule.targetClassEntry, rule.label)
            if (!isJarClassCompatPatched(targetJar, rule.targetClassEntry, patchEntries)) {
                ModCompatibilityDiagnosticsLogger.appendCompatLog(
                    context,
                    "rule restore skip (target not patched): ${rule.label}"
                )
                return
            }
            val backupFile = resolveCompatRuleBackupFile(rule, targetJar)
            if (backupFile == null || !backupFile.isFile) {
                ModCompatibilityDiagnosticsLogger.appendCompatLog(
                    context,
                    "rule restore skip, backup missing: ${rule.label}"
                )
                return
            }
            if (isJarClassCompatPatched(backupFile, rule.targetClassEntry, patchEntries)) {
                ModCompatibilityDiagnosticsLogger.appendCompatLog(
                    context,
                    "rule restore skip, backup already patched: ${rule.label}"
                )
                return
            }
            JarFileIoUtils.copyFileReplacing(backupFile, targetJar)
            ModCompatibilityDiagnosticsLogger.appendCompatLog(
                context,
                "rule restored from backup: ${rule.label}"
            )
        } catch (error: Throwable) {
            ModCompatibilityDiagnosticsLogger.appendCompatLog(
                context,
                "rule restore failed: ${rule.label} reason=${error.javaClass.simpleName}: ${error.message.toString()}"
            )
        }
    }

    private fun isDownfallFboRule(rule: CompatPatchRule?): Boolean {
        if (rule == null) {
            return false
        }
        if (DOWNFALL_MOD_ID != rule.modId || DOWNFALL_FBO_PATCH_JAR != rule.patchJarName) {
            return false
        }
        return DOWNFALL_FBO_PATCH_CLASS == rule.targetClassEntry ||
            DOWNFALL_NPC_FBO_PATCH_CLASS == rule.targetClassEntry
    }

    private fun resolveCompatRuleBackupFile(rule: CompatPatchRule, targetJar: File?): File? {
        if (!isDownfallFboRule(rule) || targetJar == null) {
            return null
        }
        return File(targetJar.absolutePath + ".amethyst.downfall_fbo.backup")
    }

    private fun resolveFixedTargetJar(context: Context, rule: CompatPatchRule): File? {
        val fixedTargetJarName = rule.fixedTargetJarName
        if (fixedTargetJarName.isNullOrBlank()) {
            return null
        }
        return File(RuntimePaths.modsDir(context), fixedTargetJarName)
    }

    @Throws(IOException::class)
    private fun ensureJarClassCompat(
        targetJar: File?,
        patchJar: File?,
        classEntry: String,
        label: String
    ): CompatPatchApplyResult {
        if (targetJar == null || !targetJar.isFile) {
            throw IOException("$label target jar not found")
        }
        if (patchJar == null || !patchJar.isFile) {
            throw IOException("$label compat patch not found")
        }

        val patchEntries = loadSinglePatchEntry(patchJar, classEntry, label)
        if (isJarClassCompatPatched(targetJar, classEntry, patchEntries)) {
            return CompatPatchApplyResult.ALREADY_PATCHED
        }

        val tempJar = File(targetJar.absolutePath + ".compat.tmp")
        val seenNames: MutableSet<String> = HashSet()
        FileInputStream(targetJar).use { fileInput ->
            ZipInputStream(fileInput).use { zipIn ->
                FileOutputStream(tempJar, false).use { outputStream ->
                    ZipOutputStream(outputStream).use { zipOut ->
                        while (true) {
                            val entry = zipIn.nextEntry ?: break
                            val name = entry.name
                            if (entry.isDirectory || !seenNames.add(name)) {
                                zipIn.closeEntry()
                                continue
                            }
                            val outEntry = ZipEntry(name)
                            if (entry.time > 0) {
                                outEntry.time = entry.time
                            }
                            zipOut.putNextEntry(outEntry)
                            val patchBytes = patchEntries[name]
                            if (patchBytes != null) {
                                zipOut.write(patchBytes)
                            } else {
                                JarFileIoUtils.copyStream(zipIn, zipOut)
                            }
                            zipOut.closeEntry()
                            zipIn.closeEntry()
                        }

                        for ((name, data) in patchEntries) {
                            if (seenNames.contains(name)) {
                                continue
                            }
                            val outEntry = ZipEntry(name)
                            zipOut.putNextEntry(outEntry)
                            zipOut.write(data)
                            zipOut.closeEntry()
                        }
                    }
                }
            }
        }

        if (!isJarClassCompatPatched(tempJar, classEntry, patchEntries)) {
            if (tempJar.exists()) {
                tempJar.delete()
            }
            throw IOException("Failed to patch $label class: $classEntry")
        }

        if (targetJar.exists() && !targetJar.delete()) {
            throw IOException("Failed to replace ${targetJar.absolutePath}")
        }
        if (!tempJar.renameTo(targetJar)) {
            throw IOException("Failed to move ${tempJar.absolutePath} -> ${targetJar.absolutePath}")
        }
        targetJar.setLastModified(System.currentTimeMillis())
        return CompatPatchApplyResult.PATCHED
    }

    @Throws(IOException::class)
    private fun loadSinglePatchEntry(
        patchJar: File,
        requiredClassEntry: String,
        label: String
    ): Map<String, ByteArray> {
        val entries: MutableMap<String, ByteArray> = HashMap()
        FileInputStream(patchJar).use { fileInput ->
            ZipInputStream(fileInput).use { zipIn ->
                while (true) {
                    val entry = zipIn.nextEntry ?: break
                    val name = entry.name
                    if (entry.isDirectory ||
                        name.startsWith("META-INF/") ||
                        requiredClassEntry != name ||
                        entries.containsKey(name)
                    ) {
                        zipIn.closeEntry()
                        continue
                    }
                    entries[name] = JarFileIoUtils.readAll(zipIn)
                    zipIn.closeEntry()
                }
            }
        }
        if (!entries.containsKey(requiredClassEntry)) {
            throw IOException("$label compat patch is missing required class: $requiredClassEntry")
        }
        return entries
    }

    private fun isJarClassCompatPatched(
        targetJar: File,
        classEntry: String,
        patchEntries: Map<String, ByteArray>
    ): Boolean {
        return try {
            ZipFile(targetJar).use { zipFile ->
                val entry = zipFile.getEntry(classEntry)
                val expected = patchEntries[classEntry]
                if (entry == null || expected == null) {
                    return false
                }
                val actual = JarFileIoUtils.readEntryBytes(zipFile, entry)
                actual.contentEquals(expected)
            }
        } catch (_: Throwable) {
            false
        }
    }
}
