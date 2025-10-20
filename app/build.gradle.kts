plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    //alias(libs.plugins.google.android.libraries.maps platform.secrets.gradle.plugin)
}

android {
    namespace = "com.example.test_app_2"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.test_app_2"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    //implementation(libs.play.services.maps)
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("org.osmdroid:osmdroid-wms:6.1.20")

    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}