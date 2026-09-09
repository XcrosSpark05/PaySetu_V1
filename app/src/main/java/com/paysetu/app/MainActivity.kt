package com.paysetu.app

import androidx.activity.compose.BackHandler
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.graphics.toArgb
import android.Manifest
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import android.widget.Toast
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.paysetu.app.ui.theme.OfflineVaultTheme
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.delay
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.view.PreviewView
import androidx.camera.view.LifecycleCameraController
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.launch
import com.google.firebase.auth.FirebaseAuth
import androidx.compose.ui.graphics.Brush
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.GoogleAuthProvider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.window.Dialog
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import android.telephony.SmsManager
import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import androidx.annotation.RequiresApi
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// Navigation State
enum class AuthScreen { START, LOGIN, SIGNUP, OTP }

class MainActivity : FragmentActivity() {

    fun cleanupStalePendingTransactions() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val db = DatabaseProvider.getDatabase(this)

        lifecycleScope.launch(Dispatchers.IO) {
            val pendingTxs = db.creditDao().getPendingTransactions()
            val now = System.currentTimeMillis()
            val tenMinutes = 10 * 60 * 1000L

            for (tx in pendingTxs) {

                // ✨ BULLETPROOF CHECK 1: If the Hub already marked this as Refunded/Blocked, KILL the Pending state!
                if (tx.note?.contains("REFUNDED") == true || tx.note?.contains("BLOCKED") == true) {
                    db.creditDao().updateTransactionSmartId(
                        tx.transactionHash,
                        tx.senderId.replace("Pending", "Voided")
                    )
                    continue
                }

                if (now - tx.timestamp > tenMinutes) {
                    val timeoutRefundHash = "REFUND_${tx.transactionHash}"
                    val hubRefundHash = "REF_${tx.transactionHash}" // ✨ The hash the Hub uses!

                    try {
                        // ✨ BULLETPROOF CHECK 2: Look for BOTH types of refunds
                        val alreadyTimedOut = db.creditDao().getTransactionByHash(timeoutRefundHash)
                        val alreadyHubRefunded = db.creditDao().getTransactionByHash(hubRefundHash)

                        if (alreadyTimedOut != null || alreadyHubRefunded != null) {
                            // The money was already returned. Just kill the pending state.
                            db.creditDao().updateTransactionSmartId(tx.transactionHash, tx.senderId.replace("Pending", "Voided"))
                            continue
                        }

                        // --- Normal Timeout Execution (Only happens if NO refund exists) ---
                        db.creditDao().updateTransactionSmartId(
                            tx.transactionHash,
                            tx.senderId.replace("Pending", "Expired")
                        )

                        val refundEntry = CreditEntry(
                            userId = uid,
                            amount = kotlin.math.abs(tx.amount),
                            senderId = "SYSTEM_REFUND|Expired Link",
                            timestamp = System.currentTimeMillis(),
                            transactionHash = timeoutRefundHash,
                            previousHash = "TIMEOUT",
                            isSynced = false,
                            targetVirtualAccount = tx.targetVirtualAccount
                        )
                        db.creditDao().addTransaction(refundEntry)

                        db.creditDao().addNotification(NotificationEntry(
                            userId = uid, title = "Refund Issued", message = "₹${kotlin.math.abs(tx.amount)} restored. Link expired.", timestamp = System.currentTimeMillis()
                        ))
                    } catch (e: Exception) {
                        Log.e("Vault", "Double refund blocked by DB integrity")
                    }
                }
            }
        }
    }
    private lateinit var nearbyManager: NearbyManager
    var viewModel: VaultViewModel? = null
    private lateinit var authViewModel: AuthViewModel
    private lateinit var authRepository: AuthRepository

    private var isScanningQR by mutableStateOf(false)
    var scannedTargetName by mutableStateOf<String?>(null)
    private var isProcessing by mutableStateOf(false)

    // Firebase Auth State
    private var tempUsername: String = ""
    private var tempPhone: String = ""
    private var tempEmail: String = ""

    // State to track if the vault is currently "unlocked" for this session
    private var isVaultUnlocked by mutableStateOf(false)
    private lateinit var syncManager: SyncManager
    private val _pendingChatNavigation = MutableStateFlow<String?>(null)
    private var showSuccessOverlay by mutableStateOf<Long?>(null)
    private var isSenderRole by mutableStateOf(false)
    private var showSmsDialog by mutableStateOf(false)

    // UI Navigation State
    private var showProfile by mutableStateOf(false)
    private var currentUserName by mutableStateOf("User")
    private var currentPhone by mutableStateOf("")


    private val refreshReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
            if (uid != null) {
                viewModel?.refreshDataForUser(uid)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        registerReceiver(
            refreshReceiver,
            android.content.IntentFilter("com.paysetu.REFRESH_UI"),
            android.content.Context.RECEIVER_EXPORTED
        )
        enableEdgeToEdge()
        createNotificationChannel()
        intent?.getStringExtra("OPEN_CHAT_ID")?.let { _pendingChatNavigation.value = it }

        enableEdgeToEdge()
        createNotificationChannel()

        val db = DatabaseProvider.getDatabase(this)
        nearbyManager = NearbyManager(this)
        viewModel = VaultViewModel(db.creditDao())

        FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
            viewModel?.refreshDataForUser(uid)
        }

        authRepository = AuthRepository(this)
        authViewModel = AuthViewModel(authRepository)
        authViewModel.checkUserStatus()

        syncManager = SyncManager(this)
        syncManager.startMonitoring()
        OfflinePaymentHelper.processSmsQueue(this)

        val prefs = SecurityUtils.getSecurePrefs(this)
        currentUserName = prefs.getString("user_name", "User") ?: "User"
        currentPhone = prefs.getString("phone_number", "") ?: ""

        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.addAll(listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){}.launch(perms.toTypedArray())

        setContent {
            OfflineVaultTheme {
                val pickImageLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.GetContent()
                ) { uri -> uri?.let { uploadProfileImage(it) } }
                val loginState by authViewModel.loginState.collectAsState()
                val balance by (viewModel?.balance?.collectAsState() ?: remember { mutableStateOf(0L) })
                val history by (viewModel?.history?.collectAsState() ?: remember { mutableStateOf(emptyList()) })
                val notifications by (viewModel?.notifications?.collectAsState(initial = emptyList()) ?: remember { mutableStateOf(emptyList()) })
                val wallets by viewModel!!.wallets.collectAsState()
                val activeVirtualAccountId by viewModel!!.activeVirtualAccountId.collectAsState()
                val activeWallet = wallets.find { it.virtualAccountId == activeVirtualAccountId }
                val pendingNavTarget by _pendingChatNavigation.collectAsState()



                val themeColor by remember(activeWallet) {
                    derivedStateOf {
                        activeWallet?.let { Color(it.themeColor) } ?: Color(0xFFF9AA33)
                    }
                }
                var selectedThemeColor by remember { mutableStateOf(Color(0xFF50C878)) }
                var profilePicUrl by remember { mutableStateOf("") }


                var showTray by remember { mutableStateOf(false) }
                var selectedTransactionForDetails by remember { mutableStateOf<CreditEntry?>(null) }
                var currentScreen by remember { mutableStateOf(AuthScreen.START) }
                var selectedChatUser by remember { mutableStateOf<String?>(null) }
                var isDarkMode by remember { mutableStateOf(prefs.getBoolean("is_dark_mode", true)) }
                var hasUnreadNotifs by remember(notifications) { mutableStateOf(notifications.isNotEmpty()) }
                var showBuyCredits by remember { mutableStateOf(false) }
                var showWithdrawFunds by remember { mutableStateOf(false) }
                var activeTab by remember { mutableStateOf(0) }
                var isScanning by remember { mutableStateOf(false) }
                var isReceiving by remember { mutableStateOf(false) }
                var discoveredDevices by remember { mutableStateOf(mapOf<String, String>()) }
                var selectedDeviceId by remember { mutableStateOf<String?>(null) }
                var showAmountDialog by remember { mutableStateOf(false) }
                var amountText by remember { mutableStateOf("") }
                var targetPhone by remember { mutableStateOf("") }
                var targetVaForSms by remember { mutableStateOf("") }
                var showCustomSplash by remember { mutableStateOf(true) }
                var showGooglePhonePrompt by remember { mutableStateOf(false) }
                var showWalletSelector by remember { mutableStateOf(false) }
                val prefAcc = prefs.getString("account_number", "") ?: ""
                val mySmartId = "$currentUserName|$currentPhone|${activeVirtualAccountId}"
                var showPostScanChoice by remember { mutableStateOf(false) }
                var pendingScanResult by remember { mutableStateOf<android.net.Uri?>(null) }
                val context = LocalContext.current

                LaunchedEffect(isVaultUnlocked, pendingNavTarget) {
                    if (isVaultUnlocked && pendingNavTarget != null) {
                        selectedChatUser = pendingNavTarget // Now it knows what this is!
                        activeTab = 0                       // Now it knows what this is!
                        _pendingChatNavigation.value = null
                    }
                }

                val contactPickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == android.app.Activity.RESULT_OK) {
                        result.data?.data?.let { uri ->
                            val rawNumber = getPhoneNumberFromUri(context, uri)
                            if (rawNumber != null) {
                                targetPhone = rawNumber.replace(Regex("[^0-9]"), "").takeLast(10)
                            } else {
                                Toast.makeText(context, "No phone number found", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                val resetEverything = {
                    nearbyManager.forceKillEverything()
                    showSuccessOverlay = null
                    isScanning = false; isReceiving = false; isScanningQR = false
                    isProcessing = false; showAmountDialog = false
                    scannedTargetName = null; selectedDeviceId = null
                    discoveredDevices = emptyMap(); amountText = ""
                    isSenderRole = false; targetPhone = ""
                }
                val isSubScreenVisible = showProfile || showBuyCredits || showSmsDialog ||
                        showAmountDialog || showTray || selectedTransactionForDetails != null ||
                        selectedChatUser != null || isScanningQR || isReceiving || isScanning ||
                        isProcessing || showSuccessOverlay != null || activeTab == 1 ||
                        (loginState == LoginState.NOT_LOGGED_IN && currentScreen != AuthScreen.START)

                BackHandler(enabled = isSubScreenVisible) {
                    when {
                        loginState == LoginState.NOT_LOGGED_IN -> {
                            if (currentScreen == AuthScreen.OTP) currentScreen = AuthScreen.SIGNUP else currentScreen = AuthScreen.START
                        }
                        selectedTransactionForDetails != null -> selectedTransactionForDetails = null
                        selectedChatUser != null -> selectedChatUser = null
                        showTray -> showTray = false
                        showSmsDialog -> showSmsDialog = false
                        showAmountDialog -> resetEverything()
                        showSuccessOverlay != null -> resetEverything()
                        isScanningQR || isReceiving || isScanning || isProcessing -> resetEverything()
                        showBuyCredits -> showBuyCredits = false
                        showWithdrawFunds -> showWithdrawFunds = false
                        showProfile -> showProfile = false
                        activeTab == 1 -> activeTab = 0
                    }
                }
                val googleSignInLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                    try {
                        val account = task.getResult(Exception::class.java)
                        account?.idToken?.let { idToken ->
                            val credential = GoogleAuthProvider.getCredential(idToken, null)
                            FirebaseAuth.getInstance().signInWithCredential(credential)
                                .addOnSuccessListener { authResult ->
                                    val user = authResult.user
                                    tempUsername = user?.displayName ?: "User"
                                    tempEmail = user?.email ?: ""
                                    currentUserName = tempUsername
                                    showGooglePhonePrompt = true
                                }
                        }
                    } catch (e: Exception) { Toast.makeText(this@MainActivity, "Google Sign-In Failed", Toast.LENGTH_SHORT).show() }
                }
                LaunchedEffect(Unit) {
                    nearbyManager.onDeviceFound = { id, name ->
                        discoveredDevices = discoveredDevices + (id to name)
                        if (scannedTargetName != null && name == scannedTargetName) selectedDeviceId = id
                    }

                    nearbyManager.onTransferComplete = { amount, isSenderConfirmed ->
                        if (amount >= 0) {
                            this@MainActivity.isSenderRole = isSenderConfirmed
                            this@MainActivity.showSuccessOverlay = amount
                            triggerSuccessSequence()

                            // 1. Attempt standard internet sync if available (No SMS fallback)
                            syncManager.triggerImmediateSync()

                        } else {
                            isProcessing = false
                            Toast.makeText(this@MainActivity, "Link Lost", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0A0E21)) {
                    if (showCustomSplash) {
                        SplashScreen(onAnimationFinished = { showCustomSplash = false }, isDarkMode = isDarkMode, themeColor = themeColor)
                    }
                    else if (!isVaultUnlocked && FirebaseAuth.getInstance().currentUser != null) {
                        val biometricManager = BiometricManager.from(LocalContext.current)
                        val isDeviceSecure = remember { biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS }
                        LockedVaultOverlay(isDeviceSecure = isDeviceSecure, onBypass = { isVaultUnlocked = true }, onTriggerPrompt = { showVaultLock() }, isDarkMode = isDarkMode, themeColor = themeColor)
                    } else {
                        when (loginState) {
                            LoginState.SUCCESS -> {
                                if (showProfile) {
                                    ProfileScreen(
                                        onBack = { showProfile = false },
                                        onLogout = { FirebaseAuth.getInstance().signOut(); authViewModel.updateState(LoginState.NOT_LOGGED_IN); showProfile = false; currentScreen = AuthScreen.START },
                                        onPickImage = { pickImageLauncher.launch("image/*") },
                                        isDarkMode = isDarkMode,
                                        onThemeToggle = { isDarkMode = !isDarkMode; prefs.edit().putBoolean("is_dark_mode", isDarkMode).apply() },
                                        themeColor = themeColor,
                                        wallets = wallets, // Passing the wallets to the profile screen
                                        activeWallet = activeWallet
                                    )
                                } else {
                                    when {
                                        showBuyCredits -> BuyCreditsScreen(currentBalance = balance, onBack = { showBuyCredits = false }, onConfirmPurchase = { amount -> showBuyCredits = false; simulateBankTopUp(amount, activeVirtualAccountId) }, isDarkMode = isDarkMode, themeColor = themeColor)
                                        // ✨ NEW: The Withdraw Screen Trigger
                                        showWithdrawFunds -> WithdrawFundsScreen(
                                            currentBalance = balance,
                                            onBack = { showWithdrawFunds = false },
                                            onConfirmWithdrawal = { amount ->
                                                showWithdrawFunds = false
                                                simulateBankWithdrawal(amount, activeVirtualAccountId)
                                            },
                                            isDarkMode = isDarkMode,
                                            themeColor = themeColor
                                        )
                                        showSuccessOverlay != null -> SuccessScreen(showSuccessOverlay!!, isSenderRole, resetEverything, isDarkMode = isDarkMode, themeColor = themeColor)
                                        isProcessing -> ProcessingScreen(isDarkMode = isDarkMode, themeColor = themeColor)
                                        isScanningQR -> QRScannerScreen(
                                            onCodeDetected = { content ->
                                                isScanningQR = false
                                                if (content.startsWith("paysetu://")) {
                                                    pendingScanResult = android.net.Uri.parse(content)
                                                    showPostScanChoice = true
                                                } else {
                                                    scannedTargetName = content
                                                    isSenderRole = true
                                                    lifecycleScope.launch {
                                                        nearbyManager.forceKillEverything()
                                                        delay(500)
                                                        nearbyManager.startDiscovery()
                                                        isScanning = true
                                                    }
                                                }
                                            },
                                            onCancel = { resetEverything() },
                                            themeColor = themeColor
                                        )
                                        isReceiving -> {
                                            val encodedName = android.net.Uri.encode(currentUserName)
                                            val dynamicNearbyQr = "paysetu://nearby?name=$encodedName&phone=$currentPhone&va=${activeVirtualAccountId}"
                                            ReceivingScreen(userName = dynamicNearbyQr, onStop = { resetEverything() }, isDarkMode = isDarkMode, themeColor = themeColor)
                                        }
                                        isScanning -> ScanningScreen(devices = discoveredDevices, onDeviceSelected = { id -> selectedDeviceId = id; showAmountDialog = true }, onCancel = { resetEverything() }, isDarkMode = isDarkMode, themeColor = themeColor)
                                        else -> {
                                            Crossfade(targetState = activeTab, animationSpec = tween(400), label = "TabTransition") { tab ->
                                                when {
                                                    selectedChatUser != null -> {
                                                        UserChatScreen(
                                                            targetName = selectedChatUser!!,
                                                            history = history,
                                                            onBack = { selectedChatUser = null },
                                                            onPayClick = {
                                                                val parts = selectedChatUser!!.split("|")
                                                                targetPhone = if (parts.size >= 2) parts[1] else parts.last()
                                                                targetVaForSms = if (parts.size >= 3) parts[2] else ""
                                                                selectedChatUser = null
                                                                showSmsDialog = true
                                                            },
                                                            isDarkMode = isDarkMode,
                                                            themeColor = themeColor
                                                        )
                                                    }
                                                    tab == 1 -> {
                                                        TransactionsScreen(
                                                            history = history,
                                                            wallets = wallets, // ✨ Make sure you add this!
                                                            userName = currentUserName,
                                                            onBack = { activeTab = 0 },
                                                            onTransactionClick = { tx -> selectedTransactionForDetails = tx },
                                                            isDarkMode = isDarkMode,
                                                            themeColor = themeColor
                                                        )
                                                    }
                                                    else -> {
                                                        Column(modifier = Modifier.fillMaxSize()) {
                                                            WalletDashboard(
                                                                balance = balance, history = history, userName = currentUserName,
                                                                activeWallet = activeWallet,
                                                                allWallets = wallets,
                                                                onOpenSelector = { showWalletSelector = true },
                                                                onReceiveClick = {
                                                                    ruggedReset(resetEverything) {
                                                                        nearbyManager.currentVirtualAccount = activeVirtualAccountId
                                                                        isReceiving = true
                                                                        nearbyManager.startAdvertising(mySmartId)
                                                                    }
                                                                },
                                                                onCheckCleanup = { cleanupStalePendingTransactions() },
                                                                onSendClick = {
                                                                    ruggedReset(resetEverything) {
                                                                        // ✨ MANDATORY: Sync the UI wallet with the Bluetooth manager
                                                                        nearbyManager.currentVirtualAccount = activeVirtualAccountId

                                                                        // Also save it to prefs as a backup
                                                                        SecurityUtils.getSecurePrefs(context).edit()
                                                                            .putString("active_wallet_id", activeVirtualAccountId).apply()

                                                                        isScanning = true
                                                                        isSenderRole = true
                                                                        nearbyManager.startDiscovery()
                                                                    }
                                                                },
                                                                onRemoteClick = { this@MainActivity.showSmsDialog = true },
                                                                onQRClick = { handleQRClick() },
                                                                onProfileClick = { showProfile = true },
                                                                onBellClick = { showTray = true; hasUnreadNotifs = false },
                                                                hasUnreadNotifs = hasUnreadNotifs, currentTab = activeTab, onTabChange = { activeTab = it },
                                                                profilePicUrl = profilePicUrl, onAmountChange = { amountText = it },
                                                                onTopUpClick = {
                                                                    showBuyCredits = true
                                                                },
                                                                // ✨ FIX 1: Passed the missing parameter to WalletDashboard!
                                                                onWithdrawClick = {
                                                                    showWithdrawFunds = true
                                                                },
                                                                onTransactionClick = { tx -> selectedTransactionForDetails = tx },
                                                                onPersonClick = { personName -> selectedChatUser = personName },
                                                                isDarkMode = isDarkMode,
                                                                themeColor = themeColor
                                                            )
                                                            BankActionsSection(
                                                                onAmountSelected = { amt -> amountText = amt.toString(); showAmountDialog = true; isSenderRole = false },
                                                                // ✨ FIX 2: Trigger the screen directly here!
                                                                onWithdrawClick = { showWithdrawFunds = true },
                                                                themeColor = themeColor
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            LoginState.NOT_LOGGED_IN -> {
                                Crossfade(targetState = currentScreen, animationSpec = tween(500), label = "AuthTransition") { screen ->
                                    when (screen) {
                                        AuthScreen.START -> StartSelectionScreen(onCreateAccount = { currentScreen = AuthScreen.SIGNUP }, onLoginClick = { currentScreen = AuthScreen.LOGIN }, onGoogleClick = {
                                            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestIdToken(getString(R.string.default_web_client_id)).requestEmail().build()
                                            googleSignInLauncher.launch(GoogleSignIn.getClient(this@MainActivity, gso).signInIntent)
                                        }, isDarkMode = isDarkMode, themeColor = themeColor)
                                        AuthScreen.LOGIN -> LoginScreen(onGoToSignUp = { currentScreen = AuthScreen.SIGNUP }, onLoginSubmit = { email, password ->
                                            FirebaseAuth.getInstance().signInWithEmailAndPassword(email, password)
                                                .addOnSuccessListener { tempUsername = ""; currentScreen = AuthScreen.OTP }
                                                .addOnFailureListener { Toast.makeText(this@MainActivity, "Invalid Login", Toast.LENGTH_SHORT).show() }
                                        }, isDarkMode = isDarkMode, themeColor = themeColor)
                                        AuthScreen.SIGNUP -> SignUpScreen(onSignUpSubmit = { firstName, lastName, email, phone, pass ->
                                            FirebaseAuth.getInstance().createUserWithEmailAndPassword(email, pass)
                                                .addOnSuccessListener { tempUsername = "$firstName $lastName"; tempEmail = email; tempPhone = phone; currentScreen = AuthScreen.OTP }
                                        }, onGoToLogin = { currentScreen = AuthScreen.LOGIN }, isDarkMode = isDarkMode, themeColor = themeColor)
                                        AuthScreen.OTP -> OtpScreen(onVerify = { finalizeUserEntry() }, isDarkMode = isDarkMode, themeColor = themeColor)
                                    }
                                }
                            }
                            LoginState.DEVICE_MISMATCH -> {
                                DeviceMismatchScreen {
                                    val uid = FirebaseAuth.getInstance().currentUser?.uid
                                    if (uid != null) {
                                        val hardwareId = android.provider.Settings.Secure.getString(this@MainActivity.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "UNKNOWN_DEVICE"
                                        SecurityUtils.getSecurePrefs(this@MainActivity).edit().putString("active_device_id", hardwareId).apply()
                                        com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("users").document(uid).update("active_device_id", hardwareId).addOnSuccessListener { authViewModel.updateState(LoginState.SUCCESS) }
                                    } else authViewModel.updateState(LoginState.SUCCESS)
                                }
                            }
                        }
                    }
                    /*
                    if (showWalletSelector) {
                        WalletSelectorSheet(
                            wallets = wallets,
                            activeWalletId = activeWallet?.virtualAccountId ?: "",
                            onSelect = { id -> viewModel?.setVirtualAccount(id) },
                            viewModel = viewModel!!,
                            selectedColor = selectedThemeColor, // ✨ Pass the shared state
                            onColorChange = { selectedThemeColor = it }, // ✨ Update the shared state
                            onDismiss = { showWalletSelector = false },
                            isDarkMode = isDarkMode,
                            themeColor = themeColor
                        )
                    }
                     */
                    if (showAmountDialog) {
                        val target = if (!isSenderRole) "Linked Bank Account" else (scannedTargetName ?: "Device")

                        NearbyPaymentSheet(
                            receiverName = target,
                            currentBalance = balance,
                            amountText = amountText,
                            onAmountChange = { amountText = it },
                            onCancel = { resetEverything() },
                            onTransfer = {
                                val amount = amountText.toLongOrNull() ?: 0L

                                val myPhone = SecurityUtils.getSecurePrefs(context).getString("phone_number", "") ?: ""
                                val receiverPhone = target.split("|").getOrNull(1) ?: ""

                                // ✨ THE FIX: Added myPhone.isNotBlank() check.
                                // Without this, if both phones are empty strings (""), it blocks valid transfers!
                                if (myPhone.isNotBlank() && myPhone == receiverPhone && isSenderRole) {
                                    Toast.makeText(context, "You cannot send money to your own vault.", Toast.LENGTH_LONG).show()
                                    resetEverything()
                                    return@NearbyPaymentSheet
                                }
                                // ✨ END OF LOCK ✨

                                if (amount > 0) {
                                    if (!isSenderRole) {
                                        showAmountDialog = false
                                        simulateBankTopUp(amount, activeVirtualAccountId)
                                        amountText = "" // ✨ Ensure input is cleared
                                        resetEverything()
                                    } else {
                                        if (selectedDeviceId != null) {
                                            nearbyManager.pendingAmount = amount
                                            showBiometricPrompt {
                                                isProcessing = true
                                                showAmountDialog = false
                                                nearbyManager.connectToDevice(selectedDeviceId!!)
                                                amountText = "" // ✨ Ensure input is cleared
                                            }
                                        } else {
                                            Toast.makeText(context, "Linking secure channel... Tap again.", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            },
                            isDarkMode = isDarkMode,
                            themeColor = themeColor
                        )
                    }
                    if (showGooglePhonePrompt) {
                        val detectedNumbers = remember { getDevicePhoneNumbers(context) }
                        GooglePhoneSetupScreen(
                            suggestedNumbers = detectedNumbers,
                            onComplete = { phone -> tempPhone = phone; showGooglePhonePrompt = false; SecurityUtils.getSecurePrefs(this@MainActivity).edit().putString("user_name", tempUsername).apply(); val uid = FirebaseAuth.getInstance().currentUser?.uid ?: ""; viewModel?.refreshDataForUser(uid); finalizeUserEntry() },
                            isDarkMode = isDarkMode, themeColor = themeColor
                        )
                    }
                    if (showPostScanChoice && pendingScanResult != null) {
                        val name = pendingScanResult!!.getQueryParameter("name") ?: "Receiver"
                        val phone = pendingScanResult!!.getQueryParameter("phone") ?: ""
                        val va = pendingScanResult!!.getQueryParameter("va") ?: ""

                        PostScanChoiceDialog(
                            receiverName = name,
                            onNearbySelected = {
                                showPostScanChoice = false
                                // ✨ SYNC WALLET ID HERE TOO
                                nearbyManager.currentVirtualAccount = activeVirtualAccountId

                                scannedTargetName = "$name|$phone|$va"
                                isSenderRole = true
                                lifecycleScope.launch {
                                    nearbyManager.forceKillEverything()
                                    delay(500)
                                    nearbyManager.startDiscovery()
                                    isScanning = true
                                }
                            },
                            onRemoteSelected = {
                                showPostScanChoice = false
                                nearbyManager.forceKillEverything()
                                targetPhone = phone
                                targetVaForSms = va // ✨ CAPTURE IT HERE!
                                showSmsDialog = true
                            },
                            onDismiss = { showPostScanChoice = false; resetEverything() },
                            isDarkMode = isDarkMode,
                            themeColor = themeColor
                        )
                    }
                    if (showSmsDialog) {
                        val scale = remember { Animatable(0.7f) }
                        val alpha = remember { Animatable(0f) }
                        var smsNote by remember { mutableStateOf("") } // ✨ State for the note

                        LaunchedEffect(Unit) { launch { scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)) }; launch { alpha.animateTo(1f, tween(200)) } }

                        val dialogBg = if (isDarkMode) Color(0xFF0F142E) else Color.White
                        val textColor = if (isDarkMode) Color.White else Color(0xFF0A0E21)
                        val subTextColor = if (isDarkMode) Color.Gray else Color(0xFF5F6368)

                        Dialog(onDismissRequest = { showSmsDialog = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
                            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).graphicsLayer(scaleX = scale.value, scaleY = scale.value, alpha = alpha.value).clip(RoundedCornerShape(32.dp)).background(dialogBg).border(1.dp, if(isDarkMode) Color.White.copy(alpha = 0.1f) else Color(0xFFE0E0E0), RoundedCornerShape(32.dp))) {
                                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Surface(modifier = Modifier.size(64.dp), shape = CircleShape, color = themeColor.copy(alpha = 0.1f), border = BorderStroke(1.dp, themeColor.copy(alpha = 0.3f))) {
                                        Icon(imageVector = Icons.Default.Sms, contentDescription = null, tint = themeColor, modifier = Modifier.padding(16.dp))
                                    }
                                    Spacer(Modifier.height(16.dp))
                                    Text(text = "Remote Transfer", color = textColor, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                    Text(text = "Secure offline routing via encrypted SMS", color = subTextColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                                    Spacer(Modifier.height(32.dp))

                                    AuthTextField(
                                        value = targetPhone,
                                        onValueChange = { targetPhone = it },
                                        label = "Receiver's Number",
                                        icon = Icons.Default.Phone,
                                        isDarkMode = isDarkMode,
                                        themeColor = themeColor,
                                        trailingIcon = {
                                            IconButton(onClick = {
                                                // ✨ Launch the direct Phone Picker Intent!
                                                val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
                                                contactPickerLauncher.launch(intent)
                                            }) {
                                                Icon(Icons.Default.Contacts, "Pick Contact", tint = themeColor)
                                            }
                                        }
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    AuthTextField(value = amountText, onValueChange = { amountText = it }, label = "Amount (₹)", icon = Icons.Default.CurrencyRupee, isDarkMode = isDarkMode, themeColor = themeColor)
                                    Spacer(Modifier.height(12.dp))

                                    // ✨ THE NOTE INPUT FIELD
                                    OutlinedTextField(
                                        value = smsNote,
                                        onValueChange = { if (it.length <= 60) smsNote = it },
                                        placeholder = { Text("What's this for?", fontSize = 14.sp, color = subTextColor.copy(alpha = 0.7f)) },
                                        modifier = Modifier.fillMaxWidth(),
                                        maxLines = 2,
                                        shape = RoundedCornerShape(12.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = themeColor,
                                            unfocusedBorderColor = if(isDarkMode) Color.White.copy(alpha = 0.2f) else Color.Gray.copy(alpha = 0.3f),
                                            focusedTextColor = textColor,
                                            unfocusedTextColor = textColor,
                                            cursorColor = themeColor
                                        )
                                    )

                                    val currentEnteredAmount = amountText.toLongOrNull() ?: 0L
                                    val isInsufficient = currentEnteredAmount > balance
                                    val activeWalletName = activeWallet?.walletName ?: "Primary"

                                    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp), horizontalArrangement = Arrangement.Start) {
                                        Text(text = "$activeWalletName Balance: ₹$balance", color = if (isInsufficient) Color(0xFFF44336) else subTextColor, style = MaterialTheme.typography.labelSmall, fontWeight = if (isInsufficient) FontWeight.Bold else FontWeight.Normal)
                                    }
                                    Spacer(Modifier.height(32.dp))

                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        TextButton(onClick = { showSmsDialog = false }, modifier = Modifier.weight(1f).height(56.dp)) { Text("CANCEL", color = subTextColor, fontWeight = FontWeight.Bold) }
                                        Button(
                                            onClick = {
                                                if (currentEnteredAmount > 0 && targetPhone.length >= 10 && !isInsufficient) {
                                                    showSmsDialog = false
                                                    // ✨ PASS smsNote HERE
                                                    sendOfflineSmsTransfer(targetPhone, currentEnteredAmount, currentUserName, balance, activeVirtualAccountId, targetVaForSms, smsNote)
                                                    amountText = ""
                                                    targetPhone = ""
                                                    targetVaForSms = ""
                                                    smsNote = "" // ✨ Reset it
                                                }
                                            },
                                            enabled = amountText.isNotEmpty() && targetPhone.isNotEmpty() && !isInsufficient, modifier = Modifier.weight(1.2f).height(56.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = themeColor, disabledContainerColor = if(isDarkMode) Color.White.copy(alpha = 0.05f) else Color(0xFFE0E0E0))
                                        ) { Text("SEND SECURE", color = if (amountText.isNotEmpty() && !isInsufficient) Color(0xFF0A0E21) else Color.Gray, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp) }
                                    }
                                }
                            }
                        }
                    }
                    if (showTray) { NotificationTray(notifications = notifications, onDismiss = { showTray = false }, isDarkMode = isDarkMode) }

                    selectedTransactionForDetails?.let { tx ->
                        TransactionDetailsSheet(
                            tx = tx,
                            onDismiss = { selectedTransactionForDetails = null },
                            isDarkMode = isDarkMode,
                            themeColor = themeColor,
                            currentWalletName = activeWallet?.walletName ?: "Primary"
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(refreshReceiver)
        } catch (e: Exception) {}
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // Update the activity's intent
        intent.getStringExtra("OPEN_CHAT_ID")?.let {
            _pendingChatNavigation.value = it
        }
    }

    override fun onStart() {
        super.onStart()
        if (FirebaseAuth.getInstance().currentUser == null) isVaultUnlocked = true else isVaultUnlocked = false
    }

    private fun triggerSuccessSequence() {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 100, 50, 200), -1)) else vibrator.vibrate(300)
        isProcessing = false
    }

    private fun showVaultLock() {
        val biometricManager = BiometricManager.from(this)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (biometricManager.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS) triggerBiometricPrompt(authenticators) else isVaultUnlocked = true
    }


    private fun simulateBankWithdrawal(amount: Long, targetVirtualAccount: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance() // ✨ Added this

        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {

            // ✨ NEW: Add the money back into their Firebase Bank Account
            firestore.collection("users").document(uid)
                .update("bank_balance", com.google.firebase.firestore.FieldValue.increment(amount))

            val db = DatabaseProvider.getDatabase(this@MainActivity)
            db.creditDao().addTransaction(
                CreditEntry(
                    userId = uid,
                    amount = -amount,
                    senderId = "BANK_WITHDRAWAL",
                    timestamp = System.currentTimeMillis(),
                    transactionHash = "WD_${System.currentTimeMillis()}",
                    previousHash = "ROOT_ENTRY",
                    isSynced = false,
                    targetVirtualAccount = targetVirtualAccount
                )
            )

            sendPersistentNotification("Withdrawal Success", "₹$amount transferred to your bank.")
            syncManager.triggerImmediateSync()

            launch(kotlinx.coroutines.Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Withdrawal Processing", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun simulateBankTopUp(amount: Long, targetVirtualAccount: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance() // ✨ Added this

        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {

            // ✨ NEW: Deduct the money from their Firebase Bank Account
            firestore.collection("users").document(uid)
                .update("bank_balance", com.google.firebase.firestore.FieldValue.increment(-amount))

            val db = DatabaseProvider.getDatabase(this@MainActivity)
            db.creditDao().addTransaction(
                CreditEntry(
                    userId = uid, amount = amount, senderId = "BANK_SIM", timestamp = System.currentTimeMillis(),
                    transactionHash = "TOPUP_${System.currentTimeMillis()}", previousHash = "ROOT_ENTRY", isSynced = false,
                    targetVirtualAccount = targetVirtualAccount
                )
            )

            sendPersistentNotification("Bank Transfer Success", "₹$amount added to your vault.")
            syncManager.triggerImmediateSync()
            firestore.collection("users").document(uid).collection("sync_logs").add(mapOf("amount" to amount, "time" to System.currentTimeMillis()))

            launch(kotlinx.coroutines.Dispatchers.Main) { Toast.makeText(this@MainActivity, "Credits Synced Successfully", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun triggerBiometricPrompt(authenticators: Int = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL, onSuccess: () -> Unit = { isVaultUnlocked = true }) {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onSuccess() }
            override fun onAuthenticationError(code: Int, msg: CharSequence) { if (code == BiometricPrompt.ERROR_USER_CANCELED || code == BiometricPrompt.ERROR_NEGATIVE_BUTTON) { if (!isVaultUnlocked) finish() } }
        })
        val promptInfo = BiometricPrompt.PromptInfo.Builder().setTitle("Confirm Identity").setSubtitle("Required to authorize vault access").setAllowedAuthenticators(authenticators).build()
        biometricPrompt.authenticate(promptInfo)
    }
    private fun showBiometricPrompt(onSuccess: () -> Unit) { triggerBiometricPrompt(onSuccess = onSuccess) }
    private fun generateAccountNumber(): String { return "8800${(10000000..99999999).random()}" }

    private fun finalizeUserEntry() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val db = DatabaseProvider.getDatabase(this)
        val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
        val prefs = SecurityUtils.getSecurePrefs(this)
        val hardwareId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "UNKNOWN_DEVICE"

        if (tempUsername.isNotBlank()) {
            val newAccountNumber = generateAccountNumber()
            prefs.edit().putString("user_name", tempUsername).putString("phone_number", tempPhone).putString("account_number", newAccountNumber).putString("active_device_id", hardwareId).apply()

            // ✨ UPDATED: Added 'bank_balance' with 50,000 starter credits
            val userData = hashMapOf(
                "username" to tempUsername,
                "email" to tempEmail,
                "phone" to tempPhone,
                "accountNumber" to newAccountNumber,
                "active_device_id" to hardwareId,
                "last_sync" to System.currentTimeMillis(),
                "bank_balance" to 50000L,
                "Local_balance" to 0L // ✨ Add this line!
            )
            firestore.collection("users").document(uid).set(userData)
            authViewModel.updateState(LoginState.SUCCESS)
        } else {
            firestore.collection("users").document(uid).get().addOnSuccessListener { doc ->
                val name = doc.getString("username") ?: "User"
                val phone = doc.getString("phone") ?: ""
                val accNumber = doc.getString("accountNumber") ?: generateAccountNumber()
                currentUserName = name; tempPhone = phone
                prefs.edit().putString("user_name", name).putString("phone_number", phone).putString("account_number", accNumber).putString("active_device_id", hardwareId).apply()
                firestore.collection("users").document(uid).update("active_device_id", hardwareId)
                viewModel?.refreshDataForUser(uid)
                authViewModel.updateState(LoginState.SUCCESS)
            }
        }
    }

    private fun setupAutoSync() {
        val networkRequest = android.net.NetworkRequest.Builder().addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        val connectivityManager = getSystemService(android.net.ConnectivityManager::class.java)
        connectivityManager.registerNetworkCallback(networkRequest, object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { startCloudSyncProgress() }
        })
    }

    private fun startCloudSyncProgress() {
        lifecycleScope.launch {
            val notificationManager = androidx.core.app.NotificationManagerCompat.from(this@MainActivity)
            val builder = androidx.core.app.NotificationCompat.Builder(this@MainActivity, "VAULT_NOTIFS").setContentTitle("Cloud Syncing...").setSmallIcon(android.R.drawable.stat_notify_sync).setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW).setOngoing(true).setProgress(100, 0, false)
            for (i in 1..100 step 20) { builder.setProgress(100, i, false); notificationManager.notify(99, builder.build()); delay(500) }
            builder.setContentText("Sync Complete").setProgress(0, 0, false).setOngoing(false)
            notificationManager.notify(99, builder.build())
            sendPersistentNotification("Vault Synced", "All offline transactions are now backed up.")
        }
    }


    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel("VAULT_NOTIFS", "Vault Alerts", android.app.NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Security and Transaction Alerts" }
            (getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun sendPersistentNotification(title: String, message: String) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val uid = user.uid
        val db = DatabaseProvider.getDatabase(this)
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            db.creditDao().addNotification(NotificationEntry(userId = uid, title = title, message = message, timestamp = System.currentTimeMillis()))
            val builder = androidx.core.app.NotificationCompat.Builder(this@MainActivity, "VAULT_NOTIFS").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(message).setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT).setAutoCancel(true)
            with(androidx.core.app.NotificationManagerCompat.from(this@MainActivity)) {
                if (androidx.core.content.ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    notify(System.currentTimeMillis().toInt(), builder.build())
                }
            }
        }
    }


    // ✨ Added note parameter at the end
    private fun sendOfflineSmsTransfer(targetPhone: String, amount: Long, currentUserName: String, currentBalance: Long, sourceVirtualAccountId: String, targetVirtualAccountId: String, note: String = "") {
        if (amount > currentBalance) { Toast.makeText(this, "Insufficient Balance", Toast.LENGTH_SHORT).show(); return }

        showBiometricPrompt {
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return@showBiometricPrompt
            val prefs = SecurityUtils.getSecurePrefs(this)
            val myPhone = prefs.getString("phone_number", "") ?: ""
            val myAcc = sourceVirtualAccountId
            val hash = "SMS_${System.currentTimeMillis()}"
            val signature = CryptoEngine.generateSignature(amount, myPhone, myAcc, hash)

            // ✨ THE INVISIBLE MIDDLEMAN LOGIC
            val CENTRAL_HUB_NUMBER = "+919920833792" // Your Tab Number

            // Packages the payload perfectly for the Hub
            val payload = "##PAYSETU_HUB##:$targetPhone:$amount:$currentUserName:$myPhone:$myAcc:$hash:$signature:$targetVirtualAccountId:$note"

            try {
                val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) this.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()

                // Silently sends to the Hub using Multipart because payload > 160 chars
                val parts = smsManager.divideMessage(payload)
                smsManager.sendMultipartTextMessage(CENTRAL_HUB_NUMBER, null, parts, null, null)

                lifecycleScope.launch {
                    val db = DatabaseProvider.getDatabase(this@MainActivity)
                    val tempSmartId = "Pending|$targetPhone|${targetVirtualAccountId.ifEmpty { "Unknown" }}"

                    db.creditDao().addTransaction(
                        CreditEntry(
                            userId = uid, amount = -amount, senderId = tempSmartId,
                            timestamp = System.currentTimeMillis(), transactionHash = hash,
                            previousHash = "REMOTE_SMS", isSynced = false,
                            targetVirtualAccount = sourceVirtualAccountId, note = note
                        )
                    )
                    this@MainActivity.showSuccessOverlay = amount
                    this@MainActivity.isSenderRole = true
                    triggerSuccessSequence()
                }
            } catch (e: Exception) { Toast.makeText(this@MainActivity, "SMS Failed", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun uploadProfileImage(uri: android.net.Uri) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        syncFieldToFirebase("profilePic", uri.toString())
    }
    private fun handleQRClick() { if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == 0) isScanningQR = true else registerForActivityResult(ActivityResultContracts.RequestPermission()){if(it) isScanningQR=true}.launch(Manifest.permission.CAMERA) }
    private fun ruggedReset(resetUi: () -> Unit, onDone: () -> Unit) {
        Toast.makeText(this, "Initializing Secure Radios...", Toast.LENGTH_SHORT).show()
        triggerSuccessVibration()
        resetUi()
        lifecycleScope.launch { delay(1000); onDone() }
    }
    private fun checkAndPromptHardware(): Boolean { return true }
    private fun triggerSuccessVibration() { (getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator).vibrate(android.os.VibrationEffect.createOneShot(400, 255)) }
}

// --- VISUAL UI COMPONENTS ---
private fun syncFieldToFirebase(fieldName: String, value: String) {
    val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
    com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("users").document(uid).update(fieldName, value).addOnFailureListener { Log.e("Vault", "Update failed: $fieldName") }
}

@Composable
fun StartSelectionScreen(onCreateAccount: () -> Unit, onLoginClick: () -> Unit, onGoogleClick: () -> Unit, isDarkMode: Boolean, themeColor: Color) {
    val navyDark = Color(0xFF0A0E21); val navyLight = Color(0xFF1C2754)
    val bgTop = if (isDarkMode) navyLight else Color(0xFFE8F0FE); val bgBottom = if (isDarkMode) navyDark else Color(0xFFFFFFFF)
    val textColor = if (isDarkMode) Color.White else Color(0xFF0A0E21); val subTextColor = if (isDarkMode) Color.White.copy(alpha = 0.7f) else Color.Gray
    Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(bgTop, bgBottom)))) {
        Column(modifier = Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Surface(modifier = Modifier.size(120.dp), shape = RoundedCornerShape(30.dp), color = if(isDarkMode) navyLight.copy(alpha = 0.5f) else Color.White, border = BorderStroke(1.dp, themeColor.copy(alpha = 0.5f)), shadowElevation = if(isDarkMode) 0.dp else 8.dp) {
                Box(contentAlignment = Alignment.Center) { Icon(imageVector = Icons.Default.Lock, contentDescription = null, tint = themeColor, modifier = Modifier.size(60.dp)) }
            }
            Spacer(Modifier.height(40.dp))
            Text(text = "Welcome to", color = subTextColor, style = MaterialTheme.typography.titleMedium)
            Text(text = "PaySetu", color = themeColor, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(60.dp))
            Button(onClick = onCreateAccount, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = themeColor)) { Text("Create Account", color = navyDark, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge) }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onGoogleClick, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, if(isDarkMode) Color.White.copy(alpha = 0.3f) else Color(0xFFE0E0E0)), colors = ButtonDefaults.outlinedButtonColors(contentColor = textColor)) { Row(verticalAlignment = Alignment.CenterVertically) { Text("Continue with Google", fontWeight = FontWeight.Medium) } }
            Spacer(Modifier.height(40.dp))
            Row { Text("Already have an account? ", color = subTextColor); Text(text = "Login", color = themeColor, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onLoginClick() }) }
        }
    }
}


@Composable
fun SignUpScreen(onSignUpSubmit: (String, String, String, String, String) -> Unit, onGoToLogin: () -> Unit, isDarkMode: Boolean, themeColor: Color) {
    val navyDark = Color(0xFF0A0E21); val navyLight = Color(0xFF1C2754)
    val bgTop = if (isDarkMode) navyLight else Color(0xFFE8F0FE); val bgBottom = if (isDarkMode) navyDark else Color(0xFFFFFFFF)
    val subTextColor = if (isDarkMode) Color.White.copy(alpha = 0.6f) else Color.Gray
    var firstName by remember { mutableStateOf("") }; var lastName by remember { mutableStateOf("") }; var email by remember { mutableStateOf("") }; var phone by remember { mutableStateOf("") }; var pass by remember { mutableStateOf("") }; var confirmPass by remember { mutableStateOf("") }
    Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(bgTop, bgBottom)))) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(60.dp))
            Text(text = "Create Account", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = themeColor)
            Text(text = "Join the PaySetu Vault network", style = MaterialTheme.typography.bodyMedium, color = subTextColor)
            Spacer(Modifier.height(40.dp))
            AuthTextField(value = firstName, onValueChange = { firstName = it }, label = "First Name", icon = Icons.Default.Person, isDarkMode = isDarkMode, themeColor = themeColor)
            AuthTextField(value = lastName, onValueChange = { lastName = it }, label = "Last Name", icon = Icons.Default.Person, isDarkMode = isDarkMode, themeColor = themeColor)
            AuthTextField(value = email, onValueChange = { email = it }, label = "Email Address", icon = Icons.Default.Email, isDarkMode = isDarkMode, themeColor = themeColor)
            AuthTextField(value = phone, onValueChange = { phone = it }, label = "Phone Number (+91)", icon = Icons.Default.Phone, isDarkMode = isDarkMode, themeColor = themeColor)
            AuthTextField(value = pass, onValueChange = { pass = it }, label = "Enter Password", icon = Icons.Default.Lock, isPassword = true, isDarkMode = isDarkMode, themeColor = themeColor)
            AuthTextField(value = confirmPass, onValueChange = { confirmPass = it }, label = "Confirm Password", icon = Icons.Default.Lock, isPassword = true, isDarkMode = isDarkMode, themeColor = themeColor)
            Spacer(Modifier.height(32.dp))
            Button(onClick = { if(pass == confirmPass && phone.length >= 10) onSignUpSubmit(firstName, lastName, email, phone, pass) }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = themeColor)) { Text("Sign up", color = navyDark, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge) }
            Spacer(Modifier.height(24.dp))
            Row(modifier = Modifier.padding(bottom = 40.dp)) { Text("Already have an account? ", color = subTextColor); Text(text = "Login", color = themeColor, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onGoToLogin() }) }
        }
    }
}

