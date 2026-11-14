package app.obdforge

import app.obdforge.core.pid.Pids
import app.obdforge.data.DashboardCodec
import app.obdforge.data.DashboardEntity
import app.obdforge.data.GaugeConfig
import app.obdforge.data.GaugeStyle
import app.obdforge.data.UnitSystem
import app.obdforge.ui.screens.TerminalGuard
import app.obdforge.util.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogicTest {
    @Test fun unitConversions() {
        val ect = Pids.channel("ect")
        assertEquals(212.0, Units.convert(ect, 100.0, UnitSystem.IMPERIAL).value, 1e-9)
        assertEquals("°F", Units.convert(ect, 100.0, UnitSystem.IMPERIAL).unit)
        assertEquals(62.137, Units.convert(Pids.channel("speed"), 100.0, UnitSystem.IMPERIAL).value, 1e-3)
        assertEquals(14.5038, Units.convert(Pids.channel("boost"), 100.0, UnitSystem.IMPERIAL).value, 1e-3)
        assertEquals(23.5215, Units.convert(Pids.channel("economy_l100"), 10.0, UnitSystem.IMPERIAL).value, 1e-3)
        assertEquals(100.0, Units.convert(ect, 100.0, UnitSystem.METRIC).value, 0.0)
    }

    @Test fun terminalBlocksStateChangingCommands() {
        assertNotNull(TerminalGuard.check("04"))
        assertNotNull(TerminalGuard.check("2E1234FF"))
        assertNotNull(TerminalGuard.check("1101"))
        assertNotNull(TerminalGuard.check("ATPP0CSV00"))
        assertNull(TerminalGuard.check("010C"))
        assertNull(TerminalGuard.check("ATRV"))
        assertNull(TerminalGuard.check("22F190"))
        assertNull(TerminalGuard.check("1902FF"))
    }

    @Test fun dashboardRoundTripAndPresets() {
        val gauges = listOf(GaugeConfig("rpm", GaugeStyle.ANALOG, 2, 2, 0xFF22D3EE, 0.0, 7000.0, 6200.0, null), GaugeConfig("boost"))
        val e = DashboardEntity(1, "Test", 0, DashboardCodec.encode(2, gauges))
        val d = DashboardCodec.decode(e)
        assertEquals(gauges, d.gauges)
        val presets = DashboardCodec.presets()
        assertEquals(9, presets.size)
        presets.flatMap { it.second }.forEach { assertNotNull("unknown channel ${it.channel}", Pids.channel(it.channel)) }
        assertTrue(presets.map { it.first }.containsAll(listOf("Engine performance", "Fuel system", "Turbo & boost", "Cooling system", "Intake & airflow", "Emissions", "Transmission", "Electrical", "General")))
    }
}
