package com.paysetu.offlinevault

import androidx.room.*
import android.content.Context
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

// ✨ 1. The Virtual Account (Wallet) Table
@Entity(tableName = "wallets")
data class WalletEntity(
    @PrimaryKey val virtualAccountId: String, // e.g., "880012345678-01"
    val userId: String,
    val walletName: String,                   // e.g., "Primary", "Business 1", "VESIT Canteen"
    val isPrimary: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val themeColor: Int = 0xFFF9AA33.toInt()
)

// 2. Transaction Table Structure (Upgraded for Virtual Accounts)
@Entity(tableName = "credits")
data class CreditEntry(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val userId: String,
    val amount: Long,
    val senderId: String,
    val timestamp: Long,
    val previousHash: String,
    val transactionHash: String,
    val isSynced: Boolean = false,

    // ✨ THE IMMUTABLE LEDGER LOCK ✨
    // The money is permanently locked to a specific Virtual Account ID. No tampering allowed.
    val targetVirtualAccount: String,
    val note: String? = null
)

@Entity(tableName = "notifications")
data class NotificationEntry(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val userId: String,
    val title: String,
    val message: String,
    val timestamp: Long,
    var isRead: Boolean = false
)

// 3. Smart Routing Rules Table
@Entity(tableName = "routing_rules")
data class RoutingRule(
    @PrimaryKey val phoneNumber: String,
    val targetVirtualAccount: String // Routes to "880012345678-01" instead of "Business"
)

// ✨ NEW: 4. Blocked Users Table (Safe Harbor Protocol)
@Entity(tableName = "blocked_users")
data class BlockedUser(
    @PrimaryKey val phoneNumber: String,
    val blockedAtTimestamp: Long
)

// 5. Data Access Object
@Dao
interface CreditDao {

    // --- TRANSACTION QUERIES ---
    @Query("SELECT * FROM credits WHERE userId = :uid ORDER BY timestamp DESC")
    fun getHistoryForUser(uid: String): kotlinx.coroutines.flow.Flow<List<CreditEntry>>

    // ✨ Fetch history ONLY for a specific Virtual Account
    @Query("SELECT * FROM credits WHERE userId = :uid AND targetVirtualAccount = :virtualAccountId ORDER BY timestamp DESC")
    fun getHistoryForWallet(uid: String, virtualAccountId: String): kotlinx.coroutines.flow.Flow<List<CreditEntry>>

    @Query("SELECT * FROM credits ORDER BY timestamp DESC")
    fun getAllTransactions(): kotlinx.coroutines.flow.Flow<List<CreditEntry>>

    @Query("SELECT SUM(amount) FROM credits WHERE userId = :uid")
    fun getBalanceForUser(uid: String): kotlinx.coroutines.flow.Flow<Long?>

    // ✨ Get the balance ONLY for a specific Virtual Account
    @Query("SELECT SUM(amount) FROM credits WHERE userId = :uid AND targetVirtualAccount = :virtualAccountId")
    fun getBalanceForWallet(uid: String, virtualAccountId: String): kotlinx.coroutines.flow.Flow<Long?>

    @Insert
    fun addTransaction(entry: CreditEntry)

    @Query("SELECT * FROM credits WHERE userId = :uid AND isSynced = 0")
    suspend fun getUnsyncedTransactions(uid: String): List<CreditEntry>

    @Query("UPDATE credits SET isSynced = 1 WHERE transactionHash IN (:hashes)")
    suspend fun markTransactionsAsSynced(hashes: List<String>)

    @Query("SELECT COUNT(*) FROM credits WHERE transactionHash = :hash")
    suspend fun isTransactionProcessed(hash: String): Int

    @Query("UPDATE credits SET senderId = :newSmartId WHERE transactionHash = :hash")
    suspend fun updateTransactionSmartId(hash: String, newSmartId: String)

    @Query("SELECT * FROM credits WHERE senderId LIKE 'Pending|%'")
    suspend fun getPendingTransactions(): List<CreditEntry>

    @Query("DELETE FROM credits WHERE transactionHash = :hash")
    suspend fun deleteTransactionByHash(hash: String)

