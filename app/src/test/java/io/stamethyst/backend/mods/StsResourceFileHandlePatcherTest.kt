package io.stamethyst.backend.mods

import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.ClassNode

class StsResourceFileHandlePatcherTest {
    private val root = listOf(File("."), File("..")).first { File(it, "build-deps/desktop.jar").isFile }
    private val gameJar = File(root, "build-deps/desktop.jar")
    private val helperJar = File(root, "patches/gdx-patch/build/libs/gdx-patch.jar")
    private val fileHandleName = "com.badlogic.gdx.files.FileHandle"
    private val fileTypeName = "com.badlogic.gdx.Files\$FileType"

    @Test fun bothConstructorFormsNormalizeResourceHierarchyAndReads() {
        withRuntime { loader ->
            val handleClass = loader.loadClass("com.badlogic.gdx.backends.lwjgl.LwjglFileHandle")
            val typeClass = loader.loadClass(fileTypeName)
            for (type in listOf("Internal", "Classpath")) {
                for (argument in listOf<Any>("probe\\角色 models\\wild.atlas", File("probe\\角色 models\\wild.atlas"))) {
                    val handle = handleClass.getConstructor(argument.javaClass, typeClass)
                        .newInstance(argument, typeClass.getField(type).get(null))
                    assertEquals("probe/角色 models/wild.atlas", rawPath(loader, handle))
                    assertEquals("wild.atlas", invoke(handle, "name"))
                    assertEquals("probe/角色 models", invoke(invoke(handle, "parent")!!, "path"))
                    assertTrue(invoke(handle, "exists") as Boolean)
                    assertTrue((invoke(handle, "readBytes") as ByteArray).isNotEmpty())
                    val sibling = invoke(handle, "sibling", "nested\\wild.png")!!
                    assertEquals("probe/角色 models/nested/wild.png", rawPath(loader, sibling))
                    assertArrayEquals("wild pixels".toByteArray(), invoke(sibling, "readBytes") as ByteArray)
                    val child = invoke(invoke(handle, "parent")!!, "child", "nested\\wild.png")!!
                    assertEquals(rawPath(loader, sibling), rawPath(loader, child))
                    assertEquals(type, invoke(child, "type").toString())
                }
            }
        }
    }

    @Test fun realAtlasParserAvoidsRootNameCollisionAndResolvesBackslashPageNames() {
        withRuntime { loader ->
            for (type in listOf("Internal", "Classpath")) {
                for (skin in listOf("default", "wild")) {
                    val page = atlasPage(loader, type, "probe\\角色 models\\$skin.atlas")
                    val expected = if (skin == "wild") "nested/wild.png" else "default.png"
                    assertEquals("probe/角色 models/$expected", rawPath(loader, page))
                    assertArrayEquals("$skin pixels".toByteArray(), invoke(page, "readBytes") as ByteArray)
                }
            }
        }
    }

    @Test fun unpatchedRuntimeReproducesRootCollisionAndMissingPage() {
        // Baseline is the actual original bytecode, not a mocked path implementation.
        if (File.separatorChar != '/') return
        withRuntime(patched = false) { loader ->
            val default = atlasPage(loader, "Internal", "probe\\角色 models\\default.atlas")
            assertEquals("default.png", rawPath(loader, default))
            assertTrue(invoke(default, "exists") as Boolean) // Game font atlas, not mod pixels.
            assertFalse("default pixels".toByteArray().contentEquals(invoke(default, "readBytes") as ByteArray))
            val wild = atlasPage(loader, "Internal", "probe\\角色 models\\wild.atlas")
            assertFalse(invoke(wild, "exists") as Boolean)
        }
    }

