package com.paysetu.app

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000FA10-0000-1000-8000-00805F9B34FB")
        val CHAR_NONCE_UUID: UUID = UUID.fromString("0000FA11-0000-1000-8000-00805F9B34FB")
        val CHAR_PAYLOAD_UUID: UUID = UUID.fromString("0000FA12-0000-1000-8000-00805F9B34FB")
        val CHAR_ACK_UUID: UUID = UUID.fromString("0000FA13-0000-1000-8000-00805F9B34FB")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
        
        private const val RSSI_GATE = -55
    }

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null

    var pendingAmount: Long = 0L
    var currentVirtualAccount: String = ""
    val currentUid = FirebaseAuth.getInstance().currentUser?.uid ?: "unknown"

    var onDeviceFound: ((String, String) -> Unit)? = null
    var onTransferComplete: ((Long, Boolean) -> Unit)? = null

    private var isScanning = false
    private var scannedDeviceAddress: String? = null

    // Nonce stored from the receiver
    private var activeNonce: String = ""

    // Chunked writing state
    private var writeBuffer: ByteArray? = null
    private var writeIndex = 0
    private var negotiatedMtu = 23

    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
    }

    fun startDiscovery() {
        forceKillEverything()
        
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Log.e("BleManager", "Bluetooth adapter disabled or not found.")
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            Log.e("BleManager", "BLE Scanner is not available.")
            return
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        isScanning = true
        Log.d("BleManager", "Starting BLE discovery with RSSI threshold $RSSI_GATE dBm...")
        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val rssi = result.rssi
            Log.d("BleManager", "Discovered device: ${device.name} [${device.address}], RSSI: $rssi")

            // Strict RSSI Gating
            if (rssi >= RSSI_GATE) {
                // Try reading device name from scanRecord first as it is more reliable in real-time scans
                val name = result.scanRecord?.deviceName ?: device.name ?: "PaySetu Receiver"
                Log.d("BleManager", "Reporting device: $name [${device.address}]")
                mainHandler.post {
                    onDeviceFound?.invoke(device.address, name)
                }
            } else {
                Log.d("BleManager", "Ignored device ${device.address} due to weak RSSI: $rssi")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("BleManager", "BLE scan failed with error code: $errorCode")
        }
    }

    fun connectToDevice(deviceAddress: String) {
        // Stop scanning immediately before connection
        stopScanning()

        scannedDeviceAddress = deviceAddress
        val adapter = bluetoothAdapter
        if (adapter == null) {
            Log.e("BleManager", "Bluetooth adapter null during connection.")
            return
        }

        val device = adapter.getRemoteDevice(deviceAddress)
        Log.d("BleManager", "Connecting GATT to ${device.address}...")
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e("BleManager", "GATT connection status error: $status. Closing...")
                closeGatt()
                mainHandler.post { onTransferComplete?.invoke(-1L, false) }
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("BleManager", "GATT Connected. Requesting 512-byte MTU...")
                val success = gatt.requestMtu(512)
                if (!success) {
                    Log.w("BleManager", "Failed to initiate MTU request, starting service discovery directly...")
                    gatt.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d("BleManager", "GATT Disconnected.")
                closeGatt()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
                Log.d("BleManager", "MTU successfully negotiated: $mtu")
            } else {
                Log.w("BleManager", "MTU negotiation failed with status: $status. Using default: $negotiatedMtu")
            }
            Log.d("BleManager", "Initiating service discovery...")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                if (service != null) {
                    val nonceChar = service.getCharacteristic(CHAR_NONCE_UUID)
                    if (nonceChar != null) {
                        Log.d("BleManager", "GATT Handshake [Step 1]: Reading Nonce from Receiver...")
                        gatt.readCharacteristic(nonceChar)
                    } else {
                        Log.e("BleManager", "Nonce characteristic not found on server")
                        failTransaction()
                    }
                } else {
                    Log.e("BleManager", "PaySetu BLE Service not found on server")
                    failTransaction()
                }
            } else {
                Log.e("BleManager", "Service discovery failed with status: $status")
                failTransaction()
            }
        }

        private fun handleCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == CHAR_NONCE_UUID) {
                val nonceValue = String(value, Charsets.UTF_8)
                activeNonce = nonceValue
                Log.d("BleManager", "GATT Handshake [Step 2]: Nonce retrieved: $activeNonce")
                
                // Now register for notifications on the ACK characteristic before writing
                val service = gatt.getService(SERVICE_UUID)
                val ackChar = service?.getCharacteristic(CHAR_ACK_UUID)
                if (ackChar != null) {
                    Log.d("BleManager", "GATT Handshake [Step 3]: Enabling ACK notifications...")
                    gatt.setCharacteristicNotification(ackChar, true)
                    val descriptor = ackChar.getDescriptor(CCCD_UUID)
                    if (descriptor != null) {
                        val success = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) == 0
                        } else {
                            @Suppress("DEPRECATION")
                            descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                            @Suppress("DEPRECATION")
                            gatt.writeDescriptor(descriptor)
                        }
                        if (!success) {
                            Log.e("BleManager", "Failed to write descriptor to enable notifications")
                            failTransaction()
                        }
                    } else {
                        Log.e("BleManager", "CCCD descriptor is null on ACK characteristic!")
                        failTransaction()
                    }
                } else {
                    Log.e("BleManager", "ACK characteristic not found")
                    failTransaction()
                }
            } else {
                Log.e("BleManager", "Failed to read nonce characteristic, status: $status")
                failTransaction()
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            handleCharacteristicRead(gatt, characteristic, characteristic.value ?: ByteArray(0), status)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            handleCharacteristicRead(gatt, characteristic, value, status)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && descriptor.uuid == CCCD_UUID) {
                Log.d("BleManager", "GATT Handshake [Step 4]: ACK notifications enabled. Starting payload write...")
                sendSecureCreditPacket(gatt)
            } else {
                Log.e("BleManager", "Failed to write descriptor to enable notifications, status: $status")
                failTransaction()
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == CHAR_PAYLOAD_UUID) {
                writeNextChunk(gatt, characteristic)
            } else {
                Log.e("BleManager", "Characteristic write chunk failed, status: $status")
                failTransaction()
            }
        }

        private fun handleCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == CHAR_ACK_UUID) {
                val ackValue = String(value, Charsets.UTF_8)
                Log.d("BleManager", "GATT Handshake [Step 5]: Received ACK response: $ackValue")
                if (ackValue == "PAYS_CONFIRMATION_SIGNAL_SUCCESS") {
                    handleTransactionSuccess()
                } else {
                    Log.e("BleManager", "Unexpected ACK value: $ackValue")
                    failTransaction()
                }
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleCharacteristicChanged(gatt, characteristic, characteristic.value ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicChanged(gatt, characteristic, value)
        }
    }

    private fun sendSecureCreditPacket(gatt: BluetoothGatt) {
        val service = gatt.getService(SERVICE_UUID) ?: return
        val payloadChar = service.getCharacteristic(CHAR_PAYLOAD_UUID) ?: return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = SecurityUtils.getSecurePrefs(context)
                val myName = prefs.getString("user_name", "Anonymous") ?: "Anonymous"
                val myPhone = prefs.getString("phone_number", "") ?: ""
                val myAcc = prefs.getString("account_number", "") ?: ""
                val mySmartId = "$myName|$myPhone|$myAcc"

                val timestamp = System.currentTimeMillis()

                // Construct raw payload
                val rawData = "AMOUNT:$pendingAmount|SENDER:$mySmartId|TIME:$timestamp|NONCE:$activeNonce"

                // Mathematically sign rawData using RSA in Hardware Keystore TEE
                val pubKey = CryptoEngine.getPublicKeyBase64()
                val signature = CryptoEngine.generateSignature(rawData)

                val finalPacket = "PAYLOAD:$rawData|PUBKEY:$pubKey|SIG:$signature"
                val packetBytes = finalPacket.toByteArray(Charsets.UTF_8)
                val prefixedPacket = "${packetBytes.size}:".toByteArray(Charsets.UTF_8) + packetBytes
                
                // Initialize chunked write buffers
                writeBuffer = prefixedPacket
                writeIndex = 0
                
                mainHandler.post {
                    writeNextChunk(gatt, payloadChar)
                }
            } catch (e: Exception) {
                Log.e("BleManager", "Failed to generate signed payment packet: ${e.message}")
                mainHandler.post { failTransaction() }
            }
        }
    }

    private fun writeNextChunk(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val buffer = writeBuffer ?: return
        val remaining = buffer.size - writeIndex
        
        if (remaining <= 0) {
            Log.d("BleManager", "Secure packet payload transmission complete. Waiting for ACK...")
            writeBuffer = null
            return
        }

        // Adjust write size dynamically based on negotiated MTU.
        // The maximum payload size in a single GATT write is (MTU - 3) bytes.
        // We cap it at 509 bytes to avoid exceeding the BluetoothGatt 512-byte max attribute length limit.
        val maxChunkSize = Math.min(509, Math.max(20, negotiatedMtu - 3))
        val chunkSize = Math.min(remaining, maxChunkSize)
        val chunk = buffer.copyOfRange(writeIndex, writeIndex + chunkSize)
        
        val success = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = chunk
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
        if (success) {
            writeIndex += chunkSize
        } else {
            Log.e("BleManager", "gatt.writeCharacteristic failed to enqueue chunk write")
            failTransaction()
        }
    }

    private fun handleTransactionSuccess() {
        val amount = pendingAmount
        CoroutineScope(Dispatchers.IO).launch {
            val db = DatabaseProvider.getDatabase(context)
            val prefs = SecurityUtils.getSecurePrefs(context)
            
            val activeWalletAtMomentOfSend = currentVirtualAccount.ifEmpty {
                val acc = prefs.getString("account_number", "") ?: ""
                if (acc.isNotEmpty()) "${acc.take(8).uppercase()}-00" else "UNKNOWN-00"
            }

            val rawTarget = (context as? MainActivity)?.scannedTargetName ?: "Receiver"
            val cleanTargetId = if (rawTarget.startsWith("paysetu://")) {
                try {
                    val uri = android.net.Uri.parse(rawTarget)
                    "${uri.getQueryParameter("name") ?: "Receiver"}|${uri.getQueryParameter("phone") ?: ""}|${uri.getQueryParameter("va") ?: ""}"
                } catch (e: Exception) { rawTarget }
            } else { rawTarget }

            // Write Sender Debit to SQLCipher Ledger
            val timestamp = System.currentTimeMillis()
            db.creditDao().addTransaction(CreditEntry(
                userId = currentUid,
                amount = -amount,
                senderId = cleanTargetId,
                timestamp = timestamp,
                transactionHash = "SENT_${timestamp}",
                previousHash = "BLE_PROXIMITY",
                isSynced = false,
                targetVirtualAccount = activeWalletAtMomentOfSend
            ))

            // Teardown GATT connection to prevent Error 133
            disconnectGatt()

            mainHandler.post {
                (context as? MainActivity)?.viewModel?.refreshDataForUser(currentUid)
                onTransferComplete?.invoke(amount, true)
            }
        }
    }

    private fun failTransaction() {
        disconnectGatt()
        mainHandler.post {
            onTransferComplete?.invoke(-1L, false)
        }
    }

    private fun stopScanning() {
        if (isScanning) {
            try {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (e: Exception) {
                Log.e("BleManager", "Error stopping scanner: ${e.message}")
            }
            isScanning = false
        }
    }

    private fun disconnectGatt() {
        bluetoothGatt?.let { gatt ->
            Log.d("BleManager", "Disconnecting GATT client...")
            gatt.disconnect()
        }
    }

    private fun closeGatt() {
        bluetoothGatt?.let { gatt ->
            Log.d("BleManager", "Closing GATT client...")
            gatt.close()
        }
        bluetoothGatt = null
        writeBuffer = null
        writeIndex = 0
        activeNonce = ""
    }

    fun forceKillEverything() {
        stopScanning()
        disconnectGatt()
        closeGatt()
        pendingAmount = 0L
    }
}
