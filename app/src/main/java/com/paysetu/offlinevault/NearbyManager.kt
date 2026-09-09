package com.paysetu.offlinevault

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

class NearbyManager(private val context: Context) {

    private val connectionsClient = Nearby.getConnectionsClient(context)
    var pendingAmount: Long = 0L
    private var amountInTransit: Long = 0L

    private val STRATEGY = Strategy.P2P_POINT_TO_POINT
    private val SERVICE_ID = "com.paysetu.offlinevault.SERVICE_ID"

    val currentUid = FirebaseAuth.getInstance().currentUser?.uid ?: "unknown"

    var onDeviceFound: ((String, String) -> Unit)? = null
    var onTransferComplete: ((Long, Boolean) -> Unit)? = null

    // ✨ Tells Nearby which Virtual Account to save the sent money into
    var currentVirtualAccount: String = ""

    fun startDiscovery() {
        forceKillEverything()
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options)
            .addOnSuccessListener { Log.d("P2P_LOG", "Fresh Discovery Started...") }
    }

    fun startAdvertising(smartId: String) {
        forceKillEverything()
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startAdvertising(smartId, SERVICE_ID, connectionLifecycleCallback, options)
    }

    fun disconnectFromPeer(endpointId: String) {
        connectionsClient.disconnectFromEndpoint(endpointId)
    }

    fun forceKillEverything() {
        try {
            connectionsClient.stopDiscovery()
            connectionsClient.stopAdvertising()
            connectionsClient.stopAllEndpoints()
            amountInTransit = 0L
            pendingAmount = 0L
            Log.d("NearbyManager", "Hardware Radios successfully flushed.")
        } catch (e: Exception) {
            Log.e("NearbyManager", "Failed to kill radios: ${e.message}")
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            onDeviceFound?.invoke(endpointId, info.endpointName)
        }
        override fun onEndpointLost(endpointId: String) {}
    }

    fun connectToDevice(endpointId: String) {
        val prefs = SecurityUtils.getSecurePrefs(context)
        val myName = prefs.getString("user_name", "Anonymous") ?: "Anonymous"
        val myPhone = prefs.getString("phone_number", "") ?: ""
        val myAcc = prefs.getString("account_number", "") ?: ""

        val mySmartId = "$myName|$myPhone|$myAcc"

        connectionsClient.requestConnection(mySmartId, endpointId, connectionLifecycleCallback)
            .addOnFailureListener {
                Log.e("P2P_LOG", "Connection request failed")
                (context as? MainActivity)?.runOnUiThread {
                    onTransferComplete?.invoke(-1L, false)
                }
            }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess && pendingAmount > 0) {
                sendSecureCreditPacket(endpointId, pendingAmount)
            }
        }
        override fun onDisconnected(endpointId: String) {}
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            val receivedString = String(bytes)

            if (receivedString == "PAYS_CONFIRMATION_SIGNAL_SUCCESS") {
                connectionsClient.disconnectFromEndpoint(endpointId)
                (context as? MainActivity)?.runOnUiThread {
                    onTransferComplete?.invoke(amountInTransit, true)
                }
            } else if (receivedString.contains("|||") && receivedString.startsWith("AMOUNT:")) {

                val amountStr = receivedString.substringAfter("AMOUNT:").substringBefore("|SENDER:")
                val senderSmartId = receivedString.substringAfter("SENDER:").substringBefore("|TIME:")
                val amount = amountStr.toLongOrNull() ?: 0L

                if (amount > 0) {
                    CoroutineScope(Dispatchers.IO).launch {
                        val db = DatabaseProvider.getDatabase(context)

                        // ✨ THE KEY RECEIVE FIX: Put money ONLY in the wallet the Receiver is looking at
                        val routedVA = if (currentVirtualAccount.isNotEmpty()) {
                            currentVirtualAccount
                        } else {
                            db.creditDao().getPrimaryWalletId(currentUid) ?: "${currentUid.take(8).uppercase()}-00"
                        }

                        // 🟢 SAVE TO DATABASE 🟢
                        db.creditDao().addTransaction(CreditEntry(
                            userId = currentUid,
                            amount = amount,
                            senderId = senderSmartId,
                            timestamp = System.currentTimeMillis(),
                            transactionHash = "TX_NEARBY_${System.currentTimeMillis()}",
                            previousHash = "NEARBY_PROXIMITY",
                            isSynced = false,
                            targetVirtualAccount = routedVA
                        ))

                        connectionsClient.sendPayload(endpointId, Payload.fromBytes("PAYS_CONFIRMATION_SIGNAL_SUCCESS".toByteArray()))

                        delay(500)
                        connectionsClient.disconnectFromEndpoint(endpointId)

                        (context as? MainActivity)?.runOnUiThread {
                            // ✨ FORCE UI REFRESH: Reloads balance and history instantly
                            (context as? MainActivity)?.apply {
                                viewModel?.refreshDataForUser(currentUid)
                            }
                            onTransferComplete?.invoke(amount, false)
                        }
                    }
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.FAILURE) {
                (context as? MainActivity)?.runOnUiThread {
                    onTransferComplete?.invoke(-1L, false)
                }
            }
        }
    }

    fun sendSecureCreditPacket(endpointId: String, amount: Long) {
        // ✨ CRITICAL: Capture the active wallet ID on the MAIN thread before starting the background thread
        // This prevents the variable from being empty when the database write happens.
        val activeWalletAtMomentOfSend = currentVirtualAccount.ifEmpty {
            val prefs = SecurityUtils.getSecurePrefs(context)
            val acc = prefs.getString("account_number", "") ?: ""
            if (acc.isNotEmpty()) "${acc.take(8).uppercase()}-00" else "UNKNOWN-00"
        }

        thread {
            try {
                amountInTransit = amount
                val db = DatabaseProvider.getDatabase(context)
                val prefs = SecurityUtils.getSecurePrefs(context)

                // 1. Get Sender Info
                val myName = prefs.getString("user_name", "Anonymous") ?: "Anonymous"
                val myPhone = prefs.getString("phone_number", "") ?: ""
                val myAcc = prefs.getString("account_number", "") ?: ""
                val mySmartId = "$myName|$myPhone|$myAcc"

                val timestamp = System.currentTimeMillis()
                val rawData = "AMOUNT:$amount|SENDER:$mySmartId|TIME:$timestamp|PREV:0"
                val finalPacket = "$rawData|||SIGNED"

                // 2. Send the packet to the Tab
                connectionsClient.sendPayload(endpointId, Payload.fromBytes(finalPacket.toByteArray()))

                // 3. ✨ THE SENDER FIX: Identify the Target correctly from the scanned URI
                val rawTarget = (context as? MainActivity)?.scannedTargetName ?: "Receiver"
                val cleanTargetId = if (rawTarget.startsWith("paysetu://")) {
                    try {
                        val uri = android.net.Uri.parse(rawTarget)
                        val tName = uri.getQueryParameter("name") ?: "Receiver"
                        val tPhone = uri.getQueryParameter("phone") ?: ""
                        val tVa = uri.getQueryParameter("va") ?: ""
                        "$tName|$tPhone|$tVa"
                    } catch (e: Exception) {
                        rawTarget
                    }
                } else {
                    rawTarget
                }

                Log.d("DebitCheck", "Debiting ₹$amount from Wallet: $activeWalletAtMomentOfSend")

                // 5. Add the DEBIT entry (-amount)
                db.creditDao().addTransaction(CreditEntry(
                    userId = currentUid,
                    amount = -amount, // Negative value for debit
                    senderId = cleanTargetId, // Saved as the clean ID for Chat/History
                    timestamp = timestamp,
                    transactionHash = "SENT_${timestamp}",
                    previousHash = "NEARBY_PROXIMITY",
                    isSynced = false,
                    targetVirtualAccount = activeWalletAtMomentOfSend // ✨ Guaranteed to have the correct ID
                ))

                pendingAmount = 0L

                // 6. Refresh the Sender's UI so the balance drops instantly
                (context as? MainActivity)?.runOnUiThread {
                    (context as? MainActivity)?.viewModel?.refreshDataForUser(currentUid)
                }

            } catch (e: Exception) {
                Log.e("P2P_LOG", "Debit failed: ${e.message}")
            }
        }
    }
}