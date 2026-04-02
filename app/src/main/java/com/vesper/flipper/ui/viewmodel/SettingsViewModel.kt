package com.vesper.flipper.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.flipper.data.AiProvider
import com.vesper.flipper.data.ModelInfo
import com.vesper.flipper.data.OpenRouterModelCatalog
import com.vesper.flipper.data.SettingsStore
import com.vesper.flipper.domain.model.CommandAction
import com.vesper.flipper.domain.model.Permission
import com.vesper.flipper.domain.service.PermissionService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsState(
    val aiProvider: AiProvider = AiProvider.OPENROUTER,
    val apiKey: String = "",
    val openRouterApiKey: String = "",
    val anthropicApiKey: String = "",
    val openAiApiKey: String = "",
    val selectedModel: String = SettingsStore.DEFAULT_MODEL,
    val aiMaxIterations: Int = SettingsStore.DEFAULT_AI_MAX_ITERATIONS,
    val autoConnect: Boolean = true,
    val defaultProjectPath: String = "/ext/apps_data/vesper",
    val permissionDuration: Long = Permission.DURATION_15_MINUTES,
    val hapticFeedback: Boolean = true,
    val darkMode: Boolean = true,
    val autoApproveMedium: Boolean = false,
    val autoApproveHigh: Boolean = false,
    val auditRetentionDays: Int = 30,
    val activePermissions: List<Permission> = emptyList(),
    // TTS (via OpenRouter — uses same API key)
    val ttsEnabled: Boolean = false,
    val ttsVoiceId: String = SettingsStore.DEFAULT_TTS_VOICE,
    val ttsAutoSpeak: Boolean = false,
    // Smart Glasses
    val glassesEnabled: Boolean = false,
    val glassesBridgeUrl: String = "",
    val glassesAutoSend: Boolean = true,
    val glassesAutoConnect: Boolean = false,
    val glassesSailorMouth: Boolean = false,
    val glassesMuted: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val openRouterModelCatalog: OpenRouterModelCatalog,
    private val permissionService: PermissionService
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private val _availableModels = MutableStateFlow(SettingsStore.FALLBACK_MODELS)
    val availableModels: StateFlow<List<ModelInfo>> = _availableModels.asStateFlow()

    private val _isRefreshingModels = MutableStateFlow(false)
    val isRefreshingModels: StateFlow<Boolean> = _isRefreshingModels.asStateFlow()

    init {
        loadSettings()
        observePermissions()
        refreshAvailableModels()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            combine(
                settingsStore.aiProvider,
                settingsStore.apiKey,
                settingsStore.openRouterApiKey,
                settingsStore.anthropicApiKey,
                settingsStore.openAiApiKey,
                settingsStore.selectedModel,
                settingsStore.aiMaxIterations,
                settingsStore.autoConnect,
                settingsStore.defaultProjectPath
            ) { values ->
                SettingsState(
                    aiProvider = values[0] as AiProvider,
                    apiKey = (values[1] as? String) ?: "",
                    openRouterApiKey = (values[2] as? String) ?: "",
                    anthropicApiKey = (values[3] as? String) ?: "",
                    openAiApiKey = (values[4] as? String) ?: "",
                    selectedModel = values[5] as String,
                    aiMaxIterations = values[6] as Int,
                    autoConnect = values[7] as Boolean,
                    defaultProjectPath = values[8] as String
                )
            }.combine(
                combine(
                    settingsStore.permissionDuration,
                    settingsStore.hapticFeedback,
                    settingsStore.darkMode,
                    settingsStore.auditRetentionDays
                ) { values ->
                    CoreSettingsBundle(
                        permissionDuration = values[0] as Long,
                        hapticFeedback = values[1] as Boolean,
                        darkMode = values[2] as Boolean,
                        auditRetentionDays = values[3] as Int
                    )
                }
            ) { base, core ->
                base.copy(
                    permissionDuration = core.permissionDuration,
                    hapticFeedback = core.hapticFeedback,
                    darkMode = core.darkMode,
                    auditRetentionDays = core.auditRetentionDays
                )
            }.combine(
                combine(
                    settingsStore.autoApproveMedium,
                    settingsStore.autoApproveHigh,
                    settingsStore.ttsEnabled,
                    settingsStore.ttsVoiceId,
                    settingsStore.ttsAutoSpeak
                ) { values ->
                    TtsSettingsBundle(
                        autoApproveMedium = values[0] as Boolean,
                        autoApproveHigh = values[1] as Boolean,
                        ttsEnabled = values[2] as Boolean,
                        ttsVoiceId = values[3] as String,
                        ttsAutoSpeak = values[4] as Boolean
                    )
                }
            ) { base, tts ->
                base.copy(
                    autoApproveMedium = tts.autoApproveMedium,
                    autoApproveHigh = tts.autoApproveHigh,
                    ttsEnabled = tts.ttsEnabled,
                    ttsVoiceId = tts.ttsVoiceId,
                    ttsAutoSpeak = tts.ttsAutoSpeak
                )
            }.combine(
                combine(
                    settingsStore.glassesEnabled,
                    settingsStore.glassesBridgeUrl,
                    settingsStore.glassesAutoSend,
                    settingsStore.glassesAutoConnect,
                    settingsStore.glassesSailorMouth,
                    settingsStore.glassesMuted
                ) { values ->
                    GlassesSettingsBundle(
                        glassesEnabled = values[0] as Boolean,
                        glassesBridgeUrl = (values[1] as? String) ?: "",
                        glassesAutoSend = values[2] as Boolean,
                        glassesAutoConnect = values[3] as Boolean,
                        glassesSailorMouth = values[4] as Boolean,
                        glassesMuted = values[5] as Boolean
                    )
                }
            ) { base, glasses ->
                base.copy(
                    glassesEnabled = glasses.glassesEnabled,
                    glassesBridgeUrl = glasses.glassesBridgeUrl,
                    glassesAutoSend = glasses.glassesAutoSend,
                    glassesAutoConnect = glasses.glassesAutoConnect,
                    glassesSailorMouth = glasses.glassesSailorMouth,
                    glassesMuted = glasses.glassesMuted
                )
            }.collect { settings ->
                _state.update { it.copy(
                    aiProvider = settings.aiProvider,
                    apiKey = settings.apiKey,
                    openRouterApiKey = settings.openRouterApiKey,
                    anthropicApiKey = settings.anthropicApiKey,
                    openAiApiKey = settings.openAiApiKey,
                    selectedModel = settings.selectedModel,
                    aiMaxIterations = settings.aiMaxIterations,
                    autoConnect = settings.autoConnect,
                    defaultProjectPath = settings.defaultProjectPath,
                    permissionDuration = settings.permissionDuration,
                    autoApproveMedium = settings.autoApproveMedium,
                    autoApproveHigh = settings.autoApproveHigh,
                    hapticFeedback = settings.hapticFeedback,
                    darkMode = settings.darkMode,
                    auditRetentionDays = settings.auditRetentionDays,
                    ttsEnabled = settings.ttsEnabled,
                    ttsVoiceId = settings.ttsVoiceId,
                    ttsAutoSpeak = settings.ttsAutoSpeak,
                    glassesEnabled = settings.glassesEnabled,
                    glassesBridgeUrl = settings.glassesBridgeUrl,
                    glassesAutoSend = settings.glassesAutoSend,
                    glassesAutoConnect = settings.glassesAutoConnect,
                    glassesSailorMouth = settings.glassesSailorMouth,
                    glassesMuted = settings.glassesMuted
                )}
            }
        }
    }

    private fun observePermissions() {
        viewModelScope.launch {
            permissionService.activePermissions.collect { permissions ->
                _state.update { it.copy(activePermissions = permissions) }
            }
        }
    }

    fun setAiProvider(provider: AiProvider) {
        viewModelScope.launch {
            settingsStore.setAiProvider(provider)
            // When switching providers, also switch to the default model for that provider
            val defaultModel = SettingsStore.defaultModelForProvider(provider)
            settingsStore.setSelectedModel(defaultModel)
            _state.update { it.copy(aiProvider = provider, selectedModel = defaultModel) }
            // Update the available models list for the new provider
            if (provider == AiProvider.OPENROUTER) {
                refreshAvailableModels()
            } else {
                _availableModels.value = SettingsStore.modelsForProvider(provider)
            }
        }
    }

    fun setApiKey(key: String) {
        viewModelScope.launch {
            // Write to the key slot for the currently selected provider
            when (_state.value.aiProvider) {
                AiProvider.OPENROUTER -> {
                    settingsStore.setOpenRouterApiKey(key)
                    _state.update { it.copy(apiKey = key, openRouterApiKey = key) }
                }
                AiProvider.ANTHROPIC -> {
                    settingsStore.setAnthropicApiKey(key)
                    _state.update { it.copy(apiKey = key, anthropicApiKey = key) }
                }
                AiProvider.OPENAI -> {
                    settingsStore.setOpenAiApiKey(key)
                    _state.update { it.copy(apiKey = key, openAiApiKey = key) }
                }
            }
        }
    }

    fun setSelectedModel(model: String) {
        viewModelScope.launch {
            settingsStore.setSelectedModel(model)
            _state.update { it.copy(selectedModel = model) }
        }
    }

    fun setAiMaxIterations(value: Int) {
        viewModelScope.launch {
            settingsStore.setAiMaxIterations(value)
            _state.update { it.copy(aiMaxIterations = value) }
        }
    }

    fun refreshAvailableModels() {
        if (_isRefreshingModels.value) return

        val provider = _state.value.aiProvider

        // For Anthropic/OpenAI direct, we have a static list — no need to fetch
        if (provider != AiProvider.OPENROUTER) {
            _availableModels.value = SettingsStore.modelsForProvider(provider)
            return
        }

        viewModelScope.launch {
            _isRefreshingModels.value = true
            openRouterModelCatalog.fetchLatestByManufacturer()
                .onSuccess { models ->
                    val merged = (
                            models + SettingsStore.FALLBACK_MODELS.filter { it.id == SettingsStore.DEFAULT_MODEL }
                            ).distinctBy { it.id }
                    _availableModels.value = merged
                }
                .onFailure {
                    _availableModels.value = SettingsStore.FALLBACK_MODELS
                }
            _isRefreshingModels.value = false
        }
    }

    fun getModelDisplayName(modelId: String): String {
        val providerModels = SettingsStore.modelsForProvider(_state.value.aiProvider)
        val displayList = (_availableModels.value + providerModels + SettingsStore.FALLBACK_MODELS).distinctBy { it.id }
        return SettingsStore.getModelDisplayName(modelId, displayList)
    }

    fun setAutoConnect(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setAutoConnect(enabled)
            _state.update { it.copy(autoConnect = enabled) }
        }
    }

    fun setDefaultProjectPath(path: String) {
        viewModelScope.launch {
            settingsStore.setDefaultProjectPath(path)
            _state.update { it.copy(defaultProjectPath = path) }
        }
    }

    fun setPermissionDuration(durationMs: Long) {
        viewModelScope.launch {
            settingsStore.setPermissionDuration(durationMs)
            _state.update { it.copy(permissionDuration = durationMs) }
        }
    }

    fun setAutoApproveMedium(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setAutoApproveMedium(enabled)
            _state.update { it.copy(autoApproveMedium = enabled) }
        }
    }

    fun setAutoApproveHigh(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setAutoApproveHigh(enabled)
            _state.update { it.copy(autoApproveHigh = enabled) }
        }
    }

    fun setHapticFeedback(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setHapticFeedback(enabled)
            _state.update { it.copy(hapticFeedback = enabled) }
        }
    }

    fun setDarkMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setDarkMode(enabled)
            _state.update { it.copy(darkMode = enabled) }
        }
    }

    fun setAuditRetentionDays(days: Int) {
        viewModelScope.launch {
            settingsStore.setAuditRetentionDays(days)
            _state.update { it.copy(auditRetentionDays = days) }
        }
    }

    fun revokePermission(permissionId: String) {
        permissionService.revokePermission(permissionId)
    }

    fun revokeAllPermissions() {
        permissionService.revokeAll()
    }

    fun grantProjectPermission() {
        val path = _state.value.defaultProjectPath
        permissionService.grantProjectScope(path)
    }

    // TTS settings
    fun setTtsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setTtsEnabled(enabled)
            _state.update { it.copy(ttsEnabled = enabled) }
        }
    }

    fun setTtsVoiceId(voiceId: String) {
        viewModelScope.launch {
            settingsStore.setTtsVoiceId(voiceId)
            _state.update { it.copy(ttsVoiceId = voiceId) }
        }
    }

    fun setTtsAutoSpeak(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setTtsAutoSpeak(enabled)
            _state.update { it.copy(ttsAutoSpeak = enabled) }
        }
    }

    // Smart Glasses settings
    fun setGlassesEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setGlassesEnabled(enabled)
            _state.update { it.copy(glassesEnabled = enabled) }
        }
    }

    fun setGlassesBridgeUrl(url: String) {
        viewModelScope.launch {
            settingsStore.setGlassesBridgeUrl(url.ifBlank { null })
            _state.update { it.copy(glassesBridgeUrl = url) }
        }
    }

    fun setGlassesAutoSend(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setGlassesAutoSend(enabled)
            _state.update { it.copy(glassesAutoSend = enabled) }
        }
    }

    fun setGlassesAutoConnect(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setGlassesAutoConnect(enabled)
            _state.update { it.copy(glassesAutoConnect = enabled) }
        }
    }

    fun setGlassesSailorMouth(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setGlassesSailorMouth(enabled)
            _state.update { it.copy(glassesSailorMouth = enabled) }
        }
    }

    fun setGlassesMuted(muted: Boolean) {
        viewModelScope.launch {
            settingsStore.setGlassesMuted(muted)
            _state.update { it.copy(glassesMuted = muted) }
        }
    }
}

private data class CoreSettingsBundle(
    val permissionDuration: Long,
    val hapticFeedback: Boolean,
    val darkMode: Boolean,
    val auditRetentionDays: Int
)

private data class TtsSettingsBundle(
    val autoApproveMedium: Boolean,
    val autoApproveHigh: Boolean,
    val ttsEnabled: Boolean,
    val ttsVoiceId: String,
    val ttsAutoSpeak: Boolean
)

private data class GlassesSettingsBundle(
    val glassesEnabled: Boolean,
    val glassesBridgeUrl: String,
    val glassesAutoSend: Boolean,
    val glassesAutoConnect: Boolean,
    val glassesSailorMouth: Boolean,
    val glassesMuted: Boolean
)
