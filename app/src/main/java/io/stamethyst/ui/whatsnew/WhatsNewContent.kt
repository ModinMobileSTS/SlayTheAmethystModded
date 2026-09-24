package io.stamethyst.ui.whatsnew

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.stamethyst.R

/** Add one release definition here; copy and localize its string resources for each locale. */
data class WhatsNewRelease(
    val id: String,
    val matchesVersion: (String) -> Boolean,
    @StringRes val titleRes: Int,
    val entries: List<WhatsNewEntry>,
    val moreTitleRes: Int? = null,
    val moreIntroductionRes: Int? = null,
    val moreGroups: List<WhatsNewMoreGroup> = emptyList(),
)

data class WhatsNewMoreGroup(
    @StringRes val titleRes: Int,
    val itemsRes: List<Int>,
)

data class WhatsNewEntry(
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    @StringRes val detailsRes: List<Int> = emptyList(),
    @StringRes val noteRes: Int? = null,
    @DrawableRes val imageRes: Int? = null,
    @StringRes val imageDescriptionRes: Int? = null,
    @StringRes val actionLabelRes: Int? = null,
    val actionRoute: WhatsNewActionRoute? = null,
    val preview: WhatsNewPreview? = null,
)

enum class WhatsNewPreview {
    SLING_BREAK_BOOT_OVERLAY,
}

enum class WhatsNewActionRoute {
    SETTINGS,
    SETTINGS_LLM,
    WORKSHOP,
    MODS,
}

object WhatsNewContent {
    private val version161Pattern = Regex("^1\\.6\\.1(?:-hotfix[0-9]+)?$")
    private val release161 = WhatsNewRelease(
        id = "1.6.1",
        matchesVersion = version161Pattern::matches,
        titleRes = R.string.whats_new_release_161_title,
        entries = listOf(
            WhatsNewEntry(
                titleRes = R.string.whats_new_ai_title,
                descriptionRes = R.string.whats_new_ai_description,
                detailsRes = listOf(
                    R.string.whats_new_ai_detail_inspect,
                    R.string.whats_new_ai_detail_patch,
                    R.string.whats_new_ai_detail_translate,
                    R.string.whats_new_ai_detail_more,
                ),
                noteRes = R.string.whats_new_ai_detail_entry,
                actionLabelRes = R.string.whats_new_ai_action,
                actionRoute = WhatsNewActionRoute.SETTINGS_LLM,
            ),
            WhatsNewEntry(
                titleRes = R.string.whats_new_minigame_title,
                descriptionRes = R.string.whats_new_minigame_description,
                preview = WhatsNewPreview.SLING_BREAK_BOOT_OVERLAY,
                noteRes = R.string.whats_new_minigame_entry,
            ),
        ),
        moreTitleRes = R.string.whats_new_more_title,
        moreIntroductionRes = R.string.whats_new_more_intro,
        moreGroups = listOf(
            WhatsNewMoreGroup(
                titleRes = R.string.whats_new_more_features,
                itemsRes = listOf(
                    R.string.whats_new_more_fps,
                    R.string.whats_new_more_cold_load,
                    R.string.whats_new_more_icons,
                    R.string.whats_new_more_renderer,
                    R.string.whats_new_more_cards,
                    R.string.whats_new_more_rooms,
                    R.string.whats_new_more_workshop,
                ),
            ),
            WhatsNewMoreGroup(
                titleRes = R.string.whats_new_more_fixes,
                itemsRes = listOf(
                    R.string.whats_new_more_superfastmode,
                    R.string.whats_new_more_kicked,
                    R.string.whats_new_more_mod_origin,
                    R.string.whats_new_more_easytier,
                    R.string.whats_new_more_refresh_rate,
                    R.string.whats_new_more_performance,
                ),
            ),
        ),
    )

    private val releases = listOf(release161)

    fun releaseFor(versionName: String): WhatsNewRelease? =
        releases.firstOrNull { it.matchesVersion(versionName) }
}
