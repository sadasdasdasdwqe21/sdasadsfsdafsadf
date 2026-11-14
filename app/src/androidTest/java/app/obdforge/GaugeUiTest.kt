package app.obdforge

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.obdforge.core.pid.Pids
import app.obdforge.data.GaugeConfig
import app.obdforge.data.GaugeStyle
import app.obdforge.data.ThemeMode
import app.obdforge.data.UnitSystem
import app.obdforge.live.LiveValue
import app.obdforge.ui.components.ConfirmDestructive
import app.obdforge.ui.components.GaugeView
import app.obdforge.ui.components.gaugeData
import app.obdforge.ui.theme.ObdTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GaugeUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun digitalGaugeShowsValueAndStats() {
        val ch = Pids.channel("rpm")
        val d = gaugeData(GaugeConfig("rpm", GaugeStyle.DIGITAL), ch, LiveValue(1726.0, 0, 800.0, 3000.0, 5000.0, 3), UnitSystem.METRIC, false)
        rule.setContent { ObdTheme(ThemeMode.DARK) { GaugeView(GaugeStyle.DIGITAL, d, null, Modifier.width(200.dp).height(160.dp)) } }
        rule.onNodeWithText("1726").assertIsDisplayed()
        rule.onNodeWithText("max 3000", substring = true).assertIsDisplayed()
    }

    @Test fun unsupportedGaugeSaysSo() {
        val d = gaugeData(GaugeConfig("frp", GaugeStyle.RING), Pids.channel("frp"), null, UnitSystem.METRIC, true)
        rule.setContent { ObdTheme(ThemeMode.DARK) { GaugeView(GaugeStyle.RING, d, null, Modifier.width(200.dp).height(160.dp)) } }
        rule.onNodeWithText("Not supported by this vehicle").assertIsDisplayed()
    }

    @Test fun clearCodesNeedsAcknowledgement() {
        var cleared = false
        rule.setContent { ObdTheme(ThemeMode.DARK) { ConfirmDestructive("Clear?", listOf("Monitors reset"), "I understand", "Clear codes", { cleared = true }, {}) } }
        rule.onNodeWithText("Clear codes").performClick()
        assert(!cleared)
        rule.onNodeWithText("I understand").performClick()
        rule.onNodeWithText("Clear codes").performClick()
        assert(cleared)
    }

}
