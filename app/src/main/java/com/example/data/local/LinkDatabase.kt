package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TrustedDeviceEntity::class,
        TransferRecordEntity::class,
        ClipboardEntryEntity::class,
        SyncedNotificationEntity::class,
        ActivityLogEntity::class,
        AutomationRuleEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class LinkDatabase : RoomDatabase() {
    abstract fun trustedDeviceDao(): TrustedDeviceDao
    abstract fun transferRecordDao(): TransferRecordDao
    abstract fun clipboardEntryDao(): ClipboardEntryDao
    abstract fun syncedNotificationDao(): SyncedNotificationDao
    abstract fun activityLogDao(): ActivityLogDao
    abstract fun automationRuleDao(): AutomationRuleDao

    companion object {
        fun build(context: Context): LinkDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                LinkDatabase::class.java,
                "link_ecosystem.db"
            ).fallbackToDestructiveMigration(dropAllTables = true).build()
    }
}
