package com.paysetu.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.security.crypto.MasterKeys
import java.security.SecureRandom

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

    fun getDatabaseKey(context: Context): ByteArray {
        val prefs = getSecurePrefs(context)
        var keyBase64 = prefs.getString("db_aes_key", null)

        if (keyBase64 == null) {
            Log.d("CryptoDemo", "🛡️ DATABASE: Generating fresh 256-bit AES Key for SQLCipher...")
            val secureRandom = java.security.SecureRandom()
            val newKey = ByteArray(32)
            secureRandom.nextBytes(newKey)
            keyBase64 = android.util.Base64.encodeToString(newKey, android.util.Base64.NO_WRAP)

            prefs.edit().putString("db_aes_key", keyBase64).apply()
        } else {
            Log.d("CryptoDemo", "🛡️ DATABASE: Recovered 256-bit AES Key from TEE.")
        }
        Log.d("CryptoDemo", "🗄️ SQLCipher is now unlocked and mapping the Ledger.")
        return android.util.Base64.decode(keyBase64, android.util.Base64.NO_WRAP)
    }
}