@Composable
fun LoginScreen(onLoginSubmit: (String, String) -> Unit, onGoToSignUp: () -> Unit, isDarkMode: Boolean, themeColor: Color) {
    val navyDark = Color(0xFF0A0E21); val navyLight = Color(0xFF1C2754)
    val bgTop = if (isDarkMode) navyLight else Color(0xFFE8F0FE); val bgBottom = if (isDarkMode) navyDark else Color(0xFFFFFFFF)
    val textColor = if (isDarkMode) Color.White else Color(0xFF0A0E21); val subTextColor = if (isDarkMode) Color.White.copy(alpha = 0.6f) else Color.Gray
    var email by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }
    Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(bgTop, bgBottom)))) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(imageVector = Icons.Default.Lock, contentDescription = null, tint = themeColor, modifier = Modifier.size(80.dp).background(if(isDarkMode) Color.White.copy(alpha = 0.05f) else Color.White, CircleShape).padding(16.dp))
            Spacer(Modifier.height(24.dp))
            Text(text = "Welcome Back", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = textColor)
            Text(text = "Sign in to access your vault", style = MaterialTheme.typography.bodyMedium, color = subTextColor)
            Spacer(Modifier.height(48.dp))
            AuthTextField(value = email, onValueChange = { email = it }, label = "Email Address", icon = Icons.Default.Email, isDarkMode = isDarkMode, themeColor = themeColor)
            Spacer(Modifier.height(12.dp))
            AuthTextField(value = password, onValueChange = { password = it }, label = "Password", icon = Icons.Default.Lock, isPassword = true, isDarkMode = isDarkMode, themeColor = themeColor)
            Text(text = "Forgot Password?", modifier = Modifier.align(Alignment.End).padding(top = 8.dp, end = 4.dp), color = themeColor.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(48.dp))
            Button(onClick = { if(email.isNotBlank() && password.isNotBlank()) onLoginSubmit(email, password) }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = themeColor)) { Text(text = "LOGIN", color = navyDark, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.bodyLarge) }
            Spacer(Modifier.height(32.dp))
            Row { Text("New to PaySetu? ", color = subTextColor); Text(text = "SignUp", color = themeColor, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onGoToSignUp() }) }
        }
    }
}

@Composable
fun SplashScreen(onAnimationFinished: () -> Unit, isDarkMode: Boolean, themeColor: Color) {
    val voltageColor = Color(0xFF00E5FF)
    val logoSize = 320.dp
    val scanY = remember { Animatable(0f) }
    val logoAlpha = remember { Animatable(0f) }
    val logoScale = remember { Animatable(0.7f) }
    val glowIntensity = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(200)
        repeat(3) { glowIntensity.animateTo(0.8f, tween(50)); glowIntensity.animateTo(0.2f, tween(50)) }
        launch { logoAlpha.animateTo(1f, tween(1500)); logoScale.animateTo(1.1f, tween(1500, easing = FastOutSlowInEasing)) }
        scanY.animateTo(1f, tween(1500, easing = LinearEasing))
        glowIntensity.animateTo(2f, tween(400)); delay(1200)
        onAnimationFinished()
    }
    Box(modifier = Modifier.fillMaxSize().background(Brush.radialGradient(colors = listOf(Color(0xFF0A0E21), Color.Black), center = Offset.Unspecified, radius = 2000f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.TopCenter, modifier = Modifier.size(logoSize)) {
                Image(painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_launcher_background), contentDescription = "PaySetu Logo", modifier = Modifier.fillMaxSize().scale(logoScale.value).graphicsLayer { alpha = logoAlpha.value; clip = true; shape = androidx.compose.foundation.shape.GenericShape { size, _ -> addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height * scanY.value)) } })
                if (scanY.value > 0f && scanY.value < 1f) {
                    Box(modifier = Modifier.fillMaxWidth().height(6.dp).offset(y = logoSize * scanY.value).background(Brush.horizontalGradient(listOf(Color.Transparent, voltageColor, Color.White, voltageColor, Color.Transparent))).shadow(25.dp * glowIntensity.value, spotColor = voltageColor))
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(text = "PAYSETU", color = Color.White.copy(alpha = logoAlpha.value), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black, letterSpacing = 14.sp, modifier = Modifier.graphicsLayer { translationY = 30f * (1f - logoAlpha.value) })
        }
    }
}

