package io.stamethyst.backend.steamcloud

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.stamethyst.R

internal object SteamAchievementCatalog {
    data class Entry(
        val apiName: String,
        @get:StringRes val titleResId: Int,
        @get:StringRes val descriptionResId: Int,
        @get:DrawableRes val unlockedIconResId: Int,
        @get:DrawableRes val lockedIconResId: Int,
    )

    val entries: List<Entry> = listOf(
        entry("shrug_it_off", R.string.steam_achievement_shrug_it_off_title, R.string.steam_achievement_shrug_it_off_description, R.drawable.achievement_shrug_it_off_unlocked, R.drawable.achievement_shrug_it_off_locked),
        entry("purity", R.string.steam_achievement_purity_title, R.string.steam_achievement_purity_description, R.drawable.achievement_purity_unlocked, R.drawable.achievement_purity_locked),
        entry("come_at_me", R.string.steam_achievement_come_at_me_title, R.string.steam_achievement_come_at_me_description, R.drawable.achievement_come_at_me_unlocked, R.drawable.achievement_come_at_me_locked),
        entry("the_pact", R.string.steam_achievement_the_pact_title, R.string.steam_achievement_the_pact_description, R.drawable.achievement_the_pact_unlocked, R.drawable.achievement_the_pact_locked),
        entry("adrenaline", R.string.steam_achievement_adrenaline_title, R.string.steam_achievement_adrenaline_description, R.drawable.achievement_adrenaline_unlocked, R.drawable.achievement_adrenaline_locked),
        entry("powerful", R.string.steam_achievement_powerful_title, R.string.steam_achievement_powerful_description, R.drawable.achievement_powerful_unlocked, R.drawable.achievement_powerful_locked),
        entry("jaxxed", R.string.steam_achievement_jaxxed_title, R.string.steam_achievement_jaxxed_description, R.drawable.achievement_jaxxed_unlocked, R.drawable.achievement_jaxxed_locked),
        entry("impervious", R.string.steam_achievement_impervious_title, R.string.steam_achievement_impervious_description, R.drawable.achievement_impervious_unlocked, R.drawable.achievement_impervious_locked),
        entry("barricaded", R.string.steam_achievement_barricaded_title, R.string.steam_achievement_barricaded_description, R.drawable.achievement_barricaded_unlocked, R.drawable.achievement_barricaded_locked),
        entry("catalyst", R.string.steam_achievement_catalyst_title, R.string.steam_achievement_catalyst_description, R.drawable.achievement_catalyst_unlocked, R.drawable.achievement_catalyst_locked),
        entry("plague", R.string.steam_achievement_plague_title, R.string.steam_achievement_plague_description, R.drawable.achievement_plague_unlocked, R.drawable.achievement_plague_locked),
        entry("ninja", R.string.steam_achievement_ninja_title, R.string.steam_achievement_ninja_description, R.drawable.achievement_ninja_unlocked, R.drawable.achievement_ninja_locked),
        entry("infinity", R.string.steam_achievement_infinity_title, R.string.steam_achievement_infinity_description, R.drawable.achievement_infinity_unlocked, R.drawable.achievement_infinity_locked),
        entry("you_are_nothing", R.string.steam_achievement_you_are_nothing_title, R.string.steam_achievement_you_are_nothing_description, R.drawable.achievement_you_are_nothing_unlocked, R.drawable.achievement_you_are_nothing_locked),
        entry("perfect", R.string.steam_achievement_perfect_title, R.string.steam_achievement_perfect_description, R.drawable.achievement_perfect_unlocked, R.drawable.achievement_perfect_locked),
        entry("guardian", R.string.steam_achievement_guardian_title, R.string.steam_achievement_guardian_description, R.drawable.achievement_guardian_unlocked, R.drawable.achievement_guardian_locked),
        entry("ghost_guardian", R.string.steam_achievement_ghost_guardian_title, R.string.steam_achievement_ghost_guardian_description, R.drawable.achievement_ghost_guardian_unlocked, R.drawable.achievement_ghost_guardian_locked),
        entry("slime_boss", R.string.steam_achievement_slime_boss_title, R.string.steam_achievement_slime_boss_description, R.drawable.achievement_slime_boss_unlocked, R.drawable.achievement_slime_boss_locked),
        entry("automaton", R.string.steam_achievement_automaton_title, R.string.steam_achievement_automaton_description, R.drawable.achievement_automaton_unlocked, R.drawable.achievement_automaton_locked),
        entry("collector", R.string.steam_achievement_collector_title, R.string.steam_achievement_collector_description, R.drawable.achievement_collector_unlocked, R.drawable.achievement_collector_locked),
        entry("champ", R.string.steam_achievement_champ_title, R.string.steam_achievement_champ_description, R.drawable.achievement_champ_unlocked, R.drawable.achievement_champ_locked),
        entry("crow", R.string.steam_achievement_crow_title, R.string.steam_achievement_crow_description, R.drawable.achievement_crow_unlocked, R.drawable.achievement_crow_locked),
        entry("shapes", R.string.steam_achievement_shapes_title, R.string.steam_achievement_shapes_description, R.drawable.achievement_shapes_unlocked, R.drawable.achievement_shapes_locked),
        entry("time_eater", R.string.steam_achievement_time_eater_title, R.string.steam_achievement_time_eater_description, R.drawable.achievement_time_eater_unlocked, R.drawable.achievement_time_eater_locked),
        entry("ruby", R.string.steam_achievement_ruby_title, R.string.steam_achievement_ruby_description, R.drawable.achievement_ruby_unlocked, R.drawable.achievement_ruby_locked),
        entry("emerald", R.string.steam_achievement_emerald_title, R.string.steam_achievement_emerald_description, R.drawable.achievement_emerald_unlocked, R.drawable.achievement_emerald_locked),
        entry("one_relic", R.string.steam_achievement_one_relic_title, R.string.steam_achievement_one_relic_description, R.drawable.achievement_one_relic_unlocked, R.drawable.achievement_one_relic_locked),
        entry("speed_climber", R.string.steam_achievement_speed_climber_title, R.string.steam_achievement_speed_climber_description, R.drawable.achievement_speed_climber_unlocked, R.drawable.achievement_speed_climber_locked),
        entry("minimalist", R.string.steam_achievement_minimalist_title, R.string.steam_achievement_minimalist_description, R.drawable.achievement_minimalist_unlocked, R.drawable.achievement_minimalist_locked),
        entry("donut", R.string.steam_achievement_donut_title, R.string.steam_achievement_donut_description, R.drawable.achievement_donut_unlocked, R.drawable.achievement_donut_locked),
        entry("ascend_0", R.string.steam_achievement_ascend_0_title, R.string.steam_achievement_ascend_0_description, R.drawable.achievement_ascend_0_unlocked, R.drawable.achievement_ascend_0_locked),
        entry("ascend_10", R.string.steam_achievement_ascend_10_title, R.string.steam_achievement_ascend_10_description, R.drawable.achievement_ascend_10_unlocked, R.drawable.achievement_ascend_10_locked),
        entry("sapphire", R.string.steam_achievement_sapphire_title, R.string.steam_achievement_sapphire_description, R.drawable.achievement_sapphire_unlocked, R.drawable.achievement_sapphire_locked),
        entry("common_sense", R.string.steam_achievement_common_sense_title, R.string.steam_achievement_common_sense_description, R.drawable.achievement_common_sense_unlocked, R.drawable.achievement_common_sense_locked),
        entry("focused", R.string.steam_achievement_focused_title, R.string.steam_achievement_focused_description, R.drawable.achievement_focused_unlocked, R.drawable.achievement_focused_locked),
        entry("neon", R.string.steam_achievement_neon_title, R.string.steam_achievement_neon_description, R.drawable.achievement_neon_unlocked, R.drawable.achievement_neon_locked),
        entry("lucky_day", R.string.steam_achievement_lucky_day_title, R.string.steam_achievement_lucky_day_description, R.drawable.achievement_lucky_day_unlocked, R.drawable.achievement_lucky_day_locked),
        entry("transient", R.string.steam_achievement_transient_title, R.string.steam_achievement_transient_description, R.drawable.achievement_transient_unlocked, R.drawable.achievement_transient_locked),
        entry("ascend_20", R.string.steam_achievement_ascend_20_title, R.string.steam_achievement_ascend_20_description, R.drawable.achievement_ascend_20_unlocked, R.drawable.achievement_ascend_20_locked),
        entry("ruby_plus", R.string.steam_achievement_ruby_plus_title, R.string.steam_achievement_ruby_plus_description, R.drawable.achievement_ruby_plus_unlocked, R.drawable.achievement_ruby_plus_locked),
        entry("emerald_plus", R.string.steam_achievement_emerald_plus_title, R.string.steam_achievement_emerald_plus_description, R.drawable.achievement_emerald_plus_unlocked, R.drawable.achievement_emerald_plus_locked),
        entry("sapphire_plus", R.string.steam_achievement_sapphire_plus_title, R.string.steam_achievement_sapphire_plus_description, R.drawable.achievement_sapphire_plus_unlocked, R.drawable.achievement_sapphire_plus_locked),
        entry("the_ending", R.string.steam_achievement_the_ending_title, R.string.steam_achievement_the_ending_description, R.drawable.achievement_the_ending_unlocked, R.drawable.achievement_the_ending_locked),
        entry("eternal_one", R.string.steam_achievement_eternal_one_title, R.string.steam_achievement_eternal_one_description, R.drawable.achievement_eternal_one_unlocked, R.drawable.achievement_eternal_one_locked),
        entry("amethyst", R.string.steam_achievement_amethyst_title, R.string.steam_achievement_amethyst_description, R.drawable.achievement_amethyst_unlocked, R.drawable.achievement_amethyst_locked),
        entry("amethyst_plus", R.string.steam_achievement_amethyst_plus_title, R.string.steam_achievement_amethyst_plus_description, R.drawable.achievement_amethyst_plus_unlocked, R.drawable.achievement_amethyst_plus_locked),
    )

    val apiNames: Set<String> = entries.mapTo(linkedSetOf(), Entry::apiName)

    private fun entry(
        apiName: String,
        @StringRes titleResId: Int,
        @StringRes descriptionResId: Int,
        @DrawableRes unlockedIconResId: Int,
        @DrawableRes lockedIconResId: Int,
    // The bundled bitmap filenames are globally reversed: *_locked is the colorful
    // Steam unlocked artwork and *_unlocked is the grayscale locked artwork.
    ) = Entry(apiName, titleResId, descriptionResId, lockedIconResId, unlockedIconResId)
}
