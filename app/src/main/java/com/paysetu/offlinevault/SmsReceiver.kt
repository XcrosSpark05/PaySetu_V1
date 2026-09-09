package com.paysetu.offlinevault

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.google.firebase.auth.FirebaseAuth
import java.security.MessageDigest

// 🔒 Cryptographic Engine for Payload Integrity
object CryptoEngine {
    private const val PAYSETU_SECRET_KEY = "VESIT_MCA_2026_VAULT_KEY_!@#"

    // ✨ Upgraded signature includes Account Number and Phone to prevent spoofing
    fun generateSignature(amount: Long, phone: String, account: String, txHash: String): String {
        val rawData = "$amount|$phone|$account|$txHash|$PAYSETU_SECRET_KEY"
        val bytes = MessageDigest.getInstance("SHA-256").digest(rawData.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(12)
    }
}

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            for (sms in messages) {
                val body = sms.displayMessageBody
                val senderPhone = sms.originatingAddress ?: "Unknown"

                if (body != null && body.startsWith("##PAYSETU_REQ##")) {
                    handleIncomingRequest(context, body, senderPhone)
                    abortBroadcast()
                }
                else if (body != null && body.startsWith("##PAYSETU_ACK##")) {
                    handleAcknowledgment(context, body, senderPhone)
                    abortBroadcast()
                }
            }
        }
    }

    // 1. WHEN RECEIVER GETS THE MONEY (The Request)
    private fun handleIncomingRequest(context: Context, payload: String, senderPhone: String) {
        try {
            val parts = payload.split(":")
            if (parts.size < 7) return

            val amount = parts[1].toLong()
            val senderName = parts[2]
            val expectedSenderPhone = parts[3] // Phone A's real number
            val senderAcc = parts[4]
            val hash = parts[5]
            val receivedSignature = parts[6]
            val intendedVA = if (parts.size >= 8 && parts[7].isNotBlank()) parts[7] else null

            // ✨ BUG FIX: Re-join the remaining parts so colons in the Note don't break the message!
            val note = if (parts.size >= 9) parts.subList(8, parts.size).joinToString(":") else ""

            val expectedSignature = CryptoEngine.generateSignature(amount, expectedSenderPhone, senderAcc, hash)
            val cleanExpectedPhone = expectedSenderPhone.takeLast(10)

            // THE MASTER KEY BYPASS: Trust the Hub if it verified a Feature Phone SMS
            if (receivedSignature != "HUB_AUTH_SIG" && expectedSignature != receivedSignature) {
                Log.e("VaultSecurity", "TAMPERING DETECTED! Signature Mismatch.")
                return
            }

            val db = DatabaseProvider.getDatabase(context)
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

            CoroutineScope(Dispatchers.IO).launch {

                // ✨ THE BULLETPROOF KILL-SWITCH
                // We check if the hash starts with REF_ to know for a FACT it's a Hub bounce-back.
                if (receivedSignature == "HUB_AUTH_SIG" && hash.startsWith("REF_")) {
                    val originalHash = hash.replace("REF_", "")

                    // Mark original as Refunded so the UI immediately crosses it out
                    db.creditDao().updateTransactionNote(originalHash, "🚨 REFUNDED")

                    // Instantly rename "Pending" to "Voided" so the 10-minute timer completely ignores it!
                    val originalTx = db.creditDao().getTransactionByHash(originalHash)
                    if (originalTx != null && originalTx.senderId.startsWith("Pending")) {
                        db.creditDao().updateTransactionSmartId(originalHash, originalTx.senderId.replace("Pending", "Voided"))
                    }

                    Log.d("VaultSecurity", "Bounce-back received. Original Tx $originalHash marked as Voided.")
                }

                if (db.creditDao().isTransactionProcessed(hash) > 0) return@launch

                val smartId = "$senderName|$cleanExpectedPhone|$senderAcc"

                // Fallback routing forces it into your currently active wallet on the screen
                val prefs = SecurityUtils.getSecurePrefs(context)
                val activeWalletFromPrefs = prefs.getString("active_wallet_id", "")
                val fallbackAcc = prefs.getString("account_number", "")

                val finalRoutedVA = intendedVA?.takeIf { it.isNotBlank() }
                    ?: db.creditDao().getVirtualAccountForPhone(cleanExpectedPhone)
                    ?: activeWalletFromPrefs?.takeIf { it.isNotBlank() }
                    ?: db.creditDao().getPrimaryWalletId(uid)
                    ?: fallbackAcc?.takeIf { it.isNotBlank() }
                    ?: "${uid.take(8).uppercase()}-00"

                db.creditDao().addTransaction(
                    CreditEntry(
                        userId = uid, amount = amount, senderId = smartId,
                        timestamp = System.currentTimeMillis(), previousHash = "REMOTE_SMS",
                        transactionHash = hash, isSynced = false,
                        targetVirtualAccount = finalRoutedVA, note = note
                    )
                )

                db.creditDao().addNotification(
                    NotificationEntry(userId = uid, title = "Money Received", message = "₹$amount securely routed.", timestamp = System.currentTimeMillis())
                )

                // 📡 SILENTLY SEND ACKNOWLEDGMENT
                val myName = prefs.getString("user_name", "User") ?: "User"
                val myPhone = prefs.getString("phone_number", "") ?: ""
                val myAcc = fallbackAcc ?: "880000000000"

                val ackSignature = CryptoEngine.generateSignature(amount, myPhone, myAcc, hash)
                val hubAckPayload = "##PAYSETU_HUB_ACK##:$expectedSenderPhone:$amount:$myName:$myPhone:$myAcc:$hash:$ackSignature"

                try {
                    val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
                    // Send receipt to the Hub, so it can route it back to Phone A
                    smsManager.sendTextMessage(senderPhone, null, hubAckPayload, null, null)
                } catch (e: Exception) { Log.e("SmsReceiver", "Failed to send ACK SMS") }

                SyncManager(context).triggerImmediateSync()
            }
        } catch (e: Exception) { Log.e("SmsReceiver", "Error parsing REQ") }
    }

    // 2. WHEN SENDER GETS CONFIRMATION BACK (The Acknowledgment)
    private fun handleAcknowledgment(context: Context, payload: String, receiverPhone: String) {
        try {
            val parts = payload.split(":")
            if (parts.size != 7) return

            val amount = parts[1].toLong()
            val receiverName = parts[2]
            val expectedReceiverPhone = parts[3] // ✨ THIS is the real Phone B number!
            val receiverAcc = parts[4]
            val hash = parts[5]
            val receivedSignature = parts[6]

            val expectedSignature = CryptoEngine.generateSignature(amount, expectedReceiverPhone, receiverAcc, hash)

            // ✨ THE FIX: We added the Hub Bypass so the Android App accepts the Hub's Auto-ACK!
            if (receivedSignature != "HUB_AUTH_SIG" && expectedSignature != receivedSignature) {
                Log.e("VaultSecurity", "ACK Tampering Detected! Signature Mismatch.")
                return
            }

            val db = DatabaseProvider.getDatabase(context)

            // ✨ THE FIX: We use 'expectedReceiverPhone' instead of the physical 'receiverPhone' (the Hub)
            val realSmartId = "$receiverName|$expectedReceiverPhone|$receiverAcc"

            CoroutineScope(Dispatchers.IO).launch {
                db.creditDao().updateTransactionSmartId(hash, realSmartId)
                SyncManager(context).triggerImmediateSync()
            }
        } catch (e: Exception) { Log.e("SmsReceiver", "Error parsing ACK") }
    }

    private fun logSecurityAlert(context: Context, title: String, message: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val db = DatabaseProvider.getDatabase(context)
        CoroutineScope(Dispatchers.IO).launch {
            db.creditDao().addNotification(NotificationEntry(userId = uid, title = title, message = message, timestamp = System.currentTimeMillis()))
        }
    }
}

