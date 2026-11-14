package io.stamethyst.backend.mods

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class AgentPatchSourceCompilerTest {
    @Test
    fun unreadableCandidateDuringHashDoesNotAbortOtherClasspathEntriesAndIsRetried() = withAgentSourceCacheRoot { root ->
        val context = cacheContext(root)
        val bad = File(root, "unreadable.jar").apply { writeText("stat succeeds, read fails") }
        val good = zipFile(File(root, "good.jar"))
        val cache = AgentCompileClasspathCache()
        var attempts = 0
        fun resolve() = cache.resolve("test", listOf(bad, good), File(root, "missing-mods"), null) { candidate ->
            AgentPatchSourceCompiler.resolveCompileClasspathEntry(
                context, candidate,
                contentHash = { attempts++; throw IOException("simulated read failure after stat") },
                readableZip = { it != bad && AgentPatchSourceCompiler.isReadableZip(it) },
            )
        }
        repeat(2) {
            val resolution = resolve()
            assertEquals(listOf(good), resolution.entries)
            assertEquals(listOf(bad), resolution.unindexable)
        }
        assertEquals(2, attempts)
    }

    @Test
    fun temporaryCreationFailureIsRecoverableAndCancellationRemainsCancellation() = withAgentSourceCacheRoot { root ->
        val context = cacheContext(root)
        val jar = zipFile(File(root, "source.jar"))
        val result = AgentPatchSourceCompiler.resolveCompileClasspathEntry(
            context, jar,
            createTemporary = { throw IOException("simulated temporary-file failure") },
            readableZip = { false },
        )
        assertEquals(null, result)
        val cancelled = CancellationException("cancelled")
        val cancellation = runCatching {
            AgentPatchSourceCompiler.resolveCompileClasspathEntry(
                context, jar, contentHash = { throw cancelled }, readableZip = { false },
            )
        }.exceptionOrNull()
        assertTrue(cancelled === cancellation)
        try {
            val interruption = runCatching {
                AgentPatchSourceCompiler.resolveCompileClasspathEntry(
                    context, jar,
                    contentHash = { throw InterruptedIOException("interrupted") },
                    readableZip = { false },
                )
            }.exceptionOrNull()
            assertTrue(interruption is CancellationException)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
        assertTrue(context.cacheDir.walkTopDown().none { it.isFile && it.extension == "tmp" })
    }

    @Test
    fun cancellationAfterTemporaryCreationCleansStagingFile() = withAgentSourceCacheRoot { root ->
        val context = cacheContext(root)
        val jar = zipFile(File(root, "source.jar"))
        val cancellation = runCatching {
            AgentPatchSourceCompiler.resolveCompileClasspathEntry(context, jar) { candidate ->
                if (candidate.extension == "tmp") throw CancellationException("cancel during overlay validation")
                false
            }
        }.exceptionOrNull()
        assertTrue(cancellation is CancellationException)
        assertTrue(context.cacheDir.walkTopDown().none { it.isFile && it.extension == "tmp" })
    }

    @Test
    fun classpathOverlay_isContentAddressedPublishedCompleteAndIsolatedByPath() = withAgentSourceCacheRoot { root ->
        val context = object : ContextWrapper(Application()) {
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        }
        fun duplicateJar(file: File): File {
            file.parentFile.mkdirs()
            ZipArchiveOutputStream(file).use { zip ->
                listOf("guide/Guide.class", "META-INF/LICENSE", "META-INF/LICENSE").forEach { name ->
                    zip.putArchiveEntry(ZipArchiveEntry(name))
                    zip.write(byteArrayOf(1, 2, 3))
                    zip.closeArchiveEntry()
                }
            }
            return file
        }
        val first = duplicateJar(File(root, "a/parent.jar"))
        val second = File(root, "b/parent.jar").apply { parentFile.mkdirs(); writeBytes(first.readBytes()) }
        Files.setLastModifiedTime(second.toPath(), Files.getLastModifiedTime(first.toPath()))
        fun resolve(jar: File): File? = AgentPatchSourceCompiler.resolveCompileClasspathEntry(context, jar) { candidate ->
            candidate != jar && AgentPatchSourceCompiler.isReadableZip(candidate)
        }
        val output = resolve(first)!!
        assertEquals(listOf("guide/Guide.class"), ZipFile(output).use { zip -> zip.entries().asSequence().map { it.name }.toList() })
        assertEquals(output, resolve(first))
        assertFalse(output == resolve(second))
        assertTrue(output.parentFile.listFiles().orEmpty().none { it.extension == "tmp" })

        val unstable = duplicateJar(File(root, "unstable.jar"))
        val failed = AgentPatchSourceCompiler.resolveCompileClasspathEntry(context, unstable) { candidate ->
            if (candidate.extension == "tmp") unstable.appendText("concurrent change")
            candidate != unstable && AgentPatchSourceCompiler.isReadableZip(candidate)
        }
        assertEquals(null, failed)
        assertTrue(output.parentFile.listFiles().orEmpty().none { it.extension == "tmp" })
        assertEquals(2, output.parentFile.listFiles().orEmpty().count { it.extension == "jar" })
    }

    @Test
    fun buildCommandLine_pinsJava8AndPassesBootAndClassPath() {
        val root = Files.createTempDirectory("agent-patch-compile").toFile()
        val output = File(root, "patch").apply { mkdirs() }
        val rtJar = File(root, "rt.jar").apply { writeText("rt") }
        val sources = listOf(File(output, "src/A.java").apply { parentFile?.mkdirs(); writeText("class A {}") })

        val args = AgentPatchSourceCompiler.buildCommandLine(
            sources = sources,
            outputDir = output,
            bootClasspath = rtJar,
            classpath = "/game.jar:/mts.jar",
        ).toList()

        assertTrue(flag(args, "-source", "1.8"))
        assertTrue(flag(args, "-target", "1.8"))
        assertTrue(flag(args, "-bootclasspath", rtJar.absolutePath))
        assertTrue(flag(args, "-classpath", "/game.jar:/mts.jar"))
        assertTrue(flag(args, "-d", output.absolutePath))
        assertTrue("-g is required for the LocalVariableTable ModTheSpire reads", args.contains("-g"))
        assertEquals(sources.single().absolutePath, args.last())
    }

    @Test
    fun replaceCompiledClasses_dropsClassesTheNewBuildDidNotProduce() {
        val root = Files.createTempDirectory("agent-patch-stale").toFile()
        val patchRoot = File(root, "patch").apply { mkdirs() }
        val staging = File(root, "staging").apply { mkdirs() }
        File(patchRoot, "ModTheSpire.json").writeText("{}")
        File(patchRoot, "agent/Old.class").apply { parentFile?.mkdirs(); writeText("old") }
        File(patchRoot, "agent/Old\$Inner.class").writeText("old inner")
        File(patchRoot, "agent/Keep.class").writeText("old keep")
        File(staging, "agent/Keep.class").apply { parentFile?.mkdirs(); writeText("new keep") }
        File(staging, "other/New.class").apply { parentFile?.mkdirs(); writeText("new") }

        val installed = AgentPatchSourceCompiler.replaceCompiledClasses(patchRoot, staging)

        assertEquals(2, installed)
        assertTrue("renamed/deleted source must not leave a stale class", !File(patchRoot, "agent/Old.class").exists())
        assertTrue(!File(patchRoot, "agent/Old\$Inner.class").exists())
        assertEquals("new keep", File(patchRoot, "agent/Keep.class").readText())
        assertEquals("new", File(patchRoot, "other/New.class").readText())
        assertTrue("non-class files are preserved", File(patchRoot, "ModTheSpire.json").isFile)
    }

    @Test
    fun replaceCompiledClasses_keepsThePreviousBuildWhenNothingWasProduced() {
        val root = Files.createTempDirectory("agent-patch-empty-build").toFile()
        val patchRoot = File(root, "patch").apply { mkdirs() }
        val staging = File(root, "staging").apply { mkdirs() }
        val previous = File(patchRoot, "agent/Keep.class").apply { parentFile?.mkdirs(); writeText("old") }

        val installed = AgentPatchSourceCompiler.replaceCompiledClasses(patchRoot, staging)

        assertEquals(0, installed)
        assertEquals("old", previous.readText())
    }

    @Test
    fun collectSources_findsJavaEverywhereUnderPatchRoot() {
        val root = Files.createTempDirectory("agent-patch-sources").toFile()
        val patchRoot = File(root, "patch").apply { mkdirs() }
        File(patchRoot, "src/com/example/A.java").apply { parentFile?.mkdirs(); writeText("class A {}") }
        File(patchRoot, "src/com/example/B.java").apply { writeText("class B {}") }
        File(patchRoot, "ModTheSpire.json").writeText("{}")

        val sources = AgentPatchSourceCompiler.collectSources(patchRoot)

        assertEquals(listOf("A.java", "B.java"), sources.map { it.name })
    }

    @Test
    fun buildClasspath_includesParentJarAndRequiredMods() {
        val root = Files.createTempDirectory("agent-patch-classpath").toFile()
        val context = testContext(root)
        val stsRoot = File(File(root, "external"), "sts").apply { mkdirs() }
        zipFile(File(stsRoot, "desktop-1.0.jar"))
        zipFile(File(stsRoot, "ModTheSpire.jar"))
        val requiredMods = File(stsRoot, "required_mods").apply { mkdirs() }
        zipFile(File(requiredMods, "BaseMod.jar"))
        File(requiredMods, "ignore.txt").writeText("x")
        val parentJar = zipFile(File(root, "parent.jar"))

        val classpath = AgentPatchSourceCompiler.buildClasspath(context, parentJar)

        val entries = classpath.split(File.pathSeparator)
        assertTrue(entries.any { it.endsWith("desktop-1.0.jar") })
        assertTrue(entries.any { it.endsWith("ModTheSpire.jar") })
        assertTrue(entries.any { it.endsWith("BaseMod.jar") })
        assertTrue(entries.any { it.endsWith("parent.jar") })
        assertTrue(entries.none { it.endsWith("ignore.txt") })
    }

    @Test
    fun classpath_usesReadableJarDirectlyWithoutCopying() {
        val root = Files.createTempDirectory("agent-patch-nocopy").toFile()
        val context = testContext(root)
        val clean = zipFile(File(root, "parent.jar"))

        val resolution = AgentPatchSourceCompiler.resolveCompileClasspathEntries(context, clean)

        assertEquals(listOf(clean.absolutePath), resolution.entries.map { it.absolutePath })
        assertTrue(resolution.unindexable.isEmpty())
        assertTrue(
            "no dedup cache should be created for a readable jar",
            !File(context.cacheDir, AgentPatchSourceCompiler.COMPILE_CLASSPATH_CACHE_DIR).exists(),
        )
    }

    @Test
    fun copyDeduplicated_producesZipFileReadableArchive() {
        val root = Files.createTempDirectory("agent-patch-dedupe").toFile()
        val duplicate = File(root, "parent.jar")
        ZipArchiveOutputStream(duplicate).use { zip ->
            listOf("META-INF/LICENSE.txt", "guide/Guide.class", "META-INF/LICENSE.txt").forEachIndexed { index, name ->
                zip.putArchiveEntry(ZipArchiveEntry(name))
                zip.write(byteArrayOf(index.toByte()))
                zip.closeArchiveEntry()
            }
        }
        val target = File(root, "deduped.jar")

        DuplicateZipEntryNormalizer.copyDeduplicated(duplicate, target)

        assertEquals(2, ZipFile(target).use { it.size() })
        ZipFile(target).use { zip ->
            assertEquals(listOf("META-INF/LICENSE.txt", "guide/Guide.class"), zip.entries().asSequence().map { it.name }.toList())
        }
    }

    @Test
    fun classpath_fallsBackToFilesDirWhenCacheDirIsUnusable() {
        val root = Files.createTempDirectory("agent-patch-cache-fallback").toFile()
        val filesDir = File(root, "files").apply { mkdirs() }
        // Make cacheDir a regular file so mkdirs() fails and the fallback must kick in.
        val brokenCache = File(root, "cache").apply { writeText("not a directory") }
        val context = object : ContextWrapper(Application()) {
            override fun getFilesDir(): File = filesDir
            override fun getExternalFilesDir(type: String?): File = File(root, "external").apply { mkdirs() }
            override fun getCacheDir(): File = brokenCache
        }

        val resolved = AgentPatchSourceCompiler.resolveAgentPatchCacheDir(
            context,
            AgentPatchSourceCompiler.COMPILE_CLASSPATH_CACHE_DIR,
        )

        assertTrue("expected fallback under filesDir, got $resolved", resolved != null && resolved.absolutePath.startsWith(filesDir.absolutePath))
    }

    @Test
    fun cancellationProgress_reportsThreadInterruptionToEcj() {
        // ECJ polls this at its own checkpoints; it is the only way a build can be stopped.
        // Before this seam existed the compiler received `null` and could not be cancelled at all.
        val progress = AgentPatchSourceCompiler.cancellationProgressForTest()

        assertFalse("a healthy run must keep compiling", progress.isCanceled())

        Thread.currentThread().interrupt()
        try {
            assertTrue("an interrupted run must cancel the build", progress.isCanceled())
        } finally {
            Thread.interrupted()
        }
        assertFalse("cancellation must not latch after the interrupt is cleared", progress.isCanceled())
    }

    @Test
    fun checkCancelled_throwsOnlyWhileInterrupted() {
        AgentPatchSourceCompiler.checkCancelledForTest()

        Thread.currentThread().interrupt()
        try {
            val failure = runCatching { AgentPatchSourceCompiler.checkCancelledForTest() }
            assertTrue(
                "an interrupted compile must fail as cancelled, got $failure",
                failure.exceptionOrNull() is java.util.concurrent.CancellationException,
            )
        } finally {
            Thread.interrupted()
        }
        AgentPatchSourceCompiler.checkCancelledForTest()
    }

    private fun zipFile(file: File): File {
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("marker.txt"))
            zip.write("marker".toByteArray())
            zip.closeEntry()
        }
        return file
    }

    private fun flag(args: List<String>, name: String, value: String): Boolean {
        val index = args.indexOf(name)
        return index >= 0 && args.getOrNull(index + 1) == value
    }

    private fun testContext(root: File): Context {
        val filesDir = File(root, "files").apply { mkdirs() }
        val externalFilesDir = File(root, "external").apply { mkdirs() }
        return object : ContextWrapper(Application()) {
            override fun getFilesDir(): File = filesDir
            override fun getExternalFilesDir(type: String?): File = externalFilesDir
        }
    }

    private fun cacheContext(root: File): Context = object : ContextWrapper(Application()) {
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
    }
}
