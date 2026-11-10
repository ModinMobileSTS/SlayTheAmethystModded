package io.stamethyst.backend.workshop

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkshopSubscriptionSyncPolicyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder(
        File(
            generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
                .first { File(it, "gradlew").isFile },
            "agent-tmp",
        ).apply { check(isDirectory || mkdirs()) },
    )

    @Test
    fun enqueuesOnlyMissingUnblockedUndeletedItemsWithNoTaskOrCompletedTask() {
        val statuses: List<WorkshopDownloadTaskStatus?> =
            listOf(null) + WorkshopDownloadTaskStatus.entries
        for (status in statuses) {
            for (blocked in listOf(false, true)) {
                for (markedDeleted in listOf(false, true)) {
                    for (hasLocalContent in listOf(false, true)) {
                        val expected = !blocked && !markedDeleted && !hasLocalContent &&
                            (status == null || status == WorkshopDownloadTaskStatus.Completed)
                        assertEquals(
                            "status=$status blocked=$blocked deleted=$markedDeleted local=$hasLocalContent",
                            expected,
                            WorkshopSubscriptionSyncPolicy.shouldEnqueue(
                                blocked = blocked,
                                markedDeleted = markedDeleted,
                                taskStatus = status,
                                hasLocalContent = hasLocalContent,
                            ),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun completedTaskDoesNotProveLocalFilesExist() {
        val jar = temporaryFolder.newFile("installed.jar")
        val record = record(localJarPath = jar.absolutePath)
        assertFalse(shouldEnqueueCompleted(record))

        assertTrue(jar.delete())

        assertTrue(shouldEnqueueCompleted(record))
    }

    @Test
    fun nullRecordHasNoLocalContent() {
        assertFalse(WorkshopSubscriptionSyncPolicy.hasLocalContent(null))
    }

    @Test
    fun jarAndNonStandardRejectBlankMissingAndDirectoryPaths() {
        val directory = temporaryFolder.newFolder("not-a-jar")
        File(directory, "undeclared.jar").writeText("not a declared file")
        val missing = File(temporaryFolder.root, "missing.jar").absolutePath
        for (kind in jarKinds) {
            for (path in listOf("", "   ", missing, directory.absolutePath)) {
                assertFalse(
                    "$kind must not treat '$path' as a local file",
                    WorkshopSubscriptionSyncPolicy.hasLocalContent(
                        record(contentKind = kind, localJarPath = path),
                    ),
                )
            }
            assertFalse(
                WorkshopSubscriptionSyncPolicy.hasLocalContent(
                    record(
                        contentKind = kind,
                        localJarPaths = listOf("", "  ", missing, directory.absolutePath),
                    ),
                ),
            )
        }
    }

    @Test
    fun jarAndNonStandardAcceptExistingLegacyFileWithoutInspectingItsContentsOrExtension() {
        val file = temporaryFolder.newFile("local-content.bin")
        for (kind in jarKinds) {
            assertTrue(
                "$kind accepts a declared actual file, even when empty",
                WorkshopSubscriptionSyncPolicy.hasLocalContent(
                    record(contentKind = kind, localJarPath = "  ${file.absolutePath}  "),
                ),
            )
        }
    }

    @Test
    fun jarAndNonStandardAcceptAnyExistingFileInDeclaredList() {
        val file = temporaryFolder.newFile("second.jar")
        val directory = temporaryFolder.newFolder("directory")
        for (kind in jarKinds) {
            assertTrue(
                WorkshopSubscriptionSyncPolicy.hasLocalContent(
                    record(
                        contentKind = kind,
                        localJarPath = File(temporaryFolder.root, "missing-legacy.jar").absolutePath,
                        localJarPaths = listOf(
                            " ",
                            File(temporaryFolder.root, "missing-first.jar").absolutePath,
                            directory.absolutePath,
                            "  ${file.absolutePath}  ",
                            file.absolutePath,
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun nonemptyDeclaredListTakesPrecedenceOverLegacyPathAsInAllLocalJarPaths() {
        val legacyFile = temporaryFolder.newFile("legacy.jar")
        for (kind in jarKinds) {
            for (paths in listOf(listOf(" "), listOf(File(temporaryFolder.root, "missing.jar").absolutePath))) {
                assertFalse(
                    "$kind must honor the declared list rather than the legacy fallback",
                    WorkshopSubscriptionSyncPolicy.hasLocalContent(
                        record(contentKind = kind, localJarPath = legacyFile.absolutePath, localJarPaths = paths),
                    ),
                )
            }
        }
    }

    @Test
    fun jarAndNonStandardDoNotUseTexturePackPathAsFallback() {
        val pack = temporaryFolder.newFolder("pack")
        File(pack, "image.png").writeText("texture")
        for (kind in jarKinds) {
            assertFalse(
                WorkshopSubscriptionSyncPolicy.hasLocalContent(
                    record(contentKind = kind, texturePackPath = pack.absolutePath),
                ),
            )
        }
    }

    @Test
    fun texturePackRejectsBlankMissingEmptyDirectoryAndRegularFilePaths() {
        val empty = temporaryFolder.newFolder("empty-pack")
        val file = temporaryFolder.newFile("not-a-pack.png")
        for (path in listOf("", "   ", File(temporaryFolder.root, "missing-pack").absolutePath, empty.absolutePath, file.absolutePath)) {
            assertFalse(
                "TexturePack must not accept '$path'",
                WorkshopSubscriptionSyncPolicy.hasLocalContent(
                    record(contentKind = WorkshopInstalledContentKind.TexturePack, texturePackPath = path),
                ),
            )
        }
    }

    @Test
    fun texturePackRequiresAnActualFileNotJustNestedDirectories() {
        val pack = temporaryFolder.newFolder("nested-pack", "images", "empty").parentFile.parentFile
        val record = record(contentKind = WorkshopInstalledContentKind.TexturePack, texturePackPath = pack.absolutePath)
        assertFalse(WorkshopSubscriptionSyncPolicy.hasLocalContent(record))

        File(pack, "images/empty/texture.bin").writeBytes(byteArrayOf())

        assertTrue(WorkshopSubscriptionSyncPolicy.hasLocalContent(record))
    }

    @Test
    fun texturePackAcceptsDirectFileAndRechecksWhenItDisappears() {
        val pack = temporaryFolder.newFolder("direct-pack")
        val file = File(pack, "texture.png").apply { writeText("texture") }
        val record = record(contentKind = WorkshopInstalledContentKind.TexturePack, texturePackPath = pack.absolutePath)
        assertTrue(WorkshopSubscriptionSyncPolicy.hasLocalContent(record))

        assertTrue(file.delete())

        assertFalse(WorkshopSubscriptionSyncPolicy.hasLocalContent(record))
    }

    @Test
    fun texturePackDoesNotUseDeclaredJarFilesAsFallback() {
        val jar = temporaryFolder.newFile("unrelated.jar")
        for (packPath in listOf("", File(temporaryFolder.root, "missing-pack").absolutePath)) {
            assertFalse(
                WorkshopSubscriptionSyncPolicy.hasLocalContent(
                    record(
                        contentKind = WorkshopInstalledContentKind.TexturePack,
                        localJarPath = jar.absolutePath,
                        localJarPaths = listOf(jar.absolutePath),
                        texturePackPath = packPath,
                    ),
                ),
            )
        }
    }

    private fun shouldEnqueueCompleted(record: WorkshopInstalledModRecord): Boolean =
        WorkshopSubscriptionSyncPolicy.shouldEnqueue(
            blocked = false,
            markedDeleted = false,
            taskStatus = WorkshopDownloadTaskStatus.Completed,
            hasLocalContent = WorkshopSubscriptionSyncPolicy.hasLocalContent(record),
        )

    private fun record(
        contentKind: WorkshopInstalledContentKind = WorkshopInstalledContentKind.JarMod,
        localJarPath: String = "",
        localJarPaths: List<String> = emptyList(),
        texturePackPath: String = "",
    ) = WorkshopInstalledModRecord(
        appId = 646570u,
        publishedFileId = 1uL,
        title = "Subscription policy fixture",
        description = "",
        previewUrl = "",
        versionText = "1",
        updatedAtMillis = 1L,
        installedAtMillis = 1L,
        localJarPath = localJarPath,
        localJarPaths = localJarPaths,
        contentKind = contentKind,
        texturePackPath = texturePackPath,
    )

    private val jarKinds = listOf(WorkshopInstalledContentKind.JarMod, WorkshopInstalledContentKind.NonStandard)
}
