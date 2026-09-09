package com.paysetu.offlinevault

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import android.util.Log
import java.security.Signature

class VaultKeyManager {

    private val KEY_ALIAS = "VaultMasterKey"
    private val ANDROID_KEYSTORE = "AndroidKeyStore"

    fun generateHardwareKey() {

        // 1. Access the phone's built-in KeyStore
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        // 2. Check if the key already exists so we don't overwrite it
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            Log.d("VaultKeyManager", "Generating new hardware key")
            val kpg = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC, // Modern, fast encryption for phones
                ANDROID_KEYSTORE
            )

            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false) // Set to 'true' later for fingerprint
                .build()

            kpg.initialize(spec)
            kpg.generateKeyPair()
        }else {
            Log.d("VaultKeyManager", "Hardware key already exists")
        }
    }

    fun signTransaction(data: String): ByteArray {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val privateKey = keyStore.getKey("VaultMasterKey", null) as java.security.PrivateKey

        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(privateKey)
        s.update(data.toByteArray())
        return s.sign()
    }

    fun getPublicKey(): ByteArray {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return keyStore.getCertificate("VaultMasterKey").publicKey.encoded
    }
}