@Composable
fun AuthTextField(
    value: String, onValueChange: (String) -> Unit, label: String, icon: ImageVector,
    isPassword: Boolean = false, trailingIcon: @Composable (() -> Unit)? = null, isDarkMode: Boolean, themeColor: Color
) {
    val textColor = if (isDarkMode) Color.White else Color(0xFF0A0E21)
    val subTextColor = if (isDarkMode) Color.White.copy(alpha = 0.5f) else Color.Gray
    val inputBg = if (isDarkMode) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.04f)
    val borderColor = if (isDarkMode) Color.White.copy(alpha = 0.2f) else Color(0xFFE0E0E0)
    var passwordVisible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onValueChange, label = { Text(label, color = subTextColor) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = themeColor) },
        trailingIcon = { if (isPassword) { IconButton(onClick = { passwordVisible = !passwordVisible }) { Icon(imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff, contentDescription = "Toggle Password", tint = themeColor) } } else { trailingIcon?.invoke() } },
        visualTransformation = if (isPassword && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), shape = RoundedCornerShape(12.dp), singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor, cursorColor = themeColor, focusedBorderColor = themeColor, unfocusedBorderColor = borderColor, focusedContainerColor = inputBg, unfocusedContainerColor = inputBg)
    )
}

@Composable
fun AnimatedTransactionList(history: List<CreditEntry>, onTransactionClick: (CreditEntry) -> Unit, isDarkMode: Boolean, themeColor: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        history.take(5).forEachIndexed { index, tx ->
            var isVisible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { delay(index * 100L); isVisible = true }
            AnimatedVisibility(visible = isVisible, enter = fadeIn(animationSpec = tween(500)) + scaleIn(initialScale = 0.8f, animationSpec = tween(500)) + slideInVertically(initialOffsetY = { 20 }, animationSpec = tween(500))) {
                TransactionRowPremium(tx, onClick = { onTransactionClick(tx) }, isDarkMode = isDarkMode, themeColor = themeColor)
            }
        }
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportStatementDialog(month: String, onDismiss: () -> Unit, onExport: () -> Unit, isDarkMode: Boolean, themeColor: Color) {
    val sheetBg = if (isDarkMode) Color(0xFF1C2754).copy(alpha = 0.95f) else Color.White
    val textColor = if (isDarkMode) Color.White else Color(0xFF0A0E21)
    val subTextColor = if (isDarkMode) Color.Gray else Color(0xFF5F6368)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = sheetBg) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp).padding(bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(modifier = Modifier.size(72.dp), shape = CircleShape, color = Color.Transparent) { Image(painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_launcher_background), contentDescription = "PaySetu Logo", modifier = Modifier.padding(8.dp)) }
            Spacer(Modifier.height(16.dp))
            Text("Export Statement", color = textColor, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Generate a detailed report for $month", color = subTextColor, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(32.dp))
            Button(onClick = { onExport(); onDismiss() }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = themeColor)) {
                Icon(Icons.Default.Download, null, tint = Color.Black); Spacer(Modifier.width(8.dp)); Text("DOWNLOAD .PDF REPORT", color = Color.Black, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, if(isDarkMode) Color.White.copy(alpha = 0.2f) else Color(0xFFE0E0E0))) { Text("Select Custom Date Range", color = textColor) }
        }
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthSelectionSheet(availableMonths: List<String>, selectedMonth: String, onSelect: (String) -> Unit, onDismiss: () -> Unit, isDarkMode: Boolean, themeColor: Color) {
    val glassColor = if (isDarkMode) Color(0xFF1C2754).copy(alpha = 0.95f) else Color.White
    val textColor = if (isDarkMode) Color.White else Color(0xFF0A0E21)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = glassColor) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp).padding(bottom = 32.dp)) {
            Text("Filter by Month", color = textColor, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            availableMonths.forEach { month ->
                val isSelected = month == selectedMonth
                Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onSelect(month); onDismiss() }, color = if (isSelected) themeColor.copy(alpha = 0.15f) else Color.Transparent, shape = RoundedCornerShape(16.dp)) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(month, color = if(isSelected) themeColor else textColor, fontWeight = if(isSelected) FontWeight.Bold else FontWeight.Medium)
                        if (isSelected) Icon(Icons.Default.CheckCircle, null, tint = themeColor)
                    }
                }
            }
        }
    }
}
@RequiresApi(Build.VERSION_CODES.Q)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    history: List<CreditEntry>,
    wallets: List<WalletEntity>, // ✨ Added wallets parameter here!
    userName: String,
    onBack: () -> Unit,
    onTransactionClick: (CreditEntry) -> Unit,
    isDarkMode: Boolean,
    themeColor: Color
) {
    val context = LocalContext.current
    val textColor = if (isDarkMode) Color.White else Color(0xFF0A0E21)
    val subTextColor = if (isDarkMode) Color.Gray else Color(0xFF5F6368)
    val bgTop = if (isDarkMode) Color(0xFF0A0E21) else Color(0xFFFFF7EB)
    val bgBottom = if (isDarkMode) Color(0xFF000000) else Color(0xFFFFFFFF)

    val dateFormatter = remember { java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.getDefault()) }
    val currentMonthStr = remember { dateFormatter.format(java.util.Date()) }

    val availableMonths = remember(history) {
        val months = history.map { dateFormatter.format(java.util.Date(it.timestamp)) }.distinct().toMutableList()
        if (!months.contains(currentMonthStr)) months.add(0, currentMonthStr)
        months.sortedByDescending { java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.getDefault()).parse(it)?.time ?: 0L }
    }

    var selectedMonth by remember { mutableStateOf(availableMonths.first()) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showMonthSheet by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredHistory = remember(history, selectedMonth, searchQuery) {
        history.filter { tx ->
            val matchesMonth = dateFormatter.format(java.util.Date(tx.timestamp)) == selectedMonth
            val matchesSearch = tx.senderId.contains(searchQuery, ignoreCase = true) || tx.amount.toString().contains(searchQuery)
            matchesMonth && matchesSearch
        }.sortedByDescending { it.timestamp }
    }

    PremiumBackground(isDarkMode) {
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 0.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Column(modifier = Modifier.statusBarsPadding()) {
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = textColor) }; Text("History", color = textColor, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                            IconButton(onClick = { showExportDialog = true }) { Icon(Icons.Default.GetApp, contentDescription = "Export", tint = themeColor) }
                        }
                        Box(modifier = Modifier.padding(horizontal = 24.dp)) { AuthTextField(value = searchQuery, onValueChange = { searchQuery = it }, label = "Search by name or amount...", icon = Icons.Default.Search, isDarkMode = isDarkMode, themeColor = themeColor) }
                        Spacer(Modifier.height(16.dp))
                        Box(modifier = Modifier.padding(horizontal = 24.dp)) {
                            val totalIn = filteredHistory.filter { it.amount > 0 }.sumOf { it.amount }
                            val totalOut = filteredHistory.filter { it.amount < 0 }.sumOf { kotlin.math.abs(it.amount) }
                            val net = totalIn - totalOut
                            GlassCard(isDarkMode, themeColor = themeColor) {
                                Text(text = if(searchQuery.isNotEmpty()) "Search Summary" else "Summary for $selectedMonth", color = subTextColor, style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.height(16.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column { Text("Money In", color = subTextColor, style = MaterialTheme.typography.labelSmall); Text("+₹$totalIn", color = Color(0xFF4CAF50), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black) }
                                    Column(horizontalAlignment = Alignment.End) { Text("Money Out", color = subTextColor, style = MaterialTheme.typography.labelSmall); Text("-₹$totalOut", color = Color(0xFFF44336), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black) }
                                }
                                Spacer(Modifier.height(16.dp)); HorizontalDivider(color = if(isDarkMode) Color.White.copy(alpha = 0.1f) else Color.Gray.copy(alpha = 0.2f)); Spacer(Modifier.height(12.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("Net Change", color = textColor, fontWeight = FontWeight.Medium)
                                    Surface(color = if(net >= 0) Color(0xFF4CAF50).copy(alpha = 0.15f) else Color(0xFFF44336).copy(alpha = 0.15f), shape = RoundedCornerShape(8.dp)) { Text(text = "${if(net >= 0) "+" else "-"}₹${kotlin.math.abs(net)}", color = if(net >= 0) Color(0xFF4CAF50) else Color(0xFFF44336), fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }
                if (filteredHistory.isEmpty()) {
                    item { Column(modifier = Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.ReceiptLong, null, tint = subTextColor.copy(alpha = 0.3f), modifier = Modifier.size(64.dp)); Spacer(Modifier.height(16.dp)); Text(if(searchQuery.isNotEmpty()) "No matches found" else "No transactions in $selectedMonth", color = subTextColor) } }
                } else {
                    items(filteredHistory) { tx -> Box(modifier = Modifier.padding(horizontal = 24.dp)) { TransactionRowPremium(tx = tx, onClick = { onTransactionClick(tx) }, isDarkMode = isDarkMode, themeColor = themeColor) } }
                }
            }
            Box(modifier = Modifier.fillMaxWidth().height(100.dp).align(Alignment.TopCenter).background(Brush.verticalGradient(colors = listOf(bgTop, bgTop.copy(alpha = 0.8f), Color.Transparent))))
            Box(modifier = Modifier.fillMaxWidth().height(120.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(colors = listOf(Color.Transparent, bgBottom.copy(alpha = 0.8f), bgBottom))))
            Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)) {
                Surface(shape = RoundedCornerShape(30.dp), color = if(isDarkMode) Color(0xFF1C2754).copy(alpha = 0.7f) else Color.White.copy(alpha = 0.85f), border = BorderStroke(1.dp, if(isDarkMode) Color.White.copy(alpha = 0.2f) else Color(0xFFE0E0E0)), shadowElevation = if(isDarkMode) 0.dp else 8.dp, modifier = Modifier.clickable { showMonthSheet = true }) {
                    Row(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.DateRange, null, tint = themeColor, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if(selectedMonth == currentMonthStr) "Current Month" else selectedMonth, color = textColor, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Default.ArrowDropDown, null, tint = subTextColor)
                    }
                }
            }
        }

        if (showMonthSheet) {
            MonthSelectionSheet(availableMonths = availableMonths, selectedMonth = selectedMonth, onSelect = { selectedMonth = it }, onDismiss = { showMonthSheet = false }, isDarkMode = isDarkMode, themeColor = themeColor)
        }

        // ✨ THE FIX: Calling EnhancedExportDialog instead of the old one!
        if (showExportDialog) {
            EnhancedExportDialog(
                wallets = wallets,
                availableMonths = availableMonths,
                onDismiss = { showExportDialog = false },
                onExport = { targetWalletId, walletName, startTimestamp, endTimestamp, periodLabel ->
                    // Filter history based on what the user picked in the dialog
                    val historyToExport = history.filter { tx ->
                        val matchesWallet = if (targetWalletId == null) true else tx.targetVirtualAccount == targetWalletId
                        val matchesTime = tx.timestamp in startTimestamp..endTimestamp
                        matchesWallet && matchesTime
                    }.sortedBy { it.timestamp } // Sort ascending for PDF ledger

                    exportStatementAsPdf(context, historyToExport, periodLabel, userName, walletName)
                    showExportDialog = false
                },
                isDarkMode = isDarkMode,
                themeColor = themeColor
            )
        }
    }
}


@Composable
fun SecureBalanceDisplay(
    actualBalance: Long,
    isDarkMode: Boolean,
    themeColor: Color
) {
    // 1. State to track if the eye is open or closed
    var isBalanceVisible by remember { mutableStateOf(false) }

    // 2. The animation engine
    val animatedBalance = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    val textColor = if (isDarkMode) Color.White else Color(0xFF0A0E21)

    // 3. Trigger the count-up animation when visibility changes
    LaunchedEffect(isBalanceVisible, actualBalance) {
        if (isBalanceVisible) {
            // Snap to 0 so it counts up every single time you reveal it
            animatedBalance.snapTo(0f)
            animatedBalance.animateTo(
                targetValue = actualBalance.toFloat(),
                animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing)
            )
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        // ✨ THE FIX: Spans the full width of the card
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
    ) {
        // The Balance Text
        Text(
            text = if (isBalanceVisible) {
                "₹${animatedBalance.value.toLong()}"
            } else {
                "₹••••"
            },
            color = textColor,
            fontSize = 42.sp, // Massive, premium font size
            fontWeight = FontWeight.Black,
            letterSpacing = if (isBalanceVisible) (-1).sp else 4.sp // Extra spacing for the dots
        )

        // ✨ THE FIX: A flexible spacer that pushes the eye button to the far right edge
        Spacer(modifier = Modifier.weight(1f))

        // The Eye Toggle Button
        IconButton(
            onClick = { isBalanceVisible = !isBalanceVisible },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = if (isBalanceVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                contentDescription = if (isBalanceVisible) "Hide Balance" else "Show Balance",
                tint = themeColor, // Use your dynamic PaySetu gold/theme color
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletDashboard(
    balance: Long, history: List<CreditEntry>, userName: String, onReceiveClick: () -> Unit, onRemoteClick: () -> Unit, onSendClick: () -> Unit, onQRClick: () -> Unit, onProfileClick: () -> Unit, currentTab: Int, onBellClick: () -> Unit, onTabChange: (Int) -> Unit, profilePicUrl: String, onAmountChange: (String) -> Unit, onTopUpClick: () -> Unit, onTransactionClick: (CreditEntry) -> Unit, onPersonClick: (String) -> Unit,
    onWithdrawClick: () -> Unit,
    isDarkMode: Boolean, hasUnreadNotifs: Boolean, themeColor: Color,onCheckCleanup: () -> Unit,
    activeWallet: WalletEntity?, allWallets: List<WalletEntity>,
    onOpenSelector: () -> Unit
) {
    val textColor = if (isDarkMode) Color.White else Color(0xFF0A0E21)
    val subTextColor = if (isDarkMode) Color.Gray else Color(0xFF5F6368)
    val bgTop = if (isDarkMode) Color(0xFF0A0E21) else Color(0xFFF4F7FB)
    val bgBottom = if (isDarkMode) Color(0xFF000000) else Color(0xFFFFFFFF)
    val walletName = activeWallet?.walletName ?: "Primary"
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var contactResults by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(searchQuery) {
        if (searchQuery.length >= 2) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                contactResults = queryLocalContacts(context, searchQuery)
            }
        } else {
            contactResults = emptyList()
        }
    }
    LaunchedEffect(Unit) {
        while(true) {
            onCheckCleanup()
            delay(60000)
        }
    }
    PremiumBackground(isDarkMode) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Box(modifier = Modifier.statusBarsPadding()) {
                    DashboardHeader(
                        onProfileClick = onProfileClick,
                        onBellClick = onBellClick,
                        themeColor = themeColor,
                        onTopUpClick = onTopUpClick,
                        onWithdrawClick = onWithdrawClick, // ✨ WE JUST ADDED THIS LINE!
                        profilePicUrl = profilePicUrl,
                        userName = userName,
                        textColor = textColor,
                        subTextColor = subTextColor,
                        hasUnreadNotifs = hasUnreadNotifs
                    )
                }
                ModernSearchBar(query = searchQuery, onQueryChange = { searchQuery = it }, isDarkMode = isDarkMode, themeColor = themeColor)
                Spacer(Modifier.height(16.dp))
                if (searchQuery.isNotEmpty()) {
                    val queryLower = searchQuery.lowercase()

                    // 1. Grab history matches AND keep their exact SmartId
                    val historyMatches = history.filter { it.previousHash == "REMOTE_SMS" && it.senderId != "BANK_SIM" && !it.senderId.startsWith("SYSTEM_REFUND") }
                        .mapNotNull { tx ->
                            val parts = tx.senderId.split("|")
                            if (parts.size >= 2) {
                                val name = if (parts[0] != "Unknown") parts[0] else parts.last()
                                val phone = parts[1]
                                if (name.lowercase().contains(queryLower) || phone.contains(queryLower)) {
                                    Triple(name, phone, tx.senderId) // ✨ Keep the real ID!
                                } else null
                            } else null
                        }

                    // 2. Format contacts to match, leaving VA blank if unknown
                    val contactMatches = contactResults.map { contact ->
                        Triple(contact.first, contact.second, "${contact.first}|${contact.second}|")
                    }

                    // 3. Combine them, prioritizing History matches so we keep the VA!
                    val combinedResults = (historyMatches + contactMatches).distinctBy {
                        it.second.replace(Regex("[^0-9]"), "").takeLast(10)
                    }

                    Text("Results", color = subTextColor, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
                    if (combinedResults.isEmpty()) {
                        Text("No contacts found.", color = subTextColor, modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
                    } else {
                        combinedResults.take(15).forEach { (name, phone, exactSmartId) ->
                            SearchResultRow(name = name, phone = phone, isDarkMode = isDarkMode, themeColor = themeColor) {
                                searchQuery = ""
                                onPersonClick(exactSmartId) // ✨ Passes the exact ID from history!
                            }
                        }
                    }
                    Spacer(Modifier.height(180.dp))

                } else {
                    Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                        val cardBgColor = if (isDarkMode) Color(0xFF13182C) else Color.White
                        val balanceTextColor = if (isDarkMode) Color.White else Color(0xFF0A0E21)
                        val cardBorderColor = if (isDarkMode) themeColor.copy(alpha = 0.3f) else Color.Transparent
                        val meshGradient = Brush.linearGradient(
                            colors = listOf(themeColor.copy(alpha = if (isDarkMode) 0.2f else 0.1f), Color.Transparent, themeColor.copy(alpha = if (isDarkMode) 0.1f else 0.05f)),
                            start = Offset(0f, 0f), end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                        )
                        Card(
                            modifier = Modifier.fillMaxWidth().height(190.dp).then(if (isDarkMode) Modifier.shadow(20.dp, RoundedCornerShape(32.dp), ambientColor = themeColor, spotColor = themeColor) else Modifier),
                            shape = RoundedCornerShape(32.dp), border = BorderStroke(1.dp, cardBorderColor), colors = CardDefaults.cardColors(containerColor = cardBgColor), elevation = CardDefaults.cardElevation(if (isDarkMode) 0.dp else 16.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize().background(meshGradient)) {
                                Column(modifier = Modifier.padding(24.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text("$walletName Balance", color = subTextColor, style = MaterialTheme.typography.labelLarge, letterSpacing = 1.sp)
                                        /*
                                        Surface(
                                            color = themeColor.copy(alpha = 0.15f), shape = RoundedCornerShape(16.dp), modifier = Modifier.clickable { onOpenSelector() }
                                        ) {
                                            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Icon(if(walletName.contains("Business", ignoreCase = true)) Icons.Default.Work else Icons.Default.AccountBalanceWallet, null, tint = themeColor, modifier = Modifier.size(14.dp))
                                                Spacer(Modifier.width(6.dp))
                                                Text("SWITCH", color = themeColor, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                            }
                                        }

                                         */
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    SecureBalanceDisplay(
                                        actualBalance = balance,
                                        isDarkMode = isDarkMode,
                                        themeColor = themeColor
                                    )
                                    Spacer(Modifier.weight(1f))

                                }
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            GlassShineButton("SEND", Icons.Default.ArrowUpward, Color(0xFF1C2754), themeColor, isDarkMode, onSendClick, Modifier.weight(1f))
                            GlassShineButton(text = "REMOTE", icon = Icons.Default.Sms, bg = Color(0xFF1C2754), tint = themeColor, isDarkMode = isDarkMode, onClick = onRemoteClick, modifier = Modifier.weight(1f))
                            GlassShineButton("RECEIVE", Icons.Default.ArrowDownward, Color(0xFF1C2754), themeColor, isDarkMode, onReceiveClick, Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(32.dp))
                        RecentPeopleSection(history = history, onPersonClick = onPersonClick, isDarkMode = isDarkMode, themeColor = themeColor)
                        if (history.isEmpty() && balance == 0L) {
                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Surface(modifier = Modifier.size(100.dp), shape = CircleShape, color = themeColor.copy(alpha = 0.05f), border = BorderStroke(1.dp, themeColor.copy(alpha = 0.2f))) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.AddModerator, null, tint = themeColor, modifier = Modifier.size(48.dp)) } }
                                Spacer(Modifier.height(24.dp))
                                Text("Your vault is ready", color = textColor, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text("Add funds via bank link or receive money\nfrom another user to start paying offline.", color = subTextColor, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 40.dp).padding(top = 8.dp))
                                Spacer(Modifier.height(32.dp))
                                Button(onClick = onTopUpClick, modifier = Modifier.height(56.dp).padding(horizontal = 32.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = themeColor)) { Text("LINK BANK ACCOUNT", color = Color.Black, fontWeight = FontWeight.Black) }
                            }
                        } else {
                            Spacer(Modifier.height(32.dp))
                            PremiumCashFlowCard(history = history, isDarkMode = isDarkMode, themeColor = themeColor)
                            Spacer(Modifier.height(32.dp))
                            Text("Recent Activity", color = textColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(16.dp))
                            AnimatedTransactionList(history, onTransactionClick = onTransactionClick, isDarkMode = isDarkMode, themeColor = themeColor)
                        }
                        Spacer(Modifier.height(180.dp))
                    }
                }
            }
            Box(modifier = Modifier.fillMaxWidth().height(90.dp).align(Alignment.TopCenter).background(Brush.verticalGradient(colors = listOf(bgTop, bgTop.copy(alpha = 0.8f), Color.Transparent))))
            Box(modifier = Modifier.fillMaxWidth().height(140.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(colors = listOf(Color.Transparent, bgBottom.copy(alpha = 0.8f), bgBottom))))
            Box(modifier = Modifier.align(Alignment.BottomCenter)) { PaySetuBottomBar(currentTab, onTabChange, onQRClick, isDarkMode, themeColor) }
        }
    }
}

// --- SEARCH UI & LOGIC HELPERS ---

@Composable
fun ModernSearchBar(query: String, onQueryChange: (String) -> Unit, isDarkMode: Boolean, themeColor: Color) {
    val bg = if (isDarkMode) Color(0xFF13182C) else Color.White
    val textColor = if (isDarkMode) Color.White else Color(0xFF0A0E21)
    val hintColor = if (isDarkMode) Color.Gray else Color(0xFF5F6368)
    val borderColor = if (isDarkMode) themeColor.copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.05f)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .height(52.dp),
        shape = RoundedCornerShape(16.dp),
        color = bg,
        border = BorderStroke(1.dp, borderColor),
        shadowElevation = if (isDarkMode) 0.dp else 4.dp
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            textStyle = TextStyle(color = textColor, fontSize = 15.sp, fontWeight = FontWeight.Medium),
            cursorBrush = SolidColor(themeColor),
            singleLine = true,
            modifier = Modifier.fillMaxSize(),
            decorationBox = { innerTextField ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Icon(Icons.Default.Search, null, tint = hintColor, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text("Search people or contacts...", color = hintColor, style = MaterialTheme.typography.bodyMedium)
                        innerTextField()
                    }
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, null, tint = hintColor, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        )
    }
}

@Composable
fun SearchResultRow(name: String, phone: String, isDarkMode: Boolean, themeColor: Color, onClick: () -> Unit) {
    val textColor = if (isDarkMode) Color.White else Color(0xFF0A0E21)
    val subTextColor = if (isDarkMode) Color.Gray else Color(0xFF5F6368)
    val initial = name.firstOrNull()?.uppercase() ?: "?"
    val colors = listOf(Color(0xFFE91E63), Color(0xFF9C27B0), Color(0xFF673AB7), Color(0xFF3F51B5), Color(0xFF009688), Color(0xFF4CAF50), Color(0xFFFF9800), Color(0xFF795548))
    val avatarColor = remember(name) { colors[kotlin.math.abs(name.hashCode()) % colors.size] }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(modifier = Modifier.size(48.dp), shape = CircleShape, color = avatarColor) {
            Box(contentAlignment = Alignment.Center) {
                Text(initial, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name, color = textColor, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(phone, color = subTextColor, fontSize = 13.sp)
        }
        Icon(Icons.Default.ArrowForwardIos, null, tint = subTextColor.copy(alpha = 0.5f), modifier = Modifier.size(14.dp))
    }
}

// Background thread function to securely fetch contacts
fun queryLocalContacts(context: android.content.Context, query: String): List<Pair<String, String>> {
    val results = mutableListOf<Pair<String, String>>()
    if (query.isBlank() || ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
        return results
    }
    try {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
        val selectionArgs = arrayOf("%$query%", "%$query%")
        context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx) ?: ""
                val number = cursor.getString(numIdx)?.replace(Regex("[^0-9+]"), "") ?: ""
                if (name.isNotBlank() && number.isNotBlank()) results.add(name to number)
            }
        }
    } catch (e: Exception) { Log.e("Contacts", "Search error", e) }
    return results.distinctBy { it.second }.take(15) // Keep list clean
}

