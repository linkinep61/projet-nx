package com.streamflixreborn.streamflix.utils

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 2026-10-27 (user : « Auto Moto / Eurosport charge 10 secondes et coupe ») — mesuré sur la TV :
 *
 *   http://ultimateiptv.me:8080/<user>/<pass>/59
 *     → 302 → http://89.248.162.217:25461/play/mpegts/<JETON>   (flux TS)
 *
 * Le lecteur jouait l'adresse À JETON. Le serveur coupe la connexion au bout de ~10 s ; la
 * rouvrir avec le même jeton → HTTP 403 (jeton à usage unique / périmé). D'où « Transfer-
 * recovery » toutes les 10 s, puis 403 en boucle.
 *
 * Remède (celui de VLC / TiviMate) : à chaque coupure, redemander l'adresse D'ORIGINE, qui
 * redirige vers un JETON NEUF. Ce registre garde « adresse à jeton → adresse d'origine » ;
 * LiveReconnectingHttpDataSource s'en sert pour se reconnecter.
 */
object XtreamRelais {
    private const val TAG = "XtreamRelais"
    private val origines = ConcurrentHashMap<String, String>()

    private val client by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .dns(DnsResolver.doh) // certains FAI faussent ultimateiptv.me
            .build()
    }

    /** Retient que [resolue] (adresse à jeton) vient de [origine]. */
    fun noter(origine: String, resolue: String) {
        if (origine == resolue) return
        if (origines.size > 300) origines.clear()
        origines[resolue] = origine
        Log.d(TAG, "relais noté : ${origine.take(60)} → ${resolue.take(60)}")
    }

    fun origineDe(url: String): String? = origines[url]

    /** Demande un jeton neuf (Location de la redirection, sans ouvrir le flux). Null = échec. */
    fun nouveauJeton(origine: String, ua: String?): String? = runCatching {
        val req = Request.Builder().url(origine)
            .header("User-Agent", ua ?: "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
            .header("Accept", "*/*")
            .build()
        client.newCall(req).execute().use { r ->
            if (r.code in 300..399) {
                r.header("Location")?.let { loc -> r.request.url.resolve(loc)?.toString() }
            } else null
        }
    }.onFailure { Log.w(TAG, "jeton neuf KO : ${it.message}") }.getOrNull()
        ?.also { neuf -> noter(origine, neuf) }
}
