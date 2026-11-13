package app.livelingo.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.livelingo.core.CaptionDisplay
import app.livelingo.overlay.SubtitleView
import app.livelingo.settings.SubtitlePreferences
import app.livelingo.ui.theme.Brand

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenTopBar(title: String, onBack: (() -> Unit)? = null) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Brand.Background),
    )
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Brand.Surface,
        border = BorderStroke(1.dp, Brand.Outline),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Brand.TextSecondary)
                }
            }
            content()
        }
    }
}

@Composable
fun SettingSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit, description: String? = null, enabled: Boolean = true) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) Brand.TextPrimary else Brand.TextSecondary)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = Brand.TextSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = Brand.Teal, checkedThumbColor = Color(0xFF00201D)),
        )
    }
}

/** Slider that only writes the preference when the finger lifts, but previews while dragging. */
@Composable
fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    label: (Float) -> String,
    steps: Int = 0,
    onLive: ((Float) -> Unit)? = null,
    onCommit: (Float) -> Unit,
) {
    var local by remember { mutableFloatStateOf(value) }
    LaunchedEffect(value) { local = value }
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(label(local), style = MaterialTheme.typography.labelLarge, color = Brand.Teal)
        }
        Slider(
            value = local,
            onValueChange = { local = it; onLive?.invoke(it) },
            onValueChangeFinished = { onCommit(local) },
            valueRange = range,
            steps = steps,
        )
    }
}

@Composable
fun RadioRow(title: String, selected: Boolean, onClick: () -> Unit, description: String? = null, enabled: Boolean = true, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) Brand.TextPrimary else Brand.TextSecondary)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = Brand.TextSecondary)
        }
        trailing?.invoke()
    }
}

@Composable
fun NavRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Brand.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = Brand.Teal) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Brand.TextSecondary)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = Brand.TextSecondary)
    }
}

@Composable
fun StatusPill(text: String, color: Color, icon: ImageVector? = null) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
        } else {
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
fun ColorSwatches(colors: List<Int>, selected: Int, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        colors.forEach { argb ->
            val isSel = (argb or 0xFF000000.toInt()) == (selected or 0xFF000000.toInt())
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color(argb or 0xFF000000.toInt()))
                    .border(if (isSel) 3.dp else 1.dp, if (isSel) Brand.Teal else Brand.Outline, CircleShape)
                    .clickable { onPick(argb or 0xFF000000.toInt()) },
            )
        }
    }
}

/**
 * Renders the real overlay view over a mock video frame, so the preview is exactly what will
 * appear over other apps.
 */
@Composable
fun SubtitlePreview(style: SubtitlePreferences, caption: CaptionDisplay, modifier: Modifier = Modifier, height: Int = 190) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF2B3A55), Color(0xFF6B4E71), Color(0xFFC08552)))),
    ) {
        val anchor = style.yFraction.coerceIn(0f, 0.85f)
        Box(Modifier.fillMaxWidth().padding(top = (height * anchor * 0.82f).dp, start = 10.dp, end = 10.dp)) {
            AndroidView(
                factory = { ctx -> SubtitleView(ctx) },
                update = { v ->
                    v.style = style.copy(minHeightDp = 0f)
                    v.caption = caption
                },
                modifier = Modifier.fillMaxWidth(style.widthFraction.coerceIn(0.3f, 1f)).align(Alignment.TopCenter),
            )
        }
    }
}

val ScreenPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
