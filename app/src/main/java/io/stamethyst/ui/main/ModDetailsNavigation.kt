package io.stamethyst.ui.main

import androidx.annotation.StringRes
import io.stamethyst.R

internal enum class ModDetailsSection(@StringRes val labelRes: Int, val key: String) {
    INFO(R.string.mod_details_tab_info, "info"),
    DESCRIPTION(R.string.mod_details_tab_description, "description"),
    BADGES(R.string.mod_details_tab_badges, "badges"),
    AI(R.string.mod_details_tab_ai, "ai"),
    ACTIONS(R.string.mod_details_actions, "actions"),
}

/** The active tab follows the section at the top, including a short final section at the bottom. */
internal fun resolveModDetailsSection(
    scrollOffset: Int,
    maxScrollOffset: Int,
    sectionOffsets: Map<ModDetailsSection, Int>,
): ModDetailsSection {
    val measuredSections = ModDetailsSection.entries.filter { it in sectionOffsets }
    if (maxScrollOffset > 0 && scrollOffset >= maxScrollOffset) {
        return measuredSections.lastOrNull() ?: ModDetailsSection.INFO
    }
    return measuredSections.lastOrNull { sectionOffsets.getValue(it) <= scrollOffset }
        ?: ModDetailsSection.INFO
}
