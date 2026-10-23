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
            if (!lien.startsWith("http")) {
                Log.w(TAG, "$slug : aucun lien (type=${cfg["streamType"]})")
                return@withContext null
            }
            Log.d(TAG, "$slug : lien résolu (${lien.substringAfter("://").substringBefore('/')})")
            Video(
                source = lien,
                type = "application/vnd.apple.mpegurl",
                headers = mapOf("User-Agent" to UA, "Referer" to page, "Origin" to BASE),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "résolution échouée pour $src : ${t.javaClass.simpleName} ${t.message}")
            null
        }
    }
}
