package pt.it.automotive.app.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import pt.it.automotive.app.BuildConfig

object KeycloakClient {

    // Replace with your actual server IP and realm name
    private val BASE_URL  = "${BuildConfig.KEYCLOAK_BASE_URL}/realms/automotive-app/protocol/openid-connect"
    private const val CLIENT_ID = "automotive-app"

    private val http = OkHttpClient()

    // ── Data classes ──────────────────────────────────────────────────────────

    data class DeviceCodeResponse(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val verificationUriComplete: String,
        val expiresIn: Int,
        val interval: Int
    )

    data class TokenResponse(
        val accessToken: String,
        val refreshToken: String,
        val expiresIn: Int
    )

    sealed class PollResult {
        data class Success(val tokens: TokenResponse) : PollResult()
        object Pending   : PollResult()
        object SlowDown  : PollResult()
        object Expired   : PollResult()
        data class Error(val message: String) : PollResult()
    }

    // ── API calls ─────────────────────────────────────────────────────────────

    suspend fun requestDeviceCode(): DeviceCodeResponse = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("scope", "openid car_id")
            .build()
        val response = http.newCall(
            Request.Builder().url("$BASE_URL/auth/device").post(body).build()
        ).execute()
        val json = JSONObject(response.body!!.string())
        DeviceCodeResponse(
            deviceCode             = json.getString("device_code"),
            userCode               = json.getString("user_code"),
            verificationUri        = json.getString("verification_uri"),
            verificationUriComplete = json.getString("verification_uri_complete"),
            expiresIn              = json.getInt("expires_in"),
            interval               = json.getInt("interval")
        )
    }

    suspend fun pollForToken(deviceCode: String): PollResult = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("device_code", deviceCode)
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .build()
        val response = http.newCall(
            Request.Builder().url("$BASE_URL/token").post(body).build()
        ).execute()
        val json = JSONObject(response.body!!.string())
        when {
            response.isSuccessful ->
                PollResult.Success(TokenResponse(
                    accessToken  = json.getString("access_token"),
                    refreshToken = json.getString("refresh_token"),
                    expiresIn    = json.getInt("expires_in")
                ))
            json.optString("error") == "authorization_pending" -> PollResult.Pending
            json.optString("error") == "slow_down"             -> PollResult.SlowDown
            json.optString("error") == "expired_token"         -> PollResult.Expired
            else -> PollResult.Error(json.optString("error_description", "Unknown error"))
        }
    }

    suspend fun refreshToken(refreshToken: String): TokenResponse? = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .build()
        try {
            val response = http.newCall(
                Request.Builder().url("$BASE_URL/token").post(body).build()
            ).execute()
            if (!response.isSuccessful) return@withContext null
            val json = JSONObject(response.body!!.string())
            TokenResponse(
                accessToken  = json.getString("access_token"),
                refreshToken = json.getString("refresh_token"),
                expiresIn    = json.getInt("expires_in")
            )
        } catch (e: Exception) {
            null
        }
    }
}