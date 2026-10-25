package com.streamflixreborn.streamflix.sport.providers

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.sport.ProviderError
import com.streamflixreborn.streamflix.sport.SportEvent
import com.streamflixreborn.streamflix.sport.StreamSource
import com.streamflixreborn.streamflix.sport.TtlCache
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * streamed.pk (liste du user, priorité 1 : « API JSON publique, aucune clé »).
 *
 * Vérifié le 25/10/2026 dans le Chrome du user :
 *  • `/api/matches/all-today` → [{id, title, category, date (ms), poster, popular,
 *    teams?, sources:[{source, id}]}] (70 matchs ce soir) ; `/api/matches/live` idem ;
 *  • `/api/stream/<source>/<id>` → [{streamNo, language, hd, embedUrl, viewers}] ;
 *    du français parfois (« French - Ligue 1+ », « French - DAZN ») ;
 *  • le lecteur `embed.st/embed/…` déchiffre son adresse (WebAssembly) puis lit
 *    `lb2.strmd.st/…/playlist.m3u8` → flux capté par la WebView cachée ; morceaux chez TikTok,
 *    mini-image WebP (42 o) devant le TS → SauteImageDataSource.
 */
object StreamedPk {

    const val ID = "streamed"
    private const val TAG = "SportStreamed"
    private const val BASE = "https://streamed.pk"

    private val cache = TtlCache<List<SportEvent>>(60_000L)
    private val client by lazy {
        NetworkClient.default.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
    }

    suspend fun getStreams(): List<SportEvent> = cache.get { charger() }
    fun derniereListe(): List<SportEvent> = cache.derniere() ?: emptyList()

    private fun get(url: String): String {
        val req = okhttp3.Request.Builder().url(url).header("Accept", "application/json")
            .header("Sec-Fetch-Mode", "cors").header("Sec-Fetch-Site", "same-origin")
            .header("Sec-Fetch-Dest", "empty").header("Referer", "$BASE/").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw ProviderError(ID, r.code)
            return r.body?.string() ?: throw ProviderError(ID, r.code, "corps vide")
        }
    }

    private suspend fun charger(): List<SportEvent> = withContext(Dispatchers.IO) {
        val (direct, jour) = coroutineScope {
            val a = async { runCatching { JSONArray(get("$BASE/api/matches/live")) }.getOrNull() }
            val b = async { runCatching { JSONArray(get("$BASE/api/matches/all-today")) }.getOrNull() }
            a.await() to b.await()
        }
        if (direct == null && jour == null) throw ProviderError(ID, -1, "API injoignable")
        val enDirect = HashSet<String>()
        direct?.let { for (i in 0 until it.length()) it.optJSONObject(i)?.optString("id")?.let(enDirect::add) }
        val vus = HashSet<String>()
        val out = ArrayList<SportEvent>()
        for (tab in listOfNotNull(direct, jour)) for (i in 0 until tab.length()) {
            val m = tab.optJSONObject(i) ?: continue
            val id = m.optString("id").ifBlank { continue }
            if (!vus.add(id)) continue
            runCatching { normaliser(m, id in enDirect) }.getOrNull()?.let { out += it }
        }
        // Langues : connues seulement par /api/stream (un appel par source). Demandées ici pour
        //   ranger les matchs sans français dans « Foot d'ailleurs » ; gardées 10 min par source.
        val porte = kotlinx.coroutines.sync.Semaphore(8)
        val avecLangue = coroutineScope {
            out.map { ev ->
                async {
                    val fr = ev.sources.any { s ->
                        langues[s.embedUrl]?.takeIf { System.currentTimeMillis() - it.first < 10 * 60_000L }?.second
                            ?: porte.withPermit {
                                runCatching { JSONArray(get(s.embedUrl)).toString().contains("\"French", ignoreCase = true) }
                                    .getOrNull()?.also { langues[s.embedUrl] = System.currentTimeMillis() to it } ?: false
                            }
                    }
                    if (fr) ev.copy(francais = true) else ev
                }
            }.awaitAll()
        }
        Log.i(TAG, "${avecLangue.size} matchs (${avecLangue.count { it.enDirect }} en direct, ${avecLangue.count { it.francais }} avec du français)")
        avecLangue
    }

    /** Source de flux → (horodatage, a du français). */
    private val langues = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Boolean>>()

    private fun normaliser(m: JSONObject, live: Boolean): SportEvent? {
        val titre = m.optString("title").trim().ifBlank { return null }
        val srcs = m.optJSONArray("sources") ?: return null
        val sources = (0 until srcs.length()).mapNotNull { srcs.optJSONObject(it) }.mapNotNull { s ->
            val src = s.optString("source"); val sid = s.optString("id")
            if (src.isBlank() || sid.isBlank()) null
            // Liste des flux demandée à la lecture seulement (un appel par source).
            else StreamSource(name = src, embedUrl = "$BASE/api/stream/$src/$sid")
        }
        if (sources.isEmpty()) return null
        val poster = m.optString("poster").let { if (it.startsWith("/")) BASE + it else it }
        val debut = m.optLong("date", 0L).takeIf { it > 0 }
        // Pas de champ « fini » : un match commencé il y a plus de 4 h n'est plus montré.
        if (!live && debut != null && System.currentTimeMillis() - debut > 4 * 3600_000L) return null
        return SportEvent(
            id = m.optString("id"), provider = ID, title = titre, league = "",
            category = m.optString("category"), poster = poster,
            startsAt = debut, endsAt = null,
            status = if (live) "live" else "upcoming", viewers = 0,
            sources = sources, embedUrl = sources.first().embedUrl,
        )
    }

    /** Serveurs d'un match : tous les flux de toutes ses sources, le français en premier. */
    suspend fun serveurs(ev: SportEvent): List<Video.Server> = withContext(Dispatchers.IO) {
        data class Flux(val langue: String, val hd: Boolean, val no: Int, val embed: String, val vues: Int)
        val tous = coroutineScope {
            ev.sources.map { s ->
                async {
                    runCatching {
                        val t = JSONArray(get(s.embedUrl))
                        (0 until t.length()).mapNotNull { t.optJSONObject(it) }.mapNotNull { x ->
                            val e = x.optString("embedUrl").ifBlank { return@mapNotNull null }
                            Flux(x.optString("language").ifBlank { "?" }, x.optBoolean("hd"),
                                x.optInt("streamNo"), e, x.optInt("viewers"))
                        }
                    }.onFailure { Log.w(TAG, "${ev.title} / ${s.name} KO : ${it.message}") }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }
        tous.distinctBy { it.embed }
            .sortedWith(compareBy<Flux>({ if (it.langue.contains("french", true)) 0 else 1 }, { if (it.hd) 0 else 1 }).thenByDescending { it.vues })
            .mapIndexed { i, f ->
                val langue = f.langue.replace(Regex("^French", RegexOption.IGNORE_CASE), "Français")
                    .replace(Regex("^English", RegexOption.IGNORE_CASE), "Anglais")
                    .replace(Regex("^Spanish", RegexOption.IGNORE_CASE), "Espagnol")
                    .replace(Regex("^Italian", RegexOption.IGNORE_CASE), "Italien")
                    .replace(Regex("^German", RegexOption.IGNORE_CASE), "Allemand")
                Video.Server(
                    id = "${SportProviders.PREFIXE_SERVEUR}$ID::ev::${ev.id}::$i",
                    name = "$langue${if (f.hd) " HD" else ""}",
                    src = f.embed,
                )
            }
    }
}
