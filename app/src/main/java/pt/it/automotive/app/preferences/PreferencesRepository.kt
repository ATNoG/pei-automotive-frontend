package pt.it.automotive.app.preferences

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pt.it.automotive.app.auth.TokenStore

private const val TAG = "PreferencesRepository"

sealed interface PreferencesSyncError {
    val userMessage: String

    data object SessionExpired : PreferencesSyncError {
        override val userMessage: String = "Sessao expirada. Inicie sessao novamente."
    }

    data class Validation(override val userMessage: String, val technicalMessage: String) : PreferencesSyncError
    data class Network(override val userMessage: String, val technicalMessage: String, val timeout: Boolean) : PreferencesSyncError
    data class Unexpected(override val userMessage: String, val technicalMessage: String) : PreferencesSyncError
}

data class PreferencesSyncState(
    val preferences: UserPreferences,
    val isLoading: Boolean,
    val isSyncing: Boolean,
    val hasPendingRetry: Boolean,
    val error: PreferencesSyncError?
)

class PreferencesRepository(
    private val api: PreferencesApi,
    private val localStore: PreferencesLocalStore,
    private val tokenProvider: () -> String?,
    private val onAuthInvalid: () -> Unit = {},
    private val maxRetryAttempts: Int = 2,
    private val retryDelayMs: Long = 2_000L,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    private var pendingSectionType: PreferencesSectionType? = null
    private val dirtySections = mutableSetOf<PreferencesSectionType>()

    private val _state = MutableStateFlow(
        PreferencesSyncState(
            preferences = localStore.readSnapshot() ?: localStore.readUiPreferences(),
            isLoading = false,
            isSyncing = false,
            hasPendingRetry = false,
            error = null
        )
    )
    val state: StateFlow<PreferencesSyncState> = _state.asStateFlow()

    fun loadPreferences() {
        scope.launch {
            loadPreferencesInternal()
        }
    }

    fun updatePreferences(section: PreferencesSectionUpdate) {
        scope.launch {
            stagePreferencesInternal(section)
            flushSectionInternal(section.sectionType)
        }
    }

    fun stagePreferencesUpdate(section: PreferencesSectionUpdate) {
        scope.launch {
            stagePreferencesInternal(section)
        }
    }

    fun flushSection(sectionType: PreferencesSectionType) {
        scope.launch {
            flushSectionInternal(sectionType)
        }
    }

    suspend fun flushSectionAwait(sectionType: PreferencesSectionType): Boolean {
        return flushSectionInternal(sectionType)
    }

    fun retryPendingUpdate() {
        val pending = pendingSectionType ?: return
        scope.launch {
            flushSectionInternal(pending)
        }
    }

    fun clear() {
        scope.cancel()
    }

    internal suspend fun loadPreferencesInternal() {
        val token = tokenProvider()
        if (token.isNullOrBlank()) {
            onUnauthorized()
            return
        }

        _state.value = _state.value.copy(
            isLoading = true,
            error = null
        )

        val localSnapshot = localStore.readSnapshot()
        if (localSnapshot != null) {
            _state.value = _state.value.copy(preferences = localSnapshot)
        }

        when (val result = api.getPreferences(token)) {
            is PreferencesApiResult.Success -> {
                val applied = localStore.applyToUiPreferences(result.preferences)
                localStore.saveSnapshot(result.preferences)

                _state.value = _state.value.copy(
                    preferences = result.preferences,
                    isLoading = false,
                    error = null,
                    hasPendingRetry = false
                )

                if (applied.appearanceChanged) {
                    safeLogInfo("Appearance changed from backend snapshot")
                }
            }

            is PreferencesApiResult.Unauthorized -> onUnauthorized()

            is PreferencesApiResult.ValidationError -> {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = PreferencesSyncError.Validation(
                        userMessage = "Nao foi possivel validar as preferencias no servidor.",
                        technicalMessage = result.technicalMessage
                    )
                )
                safeLogWarn("Validation error while loading preferences: ${result.technicalMessage}")
            }

            is PreferencesApiResult.NetworkError -> {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = PreferencesSyncError.Network(
                        userMessage = "Sem ligacao. A usar preferencias em cache.",
                        technicalMessage = result.technicalMessage,
                        timeout = result.timeout
                    )
                )
                safeLogWarn("Network fallback while loading preferences: ${result.technicalMessage}")
            }

            is PreferencesApiResult.HttpError -> {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = PreferencesSyncError.Unexpected(
                        userMessage = "Erro ao sincronizar preferencias.",
                        technicalMessage = "HTTP ${result.code}: ${result.technicalMessage}"
                    )
                )
                safeLogError("HTTP error while loading preferences: ${result.code} ${result.technicalMessage}")
            }
        }
    }

    internal suspend fun stagePreferencesInternal(section: PreferencesSectionUpdate) {
        val base = _state.value.preferences
        val merged = PreferencesPatchFactory.merge(base, section)

        localStore.applyToUiPreferences(merged)
        localStore.saveSnapshot(merged)

        dirtySections.add(section.sectionType)

        _state.value = _state.value.copy(
            preferences = merged,
            error = null
        )
    }

    internal suspend fun flushSectionInternal(sectionType: PreferencesSectionType): Boolean {
        if (!dirtySections.contains(sectionType) && pendingSectionType != sectionType) {
            return true
        }

        val token = tokenProvider()
        if (token.isNullOrBlank()) {
            onUnauthorized()
            return false
        }

        val currentPreferences = _state.value.preferences

        _state.value = _state.value.copy(
            isSyncing = true,
            error = null
        )

        val payload = PreferencesPatchFactory.buildPatchPayload(currentPreferences, sectionType)

        var result: PreferencesApiResult = api.patchPreferences(token, payload)
        var attempts = 0
        while (result is PreferencesApiResult.NetworkError && attempts < maxRetryAttempts) {
            attempts += 1
            delay(retryDelayMs * attempts)
            result = api.patchPreferences(token, payload)
        }

        when (result) {
            is PreferencesApiResult.Success -> {
                pendingSectionType = null
                dirtySections.remove(sectionType)
                localStore.applyToUiPreferences(result.preferences)
                localStore.saveSnapshot(result.preferences)
                _state.value = _state.value.copy(
                    preferences = result.preferences,
                    isSyncing = false,
                    hasPendingRetry = false,
                    error = null
                )
                return true
            }

            is PreferencesApiResult.Unauthorized -> {
                onUnauthorized()
                return false
            }

            is PreferencesApiResult.ValidationError -> {
                pendingSectionType = null
                dirtySections.remove(sectionType)
                _state.value = _state.value.copy(
                    isSyncing = false,
                    error = PreferencesSyncError.Validation(
                        userMessage = "Configuracao invalida. Verifique os dados e tente novamente.",
                        technicalMessage = result.technicalMessage
                    )
                )
                safeLogWarn("Validation error while patching preferences: ${result.technicalMessage}")
                return false
            }

            is PreferencesApiResult.NetworkError -> {
                pendingSectionType = sectionType
                _state.value = _state.value.copy(
                    isSyncing = false,
                    hasPendingRetry = true,
                    error = PreferencesSyncError.Network(
                        userMessage = "Sem ligacao. Alteracoes guardadas localmente e sera feito novo retry.",
                        technicalMessage = result.technicalMessage,
                        timeout = result.timeout
                    )
                )
                safeLogWarn("Network error while patching preferences: ${result.technicalMessage}")
                return false
            }

            is PreferencesApiResult.HttpError -> {
                pendingSectionType = sectionType
                _state.value = _state.value.copy(
                    isSyncing = false,
                    hasPendingRetry = true,
                    error = PreferencesSyncError.Unexpected(
                        userMessage = "Falha ao atualizar preferencias no servidor.",
                        technicalMessage = "HTTP ${result.code}: ${result.technicalMessage}"
                    )
                )
                safeLogError("HTTP error while patching preferences: ${result.code} ${result.technicalMessage}")
                return false
            }
        }
    }

    private fun safeLogInfo(message: String) {
        try {
            Log.i(TAG, message)
        } catch (_: RuntimeException) {
            // android.util.Log may be unavailable in plain JVM tests.
        }
    }

    private fun safeLogWarn(message: String) {
        try {
            Log.w(TAG, message)
        } catch (_: RuntimeException) {
            // android.util.Log may be unavailable in plain JVM tests.
        }
    }

    private fun safeLogError(message: String) {
        try {
            Log.e(TAG, message)
        } catch (_: RuntimeException) {
            // android.util.Log may be unavailable in plain JVM tests.
        }
    }

    private fun onUnauthorized() {
        _state.value = _state.value.copy(
            isLoading = false,
            isSyncing = false,
            error = PreferencesSyncError.SessionExpired,
            hasPendingRetry = false
        )
        onAuthInvalid()
    }

    companion object {
        fun create(
            context: Context,
            onAuthInvalid: () -> Unit = {}
        ): PreferencesRepository {
            return PreferencesRepository(
                api = PreferencesApiService(),
                localStore = PreferencesLocalDataSource(context),
                tokenProvider = { TokenStore.getAccessToken(context) },
                onAuthInvalid = onAuthInvalid
            )
        }
    }
}
