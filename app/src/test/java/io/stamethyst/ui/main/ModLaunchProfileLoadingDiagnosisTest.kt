package io.stamethyst.ui.main

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import io.stamethyst.backend.mods.ModManager
import io.stamethyst.backend.mods.OptionalModStorageCoordinator
import io.stamethyst.backend.mods.OptionalModMetadataIndex
import io.stamethyst.backend.workshop.WorkshopInstalledModRecord
import io.stamethyst.backend.workshop.WorkshopMetadataStore
import io.stamethyst.backend.workshop.WorkshopModCardState
import io.stamethyst.config.RuntimePaths
import io.stamethyst.ui.UiBusyOperation
import io.stamethyst.ui.UiText
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/** Characterization tests: exercise production refresh, not a simulated loading pipeline. */
class ModLaunchProfileLoadingDiagnosisTest {
    @Test
    fun updateRetainsEnabledStateInRunningControllerAndSavedProfiles() = withFixture { host, controller ->
        seedMods(host, 2)
        val library = RuntimePaths.optionalModsLibraryDir(host)
        val oldJar = File(library, "Diagnosis000.jar")
        val disabledJar = File(library, "Diagnosis001.jar")
        ModManager.replaceEnabledOptionalModIds(host, listOf(oldJar.absolutePath))
        host.getSharedPreferences("ModLaunchProfiles", Context.MODE_PRIVATE).edit()
            .putString("profiles", """[{"id":"default","name":"Default","enabledModKeys":["${oldJar.absolutePath}"]},{"id":"saved","name":"Saved","enabledModKeys":["${oldJar.absolutePath}"]}]""")
            .apply()
        controller.refresh(host, storageAccessible = true)
        assertTrue(controller.snapshot().optionalMods.single { it.storagePath == oldJar.absolutePath }.enabled)
        val updatedJar = File(library, "Diagnosis000_1.jar")
        oldJar.copyTo(updatedJar)
        ModManager.removeExistingOptionalModsForImport(host, "diagnosis_0", "diagnosis_0", updatedJar.absolutePath)

        controller.refresh(host, storageAccessible = true)

        val snapshot = controller.snapshot()
        assertTrue(snapshot.optionalMods.single { it.storagePath == updatedJar.absolutePath }.enabled)
        assertFalse(snapshot.optionalMods.single { it.storagePath == disabledJar.absolutePath }.enabled)
        assertTrue(snapshot.modLaunchProfiles.all { it.enabledModKeys == setOf(updatedJar.absolutePath) })
        controller.applyPendingSelection(host)
        assertEquals(setOf(updatedJar.absolutePath), RuntimePaths.enabledModsConfig(host).readLines().toSet())
    }

