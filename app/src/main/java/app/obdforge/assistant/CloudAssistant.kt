package app.obdforge.assistant

import app.obdforge.core.assistant.AssistantInput
import app.obdforge.core.assistant.AssistantReport
import app.obdforge.core.pid.Pids
import app.obdforge.core.util.Json
import app.obdforge.core.util.asList
import app.obdforge.core.util.asMap
import app.obdforge.core.util.asStr
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional: sends the question plus the same structured context the offline engine uses to the
 * Anthropic Messages API with the user's own API key. Off unless a key is entered in Settings.
 * The system prompt holds the model to the app's rules: hypotheses, not confirmed faults, and no
 * part replacement on a single reading.
 */
object CloudAssistant {
    private const val SYSTEM = """You are an automotive diagnostic assistant inside an OBD-II scan tool app.
Rules:
- Base answers on the vehicle data provided. Say clearly when data is missing.
- Present causes as hypotheses ranked by evidence, never as confirmed faults.
- Never recommend replacing a part based on a single sensor reading; give the test that would confirm or rule it out first.
- Distinguish values measured by the ECU from values calculated or estimated by the app (they are labelled).
- Do not invent manufacturer-specific codes, PIDs or procedures. If something is manufacturer-specific and not in the data, say so.
- If data is marked simulated, mention that the readings are from the simulator.
- Be concise and practical. Use plain language."""

    fun context(input: AssistantInput, report: AssistantReport, vehicle: String, simulated: Boolean): String {
        val live = input.live.mapNotNull { (k, v) ->
            val c = Pids.channel(k) ?: return@mapNotNull null
            "${c.name}: ${c.format(v)} ${c.unit}" + if (c.source != app.obdforge.core.pid.Channel.Source.ECU) " [${c.source.badge}]" else ""
        }
        return Json.write(mapOf(
            "vehicle" to vehicle, "simulated" to simulated,
            "dtcs" to input.dtcs.map { "${it.code} (${it.kind.label})" },
            "liveData" to live,
            "fuelTrimsByCondition" to input.trimBins.map { mapOf("condition" to it.condition, "stft" to it.stft, "ltft" to it.ltft, "samples" to it.samples) },
            "freezeFrames" to input.freezeFrames.map { mapOf("dtc" to it.dtc, "values" to it.values) },
            "mode06Failed" to input.mode06.filter { !it.passed }.map { "${it.monitorName} TID ${it.tid}: ${it.value} ${it.unit} (limits ${it.min}–${it.max})" },
            "userObservations" to input.observations.map { it.label },
            "userTestResults" to input.testResults.mapValues { it.value.label },
            "offlineEngineHypotheses" to report.hypotheses.take(6).map { "${it.title}: ${it.strength}" },
        ), pretty = true)
    }

    /** Blocking network call; run on Dispatchers.IO. */
    fun ask(apiKey: String, model: String, question: String, contextJson: String): String {
        val body = Json.write(mapOf(
            "model" to model,
            "max_tokens" to 1200,
            "system" to SYSTEM,
            "messages" to listOf(mapOf("role" to "user", "content" to "Vehicle data:\n$contextJson\n\nQuestion: $question")),
        ))
        val c = (URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 15000; readTimeout = 60000
            setRequestProperty("content-type", "application/json")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", "2023-06-01")
        }
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        if (code !in 200..299) {
            val msg = runCatching { Json.parse(text).asMap()["error"].asMap()["message"].asStr() }.getOrNull()
            throw IllegalStateException("Cloud assistant error $code: ${msg ?: text.take(200)}")
        }
        return Json.parse(text).asMap()["content"].asList().mapNotNull { it.asMap()["text"].asStr() }.joinToString("\n").trim()
    }
}