// 🛡️ THE MERCHANT FIREWALL (SAFE HARBOR PROTOCOL)
object DisputeManager {

    // Action types: "REFUND", "BLOCK", "REFUND_AND_BLOCK"
    fun triggerDisputeAction(context: Context, txHash: String, senderPhone: String, action: String, hubPhoneNumber: String) {
        try {
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            val disputePayload = "##PAYSETU_DISPUTE##:$action:$txHash"
            smsManager.sendTextMessage(hubPhoneNumber, null, disputePayload, null, null)

            Log.d("VaultSecurity", "Dispute ($action) initiated for Tx: $txHash")

            CoroutineScope(Dispatchers.IO).launch {
                val db = DatabaseProvider.getDatabase(context)

                if (action.contains("REFUND")) {
                    // ✨ THE FIX: Instantly mark the original transaction as voided on the merchant's phone!
                    // This creates the grey strikethrough and hides the "Report" button so they can't double-refund.
                    db.creditDao().updateTransactionNote(txHash, "🚨 REFUNDED")

                    val originalTx = db.creditDao().getTransactionByHash(txHash)
                    val uid = FirebaseAuth.getInstance().currentUser?.uid

                    if (originalTx != null && uid != null) {
                        val reverseAmount = originalTx.amount * -1
                        val reverseEntry = CreditEntry(
                            userId = uid,
                            amount = reverseAmount,
                            senderId = originalTx.senderId,
                            timestamp = System.currentTimeMillis(),
                            transactionHash = "REF_$txHash",
                            previousHash = "CONTRA_ENTRY",
                            isSynced = false,
                            targetVirtualAccount = originalTx.targetVirtualAccount,
                            note = "Refunded to Sender"
                        )
                        db.creditDao().addTransaction(reverseEntry)
                        SyncManager(context).triggerImmediateSync()
                    }
                }

                if (action.contains("BLOCK")) {
                    db.creditDao().updateTransactionNote(txHash, "🛑 BLOCKED SENDER")
                    db.creditDao().blockUser(BlockedUser(senderPhone, System.currentTimeMillis()))

                    // Update Firebase Sub-collection for the Hub Gatekeeper
                    val uid = FirebaseAuth.getInstance().currentUser?.uid
                    if (uid != null) {
                        val cleanScammer = senderPhone.takeLast(10)
                        val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                        val blockData = hashMapOf(
                            "phone" to cleanScammer,
                            "timestamp" to System.currentTimeMillis()
                        )
                        firestore.collection("users").document(uid)
                            .collection("blocked_numbers").document(cleanScammer)
                            .set(blockData)
                    }
                }
            }
        } catch (e: Exception) { Log.e("VaultSecurity", "Failed to initiate dispute SMS") }
    }

    // Call this from the Profile -> Blocked Users screen later
    fun unblockUser(context: Context, blockedPhone: String, hubPhoneNumber: String) {
        try {
            val smsManager: SmsManager = context.getSystemService(SmsManager::class.java)

            // Send unblock command to Hub
            val unblockPayload = "##PAYSETU_DISPUTE##:UNBLOCK:$blockedPhone"
            smsManager.sendTextMessage(hubPhoneNumber, null, unblockPayload, null, null)

            // Update local database
            CoroutineScope(Dispatchers.IO).launch {
                val db = DatabaseProvider.getDatabase(context)
                db.creditDao().unblockUserLocally(blockedPhone)
            }
        } catch (e: Exception) { Log.e("VaultSecurity", "Failed to send unblock SMS") }
    }
}