package app.livelingo.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "livelingo_settings")

/** Reads and writes every user preference. One JSON value per preference group. */
class SettingsRepository(private val context: Context, scope: CoroutineScope) {

    private object Keys {
        val language = stringPreferencesKey("language")
        val subtitle = stringPreferencesKey("subtitle")
        val audio = stringPreferencesKey("audio")
        val engine = stringPreferencesKey("engine")
        val general = stringPreferencesKey("general")
        /** Set while a session runs, cleared on a clean stop. Detects system kills. */
        val sessionActive = booleanPreferencesKey("session_active")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            language = LanguagePreferences.fromJson(p[Keys.language]),
            subtitle = SubtitlePreferences.fromJson(p[Keys.subtitle]),
            audio = AudioPreferences.fromJson(p[Keys.audio]),
            engine = EnginePreferences.fromJson(p[Keys.engine]),
            general = GeneralPreferences.fromJson(p[Keys.general]),
        )
    }

    /** Hot copy for synchronous reads by services and overlays. */
    val settings: StateFlow<AppSettings> = settingsFlow.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    suspend fun current(): AppSettings = settingsFlow.first()

    suspend fun updateLanguage(block: (LanguagePreferences) -> LanguagePreferences) =
        context.dataStore.edit { p -> p[Keys.language] = block(LanguagePreferences.fromJson(p[Keys.language])).toJson() }

    suspend fun updateSubtitle(block: (SubtitlePreferences) -> SubtitlePreferences) =
        context.dataStore.edit { p -> p[Keys.subtitle] = block(SubtitlePreferences.fromJson(p[Keys.subtitle])).toJson() }

    suspend fun updateAudio(block: (AudioPreferences) -> AudioPreferences) =
        context.dataStore.edit { p -> p[Keys.audio] = block(AudioPreferences.fromJson(p[Keys.audio])).toJson() }

    suspend fun updateEngine(block: (EnginePreferences) -> EnginePreferences) =
        context.dataStore.edit { p -> p[Keys.engine] = block(EnginePreferences.fromJson(p[Keys.engine])).toJson() }

    suspend fun updateGeneral(block: (GeneralPreferences) -> GeneralPreferences) =
        context.dataStore.edit { p -> p[Keys.general] = block(GeneralPreferences.fromJson(p[Keys.general])).toJson() }

    suspend fun setSessionActive(active: Boolean) =
        context.dataStore.edit { p -> p[Keys.sessionActive] = active }

    suspend fun wasSessionActive(): Boolean = context.dataStore.data.map { it[Keys.sessionActive] ?: false }.first()
}
