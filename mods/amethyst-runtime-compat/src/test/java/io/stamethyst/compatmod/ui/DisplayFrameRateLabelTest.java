package io.stamethyst.compatmod.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DisplayFrameRateLabelTest {
    @Test
    public void unlimitedLimitUsesInfinityInsteadOfZero() {
        assertEquals("∞", DisplayFrameRateLabel.format(0));
    }

    @Test
    public void nonDesktopFpsLimitKeepsExactValue() {
        assertEquals("90", DisplayFrameRateLabel.format(90));
        assertEquals("235", DisplayFrameRateLabel.format(235));
    }
}
