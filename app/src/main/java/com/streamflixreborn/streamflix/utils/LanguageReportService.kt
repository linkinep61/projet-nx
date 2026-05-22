package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Service communautaire de LANGUE (VF / VOSTFR / VO) — séparé de RatingService
 * (on NE touche PAS à leur fichier), mais on réutilise la MÊME collection
 * `ratings/{contentKey}` et leurs helpers publics `RatingService.contentKey(...)`
 * + `RatingService.getDeviceId(...)`.
 *
 * Structure Firestore (greffée sur le doc existant) :
 *   ratings/{contentKey}                    → { langVotesVF, langVotesVOSTFR, langVotesVO }
 *   ratings/{contentKey}/langs/{deviceId}   → { lang, timestamp }
 *
 * contentKey = tmdbId quand dispo (cross-provider) → un seul vote partagé entre
 * Wiflix / Cloudstream / Movix pour le même film.
 *
 * Règle de verrouillage (validée user) :
 *  - figé si total ≥ 10 ET écart 1ʳᵉ/2ᵉ ≥ 5 → langue définitive ;
 *  - si serré à 10 (écart < 5) → on continue de voter (dépassement) ;
 *  - garde-fou : au-delà de SAFETY_CAP votes toujours serré → les deux versions
 *    existent vraiment → on fige sur « VF + VOSTFR » (les 2 têtes).
 *  - 1 vote / appareil ; priorité VF en cas d'égalité.
 */
object LanguageReportService {

    private const val TAG = "LanguageReport"
    private const val COLLECTION_RATINGS = "ratings"
    private const val SUBCOLLECTION_LANGS = "langs"

    private const val LOCK_MIN_TOTAL = 10   // pas de verrou avant 10 votes
    private const val LOCK_MIN_GAP = 5      // écart 1ʳᵉ/2ᵉ requis pour figer
    private const val SAFETY_CAP = 20       // au-delà, serré = les 2 versions existent

    enum class Lang { VF, VOSTFR, VO }

    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

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

    // ── Soumettre un vote de langue (1 / appareil) ──────────────────────

    suspend fun submitVote(
        contentKey: String,
        deviceId: String,
        lang: Lang,
        title: String = "",
    ) {
        withContext(Dispatchers.IO) {
            try {
                val ratingDoc = db.collection(COLLECTION_RATINGS).document(contentKey)
                val langDoc = ratingDoc.collection(SUBCOLLECTION_LANGS).document(deviceId)

                // 1) écrire / écraser le vote individuel
                langDoc.set(
                    mapOf(
                        "lang" to lang.name,
                        "timestamp" to FieldValue.serverTimestamp(),
                    )
                ).await()

                // 2) recompter depuis tous les votes de langue
                val all = ratingDoc.collection(SUBCOLLECTION_LANGS).get().await()
                var vf = 0; var vostfr = 0; var vo = 0
                for (doc in all.documents) {
                    when (doc.getString("lang")?.uppercase()) {
                        "VF" -> vf++
                        "VOSTFR" -> vostfr++
                        "VO" -> vo++
                    }
                }

                // 3) mettre à jour les compteurs agrégés sur le doc parent
                ratingDoc.set(
                    mapOf(
                        "langVotesVF" to vf,
                        "langVotesVOSTFR" to vostfr,
                        "langVotesVO" to vo,
                        "title" to title,
                    ),
                    SetOptions.merge()
                ).await()

                Log.d(TAG, "lang vote ${lang.name} for $contentKey → VF=$vf VOSTFR=$vostfr VO=$vo")
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
                val ratingDoc = db.collection(COLLECTION_RATINGS).document(contentKey)
                ratingDoc.collection(SUBCOLLECTION_LANGS).document(deviceId).delete().await()

                val all = ratingDoc.collection(SUBCOLLECTION_LANGS).get().await()
                var vf = 0; var vostfr = 0; var vo = 0
                for (doc in all.documents) {
                    when (doc.getString("lang")?.uppercase()) {
                        "VF" -> vf++
                        "VOSTFR" -> vostfr++
                        "VO" -> vo++
                    }
                }
                ratingDoc.set(
                    mapOf(
                        "langVotesVF" to vf,
                        "langVotesVOSTFR" to vostfr,
                        "langVotesVO" to vo,
                    ),
                    SetOptions.merge()
                ).await()

                Log.d(TAG, "lang vote removed for $contentKey → VF=$vf VOSTFR=$vostfr VO=$vo")
            } catch (e: Exception) {
                Log.e(TAG, "removeVote failed for $contentKey", e)
            }
        }
    }

    // ── Lire l'agrégat + le vote de CE device ───────────────────────────

    suspend fun getLanguage(contentKey: String, deviceId: String): LanguageInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val doc = db.collection(COLLECTION_RATINGS).document(contentKey).get().await()
                val vf = doc.getLong("langVotesVF")?.toInt() ?: 0
                val vostfr = doc.getLong("langVotesVOSTFR")?.toInt() ?: 0
                val vo = doc.getLong("langVotesVO")?.toInt() ?: 0

                val userVote = db.collection(COLLECTION_RATINGS)
                    .document(contentKey)
                    .collection(SUBCOLLECTION_LANGS)
                    .document(deviceId)
                    .get().await()
                    .getString("lang")

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
