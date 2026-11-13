package app.livelingo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.livelingo.core.CaptionDisplay
import app.livelingo.settings.DisplayMode
import app.livelingo.settings.FontChoice
import app.livelingo.settings.OverlayAnchor
import app.livelingo.settings.SubtitlePreferences
import app.livelingo.settings.SubtitlePreset
import app.livelingo.settings.TextAlign
import app.livelingo.ui.MainViewModel
import app.livelingo.ui.components.ColorSwatches
import app.livelingo.ui.components.SectionCard
import app.livelingo.ui.components.SettingSwitch
import app.livelingo.ui.components.SliderRow
import app.livelingo.ui.components.SubtitlePreview
import app.livelingo.ui.theme.Brand
import kotlin.math.roundToInt

private val textColors = listOf(0xFFFFFFFF, 0xFFFFEB3B, 0xFFFFE08A, 0xFF4FD1C5, 0xFF8BE9FD, 0xFFB9F6CA, 0xFFFFB3C1).map { it.toInt() }
private val bgColors = listOf(0xFF000000, 0xFF0B0F17, 0xFF1E293B, 0xFF263238, 0xFF3E2723, 0xFF1A237E, 0xFFFFFFFF).map { it.toInt() }
private val outlineColors = listOf(0xFF000000, 0xFF1A1A1A, 0xFF0D47A1, 0xFF4A148C, 0xFFFFFFFF).map { it.toInt() }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubtitleScreen(vm: MainViewModel) {
    val settings by vm.settings.collectAsState()
    val status by vm.status.collectAsState()
    val captions by vm.captions.collectAsState()
    val saved = settings.subtitle

    // Local copy so sliders preview instantly; persisted on release.
    var draft by remember { mutableStateOf(saved) }
    LaunchedEffect(saved) { draft = saved }

    fun commit(next: SubtitlePreferences) {
        draft = next
        vm.updateSubtitle { next }
    }
    fun custom(block: (SubtitlePreferences) -> SubtitlePreferences) = commit(block(draft).copy(preset = SubtitlePreset.CUSTOM))

    val sample = if (!captions.isEmpty) captions else CaptionDisplay(
        originalFinal = "ماذا تفعل غداً؟ ",
        translationFinal = "What are you doing tomorrow? ",
        translationProvisional = "I was thinking we could",
    )

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.background(Brand.Background).padding(horizontal = 16.dp, vertical = 8.dp)) {
            SubtitlePreview(draft, sample, height = 200)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionCard(title = "Style preset") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SubtitlePreset.entries.forEach { p ->
                        FilterChip(selected = draft.preset == p, onClick = { commit(draft.applyPreset(p)) }, label = { Text(p.label) })
                    }
                }
            }

            SectionCard(title = "What to show") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    DisplayMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = draft.displayMode == m,
                            onClick = { commit(draft.copy(displayMode = m)) },
                            shape = SegmentedButtonDefaults.itemShape(i, DisplayMode.entries.size),
                        ) { Text(m.label) }
                    }
                }
                Text("Original text appears above the translation in a smaller, coloured font.", style = MaterialTheme.typography.bodySmall, color = Brand.TextSecondary)
            }

            SectionCard(title = "Text") {
                SliderRow("Font size", draft.fontSizeSp, 12f..36f, { "${it.roundToInt()} sp" }, onLive = { draft = draft.copy(fontSizeSp = it) }) { v -> custom { it.copy(fontSizeSp = v) } }
                Text("Font", style = MaterialTheme.typography.bodyLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FontChoice.entries.forEach { f ->
                        FilterChip(selected = draft.font == f, onClick = { custom { it.copy(font = f) } }, label = { Text(f.label) })
                    }
                }
                SettingSwitch("Bold", draft.bold, { b -> custom { it.copy(bold = b) } })
                Text("Translation colour", style = MaterialTheme.typography.bodyLarge)
                ColorSwatches(textColors, draft.textColor) { col -> custom { it.copy(textColor = col) } }
                Text("Original-language colour", style = MaterialTheme.typography.bodyLarge)
                ColorSwatches(textColors, draft.originalTextColor) { col -> custom { it.copy(originalTextColor = col) } }
                SliderRow("Line spacing", draft.lineSpacing, 0.9f..1.6f, { "%.2f×".format(it) }, onLive = { draft = draft.copy(lineSpacing = it) }) { v -> custom { it.copy(lineSpacing = v) } }
                SliderRow("Maximum lines", draft.maxLines.toFloat(), 1f..5f, { it.roundToInt().toString() }, steps = 3, onLive = { draft = draft.copy(maxLines = it.roundToInt()) }) { v -> custom { it.copy(maxLines = v.roundToInt()) } }
                Text("Alignment", style = MaterialTheme.typography.bodyLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    TextAlign.entries.forEachIndexed { i, a ->
                        SegmentedButton(selected = draft.align == a, onClick = { custom { it.copy(align = a) } }, shape = SegmentedButtonDefaults.itemShape(i, TextAlign.entries.size)) { Text(a.label) }
                    }
                }
            }

            SectionCard(title = "Outline and shadow") {
                SettingSwitch("Text outline", draft.outline, { b -> custom { it.copy(outline = b) } }, "Keeps text readable on bright video")
                if (draft.outline) {
                    SliderRow("Outline width", draft.outlineWidthDp, 0.5f..5f, { "%.1f dp".format(it) }, onLive = { draft = draft.copy(outlineWidthDp = it) }) { v -> custom { it.copy(outlineWidthDp = v) } }
                    ColorSwatches(outlineColors, draft.outlineColor) { col -> custom { it.copy(outlineColor = col) } }
                }
                SettingSwitch("Text shadow", draft.shadow, { b -> custom { it.copy(shadow = b) } })
            }

            SectionCard(title = "Background") {
                ColorSwatches(bgColors, draft.backgroundColor) { col -> custom { it.copy(backgroundColor = col) } }
                SliderRow("Opacity", draft.backgroundOpacity, 0f..1f, { "${(it * 100).roundToInt()}%" }, onLive = { draft = draft.copy(backgroundOpacity = it) }) { v -> custom { it.copy(backgroundOpacity = v) } }
                SliderRow("Rounded corners", draft.cornerRadiusDp, 0f..28f, { "${it.roundToInt()} dp" }, onLive = { draft = draft.copy(cornerRadiusDp = it) }) { v -> custom { it.copy(cornerRadiusDp = v) } }
            }

            SectionCard(title = "Position and size", subtitle = "You can also drag and resize subtitles directly on screen") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    OverlayAnchor.entries.forEachIndexed { i, a ->
                        SegmentedButton(
                            selected = kotlin.math.abs(draft.yFraction - a.yFraction) < 0.02f,
                            onClick = { commit(draft.copy(yFraction = a.yFraction, xFraction = (1f - draft.widthFraction) / 2f)) },
                            shape = SegmentedButtonDefaults.itemShape(i, OverlayAnchor.entries.size),
                        ) { Text(a.label) }
                    }
                }
                SliderRow("Width", draft.widthFraction, 0.3f..1f, { "${(it * 100).roundToInt()}%" }, onLive = { draft = draft.copy(widthFraction = it) }) { v ->
                    commit(draft.copy(widthFraction = v, xFraction = ((1f - v) / 2f).coerceAtLeast(0f)))
                }
                SliderRow("Minimum height", draft.minHeightDp, 0f..200f, { if (it < 1f) "Auto" else "${it.roundToInt()} dp" }, onLive = { draft = draft.copy(minHeightDp = it) }) { v -> commit(draft.copy(minHeightDp = v)) }
                SettingSwitch(
                    "Lock position",
                    draft.locked,
                    { b -> commit(draft.copy(locked = b)) },
                    "Locked subtitles let every tap through to the app underneath. Unlock to drag or resize them on screen.",
                )
                SettingSwitch(
                    "Tap-through mode",
                    draft.tapThrough,
                    { b -> commit(draft.copy(tapThrough = b)) },
                    "Android 12+ only passes taps through overlays drawn at 80% opacity or less. Turn off for fully solid subtitles that block taps on the text area.",
                )
                if (status.isActive && draft.locked) {
                    FilledTonalButton(onClick = { commit(draft.copy(locked = false)) }) { Text("Move subtitles on screen now") }
                }
            }

            SectionCard(title = "Behaviour") {
                SliderRow("Clear after silence", draft.clearAfterSeconds.toFloat(), 2f..20f, { "${it.roundToInt()} s" }) { v -> commit(draft.copy(clearAfterSeconds = v.roundToInt())) }
                SettingSwitch("Floating control bubble", draft.showBubble, { b -> commit(draft.copy(showBubble = b)) }, "Small movable button with pause, stop, language and source controls")
            }
        }
    }
}
