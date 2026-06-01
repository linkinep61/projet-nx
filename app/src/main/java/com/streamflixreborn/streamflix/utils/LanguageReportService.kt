package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.util.Log
import com.streamflixreborn.streamflix.StreamFlixApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Service communautaire de LANGUE (VF / VOSTFR / VO) — via Cloudflare Worker + D1.
 *
 * API :
 *   POST /lang/get    {contentKey}                → {votesVF, votesVOSTFR, votesVO}
 *   POST /lang/vote   {contentKey, deviceId, lang} → {votesVF, votesVOSTFR, votesVO}
 *   POST /lang/remove {contentKey, deviceId}       → {votesVF, votesVOSTFR, votesVO}
 *
 * Le vote individuel de CE device est caché localement en SharedPreferences
 * (le Worker ne renvoie pas le vote par device).
 *
 * contentKey = tmdbId quand dispo (cross-provider) → un seul vote partagé entre
 * Wiflix / Cloudstream / Movix pour le même film.
 *
 * Règle de verrouillage (validée user) :
 *  - figé si total >= 10 ET écart 1re/2e >= 5 → langue définitive ;
 *  - si serré à 10 (écart < 5) → on continue de voter (dépassement) ;
 *  - garde-fou : au-delà de SAFETY_CAP votes toujours serré → les deux versions
 *    existent vraiment → on fige sur « VF + VOSTFR » (les 2 têtes).
 *  - 1 vote / appareil ; priorité VF en cas d'égalité.
 */
object LanguageReportService {

    private const val TAG = "LanguageReport"
    private const val API_URL = "https://streamflix-api.logami61250.workers.dev"
    private const val PREFS_NAME = "community_lang_votes"
    private val JSON_MEDIA = "application/json".toMediaType()

    private const val LOCK_MIN_TOTAL = 10   // pas de verrou avant 10 votes
    private const val LOCK_MIN_GAP = 5      // écart 1re/2e requis pour figer
    private const val SAFETY_CAP = 20       // au-delà, serré = les 2 versions existent

    enum class Lang { VF, VOSTFR, VO }

    // ── Cache local du vote de CE device ────────────────────────────────

    private fun saveLocalLangVote(contentKey: String, deviceId: String, lang: String) {
        try {
            val ctx = StreamFlixApp.instance ?: return
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString("lang_${contentKey}_$deviceId", lang)
                .apply()
        } catch (_: Exception) {}
    }

    private fun removeLocalLangVote(contentKey: String, deviceId: String) {
        try {
            val ctx = StreamFlixApp.instance ?: return
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove("lang_${contentKey}_$deviceId")
                .apply()
        } catch (_: Exception) {}
    }

    private fun getLocalLangVote(contentKey: String, deviceId: String): String? {
        return try {
            val ctx = StreamFlixApp.instance ?: return null
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString("lang_${contentKey}_$deviceId", null)
        } catch (_: Exception) { null }
    }

    // ── Logique PURE (testable hors Android) ────────────────────────────

    /** Résultat de l'agrégation : libellé à afficher + si c'est figé. */
    data class Resolution(val label: String?, val locked: Boolean)

    private fun priority(lang: String): Int = when (lang.uppercase()) {
        "VF" -> 0
        "VOSTFR" -> 1
        else -> 2
    }

    /**
     * Calcule la langue affichée + l'état figé à partir des compteurs.
     * Pure : aucune dépendance réseau/Android → testée offline.
     */
    fun resolve(votesVF: Int, votesVOSTFR: Int, votesVO: Int): Resolution {
        val total = votesVF + votesVOSTFR + votesVO
        if (total == 0) return Resolution(null, false)

        val ranked = listOf("VF" to votesVF, "VOSTFR" to votesVOSTFR, "VO" to votesVO)
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<String, Int>> { it.second }
                    .thenBy { priority(it.first) } // égalité → VF d'abord
            )

        val leader = ranked.first()
        val second = ranked.getOrNull(1)
        val gap = leader.second - (second?.second ?: 0)

        // Deux versions co-existent vraiment (serré durablement) → on l'affiche.
        val bothLabel = ranked.take(2)
            .sortedBy { priority(it.first) }
            .joinToString(" + ") { it.first }

