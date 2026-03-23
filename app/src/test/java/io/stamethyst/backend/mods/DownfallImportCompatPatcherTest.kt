package io.stamethyst.backend.mods

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FrameNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode

class DownfallImportCompatPatcherTest {
    private companion object {
        const val BOSS_PANEL_X_RATIO = 0.9f
        const val BOSS_PANEL_Y_RATIO = 0.51f
        const val BOSS_PANEL_WIDTH_RATIO = 0.15f
        const val LEGACY_RELATIVE_BOSS_PANEL_X_RATIO = 0.05f
        const val LEGACY_RELATIVE_BOSS_PANEL_Y_RATIO = 0.77f
        const val LEGACY_RELATIVE_BOSS_PANEL_WIDTH_RATIO = 0.25f
        const val BOSS_PANEL_DYNAMIC_WIDTH = 240.0f
        const val BOSS_PANEL_RIGHT_MARGIN = 48.0f
        const val BOSS_PANEL_SCREEN_PADDING = 32.0f
        const val BOSS_PANEL_Y_OFFSET_RATIO = 0.25f
        const val SETTINGS_SCALE_FIELD_NAME = "scale"
        const val SETTINGS_RENDER_SCALE_FIELD_NAME = "renderScale"
    }

    @Test
    fun patchInPlace_rewritesMerchantAndHexaghostTargetsAndIsIdempotent() {
        val tempDir = Files.createTempDirectory("downfall-import-patcher-test")
        val jarFile = tempDir.resolve("Downfall.jar").toFile()
        createDownfallJar(jarFile)

        val firstPatch = DownfallImportCompatPatcher.patchInPlace(jarFile)
        assertEquals(4, firstPatch.patchedClassEntries)
        assertEquals(2, firstPatch.patchedMerchantClassEntries)
        assertEquals(1, firstPatch.patchedHexaghostBodyClassEntries)
        assertEquals(1, firstPatch.patchedBossMechanicPanelClassEntries)

        assertTrue(hasMerchantRugCenteredDrawX(jarFile, "downfall/monsters/FleeingMerchant.class"))
        assertTrue(hasMerchantRugCenteredDrawX(jarFile, "charbosses/bosses/Merchant/CharBossMerchant.class"))
        assertTrue(hasRenderScaleAlignedMyBody(jarFile))
        assertTrue(hasBossRelativeMechanicPanelLayout(jarFile))

        val secondPatch = DownfallImportCompatPatcher.patchInPlace(jarFile)
        assertEquals(0, secondPatch.patchedClassEntries)
        assertEquals(0, secondPatch.patchedMerchantClassEntries)
        assertEquals(0, secondPatch.patchedHexaghostBodyClassEntries)
        assertEquals(0, secondPatch.patchedBossMechanicPanelClassEntries)
    }

    @Test
    fun patchInPlace_returnsZeroesWhenTargetClassesAreMissing() {
        val tempDir = Files.createTempDirectory("downfall-import-patcher-empty")
        val jarFile = tempDir.resolve("OtherMod.jar").toFile()
        ZipOutputStream(jarFile.outputStream()).use { zipOut ->
            zipOut.putNextEntry(ZipEntry("example/Placeholder.class"))
            zipOut.write(byteArrayOf(0x00))
            zipOut.closeEntry()
        }

        val patchResult = DownfallImportCompatPatcher.patchInPlace(jarFile)
        assertEquals(0, patchResult.patchedClassEntries)
        assertEquals(0, patchResult.patchedMerchantClassEntries)
        assertEquals(0, patchResult.patchedHexaghostBodyClassEntries)
        assertEquals(0, patchResult.patchedBossMechanicPanelClassEntries)
    }

    @Test
    fun patchInPlace_rewritesPreviousRelativeBossPanelLayout() {
        val tempDir = Files.createTempDirectory("downfall-import-patcher-boss-panel-upgrade")
        val jarFile = tempDir.resolve("Downfall.jar").toFile()
        ZipOutputStream(jarFile.outputStream()).use { zipOut ->
            writeClassEntry(
                zipOut = zipOut,
                entryName = "charbosses/BossMechanicDisplayPanel.class",
                classBytes = buildRelativeBossMechanicDisplayPanelClassBytes(
                    xRatio = LEGACY_RELATIVE_BOSS_PANEL_X_RATIO,
                    yRatio = LEGACY_RELATIVE_BOSS_PANEL_Y_RATIO,
                    widthRatio = LEGACY_RELATIVE_BOSS_PANEL_WIDTH_RATIO
                )
            )
        }

        val patchResult = DownfallImportCompatPatcher.patchInPlace(jarFile)
        assertEquals(1, patchResult.patchedClassEntries)
        assertEquals(1, patchResult.patchedBossMechanicPanelClassEntries)
        assertTrue(hasBossRelativeMechanicPanelLayout(jarFile))
    }

