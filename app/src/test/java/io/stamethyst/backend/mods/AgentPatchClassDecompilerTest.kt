package io.stamethyst.backend.mods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AgentPatchClassDecompilerTest {
    @Test
    fun dependencyGenericSignatureUpdateChangesActualSourceInsteadOfReusingOldDriverTypes() = withAgentSourceCacheRoot { root ->
        fun dependency(file: File, generic: Boolean): File {
            val writer = ClassWriter(0)
            writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "example/Dependency", null, "java/lang/Object", null)
            writer.visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "get", "(Ljava/lang/Object;)Ljava/lang/Object;",
                if (generic) "<T:Ljava/lang/Object;>(TT;)TT;" else null, null,
            ).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitInsn(Opcodes.ARETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
            writer.visitEnd()
            return agentCacheBytecodeJar(file, "example/Dependency", writer.toByteArray())
        }
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "example/Sample", null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("value")
            visitMethodInsn(Opcodes.INVOKESTATIC, "example/Dependency", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false)
            visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 0)
            visitEnd()
        }
        writer.visitEnd()
        val jar = agentCacheBytecodeJar(File(root, "sample.jar"), "example/Sample", writer.toByteArray())
        val dependencyJar = dependency(File(root, "dependency.jar"), generic = false)
        val classpath = "${jar.path}${File.pathSeparator}${dependencyJar.path}"
        val before = AgentPatchClassDecompiler.analysisCount.get()
        val erased = AgentPatchClassDecompiler.decompileClass(jar, "example.Sample", classpath, root).source
        dependency(dependencyJar, generic = true)
        val generic = AgentPatchClassDecompiler.decompileClass(jar, "example.Sample", classpath, root).source
        assertTrue(erased, erased.contains("(String)"))
        assertFalse(generic, generic.contains("(String)"))
        assertTrue(generic.contains("Dependency.") && generic.contains("get(\"value\")"))
        assertEquals(2L, AgentPatchClassDecompiler.analysisCount.get() - before)
    }

    @Test
    fun decompileClass_reusesCfrResultButInvalidatesSourceAndDependencies() = withAgentSourceCacheRoot { root ->
        val jar = agentCacheClassJar(File(root, "parent.jar"), "FIRST")
        val dependency = agentCacheClassJar(File(root, "dependency.jar"), "DEP_A", "Dependency")
        val classpath = listOf(jar, dependency).joinToString(File.pathSeparator) { it.absolutePath }
        val start = AgentPatchClassDecompiler.analysisCount.get()
        fun decompile() = AgentPatchClassDecompiler.decompileClass(jar, "example.Sample", classpath, root).source

        assertTrue(decompile().contains("FIRST"))
        repeat(8) { assertTrue(decompile().contains("FIRST")) }
        assertEquals(1L, AgentPatchClassDecompiler.analysisCount.get() - start)
        val changed = agentCacheClassJar(File(root, "replacement.jar"), "OTHER")
        replaceKeepingSizeAndMtime(jar, changed.readBytes())
        assertTrue(decompile().contains("OTHER"))
        assertEquals(2L, AgentPatchClassDecompiler.analysisCount.get() - start)
        val changedDependency = agentCacheClassJar(File(root, "replacement-dependency.jar"), "DEP_B", "Dependency")
        replaceKeepingSizeAndMtime(dependency, changedDependency.readBytes())
        assertTrue(decompile().contains("OTHER"))
        assertEquals(3L, AgentPatchClassDecompiler.analysisCount.get() - start)
        dependency.delete()
        decompile()
        assertEquals(4L, AgentPatchClassDecompiler.analysisCount.get() - start)
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith("agent-class-") })
    }

    @Test
    fun decompileClass_concurrentSameClassDoesOneAnalysisAndDifferentJarsStayIsolated() = withAgentSourceCacheRoot { root ->
        val first = agentCacheClassJar(File(root, "first.jar"), "FIRST")
        val second = agentCacheClassJar(File(root, "second.jar"), "OTHER")
        val pool = Executors.newFixedThreadPool(6)
        val start = CountDownLatch(1)
        val before = AgentPatchClassDecompiler.analysisCount.get()
        try {
            val calls = (0..5).map { index ->
                pool.submit<String> {
                    check(start.await(5, TimeUnit.SECONDS))
                    AgentPatchClassDecompiler.decompileClass(
                        if (index == 5) second else first, "example.Sample", "", root,
                    ).source
                }
            }
            start.countDown()
            calls.forEachIndexed { index, future ->
                assertTrue(future.get(15, TimeUnit.SECONDS).contains(if (index == 5) "OTHER" else "FIRST"))
            }
            assertEquals(2L, AgentPatchClassDecompiler.analysisCount.get() - before)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun decompileIdentity_keepsOrderAndOptionVersionAndDisablesDirectoryCaching() = withAgentSourceCacheRoot { root ->
        val first = agentCacheClassJar(File(root, "first.jar"), "FIRST")
        val second = agentCacheClassJar(File(root, "second.jar"), "OTHER")
        val identity = AgentDecompileIdentity.read(first, "${first.path}${File.pathSeparator}${second.path}")!!
        assertTrue(identity != AgentDecompileIdentity.read(first, "${second.path}${File.pathSeparator}${first.path}"))
        assertTrue(identity.fingerprint() != identity.copy(optionsVersion = "future-options").fingerprint())
        assertNull(AgentDecompileIdentity.read(first, root.path))
        assertNull(AgentDecompileIdentity.read(first, "${first.path}${File.pathSeparator}"))
    }

    @Test
    fun binaryEntryPath_mapsPackagesAndRejectsUnsafeNames() {
        assertEquals(
            "com/example/Foo.class",
            AgentPatchClassDecompiler.binaryEntryPath("com.example", "Foo"),
        )
        assertEquals("Foo.class", AgentPatchClassDecompiler.binaryEntryPath(null, "Foo"))
        assertEquals("Foo.class", AgentPatchClassDecompiler.binaryEntryPath("", "Foo"))
        assertEquals(
            "com/example/Outer\$Inner.class",
            AgentPatchClassDecompiler.binaryEntryPath("com.example", "Outer\$Inner"),
        )
        assertNull(AgentPatchClassDecompiler.binaryEntryPath("com.example", "../Evil"))
        assertNull(AgentPatchClassDecompiler.binaryEntryPath("com.example", "a/b"))
        assertNull(AgentPatchClassDecompiler.binaryEntryPath("com/../evil", "Foo"))
        assertNull(AgentPatchClassDecompiler.binaryEntryPath("com.example", ""))
    }

}

internal fun agentCacheClassJar(file: File, value: String, simpleName: String = "Sample"): File {
    val writer = ClassWriter(0)
    writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "example/$simpleName", null, "java/lang/Object", null)
    writer.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "VALUE", "Ljava/lang/String;", null, value).visitEnd()
    writer.visitEnd()
    return agentCacheBytecodeJar(file, "example/$simpleName", writer.toByteArray())
}

internal fun agentCacheBytecodeJar(file: File, internalName: String, bytes: ByteArray): File {
    ZipOutputStream(file.outputStream()).use { zip ->
        zip.putNextEntry(ZipEntry("$internalName.class").apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = size
            crc = CRC32().apply { update(bytes) }.value
            time = 0
        })
        zip.write(bytes)
        zip.closeEntry()
    }
    return file
}