@Composable
fun BuyCreditsScreen(
    currentBalance: Long,
    onBack: () -> Unit,
    onConfirmPurchase: (Long) -> Unit,
    isDarkMode: Boolean,
    themeColor: Color
) {
    var customAmount by remember { mutableStateOf("") }
    val selectedAmount = customAmount.toLongOrNull() ?: 0L

    val textColor = if (isDarkMode) Color.White else Color(0xFF0A0E21)
    val subTextColor = if (isDarkMode) Color.Gray else Color(0xFF5F6368)

    PremiumBackground(isDarkMode) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, null, tint = textColor)
                }
                Text("Buy Credits", style = MaterialTheme.typography.headlineSmall, color = textColor, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(32.dp))
            GlassCard(isDarkMode, themeColor = themeColor) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(40.dp),
                        shape = CircleShape,
                        color = themeColor.copy(alpha = 0.1f)
                    ) {
                        Icon(Icons.Default.AccountBalance, null, tint = themeColor, modifier = Modifier.padding(8.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Linked Bank Account", color = textColor, fontWeight = FontWeight.Bold)
                        Text("demo.bank@payvault", color = subTextColor, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("Enter Amount", color = subTextColor, style = MaterialTheme.typography.labelMedium)
            AuthTextField(
                value = customAmount,
                onValueChange = { customAmount = it },
                label = "How many credits?",
                icon = Icons.Default.Token,
                isDarkMode = isDarkMode,
                themeColor = themeColor
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(500L, 1000L, 2500L).forEach { amt ->
                    CreditPill(
                        amount = amt,
                        label = if(amt == 1000L) "Popular" else "Refill",
                        onClick = { customAmount = it.toString() },
                        modifier = Modifier.weight(1f),
                        isHighlighted = customAmount == amt.toString(),
                        isDarkMode = isDarkMode,
                        themeColor = themeColor
                    )
                }
            }
            Spacer(Modifier.height(32.dp))

            // Cleaned up Breakdown (No Discount)
            if (selectedAmount > 0) {
                Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Text("Credits ($selectedAmount x ₹1)", color = subTextColor)
                        Text("₹$selectedAmount", color = textColor)
                    }
                    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = if(isDarkMode) Color.White.copy(alpha=0.1f) else Color(0xFFE0E0E0))
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Text("Total (INR)", color = textColor, fontWeight = FontWeight.Bold)
                        Text("₹$selectedAmount", color = themeColor, fontWeight = FontWeight.Black, fontSize = 20.sp)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { onConfirmPurchase(selectedAmount) },
                modifier = Modifier.fillMaxWidth().height(64.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = themeColor),
                enabled = selectedAmount > 0
            ) {
                Text("CHECKOUT", color = Color.Black, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            }
        }
    }
}

@Composable
fun BankActionsSection(onAmountSelected: (Long) -> Unit, onWithdrawClick: () -> Unit, themeColor: Color) {
    GlassCard(themeColor = themeColor) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Linked Bank", color = Color.White, fontWeight = FontWeight.Bold)

            // ✨ NEW: The Withdraw Button
            TextButton(onClick = onWithdrawClick) {
                Text("WITHDRAW", color = themeColor, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(500L, 1000L, 2500L).forEach { amt ->
                OutlinedButton(
                    onClick = { onAmountSelected(amt) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, themeColor.copy(alpha = 0.5f))
                ) { Text("+ ₹$amt", color = themeColor, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
fun WithdrawFundsScreen(
    currentBalance: Long,
    onBack: () -> Unit,
    onConfirmWithdrawal: (Long) -> Unit,
    isDarkMode: Boolean,
    themeColor: Color
) {
    var customAmount by remember { mutableStateOf("") }
    val selectedAmount = customAmount.toLongOrNull() ?: 0L

    val isValidAmount = selectedAmount > 0 && selectedAmount <= currentBalance
    val textColor = if (isDarkMode) Color.White else Color(0xFF0A0E21)
    val subTextColor = if (isDarkMode) Color.Gray else Color(0xFF5F6368)
    val errorColor = Color(0xFFEF4444)

    PremiumBackground(isDarkMode) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(24.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, null, tint = textColor)
                }
                Text("Withdraw Funds", style = MaterialTheme.typography.headlineSmall, color = textColor, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(32.dp))

            // Bank Account Card
            GlassCard(isDarkMode, themeColor = themeColor) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Surface(
                        modifier = Modifier.size(40.dp),
                        shape = CircleShape,
                        color = themeColor.copy(alpha = 0.1f)
                    ) {
                        Icon(Icons.Default.AccountBalance, null, tint = themeColor, modifier = Modifier.padding(8.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Transfer to Bank", color = textColor, fontWeight = FontWeight.Bold)
                        Text("demo.bank@payvault", color = subTextColor, style = MaterialTheme.typography.labelSmall)
                    }
                    Icon(Icons.Default.CheckCircle, contentDescription = "Verified", tint = Color(0xFF10B981), modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.height(24.dp))

            // Available Balance Display
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Amount to withdraw", color = subTextColor, style = MaterialTheme.typography.labelMedium)
                Text("Available: ₹$currentBalance", color = themeColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(8.dp))

            // Input Field
            AuthTextField(
                value = customAmount,
                onValueChange = { customAmount = it },
                label = "Enter amount",
                icon = Icons.Default.CurrencyRupee,
                isDarkMode = isDarkMode,
                themeColor = themeColor
            )

            // Warning if they type too much
            if (selectedAmount > currentBalance) {
                Text(
                    text = "Insufficient vault balance",
                    color = errorColor,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp, start = 8.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            // Smart Selection Pills
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CreditPill(
                    amount = (currentBalance * 0.25).toLong(),
                    label = "25%",
                    onClick = { customAmount = (currentBalance * 0.25).toLong().toString() },
                    modifier = Modifier.weight(1f),
                    isHighlighted = customAmount == (currentBalance * 0.25).toLong().toString(),
                    isDarkMode = isDarkMode,
                    themeColor = themeColor
                )
                CreditPill(
                    amount = (currentBalance * 0.50).toLong(),
                    label = "50%",
                    onClick = { customAmount = (currentBalance * 0.50).toLong().toString() },
                    modifier = Modifier.weight(1f),
                    isHighlighted = customAmount == (currentBalance * 0.50).toLong().toString(),
                    isDarkMode = isDarkMode,
                    themeColor = themeColor
                )
                CreditPill(
                    amount = currentBalance,
                    label = "MAX",
                    onClick = { customAmount = currentBalance.toString() },
                    modifier = Modifier.weight(1f),
                    isHighlighted = customAmount == currentBalance.toString(),
                    isDarkMode = isDarkMode,
                    themeColor = themeColor
                )
            }

            Spacer(Modifier.weight(1f))

            // Action Button
            Button(
                onClick = { onConfirmWithdrawal(selectedAmount) },
                modifier = Modifier.fillMaxWidth().height(64.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isValidAmount) themeColor else themeColor.copy(alpha = 0.3f)
                ),
                enabled = isValidAmount
            ) {
                Text(
                    text = "CONFIRM WITHDRAWAL",
                    color = if (isValidAmount) Color.Black else Color.White.copy(alpha = 0.5f),
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp
                )
            }
        }
    }
}

@Composable
fun CreditPill(
    amount: Long,
    label: String,
    onClick: (Long) -> Unit,
    modifier: Modifier,
    isHighlighted: Boolean = false,
    isDarkMode: Boolean = true,
    themeColor: Color
) {
    val unselectedBorder = if (isDarkMode) Color.White.copy(alpha = 0.2f) else Color(0xFFE0E0E0)
    val unselectedText = if (isDarkMode) Color.White else Color(0xFF0A0E21)
    val subText = if (isDarkMode) Color.Gray else Color(0xFF5F6368)

    OutlinedButton(
        onClick = { onClick(amount) },
        modifier = modifier.height(64.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (isHighlighted) themeColor else unselectedBorder),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (isHighlighted) themeColor.copy(alpha = 0.1f) else Color.Transparent)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("₹$amount", color = if (isHighlighted) themeColor else unselectedText, fontWeight = FontWeight.Bold)
            Text(label, color = subText, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp)
        }
    }
}

@Composable
fun StatItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(label, color = Color.Gray, style = MaterialTheme.typography.labelMedium); Text(value, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge) }
}

@Composable
fun DashboardHeader(
    onProfileClick: () -> Unit, onBellClick: () -> Unit, themeColor: Color, profilePicUrl: String, userName: String,
    onTopUpClick: () -> Unit,
    onWithdrawClick: () -> Unit, // ✨ NEW PARAMETER
    textColor: Color, subTextColor: Color, hasUnreadNotifs: Boolean
) {
    val greeting = remember { val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY); when (hour) { in 0..11 -> "Good Morning,"; in 12..16 -> "Good Afternoon,"; else -> "Good Evening," } }

    Row(modifier = Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f).clickable { onProfileClick() }) {
            Surface(modifier = Modifier.size(50.dp), shape = CircleShape, color = themeColor.copy(alpha = 0.1f), border = BorderStroke(1.dp, themeColor.copy(alpha = 0.5f))) {
                if (profilePicUrl.isEmpty()) { Icon(Icons.Default.AccountCircle, null, tint = themeColor, modifier = Modifier.fillMaxSize()) }
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(greeting, color = subTextColor, style = MaterialTheme.typography.labelMedium)
                Text(userName, color = textColor, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }

        Spacer(Modifier.width(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            // ✨ NEW: The Withdraw Icon
            IconButton(onClick = onWithdrawClick) { Icon(imageVector = Icons.Default.AccountBalance, contentDescription = "Withdraw", tint = themeColor, modifier = Modifier.size(24.dp)) }
            // The existing Top-Up Icon
            IconButton(onClick = onTopUpClick) { Icon(imageVector = Icons.Default.AddCard, contentDescription = "Buy Credits", tint = themeColor, modifier = Modifier.size(28.dp)) }

            Box(modifier = Modifier.clip(CircleShape).clickable { onBellClick() }.padding(8.dp)) {
                Icon(imageVector = Icons.Default.Notifications, contentDescription = "Notifications", tint = themeColor, modifier = Modifier.size(28.dp))
                if (hasUnreadNotifs) { Surface(modifier = Modifier.size(10.dp).align(Alignment.TopEnd), shape = CircleShape, color = Color.Red) {} }
            }
        }
    }
}

@Composable
fun GlassmorphicThemeSwitch(
    isDarkMode: Boolean,
    onThemeToggle: () -> Unit,
    themeColor: Color
) {
    val switchWidth = 64.dp
    val switchHeight = 32.dp
    val thumbSize = 26.dp
    val padding = 3.dp

    // Bouncy spring animation for the sliding thumb
    val thumbOffset by animateDpAsState(
        targetValue = if (isDarkMode) (switchWidth - thumbSize - padding) else padding,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "ThemeSwitchOffset"
    )

    // Smooth color transitions for the background track
    val trackBgColor by animateColorAsState(
        targetValue = if (isDarkMode) Color.White.copy(alpha = 0.1f) else Color.Black.copy(alpha = 0.05f),
        label = "TrackColor"
    )
    val trackBorderColor by animateColorAsState(
        targetValue = if (isDarkMode) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.1f),
        label = "TrackBorder"
    )

    Surface(
        modifier = Modifier
            .size(width = switchWidth, height = switchHeight)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null, // Removes the standard rectangular ripple
                onClick = onThemeToggle
            ),
        shape = RoundedCornerShape(16.dp),
        color = trackBgColor,
        border = BorderStroke(1.dp, trackBorderColor)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {

            // Background Icons (Sun on left, Moon on right)
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.WbSunny, null, tint = if(isDarkMode) Color.Gray.copy(alpha=0.5f) else Color.Transparent, modifier = Modifier.size(14.dp))
                Icon(Icons.Default.NightlightRound, null, tint = if(!isDarkMode) Color.Gray.copy(alpha=0.5f) else Color.Transparent, modifier = Modifier.size(14.dp))
            }

            // The Sliding Thumb
            Surface(
                modifier = Modifier
                    .offset(x = thumbOffset)
                    .size(thumbSize),
                shape = CircleShape,
                color = if (isDarkMode) Color(0xFF0A0E21) else Color.White,
                shadowElevation = if (isDarkMode) 2.dp else 4.dp, // Soft shadow
                border = BorderStroke(0.5.dp, if(isDarkMode) themeColor.copy(alpha=0.3f) else Color(0xFFE0E0E0))
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    // Icon inside the thumb
                    Icon(
                        imageVector = if (isDarkMode) Icons.Default.NightlightRound else Icons.Default.WbSunny,
                        contentDescription = null,
                        // Sun is gold, Moon matches your theme color
                        tint = if (isDarkMode) themeColor else Color(0xFFF9AA33),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
@Composable
fun GlassCard(isDarkMode: Boolean = true, themeColor: Color, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val cardBg = if (isDarkMode) Color.White.copy(alpha = 0.08f) else Color.White
    // ✨ Subtle, almost invisible border in light mode. No more yellow outlines.
    val cardBorder = if (isDarkMode) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.04f)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = BorderStroke(1.dp, cardBorder),
        // ✨ Premium soft shadow in light mode
        elevation = CardDefaults.cardElevation(defaultElevation = if(isDarkMode) 0.dp else 12.dp)
    ) {
        Column(modifier = Modifier.padding(24.dp)) { content() }
    }
}

@Composable
fun GlassShineButton(text: String, icon: ImageVector, bg: Color, tint: Color, isDarkMode: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(targetValue = if (isPressed) 0.92f else 1f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow), label = "ButtonScale")

    // ✨ In Light Mode: Crisp white button. In Dark Mode: Translucent glass.
    val buttonBg = if (isDarkMode) Color.White.copy(alpha = 0.12f) else Color.White
    val textColor = if (isDarkMode) tint else Color(0xFF0A0E21) // Navy text for readability
    val iconColor = tint
    val borderColor = if (isDarkMode) Color.White.copy(alpha = 0.15f) else Color.Transparent

    Button(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier
            .height(60.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale },
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(containerColor = buttonBg),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, borderColor),
        // ✨ Elegant drop shadow for light mode
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = if (isDarkMode) 0.dp else 8.dp,
            pressedElevation = if (isDarkMode) 0.dp else 2.dp
        )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxSize()) {
            Icon(icon, null, tint = iconColor, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(text = text, color = textColor, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedContactsSheet(
    onDismiss: () -> Unit,
    isDarkMode: Boolean,
    themeColor: Color
) {
    val context = LocalContext.current
    val db = remember { DatabaseProvider.getDatabase(context) }
    // ✨ Reads the live flow directly from Room!
    val blockedUsers by db.creditDao().getBlockedUsers().collectAsState(initial = emptyList())

    val sheetBg = if (isDarkMode) Color(0xFF0A0E21).copy(alpha = 0.98f) else Color(0xFFFFF7EB)
    val textColor = if (isDarkMode) Color.White else Color(0xFF0A0E21)
    val subTextColor = if (isDarkMode) Color.Gray else Color(0xFF5F6368)

    val hubPhone = "+919930378399" // ⚠️ CHANGE THIS TO YOUR ACTUAL HUB TABLET NUMBER

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = sheetBg) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp).padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Block, null, tint = Color(0xFFF44336), modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Text("Blocked Contacts", color = textColor, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Text("These numbers cannot send you offline payments. Unblocking them will restore their access.", color = subTextColor, style = MaterialTheme.typography.bodyMedium)

            Spacer(Modifier.height(24.dp))

            if (blockedUsers.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                    Text("No blocked contacts.", color = subTextColor, style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(blockedUsers) { user ->
                        val dateStr = remember(user.blockedAtTimestamp) {
                            val sdf = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault())
                            sdf.format(java.util.Date(user.blockedAtTimestamp))
                        }

                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = if (isDarkMode) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.03f),
                            border = BorderStroke(1.dp, if (isDarkMode) Color.White.copy(alpha = 0.1f) else Color.Transparent)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(user.phoneNumber, color = textColor, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Text("Blocked on $dateStr", color = subTextColor, fontSize = 12.sp)
                                }

                                OutlinedButton(
                                    onClick = {
                                        DisputeManager.unblockUser(context, user.phoneNumber, hubPhone)
                                        Toast.makeText(context, "Unblock command sent to Hub", Toast.LENGTH_SHORT).show()
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, themeColor),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    Text("UNBLOCK", color = themeColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(onBack: () -> Unit, onLogout: () -> Unit, onPickImage: () -> Unit, isDarkMode: Boolean, onThemeToggle: () -> Unit, themeColor: Color, wallets: List<WalletEntity>, activeWallet: WalletEntity?) {
    val context = LocalContext.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val user = FirebaseAuth.getInstance().currentUser
    val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()

    var username by remember { mutableStateOf("Loading...") }
    var phoneNumber by remember { mutableStateOf("") }
    var profilePicUrl by remember { mutableStateOf("") }
    var showShopQrDialog by remember { mutableStateOf(false) }

    // ✨ NEW STATE FOR BLOCKED CONTACTS SHEET
    var showBlockedContacts by remember { mutableStateOf(false) }

    val textColor = if (isDarkMode) Color.White else Color(0xFF0A0E21)
    val subTextColor = if (isDarkMode) Color.Gray else Color(0xFF5F6368)
    val cardBgColor = if (isDarkMode) Color(0xFF13182C) else Color.White
    val cardBorderColor = if (isDarkMode) themeColor.copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.05f)

    val currentVirtualAcc = activeWallet?.virtualAccountId ?: "Not Generated"
    val walletName = activeWallet?.walletName ?: "Primary"

    LaunchedEffect(user?.uid) {
        user?.uid?.let { uid ->
            firestore.collection("users").document(uid).get().addOnSuccessListener { doc ->
                username = doc.getString("username") ?: "User"
                phoneNumber = doc.getString("phone") ?: "Not Set"
                profilePicUrl = doc.getString("profilePic") ?: ""
            }
        }
    }

    if (showShopQrDialog) {
        Dialog(onDismissRequest = { showShopQrDialog = false }) {
            Card(modifier = Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(32.dp), colors = CardDefaults.cardColors(containerColor = if (isDarkMode) Color(0xFF1C2754) else Color.White)) {
                Column(modifier = Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$walletName QR", color = textColor, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Print this for your shop!", color = subTextColor, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(24.dp))
                    Surface(modifier = Modifier.size(220.dp), shape = RoundedCornerShape(16.dp), color = Color.White, shadowElevation = 4.dp) {
                        val encodedName = android.net.Uri.encode(username)
                        val staticShopQr = "paysetu://remote?name=$encodedName&phone=$phoneNumber&va=$currentVirtualAcc"
                        val qrBitmap = remember(staticShopQr) { generateQRCode(staticShopQr) }
                        Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                            qrBitmap?.let { Image(bitmap = it.asImageBitmap(), contentDescription = "Shop QR", modifier = Modifier.fillMaxSize()) }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Text("Scans will securely route directly into this Virtual Account via encrypted SMS.", textAlign = TextAlign.Center, color = themeColor, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(32.dp))
                    Button(onClick = { showShopQrDialog = false }, modifier = Modifier.fillMaxWidth().height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = themeColor)) { Text("CLOSE", color = Color.Black, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }

    PremiumBackground(isDarkMode) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())) {

            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = textColor) }

                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(text = "Profile Settings", style = MaterialTheme.typography.titleMedium, color = textColor, fontWeight = FontWeight.Bold)
                }

                GlassmorphicThemeSwitch(
                    isDarkMode = isDarkMode,
                    onThemeToggle = onThemeToggle,
                    themeColor = themeColor
                )
            }

            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(16.dp))

                ProfileAvatarSection(profilePicUrl, onPickImage, themeColor, isDarkMode)
                Spacer(Modifier.height(16.dp))
                Text(text = username, color = textColor, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                Text(text = user?.email ?: "", color = subTextColor, style = MaterialTheme.typography.bodyMedium)

                Spacer(Modifier.height(32.dp))

                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = cardBgColor,
                    border = BorderStroke(1.dp, cardBorderColor),
                    shadowElevation = if (isDarkMode) 0.dp else 8.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(shape = CircleShape, color = themeColor.copy(alpha = 0.15f)) {
                                    Icon(if(walletName.contains("Business", ignoreCase = true)) Icons.Default.Work else Icons.Default.AccountBalanceWallet, null, tint = themeColor, modifier = Modifier.padding(6.dp).size(16.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(walletName, color = subTextColor, style = MaterialTheme.typography.labelLarge)
                            }
                            IconButton(onClick = { /* Open Color Update Dialog */ }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Palette, "Change Color", tint = themeColor, modifier = Modifier.size(20.dp))
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(currentVirtualAcc, color = textColor, fontWeight = FontWeight.Black, fontSize = 22.sp, letterSpacing = 2.sp)
                            IconButton(
                                onClick = {
                                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(currentVirtualAcc))
                                    Toast.makeText(context, "Account Number Copied", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(32.dp).background(themeColor.copy(alpha = 0.1f), CircleShape)
                            ) {
                                Icon(Icons.Default.ContentCopy, "Copy", tint = themeColor, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Surface(
                    onClick = { showShopQrDialog = true },
                    shape = RoundedCornerShape(16.dp),
                    color = themeColor.copy(alpha = 0.1f),
                    border = BorderStroke(1.dp, themeColor.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.QrCode2, null, tint = themeColor, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Show Static Shop QR", color = themeColor, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(Modifier.height(32.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                    Text("Personal Information", color = subTextColor, style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.height(8.dp))

                GlassCard(isDarkMode, themeColor) {
                    EditableProfileRow("Full Name", username, themeColor, textColor, subTextColor) { newName -> username = newName; syncFieldToFirebase("username", newName) }
                    HorizontalDivider(color = if(isDarkMode) Color.White.copy(alpha=0.05f) else Color(0xFFE0E0E0))
                    EditableProfileRow("Mobile Number", phoneNumber, themeColor, textColor, subTextColor) { newPhone -> phoneNumber = newPhone; syncFieldToFirebase("phone", newPhone) }
                    HorizontalDivider(color = if(isDarkMode) Color.White.copy(alpha=0.05f) else Color(0xFFE0E0E0))
                    EditableProfileRow("Email Address", user?.email ?: "", themeColor, textColor, subTextColor, canEdit = false)
                }

                Spacer(Modifier.height(48.dp))

                // ✨ NEW: BLOCKED CONTACTS BUTTON
                OutlinedButton(
                    onClick = { showBlockedContacts = true },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, themeColor.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = themeColor.copy(alpha = 0.05f))
                ) {
                    Icon(Icons.Default.Block, null, tint = themeColor, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("MANAGE BLOCKED CONTACTS", color = themeColor, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }

                Spacer(Modifier.height(16.dp))

                // LOGOUT BUTTON
                OutlinedButton(
                    onClick = onLogout,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color(0xFFF44336).copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFFF44336).copy(alpha = 0.05f))
                ) {
                    Icon(Icons.Default.Logout, null, tint = Color(0xFFF44336), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("LOGOUT SESSION", color = Color(0xFFF44336), fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
                Spacer(Modifier.height(48.dp))
            }
        }
    }

    // ✨ SHOW THE SHEET IF TRIGGERED
    if (showBlockedContacts) {
        BlockedContactsSheet(
            onDismiss = { showBlockedContacts = false },
            isDarkMode = isDarkMode,
            themeColor = themeColor
        )
    }
}


@Composable
fun ProfileAvatarSection(profilePicUrl: String, onPickImage: () -> Unit, themeColor: Color, isDarkMode: Boolean) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.9f else 1f, label = "AvatarScale")
    val avatarBg = if (isDarkMode) Color(0xFF1C2754) else Color(0xFFF4F7FB)

    Box(
        contentAlignment = Alignment.BottomEnd,
        modifier = Modifier
            .scale(scale)
            .clickable(interactionSource = interactionSource, indication = null) { onPickImage() }
    ) {
        Surface(
            modifier = Modifier.size(110.dp),
            shape = CircleShape,
            color = avatarBg,
            border = BorderStroke(2.dp, themeColor),
            shadowElevation = if (isDarkMode) 0.dp else 8.dp // Soft shadow in light mode
        ) {
            if (profilePicUrl.isEmpty()) {
                Icon(imageVector = Icons.Default.Person, contentDescription = null, tint = themeColor, modifier = Modifier.padding(24.dp))
            }
        }

        // Small edit badge
        Surface(
            modifier = Modifier.size(32.dp).offset(x = (-4).dp, y = (-4).dp),
            shape = CircleShape,
            color = themeColor,
            border = BorderStroke(2.dp, if(isDarkMode) Color(0xFF0A0E21) else Color.White)
        ) {
            Icon(imageVector = Icons.Default.Edit, contentDescription = null, tint = Color.Black, modifier = Modifier.padding(6.dp))
        }
    }
}

@Composable
fun EditableProfileRow(label: String, value: String, themeColor: Color, textColor: Color, subTextColor: Color, canEdit: Boolean = true, onValueChange: (String) -> Unit = {}) {
    var isEditing by remember { mutableStateOf(false) }
    var textValue by remember { mutableStateOf(value) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), // Increased padding for breathability
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = subTextColor, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(4.dp))
            if (isEditing) {
                BasicTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    textStyle = TextStyle(color = textColor, fontWeight = FontWeight.Bold, fontSize = 16.sp),
                    cursorBrush = SolidColor(themeColor),
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text(value, color = textColor, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            }
        }
        if (canEdit) {
            IconButton(onClick = { if (isEditing) onValueChange(textValue); isEditing = !isEditing }, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = if (isEditing) Icons.Default.Check else Icons.Default.Edit,
                    null,
                    tint = if (isEditing) Color(0xFF4CAF50) else subTextColor,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun SuccessScreen(amount: Long, isSender: Boolean, onDone: () -> Unit, isDarkMode: Boolean = true, themeColor: Color) {
    val successGreen = Color(0xFF4CAF50)
    val bg = if (isDarkMode) Color(0xFF0A0E21) else Color(0xFFFFF7EB)
    val textColor = if (isDarkMode) Color.White else Color(0xFF0A0E21)
    val subTextColor = if (isDarkMode) Color.Gray else Color(0xFF5F6368)
    val cardBg = if (isDarkMode) Color.White.copy(alpha = 0.05f) else Color.White
    val borderColor = if (isDarkMode) Color.White.copy(alpha = 0.1f) else themeColor.copy(alpha = 0.5f)

    val txId = remember { "TXN${System.currentTimeMillis().toString().takeLast(8)}" }; val time = remember { formatTransactionDate(System.currentTimeMillis()) }
    val scale = remember { Animatable(0f) }
    LaunchedEffect(Unit) { scale.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow)) }

    Box(modifier = Modifier.fillMaxSize().background(bg), contentAlignment = Alignment.Center) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = successGreen, modifier = Modifier.size(100.dp).graphicsLayer(scaleX = scale.value, scaleY = scale.value))
            Spacer(Modifier.height(16.dp))
            Text("Payment Successful", color = textColor, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(32.dp))
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = BorderStroke(1.dp, borderColor), elevation = CardDefaults.cardElevation(if(isDarkMode) 0.dp else 2.dp)) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("RECEIPT", color = themeColor, style = MaterialTheme.typography.labelLarge, letterSpacing = 2.sp)
                    Spacer(Modifier.height(16.dp))
                    ReceiptRow("Amount", "₹$amount", isValueBold = true, textColor = textColor)
                    ReceiptRow("Type", if (isSender) "Vault Outbound" else "Vault Inbound", textColor = textColor)
                    ReceiptRow("Date", time, textColor = textColor)
                    ReceiptRow("Transaction ID", txId, textColor = textColor)
                    ReceiptRow("Status", "Securely Verified", color = successGreen, textColor = textColor)
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = borderColor)
                    Spacer(Modifier.height(16.dp))
                    Text("SECURE HASH", color = subTextColor, style = MaterialTheme.typography.labelSmall)
                    Text(text = "SHA256: 8f3b...a1e9", color = subTextColor.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Spacer(Modifier.height(48.dp))
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = themeColor)) { Text("DONE", color = Color.Black, fontWeight = FontWeight.Black) }
        }
    }
}

@Composable
fun ReceiptRow(label: String, value: String, isValueBold: Boolean = false, color: Color = Color.White, textColor: Color = Color.White) {
    val finalColor = if (color == Color.White) textColor else color
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, color = finalColor, style = MaterialTheme.typography.bodyMedium, fontWeight = if (isValueBold) FontWeight.ExtraBold else FontWeight.Medium)
    }
}

