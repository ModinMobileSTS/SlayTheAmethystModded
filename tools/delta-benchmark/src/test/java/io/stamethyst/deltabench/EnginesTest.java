package io.stamethyst.deltabench;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.util.Arrays;
import static org.junit.Assert.*;

public class EnginesTest {
    @Test public void fileCorpusMatchesArrayOracleAcrossChunkBoundary() throws Exception {
        int size=131076;
        for(String fixture:new String[]{"insert-middle","delete-middle","move-quarter","sparse","unrelated","identical"}){
            File base=temporary.newFile(),target=temporary.newFile();
            FileCorpus.prepare(base,target,size,fixture);
            byte[] original=Corpus.base(size);
            assertArrayEquals(original,Files.readAllBytes(base.toPath()));
            assertArrayEquals(Corpus.target(original,fixture),Files.readAllBytes(target.toPath()));
        }
    }
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void allFixturesRoundTripWithoutChangingBase() throws Exception {
        byte[] original = Corpus.base(16384);
        String[] fixtures = {"identical", "insert-head", "insert-middle", "sparse", "unrelated", "delete-middle"};
        for (String engine : Engines.NAMES) for (String fixture : fixtures) {
            File folder = temporary.newFolder();
            File base = new File(folder, "base"), target = new File(folder, "target");
            File patch = new File(folder, "patch"), output = new File(folder, "output");
            byte[] expected = Corpus.target(original, fixture);
            Files.write(base.toPath(), original); Files.write(target.toPath(), expected);
            Engines.generate(engine, base, target, patch);
            Engines.apply(engine, base, patch, output, expected.length);
            assertArrayEquals(expected, Files.readAllBytes(output.toPath()));
            assertArrayEquals(original, Files.readAllBytes(base.toPath()));
        }
    }

    @Test public void movedRangeExposesExistingJvmDecoderIncompatibility() throws Exception {
        // Record a real selection failure, not a passing roundtrip or silent skip.
        File base = temporary.newFile(), target = temporary.newFile();
        File patch = temporary.newFile(), output = temporary.newFile();
        byte[] original = Corpus.base(16384);
        Files.write(base.toPath(), original);
        Files.write(target.toPath(), Corpus.target(original, "move-quarter"));
        Engines.generate(Engines.NAMES[1], base, target, patch);
        IOException error = assertThrows(IOException.class,
            () -> Engines.apply(Engines.NAMES[1], base, patch, output, original.length));
        assertTrue(error.getMessage().contains("non-progressing directive"));
        assertArrayEquals(original, Files.readAllBytes(base.toPath()));
    }

    @Test public void truncatedPatchAndOutputOverflowAreRejected() throws Exception {
        for (String engine : Engines.NAMES) {
            File folder = temporary.newFolder();
            File base = new File(folder, "base"), target = new File(folder, "target");
            File patch = new File(folder, "patch"), output = new File(folder, "output");
            byte[] original = Corpus.base(4096);
            Files.write(base.toPath(), original); Files.write(target.toPath(), Corpus.target(original, "insert-head"));
            Engines.generate(engine, base, target, patch);
            assertThrows(IOException.class, () -> Engines.apply(engine, base, patch, output, 1));
            byte[] bytes = Files.readAllBytes(patch.toPath());
            Files.write(patch.toPath(), Arrays.copyOf(bytes, bytes.length / 2));
            assertThrows(IOException.class, () -> Engines.apply(engine, base, patch, output, 4133));
        }
    }

    @Test public void insertionPreservesShiftedSuffixAndInputsAreBounded() {
        byte[] base = Corpus.base(4096);
        byte[] target = Corpus.target(base, "insert-head");
        assertArrayEquals(base, Arrays.copyOfRange(target, 37, target.length));
        assertArrayEquals(base, Corpus.base(4096));
        assertThrows(IllegalArgumentException.class, () -> Corpus.base(Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> Engines.validate("hdiff-not-yet-integrated"));
    }
}
