import java.util.Properties
import java.net.NetworkInterface

fun getLocalLanIp(): String {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        var fallbackIp: String? = null
        
        for (networkInterface in interfaces) {
            if (networkInterface.isUp && !networkInterface.isLoopback) {
                val addresses = networkInterface.inetAddresses
                for (address in addresses) {
                    if (!address.isLoopbackAddress && address.hostAddress.indexOf(':') < 0) {
                        val ip = address.hostAddress
                        val interfaceName = networkInterface.name.lowercase()
                        // Prioritize Wi-Fi adapters
                        if (interfaceName.contains("wlan") || 
                            interfaceName.contains("wifi") ||
                            interfaceName.contains("wireless")) {
                            println("DEBUG: getLocalLanIp() found Wi-Fi IP: $ip (${networkInterface.name})")
                            return ip
                        }
                        // Keep first non-loopback as fallback
                        if (fallbackIp == null) {
                            fallbackIp = ip
                            println("DEBUG: getLocalLanIp() fallback candidate: $ip (${networkInterface.name})")
                        }
                    }
                }
            }
        }
        
        // Return fallback if no Wi-Fi found
        if (fallbackIp != null) {
            println("DEBUG: getLocalLanIp() using fallback: $fallbackIp")
            return fallbackIp
        }
    } catch (e: Exception) {
        println("DEBUG: getLocalLanIp() exception: ${e.message}")
    }
    println("DEBUG: getLocalLanIp() using hardcoded fallback: 10.0.2.2")
    return "10.0.2.2" // Fallback
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Load local.properties
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}

android {
    namespace = "pt.it.automotive.app"
    compileSdk = 36

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "pt.it.automotive.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Read from local.properties and expose to BuildConfig
        //// maptiller api
        buildConfigField("String", "MAPTILER_API_KEY", "\"${localProperties.getProperty("MAPTILER_API_KEY", "")}\"")
        //// openweather api
        buildConfigField("String", "OPENWEATHER_API_KEY", "\"${localProperties.getProperty("OPENWEATHER_API_KEY", "")}\"")
        //// openrouter api (like wtf is this but ok)
        buildConfigField("String", "OPENROUTESERVICE_API_KEY", "\"${localProperties.getProperty("OPENROUTESERVICE_API_KEY", "")}\"")

        buildConfigField("String", "MQTT_BROKER_PORT", "\"${localProperties.getProperty("MQTT_BROKER_PORT", "1884")}\"")

        buildConfigField("String", "PC_LAN_IP", "\"${getLocalLanIp()}\"")
    }

    buildTypes {
        debug {
            // Local emulator: 10.0.2.2 is the host machine alias inside the Android emulator.
            // Run the backend with `docker compose up` and this APK connects automatically.
            buildConfigField("String", "MQTT_BROKER_ADDRESS", "\"${getLocalLanIp()}\"")
            buildConfigField("String", "KEYCLOAK_BASE_URL", "\"http://${getLocalLanIp()}:8081\"")
        }
        create("staging") {
            // Staging VM. Signed with the debug keystore so it can be installed directly from
            // Android Studio; the CI workflow also produces this APK for distribution.
            buildConfigField("String", "MQTT_BROKER_ADDRESS", "\"10.255.28.243\"")
            buildConfigField("String", "KEYCLOAK_BASE_URL", "\"http://10.255.28.243:8081\"")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // MapLibre SDK
    implementation("org.maplibre.gl:android-sdk:11.8.5")


    implementation(libs.play.services.location)

    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    implementation("org.json:json:20231013")

    // QR code generation
    implementation("com.google.zxing:core:3.5.3")

    // Encrypted storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // OkHttp for HTTP calls to Keycloak
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
