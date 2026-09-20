package io.stamethyst.backend.mods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.io.File
import java.nio.file.Files

class AgentPatchTargetInspectorTest {
    private val mtsJar = listOf(
        File("src/main/assets/components/mods/ModTheSpire.jar"),
        File("app/src/main/assets/components/mods/ModTheSpire.jar"),
    ).first(File::isFile)
    private val classpath get() = listOf(mtsJar)

    @Test
    fun inspectTarget_returnsAllOverloadsFromRealClassfile() {
        val result = AgentPatchTargetInspector.inspectTarget(
            targetClassName = "com.evacipated.cardcrawl.modthespire.Patcher",
            memberName = "findPatches",
            classpath = classpath,
        )

        assertEquals(3, result.overloads.size)
        assertTrue(result.overloads.any { it.parameterTypes == listOf("java.net.URL[]") })
        assertTrue(result.classOrigin.endsWith("ModTheSpire.jar"))
    }

    @Test
    fun generateSkeleton_usesSelectedExactParameterTypes() {
        val root = Files.createTempDirectory("agent-patch-skeleton").toFile()
        val skeleton = AgentPatchTargetInspector.generateSkeleton(
            patchRoot = root,
            packageName = "agent.generated",
            patchClassName = "PatcherPatch",
            targetClassName = "com.evacipated.cardcrawl.modthespire.Patcher",
            memberName = "findPatches",
            overloadIndex = 0,
            patchKind = "postfix",
            classpath = classpath,
            overwrite = false,
        )

        assertTrue(skeleton.file.isFile)
        assertTrue(skeleton.source.contains("paramtypez"))
        assertTrue(skeleton.source.contains("Object[] __args"))
        assertTrue(skeleton.source.contains(skeleton.descriptor))
    }

    @Test
    fun preflight_rejectsOverloadedTargetWithoutExplicitSignature() {
        val root = Files.createTempDirectory("agent-patch-preflight").toFile()
        writePatchClass(root, includeParameterTypes = false)

        val result = AgentPatchPreflight.validate(root, classpath)

        assertFalse(result.valid)
        assertTrue(result.issues.any { it.contains("overloads") })
    }

    @Test
    fun preflight_acceptsMatchingExplicitSignature() {
        val root = Files.createTempDirectory("agent-patch-preflight-valid").toFile()
        writePatchClass(root, includeParameterTypes = true)

        val result = AgentPatchPreflight.validate(root, classpath)

        assertTrue(result.valid)
        assertEquals(1, result.patchAnnotationCount)
    }

    @Test
    fun preflight_rejectsPostfixReturnTypeMismatch() {
        val root = Files.createTempDirectory("agent-patch-preflight-return").toFile()
        writePatchClass(
            root,
            includeParameterTypes = true,
            hookKind = "postfix",
            hookDescriptor = "()Ljava/lang/String;",
        )

        val result = AgentPatchPreflight.validate(root, classpath)

        assertFalse(result.valid)
        assertTrue(result.issues.any { it.contains("Postfix") && it.contains("return") })
    }

    private fun writePatchClass(
        root: File,
        includeParameterTypes: Boolean,
        hookKind: String = "prefix",
        hookDescriptor: String = "()V",
    ) {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "agent/TestPatch", null, "java/lang/Object", null)
        val annotation = writer.visitAnnotation(
            "Lcom/evacipated/cardcrawl/modthespire/lib/SpirePatch2;",
            true,
        )
        annotation.visit("cls", "com.evacipated.cardcrawl.modthespire.Patcher")
        annotation.visit("method", "findPatches")
        if (includeParameterTypes) {
            val parameters = annotation.visitArray("paramtypez")
            parameters.visit(null, Type.getType("[Ljava/net/URL;"))
            parameters.visitEnd()
        }
        annotation.visitEnd()
        val hook = writer.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            if (hookKind == "prefix") "Prefix" else "Postfix",
            hookDescriptor,
            null,
            null,
        )
        hook.visitAnnotation(
            if (hookKind == "prefix") {
                "Lcom/evacipated/cardcrawl/modthespire/lib/SpirePrefixPatch;"
            } else {
                "Lcom/evacipated/cardcrawl/modthespire/lib/SpirePostfixPatch;"
            },
            true,
        ).visitEnd()
        if (hookDescriptor.endsWith("V")) {
            hook.visitInsn(Opcodes.RETURN)
            hook.visitMaxs(0, 0)
        } else {
            hook.visitInsn(Opcodes.ACONST_NULL)
            hook.visitInsn(Opcodes.ARETURN)
            hook.visitMaxs(1, 0)
        }
        hook.visitEnd()
        writer.visitEnd()
        File(root, "agent/TestPatch.class").apply {
            parentFile?.mkdirs()
            writeBytes(writer.toByteArray())
        }
    }
}