@Composable
fun OtpScreen(onVerify: (String) -> Unit, isDarkMode: Boolean, themeColor: Color) {
    var otpCode by remember { mutableStateOf("") }
    val navyDark = Color(0xFF0A0E21)
    val textColor = if (isDarkMode) Color.White else Color(0xFF0A0E21)
    val subTextColor = if (isDarkMode) Color.Gray else Color(0xFF5F6368)

    PremiumBackground(isDarkMode) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(60.dp))
            Surface(modifier = Modifier.size(90.dp), shape = CircleShape, color = themeColor.copy(alpha = 0.1f), border = BorderStroke(1.dp, themeColor.copy(alpha = 0.3f))) { Icon(imageVector = Icons.Default.Shield, contentDescription = null, tint = themeColor, modifier = Modifier.padding(20.dp)) }
            Spacer(Modifier.height(32.dp))
            Text(text = "Security Verification", color = textColor, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(12.dp))
            Text(text = "We've sent a 6-digit vault access code to your registered email.", color = subTextColor, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(60.dp))
            BasicTextField(value = otpCode, onValueChange = { if (it.length <= 6 && it.all { char -> char.isDigit() }) otpCode = it }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), decorationBox = { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { repeat(6) { index -> val char = when { index >= otpCode.length -> ""; else -> otpCode[index].toString() }; val isFocused = otpCode.length == index; OtpBox(char, isFocused, themeColor, isDarkMode) } } })
            Spacer(Modifier.height(80.dp))
            // ✨ FIXED: Removed the elevation parameter here too
            Button(
                onClick = { if (otpCode.length == 6) onVerify(otpCode) },
                enabled = otpCode.length == 6,
                modifier = Modifier.fillMaxWidth().height(60.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = themeColor,
                    disabledContainerColor = if(isDarkMode) Color.White.copy(alpha = 0.05f) else Color(0xFFE0E0E0)
                )
            ) {
                Text(text = "VERIFY & ENTER VAULT", color = if (otpCode.length == 6) navyDark else Color.Gray, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            }
        }
    }
}

@Composable
fun OtpBox(char: String, isFocused: Boolean, themeColor: Color, isDarkMode: Boolean = true) {
    val boxBg = if (isFocused) themeColor.copy(alpha = 0.1f) else if (isDarkMode) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.04f)
    val boxBorder = if (isFocused) themeColor else if (isDarkMode) Color.White.copy(alpha = 0.1f) else Color(0xFFE0E0E0)
    val textColor = if (isFocused) themeColor else if (isDarkMode) Color.White else Color(0xFF0A0E21)

    Box(modifier = Modifier.size(width = 48.dp, height = 60.dp).background(color = boxBg, shape = RoundedCornerShape(12.dp)).border(width = if (isFocused) 2.dp else 1.dp, color = boxBorder, shape = RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        Text(text = char, color = textColor, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    }
}
@Composable
fun DeviceMismatchScreen(onSwitchDevice: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0A0E21)).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Hardware Alert", color = Color.Red, style = MaterialTheme.typography.headlineMedium)
        Text("Linked to another device. credits will move to Cloud if you switch.", color = Color.White, textAlign = TextAlign.Center)
        Button(onClick = onSwitchDevice) { Text("Switch to this Device") }
    }
}

@Composable
fun TransactionRowPremium(tx: CreditEntry, onClick: () -> Unit, isDarkMode: Boolean, themeColor: Color) {
    val isIncoming = tx.amount > 0

    // ✨ 1. THE 10-MINUTE TIMEOUT LOGIC (Kept Golden & untouched!)
    val isSystemRefund = tx.senderId.contains("SYSTEM_REFUND") || tx.previousHash == "TIMEOUT"
    val isExpiredOutbound = tx.senderId.startsWith("Expired")

    // ✨ 2. THE MERCHANT FIREWALL LOGIC (Red Reverts)
    val isMerchantContraEntry = tx.previousHash == "CONTRA_ENTRY" // Merchant sent it back
    val isSenderReceivedRefund = tx.note == "Refunded by Receiver" // Sender got it back
    val isExplicitRefund = isMerchantContraEntry || isSenderReceivedRefund

    // ✨ 3. STRIKETHROUGH LOGIC for the original voided transactions
    val isOriginalRefunded = tx.note == "🚨 REFUNDED" || tx.note == "🛑 BLOCKED SENDER"
    val isStruckOut = isExpiredOutbound || isOriginalRefunded

    // Status Colors
    val statusColor = when {
        // Use a slightly darker gold/orange in light mode for readability
        isSystemRefund -> if (isDarkMode) Color(0xFFFF9800) else Color(0xFFD87B00) // Gold
        isExplicitRefund -> Color(0xFFF44336) // Red
        isStruckOut -> Color.Gray // Faded
        isIncoming -> Color(0xFF4CAF50)
        else -> Color(0xFFF44336)
    }

    val textColor = if (isDarkMode) Color.White else Color(0xFF0A0E21)
    val subTextColor = if (isDarkMode) Color.Gray else Color(0xFF5F6368)

    // Base Backgrounds
    val rowBg = when {
        isMerchantContraEntry -> Color(0xFFF44336).copy(alpha = 0.05f)
        isSystemRefund -> if (isDarkMode) Color(0xFFFF9800).copy(alpha = 0.05f) else Color.White
        isExpiredOutbound -> if (isDarkMode) Color.White.copy(alpha = 0.02f) else Color(0xFFF8F9FA)
        isDarkMode -> Color.White.copy(alpha = 0.05f)
        else -> Color.White
    }

    // Crisp Borders & Glows
    val rowBorder = when {
        isMerchantContraEntry -> Color(0xFFF44336).copy(alpha = 0.4f)
        isSystemRefund -> if (isDarkMode) Color(0xFFFF9800).copy(alpha = 0.4f) else themeColor.copy(alpha = 0.6f)
        isExpiredOutbound -> if (isDarkMode) Color.Transparent else Color.Black.copy(alpha = 0.05f)
        isDarkMode -> Color.Transparent
        else -> Color.Black.copy(alpha = 0.04f) // Extremely subtle border for light mode depth
    }

    // Soft Shadows for Light Mode
    val shadowElevation = if (isDarkMode || isStruckOut) 0.dp else 3.dp

    // Extract clean name for the UI
    val parts = tx.senderId.replace("SMS: ", "").replace("Remote Sent to ", "").split("|")
    val rawName = if (parts.isNotEmpty() && parts[0].isNotBlank() && parts[0] != "Unknown") parts[0] else "Offline User"

    // ✨ UI Metadata Logic (Added "Voided: " prefix here!)
    val (title, iconColor, icon) = when {
        isSystemRefund -> Triple("Reverted : Link Expired", if (isDarkMode) Color(0xFFFF9800) else Color(0xFFD87B00), Icons.Default.SettingsBackupRestore)
        isExpiredOutbound -> Triple("Link Expired (Unclaimed)", Color.Gray, Icons.Default.TimerOff)

        isMerchantContraEntry -> Triple("Refunded to $rawName", Color(0xFFF44336), Icons.Default.SettingsBackupRestore)
        isSenderReceivedRefund -> Triple("Refunded by $rawName", Color(0xFFF44336), Icons.Default.SettingsBackupRestore)

        // ✨ THE FIX: Visually marking the original transaction as Voided
        isOriginalRefunded -> if (isIncoming) Triple("Voided: Received from $rawName", Color.Gray, Icons.Default.CallReceived) else Triple("Voided: Payment to $rawName", Color.Gray, Icons.Default.CallMade)

        tx.senderId == "BANK_SIM" -> Triple("Bank Deposit", Color(0xFF4CAF50), Icons.Default.AccountBalance)
        tx.senderId == "BANK_WITHDRAWAL" -> Triple("Bank Withdrawal", Color(0xFFF44336), Icons.Default.AccountBalance)
        isIncoming -> Triple("Received from $rawName", Color(0xFF4CAF50), Icons.Default.CallReceived)
        else -> Triple("Payment to $rawName", themeColor, Icons.Default.CallMade)
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = rowBg,
        border = BorderStroke(1.dp, rowBorder),
        shadowElevation = shadowElevation,
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        // Subtle Inner Gradient just for Refunds to make them pop
        Box(modifier = Modifier.fillMaxSize().then(
            if (isSystemRefund) Modifier.background(
                Brush.horizontalGradient(colors = listOf(Color(0xFFFF9800).copy(alpha = if (isDarkMode) 0.1f else 0.05f), Color.Transparent))
            )
            else if (isExplicitRefund) Modifier.background(
                Brush.horizontalGradient(colors = listOf(Color(0xFFF44336).copy(alpha = if (isDarkMode) 0.1f else 0.05f), Color.Transparent))
            )
            else Modifier
        )) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(modifier = Modifier.size(42.dp), shape = CircleShape, color = iconColor.copy(alpha = 0.15f)) {
                    Icon(imageVector = icon, contentDescription = null, tint = iconColor, modifier = Modifier.padding(10.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = if(isStruckOut) subTextColor else textColor,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        textDecoration = if(isStruckOut) androidx.compose.ui.text.style.TextDecoration.LineThrough else null
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = formatTransactionDate(tx.timestamp), color = subTextColor, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(6.dp))
                        Icon(imageVector = if (tx.isSynced) Icons.Default.CloudDone else Icons.Default.CloudOff, contentDescription = "Sync Status", tint = if (tx.isSynced && !isStruckOut) Color(0xFF4CAF50) else Color.Gray.copy(alpha = 0.5f), modifier = Modifier.size(14.dp))
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${if (isIncoming || isSystemRefund || isSenderReceivedRefund) "+" else "-"} ₹${kotlin.math.abs(tx.amount)}",
                    color = statusColor,
                    fontWeight = FontWeight.ExtraBold,
                    style = MaterialTheme.typography.titleMedium,
                    textDecoration = if(isStruckOut) androidx.compose.ui.text.style.TextDecoration.LineThrough else null
                )
            }
        }
    }
}

@Composable
fun PremiumBackground(isDarkMode: Boolean = true, content: @Composable BoxScope.() -> Unit) {
    // ✨ Cooler, cleaner light mode background instead of yellow-cream
    val bgTop = if (isDarkMode) Color(0xFF0A0E21) else Color(0xFFF4F7FB)
    val bgBottom = if (isDarkMode) Color(0xFF000000) else Color(0xFFFFFFFF)
    Box(modifier = Modifier.fillMaxSize().background(brush = Brush.verticalGradient(colors = listOf(bgTop, bgBottom), startY = 0f, endY = Float.POSITIVE_INFINITY))) { content() }
}
@Composable
fun PaySetuBottomBar(currentTab: Int, onTabClick: (Int) -> Unit, onQRClick: () -> Unit, isDarkMode: Boolean, themeColor: Color) {
    // ✨ Pure white bar in light mode, translucent navy in dark mode
    val barBg = if (isDarkMode) Color(0xFF13182C).copy(alpha = 0.95f) else Color.White
    val borderColor = if (isDarkMode) Color.White.copy(alpha = 0.1f) else Color.Black.copy(alpha = 0.04f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp)
            .navigationBarsPadding(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(72.dp),
            shape = RoundedCornerShape(28.dp),
            color = barBg,
            border = BorderStroke(1.dp, borderColor),
            shadowElevation = if(isDarkMode) 0.dp else 20.dp // ✨ Heavy soft shadow makes it float!
        ) {
            Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
                DockIconButton(Icons.Default.Home, currentTab == 0, themeColor, isDarkMode) { onTabClick(0) }
                Spacer(Modifier.width(80.dp))
                DockIconButton(Icons.Default.History, currentTab == 1, themeColor, isDarkMode) { onTabClick(1) }
            }
        }

        Box(modifier = Modifier.offset(y = (-36).dp)) {
            FloatingActionButton(
                onClick = onQRClick,
                modifier = Modifier.size(68.dp),
                containerColor = themeColor,
                contentColor = Color(0xFF0A0E21),
                shape = CircleShape,
                elevation = FloatingActionButtonDefaults.elevation(if(isDarkMode) 0.dp else 8.dp)
            ) {
                Icon(Icons.Default.QrCodeScanner, null, modifier = Modifier.size(32.dp))
            }
        }
    }
}

@Composable
fun DockIconButton(icon: ImageVector, isSelected: Boolean, themeColor: Color, isDarkMode: Boolean, onClick: () -> Unit) {
    val inactiveTint = if (isDarkMode) Color.Gray.copy(alpha = 0.6f) else Color.Gray
    val dotScale by animateFloatAsState(targetValue = if (isSelected) 1f else 0f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "DotScale")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() }) {
        Icon(imageVector = icon, contentDescription = null, tint = if (isSelected) themeColor else inactiveTint, modifier = Modifier.size(28.dp))
        Box(modifier = Modifier.padding(top = 4.dp).size(4.dp).scale(dotScale).background(themeColor, CircleShape))
    }
}

@Composable
fun PremiumCashFlowCard(history: List<CreditEntry>, isDarkMode: Boolean, themeColor: Color) {
    val textColor = if (isDarkMode) Color.White else Color(0xFF0A0E21)
    val subTextColor = if (isDarkMode) Color.Gray else Color(0xFF5F6368)

    // Calculate Totals
    val totalIn = history.filter { it.amount > 0 }.sumOf { it.amount }.toFloat()
    val totalOut = history.filter { it.amount < 0 }.sumOf { kotlin.math.abs(it.amount) }.toFloat()
    val totalVolume = totalIn + totalOut

    // Calculate the ratio for the animation (default to 0.5 if no data)
    val inPercent = if (totalVolume > 0) (totalIn / totalVolume) else 0.5f

    // Smooth entry animation
    var startAnimation by remember { mutableStateOf(false) }
    val animatedRatio by animateFloatAsState(
        targetValue = if (startAnimation) inPercent else 0f,
        animationSpec = tween(1200, easing = FastOutSlowInEasing),
        label = "RatioAnimation"
    )
    LaunchedEffect(Unit) { startAnimation = true }

    // Dynamic AI-like Insight Text
    val insightText = when {
        totalVolume == 0f -> "No recent activity to analyze. Add funds to begin."
        totalIn > totalOut -> "Great job! You're keeping more funds than you're sending out."
        totalIn < totalOut -> "You're sending more out of this vault than you're adding."
        else -> "Your cash flow is perfectly balanced right now."
    }

    GlassCard(isDarkMode, themeColor = themeColor) {
        // Header
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Cash Flow", color = textColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Surface(
                color = themeColor.copy(alpha = 0.1f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.DataUsage, null, tint = themeColor, modifier = Modifier.padding(6.dp).size(16.dp))
            }
        }

        Spacer(Modifier.height(28.dp))

        // ✨ Modern Animated Ratio Bar
        Canvas(modifier = Modifier.fillMaxWidth().height(14.dp)) {
            val width = size.width
            val height = size.height
            val cornerRadius = androidx.compose.ui.geometry.CornerRadius(height / 2, height / 2)

            // Draw background (Red / Money Out)
            drawRoundRect(
                color = Color(0xFFF44336).copy(alpha = 0.8f),
                size = androidx.compose.ui.geometry.Size(width, height),
                cornerRadius = cornerRadius
            )

            // Draw foreground over it (Green / Money In)
            drawRoundRect(
                color = Color(0xFF4CAF50).copy(alpha = 0.9f),
                size = androidx.compose.ui.geometry.Size(width * animatedRatio, height),
                cornerRadius = cornerRadius
            )
        }

        Spacer(Modifier.height(28.dp))

        // Income vs Expense Breakdown
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            // INCOME (Left)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = Color(0xFF4CAF50).copy(alpha = 0.15f)) {
                    Icon(Icons.Default.TrendingUp, null, tint = Color(0xFF4CAF50), modifier = Modifier.padding(6.dp).size(16.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Money In", color = subTextColor, style = MaterialTheme.typography.labelSmall)
                    Text("₹${totalIn.toInt()}", color = textColor, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
                }
            }

            // SPENT (Right)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.End) {
                    Text("Money Out", color = subTextColor, style = MaterialTheme.typography.labelSmall)
                    Text("₹${totalOut.toInt()}", color = textColor, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.width(12.dp))
                Surface(shape = CircleShape, color = Color(0xFFF44336).copy(alpha = 0.15f)) {
                    Icon(Icons.Default.TrendingDown, null, tint = Color(0xFFF44336), modifier = Modifier.padding(6.dp).size(16.dp))
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider(color = if (isDarkMode) Color.White.copy(alpha = 0.1f) else Color.Gray.copy(alpha = 0.2f))
        Spacer(Modifier.height(16.dp))

        // Dynamic Insight Message
        Text(
            text = insightText,
            color = subTextColor,
            style = MaterialTheme.typography.bodySmall,
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
        )
    }
}

@Composable
fun AnimatedVaultStats(history: List<CreditEntry>, isDarkMode: Boolean, themeColor: Color) {
    val textColor = if (isDarkMode) Color.White else Color(0xFF0A0E21)
    val subTextColor = if (isDarkMode) Color.Gray else Color(0xFF5F6368)

    val totalIn = history.filter { it.amount > 0 }.sumOf { it.amount }.toFloat()
    val totalOut = history.filter { it.amount < 0 }.sumOf { kotlin.math.abs(it.amount) }.toFloat()

    val weeklySpending = remember(totalOut) {
        if (totalOut == 0f) listOf(0f, 0f, 0f, 0f) else listOf(totalOut * 0.15f, totalOut * 0.40f, totalOut * 0.25f, totalOut * 0.20f)
    }
    val maxSpend = weeklySpending.maxOrNull() ?: 1f

    var startAnimation by remember { mutableStateOf(false) }
    val animationProgress by animateFloatAsState(targetValue = if (startAnimation) 1f else 0f, animationSpec = tween(1500, easing = FastOutSlowInEasing), label = "ChartProgress")
    LaunchedEffect(Unit) { startAnimation = true }

    GlassCard(isDarkMode, themeColor = themeColor) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Spending Insights", color = textColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Icon(Icons.Default.Insights, null, tint = themeColor, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(24.dp))
        Row(modifier = Modifier.fillMaxWidth().height(110.dp).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            weeklySpending.forEachIndexed { index, spend ->
                val barHeight = if (maxSpend > 0) (spend / maxSpend) * 70f * animationProgress else 0f
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    Box(modifier = Modifier.width(28.dp).height(barHeight.dp.coerceAtLeast(4.dp)).clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)).background(Brush.verticalGradient(listOf(Color(0xFFF44336), Color(0xFFF44336).copy(alpha = 0.5f)))))
                    Spacer(Modifier.height(8.dp)); Text("W${index + 1}", color = subTextColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(24.dp)); HorizontalDivider(color = if (isDarkMode) Color.White.copy(alpha = 0.1f) else Color.Gray.copy(alpha = 0.2f)); Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column { Text("Total In", color = subTextColor, style = MaterialTheme.typography.labelSmall); Text("₹${totalIn.toInt()}", color = Color(0xFF4CAF50), fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium) }
            Column(horizontalAlignment = Alignment.End) { Text("Total Out", color = subTextColor, style = MaterialTheme.typography.labelSmall); Text("₹${totalOut.toInt()}", color = Color(0xFFF44336), fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium) }
        }
    }
}

