package io.stamethyst.backend.diag

import android.content.Context
import android.content.ContextWrapper
import io.stamethyst.config.RuntimePaths
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceDiagnosticsArchiveTest {
    @Test
    fun writePerformanceDiagnosticsBundle_includesPerformanceArtifacts() {
        val root = Files.createTempDirectory("performance-diagnostics").toFile()
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getPackageName(): String = "io.stamethyst.test"
            override fun getExternalFilesDir(type: String?): File = root
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        }
        val stsRoot = RuntimePaths.stsRoot(context).apply { mkdirs() }
        RuntimePaths.frameProbeIncidents(context).writeText("{\"totalMs\":12.5}")
        RuntimePaths.frameProbePreviousIncidents(context).writeText("{\"totalMs\":10.0}")
        RuntimePaths.latestLog(context).writeText("[gdx-diag] GpuResources summary")
        RuntimePaths.jvmGcLog(context).writeText("gc")
        RuntimePaths.jvmHeapSnapshot(context).writeText("heap")
        RuntimePaths.launcherPerfSnapshot(context).writeText("fps=90")
        RuntimePaths.performanceLaunchAuditLog(context).apply {
            parentFile?.mkdirs()
            writeText("performanceDeepDiagnostics=true")
        }
        RuntimePaths.memoryDiagnosticsLog(context).writeText("memory")
        File(RuntimePaths.jvmHistogramsDir(context).apply { mkdirs() }, "gc_histo_1_combat.txt")
            .writeText("histogram")
        RuntimePaths.arthasBridgeLog(context).writeText("[arthas-bridge] ready")
        File(RuntimePaths.offlineArthasOutputDir(context).apply { mkdirs() }, "arthas-offline-status.txt")
            .writeText("state=completed")
        File(RuntimePaths.offlineArthasOutputDir(context), "arthas-stack-flush.txt")
            .writeText("SpriteBatch.flush")
        File(RuntimePaths.offlineArthasOutputDir(context), "arthas-trace-render.txt")
            .writeText("AbstractCard.render")

        val output = ByteArrayOutputStream()
        val count = DiagnosticsArchiveBuilder.writePerformanceDiagnosticsBundle(context, output)
        val entries = LinkedHashSet<String>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries += entry.name
            }
        }

        assertEquals(16, count)
        assertTrue(entries.contains("sts/performance/frame-probe-incidents.jsonl"))
        assertTrue(entries.contains("sts/performance/frame-probe-incidents.prev.jsonl"))
        assertTrue(entries.contains("sts/performance/latest.log"))
        assertTrue(entries.contains("sts/performance/jvm_gc.log"))
        assertTrue(entries.contains("sts/performance/jvm_heap_snapshot.txt"))
        assertTrue(entries.contains("sts/performance/launcher_perf_snapshot.txt"))
        assertTrue(entries.contains("sts/performance/performance_launch_audit.log"))
        assertTrue(entries.contains("sts/performance/memory_diagnostics/memory_diagnostics.log"))
        assertTrue(entries.contains("sts/performance/jvm_histograms/gc_histo_1_combat.txt"))
        assertTrue(entries.contains("sts/performance/arthas-bridge.log"))
        assertTrue(entries.contains("sts/performance/arthas/arthas-offline-status.txt"))
        assertTrue(entries.contains("sts/performance/arthas/arthas-stack-flush.txt"))
        assertTrue(entries.contains("sts/performance/arthas/arthas-trace-render.txt"))
        stsRoot.deleteRecursively()
    }
}
