package app.livelingo.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.livelingo.core.Languages
import app.livelingo.ui.MainViewModel
import app.livelingo.ui.components.RadioRow
import app.livelingo.ui.components.SectionCard
import app.livelingo.ui.components.SettingSwitch
import app.livelingo.ui.theme.Brand

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LanguageScreen(vm: MainViewModel) {
    val settings by vm.settings.collectAsState()
    val lang = settings.language
    val currentKey = lang.pairKey

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionCard(title = "Current pair") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Languages.pairLabel(currentKey), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.updateLanguage { it.toggleFavourite(currentKey) } }) {
                    val fav = currentKey in lang.favouritePairs
                    Icon(if (fav) Icons.Rounded.Star else Icons.Rounded.StarBorder, if (fav) "Remove favourite" else "Add favourite", tint = Brand.Amber)
                }
            }
            if (lang.favouritePairs.isNotEmpty()) {
                Text("Favourites", style = MaterialTheme.typography.labelMedium, color = Brand.TextSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    lang.favouritePairs.forEach { key ->
                        FilterChip(selected = key == currentKey, onClick = { vm.applyPair(key) }, label = { Text(Languages.pairLabel(key)) },
                            leadingIcon = { Icon(Icons.Rounded.Star, null) })
                    }
                }
            }
            if (lang.recentPairs.isNotEmpty()) {
                Text("Recent", style = MaterialTheme.typography.labelMedium, color = Brand.TextSecondary)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    lang.recentPairs.forEach { key ->
                        AssistChip(onClick = { vm.applyPair(key) }, label = { Text(Languages.pairLabel(key)) }, leadingIcon = { Icon(Icons.Rounded.History, null) })
                    }
                }
            }
        }

        SectionCard(title = "Spoken language", subtitle = "What the people in the video are speaking") {
            SettingSwitch(
                "Detect automatically",
                lang.autoDetect,
                { on -> vm.updateLanguage { it.copy(autoDetect = on) } },
                description = "Recognises the language continuously, even if speakers switch. Choose a language manually for best accuracy on short clips.",
            )
            if (!lang.autoDetect) {
                Languages.all.forEach { l ->
                    RadioRow(
                        title = "${l.name}  ·  ${l.nativeName}",
                        selected = lang.sourceLanguage == l.code,
                        onClick = { vm.updateLanguage { it.copy(sourceLanguage = l.code, dialectLocale = null) } },
                    )
                }
            }
            val src = Languages.find(lang.sourceLanguage)
            if (!lang.autoDetect && src != null && src.dialects.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("${src.name} dialect", style = MaterialTheme.typography.titleMedium)
                Text(
                    "The cloud engine understands regional dialects in one model automatically. The offline engine needs the closest regional variant.",
                    style = MaterialTheme.typography.bodySmall, color = Brand.TextSecondary,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    src.dialects.forEach { d ->
                        FilterChip(
                            selected = (lang.dialectLocale ?: src.onDeviceLocale) == d.locale,
                            onClick = { vm.updateLanguage { it.copy(dialectLocale = d.locale) } },
                            label = { Text(d.label) },
                        )
                    }
                }
            }
        }

        SectionCard(title = "Translate into", subtitle = "Changes apply immediately, even while translating") {
            Languages.targetsOrdered().forEach { l ->
                RadioRow(
                    title = "${l.name}  ·  ${l.nativeName}",
                    selected = lang.targetLanguage == l.code,
                    onClick = { vm.updateLanguage { it.copy(targetLanguage = l.code) } },
                )
            }
        }
    }
}
