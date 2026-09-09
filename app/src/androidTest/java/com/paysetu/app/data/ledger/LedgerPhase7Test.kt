//package com.paysetu.app.data.ledger
//
//import androidx.room.Room
//import androidx.test.core.app.ApplicationProvider
//import androidx.test.ext.junit.runners.AndroidJUnit4
//import com.paysetu.app.data.ledger.LedgerRepository
//import com.paysetu.app.data.ledger.dao.LedgerDao
//import com.paysetu.app.data.ledger.entity.LedgerTransactionEntity
//import com.paysetu.app.data.ledger.entity.TransactionDirection
//import com.paysetu.app.data.ledger.entity.TransactionStatus
//import kotlinx.coroutines.test.runTest
//import org.junit.Assert.assertNull
//import org.junit.Assert.fail
//import org.junit.Before
//import org.junit.Test
//import org.junit.runner.RunWith
//
//
//
//@RunWith(AndroidJUnit4::class)
//class LedgerPhase7Test {
//
//    private lateinit var ledgerDao: LedgerDao
//    private lateinit var repository: LedgerRepository
//    private lateinit var validTx: LedgerTransactionEntity
//
//    @Before
//    fun setup() {
//        val db = Room.inMemoryDatabaseBuilder(
//            ApplicationProvider.getApplicationContext(),
//            PaySetuDatabase::class.java
//        ).allowMainThreadQueries().build()
//
//        ledgerDao = db.ledgerDao()
//        val deviceStateDao = db.deviceStateDao()
//
//        repository = LedgerRepository(
//            ledgerDao = ledgerDao,
//            deviceStateDao = deviceStateDao
//        )
//
//        validTx = LedgerTransactionEntity(
//            txHash = ByteArray(32) { 1 },
//            prevTxHash = ByteArray(32) { 0 },
//            senderDeviceId = ByteArray(16),
//            receiverDeviceId = ByteArray(16),
//            amount = 100,
//            timestamp = System.currentTimeMillis(),
//            signature = ByteArray(64),
//            direction = TransactionDirection.OUTGOING,
//            status = TransactionStatus.ACCEPTED
//        )
//    }
//
//    @Test
//    fun transaction_is_atomic() = runTest {
//        val badTx = validTx.copy(
//            prevTxHash = ByteArray(32) { 9 }
//        )
//
//        try {
//            repository.appendTransactionAtomically(badTx)
//            fail("Expected failure due to broken chain")
//        } catch (e: IllegalArgumentException) {
//            // expected
//        }
//
//        val lastTx = ledgerDao.getLastTransaction()
//        assertNull(lastTx)
//    }
//}