    // ✨ Helper query for DisputeManager to visually tag refunded transactions
    @Query("UPDATE credits SET note = :newNote WHERE transactionHash = :hash")
    suspend fun updateTransactionNote(hash: String, newNote: String)


    // --- ✨ NEW SYNC MANAGER QUERIES ✨ ---
    @Query("SELECT * FROM credits")
    suspend fun getAllTransactionsDirectly(): List<CreditEntry>

    @Query("UPDATE credits SET isSynced = 1 WHERE transactionHash = :hash")
    suspend fun markAsSynced(hash: String)

    @Query("UPDATE credits SET amount = :correctAmount, isSynced = 1 WHERE transactionHash = :hash")
    suspend fun updateTransactionAmount(hash: String, correctAmount: Long)


    // --- NOTIFICATION QUERIES ---
    @Query("SELECT * FROM notifications WHERE userId = :uid ORDER BY timestamp DESC")
    fun getNotificationsForUser(uid: String): kotlinx.coroutines.flow.Flow<List<NotificationEntry>>

    @Insert
    fun addNotification(entry: NotificationEntry)


    // --- ROUTING RULES QUERIES ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveRoutingRule(rule: RoutingRule)

    @Query("SELECT targetVirtualAccount FROM routing_rules WHERE phoneNumber = :phone LIMIT 1")
    suspend fun getVirtualAccountForPhone(phone: String): String?

    @Query("DELETE FROM routing_rules WHERE phoneNumber = :phone")
    suspend fun deleteRoutingRule(phone: String)

    @Query("SELECT * FROM routing_rules")
    suspend fun getAllRoutingRulesDirect(): List<RoutingRule>


    // --- NEW WALLET MANAGEMENT QUERIES ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWallet(wallet: WalletEntity)

    // Gets every wallet the user has created
    @Query("SELECT * FROM wallets WHERE userId = :uid")
    fun getAllWalletsForUser(uid: String): kotlinx.coroutines.flow.Flow<List<WalletEntity>>

    // Finds the fallback/default wallet (usually ending in -00)
    @Query("SELECT virtualAccountId FROM wallets WHERE userId = :uid AND isPrimary = 1 LIMIT 1")
    suspend fun getPrimaryWalletId(uid: String): String?

    // Simple Update Query to change color later
    @Query("UPDATE wallets SET themeColor = :newColor WHERE virtualAccountId = :walletId")
    suspend fun updateWalletColor(walletId: String, newColor: Int)

    @Query("SELECT * FROM credits WHERE transactionHash = :hash LIMIT 1")
    suspend fun getTransactionByHash(hash: String): CreditEntry?


    // --- ✨ NEW BLOCKED USERS QUERIES (MERCHANT FIREWALL) ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun blockUser(user: BlockedUser)

    @Query("SELECT * FROM blocked_users ORDER BY blockedAtTimestamp DESC")
    fun getBlockedUsers(): kotlinx.coroutines.flow.Flow<List<BlockedUser>>

    @Query("DELETE FROM blocked_users WHERE phoneNumber = :phone")
    suspend fun unblockUserLocally(phone: String)
}

// ✨ Updated Database configuration with the new BlockedUser table and Version bump
@Database(
    entities = [CreditEntry::class, NotificationEntry::class, RoutingRule::class, WalletEntity::class, BlockedUser::class],
    version = 9
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun creditDao(): CreditDao
}

// 5. THE PROVIDER
object DatabaseProvider {
    private var INSTANCE: AppDatabase? = null

    fun getDatabase(context: Context): AppDatabase {
        return INSTANCE ?: synchronized(this) {
            System.loadLibrary("sqlcipher")

            val passphrase = "your_secure_password_here".toByteArray()
            val factory = SupportOpenHelperFactory(passphrase)

            val instance = Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "offline_vault.db"
            )
                .openHelperFactory(factory)
                .fallbackToDestructiveMigration()
                .allowMainThreadQueries()
                .build()

            INSTANCE = instance
            instance
        }
    }
}