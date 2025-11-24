plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}

// REMOVE any `allprojects { repositories { ... } }` block
// Repositories are now only in settings.gradle.kts
