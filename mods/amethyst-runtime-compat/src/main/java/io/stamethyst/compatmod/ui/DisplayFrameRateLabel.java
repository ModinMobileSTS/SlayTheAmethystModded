package io.stamethyst.compatmod.ui;

final class DisplayFrameRateLabel {
    private DisplayFrameRateLabel() {
    }

    static String format(int fpsLimit) {
        return fpsLimit == 0 ? "∞" : Integer.toString(fpsLimit);
    }
}
