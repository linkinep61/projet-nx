package com.streamflixreborn.streamflix.sport.providers

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Chaînes TV en continu de damitv (« Télévision en direct », onglet France).
 *
 * Vérifié le 25/10/2026 (Chrome du user + PC) :
 *  • liste : `damitv.st/data/tv.json` → `{channels:[{id,name,image,country}]}` (874 chaînes) ;
 *  • jeton : `damitv.st/papi/hls-token` → `{tk, e}` ;
 *  • playlist : `damitv.st/dl/<id>.m3u8?tk=<tk>&e=<e>` — 403 sans le Referer du lecteur
 *    (`damitv.st/player/hls/`), 404 pour une chaîne éteinte ;
 *  • segments sur tiktokcdn, déguisés en PNG (image puis TS) : lus grâce à SauteImageDataSource.
 */
object DamiTvChaines {

    private const val TAG = "DamiTvChaines"
    const val PROPRIO = "damitvtv"
    const val PREFIX = "damitvtv::"
    private const val BASE = "https://damitv.st"
    const val REFERER = "$BASE/player/hls/"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    private const val TTL_MS = 15 * 60_000L

    private val ETRANGERE = Regex("\\s(SW|SE|UK|US|USA|DE|IT|ES|PL|NL|PT|RO|AR)$", RegexOption.IGNORE_CASE)

    data class Chaine(val id: Int, val nom: String, val logo: String)

    private val client by lazy {
        NetworkClient.default.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
    }
    private val verrou = Mutex()
    @Volatile private var cache: Pair<Long, List<Chaine>>? = null

    /**
     * En-têtes d'une requête du lecteur (fetch/XHR). Mesuré le 25/10 : damitv répond 403 à la
     * playlist si la requête se présente comme une navigation (`Sec-Fetch-Mode: navigate`,
     * `Accept: text/html…`) — ce que l'intercepteur commun ajoute quand ces en-têtes manquent.
     */
    val ENTETES = mapOf(
        "User-Agent" to UA, "Referer" to REFERER, "Origin" to BASE,
        "Accept" to "*/*", "Accept-Language" to "fr-FR,fr;q=0.9",
        "Sec-Fetch-Site" to "same-origin", "Sec-Fetch-Mode" to "cors", "Sec-Fetch-Dest" to "empty",
    )

    private fun get(url: String): Pair<Int, String> {
        val req = okhttp3.Request.Builder().url(url)
            .apply { ENTETES.forEach { (k, v) -> header(k, v) } }.build()
        return client.newCall(req).execute().use { r -> r.code to (r.body?.string() ?: "") }
    }

    private fun jeton(): String {
        val (code, corps) = get("$BASE/papi/hls-token")
        if (code != 200) throw Exception("hls-token $code")
        val j = JSONObject(corps)
        return "tk=${j.getString("tk")}&e=${j.get("e")}"
    }

    fun urlFlux(id: Int, jeton: String) = "$BASE/dl/$id.m3u8?$jeton"

    /** Client des sondages : coupe VRAIMENT à 3 s (un withTimeout n'interrompt pas un appel bloquant). */
    private val clientSonde by lazy {
        NetworkClient.default.newBuilder().callTimeout(3, TimeUnit.SECONDS).build()
    }
    /** Numéros éteints au dernier sondage (404/403/410), et heure de ce sondage. */
    @Volatile private var eteintes: Set<Int> = emptySet()
    @Volatile private var sondeA = 0L
    private val sondeEnCours = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Chaînes France, SANS attendre de sondage (2026-10-25 : damitv répondait 502 au bout de 8 s,
     * l'ouverture du dossier Sport attendait 26 s). Rend la liste moins les chaînes trouvées
     * éteintes au dernier sondage ; un nouveau sondage part en arrière-plan toutes les 15 min.
     */
    suspend fun chaines(): List<Chaine> = withContext(Dispatchers.IO) {
        val liste = listeFrance()
        if (System.currentTimeMillis() - sondeA > TTL_MS && sondeEnCours.compareAndSet(false, true)) {
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                try { sonder(liste) } catch (e: Exception) { Log.w(TAG, "sondage KO : ${e.message}") }
                finally { sondeEnCours.set(false) }
            }
        }
        liste.filter { it.id !in eteintes }
    }

