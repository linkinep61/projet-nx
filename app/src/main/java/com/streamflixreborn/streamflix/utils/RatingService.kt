package com.streamflixreborn.streamflix.utils

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Service de notes communautaires via Firebase Firestore.
 *
 * Structure Firestore :
 *   ratings/{contentKey}                → { averageRating, totalVotes }
 *   ratings/{contentKey}/votes/{deviceId} → { rating, timestamp }
 *
 * contentKey = tmdbId quand dispo (cross-provider), sinon SHA-256(title|year).
 * deviceId  = SHA-256(ANDROID_ID) — anonyme, pas de compte.
 */
object RatingService {

    private const val TAG = "RatingService"
    private const val COLLECTION_RATINGS = "ratings"
    private const val SUBCOLLECTION_VOTES = "votes"

    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    // ── Device ID (anonyme, stable par device) ──────────────────────────

    @SuppressLint("HardwareIds")
    fun getDeviceId(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown"
        return sha256(androidId)
    }

    // ── Content Key : identifiant universel du contenu ──────────────────

    /**
     * Cache mémoire titre+année → tmdbId résolu.
     * Évite de refaire un lookup TMDB à chaque ouverture de fiche.
     */
    private val tmdbResolveCache = ConcurrentHashMap<String, String>()

    /**
     * Génère la clé de contenu.
     * - Si tmdbId est non-null et non-vide → on l'utilise tel quel (ex: "12345")
     * - Sinon fallback sur hash(title|year) pour les providers sans TMDB
     *   (aplouf, Frembed, etc.)
     */
    fun contentKey(tmdbId: String?, title: String, year: String? = null): String {
        if (!tmdbId.isNullOrBlank()) return tmdbId.trim()
        // Vérifier le cache de résolution TMDB
        val cacheKey = "${title.trim().lowercase()}|${year.orEmpty().trim()}"
        tmdbResolveCache[cacheKey]?.let { return it }
        // Pas encore résolu → hash en attendant
        return "hash_${sha256(cacheKey).take(16)}"
    }

    /**
     * Résout le tmdbId via recherche TMDB pour les providers sans ID natif.
     * Appeler en suspend depuis le bind. Met en cache le résultat.
     * @return le tmdbId résolu ou null si introuvable
     */
    suspend fun resolveTmdbId(title: String, year: String? = null, isTvShow: Boolean = false): String? {
        val cacheKey = "${title.trim().lowercase()}|${year.orEmpty().trim()}"
        tmdbResolveCache[cacheKey]?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                val results = TMDb3.Search.multi(
                    query = title,
                    language = "fr-FR",
                )
                // Chercher le meilleur match : même type + année si dispo
                val match = results.results.firstOrNull { item ->
                    when {
                        !isTvShow && item is TMDb3.Movie -> {
                            if (year.isNullOrBlank()) true
                            else item.releaseDate?.startsWith(year) == true
                        }
                        isTvShow && item is TMDb3.Tv -> {
                            if (year.isNullOrBlank()) true
                            else item.firstAirDate?.startsWith(year) == true
                        }
                        else -> false
                    }
                } ?: results.results.firstOrNull { item ->
                    // Fallback : premier résultat movie ou tv sans filtre année
                    item is TMDb3.Movie || item is TMDb3.Tv
                }

                val resolvedId = when (match) {
                    is TMDb3.Movie -> match.id.toString()
                    is TMDb3.Tv -> match.id.toString()
                    else -> null
                }

                if (resolvedId != null) {
                    tmdbResolveCache[cacheKey] = resolvedId
                    Log.d(TAG, "Resolved TMDB for '$title' ($year) → $resolvedId")
                }
                resolvedId
            } catch (e: Exception) {
                Log.w(TAG, "TMDB resolve failed for '$title': ${e.message}")
                null
            }
        }
    }

    // ── Soumettre / mettre à jour une note ──────────────────────────────

    /**
     * Soumet (ou met à jour) la note d'un utilisateur.
     * @param contentKey  clé du contenu (tmdbId ou hash)
     * @param deviceId    SHA-256 de l'ANDROID_ID
     * @param rating      note 1..5 (étoiles)
     * @param title       titre du contenu (pour affichage console)
     */
    suspend fun submitRating(
        contentKey: String,
        deviceId: String,
        rating: Int,
        title: String = "",
    ) {
        require(rating in 1..5) { "Rating must be 1..5" }
        withContext(Dispatchers.IO) {
            try {
                val ratingDoc = db.collection(COLLECTION_RATINGS).document(contentKey)
                val voteDoc = ratingDoc.collection(SUBCOLLECTION_VOTES).document(deviceId)

                // 1) Écrire / écraser le vote individuel
                voteDoc.set(
                    mapOf(
                        "rating" to rating,
                        "timestamp" to FieldValue.serverTimestamp(),
                    )
                ).await()

                // 2) Recalculer la moyenne depuis tous les votes
                val votes = ratingDoc.collection(SUBCOLLECTION_VOTES).get().await()
                var sum = 0L
                var count = 0
                for (doc in votes.documents) {
                    val r = doc.getLong("rating")
                    if (r != null && r in 1..5) {
                        sum += r
                        count++
                    }
                }

                // 3) Mettre à jour le document parent (cache agrégé)
                if (count > 0) {
                    val avg = sum.toDouble() / count
                    ratingDoc.set(
                        mapOf(
                            "averageRating" to Math.round(avg * 10) / 10.0, // 1 décimale
                            "totalVotes" to count,
                            "title" to title,
                        ),
                        SetOptions.merge()
                    ).await()
                }

                Log.d(TAG, "Rating $rating submitted for $contentKey (avg=${if (count > 0) sum.toDouble() / count else 0}, n=$count)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to submit rating for $contentKey", e)
            }
        }
    }

    // ── Lire la note agrégée ────────────────────────────────────────────

    data class RatingInfo(
        val averageRating: Double,
        val totalVotes: Int,
        val userRating: Int?,  // note de CE device, null si pas voté
    )

    /**
     * Lit la note agrégée + la note de l'utilisateur courant.
     * Retourne null si aucune note n'existe pour ce contenu.
     */
    suspend fun getRating(contentKey: String, deviceId: String): RatingInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val ratingDoc = db.collection(COLLECTION_RATINGS)
                    .document(contentKey)
                    .get()
                    .await()

                if (!ratingDoc.exists()) return@withContext null

                val avg = ratingDoc.getDouble("averageRating") ?: 0.0
                val total = ratingDoc.getLong("totalVotes")?.toInt() ?: 0

                // Lire le vote individuel de ce device
                val voteDoc = db.collection(COLLECTION_RATINGS)
                    .document(contentKey)
                    .collection(SUBCOLLECTION_VOTES)
                    .document(deviceId)
                    .get()
                    .await()

                val userRating = if (voteDoc.exists()) {
                    voteDoc.getLong("rating")?.toInt()
                } else null

                RatingInfo(
                    averageRating = avg,
                    totalVotes = total,
                    userRating = userRating,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get rating for $contentKey", e)
                null
            }
        }
    }

    // ── Utilitaires ─────────────────────────────────────────────────────

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
