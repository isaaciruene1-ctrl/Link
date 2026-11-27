package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrustedDeviceDao {
    @Query("SELECT * FROM trusted_devices WHERE isTrusted = 1 ORDER BY lastConnectedAt DESC")
    fun observeTrustedDevices(): Flow<List<TrustedDeviceEntity>>

    @Query("SELECT * FROM trusted_devices WHERE deviceId = :deviceId LIMIT 1")
    suspend fun getById(deviceId: String): TrustedDeviceEntity?

    @Query("SELECT * FROM trusted_devices WHERE isTrusted = 1")
    suspend fun getAllTrustedSync(): List<TrustedDeviceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: TrustedDeviceEntity)

    @Query("DELETE FROM trusted_devices WHERE deviceId = :deviceId")
    suspend fun deleteById(deviceId: String)

    @Query("DELETE FROM trusted_devices")
    suspend fun deleteAll()
}

@Dao
interface TransferRecordDao {
    @Query("SELECT * FROM transfer_records ORDER BY createdAt DESC LIMIT 100")
    fun observeTransfers(): Flow<List<TransferRecordEntity>>

    @Query("SELECT * FROM transfer_records WHERE transferId = :transferId LIMIT 1")
    suspend fun getById(transferId: String): TransferRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: TransferRecordEntity)

    @Query("DELETE FROM transfer_records WHERE status IN ('COMPLETED', 'CANCELLED', 'FAILED')")
    suspend fun clearCompleted()
}

@Dao
interface ClipboardEntryDao {
    @Query("SELECT * FROM clipboard_entries WHERE expiresAt > :now ORDER BY createdAt DESC LIMIT 30")
    fun observeActiveEntries(now: Long = System.currentTimeMillis()): Flow<List<ClipboardEntryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: ClipboardEntryEntity)

    @Query("DELETE FROM clipboard_entries WHERE expiresAt <= :now")
    suspend fun pruneExpired(now: Long = System.currentTimeMillis())

    @Query("DELETE FROM clipboard_entries")
    suspend fun clearAll()
}

@Dao
interface SyncedNotificationDao {
    @Query("SELECT * FROM synced_notifications WHERE isDismissed = 0 ORDER BY postedAt DESC LIMIT 100")
    fun observeActiveNotifications(): Flow<List<SyncedNotificationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(notification: SyncedNotificationEntity)

    @Query("UPDATE synced_notifications SET isRead = 1 WHERE notificationKey = :key")
    suspend fun markRead(key: String)

    @Query("UPDATE synced_notifications SET isRead = 1")
    suspend fun markAllRead()

    @Query("UPDATE synced_notifications SET isDismissed = 1 WHERE notificationKey = :key")
    suspend fun markDismissed(key: String)

    @Query("DELETE FROM synced_notifications")
    suspend fun clearAll()
}

@Dao
interface ActivityLogDao {
    @Query("SELECT * FROM activity_logs ORDER BY timestamp DESC LIMIT 80")
    fun observeRecentLogs(): Flow<List<ActivityLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: ActivityLogEntity)

    @Query("DELETE FROM activity_logs")
    suspend fun clearAll()
}

@Dao
interface AutomationRuleDao {
    @Query("SELECT * FROM automation_rules ORDER BY title ASC")
    fun observeRules(): Flow<List<AutomationRuleEntity>>

    @Query("SELECT * FROM automation_rules WHERE enabled = 1 AND triggerType = :triggerType")
    suspend fun getEnabledByTrigger(triggerType: String): List<AutomationRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: AutomationRuleEntity)

    @Query("UPDATE automation_rules SET enabled = :enabled WHERE ruleId = :ruleId")
    suspend fun setEnabled(ruleId: String, enabled: Boolean)

    @Query("UPDATE automation_rules SET lastTriggeredAt = :ts WHERE ruleId = :ruleId")
    suspend fun markTriggered(ruleId: String, ts: Long = System.currentTimeMillis())
}
