package com.example.myapplication.auth

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(private val context: Context) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val requestBuilder = originalRequest.newBuilder()

        // 1. Attach the current access token
        TokenStore.getAccessToken(context)?.let { token ->
            requestBuilder.addHeader("Authorization", "Bearer $token")
        }

        var response = chain.proceed(requestBuilder.build())

        // 2. If token expired, try to refresh it
        if (response.code == 401) {
            val refreshToken = TokenStore.getRefreshToken(context)
            if (refreshToken != null) {
                // Synchronously refresh token so we block this request thread
                val newTokens = runBlocking { KeycloakClient.refreshToken(refreshToken) }

                if (newTokens != null) {
                    // Save new tokens
                    TokenStore.save(
                        context, 
                        newTokens.accessToken, 
                        newTokens.refreshToken, 
                        newTokens.expiresIn
                    )

                    // Close the failed response and retry the request
                    response.close()
                    val newRequest = originalRequest.newBuilder()
                        .addHeader("Authorization", "Bearer ${newTokens.accessToken}")
                        .build()
                    response = chain.proceed(newRequest)
                } else {
                    // Refresh token is also expired or invalid. Kick user out.
                    TokenStore.clear(context)
                    kickToLogin()
                }
            } else {
                TokenStore.clear(context)
                kickToLogin()
            }
        }
        return response
    }

    private fun kickToLogin() {
        val intent = Intent(context, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        context.startActivity(intent)
    }
}