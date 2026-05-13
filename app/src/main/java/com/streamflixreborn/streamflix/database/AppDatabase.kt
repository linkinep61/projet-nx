package com.streamflixreborn.streamflix.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.streamflixreborn.streamflix.database.dao.EpisodeDao
import com.streamflixreborn.streamflix.database.dao.MovieDao
import com.streamflixreborn.streamflix.database.dao.SeasonDao
import com.streamflixreborn.streamflix.database.dao.TvShowDao
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.Profile
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.utils.ProfileManager
import com.streamflixreborn.streamflix.utils.UserPreferences
import java.io.File

@Database(
    entities = [
        Episode::class,
        Movie::class,
        Season::class,
        TvShow::class,
    ],
    version = 8,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun movieDao(): MovieDao

    abstract fun tvShowDao(): TvShowDao

    abstract fun seasonDao(): SeasonDao

    abstract fun episodeDao(): EpisodeDao

    companion object {

        @Volatile
        private var INSTANCE: AppDatabase? = null
        @Volatile
        private var currentProviderName: String? = null
        @Volatile
        private var currentProfileId: String? = null

        private fun sanitizeProviderName(name: String): String {
            // Rimuove caratteri non validi per i nomi dei file DB,
            // come spazi, parentesi, e li converte in lowercase.
            return name.lowercase()
                .replace("[^a-z0-9]".toRegex(), "_")
                .replace("__+".toRegex(), "_") // Sostituisce doppie underscore con una singola
                .trim('_') // Rimuove underscore iniziale/finale
        }

        /** 2026-05-12 : nom de fichier DB inclut maintenant le profileId pour
         *  isoler les favoris/historique entre profils. Format :
         *    `{profileId}_{providerName}.db`
         *  Le profil "default" (créé à la 1re ouverture) hérite des DBs
         *  existantes via [migrateLegacyDbFilesIfNeeded]. */
        private fun buildDbFileName(profileId: String, providerName: String): String {
            val safeProfile = profileId.lowercase().replace("[^a-z0-9_]".toRegex(), "_")
            return "${safeProfile}_${sanitizeProviderName(providerName)}.db"
        }

        fun setup(context: Context) {
            // 2026-05-12 : à la 1re ouverture après update multi-profil, on
            // renomme les anciens fichiers DB `{provider}.db` → `default_{provider}.db`
            // pour préserver favoris/historique existants sans perte.
            try {
                migrateLegacyDbFilesIfNeeded(context)
            } catch (e: Exception) {
                android.util.Log.w("AppDatabase", "Legacy DB migration failed (non-fatal): ${e.message}")
            }
            if (UserPreferences.currentProvider == null) return

            getInstance(context)
        }

        fun getInstance(context: Context): AppDatabase {
            val providerName = UserPreferences.currentProvider?.name
                ?: currentProviderName
                ?: throw IllegalStateException("Current provider is not set")
            val profileId = ProfileManager.currentProfileIdOrDefault()

            return INSTANCE?.takeIf {
                currentProviderName == providerName && currentProfileId == profileId
            } ?: synchronized(this) {
                INSTANCE?.takeIf {
                    currentProviderName == providerName && currentProfileId == profileId
                } ?: run {
                    INSTANCE?.close()
                    buildDatabase(profileId, providerName, context).also { instance ->
                        INSTANCE = instance
                        currentProviderName = providerName
                        currentProfileId = profileId
                    }
                }
            }
        }

        // Metodo per forzare il cambio di database quando cambia il provider OU profil
        fun resetInstance() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
                currentProviderName = null
                currentProfileId = null
            }
        }

        fun getInstanceForProvider(providerName: String, context: Context): AppDatabase {
            val profileId = ProfileManager.currentProfileIdOrDefault()
            return buildDatabase(profileId, providerName, context)
        }

        /** Renomme les anciens DBs `{provider}.db` → `default_{provider}.db`
         *  pour qu'ils soient utilisés par le profil "default" auto-créé.
         *  Idempotent : si déjà migré, no-op. */
        private fun migrateLegacyDbFilesIfNeeded(context: Context) {
            val dbDir = context.getDatabasePath("dummy").parentFile ?: return
            if (!dbDir.exists()) return
            val files = dbDir.listFiles() ?: return
            files.forEach { f ->
                val name = f.name
                if (!name.endsWith(".db")) return@forEach
                // Anciens fichiers : `{provider}.db` (ex: cloudstream.db, frenchstream.db).
                // Nouveaux fichiers : `default_{provider}.db` (ou `{profileId}_...`).
                // Si le nom commence par un underscore après un préfixe court, c'est
                // probablement déjà préfixé. On regarde si "_db" pattern existant.
                if (name.startsWith("${Profile.DEFAULT_ID}_") ||
                    name.startsWith("p_") ||  // nouveau format profile ID
                    name.contains("-journal") ||
                    name.contains("-shm") ||
                    name.contains("-wal")
                ) return@forEach
                // SKIP : c'est un ancien DB, le renommer.
                val newName = "${Profile.DEFAULT_ID}_$name"
                val target = File(dbDir, newName)
                if (target.exists()) return@forEach  // Déjà migré ?
                try {
                    val ok = f.renameTo(target)
                    // Rename aussi les fichiers WAL/SHM/journal associés s'ils existent.
                    listOf("-journal", "-shm", "-wal").forEach { suffix ->
                        val side = File(dbDir, "$name$suffix")
                        if (side.exists()) {
                            side.renameTo(File(dbDir, "$newName$suffix"))
                        }
                    }
                    android.util.Log.d("AppDatabase", "Legacy DB migration: $name → $newName (ok=$ok)")
                } catch (e: Exception) {
                    android.util.Log.w("AppDatabase", "Failed to rename legacy DB $name: ${e.message}")
                }
            }
        }

        private fun buildDatabase(profileId: String, providerName: String, context: Context): AppDatabase {
            val dbFileName = buildDbFileName(profileId, providerName)
            return Room.databaseBuilder(
                context = context.applicationContext,
                klass = AppDatabase::class.java,
                name = dbFileName
            )
                .allowMainThreadQueries()
                .addMigrations(MIGRATION_1_2)
                .addMigrations(MIGRATION_2_3)
                .addMigrations(MIGRATION_3_4)
                .addMigrations(MIGRATION_4_5)
                .addMigrations(MIGRATION_5_6)
                .addMigrations(MIGRATION_6_7)
                .addMigrations(MIGRATION_7_8)
                .build()
        }


        private val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE episodes ADD COLUMN watchedDate TEXT")
                db.execSQL("ALTER TABLE episodes ADD COLUMN lastEngagementTimeUtcMillis INTEGER")
                db.execSQL("ALTER TABLE episodes ADD COLUMN lastPlaybackPositionMillis INTEGER")
                db.execSQL("ALTER TABLE episodes ADD COLUMN durationMillis INTEGER")

                db.execSQL("ALTER TABLE movies ADD COLUMN watchedDate TEXT")
                db.execSQL("ALTER TABLE movies ADD COLUMN lastEngagementTimeUtcMillis INTEGER")
                db.execSQL("ALTER TABLE movies ADD COLUMN lastPlaybackPositionMillis INTEGER")
                db.execSQL("ALTER TABLE movies ADD COLUMN durationMillis INTEGER")
            }
        }

        private val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE `episodes_temp` (`id` TEXT NOT NULL, `number` INTEGER NOT NULL, `title` TEXT, `poster` TEXT, `tvShow` TEXT, `season` TEXT, `released` TEXT, `isWatched` INTEGER NOT NULL, `watchedDate` TEXT, `lastEngagementTimeUtcMillis` INTEGER, `lastPlaybackPositionMillis` INTEGER, `durationMillis` INTEGER, PRIMARY KEY(`id`))")
                db.execSQL("INSERT INTO episodes_temp SELECT * FROM episodes")
                db.execSQL("DROP TABLE episodes")
                db.execSQL("ALTER TABLE episodes_temp RENAME TO episodes")

                db.execSQL("CREATE TABLE `movies_temp` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `overview` TEXT, `runtime` INTEGER, `trailer` TEXT, `quality` TEXT, `rating` REAL, `poster` TEXT, `banner` TEXT, `released` TEXT, `isFavorite` INTEGER NOT NULL, `isWatched` INTEGER NOT NULL, `watchedDate` TEXT, `lastEngagementTimeUtcMillis` INTEGER, `lastPlaybackPositionMillis` INTEGER, `durationMillis` INTEGER, PRIMARY KEY(`id`))")
                db.execSQL("INSERT INTO movies_temp SELECT * FROM movies")
                db.execSQL("DROP TABLE movies")
                db.execSQL("ALTER TABLE movies_temp RENAME TO movies")

                db.execSQL("CREATE TABLE `seasons_temp` (`id` TEXT NOT NULL, `number` INTEGER NOT NULL, `title` TEXT, `poster` TEXT, `tvShow` TEXT, PRIMARY KEY(`id`))")
                db.execSQL("INSERT INTO seasons_temp SELECT * FROM seasons")
                db.execSQL("DROP TABLE seasons")
                db.execSQL("ALTER TABLE seasons_temp RENAME TO seasons")

                db.execSQL("CREATE TABLE `tv_shows_temp` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `overview` TEXT, `runtime` INTEGER, `trailer` TEXT, `quality` TEXT, `rating` REAL, `poster` TEXT, `banner` TEXT, `released` TEXT, `isFavorite` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("INSERT INTO tv_shows_temp SELECT * FROM tv_shows")
                db.execSQL("DROP TABLE tv_shows")
                db.execSQL("ALTER TABLE tv_shows_temp RENAME TO tv_shows")
            }
        }

        private val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tv_shows ADD COLUMN isWatching INTEGER DEFAULT 1 NOT NULL")
            }
        }

        private val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE episodes ADD COLUMN overview TEXT")
            }
        }

        private val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create indexes for query optimization
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_tvShow_isWatched` ON `episodes` (`tvShow`, `isWatched`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_tvShow_lastEngagementTimeUtcMillis` ON `episodes` (`tvShow`, `lastEngagementTimeUtcMillis`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_season_number` ON `episodes` (`season`, `number`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_seasons_tvShow_number` ON `seasons` (`tvShow`, `number`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tv_shows_isWatching` ON `tv_shows` (`isWatching`)")
            }
        }

        private val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE movies ADD COLUMN favoritedAtMillis INTEGER")
                db.execSQL("ALTER TABLE tv_shows ADD COLUMN favoritedAtMillis INTEGER")
            }
        }

        private val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // No SQL changes needed as indices were already created in previous migrations 
                // but are now formally declared in Entity classes, requiring a version bump.
            }
        }
    }
}
