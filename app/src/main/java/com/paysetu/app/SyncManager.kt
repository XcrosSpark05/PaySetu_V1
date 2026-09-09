package com.paysetu.app

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

class SyncManager(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    fun startMonitoring() {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch { performTwoWaySync() }
            }
        })
    }

    fun triggerImmediateSync() {
        scope.launch { performTwoWaySync() }
    }

    @SuppressLint("MissingPermission")
    private suspend fun performTwoWaySync() {
        val uid = auth.currentUser?.uid ?: return
        val db = DatabaseProvider.getDatabase(context)
        val prefs = SecurityUtils.getSecurePrefs(context)
        val myPhone = prefs.getString("phone_number", "") ?: return

        if (myPhone.isBlank()) return

        val notificationManager = NotificationManagerCompat.from(context)
        val builder = NotificationCompat.Builder(context, "VAULT_NOTIFS")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Vault Consensus Sync")
            .setContentText("Verifying ledger integrity...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)

        notificationManager.notify(101, builder.build())

        try {
            val cloudQuery = firestore.collection("transactions")
                .whereArrayContains("participants", myPhone)
                .get()
                .await()

            val localTransactions = db.creditDao().getAllTransactionsDirectly()
            val localHashMap = localTransactions.associateBy { it.transactionHash }

            var changesMade = 0

            // --- PHASE 1: COMPARE CLOUD AGAINST LOCAL ---
            for (document in cloudQuery.documents) {
                val cloudHash = document.getString("txHash") ?: continue
                val cloudStatus = document.getString("status") ?: ""

                // ✨ Do not download Hub-Rejected transactions as actual money!
                if (cloudStatus == "REJECTED_BLOCKED") continue

                val cloudAmount = document.getLong("amount") ?: 0L
                val cloudSenderPhone = document.getString("senderPhone") ?: ""
                val cloudReceiverPhone = document.getString("receiverPhone") ?: ""
                val cloudNote = document.getString("note") ?: ""

                val isIncoming = myPhone == cloudReceiverPhone
                val adjustedCloudAmount = if (isIncoming) cloudAmount else -cloudAmount
                val localTx = localHashMap[cloudHash]

                if (localTx != null) {
                    if (localTx.amount == adjustedCloudAmount) {
                        if (!localTx.isSynced) {
                            db.creditDao().markAsSynced(cloudHash)
                            changesMade++
                        }
                    } else {
                        Log.e("SyncManager", "🚨 TAMPERING DETECTED for $cloudHash! Local: ${localTx.amount}, Cloud: $adjustedCloudAmount")
                        db.creditDao().updateTransactionAmount(cloudHash, adjustedCloudAmount)
                        db.creditDao().addNotification(
                            NotificationEntry(userId = uid, title = "⚠️ Ledger Correction", message = "A local mismatch was detected. Network truth restored.", timestamp = System.currentTimeMillis())
                        )
                        changesMade++
                    }
                } else {
                    Log.d("SyncManager", "Downloading missing cloud transaction: $cloudHash")
                    val otherPartyPhone = if (isIncoming) cloudSenderPhone else cloudReceiverPhone
                    val otherPartyName = document.getString(if (isIncoming) "senderName" else "receiverName") ?: "Unknown"
                    val otherPartyVA = document.getString(if (isIncoming) "senderVA" else "receiverVA") ?: ""

                    val smartId = "$otherPartyName|$otherPartyPhone|$otherPartyVA"
                    val myVA = document.getString(if (isIncoming) "receiverVA" else "senderVA") ?: "${uid.take(8).uppercase()}-00"

                    db.creditDao().addTransaction(
                        CreditEntry(
                            userId = uid, amount = adjustedCloudAmount, senderId = smartId,
                            timestamp = document.getLong("timestamp") ?: System.currentTimeMillis(),
                            previousHash = "CLOUD_RESTORE", transactionHash = cloudHash,
                            isSynced = true, targetVirtualAccount = myVA, note = cloudNote
                        )
                    )
                    changesMade++
                }
            }

            // --- PHASE 2: STRICT UPLOAD & UNIFIED LOCAL MATH ---
            val unsyncedLocal = localTransactions.filter { !it.isSynced }
            val cloudHashSet = cloudQuery.documents.mapNotNull { it.getString("txHash") }.toSet()

            val batch = firestore.batch()
            var uploadCount = 0

            // ✨ THE FIX: We now track ALL balance changes in one single variable!
            var localBalanceChange = 0L

            for (local in unsyncedLocal) {
                if (!cloudHashSet.contains(local.transactionHash)) {

                    // 1. BANK TRANSFERS: Mark synced and ADD to unified math
                    if (local.senderId == "BANK_SIM" || local.senderId == "BANK_WITHDRAWAL") {
                        db.creditDao().markAsSynced(local.transactionHash)
                        localBalanceChange += local.amount
                        continue
                    }

                    // 2. REFUNDS & TIMEOUTS: Mark synced, but IGNORE Cloud Math!
                    if (local.senderId.startsWith("SYSTEM_REFUND") || local.senderId.startsWith("Expired") || local.previousHash == "CONTRA_ENTRY") {
                        db.creditDao().markAsSynced(local.transactionHash)
                        continue
                    }

                    // 3. SMS TRANSFERS: Ignore completely. The Hub will upload these.
                    if (local.previousHash == "REMOTE_SMS") {
                        continue
                    }

                    // 4. OFFLINE NEARBY TRANSFERS: Upload to Firebase AND ADD to unified math
                    Log.d("SyncManager", "Uploading offline Nearby ghost: ${local.transactionHash}")
                    val targetPhone = local.senderId.split("|").getOrNull(1) ?: ""

                    // ✨ IDEMPOTENT SYNC LOGIC
                    val docRef = firestore.collection("users").document(uid)
                        .collection("transactions").document(local.transactionHash)
                    val txData = hashMapOf(
                        "txHash" to local.transactionHash,
                        "amount" to kotlin.math.abs(local.amount),
                        "senderPhone" to if (local.amount < 0) myPhone else targetPhone,
                        "receiverPhone" to if (local.amount > 0) myPhone else targetPhone,
                        "timestamp" to local.timestamp,
                        "note" to (local.note ?: ""),
                        "status" to "LATE_SYNC",
                        "participants" to listOfNotNull(myPhone, targetPhone.takeIf { it.isNotBlank() })
                    )

                    // Use SetOptions.merge() for idempotency
                    batch.set(docRef, txData, SetOptions.merge())
                    db.creditDao().markAsSynced(local.transactionHash)

                    uploadCount++
                    changesMade++
                    // ONLY update the balance if this is the FIRST time we are uploading this tx.
                    localBalanceChange += local.amount
                }
            }

            if (uploadCount > 0) {
                batch.commit().await()
            }

            // ✨ SAFELY UPDATE THE SINGLE FIREBASE LOCAL BALANCE
            val userRef = firestore.collection("users").document(uid)
            val updates = mutableMapOf<String, Any>("last_sync" to System.currentTimeMillis())

            if (localBalanceChange != 0L) {
                updates["Local_balance"] = com.google.firebase.firestore.FieldValue.increment(localBalanceChange)
            }

            userRef.update(updates).await()

            if (changesMade > 0) {
                context.sendBroadcast(android.content.Intent("com.paysetu.REFRESH_UI"))
            }

            builder.setContentTitle(if (changesMade > 0) "Vault Fully Secured" else "Vault Up To Date")
                .setContentText("Ledger mathematically verified.")
                .setProgress(0, 0, false)
                .setOngoing(false)

            notificationManager.notify(101, builder.build())

        } catch (e: Exception) {
            Log.e("SyncManager", "Consensus sync failed: ${e.message}")
            builder.setContentTitle("Sync Paused").setContentText("Will retry when connection is stable.").setProgress(0, 0, false).setOngoing(false)
            notificationManager.notify(101, builder.build())
        }
    }

    fun check24HourHealth(lastSyncMillis: Long, onNagNeeded: (String, String) -> Unit) {
        val oneDay = 24 * 60 * 60 * 1000L
        if (System.currentTimeMillis() - lastSyncMillis > oneDay) {
            onNagNeeded("Vault Backup Required", "You haven't synced your transactions in over 24 hours.")
        }
    }
}