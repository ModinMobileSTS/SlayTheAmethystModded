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
    @Serializable
    data object Feedback : Route
    @Serializable
    data object FeedbackSubscriptions : Route
    @Serializable
    data class FeedbackConversation(
        val issueNumber: Long
    ) : Route
}
