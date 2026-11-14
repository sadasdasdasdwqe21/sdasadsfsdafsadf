package app.obdforge.core.pid

/** Decoding of PID 01 (since codes cleared) and PID 41 (this drive cycle) monitor status. */
object Readiness {
    enum class State(val label: String) { COMPLETE("Complete"), INCOMPLETE("Not ready"), NOT_SUPPORTED("Not supported") }

    data class Monitor(val name: String, val state: State, val continuous: Boolean)

    data class Status(
        val milOn: Boolean,
        val dtcCount: Int,
        val compressionIgnition: Boolean,
        val monitors: List<Monitor>,
    ) {
        val supported get() = monitors.filter { it.state != State.NOT_SUPPORTED }
        val incomplete get() = monitors.filter { it.state == State.INCOMPLETE }
        val allReady get() = incomplete.isEmpty()
    }

    private val sparkNames = listOf("Catalyst", "Heated catalyst", "Evaporative system", "Secondary air system",
        "A/C refrigerant", "Oxygen sensor", "Oxygen sensor heater", "EGR / VVT system")
    private val compressionNames = listOf("NMHC catalyst", "NOx / SCR aftertreatment", "Reserved", "Boost pressure",
        "Reserved", "Exhaust gas sensor", "PM filter", "EGR / VVT system")

    fun decode(d: IntArray): Status {
        val a = d[0]; val b = d[1]; val c = d[2]; val e = d[3]
        val diesel = b and 0x08 != 0
        val list = ArrayList<Monitor>()
        val cont = listOf("Misfire", "Fuel system", "Comprehensive components")
        for (i in 0 until 3) {
            val avail = b and (1 shl i) != 0
            val incomplete = b and (1 shl (i + 4)) != 0
            list.add(Monitor(cont[i], if (!avail) State.NOT_SUPPORTED else if (incomplete) State.INCOMPLETE else State.COMPLETE, true))
        }
        val names = if (diesel) compressionNames else sparkNames
        for (i in 0 until 8) {
            if (names[i] == "Reserved") continue
            val avail = c and (1 shl i) != 0
            val incomplete = e and (1 shl i) != 0
            list.add(Monitor(names[i], if (!avail) State.NOT_SUPPORTED else if (incomplete) State.INCOMPLETE else State.COMPLETE, false))
        }
        return Status(a and 0x80 != 0, a and 0x7F, diesel, list)
    }

    fun describe(d: IntArray): String {
        val s = decode(d)
        return "MIL ${if (s.milOn) "ON" else "off"}, ${s.dtcCount} DTC(s), ${s.supported.size - s.incomplete.size}/${s.supported.size} monitors ready"
    }
}