@Composable
fun AmountEntryDialog(targetName: String, amountText: String, balance: Long, isSenderRole: Boolean, onAmountChange: (String) -> Unit, onDismiss: () -> Unit, onVerifyAndSend: (Long) -> Unit, isDarkMode: Boolean, themeColor: Color) {
    val secureGreen = Color(0xFF4CAF50)
    val glassColor = if (isDarkMode) Color(0xFF1C2754).copy(alpha = 0.95f) else Color.White
    val textColor = if (isDarkMode) Color.White else Color(0xFF0A0E21); val subTextColor = if (isDarkMode) Color.Gray else Color(0xFF5F6368)

    val scale = remember { Animatable(0.7f) }; val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) { launch { scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)) }; launch { alpha.animateTo(1f, tween(200)) } }

    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth().padding(16.dp).graphicsLayer(scaleX = scale.value, scaleY = scale.value, alpha = alpha.value), shape = RoundedCornerShape(32.dp), colors = CardDefaults.cardColors(containerColor = glassColor), border = BorderStroke(1.dp, if(isDarkMode) Color.White.copy(alpha = 0.15f) else Color(0xFFE0E0E0)), elevation = CardDefaults.cardElevation(if(isDarkMode) 0.dp else 4.dp)) {
            Column(modifier = Modifier.padding(vertical = 32.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(color = secureGreen.copy(alpha = 0.1f), shape = CircleShape, border = BorderStroke(1.dp, secureGreen.copy(alpha = 0.3f))) {
                    Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Shield, null, tint = secureGreen, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(6.dp)); Text(if(isSenderRole) "SECURE LINK ACTIVE" else "BANK LINK SECURED", color = secureGreen, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.height(24.dp)); Text(if(isSenderRole) "PAYING TO" else "TOPUP FROM", color = subTextColor, style = MaterialTheme.typography.labelSmall, letterSpacing = 2.sp)
                val displayTargetName = targetName.split("|").firstOrNull { it.isNotBlank() } ?: targetName
                Text(displayTargetName, color = textColor, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(40.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("₹", color = themeColor, fontSize = 36.sp, fontWeight = FontWeight.Black); Spacer(Modifier.width(12.dp))
                    BasicTextField(value = amountText, onValueChange = { if (it.all { char -> char.isDigit() }) onAmountChange(it) }, textStyle = TextStyle(color = themeColor, fontSize = 64.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center), cursorBrush = SolidColor(themeColor), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(IntrinsicSize.Min), decorationBox = { innerTextField -> if (amountText.isEmpty()) Text("0", color = themeColor.copy(alpha = 0.2f), fontSize = 64.sp, fontWeight = FontWeight.Black); innerTextField() })
                }
                val amountValue = amountText.toLongOrNull() ?: 0L; val isInsufficient = isSenderRole && amountValue > balance
                Text(text = "Vault Balance: ₹$balance", modifier = Modifier.padding(top = 12.dp), color = if (isInsufficient) Color.Red else subTextColor, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(48.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(54.dp)) { Text("CANCEL", color = subTextColor, fontWeight = FontWeight.Bold) }
                    Button(onClick = { if (amountValue > 0 && (!isSenderRole || amountValue <= balance)) onVerifyAndSend(amountValue) }, enabled = amountText.isNotEmpty() && (!isSenderRole || amountValue <= balance), modifier = Modifier.weight(1.2f).height(54.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = themeColor, disabledContainerColor = if(isDarkMode) Color.White.copy(alpha = 0.1f) else Color(0xFFE0E0E0))) {
                        Text(if(isSenderRole) "TRANSFER" else "CONFIRM", color = Color(0xFF0A0E21), fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
    }
}

@Composable
fun ProcessingScreen(isDarkMode: Boolean = true, themeColor: Color) {
    val bg = if (isDarkMode) Color(0xFF0A0E21) else Color(0xFFFFF7EB)
    val textColor = if (isDarkMode) Color.White else Color(0xFF0A0E21)
    val subTextColor = if (isDarkMode) Color.Gray else Color(0xFF5F6368)

    val infiniteTransition = rememberInfiniteTransition(label = "ProcessingPulsing")
    val alpha by infiniteTransition.animateFloat(initialValue = 0.4f, targetValue = 1f, animationSpec = infiniteRepeatable(animation = tween(1000, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse), label = "Alpha")

    Box(modifier = Modifier.fillMaxSize().background(bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(contentAlignment = Alignment.Center) { CircularProgressIndicator(modifier = Modifier.size(100.dp), color = themeColor, strokeWidth = 4.dp, trackColor = if(isDarkMode) Color.White.copy(alpha = 0.1f) else Color.Gray.copy(alpha=0.2f)); Icon(imageVector = Icons.Default.Shield, contentDescription = null, tint = themeColor, modifier = Modifier.size(40.dp)) }
            Spacer(Modifier.height(32.dp))
            Text(text = "Securing Vault...", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = textColor.copy(alpha = alpha))
            Spacer(Modifier.height(8.dp))
            Text(text = "Encrypting transaction data", style = MaterialTheme.typography.bodyMedium, color = subTextColor)
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisputeMenuSheet(
    txHash: String,
    senderId: String,
    onDismiss: () -> Unit,
    isDarkMode: Boolean,
    themeColor: Color
) {
    val context = LocalContext.current
    val sheetBg = if (isDarkMode) Color(0xFF0A0E21).copy(alpha = 0.98f) else Color(0xFFFFF7EB)
    val textColor = if (isDarkMode) Color.White else Color(0xFF0A0E21)
    val subTextColor = if (isDarkMode) Color.Gray else Color(0xFF5F6368)

    val hubPhone = "+919920833792" // ⚠️ CHANGE TO YOUR HUB NUMBER

    val parts = senderId.split("|")
    val cleanPhone = if (parts.size >= 2) parts[1].replace(Regex("[^0-9]"), "").takeLast(10) else senderId

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = sheetBg) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp).padding(bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Suspicious Transaction?", color = textColor, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("What would you like to do?", color = subTextColor, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { DisputeManager.triggerDisputeAction(context, txHash, cleanPhone, "REFUND", hubPhone); onDismiss() },
                modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B))
            ) { Text("Refund Transaction", color = Color.Black, fontWeight = FontWeight.Bold) }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = { DisputeManager.triggerDisputeAction(context, txHash, cleanPhone, "BLOCK", hubPhone); onDismiss() },
                modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
            ) { Text("Block Sender", color = Color.White, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
fun ReceivingScreen(onStop: () -> Unit, userName: String, isDarkMode: Boolean = true, themeColor: Color) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val successGreen = Color(0xFF4CAF50)
    val textColor = if (isDarkMode) Color.White else Color(0xFF0A0E21)
    val subTextColor = if (isDarkMode) Color.White.copy(alpha = 0.5f) else Color(0xFF5F6368)

    // ✨ Hoisted the QR Bitmap so the Share button can use it too!
    val qrBitmap = remember(userName) { generateQRCode(userName) }

    val infiniteTransition = rememberInfiniteTransition(label = "QR_Animations")
    val scale by infiniteTransition.animateFloat(initialValue = 1f, targetValue = 1.15f, animationSpec = infiniteRepeatable(animation = tween(1200, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse), label = "GlowScale")
    val glowAlpha by infiniteTransition.animateFloat(initialValue = 0.2f, targetValue = 0.6f, animationSpec = infiniteRepeatable(animation = tween(1200, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse), label = "GlowAlpha")
    val laserOffset by infiniteTransition.animateFloat(initialValue = 0f, targetValue = 260f, animationSpec = infiniteRepeatable(animation = tween(2000, easing = LinearEasing), repeatMode = RepeatMode.Restart), label = "LaserOffset")

    PremiumBackground(isDarkMode) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp).statusBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = "Receive Credits", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = textColor)
            Text(text = "Ask the sender to scan this code", style = MaterialTheme.typography.bodyMedium, color = subTextColor)

            Spacer(Modifier.height(60.dp))

            Box(contentAlignment = Alignment.Center) {
                Surface(modifier = Modifier.size(240.dp).scale(scale), shape = RoundedCornerShape(32.dp), color = themeColor.copy(alpha = glowAlpha)) {}
                Card(modifier = Modifier.size(260.dp), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)) {
                    Box(modifier = Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                        qrBitmap?.let { Image(bitmap = it.asImageBitmap(), contentDescription = "QR Code", modifier = Modifier.fillMaxSize()) }
                        // Awesome laser effect!
                        Box(modifier = Modifier.fillMaxWidth().height(4.dp).offset(y = laserOffset.dp - 130.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, successGreen, Color.Transparent))).shadow(8.dp, spotColor = successGreen))
                    }
                }
            }

            Spacer(Modifier.height(48.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = themeColor, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(text = "Broadcasting Secure Signal...", color = successGreen, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.weight(1f))

            // ✨ NEW: SHARE QR BUTTON
            Button(
                onClick = {
                    qrBitmap?.let {
                        // Convert Compose Color to native Android Color Int
                        val themeColorInt = android.graphics.Color.argb(
                            (themeColor.alpha * 255).toInt(),
                            (themeColor.red * 255).toInt(),
                            (themeColor.green * 255).toInt(),
                            (themeColor.blue * 255).toInt()
                        )
                        // Note: If userName contains extra payload data (like paysetu://...),
                        // you might want to split it: userName.split("|").first()
                        shareBrandedQrCode(context, it, userName, themeColorInt)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = themeColor)
            ) {
                Icon(Icons.Default.IosShare, null, tint = Color.Black, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("SHARE QR CODE", color = Color.Black, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            }

            Spacer(Modifier.height(16.dp))

            OutlinedButton(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, if(isDarkMode) Color.White.copy(alpha = 0.2f) else Color(0xFFE0E0E0))
            ) {
                Text("Cancel & Go Back", color = textColor, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationTray(notifications: List<NotificationEntry>, onDismiss: () -> Unit, isDarkMode: Boolean = true) {
    val sheetBg = if (isDarkMode) Color(0xFF1C2754).copy(alpha = 0.95f) else Color.White
    val textColor = if (isDarkMode) Color.White else Color(0xFF0A0E21)

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = sheetBg) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 40.dp, start = 24.dp, end = 24.dp)) {
            Text(text = "Vault Alerts", color = textColor, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(20.dp))
            if (notifications.isEmpty()) { Text(text = "No new security alerts.", color = Color.Gray, modifier = Modifier.padding(vertical = 40.dp)) } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) { items(notifications) { alert -> NotificationItem(alert, isDarkMode) } }
            }
        }
    }
}

@Composable
fun NotificationItem(alert: NotificationEntry, isDarkMode: Boolean = true) {
    val textColor = if (isDarkMode) Color.White else Color(0xFF0A0E21)
    val subTextColor = if (isDarkMode) Color.Gray else Color(0xFF5F6368)
    val cardBg = if (isDarkMode) Color.White.copy(alpha = 0.05f) else Color.White
    val borderColor = if (isDarkMode) Color.Transparent else Color(0xFFE0E0E0)

    val isCritical = alert.title.contains("fraud", ignoreCase = true) || alert.title.contains("tamper", ignoreCase = true) || alert.title.contains("duplicate", ignoreCase = true)
    val iconColor = if (isCritical) Color(0xFFF44336) else Color(0xFFF9AA33)

    Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cardBg).border(1.dp, borderColor, RoundedCornerShape(16.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(modifier = Modifier.size(40.dp), shape = CircleShape, color = iconColor.copy(alpha = 0.1f)) { Icon(if(isCritical) Icons.Default.Warning else Icons.Default.NotificationsActive, null, tint = iconColor, modifier = Modifier.padding(10.dp)) }
        Spacer(Modifier.width(16.dp))
        Column { Text(alert.title, color = if(isCritical) Color(0xFFF44336) else textColor, fontWeight = FontWeight.Bold); Text(alert.message, color = subTextColor, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
fun ScanningScreen(devices: Map<String, String>, onDeviceSelected: (String) -> Unit, onCancel: () -> Unit, isDarkMode: Boolean, themeColor: Color) {
    val textColor = if (isDarkMode) Color.White else Color(0xFF0A0E21)
    val subTextColor = if (isDarkMode) Color.White.copy(alpha = 0.5f) else Color(0xFF5F6368)

    val infiniteTransition = rememberInfiniteTransition(label = "RadarPulse")
    val radius by infiniteTransition.animateFloat(initialValue = 0f, targetValue = 400f, animationSpec = infiniteRepeatable(animation = tween(2000, easing = LinearEasing), repeatMode = RepeatMode.Restart), label = "Radius")
    val opacity by infiniteTransition.animateFloat(initialValue = 0.6f, targetValue = 0f, animationSpec = infiniteRepeatable(animation = tween(2000, easing = LinearEasing), repeatMode = RepeatMode.Restart), label = "Opacity")

    PremiumBackground(isDarkMode) {
        Canvas(modifier = Modifier.fillMaxSize()) { drawCircle(color = themeColor, radius = radius * density, center = center, alpha = opacity); drawCircle(color = themeColor.copy(alpha = 0.2f), radius = 100f * density, center = center) }
        Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(40.dp))
            Text(text = "Finding Wallets...", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = textColor)
            Text(text = "Ensure the receiver has clicked 'RECEIVE'", style = MaterialTheme.typography.bodyMedium, color = subTextColor)
            Spacer(Modifier.height(60.dp))
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (devices.isEmpty()) { item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = themeColor, modifier = Modifier.size(30.dp)) } } } else {
                    items(devices.toList()) { (id, name) ->
                        Card(modifier = Modifier.fillMaxWidth().clickable { onDeviceSelected(id) }, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = if(isDarkMode) Color.White.copy(alpha = 0.08f) else Color.White), border = BorderStroke(1.dp, if(isDarkMode) Color.White.copy(alpha = 0.1f) else themeColor.copy(alpha=0.5f)), elevation = CardDefaults.cardElevation(if(isDarkMode) 0.dp else 2.dp)) {
                            Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                                Surface(modifier = Modifier.size(40.dp), shape = CircleShape, color = themeColor.copy(alpha = 0.1f)) { Icon(Icons.Default.Devices, null, tint = themeColor, modifier = Modifier.padding(8.dp)) }
                                Spacer(Modifier.width(16.dp))
                                val displayName = name.split("|").firstOrNull { it.isNotBlank() } ?: name
                                Text(text = displayName, color = textColor, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.weight(1f)); Icon(Icons.Default.ChevronRight, null, tint = themeColor)
                            }
                        }
                    }
                }
            }
            Button(onClick = onCancel, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = if(isDarkMode) Color.White.copy(alpha = 0.1f) else Color.White), border = BorderStroke(1.dp, if(isDarkMode) Color.Transparent else Color(0xFFE0E0E0))) { Text("Cancel Search", color = textColor) }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartRoutingManagerSheet(onDismiss: () -> Unit, isDarkMode: Boolean, themeColor: Color, wallets: List<WalletEntity>) {
    val context = LocalContext.current
    val sheetBg = if (isDarkMode) Color(0xFF0A0E21).copy(alpha = 0.98f) else Color(0xFFFFF7EB)
    val textColor = if (isDarkMode) Color.White else Color(0xFF0A0E21)
    val subTextColor = if (isDarkMode) Color.Gray else Color(0xFF5F6368)

    var rules by remember { mutableStateOf<List<RoutingRule>>(emptyList()) }
    var newPhone by remember { mutableStateOf("") }
    var selectedWallet by remember { mutableStateOf(wallets.firstOrNull()) }

    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickContact()
    ) { uri ->
        uri?.let {
            val rawNumber = getPhoneNumberFromUri(context, it)
            if (rawNumber != null) {
                val cleanNumber = rawNumber.replace(Regex("[^0-9]"), "").takeLast(10)
                newPhone = cleanNumber
            } else {
                Toast.makeText(context, "No phone number found", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val loadedRules = DatabaseProvider.getDatabase(context).creditDao().getAllRoutingRulesDirect()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                rules = loadedRules
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = sheetBg) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp).padding(bottom = 32.dp)) {
            Text("Smart Routing Rules", color = textColor, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Automatically route incoming money to specific Virtual Accounts.", color = subTextColor, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(24.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) {
                    AuthTextField(
                        value = newPhone,
                        onValueChange = { if (it.length <= 10 && it.all { char -> char.isDigit() }) newPhone = it },
                        label = "Phone Number",
                        icon = Icons.Default.Phone,
                        isDarkMode = isDarkMode,
                        themeColor = themeColor,
                        trailingIcon = {
                            IconButton(onClick = { contactPickerLauncher.launch(null) }) {
                                Icon(Icons.Default.Contacts, "Pick Contact", tint = themeColor)
                            }
                        }
                    )
                }
                Spacer(Modifier.width(12.dp))
                /*
                Surface(
                    color = themeColor.copy(alpha = 0.1f), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, themeColor),
                    modifier = Modifier.clickable {
                        if (wallets.isNotEmpty()) {
                            val currentIndex = wallets.indexOf(selectedWallet)
                            val nextIndex = (currentIndex + 1) % wallets.size
                            selectedWallet = wallets[nextIndex]
                        }
                    }
                ) {
                    val displayWalletName = selectedWallet?.walletName ?: "Primary"
                    Text(displayWalletName, color = themeColor, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp))
                }
                */

            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val finalCleanPhone = newPhone.replace(Regex("[^0-9]"), "").takeLast(10)
                    if (finalCleanPhone.length == 10 && selectedWallet != null) {
                        val rule = RoutingRule(finalCleanPhone, selectedWallet!!.virtualAccountId)
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            val db = DatabaseProvider.getDatabase(context)
                            db.creditDao().saveRoutingRule(rule)
                            val updatedRules = db.creditDao().getAllRoutingRulesDirect()
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                rules = updatedRules
                                newPhone = ""
                                Toast.makeText(context, "VIP Rule Added", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        Toast.makeText(context, "Enter a valid 10-digit number", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = themeColor)
            ) { Text("ADD RULE", color = Color.Black, fontWeight = FontWeight.Bold) }

            Spacer(Modifier.height(32.dp))
            HorizontalDivider(color = themeColor.copy(alpha = 0.2f))
            Spacer(Modifier.height(16.dp))

            Text("Active VIP List", color = textColor, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
            if (rules.isEmpty()) {
                Text("No routing rules set.", color = subTextColor, style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    items(rules) { rule ->
                        val targetName = wallets.find { it.virtualAccountId == rule.targetVirtualAccount }?.walletName ?: "Primary"
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(if(targetName == "Business") Icons.Default.Work else Icons.Default.Person, null, tint = subTextColor, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(12.dp))
                                Text("${rule.phoneNumber} ➔ $targetName", color = textColor, fontWeight = FontWeight.Medium)
                            }
                            IconButton(onClick = {
                                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                    val db = DatabaseProvider.getDatabase(context)
                                    db.creditDao().deleteRoutingRule(rule.phoneNumber)
                                    val updatedRules = db.creditDao().getAllRoutingRulesDirect()
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        rules = updatedRules
                                    }
                                }
                            }) { Icon(Icons.Default.Delete, "Remove", tint = Color.Red.copy(alpha = 0.7f)) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostScanChoiceDialog(
    receiverName: String,
    onNearbySelected: () -> Unit,
    onRemoteSelected: () -> Unit,
    onDismiss: () -> Unit,
    isDarkMode: Boolean,
    themeColor: Color
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    // Keep it dark for the "call" vibe even in light mode for maximum contrast and premium feel
    val bg = Color(0xFF0A0E21)
    val textColor = Color.White

    // Radar Pulse Animation
    val infiniteTransition = rememberInfiniteTransition(label = "RadarPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 2.5f,
        animationSpec = infiniteRepeatable(tween(2000, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "PulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(2000, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "PulseAlpha"
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false) // ✨ Makes it Full Screen!
    ) {
        Box(modifier = Modifier.fillMaxSize().background(bg)) {

            // --- TOP: THE INCOMING CALL AVATAR ---
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 100.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(160.dp)) {
                    // Expanding Pulse Ring
                    Surface(
                        modifier = Modifier.fillMaxSize().scale(pulseScale),
                        shape = CircleShape,
                        color = themeColor.copy(alpha = pulseAlpha)
                    ) {}

                    // Core Avatar
                    Surface(
                        modifier = Modifier.size(90.dp),
                        shape = CircleShape,
                        color = themeColor.copy(alpha = 0.2f),
                        border = BorderStroke(2.dp, themeColor)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = receiverName.firstOrNull()?.uppercase() ?: "?",
                                color = themeColor,
                                fontSize = 40.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }

                Spacer(Modifier.height(40.dp))

                Text(
                    text = "SECURE LINK READY",
                    color = themeColor,
                    style = MaterialTheme.typography.labelMedium,
                    letterSpacing = 4.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Pay ${receiverName.split(" ").first()}",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Black,
                    color = textColor
                )
            }

            // --- BOTTOM: THE SWIPE ACTIONS ---
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 60.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                SwipeActionSlider(
                    onNearbyTriggered = {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        onNearbySelected()
                    },
                    onRemoteTriggered = {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        onRemoteSelected()
                    },
                    themeColor = themeColor
                )

                Spacer(Modifier.height(48.dp))

                TextButton(onClick = onDismiss) {
                    Text("CANCEL CONNECTION", color = Color.Gray, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            }
        }
    }
}


@Composable
fun SwipeActionSlider(
    onNearbyTriggered: () -> Unit,
    onRemoteTriggered: () -> Unit,
    themeColor: Color
) {
    val trackWidth = 320.dp
    val thumbSize = 72.dp

    val density = androidx.compose.ui.platform.LocalDensity.current
    val maxDragPx = with(density) { ((trackWidth - thumbSize) / 2).toPx() }
    val triggerThresholdPx = maxDragPx * 0.7f

    val offsetX = remember { androidx.compose.animation.core.Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    Column(horizontalAlignment = Alignment.CenterHorizontally) {

        // ✨ UPGRADED MARKETING TEXT BOX (Now handles two lines beautifully)
        Box(
            modifier = Modifier.heightIn(min = 40.dp).padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            val dragRatio = (offsetX.value / triggerThresholdPx).coerceIn(-1f, 1f)
            val alphaNearby = dragRatio.coerceAtLeast(0f)
            val alphaRemote = (-dragRatio).coerceAtLeast(0f)
            val alphaDefault = (1f - kotlin.math.abs(dragRatio)).coerceAtLeast(0f)

            if (alphaDefault > 0.01f) {
                Text(
                    text = "Swipe to Connect",
                    color = Color.Gray.copy(alpha = alphaDefault),
                    fontSize = 12.sp,
                    letterSpacing = 1.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
            }
            if (alphaNearby > 0.01f) {
                Text(
                    text = "Instant face-to-face beam. Zero network needed.\n(Ask receiver to tap 'Receive')",
                    color = themeColor.copy(alpha = alphaNearby),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    lineHeight = 16.sp
                )
            }
            if (alphaRemote > 0.01f) {
                Text(
                    text = "Send anywhere, anytime without internet.\n(Requires a basic mobile signal)",
                    color = Color.White.copy(alpha = alphaRemote),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    lineHeight = 16.sp
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .width(trackWidth)
                .height(84.dp)
                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(42.dp))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(42.dp)),
            contentAlignment = Alignment.Center
        ) {
            // --- BACKGROUND LABELS ---
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Action: Remote
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(if (offsetX.value < 0) 1f else 0.3f)) {
                    Icon(Icons.Default.CellTower, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(24.dp)) // Changed icon to CellTower to match "mobile signal"
                    Spacer(Modifier.height(4.dp))
                    Text("Anywhere", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                // Right Action: Nearby
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(if (offsetX.value > 0) 1f else 0.3f)) {
                    Icon(Icons.Default.WifiTethering, null, tint = themeColor, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(4.dp))
                    Text("Nearby", color = themeColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            // --- DRAGGABLE THUMB ---
            Box(
                modifier = Modifier
                    .offset { androidx.compose.ui.unit.IntOffset(offsetX.value.toInt(), 0) }
                    .size(thumbSize)
                    .shadow(12.dp, CircleShape, ambientColor = themeColor, spotColor = themeColor)
                    .background(themeColor, CircleShape)
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                coroutineScope.launch {
                                    if (offsetX.value > triggerThresholdPx) {
                                        offsetX.animateTo(maxDragPx)
                                        onNearbyTriggered()
                                    } else if (offsetX.value < -triggerThresholdPx) {
                                        offsetX.animateTo(-maxDragPx)
                                        onRemoteTriggered()
                                    } else {
                                        offsetX.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy))
                                    }
                                }
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                coroutineScope.launch {
                                    val newOffset = (offsetX.value + dragAmount).coerceIn(-maxDragPx, maxDragPx)
                                    offsetX.snapTo(newOffset)
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Default.ChevronLeft, null, tint = Color.Black.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                    Icon(Icons.Default.CurrencyRupee, null, tint = Color.Black, modifier = Modifier.size(24.dp))
                    Icon(Icons.Default.ChevronRight, null, tint = Color.Black.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyPaymentSheet(
    receiverName: String,
    currentBalance: Long,
    amountText: String,
    onAmountChange: (String) -> Unit,
    onCancel: () -> Unit,
    onTransfer: () -> Unit,
    isDarkMode: Boolean,
    themeColor: Color
) {
    // 1. Data Parsing & Sanitization
    val parts = receiverName.split("|")
    val displayName = parts[0].ifBlank { "Unknown Wallet" }
    val displayDetail = if (parts.size > 1) "+91 ${parts[1]}" else "Encrypted Offline Link"

    val sheetBg = if (isDarkMode) Color(0xFF0A0E21) else Color(0xFFF4F7FB)
    val textColor = if (isDarkMode) Color.White else Color(0xFF0A0E21)
    val subTextColor = if (isDarkMode) Color.Gray else Color(0xFF5F6368)

    val parsedAmount = amountText.toLongOrNull() ?: 0L
    val isInsufficient = parsedAmount > currentBalance
    val isTransferReady = parsedAmount > 0 && !isInsufficient

    // Dynamic Physics Animation for the Button
    val buttonScale by animateFloatAsState(
        targetValue = if (isTransferReady) 1.02f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "ButtonScale"
    )

    ModalBottomSheet(
        onDismissRequest = onCancel,
        containerColor = sheetBg,
        dragHandle = { BottomSheetDefaults.DragHandle(color = themeColor.copy(alpha = 0.3f)) },
        tonalElevation = 0.dp
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {

            // Premium Spotlight Background Glow
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(themeColor.copy(alpha = if (isDarkMode) 0.15f else 0.08f), Color.Transparent),
                            radius = 400f
                        )
                    )
            )

            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                // Secure Badge
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF4CAF50).copy(alpha = 0.1f),
                    border = BorderStroke(1.dp, Color(0xFF4CAF50).copy(alpha = 0.2f))
                ) {
                    Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(modifier = Modifier.size(6.dp), shape = CircleShape, color = Color(0xFF4CAF50)) {}
                        Spacer(Modifier.width(8.dp))
                        Text("SECURE BEAM", color = Color(0xFF4CAF50), fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                    }
                }

                Spacer(Modifier.height(28.dp))

                // Receiver Avatar
                Surface(
                    modifier = Modifier.size(80.dp),
                    shape = CircleShape,
                    color = themeColor.copy(alpha = 0.1f),
                    border = BorderStroke(1.dp, themeColor.copy(alpha = 0.3f)),
                    shadowElevation = if (isDarkMode) 0.dp else 8.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(displayName.firstOrNull()?.uppercase() ?: "?", color = themeColor, fontSize = 36.sp, fontWeight = FontWeight.Black)
                    }
                }

                Spacer(Modifier.height(16.dp))

                Text("PAYING", color = subTextColor, style = MaterialTheme.typography.labelSmall, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(displayName, color = textColor, fontSize = 26.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
                Text(displayDetail, color = subTextColor, fontSize = 13.sp)

                Spacer(Modifier.height(30.dp))

                // ✨ BULLETPROOF CENTERED INPUT (NO CLIPPING) ✨
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        // The Symbol (₹)
                        Text(
                            text = "₹",
                            color = if (amountText.isEmpty()) subTextColor.copy(alpha = 0.3f) else themeColor,
                            style = TextStyle(
                                fontSize = 44.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            modifier = Modifier.padding(end = 12.dp)
                        )

                        // The Field
                        androidx.compose.foundation.text.BasicTextField(
                            value = amountText,
                            onValueChange = {
                                if (it.length <= 6 && it.all { char -> char.isDigit() }) onAmountChange(it)
                            },
                            textStyle = TextStyle(
                                color = textColor,
                                fontSize = 72.sp,
                                fontWeight = FontWeight.Black,
                                textAlign = TextAlign.Start // Text flows from the symbol
                            ),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            cursorBrush = SolidColor(themeColor),
                            decorationBox = { innerTextField ->
                                Box(
                                    modifier = Modifier.width(IntrinsicSize.Min),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    if (amountText.isEmpty()) {
                                        Text(
                                            text = "0",
                                            color = subTextColor.copy(alpha = 0.2f),
                                            style = TextStyle(
                                                fontSize = 72.sp,
                                                fontWeight = FontWeight.Black
                                            )
                                        )
                                    }
                                    // Internal padding prevents the glyphs from being clipped by the container edge
                                    Box(modifier = Modifier.padding(horizontal = 4.dp)) {
                                        innerTextField()
                                    }
                                }
                            },
                            modifier = Modifier.widthIn(min = 40.dp, max = 280.dp)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Balance Indicator
                Surface(
                    color = if (isInsufficient) Color(0xFFF44336).copy(alpha = 0.1f) else themeColor.copy(alpha = 0.05f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (isInsufficient) "Insufficient Funds (₹$currentBalance limit)" else "Vault Balance: ₹$currentBalance",
                        color = if (isInsufficient) Color(0xFFF44336) else subTextColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                Spacer(Modifier.height(48.dp))

                // Physical Action Button
                Button(
                    onClick = onTransfer,
                    enabled = isTransferReady,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .scale(buttonScale),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = themeColor,
                        disabledContainerColor = if (isDarkMode) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.05f)
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = if (isTransferReady) 12.dp else 0.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Send,
                            null,
                            tint = if (isTransferReady) Color(0xFF0A0E21) else subTextColor.copy(alpha = 0.5f),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = if (parsedAmount > 0) "BEAM ₹$parsedAmount" else "ENTER AMOUNT",
                            color = if (isTransferReady) Color(0xFF0A0E21) else subTextColor.copy(alpha = 0.5f),
                            fontWeight = FontWeight.Black,
                            fontSize = 18.sp,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
        }
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailsSheet(tx: CreditEntry, onDismiss: () -> Unit, isDarkMode: Boolean, themeColor: Color, currentWalletName: String) {
    val sheetBg = if (isDarkMode) Color(0xFF0A0E21).copy(alpha = 0.98f) else Color(0xFFFFF7EB)
    val textColor = if (isDarkMode) Color.White else Color(0xFF0A0E21)
    val subTextColor = if (isDarkMode) Color.Gray else Color(0xFF5F6368)
    val cardBg = if (isDarkMode) Color.White.copy(alpha = 0.05f) else Color.White
    val borderColor = if (isDarkMode) themeColor.copy(alpha = 0.2f) else themeColor.copy(alpha = 0.5f)

    val isIncoming = tx.amount > 0
    val context = LocalContext.current
    var showDisputeSheet by remember { mutableStateOf(false) }

    // Check if this is an expired or refunded transaction
    val isExpired = tx.senderId.contains("Expired", ignoreCase = true) ||
            tx.senderId.contains("SYSTEM_REFUND", ignoreCase = true)

    // ✨ NEW: Check if this specific transaction has already been manually refunded
    val isAlreadyRefunded = tx.note == "🚨 REFUNDED" || tx.note == "🛑 BLOCKED SENDER"

    // ✨ NEW: Give Reverts a golden color instead of standard green/red, and Grey for voided
    val statusColor = when {
        isExpired -> Color(0xFFF59E0B) // Golden Amber for Reverts
        isAlreadyRefunded -> Color.Gray // ✨ Turns grey if voided
        isIncoming -> Color(0xFF4CAF50)
        else -> Color(0xFFF44336)
    }

    // ✨ NEW: Swap the top icon to a "Restore" arrow for Reverts, and "Block" for voided
    val topIcon = when {
        isExpired -> Icons.Default.SettingsBackupRestore
        isAlreadyRefunded -> Icons.Default.Block // ✨ Void icon
        isIncoming -> Icons.Default.CallReceived
        else -> Icons.Default.CallMade
    }

    val finalStatusText = when {
        isExpired -> "Expired & Reverted"
        isAlreadyRefunded -> "Voided & Refunded" // ✨ Updates Status text
        else -> "Successful"
    }
    val finalStatusColor = when {
        isExpired -> Color(0xFFF59E0B)
        isAlreadyRefunded -> Color.Gray
        else -> Color(0xFF4CAF50)
    }

    val rawId = tx.senderId.replace("Remote Sent to ", "").replace("SMS: ", "")
    val parts = rawId.split("|")

    val displayName = when {
        rawId == "BANK_SIM" -> "Linked Bank Account"
        rawId.contains("SYSTEM_REFUND", ignoreCase = true) -> "System Revert" // Fixes the robotic name!
        else -> parts.firstOrNull { it != "Unknown" && it != "Pending" } ?: "Unknown"
    }

    val displayPhone = if (parts.size > 1) parts[1] else ""
    val displayAcc = if (parts.size > 2) parts[2] else ""

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = sheetBg, dragHandle = { BottomSheetDefaults.DragHandle(color = themeColor.copy(alpha = 0.5f)) }) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 40.dp, start = 24.dp, end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {

            Surface(modifier = Modifier.size(72.dp), shape = CircleShape, color = statusColor.copy(alpha = 0.15f)) {
                Icon(imageVector = topIcon, contentDescription = null, tint = statusColor, modifier = Modifier.padding(18.dp))
            }
            Spacer(Modifier.height(16.dp))

            // ✨ UPDATED: Strikes through the amount if voided
            Text(
                text = "${if (isIncoming) "+" else "-"} ₹${kotlin.math.abs(tx.amount)}",
                color = statusColor,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Black,
                textDecoration = if(isAlreadyRefunded) androidx.compose.ui.text.style.TextDecoration.LineThrough else null
            )

            // SECURE LEDGER INDICATION
            Text(text = "Locked in $currentWalletName Ledger", color = subTextColor, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))

            // Display the Note elegantly if it exists
            if (!tx.note.isNullOrBlank()) {
                Spacer(Modifier.height(24.dp))
                Surface(
                    color = themeColor.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, themeColor.copy(alpha = 0.2f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("NOTE", color = themeColor, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(text = "\"${tx.note}\"", color = textColor, style = MaterialTheme.typography.bodyMedium, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, textAlign = TextAlign.Center)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Main Receipt Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = cardBg),
                border = BorderStroke(1.dp, borderColor)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    ReceiptRow("Date", formatTransactionDate(tx.timestamp), textColor = textColor)
                    ReceiptRow(if (isIncoming) "From" else "To", displayName, isValueBold = true, textColor = textColor)

                    if (displayPhone.isNotBlank()) ReceiptRow("Phone", displayPhone, textColor = textColor)
                    if (displayAcc.isNotBlank()) ReceiptRow("Account No", displayAcc, textColor = textColor)

                    ReceiptRow("Virtual Acc", tx.targetVirtualAccount.takeLast(11), textColor = subTextColor)

                    ReceiptRow("Status", finalStatusText, color = finalStatusColor, textColor = textColor)
                    ReceiptRow("Cloud Sync", if (tx.isSynced) "Backed Up" else "Offline Only", color = if (tx.isSynced && !isAlreadyRefunded) Color(0xFF4CAF50) else themeColor, textColor = textColor)

                    // THE REVERTED EXPLANATION BANNER
                    if (isExpired) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFFF59E0B).copy(alpha = 0.1f),
                            border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.3f))
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = "Reverted Info",
                                    tint = Color(0xFFF59E0B),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "The recipient did not claim this payment in time. The funds have been safely reverted to your wallet.",
                                    color = Color(0xFFF59E0B),
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = borderColor)
                    Spacer(Modifier.height(16.dp))

                    Text("TRANSACTION HASH", color = subTextColor, style = MaterialTheme.typography.labelSmall)
                    Text(text = tx.transactionHash, color = subTextColor.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Spacer(Modifier.height(32.dp))

            // ✨ HIDE BUTTON IF ALREADY REFUNDED!
            if (isIncoming && !isExpired && !isAlreadyRefunded) {
                OutlinedButton(
                    onClick = { showDisputeSheet = true },
                    modifier = Modifier.fillMaxWidth().height(56.dp).padding(bottom = 12.dp),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color(0xFFF44336).copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFFF44336).copy(alpha = 0.05f))
                ) {
                    Icon(Icons.Default.Warning, null, tint = Color(0xFFF44336), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("REPORT SUSPICIOUS", color = Color(0xFFF44336), fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            }

            // Close Button
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = themeColor)
            ) {
                Text("CLOSE", color = Color(0xFF0A0E21), fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
            }
        }
    }
    if (showDisputeSheet) {
        DisputeMenuSheet(
            txHash = tx.transactionHash,
            senderId = tx.senderId,
            onDismiss = {
                showDisputeSheet = false
                onDismiss() // Close both sheets
            },
            isDarkMode = isDarkMode,
            themeColor = themeColor
        )
    }
}
// --- GPAY STYLE PEOPLE & CHAT COMPONENTS ---

@Composable
fun RecentPeopleSection(history: List<CreditEntry>, onPersonClick: (String) -> Unit, isDarkMode: Boolean, themeColor: Color) {
    val textColor = if (isDarkMode) Color.White else Color(0xFF0A0E21)

    val recentPeople = remember(history) {
        // 1. Find all unique Phone Numbers that we have texted with
        val remotePhoneNumbers = history
            .filter { it.previousHash == "REMOTE_SMS" }
            .mapNotNull { tx ->
                val parts = tx.senderId.split("|")
                // ✨ THE FIX: We extract just the 10-digit phone number, ignoring the Virtual Account!
                if (parts.size >= 2) parts[1].replace(Regex("[^0-9]"), "").takeLast(10) else null
            }
            .toSet()

        // 2. Group all transactions strictly by Phone Number
        history.filter { tx ->
            val parts = tx.senderId.split("|")
            val phone = if (parts.size >= 2) parts[1].replace(Regex("[^0-9]"), "").takeLast(10) else ""
            remotePhoneNumbers.contains(phone) && tx.senderId != "BANK_SIM"
        }
            .groupBy { tx ->
                val parts = tx.senderId.split("|")
                if (parts.size >= 2) parts[1].replace(Regex("[^0-9]"), "").takeLast(10) else tx.senderId
            }
            .map { (phone, txs) ->
                // Grab the best transaction to represent this person (prefer one with a real name over "Pending")
                val bestTx = txs.firstOrNull { !it.senderId.startsWith("Pending") } ?: txs.first()
                bestTx.senderId to txs.maxOf { it.timestamp }
            }
            .sortedByDescending { it.second }
            .take(8)
            .map { it.first }
    }

    if (recentPeople.isNotEmpty()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text("People", color = textColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                recentPeople.chunked(4).forEach { rowItems ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        rowItems.forEach { smartId ->
                            val displayParts = smartId.split("|")
                            val displayName = if (displayParts.isNotEmpty() && displayParts[0] != "Unknown") displayParts[0] else displayParts.last()

                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                PersonAvatarItem(displayName, isDarkMode) { onPersonClick(smartId) }
                            }
                        }
                        repeat(4 - rowItems.size) { Box(modifier = Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
fun ColorPickerRow(selectedColor: Color, onColorSelected: (Color) -> Unit) {
    val colors = listOf(
        Color(0xFFF9AA33), // Gold
        Color(0xFF50C878), // Emerald
        Color(0xFF3B82F6), // Blue
        Color(0xFF8B5CF6), // Purple
        Color(0xFFEC4899), // Pink
        Color(0xFFEF4444)  // Red
    )

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        colors.forEach { color ->
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(color, CircleShape)
                    .border(
                        width = if (selectedColor == color) 3.dp else 0.dp,
                        color = Color.White,
                        shape = CircleShape
                    )
                    .clickable { onColorSelected(color) }
            )
        }
    }
}

@Composable
fun PersonAvatarItem(name: String, isDarkMode: Boolean, onClick: () -> Unit) {
    val initial = name.firstOrNull()?.uppercase() ?: "?"
    val colors = listOf(Color(0xFFE91E63), Color(0xFF9C27B0), Color(0xFF673AB7), Color(0xFF3F51B5), Color(0xFF009688), Color(0xFF4CAF50), Color(0xFFFF9800), Color(0xFF795548))
    val avatarColor = remember(name) { colors[kotlin.math.abs(name.hashCode()) % colors.size] }
    val textColor = if (isDarkMode) Color.White else Color(0xFF0A0E21)

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() }) {
        Surface(modifier = Modifier.size(56.dp), shape = CircleShape, color = avatarColor) {
            Box(contentAlignment = Alignment.Center) { Text(initial, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(8.dp))
        Text(text = name, color = textColor, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.width(64.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserChatScreen(targetName: String, history: List<CreditEntry>, onBack: () -> Unit, onPayClick: () -> Unit, isDarkMode: Boolean, themeColor: Color) {
    val displayParts = targetName.split("|")
    val displayName = if (displayParts.isNotEmpty() && displayParts[0] != "Unknown") displayParts[0] else displayParts.last()
    val displayPhone = if (displayParts.size > 1) displayParts[1] else "Unknown Number"

    val topBarBg = if (isDarkMode) Color(0xFF0A0E21).copy(alpha = 0.95f) else Color(0xFFF4F7FB).copy(alpha = 0.95f)
    val textColor = if (isDarkMode) Color.White else Color(0xFF0A0E21)
    val subTextColor = if (isDarkMode) Color.Gray else Color(0xFF5F6368)

    var showDetailsDialog by remember { mutableStateOf(false) }

    // ✨ FIX: Match strictly on Phone Number so all chats with this person merge!
    val userHistory = remember(history, targetName) {
        history.filter { tx ->
            if (tx.senderId == "BANK_SIM" || tx.senderId.startsWith("SYSTEM_REFUND")) return@filter false

            val targetParts = targetName.split("|")
            val txParts = tx.senderId.split("|")

            val targetPhone = if (targetParts.size >= 2) targetParts[1].replace(Regex("[^0-9]"), "").takeLast(10) else ""
            val txPhone = if (txParts.size >= 2) txParts[1].replace(Regex("[^0-9]"), "").takeLast(10) else ""

            // Match primarily on Phone Number. If phone is missing, fall back to strict ID match.
            if (targetPhone.isNotBlank() && txPhone.isNotBlank()) {
                targetPhone == txPhone
            } else {
                tx.senderId == targetName
            }
        }.sortedBy { it.timestamp }
    }

    if (showDetailsDialog) {
        PersonDetailsDialog(
            name = displayName,
            phone = displayPhone,
            virtualAccountId = if (displayParts.size > 2) displayParts[2] else "N/A",
            isDarkMode = isDarkMode,
            themeColor = themeColor,
            onDismiss = { showDetailsDialog = false }
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            Surface(
                color = topBarBg,
                shadowElevation = if (isDarkMode) 0.dp else 4.dp,
                border = BorderStroke(1.dp, if(isDarkMode) Color.White.copy(alpha = 0.05f) else Color.Transparent)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().height(72.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = textColor) }

                    // Avatar
                    Surface(
                        modifier = Modifier.size(40.dp),
                        shape = CircleShape,
                        color = themeColor.copy(alpha = 0.15f),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(displayName.firstOrNull()?.uppercase() ?: "?", color = themeColor, fontWeight = FontWeight.Black, fontSize = 18.sp)
                        }
                    }
                    Spacer(Modifier.width(12.dp))

                    // Name & Phone
                    Column(modifier = Modifier.weight(1f)) {
                        Text(displayName, color = textColor, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(displayPhone, color = subTextColor, style = MaterialTheme.typography.labelMedium)
                    }

                    // Top Right Icon for Details
                    IconButton(onClick = { showDetailsDialog = true }) {
                        Icon(Icons.Default.MoreVert, "Details", tint = textColor)
                    }
                }
            }
        }
    ) { padding ->
        PremiumBackground(isDarkMode) {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(top = 24.dp, bottom = 100.dp), // Space for bottom button
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(userHistory) { tx ->
                        GPayTransactionBubble(tx = tx, targetName = displayName, isDarkMode = isDarkMode, themeColor = themeColor)
                    }
                }

                // Sleek, Floating Bottom Pay Button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, if(isDarkMode) Color(0xFF0A0E21) else Color(0xFFF4F7FB))
                            )
                        )
                        .padding(24.dp)
                        .navigationBarsPadding()
                ) {
                    Button(
                        onClick = onPayClick,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = themeColor),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
                    ) {
                        Text("Pay", color = Color(0xFF0A0E21), fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, letterSpacing = 1.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun GPayTransactionBubble(tx: CreditEntry, targetName: String, isDarkMode: Boolean, themeColor: Color) {
    // Determine State
    val isSystemRefund = tx.senderId.contains("SYSTEM_REFUND") || tx.previousHash == "TIMEOUT"
    val isMerchantContraEntry = tx.previousHash == "CONTRA_ENTRY"
    val isSenderReceivedRefund = tx.note == "Refunded by Receiver"
    val isExplicitRefund = isMerchantContraEntry || isSenderReceivedRefund

    val isOriginalRefunded = tx.note == "🚨 REFUNDED" || tx.note == "🛑 BLOCKED SENDER"
    val isExpiredOutbound = tx.senderId.startsWith("Expired")
    val isStruckOut = isExpiredOutbound || isOriginalRefunded

    // Alignment: Negative amount goes to the right (Sent), Positive goes to the left (Received)
    val isSent = tx.amount < 0
    val amount = kotlin.math.abs(tx.amount)

    // Dynamic Colors based on State
    val bubbleBg = when {
        isSystemRefund -> Color(0xFF2D1E0B) // Dark Gold
        isExplicitRefund -> Color(0xFF2E1515) // Dark Red
        isDarkMode -> Color(0xFF1E222D)
        else -> Color.White
    }

    val textColor = if (isStruckOut) Color.Gray else if (isDarkMode) Color.White else Color(0xFF0A0E21)
    val subTextColor = if (isDarkMode) Color.Gray else Color(0xFF5F6368)
    val borderColor = if (isDarkMode) Color.White.copy(alpha = 0.05f) else Color(0xFFE0E0E0)

    val dateStr = remember(tx.timestamp) {
        val sdf = java.text.SimpleDateFormat("d MMM, h:mm a", java.util.Locale.getDefault())
        sdf.format(java.util.Date(tx.timestamp))
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isSent) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 24.dp,
                topEnd = 24.dp,
                bottomStart = if (isSent) 24.dp else 4.dp,
                bottomEnd = if (isSent) 4.dp else 24.dp
            ),
            color = bubbleBg,
            border = BorderStroke(1.dp, borderColor),
            shadowElevation = if (isDarkMode || isStruckOut) 0.dp else 4.dp,
            modifier = Modifier.width(260.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {

                // Display the Note
                if (!tx.note.isNullOrBlank()) {
                    Text(
                        text = tx.note,
                        style = MaterialTheme.typography.bodyLarge,
                        color = textColor.copy(alpha = 0.9f),
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }

                // The Amount
                Text(
                    text = "₹$amount",
                    color = textColor,
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-1).sp,
                    textDecoration = if(isStruckOut) androidx.compose.ui.text.style.TextDecoration.LineThrough else null
                )

                Spacer(Modifier.height(16.dp))

                // Status Row
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val iconTint = when {
                        isStruckOut -> Color.Gray
                        isSystemRefund -> Color(0xFFFF9800)
                        isExplicitRefund -> Color(0xFFF44336)
                        else -> Color(0xFF4CAF50)
                    }

                    val icon = if (isSystemRefund || isExplicitRefund) Icons.Default.SettingsBackupRestore else Icons.Default.CheckCircle

                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))

                    val statusLabel = when {
                        isStruckOut -> "Voided"
                        isSystemRefund -> "System Revert"
                        isMerchantContraEntry -> "Refund Sent"
                        isSenderReceivedRefund -> "Refund Received"
                        isSent -> "Paid"
                        else -> "Received"
                    }

                    Text(
                        text = "$statusLabel • $dateStr",
                        color = subTextColor,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        textDecoration = if(isStruckOut) androidx.compose.ui.text.style.TextDecoration.LineThrough else null
                    )
                }
            }
        }
    }
}

@Composable
fun PersonDetailsDialog(name: String, phone: String, virtualAccountId: String, isDarkMode: Boolean, themeColor: Color, onDismiss: () -> Unit) {
    val dialogBg = if (isDarkMode) Color(0xFF13182C) else Color.White
    val textColor = if (isDarkMode) Color.White else Color(0xFF0A0E21)
    val subTextColor = if (isDarkMode) Color.Gray else Color(0xFF5F6368)

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = dialogBg),
            elevation = CardDefaults.cardElevation(if(isDarkMode) 0.dp else 12.dp),
            border = BorderStroke(1.dp, if(isDarkMode) Color.White.copy(alpha=0.1f) else Color.Transparent)
        ) {
            Column(modifier = Modifier.padding(32.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    modifier = Modifier.size(80.dp),
                    shape = CircleShape,
                    color = themeColor.copy(alpha = 0.15f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(name.firstOrNull()?.uppercase() ?: "?", color = themeColor, fontWeight = FontWeight.Black, fontSize = 32.sp)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(name, style = MaterialTheme.typography.headlineSmall, color = textColor, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text(phone, color = subTextColor, style = MaterialTheme.typography.bodyMedium)

                Spacer(Modifier.height(24.dp))

                Surface(color = themeColor.copy(alpha = 0.1f), shape = RoundedCornerShape(12.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                        Text("Virtual Account ID", color = subTextColor, fontSize = 10.sp)
                        Text(virtualAccountId, color = themeColor, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                }

                Spacer(Modifier.height(32.dp))
                TextButton(onClick = onDismiss) {
                    Text("CLOSE", color = subTextColor, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun LockedVaultOverlay(isDeviceSecure: Boolean, onBypass: () -> Unit, onTriggerPrompt: () -> Unit, isDarkMode: Boolean, themeColor: Color) {
    val textColor = if (isDarkMode) Color.White else Color(0xFF0A0E21)
    val subTextColor = if (isDarkMode) Color.Gray else Color(0xFF5F6368)

    LaunchedEffect(Unit) {
        delay(300)
        onTriggerPrompt()
    }

    PremiumBackground(isDarkMode) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    shape = CircleShape,
                    color = themeColor.copy(alpha = 0.1f),
                    border = BorderStroke(1.dp, themeColor.copy(alpha = 0.4f)),
                    modifier = Modifier.size(100.dp)
                ) {
                    Icon(Icons.Default.Lock, null, tint = themeColor, modifier = Modifier.padding(24.dp))
                }

                Spacer(Modifier.height(32.dp))
                Text("Vault is Locked", color = textColor, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(8.dp))
                Text("Please authenticate to continue", color = subTextColor, style = MaterialTheme.typography.bodyLarge)

                Spacer(Modifier.height(40.dp))
                OutlinedButton(onClick = onTriggerPrompt, border = BorderStroke(1.dp, themeColor)) {
                    Text("USE BIOMETRICS", color = themeColor, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
fun QRScannerScreen(onCodeDetected: (String) -> Unit, onCancel: () -> Unit, themeColor: Color = Color(0xFFF9AA33)) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraController = remember { LifecycleCameraController(context) }

    // ✨ 1. GALLERY PICKER LAUNCHER
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        uri?.let {
            // Process the selected image for QR code
            decodeQrFromUri(context, it) { result ->
                if (result != null) onCodeDetected(result)
                else Toast.makeText(context, "No QR Code found in image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "Laser")
    val laserY by infiniteTransition.animateFloat(initialValue = 0.2f, targetValue = 0.8f, animationSpec = infiniteRepeatable(animation = tween(2000, easing = LinearEasing), repeatMode = RepeatMode.Reverse), label = "LaserPosition")

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    controller = cameraController
                    cameraController.bindToLifecycle(lifecycleOwner)
                    cameraController.setImageAnalysisAnalyzer(ContextCompat.getMainExecutor(ctx)) { proxy ->
                        proxy.image?.let { img ->
                            val input = InputImage.fromMediaImage(img, proxy.imageInfo.rotationDegrees)
                            BarcodeScanning.getClient().process(input)
                                .addOnSuccessListener { codes -> codes.firstOrNull()?.rawValue?.let { onCodeDetected(it) } }
                                .addOnCompleteListener { proxy.close() }
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay & Laser drawing logic... (keep your existing Canvas logic here)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width; val height = size.height; val boxSize = width * 0.7f; val left = (width - boxSize) / 2; val top = (height - boxSize) / 2; val right = left + boxSize; val bottom = top + boxSize
            drawRect(color = Color.Black.copy(alpha = 0.6f), size = size)
            drawRoundRect(color = Color.Transparent, topLeft = androidx.compose.ui.geometry.Offset(left, top), size = androidx.compose.ui.geometry.Size(boxSize, boxSize), blendMode = androidx.compose.ui.graphics.BlendMode.Clear)
            val lineLen = 40.dp.toPx(); val strokeWidth = 4.dp.toPx()
            drawLine(themeColor, Offset(left, top), Offset(left + lineLen, top), strokeWidth); drawLine(themeColor, Offset(left, top), Offset(left, top + lineLen), strokeWidth); drawLine(themeColor, Offset(right, top), Offset(right - lineLen, top), strokeWidth); drawLine(themeColor, Offset(right, top), Offset(right, top + lineLen), strokeWidth); drawLine(themeColor, Offset(left, bottom), Offset(left + lineLen, bottom), strokeWidth); drawLine(themeColor, Offset(left, bottom), Offset(left, bottom - lineLen), strokeWidth); drawLine(themeColor, Offset(right, bottom), Offset(right - lineLen, bottom), strokeWidth); drawLine(themeColor, Offset(right, bottom), Offset(right, bottom - lineLen), strokeWidth)
            val currentLaserY = top + (boxSize * laserY)
            drawLine(color = themeColor, start = Offset(left + 10.dp.toPx(), currentLaserY), end = Offset(right - 10.dp.toPx(), currentLaserY), strokeWidth = 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, alpha = 0.8f)
        }

        // ✨ 2. TOP UI BAR WITH GALLERY OPTION
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, null, tint = Color.White)
            }

            Text("Scan QR", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)

            // ✨ GALLERY BUTTON
            IconButton(
                onClick = { galleryLauncher.launch("image/*") },
                modifier = Modifier.background(Color.White.copy(alpha = 0.2f), CircleShape)
            ) {
                Icon(Icons.Default.Image, contentDescription = "Gallery", tint = Color.White)
            }
        }

        // Bottom Cancel Button
        Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
            Button(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(bottom = 8.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.2f))
            ) {
                Text("CANCEL SCAN", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

fun decodeQrFromUri(context: android.content.Context, uri: android.net.Uri, onResult: (String?) -> Unit) {
    try {
        val image = InputImage.fromFilePath(context, uri)
        val scanner = BarcodeScanning.getClient()

        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                // Take the first QR code found in the photo
                val rawValue = barcodes.firstOrNull()?.rawValue
                onResult(rawValue)
            }
            .addOnFailureListener {
                onResult(null)
            }
    } catch (e: Exception) {
        onResult(null)
    }
}

@Composable
fun GooglePhoneSetupScreen(suggestedNumbers: List<String>, onComplete: (String) -> Unit, isDarkMode: Boolean, themeColor: Color) {
    val navyDark = Color(0xFF0A0E21)
    val navyLight = Color(0xFF1C2754)
    val bgTop = if (isDarkMode) navyLight else Color(0xFFE8F0FE)
    val bgBottom = if (isDarkMode) navyDark else Color(0xFFFFFFFF)
    val textColor = if (isDarkMode) Color.White else Color(0xFF0A0E21)
    val subTextColor = if (isDarkMode) Color.White.copy(alpha = 0.7f) else Color.Gray

    var phoneInput by remember { mutableStateOf("") }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) { alpha.animateTo(1f, tween(500)) }

    Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(bgTop, bgBottom))).graphicsLayer(alpha = alpha.value)) {
        Column(modifier = Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Surface(modifier = Modifier.size(100.dp), shape = CircleShape, color = themeColor.copy(alpha = 0.1f), border = BorderStroke(1.dp, themeColor.copy(alpha = 0.5f))) {
                Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = themeColor, modifier = Modifier.padding(24.dp))
            }
            Spacer(Modifier.height(32.dp))
            Text(text = "Almost Done!", color = themeColor, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(12.dp))
            Text(text = "Last step. Please confirm your primary mobile number to link with your offline vault.", color = subTextColor, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(48.dp))
            AuthTextField(value = phoneInput, onValueChange = { if (it.length <= 10 && it.all { char -> char.isDigit() }) phoneInput = it }, label = "Phone Number (+91)", icon = Icons.Default.Phone, isDarkMode = isDarkMode, themeColor = themeColor)

            if (suggestedNumbers.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) { Text("Suggestions from this device:", color = subTextColor, style = MaterialTheme.typography.labelSmall) }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    suggestedNumbers.take(2).forEachIndexed { index, number ->
                        Surface(modifier = Modifier.weight(1f).height(48.dp).clickable { phoneInput = number }, shape = RoundedCornerShape(12.dp), color = if (phoneInput == number) themeColor.copy(alpha = 0.2f) else Color.Transparent, border = BorderStroke(1.dp, if (phoneInput == number) themeColor else themeColor.copy(alpha = 0.3f))) {
                            Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.SimCard, null, tint = themeColor, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("SIM ${index + 1}", color = textColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(48.dp))
            Button(onClick = { if (phoneInput.length >= 10) onComplete(phoneInput) }, enabled = phoneInput.length >= 10, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = themeColor, disabledContainerColor = if(isDarkMode) Color.White.copy(alpha = 0.05f) else Color(0xFFE0E0E0))) {
                Text("COMPLETE SETUP", color = if(phoneInput.length >= 10) navyDark else Color.Gray, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletSelectorSheet(
    wallets: List<WalletEntity>,
    activeWalletId: String,
    onSelect: (String) -> Unit,
    viewModel: VaultViewModel,
    selectedColor: Color,        // ✨ Added
    onColorChange: (Color) -> Unit, // ✨ Added
    onDismiss: () -> Unit,
    isDarkMode: Boolean,
    themeColor: Color
) {
    val sheetBg = if (isDarkMode) Color(0xFF0A0E21).copy(alpha = 0.98f) else Color(0xFFFFF7EB)
    val textColor = if (isDarkMode) Color.White else Color(0xFF0A0E21)
    var showNewWalletInput by remember { mutableStateOf(false) }
    var newWalletName by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = sheetBg) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp).padding(bottom = 32.dp)) {
            Text("Switch Account", color = textColor, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))

            // List all existing wallets
            wallets.forEach { wallet ->
                val isSelected = wallet.virtualAccountId == activeWalletId
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onSelect(wallet.virtualAccountId); onDismiss() },
                    color = if (isSelected) themeColor.copy(alpha = 0.15f) else Color.Transparent,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text(wallet.walletName, color = if(isSelected) themeColor else textColor, fontWeight = FontWeight.Bold)
                            Text("A/C: ${wallet.virtualAccountId}", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        }
                        if (isSelected) Icon(Icons.Default.CheckCircle, null, tint = themeColor)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = themeColor.copy(alpha = 0.2f))
            Spacer(Modifier.height(16.dp))

            // Create New Wallet Flow
            // Inside WalletSelectorSheet...
            if (showNewWalletInput) {
                // ✨ ADD THIS STATE at the top of the sheet
                var selectedColor by remember { mutableStateOf(Color(0xFF50C878)) }

                AuthTextField(value = newWalletName, onValueChange = { newWalletName = it }, label = "New Account Name", icon = Icons.Default.Add, isDarkMode = isDarkMode, themeColor = themeColor)

                // ✨ ADD THE COLOR PICKER HERE
                Text("Account Theme", color = textColor, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                ColorPickerRow(selectedColor) { selectedColor = it }

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        if (newWalletName.isNotBlank()) {
                            // ✨ Update your ViewModel call to include the color
                            viewModel?.createNewWallet(newWalletName, selectedColor.toArgb())
                            onDismiss()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = selectedColor) // Button matches chosen color!
                ) { Text("CREATE ACCOUNT", color = Color.Black, fontWeight = FontWeight.Bold) }
            } else {
                OutlinedButton(
                    onClick = { showNewWalletInput = true },
                    modifier = Modifier.fillMaxWidth().height(56.dp), border = BorderStroke(1.dp, themeColor), shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.Add, null, tint = themeColor); Spacer(Modifier.width(8.dp))
                    Text("Add New Account", color = themeColor, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnhancedExportDialog(
    wallets: List<WalletEntity>,
    availableMonths: List<String>,
    onDismiss: () -> Unit,
    onExport: (walletId: String?, walletName: String, startTimestamp: Long, endTimestamp: Long, periodLabel: String) -> Unit,
    isDarkMode: Boolean,
    themeColor: Color
) {
    val sheetBg = if (isDarkMode) Color(0xFF1C2754).copy(alpha = 0.98f) else Color.White
    val textColor = if (isDarkMode) Color.White else Color(0xFF0A0E21)
    val subTextColor = if (isDarkMode) Color.Gray else Color(0xFF5F6368)
    val inputBg = if (isDarkMode) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.03f)
    val borderColor = if (isDarkMode) Color.White.copy(alpha = 0.2f) else Color(0xFFE0E0E0)

    // State Variables
    var selectedWallet by remember { mutableStateOf<WalletEntity?>(null) } // null = All Wallets
    var walletDropdownExpanded by remember { mutableStateOf(false) }
    var isCustomRange by remember { mutableStateOf(false) }

    var selectedMonth by remember { mutableStateOf(availableMonths.firstOrNull() ?: "") }
    var monthDropdownExpanded by remember { mutableStateOf(false) }

    // Date Picker States
    var showStartDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }
    var startDateMillis by remember { mutableStateOf<Long?>(null) }
    var endDateMillis by remember { mutableStateOf<Long?>(null) }

    val sdf = remember { java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault()) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = sheetBg) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp).padding(bottom = 32.dp)) {
            Text("Export Statement", color = textColor, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))

            // 1. WALLET SELECTOR
            /*
            Text("Select Wallet", color = subTextColor, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    modifier = Modifier.fillMaxWidth().height(56.dp).clickable { walletDropdownExpanded = true },
                    shape = RoundedCornerShape(12.dp), color = inputBg, border = BorderStroke(1.dp, borderColor)
                ) {
                    Row(modifier = Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(selectedWallet?.walletName ?: "All Wallets", color = textColor, fontWeight = FontWeight.Medium)
                        Icon(Icons.Default.ArrowDropDown, null, tint = subTextColor)
                    }
                }
                DropdownMenu(expanded = walletDropdownExpanded, onDismissRequest = { walletDropdownExpanded = false }, modifier = Modifier.background(sheetBg)) {
                    DropdownMenuItem(text = { Text("All Wallets", color = textColor) }, onClick = { selectedWallet = null; walletDropdownExpanded = false })
                    wallets.forEach { wallet ->
                        DropdownMenuItem(text = { Text(wallet.walletName, color = textColor) }, onClick = { selectedWallet = wallet; walletDropdownExpanded = false })
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            */
            // 2. TIMEFRAME TOGGLE
            Text("Select Timeframe", color = subTextColor, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth().height(48.dp).background(inputBg, RoundedCornerShape(12.dp)).border(1.dp, borderColor, RoundedCornerShape(12.dp))) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight().background(if (!isCustomRange) themeColor.copy(alpha = 0.2f) else Color.Transparent, RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)).clickable { isCustomRange = false }, contentAlignment = Alignment.Center) {
                    Text("By Month", color = if (!isCustomRange) themeColor else subTextColor, fontWeight = FontWeight.Bold)
                }
                Box(modifier = Modifier.weight(1f).fillMaxHeight().background(if (isCustomRange) themeColor.copy(alpha = 0.2f) else Color.Transparent, RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp)).clickable { isCustomRange = true }, contentAlignment = Alignment.Center) {
                    Text("Custom Range", color = if (isCustomRange) themeColor else subTextColor, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(16.dp))

            // 3. TIMEFRAME INPUTS
            if (!isCustomRange) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().height(56.dp).clickable { monthDropdownExpanded = true },
                        shape = RoundedCornerShape(12.dp), color = inputBg, border = BorderStroke(1.dp, borderColor)
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(selectedMonth.ifEmpty { "Select Month" }, color = textColor, fontWeight = FontWeight.Medium)
                            Icon(Icons.Default.CalendarMonth, null, tint = subTextColor)
                        }
                    }
                    DropdownMenu(expanded = monthDropdownExpanded, onDismissRequest = { monthDropdownExpanded = false }, modifier = Modifier.background(sheetBg).heightIn(max = 250.dp)) {
                        availableMonths.forEach { month ->
                            DropdownMenuItem(text = { Text(month, color = textColor) }, onClick = { selectedMonth = month; monthDropdownExpanded = false })
                        }
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(modifier = Modifier.weight(1f).height(56.dp).clickable { showStartDatePicker = true }, shape = RoundedCornerShape(12.dp), color = inputBg, border = BorderStroke(1.dp, borderColor)) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.Center) {
                            Text("Start Date", color = subTextColor, fontSize = 10.sp)
                            Text(startDateMillis?.let { sdf.format(java.util.Date(it)) } ?: "Select", color = textColor, fontWeight = FontWeight.Medium)
                        }
                    }
                    Surface(modifier = Modifier.weight(1f).height(56.dp).clickable { showEndDatePicker = true }, shape = RoundedCornerShape(12.dp), color = inputBg, border = BorderStroke(1.dp, borderColor)) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.Center) {
                            Text("End Date", color = subTextColor, fontSize = 10.sp)
                            Text(endDateMillis?.let { sdf.format(java.util.Date(it)) } ?: "Select", color = textColor, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))

            // 4. DOWNLOAD BUTTON
            val isReady = if (isCustomRange) startDateMillis != null && endDateMillis != null else selectedMonth.isNotEmpty()
            Button(
                onClick = {
                    if (isCustomRange) {
                        val endEndOfTheDay = endDateMillis!! + (24 * 60 * 60 * 1000) - 1 // Include full day
                        val label = "${sdf.format(java.util.Date(startDateMillis!!))} to ${sdf.format(java.util.Date(endDateMillis!!))}"
                        onExport(selectedWallet?.virtualAccountId, selectedWallet?.walletName ?: "All Wallets", startDateMillis!!, endEndOfTheDay, label)
                    } else {
                        // Calculate month timestamps
                        val monthSdf = java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.getDefault())
                        val date = monthSdf.parse(selectedMonth) ?: java.util.Date()
                        val cal = java.util.Calendar.getInstance().apply { time = date }
                        cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
                        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                        cal.set(java.util.Calendar.MINUTE, 0)
                        val start = cal.timeInMillis
                        cal.set(java.util.Calendar.DAY_OF_MONTH, cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH))
                        cal.set(java.util.Calendar.HOUR_OF_DAY, 23)
                        cal.set(java.util.Calendar.MINUTE, 59)
                        val end = cal.timeInMillis
                        onExport(selectedWallet?.virtualAccountId, selectedWallet?.walletName ?: "All Wallets", start, end, selectedMonth)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                enabled = isReady,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = themeColor, disabledContainerColor = inputBg)
            ) {
                Icon(Icons.Default.Download, null, tint = if(isReady) Color.Black else subTextColor)
                Spacer(Modifier.width(8.dp))
                Text("DOWNLOAD .PDF REPORT", color = if(isReady) Color.Black else subTextColor, fontWeight = FontWeight.Bold)
            }
        }
    }

    // NATIVE DATE PICKER DIALOGS
    if (showStartDatePicker) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { showStartDatePicker = false },
            confirmButton = { TextButton(onClick = { startDateMillis = datePickerState.selectedDateMillis; showStartDatePicker = false }) { Text("OK", color = themeColor) } }
        ) { DatePicker(state = datePickerState) }
    }
    if (showEndDatePicker) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = startDateMillis ?: System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { showEndDatePicker = false },
            confirmButton = { TextButton(onClick = { endDateMillis = datePickerState.selectedDateMillis; showEndDatePicker = false }) { Text("OK", color = themeColor) } }
        ) { DatePicker(state = datePickerState) }
    }
}


@SuppressLint("MissingPermission", "HardwareIds")
fun getDevicePhoneNumbers(context: android.content.Context): List<String> {
    val numbers = mutableListOf<String>()
    try {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP_MR1) {
                val subscriptionManager = context.getSystemService(android.content.Context.TELEPHONY_SUBSCRIPTION_SERVICE) as android.telephony.SubscriptionManager
                val activeSubscriptionInfoList = subscriptionManager.activeSubscriptionInfoList
                activeSubscriptionInfoList?.forEach { subInfo ->
                    @Suppress("DEPRECATION")
                    val number = subInfo.number
                    if (!number.isNullOrBlank()) {
                        val cleanNum = number.replace(Regex("[^0-9]"), "").takeLast(10)
                        if (cleanNum.length == 10 && !numbers.contains(cleanNum)) {
                            numbers.add(cleanNum)
                        }
                    }
                }
            }
            val telephonyManager = context.getSystemService(android.content.Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            @Suppress("DEPRECATION")
            val line1Number = telephonyManager.line1Number
            if (!line1Number.isNullOrBlank()) {
                val cleanNum = line1Number.replace(Regex("[^0-9]"), "").takeLast(10)
                if (cleanNum.length == 10 && !numbers.contains(cleanNum)) {
                    numbers.add(cleanNum)
                }
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("PhoneUtils", "Could not fetch SIM numbers", e)
    }
    return numbers
}

fun generateQRCode(content: String): Bitmap? {
    return try { val writer = QRCodeWriter(); val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, 512, 512); val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565); for (x in 0 until 512) for (y in 0 until 512) { bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE) }; bitmap } catch (e: Exception) { null }
}

fun formatTransactionDate(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}


@SuppressLint("Range")
fun getPhoneNumberFromUri(context: android.content.Context, uri: Uri): String? {
    var number: String? = null
    try {
        // Because we strictly use the Phone Picker Intent, the URI contains the number directly!
        context.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val numCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (numCol != -1) {
                    number = cursor.getString(numCol)
                }
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("ContactPicker", "Error fetching phone number.", e)
    }

    return number?.replace(Regex("[^0-9+]"), "")
}

@RequiresApi(Build.VERSION_CODES.Q)
fun exportStatementAsPdf(context: android.content.Context, history: List<CreditEntry>, periodLabel: String, userName: String, walletName: String) {
    if (history.isEmpty()) { Toast.makeText(context, "No transactions to export in this range.", Toast.LENGTH_SHORT).show(); return }
    try {
        val pdfDocument = android.graphics.pdf.PdfDocument()
        val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, 1).create()
        var page = pdfDocument.startPage(pageInfo)
        var canvas = page.canvas

        val paint = android.graphics.Paint()
        val titlePaint = android.graphics.Paint().apply { typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD); textSize = 28f; color = android.graphics.Color.rgb(10, 14, 33) }
        val originalBitmap = android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.ic_launcher_background) // Uses your new logo!
        if (originalBitmap != null) {
            val logoSize = 64; val scaledBitmap = android.graphics.Bitmap.createScaledBitmap(originalBitmap, logoSize, logoSize, true)
            canvas.drawBitmap(scaledBitmap, 595f - 40f - logoSize, 40f, paint)
            scaledBitmap.recycle()
        }

        canvas.drawText("PAYSETU VAULT", 40f, 60f, titlePaint)
        paint.textSize = 14f; paint.color = android.graphics.Color.GRAY; canvas.drawText("Official Account Statement", 40f, 90f, paint)
        paint.color = android.graphics.Color.BLACK
        canvas.drawText("Account Holder: $userName", 40f, 120f, paint)

        // ✨ NEW: Prints the Wallet Name on the PDF
        canvas.drawText("Wallet Ledger: $walletName", 40f, 140f, paint)

        canvas.drawText("Statement Period: $periodLabel", 40f, 160f, paint)
        canvas.drawText("Generated On: ${formatTransactionDate(System.currentTimeMillis())}", 40f, 180f, paint)

        paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        paint.textSize = 12f;
        canvas.drawText("DATE", 40f, 240f, paint); canvas.drawText("TYPE", 160f, 240f, paint); canvas.drawText("DETAILS", 260f, 240f, paint); canvas.drawText("AMOUNT", 480f, 240f, paint)
        paint.strokeWidth = 2f; canvas.drawLine(40f, 250f, 555f, 250f, paint)

        paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.NORMAL)
        var yPosition = 280f

        history.forEach { tx ->
            if (yPosition > 800f) { pdfDocument.finishPage(page); page = pdfDocument.startPage(pageInfo); canvas = page.canvas; yPosition = 60f }
            val cleanDate = formatTransactionDate(tx.timestamp).split(",")[0]
            val type = if (tx.amount > 0) "CREDIT" else "DEBIT"
            val details = tx.senderId.replace("Remote Sent to ", "").replace("SMS: ", "").split("|").firstOrNull()?.take(18) ?: "Unknown"
            val amount = "Rs. ${kotlin.math.abs(tx.amount)}"
            paint.color = if (tx.amount > 0) android.graphics.Color.rgb(76, 175, 80) else android.graphics.Color.RED
            canvas.drawText(cleanDate, 40f, yPosition, paint); canvas.drawText(type, 160f, yPosition, paint)
            paint.color = android.graphics.Color.BLACK; canvas.drawText(details, 260f, yPosition, paint); canvas.drawText(amount, 480f, yPosition, paint)
            yPosition += 30f
        }
        canvas.drawLine(40f, yPosition, 555f, yPosition, paint); pdfDocument.finishPage(page)

        val resolver = context.contentResolver
        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "PaySetu_Statement_${System.currentTimeMillis()}.pdf")
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
            resolver.openOutputStream(uri)?.use { outputStream -> pdfDocument.writeTo(outputStream) }; pdfDocument.close()
            Toast.makeText(context, "PDF Saved to Downloads!", Toast.LENGTH_LONG).show()
            val viewIntent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, "application/pdf"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            context.startActivity(Intent.createChooser(viewIntent, "Open PDF Statement"))
        }
    } catch (e: Exception) { e.printStackTrace(); Toast.makeText(context, "Error generating PDF", Toast.LENGTH_SHORT).show() }
}

fun shareBrandedQrCode(context: android.content.Context, rawQrBitmap: android.graphics.Bitmap, userName: String, themeColorInt: Int) {
    try {
        val width = 1080
        val height = 1920
        val brandedBitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(brandedBitmap)

        // 1. BACKGROUND
        val bgPaint = android.graphics.Paint()
        bgPaint.shader = android.graphics.LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            android.graphics.Color.parseColor("#0A0E21"),
            android.graphics.Color.BLACK,
            android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // 2. THE GLASS CARD CONTAINER
        val cardRect = android.graphics.RectF(80f, 400f, 1000f, 1500f)
        val glassPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        glassPaint.color = android.graphics.Color.WHITE
        glassPaint.alpha = 15
        canvas.drawRoundRect(cardRect, 60f, 60f, glassPaint)

        val borderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            color = themeColorInt
            alpha = 80
            strokeWidth = 3f
        }
        canvas.drawRoundRect(cardRect, 60f, 60f, borderPaint)

        // 3. BRANDING HEADER
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }

        paint.color = themeColorInt
        paint.textSize = 130f
        canvas.drawText("PaySetu", width / 2f, 220f, paint)

        paint.color = android.graphics.Color.WHITE
        paint.textSize = 45f
        paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.NORMAL)
        canvas.drawText("Secure Offline Vault Technology", width / 2f, 300f, paint)

        // 4. ✨ DYNAMIC AUTO-SCALING NAME ✨
        val rawName = try {
            if (userName.startsWith("paysetu://")) {
                android.net.Uri.parse(userName).getQueryParameter("name") ?: "Receiver"
            } else {
                userName.split("|").first()
            }
        } catch (e: Exception) { "User" }

        val fullText = "Pay $rawName"
        var dynamicFontSize = 85f // Start with a premium large size
        val maxTextWidth = cardRect.width() - 160f // Leave 80px margin on each side

        paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        paint.textSize = dynamicFontSize

        // 📏 Shrink font until it fits within the card width
        while (paint.measureText(fullText) > maxTextWidth && dynamicFontSize > 40f) {
            dynamicFontSize -= 2f
            paint.textSize = dynamicFontSize
        }

        // Center the name horizontally
        paint.textAlign = android.graphics.Paint.Align.CENTER
        paint.color = android.graphics.Color.WHITE
        canvas.drawText(fullText, width / 2f, 550f, paint)

        // Subtext (always centered below the name)
        paint.textSize = 35f
        paint.color = android.graphics.Color.LTGRAY
        paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.NORMAL)
        canvas.drawText("Scan QR to pay securely", width / 2f, 615f, paint)

        // 5. THE QR CODE (Isolated and Centered)
        val qrSize = 650
        val qrLeft = (width - qrSize) / 2f
        val qrTop = 720f
        val qrRect = android.graphics.RectF(qrLeft, qrTop, qrLeft + qrSize, qrTop + qrSize)

        paint.color = android.graphics.Color.WHITE
        paint.alpha = 255
        canvas.drawRoundRect(qrRect, 40f, 40f, paint)

        val scaledQr = android.graphics.Bitmap.createScaledBitmap(rawQrBitmap, qrSize - 80, qrSize - 80, false)
        canvas.drawBitmap(scaledQr, qrLeft + 40f, qrTop + 40f, null)

        // 6. BOTTOM MARKETING
        paint.textAlign = android.graphics.Paint.Align.CENTER
        paint.color = android.graphics.Color.WHITE
        paint.textSize = 55f
        paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        canvas.drawText("Secure. Instant. No Internet Required.", width / 2f, 1650f, paint)

        paint.color = android.graphics.Color.GRAY
        paint.textSize = 38f
        paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.NORMAL)
        canvas.drawText("Transaction requires the PaySetu App", width / 2f, 1720f, paint)

        // 7. SAVE & SHARE
        val file = java.io.File(context.cacheDir, "images").apply { mkdirs() }
        val imageFile = java.io.File(file, "PaySetu_Share.png")
        java.io.FileOutputStream(imageFile).use { brandedBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }

        val imageUri = androidx.core.content.FileProvider.getUriForFile(context, "com.paysetu.offlinevault.fileprovider", imageFile)
        val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(android.content.Intent.EXTRA_STREAM, imageUri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(shareIntent, "Share Secure Link"))
    } catch (e: Exception) {
        android.util.Log.e("ShareQR", "Failed: ${e.message}")
    }
}
object OfflinePaymentHelper {

