package io.stamethyst.ui.main

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.stamethyst.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EasyTierMemberConnectionCapsuleInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun capsuleUpdatesWhenRouteChangesAndDropsProtocolOnRelay() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val link = mutableStateOf(EasyTierMemberLink(EasyTierMemberLinkState.Direct, "udp"))
        compose.setContent {
            MaterialTheme { EasyTierMemberConnectionCapsule(link.value) }
        }
        val direct = context.getString(R.string.main_easytier_link_direct) + " · UDP"
        compose.onNodeWithText(direct).assertIsDisplayed()
        compose.runOnIdle { link.value = EasyTierMemberLink(EasyTierMemberLinkState.Relay) }
        compose.onNodeWithText(context.getString(R.string.main_easytier_link_relay)).assertIsDisplayed()
        compose.onNodeWithText(direct).assertDoesNotExist()
        compose.runOnIdle { link.value = EasyTierMemberLink(EasyTierMemberLinkState.Unknown) }
        compose.onNodeWithText(context.getString(R.string.main_easytier_link_unknown)).assertIsDisplayed()
    }
}
