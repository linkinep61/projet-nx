package com.streamflixreborn.streamflix.utils

import android.util.Base64
import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 2026-10-23 (user : « les réparer », chaînes Zone 18@ en 403) : les liens `stream.xlivetv.com`
 *   que donne adult-tv-channels.click ne sont valables QUE pour l'adresse IP qui les a demandés.
 *   Générés par le serveur GitHub, ils étaient refusés pour tout le monde. La playlist contient
 *   donc `adulttv://<chemin>` et l'appli demande le vrai lien au moment de la lecture, depuis
 *   l'IP de l'utilisateur (même logique que _flux_adulttv dans scripts/eporner_m3u.py).
 */
object AdultTvResolver {
    private const val TAG = "AdultTvResolver"
    private const val SCHEME = "adulttv://"
    private const val BASE = "https://adult-tv-channels.click"
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val RE_CFG = Regex("""window\.playerConfig\s*=\s*\{(.*?)\};""", RegexOption.DOT_MATCHES_ALL)
    private val RE_CHAMP = Regex("""(\w+)\s*:\s*"([^"]*)"""")

    fun isAdultTvUrl(src: String): Boolean = src.startsWith(SCHEME)

    // 2026-10-25 (user : « les liens expirent et ne sont pas redemandés ») : le lien xlivetv est
    //   à durée limitée. Quand la lecture tombe en erreur, le lecteur réessayait le MÊME lien
    //   périmé puis abandonnait → écran noir. On redemande désormais un lien neuf au site et on
    //   relance, avec un garde-fou : au plus 1 redemande toutes les 10 s, 6 par chaîne et par
    //   quart d'heure (sinon une chaîne vraiment HS ferait tourner le site en boucle).
    private val redemandes = java.util.concurrent.ConcurrentHashMap<String, ArrayDeque<Long>>()

    /** true si on a le droit de redemander un lien neuf pour cette chaîne maintenant. */
    fun peutRedemander(src: String): Boolean {
        val now = System.currentTimeMillis()
        val file = redemandes.getOrPut(src) { ArrayDeque() }
        synchronized(file) {
            while (file.isNotEmpty() && now - file.first() > 15 * 60_000L) file.removeFirst()
            if (file.isNotEmpty() && now - file.last() < 10_000L) return false
            if (file.size >= 6) return false
            file.addLast(now)
            return true
        }
    }

    /** Playlist HLS sans aucun segment ni variante (chaîne qui ne diffuse rien). En cas de
     *  doute (réseau, code d'erreur) → false : on tente la chaîne normalement. */
    private fun playlistVide(client: okhttp3.OkHttpClient, url: String, referer: String): Boolean = try {
        client.newCall(
            Request.Builder().url(url).header("User-Agent", UA).header("Referer", referer).build()
        ).execute().use { r ->
            if (!r.isSuccessful) return@use false
            val corps = r.body?.string().orEmpty()
            corps.contains("#EXTM3U") && !corps.contains("#EXTINF") &&
                corps.lineSequence().none { it.isNotBlank() && !it.trimStart().startsWith("#") }
        }
    } catch (_: Exception) { false }

    suspend fun resolve(src: String): Video? = withContext(Dispatchers.IO) {
        try {
            val slug = src.removePrefix(SCHEME).trim('/')
            val page = "$BASE/$slug/"
            val client = NetworkClient.default
            val html = client.newCall(
                Request.Builder().url(page).header("User-Agent", UA).build()
            ).execute().use { it.body?.string().orEmpty() }

            val cfg = RE_CFG.find(html)?.groupValues?.get(1)?.let { bloc ->
                RE_CHAMP.findAll(bloc).associate { it.groupValues[1] to it.groupValues[2] }
            } ?: run {
                Log.w(TAG, "$slug : pas de playerConfig")
                return@withContext null
            }

            val lien = when (cfg["streamType"]) {
                "api" -> {
                    val api = cfg["apiUrl"]?.trimEnd('/') ?: return@withContext null
                    val ep = cfg["endpoint"] ?: return@withContext null
                    val rep = client.newCall(
                        Request.Builder().url("$api/$ep")
                            .post(ByteArray(0).toRequestBody(null))
                            .header("User-Agent", UA)
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("Accept", "application/json, text/plain, */*")
                            .header("Referer", page)
                            .header("Origin", BASE)
                            .build()
                    ).execute().use { it.body?.string().orEmpty() }
                    val code = JSONObject(rep).optString("fileUrl")
                    if (code.isEmpty()) "" else
                        String(Base64.decode(code, Base64.DEFAULT), Charsets.UTF_8).reversed()
                }
                "direct" -> cfg["sourceUrl"].orEmpty()
                else -> ""
            }
            // 2026-10-24 (user : « on devrait prendre la pub quand elle est présente, comme
            //   fait leur site ») : quand la chaîne ne diffuse rien, son flux xlivetv rend une
            //   playlist VIDE (~80 octets, aucun segment) et leur lecteur bascule sur
            //   `playerConfig.fallbackUrl` (flux de remplacement). On fait pareil.
            val secours = cfg["fallbackUrl"].orEmpty().takeIf { it.startsWith("http") }
            var choisi = lien
            if (lien.startsWith("http") && secours != null && playlistVide(client, lien, page)) {
                Log.w(TAG, "$slug : flux de la chaîne vide → flux de remplacement du site")
                choisi = secours
            } else if (!lien.startsWith("http") && secours != null) {
                choisi = secours
            }
            if (!choisi.startsWith("http")) {
                Log.w(TAG, "$slug : aucun lien (type=${cfg["streamType"]})")
                return@withContext null
            }
            Log.d(TAG, "$slug : lien résolu (${choisi.substringAfter("://").substringBefore('/')})")
            Video(
                source = choisi,
                type = "application/vnd.apple.mpegurl",
                headers = mapOf("User-Agent" to UA, "Referer" to page, "Origin" to BASE),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "résolution échouée pour $src : ${t.javaClass.simpleName} ${t.message}")
            null
        }
    }
}
