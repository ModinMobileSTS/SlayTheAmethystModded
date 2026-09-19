package io.stamethyst.backend.mods

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.stamethyst.config.RuntimePaths
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

/**
 * Proves the launcher can compile a ModTheSpire patch on-device with ECJ (running on ART) and emit
 * Java 8 bytecode for the embedded OpenJDK 8 game JVM.
 */
@RunWith(AndroidJUnit4::class)
class AgentPatchSourceCompilerInstrumentedTest {
    @Test
    fun compilesJava8SourceAgainstRuntimeBootclasspath() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val workspace = prepareWorkspace("boot")

        writeSource(
            workspace,
            "BootOnly",
            """
            package com.example;

            import java.awt.Toolkit;
            import java.nio.file.Files;

            public class BootOnly {
                public Toolkit toolkit;
                public Files files;
            }
            """.trimIndent(),
        )

        val result = AgentPatchSourceCompiler.compile(context, workspace, null)

        assertTrue("compile failed: ${result.diagnostics}\nclasspath=${AgentPatchSourceCompiler.buildClasspath(context, null)}", result.success)
        assertCompiledJava8(File(workspace.patchRoot, "com/example/BootOnly.class"))
    }

    @Test
    fun compilesSpirePatchSourceAgainstGameClasspath() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val gameJar = RuntimePaths.importedStsJar(context)
        assertTrue("game jar missing: ${gameJar.absolutePath}", gameJar.isFile && gameJar.length() > 0L)

        val workspace = prepareWorkspace("patch")
        writeSource(
            workspace,
            "Tweak",
            """
            package com.example;

            import java.awt.Toolkit;
            import basemod.BaseMod;
            import com.megacrit.cardcrawl.cards.AbstractCard;
            import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;

            @SpirePatch2(clz = AbstractCard.class, method = "getName")
            public class Tweak {
                public Toolkit toolkit;
                public BaseMod mod;
                public static String Postfix(String __result) { return __result; }
            }
            """.trimIndent(),
        )

        val result = AgentPatchSourceCompiler.compile(context, workspace, null)

        assertTrue("compile failed: ${result.diagnostics}", result.success)
        assertCompiledJava8(File(workspace.patchRoot, "com/example/Tweak.class"))
    }

    /**
     * Android's `ZipFile` rejects duplicate entry names while the host JVM tolerates them, so the
     * deduplicated-classpath path can only be exercised on-device.
     */
    @Test
    fun compileClasspath_replacesDuplicateEntryJarWithReadableCopy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val duplicate = File(context.cacheDir, "patch-compile-test-dupes/parent.jar").apply {
            parentFile?.mkdirs()
        }
        ZipArchiveOutputStream(duplicate).use { zip ->
            listOf("META-INF/LICENSE.txt", "guide/Guide.class", "META-INF/LICENSE.txt").forEachIndexed { index, name ->
                zip.putArchiveEntry(ZipArchiveEntry(name))
                zip.write(byteArrayOf(index.toByte()))
                zip.closeArchiveEntry()
            }
        }
        assertTrue(
            "fixture must be unreadable by Android ZipFile",
            runCatching { ZipFile(duplicate).use { it.size() } }.isFailure,
        )

        val resolution = AgentPatchSourceCompiler.resolveCompileClasspathEntries(context, duplicate)

        assertTrue("unexpected skips: ${resolution.unindexable}", resolution.unindexable.isEmpty())
        val resolved = resolution.entries.firstOrNull { it.name.startsWith("parent.jar-") }
            ?: throw AssertionError("no deduplicated copy in ${resolution.entries.map { it.absolutePath }}")
        assertTrue("expected a copy, got ${resolved.absolutePath}", resolved.absolutePath != duplicate.absolutePath)
        assertTrue(resolved.absolutePath.contains(AgentPatchSourceCompiler.COMPILE_CLASSPATH_CACHE_DIR))
        assertEquals(2, ZipFile(resolved).use { it.size() })
    }

    private fun prepareWorkspace(name: String): AgentPatchWorkspace {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "patch-compile-test-$name").apply {
            deleteRecursively()
            mkdirs()
        }
        return AgentPatchWorkspace(
            parentModId = "test",
            parentModSegment = "test",
            patchId = "patch-test",
            root = root,
            sourceRoot = File(root, "source").apply { mkdirs() },
            patchRoot = File(root, "patch").apply { mkdirs() },
        )
    }

    private fun writeSource(workspace: AgentPatchWorkspace, className: String, source: String) {
        File(workspace.patchRoot, "src/com/example/$className.java").apply {
            parentFile?.mkdirs()
            writeText(source)
        }
    }

    private fun assertCompiledJava8(classFile: File) {
        assertTrue("missing ${classFile.absolutePath}", classFile.isFile)
        val bytes = classFile.readBytes()
        val major = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
        assertEquals(52, major)
    }
}
