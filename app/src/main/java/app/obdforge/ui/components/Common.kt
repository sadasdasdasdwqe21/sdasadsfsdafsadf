package app.obdforge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.obdforge.AppGraph
import app.obdforge.ObdForgeApp
import app.obdforge.ui.theme.Palette

@Composable
fun graph(): AppGraph = (LocalContext.current.applicationContext as ObdForgeApp).graph

/** Registers the channels a screen needs with the live engine while it is visible. */
@Composable
fun RequireChannels(owner: String, keys: Collection<String>) {
    val g = graph()
    val set = keys.toSet()
    DisposableEffect(owner, set) {
        g.live.require(owner, set)
        onDispose { g.live.release(owner) }
    }
}

@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    action: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            if (title != null || action != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    action?.invoke(this)
                }
                Spacer(Modifier.size(8.dp))
            }
            content()
        }
    }
}

@Composable
fun Chip(text: String, color: Color = MaterialTheme.colorScheme.primary, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) { Text(text, color = color, style = MaterialTheme.typography.labelSmall) }
}

@Composable
fun SimBadge() = Chip("SIMULATED", Palette.Amber)

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).clip(CircleShape).background(color))
}

@Composable
fun KeyValue(key: String, value: String, modifier: Modifier = Modifier, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(key, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.45f))
        Text(value, color = valueColor, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(0.55f))
    }
}

@Composable
fun Busy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun InfoNote(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.Info, null, tint = color, modifier = Modifier.size(16.dp).padding(top = 2.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun EmptyState(title: String, body: String, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.invoke()
    }
}

/**
 * Confirmation for anything that changes the vehicle's state. The user must tick the
 * acknowledgement before the confirm button is enabled.
 */
@Composable
fun ConfirmDestructive(
    title: String,
    consequences: List<String>,
    acknowledge: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var ack by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                consequences.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { ack = !ack }) {
                    Checkbox(checked = ack, onCheckedChange = { ack = it })
                    Text(acknowledge, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
            }
        },
        confirmButton = { TextButton(enabled = ack, onClick = { onConfirm(); onDismiss() }) { Text(confirmLabel, color = if (ack) Palette.Red else Color.Gray) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

fun levelColor(level: app.obdforge.core.analysis.Level): Color = when (level) {
    app.obdforge.core.analysis.Level.OK -> Palette.Green
    app.obdforge.core.analysis.Level.INFO -> Palette.Cyan
    app.obdforge.core.analysis.Level.WARN -> Palette.Amber
    app.obdforge.core.analysis.Level.ALERT -> Palette.Red
}

fun severityColor(s: app.obdforge.core.dtc.Severity): Color = when (s) {
    app.obdforge.core.dtc.Severity.LOW -> Palette.Cyan
    app.obdforge.core.dtc.Severity.MODERATE -> Palette.Amber
    app.obdforge.core.dtc.Severity.HIGH -> Palette.Orange
    app.obdforge.core.dtc.Severity.CRITICAL -> Palette.Red
}