    @Test
    fun warmScan_skipsImportCleanupLockAndUnchangedProfileWrites() = withFixture { host, controller ->
        seedMods(host, 3)
        controller.refresh(host, storageAccessible = true)
        val writes = host.profileWrites.get()
        val pool = Executors.newSingleThreadExecutor()
        try {
            OptionalModStorageCoordinator.withImportArtifactLock {
                pool.submit { controller.refresh(host, storageAccessible = true) }.get(2, TimeUnit.SECONDS)
            }
            assertEquals("Unchanged profiles must not be persisted again", writes, host.profileWrites.get())
            assertEquals(3, controller.snapshot().optionalMods.size)
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun index_reusesPersistedEntriesAndRebuildsOnlyChangedJar() = withFixture { host, _ ->
        seedMods(host, 3)
        val coldStart = System.nanoTime()
        assertEquals(3, OptionalModMetadataIndex.listOptionalMods(host).size)
        val coldMs = (System.nanoTime() - coldStart) / 1_000_000.0
        val index = RuntimePaths.optionalModIndexFile(host)
        val json = JSONObject(index.readText())
        val entries = json.getJSONArray("entries")
        // Tag cached names. Reopening unchanged manifests would erase these tags.
        repeat(entries.length()) { entries.getJSONObject(it).put("name", "persisted-$it") }
        index.writeText(json.toString())
        clearRuntimeIndexCache()
        val diskStart = System.nanoTime()
        val fromDisk = OptionalModMetadataIndex.listOptionalMods(host)
        val diskMs = (System.nanoTime() - diskStart) / 1_000_000.0
        assertEquals(listOf("persisted-0", "persisted-1", "persisted-2"), fromDisk.map { it.name })
        val changed = fromDisk[1].jarFile
        assertTrue(changed.setLastModified(changed.lastModified() + 10_000))
        val refreshed = OptionalModMetadataIndex.listOptionalMods(host)
        assertEquals(listOf("persisted-0", "Diagnosis 1", "persisted-2"), refreshed.map { it.name })
        OptionalModMetadataIndex.invalidate(host, refreshed[0].jarFile)
        assertEquals(listOf("Diagnosis 0", "Diagnosis 1", "persisted-2"),
            OptionalModMetadataIndex.listOptionalMods(host).map { it.name })
        println("INDEX_DIAG 3 small JARs: cold=${coldMs}ms disk-index=${diskMs}ms; unchanged entries reused")
    }

    @Test
    fun index_explicitInvalidationHandlesSameSizeAndTimestampReplacement() = withFixture { host, _ ->
        seedMods(host, 1)
        val original = OptionalModMetadataIndex.listOptionalMods(host).single()
        val jar = original.jarFile
        val originalSize = jar.length()
        val originalTime = jar.lastModified()
        // A different same-size ZIP with exactly the cached timestamp.
        val bytes = jar.readBytes()
        bytes.fill(0)
        jar.writeBytes(bytes)
        assertEquals(originalSize, jar.length())
        assertTrue(jar.setLastModified(originalTime))
        clearRuntimeIndexCache()
        assertEquals("Fingerprint cannot detect same-size/time replacements", original.name,
            OptionalModMetadataIndex.listOptionalMods(host).single().name)
        OptionalModMetadataIndex.invalidate(host)
        // CRC mismatch or malformed header: rebuilding must not return the stale cached name.
        assertTrue(OptionalModMetadataIndex.listOptionalMods(host).none { it.name == original.name })
    }

    @Test
    fun index_malformedDiskIndexRecoversByRebuilding() = withFixture { host, _ ->
        seedMods(host, 3)
        OptionalModMetadataIndex.listOptionalMods(host)
        val index = RuntimePaths.optionalModIndexFile(host)
        index.writeText("{truncated")
        clearRuntimeIndexCache()
        assertEquals(3, OptionalModMetadataIndex.listOptionalMods(host).size)
        assertEquals(3, JSONObject(index.readText()).getJSONArray("entries").length())
    }

    private fun clearRuntimeIndexCache() {
        // Simulate a fresh process while leaving the on-disk index intact.
        OptionalModMetadataIndex::class.java.getDeclaredField("runtimeCache").apply {
            isAccessible = true
            set(null, null)
        }
    }

    @Test
    fun warmMetadataCache_storageRootLookupsDoNotScaleWithModCount() = withFixture { host, _ ->
        seedMods(host, 20)
        ModManager.listInstalledMods(host)
        host.storageCalls.set(0)
        val small = ModManager.listInstalledMods(host)
        val smallCalls = host.storageCalls.get()
        seedMods(host, 260)
        ModManager.listInstalledMods(host)
        val index = RuntimePaths.optionalModIndexFile(host)
        val indexBefore = index.readBytes()
        host.storageCalls.set(0)
        val start = System.nanoTime()
        val large = ModManager.listInstalledMods(host)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
        val largeCalls = host.storageCalls.get()

        assertEquals(20, small.count { !it.required })
        assertEquals(260, large.count { !it.required })
        assertTrue("Warm index should not be rewritten", indexBefore.contentEquals(index.readBytes()))
        assertEquals("Storage root lookups must be constant", smallCalls, largeCalls)
        println("PROFILE_DIAG warm listInstalledMods: 20mods=$smallCalls storage calls; " +
            "260mods=$largeCalls storage calls, ${elapsedMs}ms (host JVM, no injected delay)")
    }

    @Test
    fun warmScan_enabledAndPriorityPathsUseBatchNormalization() = withFixture { host, _ ->
        seedMods(host, 260)
        val mods = ModManager.listInstalledMods(host).filterNot { it.required }
        host.storageCalls.set(0)
        ModManager.listInstalledMods(host)
        val emptySelectionCalls = host.storageCalls.get()
        RuntimePaths.enabledModsConfig(host).writeText(mods.joinToString("\n") { it.jarFile.absolutePath })
        RuntimePaths.priorityModsConfig(host).writeText(mods.joinToString("\n") { "${it.jarFile.absolutePath}\t0" })
        host.storageCalls.set(0)
        val enabled = ModManager.listInstalledMods(host).filterNot { it.required }
        val selectedCalls = host.storageCalls.get()
        assertEquals(260, enabled.count { it.enabled })
        assertEquals(260, enabled.count { it.explicitPriority == 0 })
        assertTrue("Selection normalization must resolve roots once per batch", selectedCalls - emptySelectionCalls <= 4)
        println("INDEX_DIAG warm 260 mods: empty selections=$emptySelectionCalls storage calls; " +
            "all enabled + priority=$selectedCalls calls")
    }

    @Test
    fun workshopMissingFileChecks_skipStorageLookupsForMissingRecords() = withFixture { host, _ ->
        val store = WorkshopMetadataStore(host)
        fun seed(count: Int) {
            store.save((0 until count).map { i ->
                WorkshopInstalledModRecord(
                    appId = 646570u,
                    publishedFileId = (i + 1).toULong(),
                    title = "Diagnosis $i",
                    description = "",
                    previewUrl = "",
                    versionText = "1",
                    updatedAtMillis = 0,
                    installedAtMillis = 0,
                    localJarPath = "mods/Diagnosis$i.jar",
                )
            })
            // Missing records remain in the index on subsequent launcher starts.
            store.markMissingFiles()
        }
        seed(20)
        host.storageCalls.set(0)
        store.markMissingFiles()
        val smallCalls = host.storageCalls.get()
        seed(260)
        host.storageCalls.set(0)
        host.injectStorageDelay = true
        val start = System.nanoTime()
        try {
            store.markMissingFiles()
        } finally {
            host.injectStorageDelay = false
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
        val largeCalls = host.storageCalls.get()
        val records = store.list()
        assertEquals(260, records.size)
        assertTrue(records.all { it.cardState == WorkshopModCardState.FileMissing })
        assertEquals("Missing records must not resolve storage paths", 0, smallCalls)
        assertEquals("Storage lookups must not grow with missing record count", 0, largeCalls)
        println("WORKSHOP_DIAG warm markMissingFiles: 20 records=$smallCalls storage calls; " +
            "260 records=$largeCalls calls, ${elapsedMs}ms with injected 1ms/call; NOT device timing")
    }

    @Test
    fun workshopMissingFileChecks_preserveInactiveStatesWithoutStorageAccess() = withFixture { host, _ ->
        val states = listOf(
            WorkshopModCardState.Downloading,
            WorkshopModCardState.DownloadPaused,
            WorkshopModCardState.DownloadFailed,
            WorkshopModCardState.FileMissing,
        )
        val store = WorkshopMetadataStore(host)
        val records = states.mapIndexed { i, state ->
            WorkshopInstalledModRecord(
                appId = 646570u,
                publishedFileId = (i + 1).toULong(),
                title = "Inactive $i",
                description = "",
                previewUrl = "",
                versionText = "1",
                updatedAtMillis = 0,
                installedAtMillis = 0,
                localJarPath = "mods/missing-$i.jar",
                cardState = state,
            )
        }
        store.save(records)
        host.storageCalls.set(0)
        store.markMissingFiles()
        assertEquals(0, host.storageCalls.get())
        assertEquals(records.toSet(), store.list().toSet())
    }

    @Test
    fun workshopMissingFileChecks_stillDetectMissingInstalledContent() = withFixture { host, _ ->
        val states = listOf(
            WorkshopModCardState.ImportedPatched,
            WorkshopModCardState.ImportedUnpatched,
            WorkshopModCardState.NonStandardDownloaded,
            WorkshopModCardState.TexturePackInstalled,
            WorkshopModCardState.UpdateAvailable,
        )
        val store = WorkshopMetadataStore(host)
        val records = states.mapIndexed { i, state ->
            val path = "content"
            val file = File(RuntimePaths.workshopItemDir(host, 646570u, (i + 1).toULong()), path)
            file.parentFile!!.mkdirs()
            if (state == WorkshopModCardState.TexturePackInstalled) file.mkdirs() else file.writeText("installed")
            WorkshopInstalledModRecord(
                appId = 646570u,
                publishedFileId = (i + 1).toULong(),
                title = "Installed $i",
                description = "",
                previewUrl = "",
                versionText = "1",
                updatedAtMillis = 0,
                installedAtMillis = 0,
                localJarPath = path,
                cardState = state,
            )
        }
        store.save(records)
        store.markMissingFiles()
        assertEquals("Existing content must retain its state", records.toSet(), store.list().toSet())
        records.forEach { record ->
            assertTrue(File(RuntimePaths.workshopItemDir(host, record.appId, record.publishedFileId), record.localJarPath).delete())
        }
        store.markMissingFiles()
        assertTrue(store.list().all { it.cardState == WorkshopModCardState.FileMissing })
    }

    @Test
    fun fullRefresh_storageLatencyAccumulatesBeforeProfilesAreRead() = withFixture { host, controller ->
        seedMods(host, 260)
        controller.refresh(host, storageAccessible = true)
        val baselineStart = System.nanoTime()
        controller.refresh(host, storageAccessible = true)
        val baselineMs = (System.nanoTime() - baselineStart) / 1_000_000.0
        val profileStart = System.nanoTime()
        assertEquals("saved", ModLaunchProfileStore().load(host, emptySet()).activeProfileId)
        val profileOnlyMs = (System.nanoTime() - profileStart) / 1_000_000.0
        host.storageCalls.set(0)
        host.profileReads.set(0)
        host.storageCallsBeforeProfileRead = -1
        host.injectStorageDelay = true
        val start = System.nanoTime()
        controller.refresh(host, storageAccessible = true)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
        host.injectStorageDelay = false

        assertEquals("Saved profile must actually be loaded", "saved", controller.snapshot().activeModLaunchProfileId)
        assertTrue(host.profileReads.get() > 0)
        assertTrue("Warm refresh must use bounded root lookups", host.storageCalls.get() < 80)
        assertTrue("Each storage call injects >=1ms; refresh must pay that cost before returning",
            elapsedMs >= host.storageCalls.get().toDouble())
        println("PROFILE_DIAG full warm refresh: ${elapsedMs}ms; ${host.storageCalls.get()} storage calls; " +
            "${host.storageCallsBeforeProfileRead} calls BEFORE profile read; " +
            "injected 1ms/call (causal sensitivity test, NOT device timing); " +
            "baseline=${baselineMs}ms; profile only=${profileOnlyMs}ms (in-memory preferences)")
    }

    @Test
    fun importCommitLock_doesNotBlockSnapshotOrReadOnlyPreview() = withFixture { host, controller ->
        seedMods(host, 1)
        val pool = Executors.newFixedThreadPool(4)
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val enteredScan = CountDownLatch(1)
        host.onStorageCall = {
            if (Thread.currentThread().stackTrace.any { it.className.endsWith("OptionalModMetadataIndex") }) {
                enteredScan.countDown()
            }
        }
        try {
            val holder = pool.submit {
                OptionalModStorageCoordinator.withImportArtifactLock {
                    locked.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            }
            assertTrue(locked.await(5, TimeUnit.SECONDS))
            val refresh = pool.submit { controller.refresh(host, storageAccessible = true) }
            assertTrue("Production refresh must reach metadata scan", enteredScan.await(5, TimeUnit.SECONDS))
            val snapshotStarted = CountDownLatch(1)
            val snapshot = pool.submit<MainModManagementController.Snapshot> {
                snapshotStarted.countDown()
                controller.snapshot()
            }
            assertTrue(snapshotStarted.await(5, TimeUnit.SECONDS))
            assertEquals("Profiles have not even been read while scan waits on import lock", 0, host.profileReads.get())
            assertFalse("Refresh waits for the import commit lock", refresh.isDone)
            assertTrue("Last snapshot remains available during a blocked scan",
                snapshot.get(2, TimeUnit.SECONDS).optionalMods.isEmpty())
            val writesBeforePreview = host.profileWrites.get()
            val preview = pool.submit<ModLaunchProfileStore.State> { ModLaunchProfileStore().readOnly(host) }
                .get(2, TimeUnit.SECONDS)
            assertEquals("saved", preview.activeProfileId)
            assertEquals(listOf("default", "saved"), preview.profiles.map { it.id })
            assertEquals("Read-only preview must not persist or sanitize", writesBeforePreview, host.profileWrites.get())
            assertEquals(setOf("not-scanned-yet.jar"), preview.profiles.last().enabledModKeys)
            assertFalse("Preview must finish while scan is still blocked", refresh.isDone)
            release.countDown()
            holder.get(5, TimeUnit.SECONDS)
            refresh.get(5, TimeUnit.SECONDS)
            assertEquals("saved", controller.snapshot().activeModLaunchProfileId)
            assertTrue(host.profileReads.get() > 0)
        } finally {
            release.countDown()
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private fun withFixture(block: (TestActivity, MainModManagementController) -> Unit) {
        val base = File(System.getProperty("user.dir"), "../agent-tmp/profile-loading-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(base.toPath(), "fixture-").toFile()
        val host = TestActivity(root)
        // android.jar's final Context.getString() is a null-returning JVM stub. Exclude
        // unrelated localized patch labels only; storage/index/refresh/profile code stays real.
        val controller = MainModManagementController(object : MainModManagementController.Host {
            override fun canEditMainScreenState() = true
            override fun isBusy() = false
            override fun setBusy(busy: Boolean, message: UiText?, operation: UiBusyOperation, progressPercent: Int?) = Unit
            override fun republish(host: Activity) = Unit
            override fun emitEffect(effect: MainScreenViewModel.Effect) = Unit
        }, loadImportPatchStates = { emptyList() })
        try {
            block(host, controller)
        } finally {
            controller.shutdown()
            root.deleteRecursively()
        }
    }

    private fun seedMods(host: TestActivity, count: Int) {
        val library = RuntimePaths.optionalModsLibraryDir(host).apply { mkdirs() }
        repeat(count) { i ->
            val jar = File(library, "Diagnosis%03d.jar".format(i))
            if (!jar.exists()) ZipOutputStream(jar.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("ModTheSpire.json"))
                zip.write("""{"modid":"diagnosis_$i","name":"Diagnosis $i","version":"1.0"}""".toByteArray())
                zip.closeEntry()
            }
        }
    }

    private class TestActivity(root: File) : Activity() {
        private val internal = File(root, "internal").apply { mkdirs() }
        private val external = File(root, "external").apply { mkdirs() }
        private val preferences = HashMap<String, SharedPreferences>()
        val storageCalls = AtomicInteger()
        val profileReads = AtomicInteger()
        val profileWrites = AtomicInteger()
        var storageCallsBeforeProfileRead = -1
        var injectStorageDelay = false
        var onStorageCall: (() -> Unit)? = null

        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = internal
        override fun getPackageName() = "io.stamethyst.test.profilediagnosis"
        override fun getExternalFilesDir(type: String?): File {
            storageCalls.incrementAndGet()
            onStorageCall?.invoke()
            if (injectStorageDelay) Thread.sleep(1)
            return external
        }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            if (name == "ModLaunchProfiles") {
                profileReads.incrementAndGet()
                if (storageCallsBeforeProfileRead < 0) storageCallsBeforeProfileRead = storageCalls.get()
            }
            return preferences.getOrPut(name) {
                memoryPreferences(if (name == "ModLaunchProfiles") mutableMapOf(
                    "profiles" to """[{"id":"default","name":"Default","enabledModKeys":[]},{"id":"saved","name":"Saved","enabledModKeys":["not-scanned-yet.jar"]}]""",
                    "active_profile_id" to "saved",
                ) else mutableMapOf(), onEdit = { if (name == "ModLaunchProfiles") profileWrites.incrementAndGet() })
            }
        }
    }

    companion object {
        private fun memoryPreferences(values: MutableMap<String, Any?>, onEdit: () -> Unit): SharedPreferences {
            fun proxy(type: Class<*>, call: (String, Array<out Any?>) -> Any?): Any =
                Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                    call(method.name, args ?: emptyArray())
                }
            lateinit var editor: SharedPreferences.Editor
            editor = proxy(SharedPreferences.Editor::class.java) { method, args ->
                when {
                    method.startsWith("put") -> { values[args[0] as String] = args[1]; editor }
                    method == "remove" -> { values.remove(args[0]); editor }
                    method == "clear" -> { values.clear(); editor }
                    method == "commit" -> true
                    else -> null
                }
            } as SharedPreferences.Editor
            return proxy(SharedPreferences::class.java) { method, args ->
                when {
                    method == "edit" -> { onEdit(); editor }
                    method == "getAll" -> values.toMap()
                    method == "contains" -> values.containsKey(args[0])
                    method.startsWith("get") -> values[args[0]] ?: args[1]
                    else -> null
                }
            } as SharedPreferences
        }
    }
}
