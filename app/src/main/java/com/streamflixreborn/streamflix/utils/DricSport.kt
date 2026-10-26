package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/**
 * 2026-10-27 (user : « J'ai une source qui s'appelle Dric4rTV dans World Live — est-ce que les
 * chaînes de sport qu'il possède arrivent bien dans mon dossier Sport ? » → non → « ajoute ») :
 * chaînes SPORT de la liste Dric4rTV (http://dric4rt.free.fr/1.json), versées dans les bouquets
 * du dossier Sport (cf. SportHub.collecter, source « dric »).
 *
 * Depuis sa MAJ du 22/09/2026, Dric4rTV écrit les chaînes DIRECTEMENT dans chaque bouquet
 * (`groups[].stations[]`). Bouquets retenus (relevés dans Chrome le 2026-10-27) :
 *   BeinSport (10), RMC Sport (4), Divers SPORTS (13, sans AB1/AB3), Sports US/ES (10),
 *   CANAL+ (seulement Canal+ Sport — le reste = Canal+ / Ciné+).
 * Exclus : « Sports 3b0xTV » / « Canal+ 3b0xTV » = liens rebrand.ly / s.gy (pipeline html.bet
 *   anti-bot, déjà écarté dans Dric4rTvProvider).
 *
 * LECTURE : les serveurs portent le préfixe `livehub::fast::` → LiveTvHubProvider.getVideo,
 * branche FAST, qui sait déjà tout faire : pages de lecteur cartelive.club (extracteur Hoca8 +
 * familles Multi Live en cascade) et flux Xtream sans extension (ultimateiptv.me → MPEG-TS).
 */
object DricSport {
    private const val TAG = "DricSport"
    const val PROPRIO = "dric"
    const val PREFIX = "dric::"
    private const val URL_LISTE = "http://dric4rt.free.fr/1.json"
    private const val DUREE_CACHE_MS = 30 * 60 * 1000L

    data class Station(val nom: String, val url: String)
    data class Chaine(val id: String, val nom: String, val logo: String, val stations: List<Station>)

    private val BOUQUETS_SPORT = setOf("beinsport", "rmc sport", "divers sports", "sports us/es")
    private const val BOUQUET_CANAL = "canal+"
    private val HORS_SPORT = Regex("^ab\\s?\\d", RegexOption.IGNORE_CASE)
    private val QUALITE = Regex("\\s+(FULL\\s*HD|FHD|UHD|4K|HD|SD|HEVC)\\s*$", RegexOption.IGNORE_CASE)

    @Volatile private var cache: List<Chaine> = emptyList()
    @Volatile private var dateCache = 0L
    private val verrou = Mutex()

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .dns(DnsResolver.doh)
            .build()
    }

    suspend fun chaines(): List<Chaine> = verrou.withLock {
        if (cache.isNotEmpty() && System.currentTimeMillis() - dateCache < DUREE_CACHE_MS) return cache
        val neuves = runCatching { charger() }
            .onFailure { Log.w(TAG, "liste KO : ${it.message}") }.getOrNull().orEmpty()
        if (neuves.isNotEmpty()) { cache = neuves; dateCache = System.currentTimeMillis() }
        cache
    }

    suspend fun serveurs(id: String): List<Video.Server> {
        val ch = chaines().firstOrNull { it.id == id } ?: return emptyList()
        return ch.stations.mapIndexed { i, st ->
            Video.Server(id = "livehub::fast::$id::$i", name = st.nom, src = st.url)
        }
    }

    private suspend fun charger(): List<Chaine> = withContext(Dispatchers.IO) {
        val corps = client.newCall(
            Request.Builder().url(URL_LISTE)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                .build()
        ).execute().use { r -> if (r.isSuccessful) r.body?.string() else null } ?: return@withContext emptyList()
        val groupes = JSONObject(DricJson.repare(corps).trim()).optJSONArray("groups") ?: return@withContext emptyList()
        val parNom = LinkedHashMap<String, MutableList<Station>>()
        val logos = HashMap<String, String>()
        for (i in 0 until groupes.length()) {
            val g = groupes.optJSONObject(i) ?: continue
            val bouquet = g.optString("name").trim().lowercase()
            val canal = bouquet == BOUQUET_CANAL
            if (bouquet !in BOUQUETS_SPORT && !canal) continue
            val st = g.optJSONArray("stations") ?: continue
            for (j in 0 until st.length()) {
                val s = st.optJSONObject(j) ?: continue
                val nomBrut = s.optString("name").trim()
                val url = s.optString("url").trim()
                if (nomBrut.isEmpty() || !url.startsWith("http", ignoreCase = true)) continue
                if (HORS_SPORT.containsMatchIn(nomBrut)) continue
                if (canal && !SportHub.estSport(nomBrut)) continue
                val nom = nomBrut.replace(QUALITE, "").trim().ifEmpty { nomBrut }
                parNom.getOrPut(nom) { mutableListOf() }.add(Station(nomBrut, url))
                s.optString("image").takeIf { it.isNotBlank() }?.let { logos.putIfAbsent(nom, it) }
            }
        }
        val r = parNom.map { (nom, sts) ->
            Chaine(PREFIX + slug(nom), nom, logos[nom].orEmpty(), sts.distinctBy { it.url })
        }
        Log.i(TAG, "${r.size} chaînes sport Dric4rTV")
        r
    }

    private fun slug(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9+]+"), "-").trim('-')
}
