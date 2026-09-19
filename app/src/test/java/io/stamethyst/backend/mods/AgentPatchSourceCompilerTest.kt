package io.stamethyst.backend.mods

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class AgentPatchSourceCompilerTest {
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
        assertEquals(sources.single().absolutePath, args.last())
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
}
