package io.stamethyst.backend.steamcloud

internal sealed interface SteamCloudUserWarning {
    fun rawMessage(): String

    data class UnsupportedLocalPath(
        val localRelativePath: String,
    ) : SteamCloudUserWarning {
        override fun rawMessage(): String {
            return UNSUPPORTED_LOCAL_PATH_PREFIX + localRelativePath
        }
    }

    data class FailedToMapLocalFile(
        val localRelativePath: String,
    ) : SteamCloudUserWarning {
        override fun rawMessage(): String {
            return FAILED_TO_MAP_LOCAL_FILE_PREFIX + localRelativePath
        }
    }

    object BaselineRequired : SteamCloudUserWarning {
        override fun rawMessage(): String {
            return BASELINE_REQUIRED_MESSAGE
        }
    }

    data class IgnoredLocalDeletions(
        val count: Int,
    ) : SteamCloudUserWarning {
        override fun rawMessage(): String {
            return IGNORED_LOCAL_DELETIONS_PREFIX + count + IGNORED_LOCAL_DELETIONS_SUFFIX
        }
    }

    data class UnsupportedRemotePath(
        val remotePath: String,
    ) : SteamCloudUserWarning {
        override fun rawMessage(): String {
            return UNSUPPORTED_REMOTE_PATH_PREFIX + remotePath
        }
    }

    companion object {
        fun parse(rawMessage: String): SteamCloudUserWarning? {
            return when {
                rawMessage.startsWith(UNSUPPORTED_LOCAL_PATH_PREFIX) -> {
                    UnsupportedLocalPath(rawMessage.removePrefix(UNSUPPORTED_LOCAL_PATH_PREFIX))
                }

                rawMessage.startsWith(FAILED_TO_MAP_LOCAL_FILE_PREFIX) -> {
                    FailedToMapLocalFile(rawMessage.removePrefix(FAILED_TO_MAP_LOCAL_FILE_PREFIX))
                }

                rawMessage == BASELINE_REQUIRED_MESSAGE -> {
                    BaselineRequired
                }

                rawMessage.startsWith(IGNORED_LOCAL_DELETIONS_PREFIX) &&
                    rawMessage.endsWith(IGNORED_LOCAL_DELETIONS_SUFFIX) -> {
                    rawMessage
                        .removePrefix(IGNORED_LOCAL_DELETIONS_PREFIX)
                        .removeSuffix(IGNORED_LOCAL_DELETIONS_SUFFIX)
                        .toIntOrNull()
                        ?.let(::IgnoredLocalDeletions)
                }

                rawMessage.startsWith(UNSUPPORTED_REMOTE_PATH_PREFIX) -> {
                    UnsupportedRemotePath(rawMessage.removePrefix(UNSUPPORTED_REMOTE_PATH_PREFIX))
                }

                else -> null
            }
        }

        private const val UNSUPPORTED_LOCAL_PATH_PREFIX =
            "Ignored unsupported local path while planning upload: "
        private const val FAILED_TO_MAP_LOCAL_FILE_PREFIX =
            "Failed to map local file for Steam Cloud upload: "
        private const val BASELINE_REQUIRED_MESSAGE =
            "No previous Steam Cloud sync baseline is saved yet. Existing files present on both sides are treated as conflicts until you complete a full pull once."
        private const val IGNORED_LOCAL_DELETIONS_PREFIX = "Ignored "
        private const val IGNORED_LOCAL_DELETIONS_SUFFIX =
            " local deletion(s) because Phase 2 uploads do not delete Steam Cloud files."
        private const val UNSUPPORTED_REMOTE_PATH_PREFIX =
            "Ignored unsupported Steam Cloud path: "
    }
}
