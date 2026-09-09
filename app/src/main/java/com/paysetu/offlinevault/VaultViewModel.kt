package com.paysetu.offlinevault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FirebaseFirestore // ✨ Added Firebase Import
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class VaultViewModel(private val dao: CreditDao) : ViewModel() {

    // ✨ 1. Tracks the full list of wallets the user owns in the database
    private val _wallets = MutableStateFlow<List<WalletEntity>>(emptyList())
    val wallets: StateFlow<List<WalletEntity>> = _wallets.asStateFlow()

    // ✨ 2. Tracks the active Virtual Account ID (e.g., "A1B2C3D4-00") instead of "Personal"
    private val _activeVirtualAccountId = MutableStateFlow<String>("")
    val activeVirtualAccountId: StateFlow<String> = _activeVirtualAccountId.asStateFlow()

    private val currentUid = MutableStateFlow<String?>(null)

    // ✨ 3. Filtered History: Auto-updates when the virtual account changes!
    @OptIn(ExperimentalCoroutinesApi::class)
    val history: StateFlow<List<CreditEntry>> = combine(currentUid, _activeVirtualAccountId) { uid, van ->
        Pair(uid, van)
    }.flatMapLatest { (uid, van) ->
        if (uid != null && van.isNotEmpty()) dao.getHistoryForWallet(uid, van) else flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ✨ 4. Filtered Balance: Auto-updates when the virtual account changes!
    @OptIn(ExperimentalCoroutinesApi::class)
    val balance: StateFlow<Long> = combine(currentUid, _activeVirtualAccountId) { uid, van ->
        Pair(uid, van)
    }.flatMapLatest { (uid, van) ->
        if (uid != null && van.isNotEmpty()) dao.getBalanceForWallet(uid, van).map { it ?: 0L } else flowOf(0L)
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0L)

    @OptIn(ExperimentalCoroutinesApi::class)
    val notifications: StateFlow<List<NotificationEntry>> = currentUid.flatMapLatest { uid ->
        if (uid != null) dao.getNotificationsForUser(uid) else flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun refreshDataForUser(uid: String) {
        if (currentUid.value == uid) return // Prevent redundant collectors
        currentUid.value = uid

        viewModelScope.launch {
            dao.getAllWalletsForUser(uid).collect { userWallets ->
                if (userWallets.isEmpty()) {
                    // Create the Primary wallet locally
                    val shortUid = uid.take(8).uppercase()
                    val primaryId = "${shortUid}-00"
                    val premiumGold = 0xFFF9AA33.toInt()

                    dao.insertWallet(
                        WalletEntity(
                            virtualAccountId = primaryId,
                            userId = uid,
                            walletName = "Primary Wallet",
                            isPrimary = true,
                            themeColor = premiumGold
                        )
                    )

                    // ✨ FIREBASE FIX: Tell the cloud the Primary Wallet exists!
                    try {
                        val db = FirebaseFirestore.getInstance()
                        db.collection("users").document(uid).update(
                            "wallet_balances.$primaryId", 0L
                        )
                    } catch (e: Exception) {
                        // Silent catch. It will re-sync next time if network fails.
                    }

                } else {
                    _wallets.value = userWallets
                    // If no account is active yet, set the primary one
                    if (_activeVirtualAccountId.value.isEmpty()) {
                        val primary = userWallets.find { it.isPrimary } ?: userWallets.first()
                        _activeVirtualAccountId.value = primary.virtualAccountId
                    }
                }
            }
        }
    }

    // Call this from the UI to create Business 1, Business 2, etc.
    fun createNewWallet(walletName: String, colorInt: Int) {
        val uid = currentUid.value ?: return
        val shortUid = uid.take(8).uppercase()
        val newIndex = _wallets.value.size
        val newId = "${shortUid}-0$newIndex"

        viewModelScope.launch {
            // Save locally
            dao.insertWallet(
                WalletEntity(
                    virtualAccountId = newId,
                    userId = uid,
                    walletName = walletName,
                    isPrimary = false,
                    themeColor = colorInt
                )
            )
            _activeVirtualAccountId.value = newId

            // ✨ FIREBASE FIX: Instantly push the new wallet slot to the Cloud Ledger!
            try {
                val db = FirebaseFirestore.getInstance()
                db.collection("users").document(uid).update(
                    "wallet_balances.$newId", 0L
                )
            } catch (e: Exception) {
                // If the user is offline, we still created the wallet locally.
                // We just couldn't tell Firebase about the 0 balance yet.
            }
        }
    }

    // Call this when the user taps the Dropdown on the UI to switch wallets
    fun setVirtualAccount(virtualAccountId: String) {
        _activeVirtualAccountId.value = virtualAccountId
    }
}