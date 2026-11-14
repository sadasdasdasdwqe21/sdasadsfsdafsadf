package app.obdforge.core.assistant

import app.obdforge.core.dtc.DtcKnowledge
import app.obdforge.core.pid.Channel
import app.obdforge.core.pid.Pids

/**
 * Offline answers to plain-language questions about the vehicle, built from the live data,
 * codes and assistant report. (The app can optionally forward questions to a cloud model with
 * the same context; this engine is what works with no network.)
 */
class QueryEngine(private val assistant: DiagnosticAssistant = DiagnosticAssistant()) {

    private val synonyms: Map<String, String> = mapOf(
        "rpm" to "rpm", "revs" to "rpm", "engine speed" to "rpm",
        "speed" to "speed", "how fast" to "speed",
        "coolant" to "ect", "engine temp" to "ect", "temperature" to "ect", "overheat" to "ect", "hot" to "ect",
        "intake temp" to "iat", "iat" to "iat", "air temp" to "iat",
        "boost" to "boost", "turbo" to "boost",
        "map" to "map", "manifold" to "map",
        "maf" to "maf", "airflow" to "maf", "air flow" to "maf",
        "throttle" to "tps", "load" to "load",
        "battery" to "ecu_voltage", "voltage" to "ecu_voltage", "charging" to "ecu_voltage", "alternator" to "ecu_voltage",
        "timing" to "timing", "ignition" to "timing",
        "fuel level" to "fuel_level", "tank" to "fuel_level",
        "rail" to "frp", "fuel pressure" to "frp",
        "oil temp" to "oil_temp",
        "economy" to "economy_l100", "consumption" to "economy_l100", "mileage" to "economy_kml",
    )

    fun answer(question: String, input: AssistantInput): String {
        val q = question.lowercase().trim()
        if (q.isEmpty()) return "Ask about a reading (\"what is my boost?\"), a code (\"what does P0171 mean?\"), or the car's condition (\"why are my trims negative?\")."

        Regex("\\b([pcbu][0-9][0-9a-f]{3})\\b").find(q)?.let { m ->
            val info = DtcKnowledge.info(m.value.uppercase())
            val sb = StringBuilder("${info.code}: ${info.description}\nSeverity: ${info.severity.label}. ${info.severity.advice}\n")
            if (info.possibleCauses.isNotEmpty()) sb.append("Possible causes (not confirmed):\n").append(info.possibleCauses.joinToString("\n") { "• $it" }).append('\n')
            if (info.diagnosticSteps.isNotEmpty()) sb.append("How to narrow it down:\n").append(info.diagnosticSteps.take(4).mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n"))
            val present = input.dtcs.any { it.code.startsWith(info.code) }
            if (present) sb.append("\nThis code is currently stored or pending in your car.")
            return sb.toString().trim()
        }

        if (listOf("trim", "rich", "lean", "fuel mixture", "negative", "positive").any { q.contains(it) }) {
            val r = assistant.analyse(input)
            val trimLines = r.interpretations.filter { it.reading.contains("trim", true) || it.reading.startsWith("Idle") }
            if (trimLines.isEmpty()) return "I have no fuel trim data yet. Connect, open live data with STFT/LTFT, or record a session with idle and cruise."
            val sb = StringBuilder()
            trimLines.forEach { sb.append("${it.reading}: ${it.meaning}\n") }
            val mix = r.hypotheses.filter { it.category.contains("Rich") || it.category.contains("Lean") }.take(3)
            if (mix.isNotEmpty()) {
                sb.append("\nMost likely explanations so far (hypotheses, not confirmed):\n")
                mix.forEach { sb.append("• ${it.title}: ${it.strength}\n") }
                sb.append("\nFirst test: ${r.testPlan.firstOrNull()?.second?.action ?: "record a session with idle and cruise"}")
            }
            return sb.toString().trim()
        }

        if (listOf("safe to drive", "can i drive", "should i drive", "is it safe").any { q.contains(it) }) {
            val codes = input.dtcs.map { DtcKnowledge.info(it.code.take(5)) }
            val worst = codes.maxByOrNull { it.severity.rank }
            val ect = input.live["ect"]
            return when {
                ect != null && ect > 110 -> "Coolant is ${ect.toInt()} °C. Stop as soon as it is safe and let the engine cool; continuing risks head gasket and cylinder head damage."
                worst == null -> "No trouble codes are stored and nothing in the live data I have is alarming. That does not rule out mechanical problems you can feel or hear."
                else -> "Most serious code: ${worst.code} (${worst.description}). ${worst.severity.advice}"
            }
        }

        if (listOf("what should i check", "what to check", "next step", "what test", "diagnose", "what's wrong", "whats wrong", "problem", "summary", "health").any { q.contains(it) }) {
            val r = assistant.analyse(input)
            val sb = StringBuilder(r.headline).append('\n')
            r.conditions.forEach { sb.append("• $it\n") }
            if (r.testPlan.isNotEmpty()) {
                sb.append("\nSuggested order of checks:\n")
                r.testPlan.take(4).forEachIndexed { i, (h, t) -> sb.append("${i + 1}. ${t.action}\n   → supports \"$h\" if: ${t.confirmsIf}\n") }
            }
            if (r.missingData.isNotEmpty()) sb.append("\nData that would help: ").append(r.missingData.first())
            sb.append("\n\n").append(r.disclaimer)
            return sb.toString().trim()
        }

        if (q.contains("code") || q.contains("dtc") || q.contains("check engine") || q.contains("mil")) {
            if (input.dtcs.isEmpty()) return "No trouble codes in the last read. Use Diagnostics → Read codes to refresh (stored, pending and permanent)."
            return "Codes: " + input.dtcs.joinToString("; ") { "${it.code} ${it.kind.label.lowercase()} – ${app.obdforge.core.dtc.DtcDatabase.describe(it.code.take(5)) ?: "manufacturer-specific"}" }
        }

        // Reading lookups
        val key = synonyms.entries.sortedByDescending { it.key.length }.firstOrNull { q.contains(it.key) }?.value
        if (key != null) {
            val ch = Pids.channel(key)
            val v = input.live[key] ?: if (key == "ecu_voltage") input.live["adapter_voltage"] else null
            if (ch == null) return "I don't know"
            if (v == null) return "${ch.name} is not in the current live data. It may not be supported by this vehicle, or live data is not running."
            val note = when (ch.source) {
                Channel.Source.ECU -> "read from the ECU"
                Channel.Source.CALCULATED -> "calculated by the app (${ch.description})"
                Channel.Source.ENHANCED -> "manufacturer-specific value"
                Channel.Source.GPS -> "from the phone's GPS"
            }
            return "${ch.name}: ${ch.format(v)} ${ch.unit} ($note).${commentFor(key, v)}"
        }
        return "I don't know"
    }

    private fun commentFor(key: String, v: Double): String = when (key) {
        "ect" -> when { v > 105 -> " That is hot: check coolant level and fan."; v < 70 -> " Engine not fully warm yet."; else -> " Normal operating temperature." }
        "ecu_voltage", "adapter_voltage" -> when { v < 12.2 -> " Low: battery discharged or not charging."; v in 13.2..14.8 -> " Normal charging voltage."; v > 14.9 -> " High: check the regulator."; else -> "" }
        "boost" -> if (v < 0) " Negative means manifold vacuum (no boost)." else ""
        else -> ""
    }
}
