package com.oxygens.registry

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CloneDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: CloneEntity)

    @Query("SELECT * FROM clones WHERE cloneId = :cloneId LIMIT 1")
    suspend fun getById(cloneId: Int): CloneEntity?

    /**
     * A process group (e.g. ":clone_0") is allocated to exactly one clone at a time
     * (see CloneManager.installClone) — this is how a freshly-attached clone process
     * figures out which clone it is responsible for, since it starts with nothing but
     * its own process name.
     */
    @Query("SELECT * FROM clones WHERE processGroup = :processGroup LIMIT 1")
    suspend fun getByProcessGroup(processGroup: String): CloneEntity?

    @Query("SELECT * FROM clones ORDER BY installedAtMillis DESC")
    suspend fun getAll(): List<CloneEntity>

    @Query("DELETE FROM clones WHERE cloneId = :cloneId")
    suspend fun delete(cloneId: Int)
}
