package io.stamethyst.compatmod.save;

import java.util.ArrayList;
import java.util.List;

final class OrderedStringListSaveState {
    private ArrayList<String> pending;

    ArrayList<String> snapshot(List<String> values) {
        return values == null ? null : new ArrayList<String>(values);
    }

    void load(List<String> values) {
        pending = values == null ? null : new ArrayList<String>(values);
    }

    ArrayList<String> consume() {
        ArrayList<String> value = pending;
        pending = null;
        return value == null ? null : new ArrayList<String>(value);
    }
}