    @Test fun portableResourcePathsAndFileSystemHandlesPreserveExistingSemantics() {
        withRuntime { loader ->
            val typeClass = loader.loadClass(fileTypeName)
            val handleClass = loader.loadClass("com.badlogic.gdx.backends.lwjgl.LwjglFileHandle")
            for (type in listOf("Absolute", "Local", "External", "Internal", "Classpath")) {
                val path = if (type in listOf("Internal", "Classpath")) "probe/角色 models/default.png"
                    else "saves\\literal-name"
                for (argument in listOf<Any>(path, File(path))) {
                    val handle = handleClass.getConstructor(argument.javaClass, typeClass)
                        .newInstance(argument, typeClass.getField(type).get(null))
                    assertEquals(File(path).path, rawPath(loader, handle))
                }
            }
            for (argument in listOf<Any>("saves\\literal-name", File("saves\\literal-name"))) {
                val handle = loader.loadClass(fileHandleName).getConstructor(argument.javaClass).newInstance(argument)
                assertEquals(File("saves\\literal-name").path, rawPath(loader, handle))
            }
            val empty = handleClass.getConstructor(String::class.java, typeClass)
                .newInstance("", typeClass.getField("Internal").get(null))
            assertEquals("", rawPath(loader, invoke(empty, "parent")!!))
        }
    }

    @Test fun missingResourcesStillFailRatherThanSearchByBasename() {
        withRuntime { loader ->
            val page = atlasPage(loader, "Internal", "probe\\角色 models\\missing.atlas")
            assertEquals("probe/角色 models/not-present.png", rawPath(loader, page))
            assertFalse(invoke(page, "exists") as Boolean)
            val error = assertThrows(InvocationTargetException::class.java) { invoke(page, "readBytes") }
            assertTrue(error.cause!!.message!!.contains("not-present.png"))
        }
    }

    @Test fun patchIsIdempotentAndRejectsMissingConstructors() {
        val original = originalBytes()
        assertFalse(StsResourceFileHandlePatcher.isPatchedFileHandleClass(original))
        val patched = StsResourceFileHandlePatcher.patchFileHandleClass(original)
        assertTrue(StsResourceFileHandlePatcher.isPatchedFileHandleClass(patched))
        assertArrayEquals(patched, StsResourceFileHandlePatcher.patchFileHandleClass(patched))
        val node = ClassNode().also { ClassReader(original).accept(it, 0) }
        node.methods.removeAll { it.name == "<init>" && it.desc.startsWith("(Ljava/io/File;") }
        val missing = ClassWriter(0).also(node::accept).toByteArray()
        assertFalse(StsResourceFileHandlePatcher.isPatchedFileHandleClass(missing))
        assertThrows(IOException::class.java) { StsResourceFileHandlePatcher.patchFileHandleClass(missing) }
    }

