package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.util.Log
import com.streamflixreborn.streamflix.StreamFlixApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 2026-10-31 — NOTE + LANGUE D'UNE FICHE EN UN SEUL APPEL, GARDÉES 24 H SUR L'APPAREIL.
 *
 * Mesuré en direct sur l'API : `/rating/get` (36 %) et `/lang/get` (35 %) partaient
 * côte à côte à chaque ouverture de fiche, et leur cache n'était qu'en mémoire (vidé à
 * chaque fermeture de l'appli). Ici :
 *  - un seul appel `/fiche/get` renvoie les deux ;
 *  - le résultat est gardé 24 h dans les préférences (survit à la fermeture) ;
 *  - note et langue demandées en même temps → un seul appel (verrou par fiche) ;
 *  - un échec (réseau, API coupée) n'est PAS gardé : on réessaiera plus tard.
 * Un vote de l'utilisateur vide l'entrée ([invalider]) : il voit son choix tout de suite.
 */
object FicheCommunaute {

    private const val TAG = "FicheCommunaute"
    private const val API_URL = "https://streamflix-api.logami61250.workers.dev"
    private const val PREFS = "fiche_communaute"
    private const val DUREE_MS = 24 * 60 * 60 * 1000L
    private const val MAX_ENTREES = 3000
    private val JSON_MEDIA = "application/json".toMediaType()

    data class Compteurs(
        val moyenne: Double,
        val totalVotes: Int,
        val vf: Int,
        val vostfr: Int,
        val vo: Int,
    )

    private val memoire = ConcurrentHashMap<String, Pair<Long, Compteurs>>()
    private val verrous = ConcurrentHashMap<String, Mutex>()

    private fun prefs() = try {
        StreamFlixApp.instance?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    } catch (_: Throwable) { null }

    private fun versTexte(pose: Long, c: Compteurs) =
        "$pose|${c.moyenne}|${c.totalVotes}|${c.vf}|${c.vostfr}|${c.vo}"

    private fun depuisTexte(t: String?): Pair<Long, Compteurs>? = try {
        val p = t!!.split('|')
        p[0].toLong() to Compteurs(p[1].toDouble(), p[2].toInt(), p[3].toInt(), p[4].toInt(), p[5].toInt())
    } catch (_: Throwable) { null }

    private fun enCache(cle: String): Compteurs? {
        val maintenant = System.currentTimeMillis()
        val entree = memoire[cle] ?: depuisTexte(prefs()?.getString(cle, null))?.also { memoire[cle] = it }
        return entree?.takeIf { maintenant - it.first < DUREE_MS }?.second
    }

    private fun garder(cle: String, c: Compteurs) {
        val pose = System.currentTimeMillis()
        memoire[cle] = pose to c
        try {
            val p = prefs() ?: return
            val ed = p.edit()
            // Ménage : au-delà de MAX_ENTREES, on retire ce qui a expiré.
            if (p.all.size > MAX_ENTREES) {
                for ((k, v) in p.all) {
                    val e = depuisTexte(v as? String)
                    if (e == null || pose - e.first >= DUREE_MS) ed.remove(k)
                }
            }
            ed.putString(cle, versTexte(pose, c)).apply()
        } catch (_: Throwable) {}
    }

    /** Vide l'entrée d'une fiche (après un vote) pour forcer une relecture fraîche. */
    fun invalider(contentKey: String) {
        memoire.remove(contentKey)
        try { prefs()?.edit()?.remove(contentKey)?.apply() } catch (_: Throwable) {}
    }

    /** Compteurs de la fiche (note + langue). null = injoignable pour l'instant. */
    suspend fun lire(contentKey: String): Compteurs? {
        enCache(contentKey)?.let { return it }
        val verrou = verrous.getOrPut(contentKey) { Mutex() }
        return verrou.withLock {
            // L'autre lecture (note ou langue) l'a peut-être déjà ramenée pendant l'attente.
            enCache(contentKey)?.let { return@withLock it }
            withContext(Dispatchers.IO) {
                try {
                    val corps = JSONObject().put("contentKey", contentKey).toString()
                    val requete = Request.Builder()
                        .url("$API_URL/fiche/get")
                        .post(corps.toRequestBody(JSON_MEDIA))
                        .build()
                    NetworkClient.default.newCall(requete).execute().use { rep ->
                        val texte = rep.body?.string()
                        if (!rep.isSuccessful || texte == null) {
                            Log.i(TAG, "HTTP ${rep.code} pour $contentKey → rien gardé")
                            return@withContext null
                        }
                        val j = JSONObject(texte)
                        Compteurs(
                            moyenne = j.optDouble("averageRating", 0.0),
                            totalVotes = j.optInt("totalVotes", 0),
                            vf = j.optInt("votesVF", 0),
                            vostfr = j.optInt("votesVOSTFR", 0),
                            vo = j.optInt("votesVO", 0),
                        ).also {
                            garder(contentKey, it)
                            Log.d(TAG, "réseau $contentKey → $it")
                        }
                    }
                } catch (e: Exception) {
                    Log.i(TAG, "lecture impossible pour $contentKey : ${e.javaClass.simpleName}")
                    null
                }
            }
        }.also { verrous.remove(contentKey, verrou) }
    }
}
