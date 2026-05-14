package io.stamethyst.backend.mods.importing

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import io.stamethyst.R
import io.stamethyst.backend.mods.CompatibilitySettings
import io.stamethyst.backend.mods.DuplicateZipNormalizationResult
import io.stamethyst.backend.mods.DuplicateZipEntryNormalizer
import io.stamethyst.backend.mods.ModAtlasFilterCompatPatcher
import io.stamethyst.backend.mods.ModJarSupport
import io.stamethyst.backend.mods.ModManager
import io.stamethyst.backend.mods.ModManifestRootCompatPatcher
import io.stamethyst.backend.mods.MtsLaunchManifestValidator
import io.stamethyst.backend.mods.importing.patches.AtlasFilterPatchModule
import io.stamethyst.backend.mods.importing.patches.AtlasOfflineDownscalePatchModule
import io.stamethyst.backend.mods.importing.patches.DuplicateZipEntryPatchModule
import io.stamethyst.backend.mods.importing.patches.ImportPatchRegistry
import io.stamethyst.backend.mods.importing.patches.ManifestRootPatchModule
import io.stamethyst.model.ModItemUi
import io.stamethyst.ui.main.MainFolderStateStore
import io.stamethyst.ui.main.resolveAssignedFolderId
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale
import java.util.zip.ZipFile

internal object ModImportPlanner {
    private val ARCHIVE_EXTENSIONS = arrayOf(
        ".zip",
        ".rar",
        ".7z",
        ".tar",
        ".tgz",
        ".tar.gz",
        ".gz",
        ".bz2",
        ".tar.bz2",
        ".xz",
        ".tar.xz",
        ".zst",
        ".tar.zst",
        ".lz4",
        ".tar.lz4"
    )
    private val ARCHIVE_MIME_TYPES = setOf(
        "application/zip",
        "application/x-zip",
        "application/x-zip-compressed",
        "multipart/x-zip",
        "application/vnd.rar",
        "application/rar",
        "application/x-rar",
        "application/x-rar-compressed",
        "application/x-7z-compressed",
        "application/x-tar",
        "application/gzip",
        "application/x-gzip",
        "application/x-bzip2",
        "application/x-xz",
        "application/zstd",
        "application/x-zstd"
    )

    fun plan(context: Context, uris: List<Uri>): ModImportPlan {
        val session = createSession(context)
        try {
            val preparedSources = uris.mapIndexed { index, uri ->
                prepareSource(context, session, index, uri)
            }
            val plannedItems = preparedSources.map { source ->
                inspectSource(context, source)
            }
            return applyDuplicateConflicts(context, session, plannedItems)
        } catch (error: Throwable) {
            cleanup(session)
            throw error
        }
    }

    fun cleanup(session: ModImportSession) {
        runCatching {
            if (session.sessionDir.exists()) {
                session.sessionDir.deleteRecursively()
            }
        }
    }

