package app.livelingo.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.livelingo.audio.AudioEnvironment
import app.livelingo.core.AudioSourceType
import app.livelingo.core.Languages
import app.livelingo.settings.CredentialMode
import app.livelingo.settings.SpeechEngine
import app.livelingo.settings.TranslationEngine
import app.livelingo.ui.MainViewModel
import app.livelingo.ui.components.RadioRow
import app.livelingo.ui.components.SectionCard
import app.livelingo.ui.components.SettingSwitch
import app.livelingo.ui.components.SliderRow
import app.livelingo.ui.theme.Brand
import kotlin.math.roundToInt

@Composable
fun AudioScreen(vm: MainViewModel) {
    val settings by vm.settings.collectAsState()
    val setup by vm.setup.collectAsState()
    val a = settings.audio
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.refreshSetup() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionCard(title = "Audio source", subtitle = "Where LiveLingo listens") {
            RadioRow(
                "Internal audio (recommended)",
                a.preferredSource == AudioSourceType.INTERNAL,
                { vm.updateAudio { it.copy(preferredSource = AudioSourceType.INTERNAL) } },
                description = if (AudioEnvironment.internalCaptureSupported())
                    "Copies the sound other apps play, without room noise. Works with headphones. Android asks for permission each session."
                else "Needs Android 10 or newer.",
                enabled = AudioEnvironment.internalCaptureSupported(),
            )
            RadioRow(
                "Microphone",
                a.preferredSource == AudioSourceType.MICROPHONE,
                { vm.updateAudio { it.copy(preferredSource = AudioSourceType.MICROPHONE) } },
                description = "Listens to the phone speaker through the mic. Works with apps that block capture, but picks up room noise.",
            )
            SettingSwitch(
                "Fall back to the microphone automatically",
                a.autoFallbackToMicrophone,
                { b -> vm.updateAudio { it.copy(autoFallbackToMicrophone = b) } },
                "If an app blocks internal capture, or you decline the capture dialog, switch to the mic instead of stopping.",
            )
        }

        if (setup.headphones) {
            SectionCard(title = "Headphones connected") {
                Text(
                    "Internal audio keeps working with Bluetooth or wired headphones. The microphone cannot hear audio playing in your headphones, so microphone mode will not work until they are disconnected.",
                    style = MaterialTheme.typography.bodyMedium, color = Brand.Amber,
                )
            }
        }

        SectionCard(title = "Microphone processing") {
            SettingSwitch("Noise suppression", a.noiseSuppression, { b -> vm.updateAudio { it.copy(noiseSuppression = b) } }, "Reduces steady background noise like fans and traffic")
            SettingSwitch("Automatic gain", a.autoGain, { b -> vm.updateAudio { it.copy(autoGain = b) } }, "Evens out quiet and loud speech")
            Text(
                "Echo cancellation is intentionally off: it removes the phone's own speaker sound from the microphone, which is exactly the video audio LiveLingo needs to hear.",
                style = MaterialTheme.typography.bodySmall, color = Brand.TextSecondary,
            )
        }

        SectionCard(title = "Voice detection") {
            SettingSwitch(
                "Skip silence",
                a.silenceGating,
                { b -> vm.updateAudio { it.copy(silenceGating = b) } },
                "Only speech is streamed to the cloud engine. Saves data and cost; the start of each sentence is kept with a short pre-roll.",
            )
            SliderRow("Sensitivity", a.vadSensitivity, 0f..1f, { "${(it * 100).roundToInt()}%" }) { v -> vm.updateAudio { it.copy(vadSensitivity = v) } }
        }

        SectionCard(title = "Battery", subtitle = "Samsung may close background apps to save power") {
            Text(
                if (setup.batteryUnrestricted) "LiveLingo is exempt from battery optimisation."
                else "Set LiveLingo's battery use to Unrestricted so One UI does not stop it during long videos. Streaming audio uses roughly the power of a video call.",
                style = MaterialTheme.typography.bodyMedium, color = if (setup.batteryUnrestricted) Brand.Green else Brand.TextSecondary,
            )
            if (!setup.batteryUnrestricted) FilledTonalButton(onClick = { openBatterySettings(context) }) { Text("Allow unrestricted battery") }
        }
    }
}

