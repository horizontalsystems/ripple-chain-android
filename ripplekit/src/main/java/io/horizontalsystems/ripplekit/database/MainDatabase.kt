package io.horizontalsystems.ripplekit.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import io.horizontalsystems.ripplekit.models.AccountState
import io.horizontalsystems.ripplekit.models.Amount
import io.horizontalsystems.ripplekit.models.LedgerState
import io.horizontalsystems.ripplekit.models.Transaction
import io.horizontalsystems.ripplekit.models.TransactionSyncState
import io.horizontalsystems.ripplekit.models.TrustLine
import io.horizontalsystems.ripplekit.network.Network
import java.math.BigDecimal
import java.math.BigInteger

@Database(
    entities = [AccountState::class, LedgerState::class, TrustLine::class, Transaction::class, TransactionSyncState::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(RoomTypeConverters::class)
internal abstract class MainDatabase : RoomDatabase() {
    abstract fun accountStateDao(): AccountStateDao
    abstract fun ledgerStateDao(): LedgerStateDao
    abstract fun trustLineDao(): TrustLineDao
    abstract fun transactionDao(): TransactionDao
    abstract fun transactionSyncStateDao(): TransactionSyncStateDao

    companion object {
        fun getInstance(context: Context, name: String): MainDatabase =
            Room.databaseBuilder(context, MainDatabase::class.java, name)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .allowMainThreadQueries()
                .build()
    }
}

internal object RippleDatabaseManager {
    private fun name(network: Network, walletId: String) = "Ripple-${network.name}-$walletId"

    fun getDatabase(context: Context, network: Network, walletId: String): MainDatabase =
        MainDatabase.getInstance(context, name(network, walletId))

    fun clear(context: Context, network: Network, walletId: String) {
        context.deleteDatabase(name(network, walletId))
    }
}

internal class RoomTypeConverters {
    @TypeConverter
    fun bigDecimalToString(value: BigDecimal?): String? = value?.toPlainString()

    @TypeConverter
    fun stringToBigDecimal(value: String?): BigDecimal? = value?.let { BigDecimal(it) }

    // Amount is stored as "drops|<n>" or "iou|<value>|<currency>|<issuer>"
    @TypeConverter
    fun amountToString(value: Amount?): String? = when (value) {
        null -> null
        is Amount.Xrp -> "drops|${value.drops}"
        is Amount.Issued -> "iou|${value.value.toPlainString()}|${value.currency}|${value.issuer}"
    }

    @TypeConverter
    fun stringToAmount(value: String?): Amount? {
        if (value == null) return null
        val parts = value.split("|")
        return when (parts[0]) {
            "drops" -> Amount.Xrp(BigInteger(parts[1]))
            "iou" -> Amount.Issued(BigDecimal(parts[1]), parts[2], parts[3])
            else -> null
        }
    }
}

@Dao
internal interface AccountStateDao {
    @Query("SELECT * FROM AccountState WHERE id = 1")
    fun get(): AccountState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun save(state: AccountState)
}

@Dao
internal interface LedgerStateDao {
    @Query("SELECT * FROM LedgerState WHERE id = 1")
    fun get(): LedgerState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun save(state: LedgerState)
}

@Dao
internal interface TrustLineDao {
    @Query("SELECT * FROM TrustLine")
    fun getAll(): List<TrustLine>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(lines: List<TrustLine>)

    @Query("DELETE FROM TrustLine")
    fun deleteAll()
}

@Dao
internal interface TransactionDao {
    @Query("SELECT * FROM `Transaction` WHERE hash = :hash")
    fun get(hash: String): Transaction?

    @Query("SELECT * FROM `Transaction` ORDER BY timestamp DESC, ledgerIndex DESC")
    fun getAll(): List<Transaction>

    @Query("SELECT * FROM `Transaction` WHERE timestamp < :beforeTimestamp ORDER BY timestamp DESC, ledgerIndex DESC LIMIT :limit")
    fun getBefore(beforeTimestamp: Long, limit: Int): List<Transaction>

    @Query("SELECT * FROM `Transaction` ORDER BY timestamp DESC, ledgerIndex DESC LIMIT :limit")
    fun getLatest(limit: Int): List<Transaction>

    @Query("SELECT * FROM `Transaction` WHERE validated = 0 AND failed = 0")
    fun getPending(): List<Transaction>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(transactions: List<Transaction>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(transaction: Transaction)
}

@Dao
internal interface TransactionSyncStateDao {
    @Query("SELECT * FROM TransactionSyncState WHERE id = 1")
    fun get(): TransactionSyncState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun save(state: TransactionSyncState)
}
