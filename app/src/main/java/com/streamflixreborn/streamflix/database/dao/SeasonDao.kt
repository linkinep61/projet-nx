package com.streamflixreborn.streamflix.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.streamflixreborn.streamflix.models.Season
import kotlinx.coroutines.flow.Flow

@Dao
interface SeasonDao {

    @Query("SELECT * FROM seasons")
    fun getAllForBackup(): List<Season>

    @Query("SELECT * FROM seasons WHERE id = :id")
    fun getById(id: String): Season?

    @Query("SELECT * FROM seasons WHERE id IN (:ids)")
    fun getByIdsPaquet(ids: List<String>): List<Season>

    // 2026-10-24 : même limite SQLite que TvShowDao.getByIds (999 paramètres max jusqu'à
    //   Android 10) → requête par paquets de 500.
    fun getByIds(ids: List<String>): List<Season> =
        if (ids.size <= 500) getByIdsPaquet(ids) else ids.chunked(500).flatMap { getByIdsPaquet(it) }

    @Query("SELECT * FROM seasons WHERE id = :id")
    fun getByIdAsFlow(id: String): Flow<Season?>

    @Query("SELECT * FROM seasons WHERE tvShow = :tvShowId")
    fun getByTvShowIdAsFlow(tvShowId: String): Flow<List<Season>>

    @Query("SELECT * FROM seasons WHERE tvShow = :tvShowId ORDER BY number")
    fun getByTvShowId(tvShowId: String): List<Season>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(season: Season)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(seasons: List<Season>)

    @Query("DELETE FROM seasons")
    fun deleteAll()

    @Transaction
    fun saveAll(seasons: List<Season>) {
        // La logica di "save" per le stagioni è meno critica di Movie/TvShow, usiamo REPLACE
        // per salvare tutte le stagioni di un provider in blocco.
        insertAll(seasons)
    }
}
