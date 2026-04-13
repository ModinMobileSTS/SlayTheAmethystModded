package io.stamethyst.backend.mods

import java.io.IOException
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode

internal object StsFreeTypeGlyphFallbackPatcher {
    private const val TARGET_INTERNAL_NAME =
        "com/badlogic/gdx/graphics/g2d/freetype/FreeTypeFontGenerator\$FreeTypeBitmapFontData"
    private const val TARGET_METHOD_NAME = "getGlyph"
    private const val TARGET_METHOD_DESC =
        "(C)Lcom/badlogic/gdx/graphics/g2d/BitmapFont\$Glyph;"
    private const val GENERATOR_INTERNAL_NAME =
        "com/badlogic/gdx/graphics/g2d/freetype/FreeTypeFontGenerator"
    private const val CREATE_GLYPH_METHOD_NAME = "createGlyph"
    private const val CREATE_GLYPH_METHOD_DESC =
        "(CLcom/badlogic/gdx/graphics/g2d/freetype/FreeTypeFontGenerator\$FreeTypeBitmapFontData;" +
            "Lcom/badlogic/gdx/graphics/g2d/freetype/FreeTypeFontGenerator\$FreeTypeFontParameter;" +
            "Lcom/badlogic/gdx/graphics/g2d/freetype/FreeType\$Stroker;" +
            "FLcom/badlogic/gdx/graphics/g2d/PixmapPacker;)" +
            "Lcom/badlogic/gdx/graphics/g2d/BitmapFont\$Glyph;"
    private const val HELPER_INTERNAL_NAME =
        "com/badlogic/gdx/graphics/g2d/freetype/FreeTypeGlyphFallbackCompat"
    private const val HELPER_METHOD_NAME = "createGlyphOrFallback"
    private const val HELPER_METHOD_DESC =
        "(Lcom/badlogic/gdx/graphics/g2d/freetype/FreeTypeFontGenerator;" +
            "CLcom/badlogic/gdx/graphics/g2d/freetype/FreeTypeFontGenerator\$FreeTypeBitmapFontData;" +
            "Lcom/badlogic/gdx/graphics/g2d/freetype/FreeTypeFontGenerator\$FreeTypeFontParameter;" +
            "Lcom/badlogic/gdx/graphics/g2d/freetype/FreeType\$Stroker;" +
            "FLcom/badlogic/gdx/graphics/g2d/PixmapPacker;)" +
            "Lcom/badlogic/gdx/graphics/g2d/BitmapFont\$Glyph;"

    fun patchFreeTypeBitmapFontDataClass(classBytes: ByteArray): ByteArray {
        val classNode = readClassNode(classBytes)
        if (classNode.name != TARGET_INTERNAL_NAME) {
            throw IOException("Unexpected FreeType font data class: ${classNode.name}")
        }
        val getGlyphMethod = classNode.methods.firstOrNull { method ->
            method.name == TARGET_METHOD_NAME && method.desc == TARGET_METHOD_DESC
        } ?: throw IOException("Unsupported desktop-1.0.jar: FreeTypeBitmapFontData.getGlyph(char) not found")

        if (isPatchedGetGlyph(getGlyphMethod)) {
            return classBytes
        }

        var replacements = 0
        var current = getGlyphMethod.instructions.first
        while (current != null) {
            val next = current.next
            val call = current as? MethodInsnNode
            if (call != null &&
                call.opcode == Opcodes.INVOKEVIRTUAL &&
                call.owner == GENERATOR_INTERNAL_NAME &&
                call.name == CREATE_GLYPH_METHOD_NAME &&
                call.desc == CREATE_GLYPH_METHOD_DESC
            ) {
                getGlyphMethod.instructions.set(
                    call,
                    MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        HELPER_INTERNAL_NAME,
                        HELPER_METHOD_NAME,
                        HELPER_METHOD_DESC,
                        false
                    )
                )
                replacements++
            }
            current = next
        }

        if (replacements != 1) {
            throw IOException(
                "Unsupported desktop-1.0.jar: expected 1 FreeType createGlyph call, found $replacements"
            )
        }

        return writeClass(classNode)
    }

    fun isPatchedFreeTypeBitmapFontDataClass(classBytes: ByteArray): Boolean {
        val classNode = readClassNode(classBytes)
        if (classNode.name != TARGET_INTERNAL_NAME) {
            return false
        }
        val getGlyphMethod = classNode.methods.firstOrNull { method ->
            method.name == TARGET_METHOD_NAME && method.desc == TARGET_METHOD_DESC
        } ?: return false
        return isPatchedGetGlyph(getGlyphMethod)
    }

    private fun isPatchedGetGlyph(method: MethodNode): Boolean {
        var sawHelperCall = false
        var sawOriginalCall = false
        var current = method.instructions.first
        while (current != null) {
            val call = current as? MethodInsnNode
            if (call != null) {
                if (call.opcode == Opcodes.INVOKESTATIC &&
                    call.owner == HELPER_INTERNAL_NAME &&
                    call.name == HELPER_METHOD_NAME &&
                    call.desc == HELPER_METHOD_DESC
                ) {
                    sawHelperCall = true
                } else if (call.opcode == Opcodes.INVOKEVIRTUAL &&
                    call.owner == GENERATOR_INTERNAL_NAME &&
                    call.name == CREATE_GLYPH_METHOD_NAME &&
                    call.desc == CREATE_GLYPH_METHOD_DESC
                ) {
                    sawOriginalCall = true
                }
            }
            current = current.next
        }
        return sawHelperCall && !sawOriginalCall
    }

    private fun readClassNode(classBytes: ByteArray): ClassNode {
        val classNode = ClassNode()
        ClassReader(classBytes).accept(classNode, 0)
        return classNode
    }

    private fun writeClass(classNode: ClassNode): ByteArray {
        val classWriter = ClassWriter(0)
        classNode.accept(classWriter)
        return classWriter.toByteArray()
    }
}
