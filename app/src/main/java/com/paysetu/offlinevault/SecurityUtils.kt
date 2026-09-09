package com.paysetu.offlinevault

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.security.crypto.MasterKeys

object SecurityUtils {

    // In SecurityUtils.kt
    fun getSecurePrefs(context: Context): SharedPreferences {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

        return try {
            EncryptedSharedPreferences.create(
                "secure_prefs",
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // If the key is corrupt, delete the file and try one more time
            Log.e("Security", "Secure key mismatch. Wiping corrupt prefs.")
            context.getSharedPreferences("secure_prefs", Context.MODE_PRIVATE).edit().clear().apply()

            // Return a fresh instance
            EncryptedSharedPreferences.create(
                "secure_prefs",
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    // Use this during login/setup
    fun saveAppPin(context: Context, pin: String) {
        getSecurePrefs(context).edit().putString("app_pin", pin).apply()
    }

    // Use this to verify before a transaction
    fun getAppPin(context: Context): String? {
        return getSecurePrefs(context).getString("app_pin", null)
    }
}