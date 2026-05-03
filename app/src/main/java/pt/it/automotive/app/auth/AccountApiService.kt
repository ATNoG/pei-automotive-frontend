package pt.it.automotive.app.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import pt.it.automotive.app.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed interface AccountApiResult {
    data object Success : AccountApiResult
    data class Error(val message: String) : AccountApiResult
}

class AccountApiService(
    private val baseUrl: String = BuildConfig.PREFERENCES_BASE_URL,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()
) {

    suspend fun deleteAccount(accessToken: String): AccountApiResult {
        return withContext(Dispatchers.IO) {
            val url = "$baseUrl/api/users/me"
            val request = Request.Builder()
                .url(url)
                .delete()
                .header("Authorization", "Bearer $accessToken")
                .build()

            try {
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful || response.code == 204) {
                    AccountApiResult.Success
                } else {
                    AccountApiResult.Error("HTTP Error: ${response.code} ${response.message}")
                }
            } catch (e: IOException) {
                AccountApiResult.Error("Network error: ${e.message}")
            } catch (e: Exception) {
                AccountApiResult.Error("Unknown error: ${e.message}")
            }
        }
    }
}