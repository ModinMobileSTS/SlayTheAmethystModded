package io.stamethyst.backend.mods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AgentApiIndexTest {
    private val baseModJar = listOf(
        File("src/main/assets/components/mods/BaseMod.jar"),
        File("app/src/main/assets/components/mods/BaseMod.jar"),
    ).first(File::isFile)
    private val stsLibJar = listOf(
        File("src/main/assets/components/mods/StSLib.jar"),
        File("app/src/main/assets/components/mods/StSLib.jar"),
    ).first(File::isFile)
    private val realClasspath get() = listOf(baseModJar, stsLibJar)

    @Test
    fun search_findsBaseModTypesWithTheirOrigin() {
        val index = AgentApiIndex.index(realClasspath)

        val results = AgentApiIndex.search(index.all(), "CustomRelic", null, 25)

        val customRelic = results.firstOrNull { it.binaryName == "basemod.abstracts.CustomRelic" }
        assertNotNull("CustomRelic must be indexed", customRelic)
        assertEquals("class", customRelic!!.kind)
        assertTrue(customRelic.origin.endsWith("BaseMod.jar"))
    }

    @Test
    fun search_byImplementsListsInterfaceImplementors() {
        val index = AgentApiIndex.index(realClasspath)

        val byName = AgentApiIndex.search(index.all(), "EditCardsSubscriber", null, 10)
        assertTrue(byName.any { it.binaryName == "basemod.interfaces.EditCardsSubscriber" })

        // StSLib's main class implements EditCardsSubscriber; BaseMod itself does not.
        val implementors = AgentApiIndex.search(index.all(), null, "EditCardsSubscriber", 100)
        assertTrue(
            "StSLib must be found as an implementor",
            implementors.any { it.binaryName == "com.evacipated.cardcrawl.mod.stslib.StSLib" },
        )
        assertTrue(
            "the filter must not leak non-implementors",
            implementors.all { type -> type.interfaces.any { it.endsWith("EditCardsSubscriber") } },
        )
    }

    @Test
    fun search_ranksExactSimpleNameFirstAndSkipsSyntheticClasses() {
        val root = createTempDirectory("api-index-rank").toFile()
        val jar = syntheticJar(root)

        val index = AgentApiIndex.index(listOf(jar))
        val results = AgentApiIndex.search(index.all(), "Outer", null, 10)

        assertTrue(results.isNotEmpty())
        assertEquals("demo.Outer", results.first().binaryName)
        assertFalse("anonymous classes must not be indexed", results.any { it.simpleName == "1" })
    }

    @Test
    fun index_exposesNestedTypesInSourceForm() {
        val root = createTempDirectory("api-index-nested").toFile()
        val jar = syntheticJar(root)

        val index = AgentApiIndex.index(listOf(jar))
        val nested = index.all().firstOrNull { it.simpleName == "Inner" }

        assertNotNull(nested)
        assertEquals("demo.Outer.Inner", nested!!.binaryName)
        assertTrue(nested.isNested)
    }

    @Test
    fun describe_resolvesDottedAndDollarNestedNames() {
        val root = createTempDirectory("api-index-describe").toFile()
        val jar = syntheticJar(root)

        val dotted = AgentApiIndex.describe(listOf(jar), "demo.Outer.Inner", null)
        val dollar = AgentApiIndex.describe(listOf(jar), "demo.Outer\$Inner", null)

        assertNotNull(dotted)
        assertNotNull(dollar)
        assertEquals("demo.Outer.Inner", dotted!!.summary.binaryName)
        assertEquals(dotted.summary.binaryName, dollar!!.summary.binaryName)
    }

    @Test
    fun describe_returnsExactSignaturesAndSupportsMemberFilter() {
        val root = createTempDirectory("api-index-members").toFile()
        val jar = syntheticJar(root)

        val detail = AgentApiIndex.describe(listOf(jar), "demo.Outer", null)
        assertNotNull(detail)
        assertTrue(detail!!.summary.interfaces.contains("demo.Api"))
        assertTrue(detail.constructors.any { it.declaration == "Outer()" })
        assertTrue(detail.methods.any { it.declaration == "int size()" })
        assertTrue(detail.fields.any { it.declaration == "static java.lang.String NAME" })

        val filtered = AgentApiIndex.describe(listOf(jar), "demo.Outer", "size")
        assertEquals(1, filtered!!.methods.size)
        assertTrue(filtered.constructors.isEmpty())
    }

    @Test
    fun describe_returnsNullForUnknownClass() {
        val root = createTempDirectory("api-index-missing").toFile()
        val jar = syntheticJar(root)

        assertEquals(null, AgentApiIndex.describe(listOf(jar), "demo.DoesNotExist", null))
    }

    private fun syntheticJar(root: File): File {
        val jar = File(root, "fixture.jar")
        ZipOutputStream(jar.outputStream()).use { zip ->
            writeEntry(zip, "demo/Api.class", apiInterface())
            writeEntry(zip, "demo/Outer.class", outerClass())
            writeEntry(zip, "demo/Outer\$Inner.class", innerClass())
            writeEntry(zip, "demo/Outer\$1.class", anonymousClass())
        }
        return jar
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun apiInterface(): ByteArray = ClassWriter(0).also { writer ->
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
            "demo/Api",
            null,
            "java/lang/Object",
            null,
        )
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "run", "()V", null, null).visitEnd()
        writer.visitEnd()
    }.toByteArray()

    private fun outerClass(): ByteArray = ClassWriter(0).also { writer ->
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "demo/Outer", null, "java/lang/Object", arrayOf("demo/Api"))
        writer.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "NAME", "Ljava/lang/String;", null, null).visitEnd()
        val constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        constructor.visitCode()
        constructor.visitVarInsn(Opcodes.ALOAD, 0)
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        constructor.visitInsn(Opcodes.RETURN)
        constructor.visitMaxs(1, 1)
        constructor.visitEnd()
        val size = writer.visitMethod(Opcodes.ACC_PUBLIC, "size", "()I", null, null)
        size.visitCode()
        size.visitInsn(Opcodes.ICONST_0)
        size.visitInsn(Opcodes.IRETURN)
        size.visitMaxs(1, 1)
        size.visitEnd()
        writer.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null).also { run ->
            run.visitCode()
            run.visitInsn(Opcodes.RETURN)
            run.visitMaxs(0, 1)
            run.visitEnd()
        }
        writer.visitEnd()
    }.toByteArray()

    private fun innerClass(): ByteArray = ClassWriter(0).also { writer ->
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC,
            "demo/Outer\$Inner",
            null,
            "java/lang/Object",
            null,
        )
        writer.visitMethod(Opcodes.ACC_PUBLIC, "value", "()I", null, null).also { method ->
            method.visitCode()
            method.visitInsn(Opcodes.ICONST_1)
            method.visitInsn(Opcodes.IRETURN)
            method.visitMaxs(1, 1)
            method.visitEnd()
        }
        writer.visitEnd()
    }.toByteArray()

    private fun anonymousClass(): ByteArray = ClassWriter(0).also { writer ->
        writer.visit(Opcodes.V1_8, 0, "demo/Outer\$1", null, "java/lang/Object", null)
        writer.visitEnd()
    }.toByteArray()

    private fun createTempDirectory(prefix: String): java.nio.file.Path =
        java.nio.file.Files.createTempDirectory(prefix)
}
