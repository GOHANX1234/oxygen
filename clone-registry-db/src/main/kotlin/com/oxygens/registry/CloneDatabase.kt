package com.oxygens.registry

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// exportSchema=false — no schema-migration tracking directory configured yet.
@Database(entities = [CloneEntity::class], version = 3, exportSchema = false)
abstract class CloneDatabase : RoomDatabase() {

    abstract fun cloneDao(): CloneDao

    companion object {
        @Volatile private var instance: CloneDatabase? = null

        fun getInstance(context: Context): CloneDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CloneDatabase::class.java,
                    "oxygen-s-clone-registry.db",
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build().also { instance = it }
            }

        /** v1→v2: added nullable iconPath column. */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE clones ADD COLUMN iconPath TEXT")
            }
        }

        /** v2→v3: added nullable mainActivity column (fully-qualified launcher Activity name). */
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE clones ADD COLUMN mainActivity TEXT")
            }
        }
    }
}
