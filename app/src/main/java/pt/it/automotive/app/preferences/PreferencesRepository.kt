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
    val error: PreferencesSyncError?,
    /** True only after a successful GET from the backend — used to gate recreate() in the UI. */
    val loadedFromBackend: Boolean = false
)

class PreferencesRepository(
    private val api: PreferencesApi,
    private val localStore: PreferencesLocalStore,
    private val tokenProvider: () -> String?,
    private val userIdProvider: () -> String?,
    private val onAuthInvalid: () -> Unit = {},
    private val maxRetryAttempts: Int = 2,
    private val retryDelayMs: Long = 2_000L,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    private var pendingSectionType: PreferencesSectionType? = null
    private val dirtySections = mutableSetOf<PreferencesSectionType>()

    private val _state = MutableStateFlow(
        // Start with defaults and isLoading=true so the UI never renders stale data
        // from a previous user session while awaiting the backend response.
        PreferencesSyncState(
            preferences = PreferencesDefaults.create(),
            isLoading = true,
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
            flushDirtySectionsInternal()
        }
    }

    fun stagePreferencesUpdate(section: PreferencesSectionUpdate) {
        scope.launch {
            stagePreferencesInternal(section)
        }
    }

    fun flushDirtySections() {
        scope.launch {
            flushDirtySectionsInternal()
        }
    }

    suspend fun flushDirtySectionsAwait(): Boolean {
        return flushDirtySectionsInternal()
    }

    fun retryPendingUpdate() {
        if (dirtySections.isEmpty()) return
        scope.launch {
            flushDirtySectionsInternal()
        }
    }

    fun clear() {
        scope.cancel()
    }

    /**
     * Clears all locally cached preferences (snapshot + UI SharedPreferences) and resets
     * the in-memory state to defaults. Call this on logout so that the next user
     * starts with a clean slate instead of inheriting the previous user's data.
     */
    fun clearLocalData() {
        localStore.clearSnapshot()
        _state.value = PreferencesSyncState(
            preferences = PreferencesDefaults.create(),
            isLoading = false,
            isSyncing = false,
            hasPendingRetry = false,
            error = null
        )
        pendingSectionType = null
        dirtySections.clear()
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

        // Only surface a local snapshot to the UI if it belongs to the current user.
        // This prevents showing stale data from a previous user if clearLocalData()
        // was not called (e.g., cold start after an abnormal termination).
        val currentUserId = userIdProvider()
        if (currentUserId != null) {
            val ownedSnapshot = localStore.readSnapshotForUser(currentUserId)
            if (ownedSnapshot != null) {
                _state.value = _state.value.copy(preferences = ownedSnapshot)
            }
        }

        when (val result = api.getPreferences(token)) {
            is PreferencesApiResult.Success -> {
                val applied = localStore.applyToUiPreferences(result.preferences)
                localStore.saveSnapshot(result.preferences)

                _state.value = _state.value.copy(
                    preferences = result.preferences,
                    isLoading = false,
                    error = null,
                    hasPendingRetry = false,
                    loadedFromBackend = true
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

    internal suspend fun flushDirtySectionsInternal(): Boolean {
        val sectionsToFlush = dirtySections.toSet()
        if (sectionsToFlush.isEmpty()) {
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

        val payload = PreferencesPatchFactory.buildPatchPayload(currentPreferences, sectionsToFlush)

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
                sectionsToFlush.forEach { dirtySections.remove(it) }
                
                // If there are still dirty sections that were added DURING the network call,
                // we must preserve them so they aren't overwritten by the backend's response.
                var finalPreferences = result.preferences
                if (dirtySections.contains(PreferencesSectionType.APPEARANCE)) {
                    finalPreferences = finalPreferences.copy(appearance = _state.value.preferences.appearance)
                }
                if (dirtySections.contains(PreferencesSectionType.ALERTS)) {
                    finalPreferences = finalPreferences.copy(alerts = _state.value.preferences.alerts)
                }
                if (dirtySections.contains(PreferencesSectionType.WEATHER)) {
                    finalPreferences = finalPreferences.copy(weather = _state.value.preferences.weather)
                }

                localStore.applyToUiPreferences(finalPreferences)
                localStore.saveSnapshot(finalPreferences)
                _state.value = _state.value.copy(
                    preferences = finalPreferences,
                    isSyncing = false,
                    hasPendingRetry = false,
                    error = null,
                    loadedFromBackend = true
                )
                return true
            }

            is PreferencesApiResult.Unauthorized -> {
                onUnauthorized()
                return false
            }

            is PreferencesApiResult.ValidationError -> {
                pendingSectionType = null
                sectionsToFlush.forEach { dirtySections.remove(it) }
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
                pendingSectionType = sectionsToFlush.first() // kept for compatibility, though dirtySections has everything
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
                pendingSectionType = sectionsToFlush.first()
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
                userIdProvider = { TokenStore.getUserId(context) },
                onAuthInvalid = onAuthInvalid
            )
        }
    }
}