    @Test fun desktopPatchStateRequiresConstructorHookEvenWhenHelperIsAlreadyCurrent() {
        val helper = ZipFile(helperJar).use { zip ->
            zip.getInputStream(zip.getEntry(STS_PATCH_RESOURCE_FILE_HANDLE_COMPAT_CLASS)).use { it.readBytes() }
        }
        val entries = ZipFile(gameJar).use { zip ->
            fun bytes(name: String) = zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }
            linkedMapOf(
                STS_PATCH_RESOURCE_FILE_HANDLE_COMPAT_CLASS to helper,
                STS_PATCH_SHADER_PROGRAM_CLASS to StsShaderPrecisionCompatPatcher.patchShaderProgramClass(bytes(STS_PATCH_SHADER_PROGRAM_CLASS)),
                STS_PATCH_FREETYPE_BITMAP_FONT_DATA_CLASS to StsFreeTypeGlyphFallbackPatcher.patchFreeTypeBitmapFontDataClass(bytes(STS_PATCH_FREETYPE_BITMAP_FONT_DATA_CLASS)),
                STS_PATCH_OPTIONS_PANEL_CLASS to StsFramerateOptionsPatcher.patchOptionsPanelClass(bytes(STS_PATCH_OPTIONS_PANEL_CLASS))
            )
        }
        val check = StsDesktopJarPatcher::class.java.getDeclaredMethod(
            "isStsPatched", File::class.java, Map::class.java
        ).apply { isAccessible = true }
        val directory = Files.createTempDirectory(File(root, "agent-tmp").toPath(), "resource-patch-state-").toFile()
        try {
            val candidate = File(directory, "desktop.jar")
            fun matches(fileHandle: ByteArray?): Boolean {
                val candidateEntries = entries.toMutableMap()
                if (fileHandle != null) candidateEntries[STS_PATCH_FILE_HANDLE_CLASS] = fileHandle
                ZipOutputStream(candidate.outputStream()).use { zip ->
                    for ((name, bytes) in candidateEntries) {
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                }
                return check.invoke(StsDesktopJarPatcher, candidate,
                    mapOf(STS_PATCH_RESOURCE_FILE_HANDLE_COMPAT_CLASS to helper)) as Boolean
            }
            assertFalse(matches(null))
            assertFalse(matches(originalBytes()))
            assertTrue(matches(StsResourceFileHandlePatcher.patchFileHandleClass(originalBytes())))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun originalBytes(): ByteArray = ZipFile(gameJar).use { zip ->
        zip.getInputStream(zip.getEntry(STS_PATCH_FILE_HANDLE_CLASS)).use { it.readBytes() }
    }

    private fun atlasPage(loader: ClassLoader, type: String, path: String): Any {
        val files = loader.loadClass("com.badlogic.gdx.backends.lwjgl.LwjglFiles").getConstructor().newInstance()
        val handle = invoke(files, type.lowercase(), path)!!
        val handleClass = loader.loadClass(fileHandleName)
        val dataClass = loader.loadClass("com.badlogic.gdx.graphics.g2d.TextureAtlas\$TextureAtlasData")
        val data = dataClass.getConstructor(handleClass, handleClass, Boolean::class.javaPrimitiveType)
            .newInstance(handle, invoke(handle, "parent"), false)
        val pages = invoke(data, "getPages")!!
        val page = pages.javaClass.getMethod("get", Int::class.javaPrimitiveType).invoke(pages, 0)
        return requireNotNull(page.javaClass.getField("textureFile").get(page))
    }

    private fun rawPath(loader: ClassLoader, handle: Any): String =
        (loader.loadClass(fileHandleName).getDeclaredField("file").apply { isAccessible = true }.get(handle) as File).path

    private fun invoke(target: Any, name: String, vararg strings: String): Any? =
        target.javaClass.getMethod(name, *Array(strings.size) { String::class.java }).invoke(target, *strings)

    private fun withRuntime(patched: Boolean = true, block: (ClassLoader) -> Unit) {
        assertTrue("Build :patches:gdx-patch:jar before this integration test", helperJar.isFile)
        val tempRoot = File(root, "agent-tmp").apply { mkdirs() }
        val directory = Files.createTempDirectory(tempRoot.toPath(), "resource-path-test-").toFile()
        try {
            val fixture = File(directory, "resources.jar")
            ZipOutputStream(fixture.outputStream()).use { zip ->
                fun entry(name: String, contents: String) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(contents.toByteArray())
                    zip.closeEntry()
                }
                for ((skin, page) in listOf("default" to "default.png", "wild" to "nested\\wild.png", "missing" to "not-present.png")) {
                    entry("probe/角色 models/$skin.atlas", "\n$page\nsize: 512,512\nformat: RGBA8888\nfilter: Linear,Linear\nrepeat: none\n")
                }
                entry("probe/角色 models/default.png", "default pixels")
                entry("probe/角色 models/nested/wild.png", "wild pixels")
            }
            val bytes = if (patched) StsResourceFileHandlePatcher.patchFileHandleClass(originalBytes()) else originalBytes()
            object : URLClassLoader(arrayOf(fixture.toURI().toURL(), helperJar.toURI().toURL(), gameJar.toURI().toURL()), null) {
                override fun findClass(name: String): Class<*> = if (name == fileHandleName)
                    defineClass(name, bytes, 0, bytes.size) else super.findClass(name)
            }.use(block)
        } finally {
            directory.deleteRecursively()
        }
    }
}