        return when {
            total >= LOCK_MIN_TOTAL && gap >= LOCK_MIN_GAP -> Resolution(leader.first, locked = true)
            total >= SAFETY_CAP && gap < LOCK_MIN_GAP -> Resolution(bothLabel, locked = true)
            else -> Resolution(leader.first, locked = false) // provisoire = leader du moment
        }
    }

    // ── Infos remontées à l'UI ──────────────────────────────────────────

    data class LanguageInfo(
        val votesVF: Int,
        val votesVOSTFR: Int,
        val votesVO: Int,
        val userVote: String?,   // "VF"/"VOSTFR"/"VO" ou null si ce device n'a pas voté
        val label: String?,      // langue affichée (provisoire ou figée)
        val locked: Boolean,     // true → on cache les cases pour TOUT LE MONDE
    ) {
        val total: Int get() = votesVF + votesVOSTFR + votesVO
        /** Faut-il montrer les cases de vote à CE device ? */
        val canVote: Boolean get() = !locked && userVote == null
    }

    // ── Helper : parse la réponse JSON compteurs ────────────────────────

    private fun parseVoteCounts(json: JSONObject): Triple<Int, Int, Int> {
        val vf = json.optInt("votesVF", 0)
        val vostfr = json.optInt("votesVOSTFR", 0)
        val vo = json.optInt("votesVO", 0)
        return Triple(vf, vostfr, vo)
    }

    // ── Soumettre un vote de langue (1 / appareil) ──────────────────────

    suspend fun submitVote(
        contentKey: String,
        deviceId: String,
        lang: Lang,
        title: String = "",
    ) {
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("contentKey", contentKey)
                    put("deviceId", deviceId)
                    put("lang", lang.name)
                }
                val request = Request.Builder()
                    .url("$API_URL/lang/vote")
                    .post(body.toString().toRequestBody(JSON_MEDIA))
                    .build()

                val response = NetworkClient.default.newCall(request).execute()
                val respBody = response.body?.string()
                response.close()

                if (response.isSuccessful && respBody != null) {
                    // Sauvegarder le vote localement
                    saveLocalLangVote(contentKey, deviceId, lang.name)
                    val json = JSONObject(respBody)
                    val (vf, vostfr, vo) = parseVoteCounts(json)
                    Log.d(TAG, "lang vote ${lang.name} for $contentKey → VF=$vf VOSTFR=$vostfr VO=$vo")
                } else {
                    Log.e(TAG, "submitVote failed for $contentKey: HTTP ${response.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "submitVote failed for $contentKey", e)
            }
        }
    }

    // ── Retirer le vote de CE device ────────────────────────────────────

    /** Supprime le vote de langue de cet appareil et recalcule les compteurs. */
    suspend fun removeVote(contentKey: String, deviceId: String) {
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("contentKey", contentKey)
                    put("deviceId", deviceId)
                }
                val request = Request.Builder()
                    .url("$API_URL/lang/remove")
                    .post(body.toString().toRequestBody(JSON_MEDIA))
                    .build()

                val response = NetworkClient.default.newCall(request).execute()
                val respBody = response.body?.string()
                response.close()

                if (response.isSuccessful && respBody != null) {
                    // Retirer le vote local
                    removeLocalLangVote(contentKey, deviceId)
                    val json = JSONObject(respBody)
                    val (vf, vostfr, vo) = parseVoteCounts(json)
                    Log.d(TAG, "lang vote removed for $contentKey → VF=$vf VOSTFR=$vostfr VO=$vo")
                } else {
                    Log.e(TAG, "removeVote failed for $contentKey: HTTP ${response.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "removeVote failed for $contentKey", e)
            }
        }
    }

    // ── Lire l'agrégat + le vote de CE device ───────────────────────────

    suspend fun getLanguage(contentKey: String, deviceId: String): LanguageInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("contentKey", contentKey)
                }
                val request = Request.Builder()
                    .url("$API_URL/lang/get")
                    .post(body.toString().toRequestBody(JSON_MEDIA))
                    .build()

                val response = NetworkClient.default.newCall(request).execute()
                val respBody = response.body?.string()
                response.close()

                if (!response.isSuccessful || respBody == null) return@withContext null

                val json = JSONObject(respBody)
                val (vf, vostfr, vo) = parseVoteCounts(json)

                // Le vote individuel est lu depuis le cache local
                val userVote = getLocalLangVote(contentKey, deviceId)

                val res = resolve(vf, vostfr, vo)
                LanguageInfo(
                    votesVF = vf,
                    votesVOSTFR = vostfr,
                    votesVO = vo,
                    userVote = userVote,
                    label = res.label,
                    locked = res.locked,
                )
            } catch (e: Exception) {
                Log.e(TAG, "getLanguage failed for $contentKey", e)
                null
            }
        }
    }
}
