package io.stamethyst.ui.main

import org.junit.Assert.assertEquals
import org.junit.Test

class ModDetailsNavigationTest {
    private val offsets = mapOf(
        ModDetailsSection.INFO to 12,
        ModDetailsSection.DESCRIPTION to 220,
        ModDetailsSection.BADGES to 420,
        ModDetailsSection.AI to 580,
        ModDetailsSection.ACTIONS to 1000,
    )

    @Test fun eachSectionBecomesSelectedAtItsAnchor() {
        offsets.forEach { (section, position) ->
            assertEquals(section, resolveModDetailsSection(position, 1600, offsets))
        }
    }

    @Test fun scrollingBetweenAnchorsKeepsCurrentSection() {
        assertEquals(ModDetailsSection.INFO, resolveModDetailsSection(0, 1600, offsets))
        assertEquals(ModDetailsSection.INFO, resolveModDetailsSection(219, 1600, offsets))
        assertEquals(ModDetailsSection.DESCRIPTION, resolveModDetailsSection(419, 1600, offsets))
        assertEquals(ModDetailsSection.AI, resolveModDetailsSection(999, 1600, offsets))
    }

    @Test fun shortLastSectionIsSelectedWhenScrolledToBottom() {
        assertEquals(ModDetailsSection.ACTIONS, resolveModDetailsSection(900, 900, offsets))
    }

    @Test fun nonScrollableContentAndMissingMeasurementsStartAtInfo() {
        assertEquals(ModDetailsSection.INFO, resolveModDetailsSection(0, 0, offsets))
        assertEquals(ModDetailsSection.INFO, resolveModDetailsSection(0, Int.MAX_VALUE, emptyMap()))
    }

    @Test fun changedSectionHeightsUseLatestAnchors() {
        val expandedOffsets = offsets.mapValues { (section, offset) ->
            if (section.ordinal > ModDetailsSection.DESCRIPTION.ordinal) offset + 300 else offset
        }
        assertEquals(ModDetailsSection.AI, resolveModDetailsSection(600, 2000, offsets))
        assertEquals(ModDetailsSection.DESCRIPTION, resolveModDetailsSection(600, 2000, expandedOffsets))
        assertEquals(ModDetailsSection.BADGES, resolveModDetailsSection(800, 2000, expandedOffsets))
    }
}