@Composable
fun EngineScreen(vm: MainViewModel) {
    val settings by vm.settings.collectAsState()
    val setup by vm.setup.collectAsState()
    val message by vm.engineMessage.collectAsState()
    val busy by vm.busy.collectAsState()
    val e = settings.engine
    LaunchedEffect(Unit) { vm.refreshSetup(); vm.clearEngineMessage() }

    var keyInput by remember { mutableStateOf("") }
    var secretInput by remember { mutableStateOf("") }
    var urlInput by remember(e.tokenServerUrl) { mutableStateOf(e.tokenServerUrl) }
    var modelInput by remember(e.sonioxModel) { mutableStateOf(e.sonioxModel) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionCard(title = "Speech recognition") {
            SpeechEngine.entries.forEach { s ->
                val enabled = s != SpeechEngine.ON_DEVICE || setup.onDeviceAvailable
                RadioRow(
                    s.label, e.speechEngine == s, { vm.updateEngine { it.copy(speechEngine = s) } },
                    description = if (enabled) s.description else "Needs Android 13+ with on-device recognition available.",
                    enabled = enabled,
                )
            }
        }

        SectionCard(title = "Translation") {
            TranslationEngine.entries.forEach { t ->
                val enabled = !(t == TranslationEngine.INLINE && e.speechEngine == SpeechEngine.ON_DEVICE)
                RadioRow(t.label, e.translationEngine == t, { vm.updateEngine { it.copy(translationEngine = t) } }, description = t.description, enabled = enabled)
            }
            if (e.speechEngine == SpeechEngine.ON_DEVICE) {
                Text("The on-device recognizer only transcribes, so ML Kit translates.", style = MaterialTheme.typography.bodySmall, color = Brand.TextSecondary)
            }
            if (e.translationEngine == TranslationEngine.ML_KIT || e.speechEngine == SpeechEngine.ON_DEVICE) {
                OutlinedButton(onClick = { vm.downloadTranslationModel() }, enabled = !busy) { Text("Download offline translation model") }
            }
        }

        if (e.speechEngine == SpeechEngine.SONIOX) {
            SectionCard(title = "Soniox credentials", subtitle = "Create a key at console.soniox.com. Real-time translation costs roughly US$0.18 per hour of speech.") {
                CredentialMode.entries.forEach { m ->
                    RadioRow(m.label, e.credentialMode == m, { vm.updateEngine { it.copy(credentialMode = m) } })
                }
                when (e.credentialMode) {
                    CredentialMode.DEVICE_KEY -> {
                        Text(
                            if (vm.hasSonioxKey()) "A key is saved (encrypted with the Android Keystore)." else "No key saved yet.",
                            style = MaterialTheme.typography.bodySmall, color = if (vm.hasSonioxKey()) Brand.Green else Brand.Amber,
                        )
                        OutlinedTextField(
                            value = keyInput, onValueChange = { keyInput = it.trim() },
                            label = { Text("Soniox API key") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { vm.saveSonioxKey(keyInput); keyInput = "" }, enabled = keyInput.isNotBlank()) { Text("Save key") }
                            if (vm.hasSonioxKey()) OutlinedButton(onClick = { vm.saveSonioxKey("") }) { Text("Remove") }
                        }
                        Text(
                            "Fine for personal use. If you share the app, use a token server so your key never sits on other people's phones.",
                            style = MaterialTheme.typography.bodySmall, color = Brand.TextSecondary,
                        )
                    }
                    CredentialMode.TOKEN_SERVER -> {
                        OutlinedTextField(
                            value = urlInput, onValueChange = { urlInput = it.trim() },
                            label = { Text("Token server URL (https://…)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = secretInput, onValueChange = { secretInput = it.trim() },
                            label = { Text(if (vm.hasTokenSecret()) "Client secret (saved)" else "Client secret") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                        )
                        FilledTonalButton(onClick = {
                            vm.updateEngine { it.copy(tokenServerUrl = urlInput) }
                            if (secretInput.isNotBlank()) { vm.saveTokenSecret(secretInput); secretInput = "" }
                        }) { Text("Save") }
                        Text(
                            "The server (see backend/ in the project) keeps the real Soniox key and hands the phone a temporary key for each session.",
                            style = MaterialTheme.typography.bodySmall, color = Brand.TextSecondary,
                        )
                    }
                }
            }

            SectionCard(title = "Advanced") {
                OutlinedTextField(
                    value = modelInput, onValueChange = { modelInput = it.trim() },
                    label = { Text("Real-time model") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                if (modelInput != e.sonioxModel && modelInput.isNotBlank()) {
                    FilledTonalButton(onClick = { vm.updateEngine { it.copy(sonioxModel = modelInput) } }) { Text("Use this model") }
                }
                SliderRow(
                    "Sentence end after pause", e.endpointDelayMs.toFloat(), 500f..3000f, { "${it.roundToInt()} ms" },
                ) { v -> vm.updateEngine { it.copy(endpointDelayMs = (v / 100).roundToInt() * 100) } }
                Text("Shorter makes sentences finish faster; longer keeps slow speakers' sentences together.", style = MaterialTheme.typography.bodySmall, color = Brand.TextSecondary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = { vm.testConnection() }, enabled = !busy) { Text("Test connection") }
                    if (busy) { Spacer(Modifier.width(12.dp)); CircularProgressIndicator(Modifier.width(22.dp).height(22.dp), strokeWidth = 2.dp) }
                }
            }
        } else {
            SectionCard(title = "Offline speech model") {
                val lang = settings.language
                Text(
                    if (lang.autoDetect) "Turn off auto-detect on the Languages tab and choose the spoken language. The offline engine cannot detect languages."
                    else "Spoken language: ${Languages.nameOf(lang.sourceLanguage)} (${lang.dialectLocale ?: Languages.find(lang.sourceLanguage)?.onDeviceLocale})",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.checkOnDeviceModel() }) { Text("Check model") }
                    FilledTonalButton(onClick = { vm.downloadOnDeviceModel() }, enabled = Build.VERSION.SDK_INT >= 33) { Text("Download") }
                }
            }
        }

        message?.let {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Brand.SurfaceHigh).padding(14.dp)) {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun DiagnosticsScreen(vm: MainViewModel) {
    val d by vm.diagnostics.collectAsState()
    val s by vm.status.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionCard(title = "Audio input") {
            DiagRow("Source", if (s.isActive) "${d.audioInput} · ${s.capture.label}" else "Idle")
            DiagRow("Capture rate", if (d.sampleRate > 0) "${d.sampleRate} Hz → 16000 Hz mono" else "-")
            Text("Level", style = MaterialTheme.typography.bodyMedium, color = Brand.TextSecondary)
            val frac = ((d.levelDb + 70f) / 70f).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Brand.SurfaceHigh)) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(if (d.speechDetected) Brand.Green else Brand.TealDim))
            }
            DiagRow("Speech detected", if (d.speechDetected) "Yes" else "No")
        }
        SectionCard(title = "Recognition") {
            DiagRow("Detected language", d.detectedLanguage?.let { Languages.nameOf(it) } ?: "-")
            DiagRow("Speech recognition latency", d.recognitionLatencyMs?.let { "$it ms" } ?: "-")
            DiagRow("Translation lag", d.translationLatencyMs?.let { "$it ms" } ?: "-")
            DiagRow("Total subtitle latency", d.totalLatencyMs?.let { "$it ms" } ?: "-")
            Text(
                "Recognition latency is measured from the moment audio was captured to the moment its text arrived. Translation lag is how long the translation trails the end of a sentence.",
                style = MaterialTheme.typography.bodySmall, color = Brand.TextSecondary,
            )
        }
        SectionCard(title = "Network and buffer") {
            DiagRow("Network", if (d.networkOnline) "Online" else "Offline")
            DiagRow("Engine connection", d.connection)
            DiagRow("Buffer", "${d.bufferQueued} / ${d.bufferCapacity} chunks (100 ms each)")
            DiagRow("Dropped audio chunks", d.droppedChunks.toString())
            DiagRow("Reconnects", d.reconnects.toString())
            DiagRow("Speech streamed", "%.1f min · %.1f MB".format(d.audioSentSeconds / 60.0, d.bytesSent / 1_048_576.0))
        }
        SectionCard(title = "Battery") {
            val drop = if (d.batteryAtStart >= 0 && d.batteryNow >= 0) d.batteryAtStart - d.batteryNow else null
            val minutes = if (d.sessionStartedAt > 0) (System.currentTimeMillis() - d.sessionStartedAt) / 60_000 else 0
            DiagRow("Session length", if (s.isActive) "$minutes min" else "-")
            DiagRow("Battery used this session", drop?.let { "$it%" } ?: "-")
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Brand.TextSecondary, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
