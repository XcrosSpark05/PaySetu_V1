package com.paysetu.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

object CryptoEngine {
    private const val KEY_ALIAS = "PaySetu_Master_RSA_Key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    init {
        generateKeyPairIfNotExists()
    }

    private fun generateKeyPairIfNotExists() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            Log.d("CryptoDemo", "🛠️ TEE: Generating brand new RSA KeyPair inside Hardware Keystore...")
            val keyPairGenerator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE)
            val parameterSpec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .build()

            keyPairGenerator.initialize(parameterSpec)
            keyPairGenerator.generateKeyPair()
            Log.d("CryptoDemo", "🔒 TEE: Keys locked in hardware. Private Key cannot be extracted.")
        } else {
            Log.d("CryptoDemo", "🔒 TEE: Existing RSA KeyPair found in hardware.")
        }
    }

    fun getPublicKeyBase64(): String {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val publicKey = keyStore.getCertificate(KEY_ALIAS).publicKey
        Log.d("CryptoDemo", "📤 TEE: Exporting Public Key for Handshake.")
        return Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
    }

    fun generateSignature(data: String): String {
        Log.d("CryptoDemo", "✍️ TEE: Cryptographically signing payload with Hardware Private Key...")
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as PrivateKey

        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(privateKey)
        signature.update(data.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(signature.sign(), Base64.NO_WRAP)
    }

    fun generateSignature(amount: Long, phone: String, account: String, hash: String): String {
        val payload = "$amount:$phone:$account:$hash"
        return generateSignature(payload)
    }

    fun verifySignature(publicKeyBase64: String, data: String, signatureBase64: String): Boolean {
        Log.d("CryptoDemo", "🛡️ TEE: Verifying incoming signature against Sender's Public Key...")
        return try {
            val keyBytes = Base64.decode(publicKeyBase64, Base64.NO_WRAP)
            val keySpec = X509EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance("RSA")
            val publicKey = keyFactory.generatePublic(keySpec)

            val signature = Signature.getInstance("SHA256withRSA")
            signature.initVerify(publicKey)
            signature.update(data.toByteArray(Charsets.UTF_8))
            val sigBytes = Base64.decode(signatureBase64, Base64.NO_WRAP)

            val isValid = signature.verify(sigBytes)
            if (isValid) Log.d("CryptoDemo", "✅ TEE: Signature verified! Data is authentic.")
            else Log.d("CryptoDemo", "❌ TEE: Signature mismatch! Data was tampered with.")

            isValid
        } catch (e: Exception) {
            Log.e("CryptoDemo", "❌ TEE: Forgery detected. Invalid Key Format.")
            false
        }
    }

    fun generateNonce(): String {
        Log.d("CryptoDemo", "🎲 Security: Generating one-time Nonce to prevent replay attacks.")
        val random = SecureRandom()
        val nonceBytes = ByteArray(16)
        random.nextBytes(nonceBytes)
        return Base64.encodeToString(nonceBytes, Base64.NO_WRAP)
    }
}