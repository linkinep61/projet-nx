package com.streamflixreborn.streamflix.utils

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Log
import com.streamflixreborn.streamflix.StreamFlixApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Service de notes communautaires via Cloudflare Worker + D1.
 *
 * API :
 *   POST /rating/get   {contentKey}                      → {averageRating, totalVotes, title}
 *   POST /rating/vote  {contentKey, deviceId, rating, title} → {averageRating, totalVotes}
 *
 * Le vote individuel de CE device est caché localement en SharedPreferences
 * (le Worker ne renvoie pas le vote par device).
 *
 * contentKey = tmdbId quand dispo (cross-provider), sinon SHA-256(title|year).
 * deviceId  = SHA-256(ANDROID_ID) — anonyme, pas de compte.
 */
object RatingService {

    private const val TAG = "RatingService"
    private const val API_URL = "https://streamflix-api.logami61250.workers.dev"
    private const val PREFS_NAME = "community_rating_votes"
    private val JSON_MEDIA = "application/json".toMediaType()

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

    // ── Cache local du vote de CE device ────────────────────────────────

    private fun saveLocalVote(contentKey: String, deviceId: String, rating: Int) {
        try {
            val ctx = StreamFlixApp.instance ?: return
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt("vote_${contentKey}_$deviceId", rating)
                .apply()
        } catch (_: Exception) {}
    }

    private fun getLocalVote(contentKey: String, deviceId: String): Int? {
        return try {
            val ctx = StreamFlixApp.instance ?: return null
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val key = "vote_${contentKey}_$deviceId"
            if (prefs.contains(key)) prefs.getInt(key, 0) else null
        } catch (_: Exception) { null }
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
                val body = JSONObject().apply {
                    put("contentKey", contentKey)
                    put("deviceId", deviceId)
                    put("rating", rating)
                    put("title", title)
                }
                val request = Request.Builder()
                    .url("$API_URL/rating/vote")
                    .post(body.toString().toRequestBody(JSON_MEDIA))
                    .build()

                val response = NetworkClient.default.newCall(request).execute()
                val respBody = response.body?.string()
                response.close()

                if (response.isSuccessful && respBody != null) {
                    // Sauvegarder le vote localement
                    saveLocalVote(contentKey, deviceId, rating)
                    val json = JSONObject(respBody)
                    val avg = json.optDouble("averageRating", 0.0)
                    val total = json.optInt("totalVotes", 0)
                    Log.d(TAG, "Rating $rating submitted for $contentKey (avg=$avg, n=$total)")
                } else {
                    Log.e(TAG, "Failed to submit rating for $contentKey: HTTP ${response.code}")
                }
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
                val body = JSONObject().apply {
                    put("contentKey", contentKey)
                }
                val request = Request.Builder()
                    .url("$API_URL/rating/get")
                    .post(body.toString().toRequestBody(JSON_MEDIA))
                    .build()

                val response = NetworkClient.default.newCall(request).execute()
                val respBody = response.body?.string()
                response.close()

                if (!response.isSuccessful || respBody == null) return@withContext null

                val json = JSONObject(respBody)
                val avg = json.optDouble("averageRating", 0.0)
                val total = json.optInt("totalVotes", 0)

                if (total == 0 && avg == 0.0) return@withContext null

                // Le vote individuel est lu depuis le cache local
                val userRating = getLocalVote(contentKey, deviceId)

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
