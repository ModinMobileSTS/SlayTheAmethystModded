package io.stamethyst.backend.launch

import io.stamethyst.backend.mods.StsDesktopJarPatcher
import java.io.File

/** Successful deep verification bound to the exact jar inputs of one preparation. */
internal class GameBodyPatchVerification private constructor(
    private val desktopLine: String,
    private val patchLine: String
) {
    fun matches(stsJar: File, patchJar: File, current: LaunchJarFingerprints): Boolean =
        current.get(stsJar).readableZip && current.get(patchJar).readableZip &&
            desktopLine == current.line("desktop", stsJar) &&
            patchLine == current.line("gdxpatch", patchJar)

    companion object {
        fun verify(
            stsJar: File,
            patchJar: File,
            isPatched: (File, File) -> Boolean = StsDesktopJarPatcher::isPatchedWithCurrentPatch
        ): GameBodyPatchVerification? {
            // Capture BEFORE verification. The consumer captures again, so a changed
            // input, including a same-size/mtime replacement, cannot inherit this result.
            val before = LaunchJarFingerprints.capture(listOf(stsJar, patchJar))
            if (!isPatched(stsJar, patchJar) ||
                !before.get(stsJar).readableZip || !before.get(patchJar).readableZip
            ) {
                return null
            }
            return GameBodyPatchVerification(
                before.line("desktop", stsJar),
                before.line("gdxpatch", patchJar)
            )
        }
    }
}
