package io.stamethyst.backend.steamcloud;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

public final class SteamCloudClientTest {
    @Test
    public void ensureDirectoryExists_toleratesConcurrentCreationRace() throws Exception {
        SteamCloudClient.ensureDirectoryExists(
            new SequencedDirectoryFile("C:/steam-cloud/preferences", false, false, true),
            "output directory"
        );
    }

    @Test
    public void ensureDirectoryExists_throwsWhenDirectoryCannotBeCreated() {
        SequencedDirectoryFile directory = new SequencedDirectoryFile(
            "C:/steam-cloud/preferences",
            false,
            false,
            false
        );
        IOException error = Assert.assertThrows(
            IOException.class,
            () -> SteamCloudClient.ensureDirectoryExists(directory, "output directory")
        );

        Assert.assertEquals(
            "Failed to create output directory: " + directory.getAbsolutePath(),
            error.getMessage()
        );
    }

    private static final class SequencedDirectoryFile extends File {
        private final boolean mkdirsResult;
        private final boolean[] isDirectoryResults;
        private int isDirectoryCallCount = 0;

        private SequencedDirectoryFile(String pathname, boolean mkdirsResult, boolean... isDirectoryResults) {
            super(pathname);
            this.mkdirsResult = mkdirsResult;
            this.isDirectoryResults = isDirectoryResults;
        }

        @Override
        public boolean isDirectory() {
            if (isDirectoryResults.length == 0) {
                return false;
            }
            int index = Math.min(isDirectoryCallCount, isDirectoryResults.length - 1);
            isDirectoryCallCount += 1;
            return isDirectoryResults[index];
        }

        @Override
        public boolean mkdirs() {
            return mkdirsResult;
        }
    }
}
