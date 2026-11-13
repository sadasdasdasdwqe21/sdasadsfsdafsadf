package app.livelingo.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.livelingo.core.Languages
import app.livelingo.ui.MainViewModel
import app.livelingo.ui.components.SectionCard
import app.livelingo.ui.components.SettingSwitch
import app.livelingo.ui.theme.Brand
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryScreen(vm: MainViewModel) {
    val settings by vm.settings.collectAsState()
    val entries by vm.history.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }
    val timeFmt = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val dateFmt = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard {
                SettingSwitch(
                    "Keep translation history",
                    settings.general.historyEnabled,
                    { vm.setHistoryEnabled(it) },
                    "Stores text only, on this phone. Audio is never recorded or saved.",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            val intent = vm.exportIntent()
                            if (intent != null) context.startActivity(Intent.createChooser(intent, "Export transcript"))
                        }
                    }, enabled = entries.isNotEmpty()) {
                        Icon(Icons.Rounded.IosShare, null); Spacer(Modifier.width(6.dp)); Text("Export")
                    }
                    OutlinedButton(onClick = { confirmClear = true }, enabled = entries.isNotEmpty()) {
                        Icon(Icons.Rounded.DeleteOutline, null); Spacer(Modifier.width(6.dp)); Text("Clear all")
                    }
                }
            }
        }
        if (entries.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (settings.general.historyEnabled) "Finished sentences will appear here." else "History is turned off.",
                        color = Brand.TextSecondary,
                    )
                }
            }
        }
        items(entries, key = { it.id }) { e ->
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${Languages.nameOf(e.sourceLanguage)} → ${Languages.nameOf(e.targetLanguage)} · ${dateFmt.format(Date(e.createdAt))} ${timeFmt.format(Date(e.createdAt))}",
                        style = MaterialTheme.typography.labelMedium, color = Brand.TextSecondary, modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        vm.copy(e.translation)
                        Toast.makeText(context, "Translation copied", Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Rounded.ContentCopy, "Copy translation", tint = Brand.Teal) }
                    IconButton(onClick = { vm.deleteHistory(e.id) }) { Icon(Icons.Rounded.DeleteOutline, "Delete", tint = Brand.TextSecondary) }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(e.translation, style = MaterialTheme.typography.bodyLarge)
                    if (e.original != e.translation) Text(e.original, style = MaterialTheme.typography.bodyMedium, color = Brand.Amber)
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("This permanently deletes every saved transcript on this phone.") },
            confirmButton = { TextButton(onClick = { vm.clearHistory(); confirmClear = false }) { Text("Delete all") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
