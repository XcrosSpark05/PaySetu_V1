package com.paysetu.app

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

    // ✨ Stores the Nonce to prevent replay attacks
    private var activeNonce: String = ""

    private val STRATEGY = Strategy.P2P_POINT_TO_POINT
    private val SERVICE_ID = "com.paysetu.offlinevault.SERVICE_ID"

    val currentUid = FirebaseAuth.getInstance().currentUser?.uid ?: "unknown"

    var onDeviceFound: ((String, String) -> Unit)? = null
    var onTransferComplete: ((Long, Boolean) -> Unit)? = null
    var currentVirtualAccount: String = ""

    fun startDiscovery() {
        forceKillEverything()
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options)
    }

    fun startAdvertising(smartId: String) {
        forceKillEverything()
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startAdvertising(smartId, SERVICE_ID, connectionLifecycleCallback, options)
    }

    fun forceKillEverything() {
        try {
            connectionsClient.stopDiscovery()
            connectionsClient.stopAdvertising()
            connectionsClient.stopAllEndpoints()
            amountInTransit = 0L
            pendingAmount = 0L
            activeNonce = ""
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
                (context as? MainActivity)?.runOnUiThread { onTransferComplete?.invoke(-1L, false) }
            }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                if (pendingAmount == 0L) { // I am Receiver
                    Log.d("CryptoDemo", "📡 HANDSHAKE [Step 1]: Receiver connected. Generating and sending Nonce challenge to Sender...")
                    activeNonce = CryptoEngine.generateNonce()
                    connectionsClient.sendPayload(endpointId, Payload.fromBytes("HANDSHAKE:NONCE:$activeNonce".toByteArray()))
                }
            }
        }
        override fun onDisconnected(endpointId: String) {}
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            val receivedString = String(bytes)

            if (receivedString.startsWith("HANDSHAKE:NONCE:")) {
                Log.d("CryptoDemo", "📡 HANDSHAKE [Step 2]: Sender received Nonce challenge. Processing secure packet...")
                val nonce = receivedString.substringAfter("HANDSHAKE:NONCE:")
                sendSecureCreditPacket(endpointId, pendingAmount, nonce)
            }

            else if (receivedString.startsWith("PAYLOAD:")) {
                Log.d("CryptoDemo", "📡 HANDSHAKE [Step 3]: Receiver got the Payload. Extracting Data, Public Key, and Signature...")
                val payloadContent = receivedString.substringAfter("PAYLOAD:")

                val rawData = payloadContent.substringBefore("|PUBKEY:")
                val pubKey = payloadContent.substringAfter("|PUBKEY:").substringBefore("|SIG:")
                val signature = payloadContent.substringAfter("|SIG:")

                val amountStr = rawData.substringAfter("AMOUNT:").substringBefore("|SENDER:")
                val senderSmartId = rawData.substringAfter("SENDER:").substringBefore("|TIME:")
                val receivedNonce = rawData.substringAfter("NONCE:")
                val amount = amountStr.toLongOrNull() ?: 0L

                Log.d("CryptoDemo", "🕵️‍♂️ Checking for Replay Attacks...")
                if (receivedNonce != activeNonce) {
                    Log.e("CryptoDemo", "🚨 REPLAY ATTACK PREVENTED! Nonce mismatch.")
                    connectionsClient.disconnectFromEndpoint(endpointId)
                    return
                }
                Log.d("CryptoDemo", "✅ Nonce matches. No replay attack detected.")

                val isValid = CryptoEngine.verifySignature(pubKey, rawData, signature)
                if (!isValid) {
                    Log.e("CryptoDemo", "🚨 FORGERY DETECTED! Signature Verification Failed.")
                    connectionsClient.disconnectFromEndpoint(endpointId)
                    return
                }

                Log.d("CryptoDemo", "🎉 ZERO-TRUST VERIFIED: Executing local ledger update!")

                if (amount > 0) {
                    // ... (keep your existing Database CreditDao saving code here) ...
                    CoroutineScope(Dispatchers.IO).launch {
                        val db = DatabaseProvider.getDatabase(context)
                        val routedVA = if (currentVirtualAccount.isNotEmpty()) currentVirtualAccount else {
                            db.creditDao().getPrimaryWalletId(currentUid) ?: "${currentUid.take(8).uppercase()}-00"
                        }

                        db.creditDao().addTransaction(CreditEntry(
                            userId = currentUid, amount = amount, senderId = senderSmartId,
                            timestamp = System.currentTimeMillis(), transactionHash = "TX_NEARBY_${System.currentTimeMillis()}",
                            previousHash = "NEARBY_PROXIMITY", isSynced = false, targetVirtualAccount = routedVA
                        ))

                        // ✨ STATE 4: Inform Sender of success
                        connectionsClient.sendPayload(endpointId, Payload.fromBytes("PAYS_CONFIRMATION_SIGNAL_SUCCESS".toByteArray()))
                        delay(500)
                        connectionsClient.disconnectFromEndpoint(endpointId)

                        (context as? MainActivity)?.runOnUiThread {
                            (context as? MainActivity)?.viewModel?.refreshDataForUser(currentUid)
                            onTransferComplete?.invoke(amount, false)
                        }
                    }
                }
            }

            // ✨ STATE 5: Sender gets confirmation, drops connection, updates UI
            else if (receivedString == "PAYS_CONFIRMATION_SIGNAL_SUCCESS") {
                connectionsClient.disconnectFromEndpoint(endpointId)
                (context as? MainActivity)?.runOnUiThread {
                    onTransferComplete?.invoke(amountInTransit, true)
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.FAILURE) {
                (context as? MainActivity)?.runOnUiThread { onTransferComplete?.invoke(-1L, false) }
            }
        }
    }

    private fun sendSecureCreditPacket(endpointId: String, amount: Long, nonce: String) {
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

                val myName = prefs.getString("user_name", "Anonymous") ?: "Anonymous"
                val myPhone = prefs.getString("phone_number", "") ?: ""
                val myAcc = prefs.getString("account_number", "") ?: ""
                val mySmartId = "$myName|$myPhone|$myAcc"

                val timestamp = System.currentTimeMillis()

                // ✨ Construct the core data
                val rawData = "AMOUNT:$amount|SENDER:$mySmartId|TIME:$timestamp|NONCE:$nonce"

                // ✨ Pull the Public Key and Sign the data mathematically via TEE
                val pubKey = CryptoEngine.getPublicKeyBase64() // <--- PARENTHESES GO HERE!
                val signature = CryptoEngine.generateSignature(rawData)

                // Wrap the payload
                val finalPacket = "PAYLOAD:$rawData|PUBKEY:$pubKey|SIG:$signature"

                connectionsClient.sendPayload(endpointId, Payload.fromBytes(finalPacket.toByteArray()))

                val rawTarget = (context as? MainActivity)?.scannedTargetName ?: "Receiver"
                val cleanTargetId = if (rawTarget.startsWith("paysetu://")) {
                    try {
                        val uri = android.net.Uri.parse(rawTarget)
                        "${uri.getQueryParameter("name") ?: "Receiver"}|${uri.getQueryParameter("phone") ?: ""}|${uri.getQueryParameter("va") ?: ""}"
                    } catch (e: Exception) { rawTarget }
                } else { rawTarget }

                db.creditDao().addTransaction(CreditEntry(
                    userId = currentUid, amount = -amount, senderId = cleanTargetId,
                    timestamp = timestamp, transactionHash = "SENT_${timestamp}",
                    previousHash = "NEARBY_PROXIMITY", isSynced = false, targetVirtualAccount = activeWalletAtMomentOfSend
                ))

                pendingAmount = 0L

                (context as? MainActivity)?.runOnUiThread {
                    (context as? MainActivity)?.viewModel?.refreshDataForUser(currentUid)
                }
            } catch (e: Exception) {
                Log.e("P2P_LOG", "Debit failed: ${e.message}")
            }
        }
    }
}