package pt.it.automotive.app.preferences

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import pt.it.automotive.app.BuildConfig
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

sealed interface PreferencesApiResult {
    data class Success(val preferences: UserPreferences) : PreferencesApiResult
    data object Unauthorized : PreferencesApiResult
    data class ValidationError(val technicalMessage: String) : PreferencesApiResult
    data class NetworkError(val technicalMessage: String, val timeout: Boolean) : PreferencesApiResult
    data class HttpError(val code: Int, val technicalMessage: String) : PreferencesApiResult
}

interface PreferencesApi {
    suspend fun getPreferences(accessToken: String): PreferencesApiResult
    suspend fun patchPreferences(accessToken: String, payload: PreferencesPatchPayloadDto): PreferencesApiResult
}

class PreferencesApiService(
    private val baseUrl: String = BuildConfig.PREFERENCES_BASE_URL,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()
) : PreferencesApi {

    private companion object {
        const val PREFERENCES_PATH = "/api/preferences/"
    }

    override suspend fun getPreferences(accessToken: String): PreferencesApiResult {
        return withContext(Dispatchers.IO) {
            executeRequest(accessToken = accessToken, patchPayload = null)
        }
    }

    override suspend fun patchPreferences(
        accessToken: String,
        payload: PreferencesPatchPayloadDto
    ): PreferencesApiResult {
        return withContext(Dispatchers.IO) {
            executeRequest(accessToken = accessToken, patchPayload = payload)
        }
    }

    private fun executeRequest(
        accessToken: String,
        patchPayload: PreferencesPatchPayloadDto?
    ): PreferencesApiResult {
        return try {
            val requestBuilder = Request.Builder()
                .url(buildUrl())
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Accept", "application/json")

            val request = if (patchPayload == null) {
                requestBuilder.get().build()
            } else {
                val payloadJson = PreferencesJsonMapper.serializePatchPayload(patchPayload)
                val body = payloadJson.toRequestBody("application/json; charset=utf-8".toMediaType())
                requestBuilder.patch(body).build()
            }

            httpClient.newCall(request).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> {
                        val parsed = PreferencesJsonMapper.parsePreferencesOrNull(bodyText)
                        if (parsed != null) {
                            PreferencesApiResult.Success(parsed)
                        } else {
                            PreferencesApiResult.HttpError(
                                code = response.code,
                                technicalMessage = "Unable to parse preferences response"
                            )
                        }
                    }

                    response.code == 401 -> PreferencesApiResult.Unauthorized

                    response.code == 422 -> PreferencesApiResult.ValidationError(
                        technicalMessage = bodyText.ifBlank { "Invalid preferences payload" }
                    )

                    else -> PreferencesApiResult.HttpError(
                        code = response.code,
                        technicalMessage = bodyText.ifBlank { "HTTP ${response.code}" }
                    )
                }
            }
        } catch (timeout: SocketTimeoutException) {
            PreferencesApiResult.NetworkError(
                technicalMessage = timeout.message ?: "Request timeout",
                timeout = true
            )
        } catch (network: IOException) {
            PreferencesApiResult.NetworkError(
                technicalMessage = network.message ?: "Network error",
                timeout = false
            )
        } catch (e: Exception) {
            PreferencesApiResult.HttpError(
                code = -1,
                technicalMessage = e.message ?: "Unexpected preferences API error"
            )
        }
    }

    private fun buildUrl(): String {
        val normalizedBase = baseUrl.trimEnd('/')
        return "$normalizedBase$PREFERENCES_PATH"
    }
}
