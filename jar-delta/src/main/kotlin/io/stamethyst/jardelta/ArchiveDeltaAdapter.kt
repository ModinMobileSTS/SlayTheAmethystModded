package io.stamethyst.jardelta

import io.stamethyst.archivedelta.ArchiveControl
import io.stamethyst.archivedelta.ArchiveLimits
import io.stamethyst.archivedelta.ArchivePackageManifest
import io.stamethyst.archivedelta.ArchivePackages
import java.nio.file.Path

/** Explicit new-format entry point, not auto-detection or migration of the existing v1 engine.
 * Produces a verified candidate only; callers must separately manage history and commit.
 */
class ArchiveDeltaAdapter {
    fun inspect(deltaPackage: Path, limits: ArchiveLimits = ArchiveLimits()): ArchivePackageManifest =
        ArchivePackages.inspect(deltaPackage, limits)

    fun buildCandidate(
        base: Path, deltaPackage: Path, destination: Path, temporaryDirectory: Path,
        limits: ArchiveLimits = ArchiveLimits(), control: ArchiveControl = ArchiveControl(),
    ): ArchivePackageManifest = ArchivePackages.buildCandidate(base, deltaPackage, destination, temporaryDirectory, limits, control)
}
