package io.stamethyst.backend.mods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.nio.file.Files

/**
 * Covers the non-hook side of the preflight: resource-only patch mods, plain helper classes, and
 * `@SpireInitializer` content registration must not be rejected for lacking a `@SpirePatch`.
 */
class AgentPatchPreflightTest {
    private val classpath = emptyList<File>()

    @Test
    fun validate_acceptsResourceOnlyPatchModWithoutClasses() {
        val root = Files.createTempDirectory("preflight-resource").toFile()
        File(root, "data/config.txt").apply {
            parentFile?.mkdirs()
            writeText("value")
        }

        val result = AgentPatchPreflight.validate(root, classpath)

        assertTrue(result.valid)
        assertEquals(0, result.patchClassCount)
        assertTrue(result.warnings.any { it.contains("resource or helper-only") })
    }

    @Test
    fun validate_acceptsHelperClassWithoutAnnotations() {
        val root = Files.createTempDirectory("preflight-helper").toFile()
        writeClass(root, "agent/Helper", null)

        val result = AgentPatchPreflight.validate(root, classpath)

        assertTrue(result.valid)
        assertEquals(0, result.patchAnnotationCount)
        assertEquals(0, result.patchInitializerCount)
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun validate_acceptsSpireInitializerEntryPoint() {
        val root = Files.createTempDirectory("preflight-initializer").toFile()
        writeClass(root, "agent/ModInitializer", SPIRE_INITIALIZER)

        val result = AgentPatchPreflight.validate(root, classpath)

        assertTrue(result.valid)
        assertEquals(1, result.patchInitializerCount)
        assertEquals(0, result.patchAnnotationCount)
    }

    @Test
    fun validate_rejectsSpireInitializerWithoutInitializeMethod() {
        val root = Files.createTempDirectory("preflight-initializer-missing").toFile()
        writeClass(root, "agent/BrokenInitializer", SPIRE_INITIALIZER, withInitialize = false)

        val result = AgentPatchPreflight.validate(root, classpath)

        assertFalse(result.valid)
        assertTrue(result.issues.any { it.contains("initialize()") })
    }

    @Test
    fun validate_rejectsNonStaticInitializeMethod() {
        val root = Files.createTempDirectory("preflight-initializer-nonstatic").toFile()
        writeClass(
            root,
            "agent/InstanceInitializer",
            SPIRE_INITIALIZER,
            initializeAccess = Opcodes.ACC_PUBLIC,
        )

        val result = AgentPatchPreflight.validate(root, classpath)

        assertFalse(result.valid)
        assertTrue(result.issues.any { it.contains("must be static") })
    }

    @Test
    fun validate_rejectsSpireInitializerOnInterface() {
        val root = Files.createTempDirectory("preflight-initializer-interface").toFile()
        writeClass(
            root,
            "agent/InitializerContract",
            SPIRE_INITIALIZER,
            classAccess = Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
        )

        val result = AgentPatchPreflight.validate(root, classpath)

        assertFalse(result.valid)
        assertTrue(result.issues.any { it.contains("concrete class") })
    }

    private fun writeClass(
        root: File,
        internalName: String,
        annotationDescriptor: String?,
        classAccess: Int = Opcodes.ACC_PUBLIC,
        withInitialize: Boolean = true,
        initializeAccess: Int = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
    ) {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V1_8, classAccess, internalName, null, "java/lang/Object", null)
        annotationDescriptor?.let {
            writer.visitAnnotation(it, true).visitEnd()
        }
        if (withInitialize) {
            val initialize = writer.visitMethod(initializeAccess, "initialize", "()V", null, null)
            initialize.visitCode()
            initialize.visitInsn(Opcodes.RETURN)
            initialize.visitMaxs(0, 0)
            initialize.visitEnd()
        }
        writer.visitEnd()
        File(root, "$internalName.class").apply {
            parentFile?.mkdirs()
            writeBytes(writer.toByteArray())
        }
    }

    private companion object {
        const val SPIRE_INITIALIZER =
            "Lcom/evacipated/cardcrawl/modthespire/lib/SpireInitializer;"
    }
}
