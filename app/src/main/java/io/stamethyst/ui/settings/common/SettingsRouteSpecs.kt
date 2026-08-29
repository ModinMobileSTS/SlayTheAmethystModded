package io.stamethyst.ui.settings.common

import io.stamethyst.ui.settings.baidu.*
import io.stamethyst.ui.settings.core.*
import io.stamethyst.ui.settings.files.*
import io.stamethyst.ui.settings.first_run.*
import io.stamethyst.ui.settings.mobileglues.*
import io.stamethyst.ui.settings.native_library.*
import io.stamethyst.ui.settings.sections.*
import io.stamethyst.ui.settings.services.*
import io.stamethyst.ui.settings.steamcloud.*

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.stamethyst.R
import io.stamethyst.navigation.Route


internal data class SettingsRouteSpec(
    @param:StringRes val titleResId: Int,
    @param:StringRes val subtitleResId: Int,
    @param:DrawableRes val iconResId: Int,
)


internal data class SettingsHomeDestination(
    val route: Route,
    val spec: SettingsRouteSpec,
)


internal val SettingsHomeRouteSpec = SettingsRouteSpec(
    titleResId = R.string.settings_title,
    subtitleResId = R.string.settings_home_subtitle,
    iconResId = R.drawable.ic_dock_settings,
)


internal val SettingsLauncherRouteSpec = SettingsRouteSpec(
    titleResId = R.string.settings_category_launcher_title,
    subtitleResId = R.string.settings_category_launcher_subtitle,
    iconResId = R.drawable.ic_settings_launcher,
)


internal val SettingsGameRouteSpec = SettingsRouteSpec(
    titleResId = R.string.settings_category_game_title,
    subtitleResId = R.string.settings_category_game_subtitle,
    iconResId = R.drawable.ic_gamepad,
)


internal val SettingsPerformanceRouteSpec = SettingsRouteSpec(
    titleResId = R.string.settings_category_performance_title,
    subtitleResId = R.string.settings_category_performance_subtitle,
    iconResId = R.drawable.ic_speed,
)


internal val SettingsMarketCloudRouteSpec = SettingsRouteSpec(
    titleResId = R.string.settings_category_market_cloud_title,
    subtitleResId = R.string.settings_category_market_cloud_subtitle,
    iconResId = R.drawable.ic_steam,
)


internal val SettingsFeedbackRouteSpec = SettingsRouteSpec(
    titleResId = R.string.settings_feedback_logs_title,
    subtitleResId = R.string.settings_feedback_logs_subtitle,
    iconResId = R.drawable.ic_feedback_updates,
)


internal val SettingsNativeLibraryMarketRouteSpec = SettingsRouteSpec(
    titleResId = R.string.settings_native_library_market_title,
    subtitleResId = R.string.settings_native_library_market_desc,
    iconResId = R.drawable.ic_settings_native_library,
)


internal val SettingsDeveloperRouteSpec = SettingsRouteSpec(
    titleResId = R.string.settings_developer_title,
    subtitleResId = R.string.settings_developer_summary,
    iconResId = R.drawable.ic_build,
)


internal val SettingsAboutRouteSpec = SettingsRouteSpec(
    titleResId = R.string.settings_category_about_title,
    subtitleResId = R.string.settings_category_about_subtitle,
    iconResId = R.drawable.ic_info_outline,
)


internal val SettingsWorkshopAutoImportDefaultsRouteSpec = SettingsRouteSpec(
    titleResId = R.string.settings_workshop_auto_import_defaults_title,
    subtitleResId = R.string.settings_workshop_auto_import_defaults_subtitle,
    iconResId = R.drawable.ic_workshop_download,
)


internal val SettingsHomeDestinations = listOf(
    SettingsHomeDestination(Route.SettingsLauncher, SettingsLauncherRouteSpec),
    SettingsHomeDestination(Route.SettingsGame, SettingsGameRouteSpec),
    SettingsHomeDestination(Route.SettingsPerformance, SettingsPerformanceRouteSpec),
    SettingsHomeDestination(Route.SettingsMarketCloud, SettingsMarketCloudRouteSpec),
    SettingsHomeDestination(Route.SettingsFeedback, SettingsFeedbackRouteSpec),
    SettingsHomeDestination(Route.NativeLibraryMarket, SettingsNativeLibraryMarketRouteSpec),
    SettingsHomeDestination(Route.DeveloperSettings, SettingsDeveloperRouteSpec),
    SettingsHomeDestination(Route.SettingsAbout, SettingsAboutRouteSpec),
)