    @Test
    fun patchInPlace_normalizesLegacyHexaghostBodyOffsets() {
        val tempDir = Files.createTempDirectory("downfall-import-patcher-legacy-hexa")
        val jarFile = tempDir.resolve("Downfall.jar").toFile()
        ZipOutputStream(jarFile.outputStream()).use { zipOut ->
            writeClassEntry(
                zipOut = zipOut,
                entryName = "theHexaghost/vfx/MyBody.class",
                classBytes = buildMyBodyClassBytes(
                    xOffset = 256.0f,
                    yOffset = 268.0f,
                    scaleOffsets = true,
                    useCorrectXFineOffsetSign = true
                )
            )
        }

        val patchResult = DownfallImportCompatPatcher.patchInPlace(jarFile)
        assertEquals(1, patchResult.patchedClassEntries)
        assertEquals(0, patchResult.patchedMerchantClassEntries)
        assertEquals(1, patchResult.patchedHexaghostBodyClassEntries)
        assertEquals(0, patchResult.patchedBossMechanicPanelClassEntries)
        assertTrue(hasRenderScaleAlignedMyBody(jarFile))
    }

    private fun createDownfallJar(jarFile: File) {
        ZipOutputStream(jarFile.outputStream()).use { zipOut ->
            writeClassEntry(
                zipOut = zipOut,
                entryName = "downfall/monsters/FleeingMerchant.class",
                classBytes = buildMerchantClassBytes("downfall/monsters/FleeingMerchant")
            )
            writeClassEntry(
                zipOut = zipOut,
                entryName = "charbosses/bosses/Merchant/CharBossMerchant.class",
                classBytes = buildMerchantClassBytes("charbosses/bosses/Merchant/CharBossMerchant")
            )
            writeClassEntry(
                zipOut = zipOut,
                entryName = "theHexaghost/vfx/MyBody.class",
                classBytes = buildMyBodyClassBytes()
            )
            writeClassEntry(
                zipOut = zipOut,
                entryName = "charbosses/BossMechanicDisplayPanel.class",
                classBytes = buildBossMechanicDisplayPanelClassBytes()
            )
        }
    }

    private fun writeClassEntry(zipOut: ZipOutputStream, entryName: String, classBytes: ByteArray) {
        zipOut.putNextEntry(ZipEntry(entryName))
        zipOut.write(classBytes)
        zipOut.closeEntry()
    }

    private fun buildMerchantClassBytes(className: String): ByteArray {
        val classWriter = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        classWriter.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null)
        classWriter.visitField(Opcodes.ACC_PUBLIC, "drawX", "F", null, null).visitEnd()

        val constructor = classWriter.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        constructor.visitCode()
        constructor.visitVarInsn(Opcodes.ALOAD, 0)
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        constructor.visitVarInsn(Opcodes.ALOAD, 0)
        constructor.visitLdcInsn(1260.0f)
        constructor.visitFieldInsn(
            Opcodes.GETSTATIC,
            "com/megacrit/cardcrawl/core/Settings",
            "scale",
            "F"
        )
        constructor.visitInsn(Opcodes.FMUL)
        constructor.visitFieldInsn(Opcodes.PUTFIELD, className, "drawX", "F")
        constructor.visitInsn(Opcodes.RETURN)
        constructor.visitMaxs(0, 0)
        constructor.visitEnd()

