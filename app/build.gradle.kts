import java.util.Properties

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
        buildConfigField("String", "MAPTILER_API_KEY", "\"${localProperties.getProperty("MAPTILER_API_KEY", "")}\"")
        buildConfigField("String", "MQTT_BROKER_ADDRESS", "\"${localProperties.getProperty("MQTT_BROKER_ADDRESS", "192.168.50.89")}\"")
        buildConfigField("String", "MQTT_BROKER_PORT", "\"${localProperties.getProperty("MQTT_BROKER_PORT", "1884")}\"")
        buildConfigField("String", "OPENWEATHER_API_KEY", "\"${localProperties.getProperty("OPENWEATHER_API_KEY", "")}\"")
        buildConfigField("String", "OPENROUTESERVICE_API_KEY", "\"${localProperties.getProperty("OPENROUTESERVICE_API_KEY", "")}\"")
        buildConfigField("String", "KEYCLOAK_BASE_URL", "\"${localProperties.getProperty("KEYCLOAK_BASE_URL", "http://localhost:8080")}\"")
    }

    buildTypes {
        debug {
            // Connects to services running on the dev machine via Android emulator's host alias.
            // Docker containers on host:1884 and host:8081 are reachable at 10.0.2.2 from the emulator.
            buildConfigField("String", "MQTT_BROKER_ADDRESS", "\"10.0.2.2\"")
            buildConfigField("String", "KEYCLOAK_BASE_URL", "\"http://10.0.2.2:8081\"")
        }
        release {
            // Uses VM addresses from local.properties (staging environment).
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        buildConfig = true
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
