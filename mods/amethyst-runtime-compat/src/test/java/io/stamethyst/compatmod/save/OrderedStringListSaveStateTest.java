package io.stamethyst.compatmod.save;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class OrderedStringListSaveStateTest {
    @Test
    public void snapshotPreservesOrderDuplicatesAndUsesDefensiveCopy() {
        OrderedStringListSaveState state = new OrderedStringListSaveState();
        ArrayList<String> source = new ArrayList<String>(Arrays.asList("a", "b", "a"));

        ArrayList<String> snapshot = state.snapshot(source);
        source.clear();

        assertEquals(Arrays.asList("a", "b", "a"), snapshot);
    }

    @Test
    public void loadPreservesExplicitEmptyListAndConsumesOnce() {
        OrderedStringListSaveState state = new OrderedStringListSaveState();

        state.load(new ArrayList<String>());

        assertEquals(new ArrayList<String>(), state.consume());
        assertNull(state.consume());
    }

    @Test
    public void loadUsesDefensiveCopy() {
        OrderedStringListSaveState state = new OrderedStringListSaveState();
        ArrayList<String> loaded = new ArrayList<String>(Arrays.asList("first", "second"));

        state.load(loaded);
        loaded.clear();

        assertEquals(Arrays.asList("first", "second"), state.consume());
    }

    @Test
    public void missingOldSaveValueDoesNotCreatePendingRestore() {
        OrderedStringListSaveState state = new OrderedStringListSaveState();

        state.load(null);

        assertNull(state.consume());
    }
}
