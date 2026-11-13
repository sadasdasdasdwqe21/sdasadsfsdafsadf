package app.livelingo.ui.screens

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.livelingo.R
import app.livelingo.core.CaptionDisplay
import app.livelingo.core.Languages
import app.livelingo.core.RecoveryAction
import app.livelingo.core.TranslationState
import app.livelingo.overlay.stateColor
import app.livelingo.overlay.stateLabel
import app.livelingo.service.TranslationQuickSettingsTile
import app.livelingo.settings.DisplayMode
import app.livelingo.ui.MainViewModel
import app.livelingo.ui.components.NavRow
import app.livelingo.ui.components.SectionCard
import app.livelingo.ui.components.StatusPill
import app.livelingo.ui.components.SubtitlePreview
import app.livelingo.ui.theme.Brand

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(vm: MainViewModel, navigate: (String) -> Unit) {
    val status by vm.status.collectAsState()
    val settings by vm.settings.collectAsState()
    val setup by vm.setup.collectAsState()
    val captions by vm.captions.collectAsState()
    val context = LocalContext.current

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
            Text("LiveLingo", style = MaterialTheme.typography.headlineLarge)
            Text("Live subtitles for any video, in your language", style = MaterialTheme.typography.bodyMedium, color = Brand.TextSecondary)
        }

        // --- Hero: start / stop ---------------------------------------------------------------
        SectionCard {
            val active = status.isActive
            val accent by animateColorAsState(stateColor(status.state), label = "accent")
            val pulse = rememberInfiniteTransition(label = "pulse")
            val scale by pulse.animateFloat(
                initialValue = 1f, targetValue = if (status.state == TranslationState.TRANSLATING) 1.08f else 1f,
                animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "scale",
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(84.dp)
                        .scale(scale)
                        .clip(CircleShape)
                        .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.35f), accent.copy(alpha = 0.08f))))
                        .border(2.dp, accent, CircleShape)
                        .clickable { if (active) vm.stop() else vm.start() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (active) Icons.Rounded.PowerSettingsNew else Icons.Rounded.Translate,
                        contentDescription = if (active) "Stop translation" else "Start translation",
                        tint = accent, modifier = Modifier.size(38.dp),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stateLabel(status.state), style = MaterialTheme.typography.headlineSmall, color = accent)
                    Text(
                        if (active) status.languageLine() else "Tap to start. You can also use the Quick Settings tile.",
                        style = MaterialTheme.typography.bodyMedium, color = Brand.TextSecondary,
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(status.capture.label, if (status.isActive) Brand.Teal else Brand.TextSecondary, Icons.Rounded.GraphicEq)
                status.detectedLanguage?.let { StatusPill("Hearing ${Languages.nameOf(it)}", Brand.Violet, Icons.Rounded.Mic) }
                if (status.engineLabel.isNotBlank() && active) StatusPill(status.engineLabel, Brand.TextSecondary)
            }
            if (active) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val paused = status.state == TranslationState.PAUSED
                    FilledTonalButton(onClick = { if (paused) vm.resume() else vm.pause() }, modifier = Modifier.weight(1f)) {
                        Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null)
                        Spacer(Modifier.width(6.dp)); Text(if (paused) "Resume" else "Pause")
                    }
                    OutlinedButton(onClick = { vm.stop() }, modifier = Modifier.weight(1f)) { Text("Stop") }
                }
            }
            AnimatedVisibility(status.error != null || status.notice != null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    status.error?.let { err ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Brand.Red.copy(alpha = 0.12f)).padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.ErrorOutline, null, tint = Brand.Red)
                            Spacer(Modifier.width(10.dp))
                            Text(err.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            err.recovery?.takeIf { it != RecoveryAction.WAIT }?.let { action ->
                                FilledTonalButton(onClick = { runRecovery(action, vm, context, navigate) }) { Text(action.label) }
                            }
                            TextButton(onClick = { vm.dismissError() }) { Text("Dismiss") }
                        }
                    }
                    status.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Brand.Amber) }
                }
            }
        }

        // --- Language pair ---------------------------------------------------------------------
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LangBlock("From", if (settings.language.autoDetect) "Auto-detect" else Languages.nameOf(settings.language.sourceLanguage), Modifier.weight(1f)) { navigate("languages") }
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(Brand.SurfaceHigh).clickable(enabled = !settings.language.autoDetect) {
                        vm.updateLanguage { it.copy(sourceLanguage = it.targetLanguage, targetLanguage = it.sourceLanguage) }
                    },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.SwapHoriz, "Swap languages", tint = if (settings.language.autoDetect) Brand.TextSecondary else Brand.Teal) }
                LangBlock("To", Languages.nameOf(settings.language.targetLanguage), Modifier.weight(1f), alignEnd = true) { navigate("languages") }
            }
        }

        // --- Overlay preview -------------------------------------------------------------------
        SectionCard(title = "Subtitle preview", subtitle = "This is how subtitles will look over other apps") {
            val preview = if (!captions.isEmpty) captions else CaptionDisplay(
                originalFinal = if (settings.subtitle.displayMode == DisplayMode.ORIGINAL_AND_TRANSLATION) "ماذا تفعل غداً؟" else "",
                translationFinal = "What are you doing tomorrow?",
            )
            SubtitlePreview(settings.subtitle, preview)
            TextButton(onClick = { navigate("subtitles") }) {
                Icon(Icons.Rounded.Tune, null); Spacer(Modifier.width(6.dp)); Text("Customise subtitles")
            }
        }

        // --- Setup checklist -------------------------------------------------------------------
        if (!setup.ready || !setup.batteryUnrestricted || !setup.notifications) {
            SectionCard(title = "Finish setting up", subtitle = "LiveLingo only asks for what it needs to work") {
                CheckRow(Icons.Rounded.Layers, "Display over other apps", "Shows subtitles on top of Instagram, TikTok, YouTube…", setup.overlay) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                }
                CheckRow(Icons.Rounded.Mic, "Audio permission", "Needed to hear the video (internal audio or microphone)", setup.audio) {
                    vm.start()
                }
                CheckRow(Icons.Rounded.Key, "Speech engine", "Add your Soniox key or token server", setup.engineConfigured) { navigate("engine") }
                CheckRow(Icons.Rounded.Notifications, "Notifications", "Shows status with Pause and Stop buttons", setup.notifications) {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }
                CheckRow(Icons.Rounded.BatteryChargingFull, "Unrestricted battery", "Stops Samsung from closing LiveLingo mid-video", setup.batteryUnrestricted) {
                    openBatterySettings(context)
                }
            }
        }

        // --- Quick Settings tile ---------------------------------------------------------------
        SectionCard(title = "Control it from the notification shade", subtitle = "Add the LiveLingo tile once, then start translating from any app") {
            Text(
                "1. Swipe down twice to open Quick Settings.\n2. Tap the pencil (Edit) button.\n3. Drag the LiveLingo tile into your active tiles.\n" +
                    "4. In any app, tap the tile and choose \"Start now\" in Android's dialog. Tap it again to pause or resume.",
                style = MaterialTheme.typography.bodyMedium, color = Brand.TextSecondary,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                FilledTonalButton(onClick = {
                    val sbm = context.getSystemService(StatusBarManager::class.java)
                    sbm?.requestAddTileService(
                        ComponentName(context, TranslationQuickSettingsTile::class.java),
                        "LiveLingo",
                        Icon.createWithResource(context, R.drawable.ic_tile),
                        context.mainExecutor,
                    ) { result ->
                        val msg = when (result) {
                            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Tile added."
                            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "The tile is already in Quick Settings."
                            else -> "Tile not added. You can add it from the Quick Settings editor."
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Add tile now") }
            }
            Text(
                "Android 14 and newer ask for capture permission every time a session starts. Pause and resume do not ask again.",
                style = MaterialTheme.typography.bodySmall, color = Brand.TextSecondary,
            )
        }

        // --- Settings --------------------------------------------------------------------------
        SectionCard(title = "Settings") {
            NavRow(Icons.Rounded.GraphicEq, "Audio capture", "Internal audio, microphone fallback, noise handling") { navigate("audio") }
            NavRow(Icons.Rounded.Key, "Speech & translation engine", "Provider, API key, offline models") { navigate("engine") }
            NavRow(Icons.Rounded.Speed, "Diagnostics", "Latency, audio level, buffer, network") { navigate("diagnostics") }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun LangBlock(label: String, value: String, modifier: Modifier, alignEnd: Boolean = false, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Brand.TextSecondary)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CheckRow(icon: ImageVector, title: String, desc: String, done: Boolean, onFix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (done) Icons.Rounded.CheckCircle else icon, null, tint = if (done) Brand.Green else Brand.Amber)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = Brand.TextSecondary)
        }
        if (!done) TextButton(onClick = onFix) { Text("Fix") }
    }
}

fun openBatterySettings(context: android.content.Context) {
    val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    runCatching { context.startActivity(request) }.onFailure {
        runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}

private fun runRecovery(action: RecoveryAction, vm: MainViewModel, context: android.content.Context, navigate: (String) -> Unit) {
    when (action) {
        RecoveryAction.GRANT_OVERLAY -> context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
        RecoveryAction.GRANT_MICROPHONE -> context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        RecoveryAction.RESTART -> { vm.dismissError(); vm.start() }
        RecoveryAction.SWITCH_TO_MICROPHONE -> app.livelingo.service.ServiceController.send(context, app.livelingo.service.ServiceController.ACTION_USE_MICROPHONE)
        RecoveryAction.OPEN_ENGINE_SETTINGS -> navigate("engine")
        RecoveryAction.OPEN_BATTERY_SETTINGS -> openBatterySettings(context)
        RecoveryAction.WAIT -> Unit
    }
}
