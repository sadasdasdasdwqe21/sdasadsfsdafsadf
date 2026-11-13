package app.livelingo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.livelingo.core.CaptionDisplay
import app.livelingo.settings.SubtitlePreferences
import app.livelingo.ui.components.SettingSwitch
import app.livelingo.ui.components.SubtitlePreview
import app.livelingo.ui.theme.LiveLingoTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a device or emulator: ./gradlew connectedDebugAndroidTest */
@RunWith(AndroidJUnit4::class)
class ComponentsUiTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun settingSwitchTogglesOnTap() {
        var checked by mutableStateOf(false)
        rule.setContent {
            LiveLingoTheme { SettingSwitch("Keep translation history", checked, { checked = it }) }
        }
        rule.onNodeWithText("Keep translation history").assertIsDisplayed().performClick()
        assertTrue(checked)
    }

    @Test
    fun subtitlePreviewRendersWithoutCrashing() {
        rule.setContent {
            LiveLingoTheme {
                SubtitlePreview(
                    SubtitlePreferences(),
                    CaptionDisplay(originalFinal = "ماذا تفعل غداً؟", translationFinal = "What are you doing tomorrow?"),
                )
            }
        }
        rule.waitForIdle()
    }
}
