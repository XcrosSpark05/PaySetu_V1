plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false

    id("com.google.gms.google-services") version "4.4.1" apply false
    // CHANGE THIS VERSION TO MATCH YOUR KOTLIN VERSION
    id("com.google.devtools.ksp") version "2.0.0-1.0.24" apply false
}