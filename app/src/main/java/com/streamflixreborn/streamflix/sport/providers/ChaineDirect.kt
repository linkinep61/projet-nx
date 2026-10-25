package com.streamflixreborn.streamflix.sport.providers

import android.util.Log
import com.streamflixreborn.streamflix.sport.SportEvent
import com.streamflixreborn.streamflix.sport.StreamSource
import com.streamflixreborn.streamflix.sport.TtlCache
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

/**
 * chainedirect.com (liste du user ; « tu prends tout, il y a plein de choses », son propre petit
 * dossier dans Sport).
 *
 * Vérifié le 25/10/2026 dans le Chrome du user : `/chaines-live/<rubrique>/` en HTML simple,
 * une carte par chaîne (`.ann-short_item` : `.ann-short_price` = nom, `img.poster` = logo,
 * `a[href$=.html]` = page). 163 chaînes : sport 52, cinéma 27, documentaire 22, enfants 22,
 * généraliste 19, info 13, musique 6, Belgique 1, Suisse 1. La page d'une chaîne charge
 * `/player/source2/?id=<n>` puis `wideiptv.top/daddy.php` (relais thedaddy : Canal+ Foot = 463,
 * comme chez damitv) → lue par la WebView cachée, flux capté puis lu par le lecteur ONYX.
 * Chaque chaîne est rendue comme un « événement » en direct (même chemin de lecture que damitv).
 */
object ChaineDirect {

    const val ID = "chainedirect"
    private const val TAG = "SportChaineDirect"
    private const val BASE = "https://chainedirect.com"

    /** Rubrique du site → nom de la rubrique dans le dossier ONYX. */
    val RUBRIQUES = linkedMapOf(
        "sport" to "⚽ Sport",
        "generaliste" to "📺 Généraliste",
        "info" to "📰 Info",
        "cinema" to "🎬 Cinéma",
        "documentaire" to "🌍 Documentaire",
        "enfants" to "🧸 Enfants",
        "musique" to "🎵 Musique",
        "belgique" to "🇧🇪 Belgique et Suisse",
        "suisse" to "🇧🇪 Belgique et Suisse",
    )

    private val cache = TtlCache<List<SportEvent>>(30 * 60_000L)
    private val client by lazy {
        NetworkClient.default.newBuilder().callTimeout(12, TimeUnit.SECONDS).build()
    }

    suspend fun getStreams(): List<SportEvent> = cache.get { charger() }
    fun derniereListe(): List<SportEvent> = cache.derniere() ?: emptyList()

    /** Rubrique ONYX d'une chaîne (portée dans [SportEvent.category]). */
    fun rubrique(ev: SportEvent): String = RUBRIQUES[ev.category] ?: "📺 Généraliste"

    private fun page(chemin: String): String {
        val req = okhttp3.Request.Builder().url(BASE + chemin).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw Exception("$chemin : HTTP ${r.code}")
            return r.body?.string().orEmpty()
        }
    }

    private suspend fun charger(): List<SportEvent> = withContext(Dispatchers.IO) {
        val debut = System.currentTimeMillis()
        val parRubrique = coroutineScope {
            RUBRIQUES.keys.map { r ->
                async {
                    r to runCatching { Jsoup.parse(page("/chaines-live/$r/"), BASE) }
                        .onFailure { Log.w(TAG, "$r KO : ${it.message}") }.getOrNull()
                }
            }.awaitAll()
        }
        val vus = HashSet<String>()
        val out = ArrayList<SportEvent>()
        for ((r, doc) in parRubrique) {
            doc ?: continue
            for (carte in doc.select(".ann-short_item")) {
                val lien = carte.selectFirst("a[href$=.html]")?.absUrl("href")?.ifBlank { null } ?: continue
                if (!vus.add(lien)) continue
                val nom = carte.selectFirst(".ann-short_price")?.text()?.trim()?.ifBlank { null } ?: continue
                val logo = carte.selectFirst("img")?.absUrl("src").orEmpty()
                val id = lien.substringAfterLast('/').substringBefore(".html")
                out += SportEvent(
                    id = id, provider = ID, title = nom, league = "", category = r, poster = logo,
                    startsAt = null, endsAt = null, status = "live", viewers = 0,
                    sources = listOf(StreamSource(name = "Direct", embedUrl = lien)),
                    embedUrl = lien, francais = true,
                )
            }
        }
        if (out.isEmpty()) throw Exception("aucune chaîne lue")
        Log.i(TAG, "${out.size} chaînes (${System.currentTimeMillis() - debut} ms) : " +
            out.groupBy { it.category }.entries.joinToString { "${it.key}=${it.value.size}" })
        out
    }
}
