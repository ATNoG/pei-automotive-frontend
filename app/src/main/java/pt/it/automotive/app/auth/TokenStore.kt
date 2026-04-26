package pt.it.automotive.app.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

object TokenStore {

    private const val PREFS_NAME    = "auth_tokens"
    private const val KEY_ACCESS    = "access_token"
    private const val KEY_REFRESH   = "refresh_token"
    private const val KEY_EXPIRY    = "token_expiry_ms"
    private const val KEY_REMEMBER  = "remember_me"

    private fun prefs(ctx: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            ctx,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun save(ctx: Context, accessToken: String, refreshToken: String, expiresInSeconds: Int, rememberMe: Boolean = isRememberMe(ctx)) {
        // Subtract 60s so we refresh before the token actually expires
        val expiryMs = System.currentTimeMillis() + (expiresInSeconds * 1000L) - 60_000
        prefs(ctx).edit()
            .putString(KEY_ACCESS, accessToken)
            .putString(KEY_REFRESH, refreshToken)
            .putLong(KEY_EXPIRY, expiryMs)
            .putBoolean(KEY_REMEMBER, rememberMe)
            .apply()
    }

    fun isRememberMe(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_REMEMBER, false)

    /** Call on app start: if the user didn't choose remember me, wipe tokens so they re-login. */
    fun clearIfNotRememberMe(ctx: Context) {
        if (!isRememberMe(ctx)) clear(ctx)
    }

    fun getAccessToken(ctx: Context): String? =
        prefs(ctx).getString(KEY_ACCESS, null)

    fun getRefreshToken(ctx: Context): String? =
        prefs(ctx).getString(KEY_REFRESH, null)

    fun isAccessTokenValid(ctx: Context): Boolean {
        val expiry = prefs(ctx).getLong(KEY_EXPIRY, 0L)
        return System.currentTimeMillis() < expiry
    }

    fun clear(ctx: Context) =
        prefs(ctx).edit().clear().apply()

    /**
     * Decodes the JWT payload locally (no signature check needed —
     * Keycloak already signed it; we just want the car_id claim).
     */
    fun getCarId(ctx: Context): String? {
        val token = getAccessToken(ctx) ?: return null
        return try {
            val payloadB64 = token.split(".")[1]
            val decoded = String(Base64.decode(payloadB64, Base64.URL_SAFE or Base64.NO_PADDING))
            JSONObject(decoded).optString("car_id", null)
        } catch (e: Exception) {
            null
        }
    }

    fun getFullName(ctx: Context): String? {
        val token = getAccessToken(ctx) ?: return null
        return try {
            val payloadB64 = token.split(".")[1]
            val decoded = String(Base64.decode(payloadB64, Base64.URL_SAFE or Base64.NO_PADDING))
            val json = JSONObject(decoded)
            json.optString("name", null)?.takeIf { it.isNotBlank() }
                ?: listOfNotNull(
                    json.optString("given_name", null)?.takeIf { it.isNotBlank() },
                    json.optString("family_name", null)?.takeIf { it.isNotBlank() }
                ).joinToString(" ").takeIf { it.isNotBlank() }
                ?: json.optString("preferred_username", null)?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    fun getUsername(ctx: Context): String? {
        val token = getAccessToken(ctx) ?: return null
        return try {
            val payloadB64 = token.split(".")[1]
            val decoded = String(Base64.decode(payloadB64, Base64.URL_SAFE or Base64.NO_PADDING))
            JSONObject(decoded).optString("preferred_username", null)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Returns the Keycloak user UUID (the "sub" claim) from the access token.
     * This is the same identifier used as [user_id] in the backend database,
     * so it can be used to validate that a cached preferences snapshot belongs
     * to the currently authenticated user.
     */
    fun getUserId(ctx: Context): String? {
        val token = getAccessToken(ctx) ?: return null
        return try {
            val payloadB64 = token.split(".")[1]
            val decoded = String(Base64.decode(payloadB64, Base64.URL_SAFE or Base64.NO_PADDING))
            JSONObject(decoded).optString("sub", null)?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }
}