    fun sendRemoteOfflinePayment(context: android.content.Context, targetPhoneB: String, amount: Long, senderName: String, senderPhoneA: String, senderVA: String) {
        val CENTRAL_HUB_NUMBER = "+919920833792"
        val hash = "SMS_${System.currentTimeMillis()}"

        // 1. Generate real hardware signature
        val signature = CryptoEngine.generateSignature(amount, senderPhoneA, senderVA, hash)
        val payload = "##PAYSETU_HUB##:$targetPhoneB:$amount:$senderName:$senderPhoneA:$senderVA:$hash:$signature::"

        // 2. Add to Smart Queue (Use .commit() so it saves instantly, not asynchronously!)
        val prefs = context.getSharedPreferences("PaysetuSmsQueue", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString(hash, payload).commit()
        android.util.Log.d("CryptoDemo", "📡 REMOTE: Queued encrypted SMS transfer to $targetPhoneB")

        // 3. Deduct from Local Database instantly
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val db = DatabaseProvider.getDatabase(context)
            val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid

            if (uid != null) {
                db.creditDao().addTransaction(
                    CreditEntry(
                        userId = uid, amount = -amount, senderId = "Pending|$targetPhoneB|Unknown",
                        timestamp = System.currentTimeMillis(), transactionHash = hash,
                        previousHash = "REMOTE_SMS", isSynced = false, targetVirtualAccount = senderVA
                    )
                )
                // Force UI to refresh on the main thread
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    (context as? MainActivity)?.viewModel?.refreshDataForUser(uid)
                }
            }
        }

