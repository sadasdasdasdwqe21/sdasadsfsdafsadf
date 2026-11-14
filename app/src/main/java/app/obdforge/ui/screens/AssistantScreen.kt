package app.obdforge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.obdforge.AppGraph
import app.obdforge.assistant.CloudAssistant
import app.obdforge.core.analysis.EngineAnalysis
import app.obdforge.core.analysis.TrimBin
import app.obdforge.core.assistant.AssistantInput
import app.obdforge.core.assistant.AssistantReport
import app.obdforge.core.assistant.DiagnosticAssistant
import app.obdforge.core.assistant.Hypothesis
import app.obdforge.core.assistant.Observation
import app.obdforge.core.assistant.QueryEngine
import app.obdforge.core.assistant.TestOutcome
import app.obdforge.core.live.VehicleSpec
import app.obdforge.data.InvestigationEntity
import app.obdforge.ui.components.Busy
import app.obdforge.ui.components.Chip
import app.obdforge.ui.components.InfoNote
import app.obdforge.ui.components.RequireChannels
import app.obdforge.ui.components.SectionCard
import app.obdforge.ui.components.graph
import app.obdforge.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ChatLine(val fromUser: Boolean, val text: String, val cloud: Boolean = false)

@Composable
fun AssistantScreen(nav: NavHostController) {
    val g = graph()
    val diag by g.diagnostics.state.collectAsStateWithLifecycle()
    val vehicle by g.activeVehicle.collectAsStateWithLifecycle()
    val sessions by g.db.sessions().all().collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by g.settings.state.collectAsStateWithLifecycle()
    val observations = remember { mutableStateListOf<Observation>() }
    val tests = remember { mutableStateMapOf<String, TestOutcome>() }
    var sessionId by remember { mutableStateOf<Long?>(null) }
    var bins by remember { mutableStateOf<List<TrimBin>>(emptyList()) }
    var report by remember { mutableStateOf<AssistantReport?>(null) }
    var busy by remember { mutableStateOf(false) }
    var question by remember { mutableStateOf("") }
    val chat = remember { mutableStateListOf<ChatLine>() }
    var useCloud by remember { mutableStateOf(false) }
    var pickOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    RequireChannels("assistant", listOf("stft1", "ltft1", "stft2", "ltft2", "o2_b1s1_v", "ect", "rpm", "speed", "boost", "ecu_voltage", "maf", "load", "runtime"))

    LaunchedEffect(vehicle?.id) {
        val vid = vehicle?.id ?: return@LaunchedEffect
        val inv = withContext(Dispatchers.IO) { g.db.misc().investigation(vid) } ?: return@LaunchedEffect
        observations.clear(); observations.addAll(inv.observations.split(',').mapNotNull { runCatching { Observation.valueOf(it) }.getOrNull() })
        tests.clear(); inv.testResults.split(',').forEach { e -> val p = e.split('='); if (p.size == 2) runCatching { tests[p[0]] = TestOutcome.valueOf(p[1]) } }
    }

    fun persist() {
        val vid = vehicle?.id ?: return
        scope.launch(Dispatchers.IO) {
            g.db.misc().putInvestigation(InvestigationEntity(vid, observations.joinToString(",") { it.name }, tests.entries.joinToString(",") { "${it.key}=${it.value.name}" }, System.currentTimeMillis()))
        }
    }

    fun input(): AssistantInput = AssistantInput(
        dtcs = diag.stored + diag.pending, freezeFrames = diag.freezeFrames, live = g.live.latestMap(), trimBins = bins, mode06 = diag.mode06,
        observations = observations.toSet(), testResults = tests.toMap(), spec = vehicle?.let { AppGraph.specOf(it) } ?: VehicleSpec(),
    )

    fun analyse() {
        busy = true
        scope.launch {
            bins = withContext(Dispatchers.Default) {
                val data = sessionId?.let { g.recorder.load(it, listOf("rpm", "speed", "tps", "load", "stft1", "ltft1", "stft2", "ltft2")) }
                    ?: listOf("rpm", "speed", "tps", "load", "stft1", "ltft1", "stft2", "ltft2").associateWith { g.live.history(it, System.currentTimeMillis() - 15 * 60_000) }.filterValues { it.size > 0 }
                EngineAnalysis(data).fuelTrims().bank1
            }
            report = withContext(Dispatchers.Default) { DiagnosticAssistant().analyse(input()) }
            busy = false
        }
    }

    ListScreen("Diagnostic assistant", nav) {
        item {
            SectionCard("What the assistant looks at") {
                Text("${diag.stored.size + diag.pending.size} codes · ${diag.freezeFrames.size} freeze frame(s) · ${g.live.latestMap().size} live values · " +
                    "${diag.mode06.size} Mode 06 results", style = MaterialTheme.typography.bodySmall)
                Box {
                    OutlinedButton(onClick = { pickOpen = true }) { Text("Trims from: " + (sessionId?.let { id -> sessions.firstOrNull { it.id == id }?.title } ?: "live data (last 15 min)")) }
                    DropdownMenu(pickOpen, { pickOpen = false }) {
                        DropdownMenuItem(text = { Text("Live data (last 15 min)") }, onClick = { sessionId = null; pickOpen = false })
                        sessions.take(25).forEach { s -> DropdownMenuItem(text = { Text(s.title) }, onClick = { sessionId = s.id; pickOpen = false }) }
                    }
                }
                if (diag.readAt == null) TextButton(onClick = { g.diagnostics.readAll() }) { Text("Read codes first") }
            }
        }
        item {
            SectionCard("Your observations") {
                InfoNote("Things only you can check. They often decide between causes the ECU data cannot separate.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Observation.entries.forEach { o ->
                        FilterChip(o in observations, { if (o in observations) observations.remove(o) else observations.add(o); persist() }, label = { Text(o.label) })
                    }
                }
            }
        }
        item {
            Button(onClick = { analyse() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Analyse") }
            if (busy) Busy("Thinking through the evidence…")
        }
        report?.let { r ->
            item {
                SectionCard("Summary") {
                    Text(r.headline, style = MaterialTheme.typography.titleMedium)
                    r.conditions.forEach { Text("• $it", color = Palette.Amber) }
                    Spacer(Modifier.width(4.dp))
                    InfoNote(r.disclaimer, Palette.Amber)
                }
            }
            if (r.interpretations.isNotEmpty()) item {
                SectionCard("What the readings indicate") {
                    r.interpretations.forEach { Column(Modifier.padding(vertical = 3.dp)) { Text(it.reading, fontWeight = FontWeight.SemiBold); Text(it.meaning, style = MaterialTheme.typography.bodySmall) } }
                }
            }
            items(r.hypotheses, key = { it.id }) { h -> HypothesisCard(h, tests[h.id] ?: TestOutcome.NOT_DONE) { o -> if (o == TestOutcome.NOT_DONE) tests.remove(h.id) else tests[h.id] = o; persist(); analyse() } }
            if (r.testPlan.isNotEmpty()) item {
                SectionCard("Suggested testing sequence") {
                    r.testPlan.take(8).forEachIndexed { i, (hyp, t) ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text("${i + 1}. ${t.action}", fontWeight = FontWeight.Medium)
                            Text("Supports “$hyp” if: ${t.confirmsIf}", style = MaterialTheme.typography.bodySmall, color = Palette.Green)
                            Text("Rules it out if: ${t.eliminatesIf}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (r.missingData.isNotEmpty()) item { SectionCard("Data that would help") { r.missingData.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) } } }
        }
        item {
            SectionCard("Ask about your vehicle") {
                chat.forEach { c ->
                    Text((if (c.fromUser) "You: " else if (c.cloud) "Assistant (cloud): " else "Assistant: ") + c.text,
                        color = if (c.fromUser) Palette.Cyan else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
                }
                OutlinedTextField(question, { question = it }, label = { Text("e.g. Why is my LTFT negative? Is it safe to drive?") }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (g.settings.apiKey.isNotBlank()) { Text("Cloud model", style = MaterialTheme.typography.bodySmall); Switch(useCloud, { useCloud = it }) }
                    Spacer(Modifier.weight(1f))
                    Button(enabled = question.isNotBlank(), onClick = {
                        val q = question.trim(); question = ""; chat.add(ChatLine(true, q))
                        scope.launch {
                            val inp = input()
                            val rep = report ?: withContext(Dispatchers.Default) { DiagnosticAssistant().analyse(inp) }
                            val answer = if (useCloud && g.settings.apiKey.isNotBlank()) {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        CloudAssistant.ask(g.settings.apiKey, settings.cloudModel, q, CloudAssistant.context(inp, rep, vehicle?.name ?: "Vehicle", g.connection.isSimulated))
                                    }
                                }.getOrElse { "Cloud assistant unavailable (${it.message}). Offline answer:\n" + QueryEngine().answer(q, inp) }
                            } else QueryEngine().answer(q, inp)
                            chat.add(ChatLine(false, answer, useCloud))
                        }
                    }) { Text("Ask") }
                }
                if (g.settings.apiKey.isBlank()) InfoNote("Answers come from the built-in offline engine. Add an Anthropic API key in Settings to optionally use a cloud model with the same vehicle data.")
            }
        }
    }
}

@Composable
private fun HypothesisCard(h: Hypothesis, outcome: TestOutcome, onOutcome: (TestOutcome) -> Unit) {
    val color = when {
        h.outcome == TestOutcome.CONFIRMED -> Palette.Red
        h.outcome == TestOutcome.ELIMINATED -> MaterialTheme.colorScheme.onSurfaceVariant
        h.score >= 6 -> Palette.Orange
        h.score >= 3.5 -> Palette.Amber
        else -> Palette.Cyan
    }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(h.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Chip(h.strength, color)
        }
        Text(h.category, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(h.explanation, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
        if (h.evidenceFor.isNotEmpty()) { Text("Evidence for", style = MaterialTheme.typography.labelMedium, color = Palette.Green); h.evidenceFor.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) } }
        if (h.evidenceAgainst.isNotEmpty()) { Text("Evidence against", style = MaterialTheme.typography.labelMedium, color = Palette.Red); h.evidenceAgainst.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) } }
        if (h.measurementsNeeded.isNotEmpty()) { Text("Measurements needed", style = MaterialTheme.typography.labelMedium); h.measurementsNeeded.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) } }
        Text("Record your test result:", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(outcome == TestOutcome.CONFIRMED, { onOutcome(if (outcome == TestOutcome.CONFIRMED) TestOutcome.NOT_DONE else TestOutcome.CONFIRMED) }, label = { Text("Confirmed") })
            FilterChip(outcome == TestOutcome.ELIMINATED, { onOutcome(if (outcome == TestOutcome.ELIMINATED) TestOutcome.NOT_DONE else TestOutcome.ELIMINATED) }, label = { Text("Ruled out") })
        }
    }
}