        classWriter.visitEnd()
        return classWriter.toByteArray()
    }

    private fun buildMyBodyClassBytes(
        xOffset: Float = 270.0f,
        yOffset: Float = 256.0f,
        scaleOffsets: Boolean = false,
        useCorrectXFineOffsetSign: Boolean = false,
        scaleFieldName: String = SETTINGS_SCALE_FIELD_NAME
    ): ByteArray {
        val classWriter = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        classWriter.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "theHexaghost/vfx/MyBody", null, "java/lang/Object", null)
        classWriter.visitField(Opcodes.ACC_PUBLIC, "XOffset", "F", null, null).visitEnd()
        classWriter.visitField(Opcodes.ACC_PUBLIC, "YOffset", "F", null, null).visitEnd()
        classWriter.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC, "BODY_OFFSET_Y", "F", null, null).visitEnd()

        val constructor = classWriter.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        constructor.visitCode()
        constructor.visitVarInsn(Opcodes.ALOAD, 0)
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        constructor.visitInsn(Opcodes.RETURN)
        constructor.visitMaxs(0, 0)
        constructor.visitEnd()

        val renderMethod = classWriter.visitMethod(
            Opcodes.ACC_PUBLIC,
            "render",
            "(Lcom/badlogic/gdx/graphics/g2d/SpriteBatch;)V",
            null,
            null
        )
        renderMethod.visitCode()
        renderMethod.visitVarInsn(Opcodes.ALOAD, 0)
        renderMethod.visitFieldInsn(Opcodes.GETFIELD, "theHexaghost/vfx/MyBody", "XOffset", "F")
        renderMethod.visitFieldInsn(
            Opcodes.GETSTATIC,
            "com/megacrit/cardcrawl/dungeons/AbstractDungeon",
            "player",
            "Lcom/megacrit/cardcrawl/characters/AbstractPlayer;"
        )
        renderMethod.visitFieldInsn(
            Opcodes.GETFIELD,
            "com/megacrit/cardcrawl/characters/AbstractPlayer",
            "drawX",
            "F"
        )
        renderMethod.visitInsn(Opcodes.FADD)
        renderMethod.visitLdcInsn(xOffset)
        if (scaleOffsets) {
            renderMethod.visitFieldInsn(
                Opcodes.GETSTATIC,
                "com/megacrit/cardcrawl/core/Settings",
                scaleFieldName,
                "F"
            )
            renderMethod.visitInsn(Opcodes.FMUL)
        }
        renderMethod.visitInsn(Opcodes.FSUB)
        renderMethod.visitFieldInsn(
            Opcodes.GETSTATIC,
            "com/megacrit/cardcrawl/dungeons/AbstractDungeon",
            "player",
            "Lcom/megacrit/cardcrawl/characters/AbstractPlayer;"
        )
        renderMethod.visitFieldInsn(
            Opcodes.GETFIELD,
            "com/megacrit/cardcrawl/characters/AbstractPlayer",
            "animX",
            "F"
        )
        renderMethod.visitInsn(Opcodes.FADD)
        renderMethod.visitLdcInsn(12.0f)
        renderMethod.visitFieldInsn(
            Opcodes.GETSTATIC,
            "com/megacrit/cardcrawl/core/Settings",
            scaleFieldName,
            "F"
        )
        renderMethod.visitInsn(Opcodes.FMUL)
        renderMethod.visitInsn(if (useCorrectXFineOffsetSign) Opcodes.FADD else Opcodes.FSUB)
        renderMethod.visitInsn(Opcodes.POP)

        renderMethod.visitVarInsn(Opcodes.ALOAD, 0)
        renderMethod.visitFieldInsn(Opcodes.GETFIELD, "theHexaghost/vfx/MyBody", "XOffset", "F")
        renderMethod.visitFieldInsn(
            Opcodes.GETSTATIC,
            "com/megacrit/cardcrawl/dungeons/AbstractDungeon",
            "player",
            "Lcom/megacrit/cardcrawl/characters/AbstractPlayer;"
        )
        renderMethod.visitFieldInsn(
            Opcodes.GETFIELD,
            "com/megacrit/cardcrawl/characters/AbstractPlayer",
            "drawX",
            "F"
        )
        renderMethod.visitInsn(Opcodes.FADD)
        renderMethod.visitLdcInsn(xOffset)
        if (scaleOffsets) {
            renderMethod.visitFieldInsn(
                Opcodes.GETSTATIC,
                "com/megacrit/cardcrawl/core/Settings",
                scaleFieldName,
                "F"
            )
            renderMethod.visitInsn(Opcodes.FMUL)
        }
        renderMethod.visitInsn(Opcodes.FSUB)
        renderMethod.visitFieldInsn(
            Opcodes.GETSTATIC,
            "com/megacrit/cardcrawl/dungeons/AbstractDungeon",
            "player",
            "Lcom/megacrit/cardcrawl/characters/AbstractPlayer;"
        )
        renderMethod.visitFieldInsn(
            Opcodes.GETFIELD,
            "com/megacrit/cardcrawl/characters/AbstractPlayer",
            "animX",
            "F"
        )
        renderMethod.visitInsn(Opcodes.FADD)
        renderMethod.visitLdcInsn(6.0f)
        renderMethod.visitFieldInsn(
            Opcodes.GETSTATIC,
            "com/megacrit/cardcrawl/core/Settings",
            scaleFieldName,
            "F"
        )
        renderMethod.visitInsn(Opcodes.FMUL)
        renderMethod.visitInsn(if (useCorrectXFineOffsetSign) Opcodes.FADD else Opcodes.FSUB)
        renderMethod.visitInsn(Opcodes.POP)

        renderMethod.visitInsn(Opcodes.FCONST_0)
        renderMethod.visitLdcInsn(yOffset)
        if (scaleOffsets) {
            renderMethod.visitFieldInsn(
                Opcodes.GETSTATIC,
                "com/megacrit/cardcrawl/core/Settings",
                scaleFieldName,
                "F"
            )
            renderMethod.visitInsn(Opcodes.FMUL)
        }
        renderMethod.visitInsn(Opcodes.FSUB)
        renderMethod.visitFieldInsn(
            Opcodes.GETSTATIC,
            "theHexaghost/vfx/MyBody",
            "BODY_OFFSET_Y",
            "F"
        )
        renderMethod.visitInsn(Opcodes.FADD)
        renderMethod.visitInsn(Opcodes.POP)
        renderMethod.visitFieldInsn(
            Opcodes.GETSTATIC,
            "com/megacrit/cardcrawl/core/Settings",
            scaleFieldName,
            "F"
        )
        renderMethod.visitInsn(Opcodes.POP)
        renderMethod.visitInsn(Opcodes.RETURN)
        renderMethod.visitMaxs(0, 0)
        renderMethod.visitEnd()

        val clinitMethod = classWriter.visitMethod(
            Opcodes.ACC_STATIC,
            "<clinit>",
            "()V",
            null,
            null
        )
        clinitMethod.visitCode()
        clinitMethod.visitLdcInsn(256.0f)
        clinitMethod.visitFieldInsn(
            Opcodes.GETSTATIC,
            "com/megacrit/cardcrawl/core/Settings",
            scaleFieldName,
            "F"
        )
        clinitMethod.visitInsn(Opcodes.FMUL)
        clinitMethod.visitFieldInsn(Opcodes.PUTSTATIC, "theHexaghost/vfx/MyBody", "BODY_OFFSET_Y", "F")
        clinitMethod.visitInsn(Opcodes.RETURN)
        clinitMethod.visitMaxs(0, 0)
        clinitMethod.visitEnd()

        classWriter.visitEnd()
        return classWriter.toByteArray()
    }

    private fun buildBossMechanicDisplayPanelClassBytes(): ByteArray {
        val classWriter = ClassWriter(0)
        classWriter.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC,
            "charbosses/BossMechanicDisplayPanel",
            null,
            "automaton/EasyInfoDisplayPanel",
            null
        )
        classWriter.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "mechanicDesc", "Ljava/lang/String;", null, null).visitEnd()
        classWriter.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC, "X", "I", null, null).visitEnd()
        classWriter.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC, "Y", "I", null, null).visitEnd()
        classWriter.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC, "WIDTH", "I", null, null).visitEnd()

        val constructor = classWriter.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        constructor.visitCode()
        constructor.visitVarInsn(Opcodes.ALOAD, 0)
        constructor.visitFieldInsn(Opcodes.GETSTATIC, "charbosses/BossMechanicDisplayPanel", "X", "I")
        constructor.visitInsn(Opcodes.I2F)
        constructor.visitFieldInsn(Opcodes.GETSTATIC, "charbosses/BossMechanicDisplayPanel", "Y", "I")
        constructor.visitInsn(Opcodes.I2F)
        constructor.visitFieldInsn(Opcodes.GETSTATIC, "charbosses/BossMechanicDisplayPanel", "WIDTH", "I")
        constructor.visitInsn(Opcodes.I2F)
        constructor.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            "automaton/EasyInfoDisplayPanel",
            "<init>",
            "(FFF)V",
            false
        )
        constructor.visitInsn(Opcodes.RETURN)
        constructor.visitMaxs(4, 1)
        constructor.visitEnd()

        val getDescription = classWriter.visitMethod(Opcodes.ACC_PUBLIC, "getDescription", "()Ljava/lang/String;", null, null)
        getDescription.visitCode()
        getDescription.visitFieldInsn(
            Opcodes.GETSTATIC,
            "charbosses/BossMechanicDisplayPanel",
            "mechanicDesc",
            "Ljava/lang/String;"
        )
        getDescription.visitInsn(Opcodes.ARETURN)
        getDescription.visitMaxs(1, 1)
        getDescription.visitEnd()

        val clinitMethod = classWriter.visitMethod(
            Opcodes.ACC_STATIC,
            "<clinit>",
            "()V",
            null,
            null
        )
        clinitMethod.visitCode()
        clinitMethod.visitLdcInsn("NORENDER")
        clinitMethod.visitFieldInsn(
            Opcodes.PUTSTATIC,
            "charbosses/BossMechanicDisplayPanel",
            "mechanicDesc",
            "Ljava/lang/String;"
        )
        clinitMethod.visitLdcInsn(1550)
        clinitMethod.visitFieldInsn(Opcodes.PUTSTATIC, "charbosses/BossMechanicDisplayPanel", "X", "I")
        clinitMethod.visitLdcInsn(550)
        clinitMethod.visitFieldInsn(Opcodes.PUTSTATIC, "charbosses/BossMechanicDisplayPanel", "Y", "I")
        clinitMethod.visitLdcInsn(287)
        clinitMethod.visitFieldInsn(Opcodes.PUTSTATIC, "charbosses/BossMechanicDisplayPanel", "WIDTH", "I")
        clinitMethod.visitInsn(Opcodes.RETURN)
        clinitMethod.visitMaxs(1, 0)
        clinitMethod.visitEnd()

        classWriter.visitEnd()
        return classWriter.toByteArray()
    }

    private fun buildRelativeBossMechanicDisplayPanelClassBytes(
        xRatio: Float,
        yRatio: Float,
        widthRatio: Float
    ): ByteArray {
        val classWriter = ClassWriter(0)
        classWriter.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC,
            "charbosses/BossMechanicDisplayPanel",
            null,
            "automaton/EasyInfoDisplayPanel",
            null
        )
        classWriter.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "mechanicDesc", "Ljava/lang/String;", null, null).visitEnd()

        val constructor = classWriter.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        constructor.visitCode()
        constructor.visitVarInsn(Opcodes.ALOAD, 0)
        constructor.visitFieldInsn(Opcodes.GETSTATIC, "com/megacrit/cardcrawl/core/Settings", "WIDTH", "I")
        constructor.visitInsn(Opcodes.I2F)
        constructor.visitLdcInsn(xRatio)
        constructor.visitInsn(Opcodes.FMUL)
        constructor.visitFieldInsn(Opcodes.GETSTATIC, "com/megacrit/cardcrawl/core/Settings", "HEIGHT", "I")
        constructor.visitInsn(Opcodes.I2F)
        constructor.visitLdcInsn(yRatio)
        constructor.visitInsn(Opcodes.FMUL)
        constructor.visitFieldInsn(Opcodes.GETSTATIC, "com/megacrit/cardcrawl/core/Settings", "WIDTH", "I")
        constructor.visitInsn(Opcodes.I2F)
        constructor.visitLdcInsn(widthRatio)
        constructor.visitInsn(Opcodes.FMUL)
        constructor.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            "automaton/EasyInfoDisplayPanel",
            "<init>",
            "(FFF)V",
            false
        )
        constructor.visitInsn(Opcodes.RETURN)
        constructor.visitMaxs(4, 1)
        constructor.visitEnd()

        val getDescription = classWriter.visitMethod(Opcodes.ACC_PUBLIC, "getDescription", "()Ljava/lang/String;", null, null)
        getDescription.visitCode()
        getDescription.visitFieldInsn(
            Opcodes.GETSTATIC,
            "charbosses/BossMechanicDisplayPanel",
            "mechanicDesc",
            "Ljava/lang/String;"
        )
        getDescription.visitInsn(Opcodes.ARETURN)
        getDescription.visitMaxs(1, 1)
        getDescription.visitEnd()

        classWriter.visitEnd()
        return classWriter.toByteArray()
    }

    private fun hasMerchantRugCenteredDrawX(jarFile: File, entryName: String): Boolean {
        val classBytes = JarFileIoUtils.readJarEntryBytes(jarFile, entryName)
        assertNotNull(classBytes)
        val constructor = readClassNode(classBytes!!).methods.firstOrNull { it.name == "<init>" }
        assertNotNull(constructor)
        return constructorHasRugCenteredDrawX(constructor!!)
    }

    private fun constructorHasRugCenteredDrawX(method: MethodNode): Boolean {
        val opcodes = method.instructions.toArray().toList()
        val hasWidthRead = opcodes.any { node ->
            node is FieldInsnNode &&
                node.opcode == Opcodes.GETSTATIC &&
                node.owner == "com/megacrit/cardcrawl/core/Settings" &&
                node.name == "WIDTH" &&
                node.desc == "I"
        }
        val hasScaleRead = opcodes.any { node ->
            node is FieldInsnNode &&
                node.opcode == Opcodes.GETSTATIC &&
                node.owner == "com/megacrit/cardcrawl/core/Settings" &&
                node.name == "scale" &&
                node.desc == "F"
        }
        val hasXScaleRead = opcodes.any { node ->
            node is FieldInsnNode &&
                node.opcode == Opcodes.GETSTATIC &&
                node.owner == "com/megacrit/cardcrawl/core/Settings" &&
                node.name == "xScale" &&
                node.desc == "F"
        }
        val hasRugLeftOffset = opcodes.any { node ->
            node is LdcInsnNode && node.cst == 34.0f
        }
        val hasRugCenterOffset = opcodes.any { node ->
            node is LdcInsnNode && node.cst == 256.0f
        }
        val stillHasRaw1260 = opcodes.any { node ->
            node is LdcInsnNode && node.cst == 1260.0f
        }
        return hasWidthRead && hasScaleRead && hasRugLeftOffset && hasRugCenterOffset &&
            !hasXScaleRead && !stillHasRaw1260
    }

    private fun hasRenderScaleAlignedMyBody(jarFile: File): Boolean {
        val classBytes = JarFileIoUtils.readJarEntryBytes(jarFile, "theHexaghost/vfx/MyBody.class")
        assertNotNull(classBytes)
        val classNode = readClassNode(classBytes!!)
        val renderMethod = classNode.methods.firstOrNull { method ->
            method.name == "render"
        }
        val clinitMethod = classNode.methods.firstOrNull { method ->
            method.name == "<clinit>"
        }
        assertNotNull(renderMethod)
        assertNotNull(clinitMethod)

        val instructions = meaningfulInstructions(renderMethod!!)
        val clinitInstructions = meaningfulInstructions(clinitMethod!!)
        var restoredXAnchorCount = 0
        var restoredYAnchorCount = 0
        var restoredLargeFineOffset = false
        var restoredSmallFineOffset = false
        var hasRenderScaleReadInRender = false
        instructions.forEach { node ->
            if (node is LdcInsnNode) {
                if (node.cst == 270.0f) {
                    val next = nextMeaningful(node)
                    val animXField = findFieldAccessAfter(
                        start = next,
                        owner = "com/megacrit/cardcrawl/characters/AbstractPlayer",
                        name = "animX",
                        desc = "F",
                        maxHops = 2
                    )
                    if (next?.opcode == Opcodes.FSUB &&
                        animXField != null
                    ) {
                        restoredXAnchorCount++
                    }
                }
                if (node.cst == 256.0f) {
                    val next = nextMeaningful(node)
                    val afterNext = nextMeaningful(next)
                    if (next?.opcode == Opcodes.FSUB &&
                        afterNext is FieldInsnNode &&
                        afterNext.owner == "theHexaghost/vfx/MyBody" &&
                        afterNext.name == "BODY_OFFSET_Y"
                    ) {
                        restoredYAnchorCount++
                    }
                }
                if (node.cst == 12.0f || node.cst == 6.0f) {
                    val scaleNode = nextMeaningful(node)
                    val scaleMulNode = nextMeaningful(scaleNode)
                    val arithmeticNode = nextMeaningful(scaleMulNode)
                    val prevNode = previousMeaningful(node)
                    val prevPrevNode = previousMeaningful(prevNode)
                    if (scaleNode is FieldInsnNode &&
                        scaleNode.owner == "com/megacrit/cardcrawl/core/Settings" &&
                        scaleNode.name == SETTINGS_RENDER_SCALE_FIELD_NAME &&
                        scaleMulNode?.opcode == Opcodes.FMUL &&
                        arithmeticNode?.opcode == Opcodes.FSUB &&
                        prevNode?.opcode == Opcodes.FADD &&
                        prevPrevNode is FieldInsnNode &&
                        prevPrevNode.owner == "com/megacrit/cardcrawl/characters/AbstractPlayer" &&
                        prevPrevNode.name == "animX"
                    ) {
                        if (node.cst == 12.0f) {
                            restoredLargeFineOffset = true
                        } else {
                            restoredSmallFineOffset = true
                        }
                    }
                }
            }
            if (node is FieldInsnNode &&
                node.opcode == Opcodes.GETSTATIC &&
                node.owner == "com/megacrit/cardcrawl/core/Settings" &&
                node.name == SETTINGS_RENDER_SCALE_FIELD_NAME
            ) {
                hasRenderScaleReadInRender = true
            }
        }
        val clinitHasRenderScale = clinitInstructions.any { node ->
            node is FieldInsnNode &&
                node.opcode == Opcodes.GETSTATIC &&
                node.owner == "com/megacrit/cardcrawl/core/Settings" &&
                node.name == SETTINGS_RENDER_SCALE_FIELD_NAME
        }
        val hasLegacyScaleRead = (instructions + clinitInstructions).any { node ->
            node is FieldInsnNode &&
                node.opcode == Opcodes.GETSTATIC &&
                node.owner == "com/megacrit/cardcrawl/core/Settings" &&
                node.name == SETTINGS_SCALE_FIELD_NAME
        }
        assertFalse(instructions.any { it is LdcInsnNode && it.cst == 268.0f })
        assertFalse(instructions.any { it is LdcInsnNode && it.cst == 244.0f })
        instructions.forEach { node ->
            if (node is LdcInsnNode && (node.cst == 256.0f || node.cst == 270.0f)) {
                val next = nextMeaningful(node)
                assertNotEquals(
                    "Hexaghost origin constants should not be multiplied directly by a scale field",
                    Opcodes.GETSTATIC,
                    next?.opcode
                )
            }
        }
        return restoredXAnchorCount == 2 &&
            restoredYAnchorCount == 1 &&
            restoredLargeFineOffset &&
            restoredSmallFineOffset &&
            hasRenderScaleReadInRender &&
            clinitHasRenderScale &&
            !hasLegacyScaleRead
    }

    private fun hasBossRelativeMechanicPanelLayout(jarFile: File): Boolean {
        val classBytes = JarFileIoUtils.readJarEntryBytes(jarFile, "charbosses/BossMechanicDisplayPanel.class")
        assertNotNull(classBytes)
        val getDescription = readClassNode(classBytes!!).methods.firstOrNull { it.name == "getDescription" }
        assertNotNull(getDescription)
        val instructions = meaningfulInstructions(getDescription!!)
        val hasBossRead = instructions.any { node ->
            node is FieldInsnNode &&
                node.opcode == Opcodes.GETSTATIC &&
                node.owner == "charbosses/bosses/AbstractCharBoss" &&
                node.name == "boss" &&
                node.desc == "Lcharbosses/bosses/AbstractCharBoss;"
        }
        val hasHitboxRead = instructions.any { node ->
            node is FieldInsnNode &&
                node.opcode == Opcodes.GETFIELD &&
                node.owner == "com/megacrit/cardcrawl/core/AbstractCreature" &&
                node.name == "hb" &&
                node.desc == "Lcom/megacrit/cardcrawl/helpers/Hitbox;"
        }
        val hasHitboxCXRead = instructions.any { node ->
            node is FieldInsnNode &&
                node.opcode == Opcodes.GETFIELD &&
                node.owner == "com/megacrit/cardcrawl/helpers/Hitbox" &&
                node.name == "cX" &&
                node.desc == "F"
        }
        val hasHitboxCYRead = instructions.any { node ->
            node is FieldInsnNode &&
                node.opcode == Opcodes.GETFIELD &&
                node.owner == "com/megacrit/cardcrawl/helpers/Hitbox" &&
                node.name == "cY" &&
                node.desc == "F"
        }
        val hasHitboxWidthRead = instructions.any { node ->
            node is FieldInsnNode &&
                node.opcode == Opcodes.GETFIELD &&
                node.owner == "com/megacrit/cardcrawl/helpers/Hitbox" &&
                node.name == "width" &&
                node.desc == "F"
        }
        val hasHitboxHeightRead = instructions.any { node ->
            node is FieldInsnNode &&
                node.opcode == Opcodes.GETFIELD &&
                node.owner == "com/megacrit/cardcrawl/helpers/Hitbox" &&
                node.name == "height" &&
                node.desc == "F"
        }
        val hasDynamicWidth = instructions.any { node ->
            node is LdcInsnNode && node.cst == BOSS_PANEL_DYNAMIC_WIDTH
        }
        val hasRightMargin = instructions.any { node ->
            node is LdcInsnNode && node.cst == BOSS_PANEL_RIGHT_MARGIN
        }
        val hasScreenPadding = instructions.any { node ->
            node is LdcInsnNode && node.cst == BOSS_PANEL_SCREEN_PADDING
        }
        val hasYOffsetRatio = instructions.any { node ->
            node is LdcInsnNode && node.cst == BOSS_PANEL_Y_OFFSET_RATIO
        }
        val hasMathMin = instructions.any { node ->
            node is MethodInsnNode &&
                node.opcode == Opcodes.INVOKESTATIC &&
                node.owner == "java/lang/Math" &&
                node.name == "min" &&
                node.desc == "(FF)F"
        }
        val hasPanelWrites = setOf("x", "y", "width").all { fieldName ->
            instructions.any { node ->
                node is FieldInsnNode &&
                    node.opcode == Opcodes.PUTFIELD &&
                    node.owner == "automaton/EasyInfoDisplayPanel" &&
                    node.name == fieldName &&
                    node.desc == "F"
            }
        }
        return hasBossRead &&
            hasHitboxRead &&
            hasHitboxCXRead &&
            hasHitboxCYRead &&
            hasHitboxWidthRead &&
            hasHitboxHeightRead &&
            hasDynamicWidth &&
            hasRightMargin &&
            hasScreenPadding &&
            hasYOffsetRatio &&
            hasMathMin &&
            hasPanelWrites
    }

    private fun readClassNode(classBytes: ByteArray): ClassNode {
        val classNode = ClassNode()
        ClassReader(classBytes).accept(classNode, 0)
        return classNode
    }

    private fun meaningfulInstructions(method: MethodNode): List<AbstractInsnNode> {
        val result = ArrayList<AbstractInsnNode>()
        var current: AbstractInsnNode? = method.instructions.first
        while (current != null) {
            if (current !is LabelNode && current !is LineNumberNode && current !is FrameNode) {
                result += current
            }
            current = current.next
        }
        return result
    }

    private fun previousMeaningful(node: AbstractInsnNode?): AbstractInsnNode? {
        var current = node?.previous
        while (current is LabelNode || current is LineNumberNode || current is FrameNode) {
            current = current.previous
        }
        return current
    }

    private fun nextMeaningful(node: AbstractInsnNode?): AbstractInsnNode? {
        var current = node?.next
        while (current is LabelNode || current is LineNumberNode || current is FrameNode) {
            current = current.next
        }
        return current
    }

    private fun findFieldAccessAfter(
        start: AbstractInsnNode?,
        owner: String,
        name: String,
        desc: String,
        maxHops: Int
    ): FieldInsnNode? {
        var current = start
        repeat(maxHops) {
            current = nextMeaningful(current)
            val fieldNode = current as? FieldInsnNode ?: return@repeat
            if (fieldNode.owner == owner && fieldNode.name == name && fieldNode.desc == desc) {
                return fieldNode
            }
        }
        return null
    }
}
