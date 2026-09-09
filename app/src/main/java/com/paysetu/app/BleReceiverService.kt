package com.paysetu.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import com.google.firebase.auth.FirebaseAuth
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("MissingPermission")
class BleReceiverService : Service() {

    companion object {
        const val CHANNEL_ID = "BleReceiverChannel"
        const val NOTIFICATION_ID = 1001

        val SERVICE_UUID: UUID = UUID.fromString("0000FA10-0000-1000-8000-00805F9B34FB")
        val CHAR_NONCE_UUID: UUID = UUID.fromString("0000FA11-0000-1000-8000-00805F9B34FB")
        val CHAR_PAYLOAD_UUID: UUID = UUID.fromString("0000FA12-0000-1000-8000-00805F9B34FB")
        val CHAR_ACK_UUID: UUID = UUID.fromString("0000FA13-0000-1000-8000-00805F9B34FB")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        // Expose lists of recent transactions and state flows for the UI to observe
        var onTransferCompletedCallback: ((Long, String) -> Unit)? = null
        var onStatusUpdated: ((String) -> Unit)? = null

        // In-memory LRU cache to check txId of incoming payloads to instantly drop replay attacks.
        private const val MAX_LRU_CACHE_SIZE = 100
        private val processedTxIds = Collections.synchronizedMap(object : LinkedHashMap<String, Boolean>(MAX_LRU_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Boolean>?): Boolean {
                return size > MAX_LRU_CACHE_SIZE
            }
        })
    }

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null

    private var originalBluetoothName: String? = null
    private var activeNonce: String = ""

    // Text to Speech
    private var tts: TextToSpeech? = null
    private var audioManager: AudioManager? = null
    private var activeFocusRequest: AudioFocusRequest? = null

    // Buffer to handle reliable long writes (prepared writes) for each device
    private val deviceWriteBuffers = ConcurrentHashMap<String, ByteArrayOutputStream>()
    private val deviceExpectedSizes = ConcurrentHashMap<String, Int>()

    // Tracking connected devices to send notifications
    private val connectedDevices = ConcurrentHashMap.newKeySet<BluetoothDevice>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startServiceForeground()
        initializeBluetooth()
        initializeTts()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val smartId = intent?.getStringExtra("smartId") ?: "Anonymous"
        val activeVa = intent?.getStringExtra("activeVirtualAccount") ?: ""
        
        Log.d("BleReceiverService", "Service started. smartId: $smartId, activeVa: $activeVa")
        
        startAdvertisingAndGattServer(smartId)
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "PaySetu Background Receiver",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun startServiceForeground() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PaySetu Proximity Receiver")
            .setContentText("Zero-touch proximity receiver is listening for payments...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun initializeBluetooth() {
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        originalBluetoothName = bluetoothAdapter?.name
    }

    private fun initializeTts() {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d("BleReceiverService", "TTS started speaking: $utteranceId")
                    }

                    override fun onDone(utteranceId: String?) {
                        Log.d("BleReceiverService", "TTS completed speaking: $utteranceId")
                        abandonAudioFocus()
                    }

                    override fun onError(utteranceId: String?) {
                        Log.e("BleReceiverService", "TTS error: $utteranceId")
                        abandonAudioFocus()
                    }
                })
            } else {
                Log.e("BleReceiverService", "Failed to initialize TTS engine")
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        audioManager ?: return false
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { }
                .build()
            activeFocusRequest = focusRequest
            return audioManager?.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            return audioManager?.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activeFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(null)
        }
    }

    private fun speakSequential(message: String) {
        if (requestAudioFocus()) {
            val params = android.os.Bundle()
            val utteranceId = "PAYMENT_TTS_${System.currentTimeMillis()}"
            tts?.speak(message, TextToSpeech.QUEUE_ADD, params, utteranceId)
        }
    }

    private fun startAdvertisingAndGattServer(smartId: String) {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Log.e("BleReceiverService", "Bluetooth adapter not enabled or not found.")
            onStatusUpdated?.invoke("Bluetooth disabled")
            return
        }

        // Format name to fit within BLE advertising limits: "Name|Phone" (e.g. John|9876543210)
        val parts = smartId.split("|")
        val name = parts.getOrNull(0) ?: "User"
        val phone = parts.getOrNull(1) ?: ""
        val shortName = name.take(15)
        val nameToAdvertise = if (phone.isNotEmpty()) "$shortName|$phone" else shortName

        try {
            adapter.name = nameToAdvertise
            Log.d("BleReceiverService", "Bluetooth name set to: $nameToAdvertise")
        } catch (e: Exception) {
            Log.w("BleReceiverService", "Failed to set Bluetooth device name: ${e.message}")
        }

        // 1. Setup GATT Server
        val server = bluetoothManager?.openGattServer(this, gattServerCallback)
        if (server == null) {
            Log.e("BleReceiverService", "Failed to open GATT Server.")
            onStatusUpdated?.invoke("GATT initialization failed")
            return
        }
        gattServer = server

        val gattService = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        // Nonce Characteristic (Read)
        val nonceChar = BluetoothGattCharacteristic(
            CHAR_NONCE_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        gattService.addCharacteristic(nonceChar)

        // Payload Characteristic (Write)
        val payloadChar = BluetoothGattCharacteristic(
            CHAR_PAYLOAD_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        gattService.addCharacteristic(payloadChar)

        // ACK Characteristic (Notify/Indicate)
        val ackChar = BluetoothGattCharacteristic(
            CHAR_ACK_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val cccdDescriptor = BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_WRITE)
        ackChar.addDescriptor(cccdDescriptor)
        gattService.addCharacteristic(ackChar)

        server.clearServices()
        server.addService(gattService)

        // 2. Start Advertising
        advertiser = adapter.bluetoothLeAdvertiser
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false) // Fit Service UUID in primary packet
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true) // Fit device name in scan response
            .build()

        advertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
        onStatusUpdated?.invoke("Broadcasting (Listening)")
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d("BleReceiverService", "BLE Advertiser started successfully.")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e("BleReceiverService", "BLE Advertiser failed with error code: $errorCode")
            onStatusUpdated?.invoke("Advertiser error $errorCode")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("BleReceiverService", "Device connected: ${device.address}")
                connectedDevices.add(device)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d("BleReceiverService", "Device disconnected: ${device.address}")
                connectedDevices.remove(device)
                deviceWriteBuffers.remove(device.address)
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == CHAR_NONCE_UUID) {
                Log.d("BleReceiverService", "GATT Server: Nonce read request from ${device.address}")
                activeNonce = CryptoEngine.generateNonce()
                val responseBytes = activeNonce.toByteArray(Charsets.UTF_8)
                val slicedResponse = if (offset < responseBytes.size) responseBytes.copyOfRange(offset, responseBytes.size) else ByteArray(0)
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slicedResponse)
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid == CHAR_PAYLOAD_UUID) {
                var buffer = deviceWriteBuffers[device.address]
                if (buffer == null) {
                    buffer = ByteArrayOutputStream()
                    deviceWriteBuffers[device.address] = buffer
                }

                try {
                    var expectedSize = deviceExpectedSizes[device.address] ?: -1
                    
                    if (expectedSize == -1) {
                        // First chunk: parse expected size
                        val valueStr = String(value, Charsets.UTF_8)
                        val colonIndex = valueStr.indexOf(':')
                        if (colonIndex != -1) {
                            val sizeStr = valueStr.substring(0, colonIndex)
                            expectedSize = sizeStr.toIntOrNull() ?: -1
                            if (expectedSize != -1) {
                                deviceExpectedSizes[device.address] = expectedSize
                                val prefixBytesLength = sizeStr.toByteArray(Charsets.UTF_8).size + 1
                                if (value.size > prefixBytesLength) {
                                    buffer.write(value, prefixBytesLength, value.size - prefixBytesLength)
                                }
                            }
                        }
                    } else {
                        buffer.write(value)
                    }

                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                    }

                    if (expectedSize != -1 && buffer.size() >= expectedSize) {
                        val fullData = buffer.toByteArray()
                        deviceWriteBuffers.remove(device.address)
                        deviceExpectedSizes.remove(device.address)
                        processPayload(device, String(fullData, Charsets.UTF_8))
                    }
                } catch (e: Exception) {
                    Log.e("BleReceiverService", "Error during write characteristic request: ${e.message}")
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                    }
                }
            } else {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
            } else {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                }
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            val buffer = deviceWriteBuffers.remove(device.address)
            if (execute && buffer != null) {
                val fullData = buffer.toByteArray()
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                processPayload(device, String(fullData, Charsets.UTF_8))
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }
    }

    private fun processPayload(device: BluetoothDevice, payloadContent: String) {
        if (!payloadContent.startsWith("PAYLOAD:")) {
            Log.e("BleReceiverService", "Invalid payload format prefix")
            return
        }
        
        Log.d("BleReceiverService", "Processing received secure payload...")
        val content = payloadContent.substringAfter("PAYLOAD:")
        val rawData = content.substringBefore("|PUBKEY:")
        val pubKey = content.substringAfter("|PUBKEY:").substringBefore("|SIG:")
        val signature = content.substringAfter("|SIG:")

        val amountStr = rawData.substringAfter("AMOUNT:").substringBefore("|SENDER:")
        val senderSmartId = rawData.substringAfter("SENDER:").substringBefore("|TIME:")
        val timestampStr = rawData.substringAfter("TIME:").substringBefore("|NONCE:")
        val receivedNonce = rawData.substringAfter("NONCE:")
        
        val amount = amountStr.toLongOrNull() ?: 0L
        val timestamp = timestampStr.toLongOrNull() ?: 0L

        // Replay Protection Check: Construct transaction ID
        val txId = "$senderSmartId|$timestamp|$receivedNonce"
        if (processedTxIds.containsKey(txId)) {
            Log.e("BleReceiverService", "🚨 REPLAY ATTACK DETECTED: Transaction ID $txId already processed. Instantly dropping!")
            return
        }

        // Nonce Verification
        if (receivedNonce != activeNonce) {
            Log.e("BleReceiverService", "🚨 REPLAY ATTACK PREVENTED: Nonce mismatch. Expected $activeNonce, got $receivedNonce")
            return
        }

        // Cryptographic signature check
        val isValid = CryptoEngine.verifySignature(pubKey, rawData, signature)
        if (!isValid) {
            Log.e("BleReceiverService", "🚨 FORGERY DETECTED: Cryptographic signature verification failed!")
            return
        }

        // Add to processed txIds cache
        processedTxIds[txId] = true

        Log.d("BleReceiverService", "🛡️ Zero-Trust payment verified. Writing to SQLCipher DB ledger...")

        CoroutineScope(Dispatchers.IO).launch {
            val db = DatabaseProvider.getDatabase(this@BleReceiverService)
            
            // Resolve Virtual Account routing
            val prefs = SecurityUtils.getSecurePrefs(this@BleReceiverService)
            val currentUid = FirebaseAuth.getInstance().currentUser?.uid ?: "unknown"
            val activeVirtualAccount = prefs.getString("active_wallet_id", "") ?: ""
            
            val routedVA = if (activeVirtualAccount.isNotEmpty()) activeVirtualAccount else {
                db.creditDao().getPrimaryWalletId(currentUid) ?: "${currentUid.take(8).uppercase()}-00"
            }

            val cleanSenderName = senderSmartId.split("|").firstOrNull() ?: "Proximity Sender"

            // Save to Ledger
            db.creditDao().addTransaction(CreditEntry(
                userId = currentUid,
                amount = amount,
                senderId = senderSmartId,
                timestamp = System.currentTimeMillis(),
                transactionHash = "TX_BLE_${System.currentTimeMillis()}",
                previousHash = "BLE_PROXIMITY",
                isSynced = false,
                targetVirtualAccount = routedVA
            ))

            // Trigger success callback to update main screen
            CoroutineScope(Dispatchers.Main).launch {
                onTransferCompletedCallback?.invoke(amount, cleanSenderName)
            }

            // Push standard heads-up phone notification
            showPaymentNotification(amount, cleanSenderName)

            // Play voice alert sequentially
            speakSequential("Received payment of $amount rupees from $cleanSenderName")

            // Wait a brief moment, then push confirmation ACK notification back to Sender
            delay(300)
            sendAckNotification(device)
        }
    }

    private fun showPaymentNotification(amount: Long, senderName: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val paymentChannelId = "PaySetuPaymentsChannel"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                paymentChannelId,
                "Payment Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts for incoming proximity payments"
                enableLights(true)
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 
            0, 
            notificationIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notificationId = (System.currentTimeMillis() % 100000).toInt() + 2000
        val notification = NotificationCompat.Builder(this, paymentChannelId)
            .setContentTitle("Payment Received")
            .setContentText("Received ₹$amount from $senderName")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(notificationId, notification)
    }

    private fun sendAckNotification(device: BluetoothDevice) {
        val server = gattServer ?: return
        val service = server.getService(SERVICE_UUID) ?: return
        val ackCharacteristic = service.getCharacteristic(CHAR_ACK_UUID) ?: return

        val ackValue = "PAYS_CONFIRMATION_SIGNAL_SUCCESS".toByteArray(Charsets.UTF_8)

        // Send Notification/Indication
        Log.d("BleReceiverService", "Sending success ACK to sender ${device.address}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            server.notifyCharacteristicChanged(device, ackCharacteristic, true, ackValue)
        } else {
            @Suppress("DEPRECATION")
            ackCharacteristic.value = ackValue
            @Suppress("DEPRECATION")
            server.notifyCharacteristicChanged(device, ackCharacteristic, true)
        }
    }

    override fun onDestroy() {
        stopAdvertisingAndGattServer()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun stopAdvertisingAndGattServer() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
            gattServer?.close()
        } catch (e: Exception) {
            Log.e("BleReceiverService", "Error shutting down BLE advertiser or GATT server: ${e.message}")
        }

        // Restore original Bluetooth name
        originalBluetoothName?.let {
            try {
                bluetoothAdapter?.name = it
            } catch (e: Exception) {
                Log.e("BleReceiverService", "Error restoring original name: ${e.message}")
            }
        }
        onStatusUpdated?.invoke("Offline")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
