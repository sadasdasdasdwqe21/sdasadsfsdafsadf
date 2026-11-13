package app.livelingo.overlay

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.SubtitlesOff
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.livelingo.core.AudioSourceType
import app.livelingo.core.Languages
import app.livelingo.core.SessionStatus
import app.livelingo.core.TranslationState
import app.livelingo.settings.AppSettings
import app.livelingo.ui.theme.Brand
import app.livelingo.ui.theme.LiveLingoTheme
import kotlinx.coroutines.flow.StateFlow

fun stateColor(state: TranslationState): Color = when (state) {
    TranslationState.TRANSLATING -> Brand.Green
    TranslationState.STARTING -> Brand.Teal
    TranslationState.PAUSED -> Brand.Amber
    TranslationState.ERROR -> Brand.Red
    TranslationState.OFF -> Brand.TextSecondary
}

fun stateLabel(state: TranslationState): String = when (state) {
    TranslationState.TRANSLATING -> "Translating"
    TranslationState.STARTING -> "Starting"
    TranslationState.PAUSED -> "Paused"
    TranslationState.ERROR -> "Needs attention"
    TranslationState.OFF -> "Off"
}

/** The small floating button. Dragging and tapping are handled by the window's touch listener. */
@Composable
fun ControlBubble(status: StateFlow<SessionStatus>) {
    val s by status.collectAsState()
    LiveLingoTheme {
        Box(
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(Brand.Surface.copy(alpha = 0.92f))
                .border(2.5.dp, stateColor(s.state), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Translate, contentDescription = "LiveLingo controls", tint = Brand.TextPrimary, modifier = Modifier.size(24.dp))
        }
    }
}

/** Compact control panel shown when the bubble is tapped. */
@Composable
fun ControlPanel(
    status: StateFlow<SessionStatus>,
    settings: StateFlow<AppSettings>,
    actions: OverlayActions,
    onClose: () -> Unit,
) {
    val s by status.collectAsState()
    val prefs by settings.collectAsState()
    LiveLingoTheme {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = Brand.Surface.copy(alpha = 0.97f),
            border = BorderStroke(1.dp, Brand.Outline),
            shadowElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(stateColor(s.state)))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("LiveLingo", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${stateLabel(s.state)} · ${s.languageLine()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Brand.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Close") }
                }

                s.error?.let {
                    Text(it.message, style = MaterialTheme.typography.bodySmall, color = Brand.Red)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val paused = s.state == TranslationState.PAUSED
                    FilledTonalButton(
                        onClick = { if (paused) actions.resume() else actions.pause() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (paused) "Resume" else "Pause")
                    }
                    OutlinedButton(onClick = { onClose(); actions.stop() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Stop, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Stop")
                    }
                }

                Text("Translate into", style = MaterialTheme.typography.labelMedium, color = Brand.TextSecondary)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Languages.targetsOrdered().forEach { lang ->
                        FilterChip(
                            selected = prefs.language.targetLanguage == lang.code,
                            onClick = { actions.setTarget(lang.code) },
                            label = { Text(lang.name) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Brand.TealDim),
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = s.subtitlesVisible,
                        onClick = { actions.setSubtitlesVisible(!s.subtitlesVisible) },
                        label = { Text(if (s.subtitlesVisible) "Subtitles on" else "Subtitles hidden") },
                        leadingIcon = { Icon(if (s.subtitlesVisible) Icons.Rounded.Subtitles else Icons.Rounded.SubtitlesOff, null, Modifier.size(18.dp)) },
                    )
                    FilterChip(
                        selected = !prefs.subtitle.locked,
                        onClick = { actions.setLocked(!prefs.subtitle.locked) },
                        label = { Text(if (prefs.subtitle.locked) "Move" else "Lock") },
                        leadingIcon = { Icon(Icons.Rounded.OpenWith, null, Modifier.size(18.dp)) },
                    )
                }

                Text("Audio source", style = MaterialTheme.typography.labelMedium, color = Brand.TextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = s.sourceType == AudioSourceType.INTERNAL,
                        onClick = { actions.switchSource(AudioSourceType.INTERNAL) },
                        label = { Text("Internal") },
                        leadingIcon = { Icon(Icons.Rounded.PhoneAndroid, null, Modifier.size(18.dp)) },
                    )
                    FilterChip(
                        selected = s.sourceType == AudioSourceType.MICROPHONE,
                        onClick = { actions.switchSource(AudioSourceType.MICROPHONE) },
                        label = { Text("Microphone") },
                        leadingIcon = { Icon(Icons.Rounded.Mic, null, Modifier.size(18.dp)) },
                    )
                }

                TextButton(onClick = { onClose(); actions.openSettings() }) {
                    Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Subtitle settings")
                }
            }
        }
    }
}
