package io.stamethyst.backend.launch

import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeLibraryPathResolverTest {
    @Test
    fun resolveLibraryFile_prefersTheApkLibraryBeforeOptionalExternalDirectories() {
        val appNativeDir = Files.createTempDirectory("apk-native-").toFile()
        val appLibrary = File(appNativeDir, "liblwjgl.so").apply { writeText("apk") }

        try {
            assertEquals(
                appLibrary,
                NativeLibraryPathResolver.resolveLibraryFile(
                    context = ContextWrapper(null),
                    libraryName = appLibrary.name,
                    appNativeLibraryDir = appNativeDir.absolutePath
                )
            )
        } finally {
            appNativeDir.deleteRecursively()
        }
    }
}
