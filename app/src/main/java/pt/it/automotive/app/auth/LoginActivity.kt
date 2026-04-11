package pt.it.automotive.app.auth

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import pt.it.automotive.app.MainActivity
import pt.it.automotive.app.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var qrImageView: ImageView
    private lateinit var userCodeText: TextView
    private lateinit var verificationUriText: TextView
    private lateinit var statusText: TextView
    private lateinit var rememberMeCheckbox: CheckBox

    private var pollingJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If no remember me, clear any stored tokens so the user must log in again on each app launch
        TokenStore.clearIfNotRememberMe(this)

        // If we already have a valid token, skip login entirely
        if (TokenStore.isAccessTokenValid(this)) {
            launchMain()
            return
        }

        setContentView(R.layout.activity_login)

        qrImageView         = findViewById(R.id.qrCode)
        userCodeText        = findViewById(R.id.userCode)
        verificationUriText = findViewById(R.id.verificationUri)
        statusText          = findViewById(R.id.statusText)
        rememberMeCheckbox  = findViewById(R.id.rememberMe)

        startDeviceFlow()
    }

    private fun startDeviceFlow() {
        pollingJob?.cancel()
        lifecycleScope.launch {
            statusText.text = "Connecting to auth server…"
            try {
                val deviceCode = KeycloakClient.requestDeviceCode(rememberMeCheckbox.isChecked)

                // Show QR code
                showQrCode(deviceCode.verificationUriComplete)

                // Show manual fallback
                verificationUriText.text = deviceCode.verificationUri
                userCodeText.text = deviceCode.userCode

                statusText.text = "Waiting for login on your phone…"

                startPolling(deviceCode.deviceCode, deviceCode.interval)

            } catch (e: Exception) {
                statusText.text = "Could not reach auth server. Check network connection."
            }
        }
    }

    private fun showQrCode(uri: String) {
        val size = 512
        val bits = QRCodeWriter().encode(uri, BarcodeFormat.QR_CODE, size, size)
        val bmp  = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size)
            for (y in 0 until size)
                bmp.setPixel(x, y, if (bits[x, y]) Color.BLACK else Color.WHITE)
        qrImageView.setImageBitmap(bmp)
    }

    private fun startPolling(deviceCode: String, intervalSeconds: Int) {
        pollingJob = lifecycleScope.launch {
            var interval = intervalSeconds.toLong()
            while (isActive) {
                delay(interval * 1000)
                when (val result = KeycloakClient.pollForToken(deviceCode)) {
                    is KeycloakClient.PollResult.Success -> {
                        TokenStore.save(
                            this@LoginActivity,
                            result.tokens.accessToken,
                            result.tokens.refreshToken,
                            result.tokens.expiresIn,
                            rememberMe = rememberMeCheckbox.isChecked
                        )
                        launchMain()
                        return@launch
                    }
                    is KeycloakClient.PollResult.Pending  ->
                        statusText.text = "Waiting for login on your phone…"
                    is KeycloakClient.PollResult.SlowDown -> {
                        interval += 5
                        statusText.text = "Waiting…"
                    }
                    is KeycloakClient.PollResult.Expired  -> {
                        statusText.text = "Code expired. Restarting…"
                        delay(1500)
                        startDeviceFlow()
                        return@launch
                    }
                    is KeycloakClient.PollResult.Error    ->
                        statusText.text = "Error: ${result.message}"
                }
            }
        }
    }

    private fun launchMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish() // remove LoginActivity from back stack so user can't go back to it
    }

    override fun onDestroy() {
        super.onDestroy()
        pollingJob?.cancel()
    }
}
