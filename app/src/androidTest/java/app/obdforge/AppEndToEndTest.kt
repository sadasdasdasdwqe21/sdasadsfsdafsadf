package app.obdforge

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.obdforge.connection.AdapterTarget
import app.obdforge.connection.LinkState
import app.obdforge.core.dtc.DtcKind
import app.obdforge.core.sim.SimScenario
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real app against the built-in simulator: connection, live data, trouble codes,
 * recording to the database, reconnection after a link drop, and navigation.
 */
@RunWith(AndroidJUnit4::class)
class AppEndToEndTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val g: AppGraph get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ObdForgeApp).graph

    @After fun tearDown() { g.connection.disconnect() }

    private fun connectSim(s: SimScenario) {
        g.connection.connect(AdapterTarget.Simulator(s))
        rule.waitUntil(30_000) { g.connection.state.value is LinkState.Connected }
    }

    @Test fun connectReadCodesAndRecord() {
        connectSim(SimScenario.RICH)
        rule.waitUntil(15_000) { g.live.values.value.containsKey("rpm") }
        rule.onAllNodesWithText("Connected", substring = true).fetchSemanticsNodes().isNotEmpty().let { assertTrue("home shows connection", it) }

        g.connection.simulator!!.fastForward(400.0)
        g.diagnostics.readAll()
        rule.waitUntil(30_000) { g.diagnostics.state.value.readAt != null && g.diagnostics.state.value.busy == null }
        val st = g.diagnostics.state.value
        assertTrue("P0172 stored: ${st.stored}", st.stored.any { it.code == "P0172" && it.kind == DtcKind.STORED })
        assertTrue("freeze frame read", st.freezeFrames.isNotEmpty())

        val id = runBlocking { g.recorder.start("E2E", null, setOf("rpm", "speed", "ltft1")) }
        Thread.sleep(3_000)
        runBlocking { g.recorder.stop() }
        val s = runBlocking { g.db.sessions().get(id) }!!
        assertTrue("samples written: ${s.sampleCount}", s.sampleCount > 5)
        assertTrue(s.simulated)
        assertTrue(s.status == "COMPLETE")
    }

    @Test fun reconnectsAfterLinkDrop() {
        connectSim(SimScenario.NORMAL)
        rule.waitUntil(15_000) { g.live.values.value.containsKey("rpm") }
        g.connection.simulator!!.dropLink()
        rule.waitUntil(15_000) { g.connection.state.value is LinkState.Reconnecting }
        rule.waitUntil(40_000) { g.connection.state.value is LinkState.Connected }
        rule.waitUntil(15_000) { g.live.values.value.containsKey("rpm") }
    }

    @Test fun navigateMainScreens() {
        connectSim(SimScenario.NORMAL)
        rule.onAllNodesWithText("Diagnose").onFirst().performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Read codes").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Live").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Engine performance").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Engine performance").performClick()
        rule.waitUntil(15_000) { rule.onAllNodesWithText("updates/s", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Logs").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Recordings").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("More").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Settings").fetchSemanticsNodes().isNotEmpty() }
    }
}
