package io.stamethyst.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object QuickStart : Route
    @Serializable
    data object Main : Route
    @Serializable
    data object Settings : Route
    @Serializable
    data object Compatibility : Route
}
