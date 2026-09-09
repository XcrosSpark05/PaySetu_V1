package com.paysetu.offlinevault

import android.provider.Settings
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

// ENUM placed here for package-wide visibility
enum class LoginState { SUCCESS, DEVICE_MISMATCH, NOT_LOGGED_IN }

class AuthRepository(private val context: android.content.Context) {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    private val currentDeviceId = Settings.Secure.getString(
        context.contentResolver, Settings.Secure.ANDROID_ID
    )

    fun checkUserBinding(onResult: (LoginState) -> Unit) {
        val user = auth.currentUser ?: return onResult(LoginState.NOT_LOGGED_IN)

        db.collection("users").document(user.uid).get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    val activeId = doc.getString("active_device_id")
                    if (activeId == currentDeviceId) onResult(LoginState.SUCCESS)
                    else onResult(LoginState.DEVICE_MISMATCH)
                } else {
                    onResult(LoginState.NOT_LOGGED_IN)
                }
            }
    }

    // REAL UNIQUE IDENTITY CHECK
    fun validateNewUser(username: String, phone: String, email: String, onResult: (Boolean, String?) -> Unit) {
        // Check Username
        db.collection("users").whereEqualTo("username", username).get()
            .addOnSuccessListener { users ->
                if (!users.isEmpty) return@addOnSuccessListener onResult(false, "Username taken")

                // NEW: Check Email Uniqueness
                db.collection("users").whereEqualTo("email", email).get()
                    .addOnSuccessListener { emails ->
                        if (!emails.isEmpty) return@addOnSuccessListener onResult(false, "Email already in use")

                        // Check Phone
                        db.collection("users").whereEqualTo("phone", phone).get()
                            .addOnSuccessListener { phones ->
                                if (!phones.isEmpty) onResult(false, "Phone already registered")
                                else onResult(true, null)
                            }
                    }
            }
    }

    // BIND HARDWARE TO ACCOUNT
    fun registerNewUser(uid: String, username: String, email: String, phone: String, onResult: (Boolean) -> Unit) {
        val data = hashMapOf(
            "username" to username,
            "email" to email,
            "phone" to phone,
            "active_device_id" to currentDeviceId,
            "locker_balance" to 0L,
            "last_sync" to System.currentTimeMillis()
        )
        db.collection("users").document(uid).set(data)
            .addOnSuccessListener { onResult(true) }
            .addOnFailureListener { onResult(false) }
    }
}