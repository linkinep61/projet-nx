package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 2026-08-11 — INTERRUPTEUR GÉNÉRAL À DISTANCE.
 *
 * L'app demande son autorisation de démarrer au Worker Cloudflare (table D1
 * `app_etat`, une seule ligne). Le pilotage se fait depuis la page /admin du
 * Worker : un bouton COUPER / RÉACTIVER.
 *
 * ── RÈGLE DE SÛRETÉ, à ne jamais assouplir ─────────────────────────────
 * On ne bloque QUE sur un `actif:false` explicite et correctement lu.
 * Réseau coupé, DNS HS, Cloudflare en panne, JSON inattendu, HTTP 500 →
 * l'app DÉMARRE. Bloquer sur une panne de notre côté serait irréparable :
 * une fois que l'app refuse de s'ouvrir, plus aucun correctif ne passe.
 *
 * ── MÉMOIRE LOCALE ─────────────────────────────────────────────────────
 * Un refus reçu est mémorisé. Sans ça, couper l'app n'aurait aucun effet
 * sur un appareil hors ligne : il suffirait de passer en mode avion. Une
 * fois le refus connu, il tient même sans réseau, jusqu'à ce que le
 * serveur réponde de nouveau `actif:true`.
 */
object EtatApp {

    private const val TAG = "EtatApp"
    private const val URL_ETAT = "https://streamflix-api.logami61250.workers.dev/etat"

    private const val PREFS = "etat_app"
    private const val K_BLOQUEE = "bloquee"
    private const val K_MESSAGE = "message"
    private const val K_VU_LE = "vu_le"

    /** actif = l'app a le droit de démarrer. message = texte affiché si coupée. */
    data class Etat(val actif: Boolean, val message: String?)

    private val client by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Dernier état connu, lu instantanément en local. Aucun réseau, aucune
     * attente : c'est ce qui permet de bloquer dès la 1ʳᵉ image du splash
     * quand la coupure a déjà été reçue lors d'un lancement précédent.
     */
    fun etatMemorise(context: Context): Etat {
        return try {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            Etat(
                actif = !p.getBoolean(K_BLOQUEE, false),
                message = p.getString(K_MESSAGE, null),
            )
        } catch (e: Throwable) {
            Etat(actif = true, message = null)   // au moindre doute : on laisse passer
        }
    }

    /**
     * Interroge le serveur. BLOQUANT — à appeler hors du thread principal.
     * En cas d'échec, renvoie le dernier état connu (donc : actif, sauf si une
     * coupure avait déjà été reçue).
     */
    fun interroger(context: Context): Etat {
        return try {
            val requete = okhttp3.Request.Builder()
                .url(URL_ETAT)
                .header("Cache-Control", "no-cache")
                .get()
                .build()

            client.newCall(requete).execute().use { reponse ->
                if (!reponse.isSuccessful) {
                    Log.i(TAG, "HTTP ${reponse.code} → on garde le dernier état connu")
                    return etatMemorise(context)
                }
                val corps = reponse.body?.string()
                if (corps.isNullOrBlank()) return etatMemorise(context)

                val objet = JSONObject(corps)
                // Champ absent = réponse qu'on ne comprend pas → surtout ne pas bloquer.
                if (!objet.has("actif")) {
                    Log.w(TAG, "réponse sans champ 'actif' → ignorée")
                    return etatMemorise(context)
                }

                val actif = objet.optBoolean("actif", true)
                val message = objet.optString("message", "")
                    .takeIf { it.isNotBlank() && it != "null" }

                memoriser(context, actif, message)
                Log.i(TAG, "état serveur : actif=$actif" + (message?.let { " — $it" } ?: ""))
                Etat(actif, message)
            }
        } catch (e: Throwable) {
            Log.i(TAG, "injoignable (${e.javaClass.simpleName}) → dernier état connu")
            etatMemorise(context)
        }
    }

    private fun memoriser(context: Context, actif: Boolean, message: String?) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                putBoolean(K_BLOQUEE, !actif)
                if (message != null) putString(K_MESSAGE, message) else remove(K_MESSAGE)
                putLong(K_VU_LE, System.currentTimeMillis())
                apply()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "mémorisation impossible : ${e.message}")
        }
    }
}