        // 4. Send immediately!
        processSmsQueue(context, isImmediate = true)
    }

    // ✨ THE MULTIPART QUEUE PROCESSOR
    fun processSmsQueue(context: android.content.Context, isImmediate: Boolean = false) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val prefs = context.getSharedPreferences("PaysetuSmsQueue", android.content.Context.MODE_PRIVATE)
            if (prefs.all.isEmpty()) return@launch

            val isAirplaneModeOn = android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) != 0
            if (isAirplaneModeOn) {
                android.util.Log.d("CryptoDemo", "✈️ QUEUE: Airplane mode ON. Holding SMS securely.")
                return@launch
            }

            // If coming out of Airplane mode, wait 8 seconds for towers. Otherwise, send instantly.
            if (!isImmediate) {
                android.util.Log.d("CryptoDemo", "⏳ QUEUE: Waiting 8s for cellular modem lock...")
                kotlinx.coroutines.delay(8000)
            }

            val CENTRAL_HUB_NUMBER = "+919920833792"

            // Bulletproof SmsManager acquisition for Dual-SIM/Modern devices
            val smsManager: android.telephony.SmsManager = try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    context.getSystemService(android.telephony.SmsManager::class.java) ?: android.telephony.SmsManager.getDefault()
                } else {
                    @Suppress("DEPRECATION")
                    android.telephony.SmsManager.getDefault()
                }
            } catch (e: Exception) {
                @Suppress("DEPRECATION")
                android.telephony.SmsManager.getDefault()
            }

            for ((hash, payloadStr) in prefs.all) {
                try {
                    val payload = payloadStr as String
                    val parts = smsManager.divideMessage(payload)

                    android.util.Log.d("CryptoDemo", "📦 QUEUE: Splitting ${payload.length}-char payload into ${parts.size} chunks.")

                    // Attach PendingIntents so the OS physically tracks the broadcast
                    val sentIntents = java.util.ArrayList<android.app.PendingIntent>()
                    for (i in parts.indices) {
                        val intent = android.content.Intent("SMS_SENT_ACTION_PAYSETU")
                        val pendingIntent = android.app.PendingIntent.getBroadcast(
                            context, i, intent,
                            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                        )
                        sentIntents.add(pendingIntent)
                    }

                    smsManager.sendMultipartTextMessage(CENTRAL_HUB_NUMBER, null, parts, sentIntents, null)

                    // Remove from queue ONLY after handoff
                    prefs.edit().remove(hash).commit()
                    android.util.Log.d("CryptoDemo", "🚀 QUEUE: Successfully Dispatched MULTIPART SMS: $hash")

                    // 🎉 Visual confirmation for the Mentor!
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(context, "🔒 Encrypted SMS injected to Cellular Radio!", android.widget.Toast.LENGTH_LONG).show()
                    }

                } catch (e: Exception) {
                    android.util.Log.e("CryptoDemo", "❌ QUEUE: Failed to dispatch SMS", e)
                }
            }
        }
    }
}