package com.codex.stormy.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "codex_preferences")

class PreferencesRepository(private val context: Context) {

    private object PreferenceKeys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLORS = booleanPreferencesKey("dynamic_colors")
        val FONT_SIZE = floatPreferencesKey("font_size")
        val LINE_NUMBERS = booleanPreferencesKey("line_numbers")
        val WORD_WRAP = booleanPreferencesKey("word_wrap")
        val AUTO_SAVE = booleanPreferencesKey("auto_save")
        // CODE_AUTOCOMPLETION removed - feature disabled

        // API Key (DeepInfra - free, no key required, kept for legacy/future use)
        val API_KEY = stringPreferencesKey("api_key")
        val DEEPINFRA_API_KEY = stringPreferencesKey("deepinfra_api_key")

        // AI Provider and Model settings
        val AI_PROVIDER = stringPreferencesKey("ai_provider")
        val AI_MODEL = stringPreferencesKey("ai_model")
        val DEFAULT_MODEL_ID = stringPreferencesKey("default_model_id")
        val HAS_SET_DEFAULT_MODEL = booleanPreferencesKey("has_set_default_model")

        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")

        // Beta Features
        val DEBUG_LOGS_ENABLED = booleanPreferencesKey("debug_logs_enabled")
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { preferences ->
        try {
            ThemeMode.valueOf(preferences[PreferenceKeys.THEME_MODE] ?: ThemeMode.SYSTEM.name)
        } catch (e: IllegalArgumentException) {
            ThemeMode.SYSTEM
        }
    }

    val dynamicColors: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferenceKeys.DYNAMIC_COLORS] ?: false
    }

    val fontSize: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[PreferenceKeys.FONT_SIZE] ?: 14f
    }

    val lineNumbers: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferenceKeys.LINE_NUMBERS] ?: true
    }

    val wordWrap: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferenceKeys.WORD_WRAP] ?: true
    }

    val autoSave: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferenceKeys.AUTO_SAVE] ?: true
    }

    // Legacy API key (DeepInfra)
    val apiKey: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[PreferenceKeys.API_KEY] ?: ""
    }

    // DeepInfra API key (optional - free tier doesn't require key)
    val deepInfraApiKey: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[PreferenceKeys.DEEPINFRA_API_KEY]
            ?: preferences[PreferenceKeys.API_KEY]
            ?: ""
    }

    // AI Provider and Model
    val aiProvider: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[PreferenceKeys.AI_PROVIDER] ?: "DEEPINFRA"
    }

    val aiModel: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[PreferenceKeys.AI_MODEL] ?: ""
    }

    val defaultModelId: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[PreferenceKeys.DEFAULT_MODEL_ID] ?: ""
    }

    val hasSetDefaultModel: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferenceKeys.HAS_SET_DEFAULT_MODEL] ?: false
    }

    val onboardingCompleted: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferenceKeys.ONBOARDING_COMPLETED] ?: false
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferenceKeys.THEME_MODE] = mode.name
        }
    }

    suspend fun setDynamicColors(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferenceKeys.DYNAMIC_COLORS] = enabled
        }
    }

    suspend fun setFontSize(size: Float) {
        context.dataStore.edit { preferences ->
            preferences[PreferenceKeys.FONT_SIZE] = size.coerceIn(10f, 24f)
        }
    }

    suspend fun setLineNumbers(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferenceKeys.LINE_NUMBERS] = enabled
        }
    }

    suspend fun setWordWrap(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferenceKeys.WORD_WRAP] = enabled
        }
    }

    suspend fun setAutoSave(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferenceKeys.AUTO_SAVE] = enabled
        }
    }

    suspend fun setApiKey(key: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferenceKeys.API_KEY] = key
        }
    }

    suspend fun setDeepInfraApiKey(key: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferenceKeys.DEEPINFRA_API_KEY] = key
        }
    }

    suspend fun setAiProvider(provider: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferenceKeys.AI_PROVIDER] = provider
        }
    }

    suspend fun setAiModel(model: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferenceKeys.AI_MODEL] = model
        }
    }

    suspend fun setDefaultModelId(modelId: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferenceKeys.DEFAULT_MODEL_ID] = modelId
            preferences[PreferenceKeys.HAS_SET_DEFAULT_MODEL] = true
        }
    }

    suspend fun clearDefaultModel() {
        context.dataStore.edit { preferences ->
            preferences[PreferenceKeys.DEFAULT_MODEL_ID] = ""
            preferences[PreferenceKeys.HAS_SET_DEFAULT_MODEL] = false
        }
    }

    /**
     * Get the API key for DeepInfra provider
     * Note: DeepInfra free tier doesn't require an API key
     */
    suspend fun getApiKeyForProvider(provider: String): String {
        val preferences = context.dataStore.data.map { it }.first()
        return preferences[PreferenceKeys.DEEPINFRA_API_KEY]
            ?: preferences[PreferenceKeys.API_KEY] ?: ""
    }

    /**
     * Set the API key for DeepInfra provider
     */
    suspend fun setApiKeyForProvider(provider: String, key: String) {
        setDeepInfraApiKey(key)
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferenceKeys.ONBOARDING_COMPLETED] = completed
        }
    }

    val debugLogsEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferenceKeys.DEBUG_LOGS_ENABLED] ?: false
    }

    suspend fun setDebugLogsEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferenceKeys.DEBUG_LOGS_ENABLED] = enabled
        }
    }
}

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}