    private suspend fun sonder(liste: List<Chaine>) {
        val debut = System.currentTimeMillis()
        val jt = jeton()
        val porte = kotlinx.coroutines.sync.Semaphore(6)
        val codes = coroutineScope {
            liste.map { c ->
                async {
                    c.id to porte.withPermit {
                        runCatching {
                            val req = okhttp3.Request.Builder().url(urlFlux(c.id, jt))
                                .apply { ENTETES.forEach { (k, v) -> header(k, v) } }.build()
                            clientSonde.newCall(req).execute().use { it.code }
                        }.getOrDefault(-1)
                    }
                }
            }.awaitAll()
        }
        // Seule une réponse franche « n'existe pas » éteint une chaîne ; 502, délai… = on la garde.
        val mortes = codes.filter { it.second == 404 || it.second == 403 || it.second == 410 }.map { it.first }.toSet()
        eteintes = mortes
        sondeA = System.currentTimeMillis()
        Log.i(TAG, "sondage : ${liste.size - mortes.size}/${liste.size} chaînes gardées, " +
            "${codes.count { it.second == 200 }} en 200 (${System.currentTimeMillis() - debut} ms)")
    }

    /** Liste France de `data/tv.json` (gardée 6 h). */
    private suspend fun listeFrance(): List<Chaine> = verrou.withLock {
        cache?.takeIf { System.currentTimeMillis() - it.first < 6 * 3600_000L }?.let { return@withLock it.second }
        run {
            val (code, corps) = get("$BASE/data/tv.json")
            if (code != 200) throw Exception("tv.json $code")
            val tab = JSONObject(corps).optJSONArray("channels") ?: throw Exception("tv.json sans channels")
            val france = (0 until tab.length()).mapNotNull { tab.optJSONObject(it) }
                .filter { it.optString("country").equals("fr", ignoreCase = true) }
                .map { o ->
                    val img = o.optString("image")
                    Chaine(
                        id = o.optInt("id"),
                        // « beIN SPORTS 1 France » → « beIN SPORTS 1 » ; « Sport en France » reste tel quel.
                        nom = o.optString("name").trim().let { n ->
                            n.replace(Regex("\\s+France$", RegexOption.IGNORE_CASE), "").trim()
                                .takeUnless { it.endsWith(" en", ignoreCase = true) || it.isBlank() } ?: n
                        },
                        logo = if (img.startsWith("http")) img else if (img.isNotBlank()) BASE + img else "",
                    )
                }.filter { it.id > 0 && it.nom.isNotBlank() }
                // « Eurosport 1 SW » est rangé en France chez eux mais n'est pas en français.
                .filterNot { ETRANGERE.containsMatchIn(it.nom) }
            Log.i(TAG, "${france.size} chaînes France dans tv.json")
            if (france.isNotEmpty()) cache = System.currentTimeMillis() to france
            france
        }
    }

    fun estServeur(server: Video.Server) = server.id.startsWith(PREFIX)

    fun serveur(id: String, nom: String, libelle: String = "DAMITV"): Video.Server =
        Video.Server(id = id, name = "$libelle · $nom", src = "$BASE/dl/${id.removePrefix(PREFIX)}.m3u8")

    /** Flux HLS (jeton frais) avec le Referer exigé par damitv. */
    suspend fun video(server: Video.Server): Video = withContext(Dispatchers.IO) {
        val id = server.id.removePrefix(PREFIX).substringBefore("::").toInt()
        val url = urlFlux(id, jeton())
        Log.i(TAG, "lecture ${server.name} → /dl/$id.m3u8")
        Video(
            source = url,
            headers = ENTETES,
            type = androidx.media3.common.MimeTypes.APPLICATION_M3U8,
        )
    }
}
