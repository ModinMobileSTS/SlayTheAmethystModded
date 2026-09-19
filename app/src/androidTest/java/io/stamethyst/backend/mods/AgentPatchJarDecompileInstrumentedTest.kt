package io.stamethyst.backend.mods

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.stamethyst.config.RuntimePaths
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

/**
 * Proves the launcher can turn an extracted mod JAR into a readable Java project on-device: CFR
 * runs on ART, writes `.java` next to each class, and leaves failed classes as bytecode.
 */
@RunWith(AndroidJUnit4::class)
class AgentPatchJarDecompileInstrumentedTest {
    @Test
    fun createWorkspaceKeepsRawClassesUntilDecompileIsCalled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val mtsJar = RuntimePaths.importedMtsJar(context)
        assertTrue("ModTheSpire.jar missing: ${mtsJar.absolutePath}", mtsJar.isFile)

        // A minimal parent mod jar whose only class is a real one copied from ModTheSpire.
        val parentJar = File(context.cacheDir, "jar-decompile-decouple/parent.jar").apply {
            parentFile?.mkdirs()
        }
        val entryName = "com/evacipated/cardcrawl/modthespire/lib/SpirePatch2.class"
        java.util.zip.ZipOutputStream(parentJar.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("ModTheSpire.json"))
            zip.write("""{"modid":"parentmod","name":"Parent Mod"}""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry(entryName))
            ZipFile(mtsJar).use { source ->
                source.getInputStream(source.getEntry(entryName)).use { input -> input.copyTo(zip) }
            }
            zip.closeEntry()
        }

        val workspace = AgentPatchModManager.createWorkspace(context, "parentmod", parentJar)

        // Decoupled: creating the revision must not decompile.
        val rawClass = File(workspace.sourceRoot, entryName)
        assertTrue("workspace should keep the raw class: ${rawClass.absolutePath}", rawClass.isFile)
        assertTrue(
            "create_agent_patch_mod must not decompile",
            !File(workspace.sourceRoot, entryName.removeSuffix(".class") + ".java").exists(),
        )

        val result = AgentPatchClassDecompiler.decompileJarInto(
            sourceDir = workspace.sourceRoot,
            jarFile = parentJar,
            classpath = AgentPatchSourceCompiler.buildClasspath(context, parentJar),
        )

        assertTrue("nothing decompiled: $result", result.decompiledClasses > 0)
        assertTrue(File(workspace.sourceRoot, entryName.removeSuffix(".class") + ".java").isFile)
    }

    @Test
    fun decompilesExtractedJarIntoJavaProject() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val mtsJar = RuntimePaths.importedMtsJar(context)
        assertTrue("ModTheSpire.jar missing: ${mtsJar.absolutePath}", mtsJar.isFile)

        val sourceDir = File(context.cacheDir, "jar-decompile-test/source").apply {
            deleteRecursively()
            mkdirs()
        }
        // Mimic extraction: pull a couple of classes plus a resource out of the jar.
        val wanted = listOf(
            "com/evacipated/cardcrawl/modthespire/lib/SpirePatch2.class",
            "com/evacipated/cardcrawl/modthespire/lib/SpirePatch.class",
        )
        ZipFile(mtsJar).use { zip ->
            wanted.forEach { entryName ->
                val target = File(sourceDir, entryName)
                target.parentFile?.mkdirs()
                zip.getInputStream(zip.getEntry(entryName)).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }

        val result = AgentPatchClassDecompiler.decompileJarInto(
            sourceDir = sourceDir,
            jarFile = mtsJar,
            classpath = "",
            maxClasses = 3000,
        )

        assertTrue("nothing decompiled: $result", result.decompiledClasses > 0)
        val javaFile = File(sourceDir, "com/evacipated/cardcrawl/modthespire/lib/SpirePatch2.java")
        assertTrue("missing ${javaFile.absolutePath}: $result", javaFile.isFile)
        assertTrue(javaFile.readText(), javaFile.readText().contains("interface SpirePatch2"))
        assertTrue("raw .class should be removed after decompiling", !File(sourceDir, wanted[0]).exists())
    }

    @Test
    fun skipsDecompilationWhenClassCapExceeded() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sourceDir = File(context.cacheDir, "jar-decompile-cap/source").apply {
            deleteRecursively()
            mkdirs()
        }
        File(sourceDir, "com/example/A.class").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
        }
        File(sourceDir, "com/example/B.class").apply { writeBytes(byteArrayOf(0xCA.toByte())) }

        val result = AgentPatchClassDecompiler.decompileJarInto(
            sourceDir = sourceDir,
            jarFile = File(sourceDir, "unused.jar"),
            classpath = "",
            maxClasses = 1,
        )

        assertTrue("expected skip, got $result", result.skipped)
        assertTrue(File(sourceDir, "com/example/A.class").exists())
    }
}
