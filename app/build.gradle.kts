plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)


    id("com.google.gms.google-services")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.paysetu.app"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.paysetu.app"
        minSdk = 26
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
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(libs.androidx.compose.material)
    implementation(libs.androidx.compose.ui.geometry)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.material3)
    implementation(libs.material)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    val room_version = "2.6.1" // Ensure this is 2.6.1 or higher

    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version")

    // REMOVE THIS: kapt("androidx.room:room-compiler:$room_version")
    // ADD THIS:
    ksp("androidx.room:room-compiler:$room_version")
    // SQLCipher for Room
    // In your dependencies block
    implementation("net.zetetic:sqlcipher-android:4.6.0") // Use the newer artifact name
    implementation("androidx.sqlite:sqlite:2.4.0")

    // implementation("com.google.android.gms:play-services-nearby:19.0.0")
    implementation("com.google.android.gms:play-services-auth:21.0.0")
    implementation("com.google.android.gms:play-services-base:18.5.0")
    // Replace the problematic line with this one:
    implementation("androidx.biometric:biometric:1.2.0-alpha05")



    // Ensure you also have these for the UI and Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.1")
    implementation("androidx.fragment:fragment-ktx:1.6.1")

    implementation("androidx.camera:camera-camera2:1.3.0")
    implementation("androidx.camera:camera-lifecycle:1.3.0")
    implementation("androidx.camera:camera-view:1.3.0")
    implementation("com.google.mlkit:barcode-scanning:17.2.0")
    implementation("androidx.compose.material:material-icons-extended:1.5.4")
    implementation("com.google.zxing:core:3.5.3")

    // Firebase BoM for version management
    implementation(platform("com.google.firebase:firebase-bom:34.8.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")

    // Jetpack Security for the PaySetu PIN
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Biometrics for Phone authentication
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")


}