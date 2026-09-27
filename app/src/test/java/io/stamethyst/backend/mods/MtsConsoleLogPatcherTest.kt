package io.stamethyst.backend.mods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.ClassNode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.PrintStream

class MtsConsoleLogPatcherTest {
    @Test
    fun patchMessageConsoleBytes_isIdempotent() {
        val original = originalConsoleBytes()
        assertFalse(MtsConsoleLogPatcher.isPatchedMessageConsoleClass(original))

        val patched = MtsConsoleLogPatcher.patchMessageConsoleBytes(original)
        assertTrue(MtsConsoleLogPatcher.isPatchedMessageConsoleClass(patched))
        assertTrue(MtsConsoleLogPatcher.patchMessageConsoleBytes(patched).contentEquals(patched))
    }

    @Test
    fun patchedConsole_preservesCachedStreamsAndFullErrorsWithoutGrowingDocument() {
        synchronized(System::class.java) {
            val originalOut = System.out
            val originalErr = System.err
            val outBytes = ByteArrayOutputStream()
            val errBytes = ByteArrayOutputStream()
            val out = PrintStream(outBytes, true, "UTF-8")
            val err = PrintStream(errBytes, true, "UTF-8")
            try {
                System.setOut(out)
                System.setErr(err)
                val consoleClass = loadPatchedConsoleClass()
                val area = textArea()
                area.javaClass.getMethod("setText", String::class.java)
                    .invoke(area, "existing console text")
                val console = consoleClass.getConstructor(textComponentClass()).newInstance(area)
                // Log4j may cache the stream at initialization. Never install a Swing tee that
                // would stay reachable through that cached stream after the window is hidden.
                val cachedOut = System.out
                val cachedErr = System.err
                consoleClass.getMethod("redirectOut", colorClass(), PrintStream::class.java)
                    .invoke(console, null, cachedOut)
                consoleClass.getMethod("redirectErr", colorClass(), PrintStream::class.java)
                    .invoke(console, null, cachedErr)
                assertSame(cachedOut, System.out)
                assertSame(cachedErr, System.err)

                val error = NullPointerException("repeated mod error")
                repeat(1_000) {
                    cachedOut.println("NON_FATAL: repeated mod error")
                    error.printStackTrace()
                }
                cachedOut.println("Game crashed.")
                cachedErr.println("Exception in thread \"LWJGL Application\"")
                assertEquals(
                    "existing console text",
                    area.javaClass.getMethod("getText").invoke(area)
                )
                val outText = outBytes.toString("UTF-8")
                val errText = errBytes.toString("UTF-8")
                assertEquals(1_000, outText.lineSequence().count { it == "NON_FATAL: repeated mod error" })
                assertEquals(
                    1_000,
                    errText.lineSequence().count { it == "java.lang.NullPointerException: repeated mod error" }
                )
                assertTrue(errText.contains("\tat ${javaClass.name}."))
                assertTrue(outText.contains("Game crashed."))
                assertTrue(errText.contains("Exception in thread \"LWJGL Application\""))
            } finally {
                System.setOut(originalOut)
                System.setErr(originalErr)
                out.close()
                err.close()
            }
        }
    }

    @Test
    fun patchedConsole_noArgAndNullDestinationsDoNotSilenceCurrentStreams() {
        synchronized(System::class.java) {
            val originalOut = System.out
            val originalErr = System.err
            try {
                val consoleClass = loadPatchedConsoleClass()
                val console = consoleClass.getConstructor(textComponentClass()).newInstance(textArea())
                for (name in listOf("redirectOut", "redirectErr")) {
                    consoleClass.getMethod(name).invoke(console)
                    consoleClass.getMethod(name, colorClass(), PrintStream::class.java)
                        .invoke(console, null, null)
                }
                assertSame(originalOut, System.out)
                assertSame(originalErr, System.err)
            } finally {
                System.setOut(originalOut)
                System.setErr(originalErr)
            }
        }
    }

    @Test
    fun patchedConsole_explicitDestinationsAreUsedWithoutWrapping() {
        synchronized(System::class.java) {
            val originalOut = System.out
            val originalErr = System.err
            val outBytes = ByteArrayOutputStream()
            val errBytes = ByteArrayOutputStream()
            val out = PrintStream(outBytes, true, "UTF-8")
            val err = PrintStream(errBytes, true, "UTF-8")
            try {
                val consoleClass = loadPatchedConsoleClass()
                val console = consoleClass.getConstructor(textComponentClass()).newInstance(textArea())
                consoleClass.getMethod("redirectOut", colorClass(), PrintStream::class.java)
                    .invoke(console, null, out)
                consoleClass.getMethod("redirectErr", colorClass(), PrintStream::class.java)
                    .invoke(console, null, err)
                assertSame(out, System.out)
                assertSame(err, System.err)
                System.out.println("stdout retained")
                System.err.println("stderr retained")
                assertEquals("stdout retained", outBytes.toString("UTF-8").trim())
                assertEquals("stderr retained", errBytes.toString("UTF-8").trim())
            } finally {
                System.setOut(originalOut)
                System.setErr(originalErr)
                out.close()
                err.close()
            }
        }
    }

    @Test(expected = IOException::class)
    fun patchMessageConsoleBytes_rejectsMissingRedirectMethod() {
        val classNode = ClassNode()
        ClassReader(originalConsoleBytes()).accept(classNode, 0)
        classNode.methods.removeAll { it.name == "redirectErr" && it.desc == "()V" }
        val writer = ClassWriter(0)
        classNode.accept(writer)

        MtsConsoleLogPatcher.patchMessageConsoleBytes(writer.toByteArray())
    }

    private fun loadPatchedConsoleClass(): Class<*> {
        val bytes = MtsConsoleLogPatcher.patchMessageConsoleBytes(originalConsoleBytes())
        return object : ClassLoader(null) {
            fun defineConsole(): Class<*> {
                return defineClass("com.evacipated.cardcrawl.modthespire.ui.MessageConsole", bytes, 0, bytes.size)
            }
        }.defineConsole()
    }

    // The Android test source set compiles against android.jar (which omits AWT/Swing), but
    // local unit tests run on a desktop JVM. Reflection keeps the test runnable on that host.
    private fun colorClass(): Class<*> = Class.forName("java.awt.Color")

    private fun textComponentClass(): Class<*> = Class.forName("javax.swing.text.JTextComponent")

    private fun textArea(): Any = Class.forName("javax.swing.JTextArea").getConstructor().newInstance()

    private fun originalConsoleBytes(): ByteArray {
        val sourceJar = sequenceOf(
            File("src/main/assets/components/mods/ModTheSpire.jar"),
            File("app/src/main/assets/components/mods/ModTheSpire.jar")
        ).firstOrNull { it.isFile } ?: error("Missing test fixture jar: ModTheSpire.jar")
        return requireNotNull(
            JarFileIoUtils.readJarEntryBytes(sourceJar, MtsConsoleLogPatcher.MESSAGE_CONSOLE_CLASS_ENTRY)
        )
    }
}