    private fun createSession(context: Context): ModImportSession {
        val id = System.currentTimeMillis().coerceAtLeast(1L) * 1000L + (System.nanoTime() and 999L)
        val dir = File(context.cacheDir, "mod-import-sessions/$id")
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("Failed to create mod import session directory: ${dir.absolutePath}")
        }
        return ModImportSession(id = id, sessionDir = dir)
    }

    private fun prepareSource(
        context: Context,
        session: ModImportSession,
        index: Int,
        uri: Uri
    ): PreparedImportSource {
        val displayName = resolveDisplayName(context, uri)
        val mimeType = context.contentResolver.getType(uri)?.trim()?.takeIf { it.isNotEmpty() }
        val target = File(session.sessionDir, "source-$index.jar")
        context.contentResolver.openInputStream(uri).use { input ->
            if (input == null) {
                throw IOException("Unable to open selected file")
            }
            FileOutputStream(target, false).use { output ->
                input.copyTo(output)
            }
        }
        return PreparedImportSource(
            index = index,
            uri = uri,
            displayName = displayName,
            mimeType = mimeType,
            file = target
        )
    }

    private fun inspectSource(context: Context, source: PreparedImportSource): ModImportItemPlan {
        val itemId = "item-${source.index}"
        if (isLikelyCompressedArchive(source)) {
            return blockedItem(
                itemId = itemId,
                source = source,
                reason = ModImportBlockingReason.CompressedArchive,
                detail = context.getString(R.string.mod_import_block_archive_detail)
            )
        }
        if (!source.displayName.endsWith(".jar", ignoreCase = true)) {
            return blockedItem(
                itemId = itemId,
                source = source,
                reason = ModImportBlockingReason.UnsupportedFile,
                detail = context.getString(R.string.mod_import_block_unsupported_detail)
            )
        }
        val inspectionFile = File(source.file.parentFile, "inspect-${source.index}.jar")
        return try {
            copyFile(source.file, inspectionFile)
            val duplicatePlan = buildDuplicateZipPlan(
                context = context,
                result = normalizeAndValidateInspectionJar(inspectionFile)
            )
            val manifestRootPlan = runManifestRootPlan(context, inspectionFile)
            val manifest = try {
                ModJarSupport.readModManifest(inspectionFile)
            } catch (error: Throwable) {
                if (isLikelyModTheSpireJar(inspectionFile)) {
                    return blockedItem(
                        itemId = itemId,
                        source = source,
                        reason = ModImportBlockingReason.ReservedCoreComponent,
                        detail = context.getString(R.string.mod_import_block_modthespire_core_detail)
                    )
                }
                return blockedItem(
                    itemId = itemId,
                    source = source,
                    reason = ModImportBlockingReason.MissingManifest,
                    detail = error.message ?: context.getString(R.string.mod_import_block_manifest_read_failed)
                )
            }
            val normalizedModId = ModManager.normalizeModId(manifest.normalizedModId)
            if (normalizedModId.isEmpty()) {
                return blockedItem(
                    itemId = itemId,
                    source = source,
                    reason = ModImportBlockingReason.MissingModId,
                    detail = context.getString(R.string.mod_import_block_missing_modid_detail)
                )
            }
            val reservedComponent = resolveReservedComponent(normalizedModId)
            if (reservedComponent.isNotEmpty()) {
                return blockedItem(
                    itemId = itemId,
                    source = source,
                    reason = ModImportBlockingReason.ReservedCoreComponent,
                    detail = context.getString(R.string.mod_import_block_reserved_core_detail, reservedComponent),
                    manifest = manifest,
                    normalizedModId = normalizedModId,
                    reservedComponent = reservedComponent
                )
            }

            val launchFailure = MtsLaunchManifestValidator.validateModJar(inspectionFile)
            if (launchFailure != null) {
                return blockedItem(
                    itemId = itemId,
                    source = source,
                    reason = ModImportBlockingReason.InvalidMtsLaunchManifest,
                    detail = launchFailure.reason,
                    manifest = manifest,
                    normalizedModId = normalizedModId
                )
            }
            val launchModId = MtsLaunchManifestValidator.resolveLaunchModId(inspectionFile).trim()
            val atlasFilterPlan = runAtlasFilterPlan(context, inspectionFile)
            val baseItem = ModImportItemPlan(
                id = itemId,
                source = source,
                status = ModImportItemStatus.IMPORTABLE,
                manifest = manifest,
                normalizedModId = normalizedModId,
                launchModId = launchModId
            )
            val patchPlans = buildPatchPlans(
                context = context,
                item = baseItem,
                duplicatePlan = duplicatePlan,
                manifestRootPlan = manifestRootPlan,
                atlasFilterPlan = atlasFilterPlan
            )
            baseItem.copy(patchPlans = patchPlans)
        } catch (error: Throwable) {
            blockedItem(
                itemId = itemId,
                source = source,
                reason = ModImportBlockingReason.UnreadableJar,
                detail = error.message ?: error.javaClass.simpleName
            )
        } finally {
            if (inspectionFile.exists()) {
                inspectionFile.delete()
            }
        }
    }

    private fun buildPatchPlans(
        context: Context,
        item: ModImportItemPlan,
        duplicatePlan: ImportPatchPlan?,
        manifestRootPlan: ImportPatchPlan?,
        atlasFilterPlan: ImportPatchPlan?
    ): List<ImportPatchPlan> {
        val plans = ArrayList<ImportPatchPlan>()
        duplicatePlan?.let { plans.add(it) }
        manifestRootPlan?.let { plans.add(it) }
        atlasFilterPlan?.let { plans.add(it) }
        val modules = ImportPatchRegistry.modules(context)
        modules.forEach { module ->
            if (module.id == DuplicateZipEntryPatchModule.id ||
                module.id == ManifestRootPatchModule.id ||
                module.id == AtlasFilterPatchModule.id
            ) {
                return@forEach
            }
            val plan = module.plan(context, item) ?: return@forEach
            if (plan.applicable) {
                plans.add(plan)
            }
        }
        return plans.sortedBy { plan ->
            modules.firstOrNull { it.id == plan.moduleId }?.order ?: Int.MAX_VALUE
        }
    }

    internal fun normalizeAndValidateInspectionJar(inspectionFile: File): DuplicateZipNormalizationResult {
        val result = DuplicateZipEntryNormalizer.normalizeInPlaceIfNeeded(inspectionFile)
        ZipFile(inspectionFile).use { /* jar readability check */ }
        return result
    }

    private fun buildDuplicateZipPlan(
        context: Context,
        result: DuplicateZipNormalizationResult
    ): ImportPatchPlan? {
        if (!result.changed) {
            return null
        }
        return DuplicateZipEntryPatchModule.basePlan(
            applicable = true,
            details = listOf(
                context.getString(R.string.mod_import_patch_zip_entry_detail_total, result.totalEntries),
                context.getString(R.string.mod_import_patch_zip_entry_detail_unique, result.uniqueEntries),
                context.getString(R.string.mod_import_patch_zip_entry_detail_removed, result.duplicateEntriesRemoved)
            )
        )
    }

    private fun runManifestRootPlan(context: Context, inspectionFile: File): ImportPatchPlan? {
        if (!CompatibilitySettings.isModManifestRootCompatEnabled(context)) {
            return null
        }
        val result = ModManifestRootCompatPatcher.patchNestedManifestRootInPlace(inspectionFile)
        if (!result.hasPatchedChanges) {
            return null
        }
        return ManifestRootPatchModule.basePlan(
            applicable = true,
            details = listOf(
                context.getString(R.string.mod_import_patch_manifest_root_detail_moved, result.patchedFileEntries),
                context.getString(R.string.mod_import_patch_manifest_root_detail_prefix, result.sourceRootPrefix)
            )
        )
    }

    private fun runAtlasFilterPlan(context: Context, inspectionFile: File): ImportPatchPlan? {
        if (!CompatibilitySettings.isGlobalAtlasFilterCompatEnabled(context)) {
            return null
        }
        val result = ModAtlasFilterCompatPatcher.patchMipMapFiltersInPlace(inspectionFile)
        if (!result.hasPatchedChanges) {
            return null
        }
        return AtlasFilterPatchModule.basePlan(
            applicable = true,
            details = listOf(
                context.getString(R.string.mod_import_patch_atlas_filter_detail_files, result.patchedAtlasEntries),
                context.getString(R.string.mod_import_patch_atlas_filter_detail_lines, result.patchedFilterLines)
            )
        )
    }

    private fun applyDuplicateConflicts(
        context: Context,
        session: ModImportSession,
        items: List<ModImportItemPlan>
    ): ModImportPlan {
        val incomingByModId = LinkedHashMap<String, MutableList<ModImportItemPlan>>()
        items.filter { it.importable }.forEach { item ->
            val modId = item.normalizedModId
            if (modId.isNotEmpty()) {
                incomingByModId.getOrPut(modId) { ArrayList() }.add(item)
            }
        }
        val existingByModId = buildExistingDuplicateSources(context)
        val conflicts = ArrayList<DuplicateImportConflict>()
        val conflictedIds = LinkedHashSet<String>()
        incomingByModId.forEach { (modId, incoming) ->
            val existing = existingByModId[modId].orEmpty()
            if (incoming.size <= 1 && existing.isEmpty()) {
                return@forEach
            }
            conflictedIds.add(modId)
            conflicts.add(
                DuplicateImportConflict(
                    normalizedModId = modId,
                    displayModId = incoming.firstOrNull()?.displayModId?.ifBlank { modId } ?: modId,
                    importingItemIds = incoming.map { it.id },
                    importingDisplayNames = incoming.map { it.source.displayName },
                    existingSources = existing
                )
            )
        }
        val updatedItems = items.map { item ->
            if (item.importable && conflictedIds.contains(item.normalizedModId)) {
                item.copy(
                    status = ModImportItemStatus.NEEDS_DECISION,
                    duplicateConflictKey = item.normalizedModId
                )
            } else {
                item
            }
        }
        return ModImportPlan(
            session = session,
            items = updatedItems,
            duplicateConflicts = conflicts.sortedBy { it.normalizedModId }
        )
    }

    private fun buildExistingDuplicateSources(context: Context): Map<String, List<ExistingDuplicateImportSource>> {
        val result = LinkedHashMap<String, MutableList<ExistingDuplicateImportSource>>()
        val activity = context as? android.app.Activity
        val folderStateStore = activity?.let { MainFolderStateStore().apply { ensureLoaded(it) } }
        val validFolderIds = folderStateStore?.folders?.map { it.id }?.toHashSet().orEmpty()
        ModManager.listInstalledMods(context).forEach { mod ->
            if (mod.required || !mod.jarFile.isFile) {
                return@forEach
            }
            val modId = ModManager.normalizeModId(mod.modId).ifBlank {
                ModManager.normalizeModId(mod.manifestModId)
            }
            if (modId.isEmpty()) {
                return@forEach
            }
            val assignedFolderId = if (folderStateStore != null) {
                resolveAssignedFolderId(
                    mod = mod.toModItemUi(mod.jarFile.absolutePath),
                    folderAssignments = folderStateStore.assignments,
                    validFolderIds = validFolderIds
                )
            } else {
                null
            }
            result.getOrPut(modId) { ArrayList() }.add(
                ExistingDuplicateImportSource(
                    storagePath = mod.jarFile.absolutePath,
                    fileName = mod.jarFile.name,
                    assignedFolderId = assignedFolderId
                )
            )
        }
        return result
    }

    private fun ModManager.InstalledMod.toModItemUi(storagePath: String): ModItemUi {
        return ModItemUi(
            modId = modId,
            manifestModId = manifestModId,
            storagePath = storagePath,
            name = name,
            version = version,
            description = description,
            dependencies = dependencies,
            required = required,
            installed = installed,
            enabled = enabled,
            explicitPriority = explicitPriority,
            effectivePriority = effectivePriority
        )
    }

    private fun blockedItem(
        itemId: String,
        source: PreparedImportSource,
        reason: ModImportBlockingReason,
        detail: String,
        manifest: ModJarSupport.ModManifestInfo? = null,
        normalizedModId: String = "",
        reservedComponent: String = ""
    ): ModImportItemPlan {
        return ModImportItemPlan(
            id = itemId,
            source = source,
            status = if (reason == ModImportBlockingReason.CompressedArchive) {
                ModImportItemStatus.SKIPPED
            } else {
                ModImportItemStatus.BLOCKED
            },
            blockingReason = reason,
            blockingDetail = detail,
            manifest = manifest,
            normalizedModId = normalizedModId,
            reservedComponent = reservedComponent
        )
    }

    private fun resolveReservedComponent(normalizedModId: String): String {
        return when (ModManager.normalizeModId(normalizedModId)) {
            ModManager.MOD_ID_BASEMOD -> "BaseMod"
            ModManager.MOD_ID_STSLIB -> "StSLib"
            ModManager.MOD_ID_AMETHYST_RUNTIME_COMPAT -> "Amethyst Runtime Compat"
            "modthespire" -> "ModTheSpire"
            else -> ""
        }
    }

    private fun isLikelyModTheSpireJar(file: File): Boolean {
        return try {
            ZipFile(file).use { zipFile ->
                zipFile.getEntry("com/evacipated/cardcrawl/modthespire/Loader.class") != null
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun isLikelyCompressedArchive(source: PreparedImportSource): Boolean {
        val name = source.displayName.trim().lowercase(Locale.ROOT)
        if (name.isNotEmpty() && !name.endsWith(".jar") && ARCHIVE_EXTENSIONS.any { name.endsWith(it) }) {
            return true
        }
        val mime = source.mimeType?.trim()?.lowercase(Locale.ROOT) ?: return false
        return mime in ARCHIVE_MIME_TYPES
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String {
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    val value = cursor.getString(index)
                    if (!value.isNullOrBlank()) {
                        return value
                    }
                }
            }
            uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "unknown.jar"
        } catch (_: Throwable) {
            "unknown.jar"
        } finally {
            cursor?.close()
        }
    }

    private fun copyFile(source: File, target: File) {
        source.inputStream().use { input ->
            FileOutputStream(target, false).use { output ->
                input.copyTo(output)
            }
        }
    }
}
