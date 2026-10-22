package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 2026-10-22 (user : « un serveur où il y aurait beaucoup de dramas coréens, qu'on puisse
 * traduire nous-mêmes… ça remplacerait la casse et le manque qu'on a dans VoirDrama ») :
 * source de secours KissKH (kisskh.co) — LE catalogue de dramas asiatiques (coréens, chinois,
 * thaïs, japonais…). Vérifié dans le navigateur : « Meteor Garden » (2018) = 49 épisodes, avec
 * des sous-titres en fichiers séparés en 6 langues, DONT le français ; sinon l'anglais est
 * traduit par TraductionSousTitres.
 *   · Recherche / fiche / liste des épisodes : API ouverte (`/api/DramaList/Search`, `/Drama/{id}`).
 *   · Lecture : `/api/DramaList/Episode/{id}.png` et `/api/Sub/{id}` exigent un `kkey` calculé par
 *     le script (obfusqué) de la page → on fait lire la page d'épisode par une WebView invisible
 *     qui capte le flux, et le script lu après capture va chercher la liste des sous-titres.
 *   · Secours si la WebView échoue : le lecteur MegaPlay (`kisskh.megaplay.su/kisskh/{id}`, mêmes
 *     identifiants d'épisode, sous-titres anglais).
 */
object KissKh {

    private const val TAG = "KissKh"
    const val BASE = "https://kisskh.co"
    const val PREFIXE = "kisskh::"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    private val client by lazy {
        NetworkClient.default.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
    }

    private fun get(url: String): String? = client.newCall(
        Request.Builder().url(url).header("User-Agent", UA).header("Referer", "$BASE/").build()
    ).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }

    private fun norm(s: String) = s.lowercase()
        .replace(Regex("""\(\d{4}\)"""), " ")
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

    /** Serveurs KissKH pour un titre (titres connus TMDB/site), ou liste vide. */
    suspend fun serveurs(titres: Collection<String>, estUnFilm: Boolean, saison: Int, episode: Int): List<Video.Server> =
        withContext(Dispatchers.IO) {
            val s = saison.coerceAtLeast(1)
            // Saison > 1 : KissKH range chaque saison comme un drama à part (« X Season 2 »).
            val voulus = titres.filter { it.isNotBlank() }.flatMap { t ->
                if (estUnFilm || s == 1) listOf(norm(t)) else listOf(norm("$t Season $s"), norm("$t $s"))
            }.toSet()
            if (voulus.isEmpty()) return@withContext emptyList()
            var drama: JSONObject? = null
            for (t in titres.filter { it.isNotBlank() }.take(4)) {
                val rep = runCatching { get("$BASE/api/DramaList/Search?q=${java.net.URLEncoder.encode(t, "UTF-8")}&type=0") }.getOrNull() ?: continue
                val arr = runCatching { JSONArray(rep) }.getOrNull() ?: continue
                val trouve = (0 until arr.length()).map { arr.getJSONObject(it) }
                    .firstOrNull { norm(it.optString("title")) in voulus } ?: continue
                drama = trouve; break
            }
            val d = drama ?: return@withContext emptyList()
            val fiche = runCatching { get("$BASE/api/DramaList/Drama/${d.optInt("id")}?isq=false") }.getOrNull()
                ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@withContext emptyList()
            val type = fiche.optString("type")
            if (estUnFilm != type.equals("Movie", true) && !(estUnFilm && fiche.optInt("episodesCount") == 1)) {
                Log.d(TAG, "« ${fiche.optString("title")} » : type $type incompatible → ignoré")
                return@withContext emptyList()
            }
            val eps = fiche.optJSONArray("episodes") ?: return@withContext emptyList()
            val voulu = if (estUnFilm) 1 else episode
            val ep = (0 until eps.length()).map { eps.getJSONObject(it) }
                .firstOrNull { it.optDouble("number", -1.0).toInt() == voulu }
                ?: if (estUnFilm && eps.length() == 1) eps.getJSONObject(0) else null
            if (ep == null) {
                Log.d(TAG, "« ${fiche.optString("title")} » : pas d'épisode $voulu")
                return@withContext emptyList()
            }
            val idDrama = d.optInt("id"); val idEp = ep.optInt("id")
            Log.i(TAG, "« ${fiche.optString("title")} » ($idDrama) → épisode $voulu = $idEp (${ep.optInt("sub")} sous-titre(s))")
            listOf(Video.Server(
                id = "$PREFIXE$idDrama::$idEp",
                name = "[VOSTFR]",
                src = "$BASE/Drama/x/Episode-$voulu?id=$idDrama&ep=$idEp&page=0&pageSize=100",
            ))
        }

    // Lu dans la page APRÈS la capture du flux : la liste des sous-titres (JSON), récupérée
    //   par la page elle-même avec son `kkey`.
    private const val JS_SOUS_TITRES = """(function(){try{
        if(window.__kkSubs) return window.__kkSubs;
        if(!window.__kkDem){ var u=performance.getEntriesByType('resource').map(function(e){return e.name;})
            .filter(function(n){return n.indexOf('/api/Sub/')>=0;})[0];
          if(u){ window.__kkDem=1; fetch(u).then(function(r){return r.text();}).then(function(t){window.__kkSubs=t;}); } }
        return null; }catch(e){return null;}})()"""

    suspend fun video(server: Video.Server): Video {
        val idEp = server.id.substringAfterLast("::")
        val r = WebViewStreamResolver.resolve(
            entryUrl = server.src, referer = "$BASE/", timeoutMs = 25_000L,
            attach = true, clickPlay = true, lireJsApresCapture = JS_SOUS_TITRES,
        )
        if (r != null) {
            val sousTitres = runCatching { JSONArray(r.valeurJs ?: "[]") }.getOrNull()?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val o = a.optJSONObject(i) ?: return@mapNotNull null
                    val f = o.optString("src").takeIf { it.startsWith("http") } ?: return@mapNotNull null
                    val lg = o.optString("land").lowercase()
                    val label = when (lg) { "fr" -> "Français"; "en" -> "English"; else -> o.optString("label").ifBlank { lg } }
                    Video.Subtitle(label = label, file = f)
                }
            }.orEmpty().let { tous ->
                // Règle de l'appli : le français seul s'il existe ; sinon l'anglais (traduit
                //   automatiquement) ; sinon la 1re langue que le traducteur sait traiter.
                tous.filter { it.label == "Français" }.ifEmpty { tous.filter { it.label == "English" } }.ifEmpty {
                    tous.firstOrNull { TraductionSousTitres.traduisible(TraductionSousTitres.codeDepuisLibelle(it.label)) }?.let { listOf(it) }.orEmpty()
                }
            }
            Log.i(TAG, "épisode $idEp : flux capté, sous-titres ${sousTitres.joinToString { it.label }.ifBlank { "aucun" }}")
            return Video(
                source = r.url,
                subtitles = sousTitres,
                headers = r.headers + mapOf("Referer" to "$BASE/", "Origin" to BASE),
            )
        }
        Log.w(TAG, "épisode $idEp : WebView sans flux → lecteur MegaPlay")
        return com.streamflixreborn.streamflix.extractors.Extractor.extract("https://kisskh.megaplay.su/kisskh/$idEp", server)
    }
}
