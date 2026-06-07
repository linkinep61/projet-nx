package com.streamflixreborn.streamflix.utils

import android.util.Base64
import android.util.Log
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 2026-05-31 : OTF TV — API chiffrée AES qui retourne des URLs m3u8 directes.
 * Extrait de WiTvProvider pour être utilisé dans TV Hub.
 *
 * API : POST https://app.otf-tv.com/otf/authV3.php
 * Réponse : JSON chiffré AES-128-CBC contenant des groupes de chaînes
 * avec des URLs directes sur stm.linkip.org.
 */
object OtfTvService {

    private const val TAG = "OtfTvService"
    private const val OTF_API_URL = "https://app.otf-tv.com/otf/authV3.php"
    private const val OTF_AES_KEY = "@z5wFi5vDgtF_vds"

    data class OtfChannel(
        val name: String,
        val normalizedKey: String,
        val catId: Int = 0, // CatID unique de l'API OTF
        val urls: List<String>, // m3u8 URLs (différentes qualités)
        val logo: String? = null, // URL du logo si dispo dans l'API
        val group: String = "", // Nom du groupe/catégorie (langue/pays)
    )

    // Cache en mémoire — valide 30 min
    private var cachedChannels: List<OtfChannel>? = null
    private var cacheTimestamp = 0L
    private const val CACHE_TTL = 30 * 60 * 1000L
    // 2026-06-04 (user "le TV hub galère à se charger et s'ouvrir" quand OTF est
    //   down) : cache négatif — si le fetch échoue ou timeout, on bloque les
    //   re-tentatives pendant 60s pour ne pas faire patienter à chaque
    //   navigation home.
    @Volatile private var lastFailureTimestamp = 0L
    private const val FAILURE_COOLDOWN_MS = 60 * 1000L

    /**
     * Récupère toutes les chaînes OTF TV avec leurs URLs m3u8.
     * Cache 30 min en mémoire + cooldown 60s sur échec.
     */
    suspend fun fetchChannels(): List<OtfChannel> {
        val cached = cachedChannels
        if (cached != null && System.currentTimeMillis() - cacheTimestamp < CACHE_TTL) {
            return cached
        }
        // Si fetch a fail récemment, return ce qu'on a (souvent emptyList) sans
        // refaire un appel réseau qui re-timeout 8s.
        if (System.currentTimeMillis() - lastFailureTimestamp < FAILURE_COOLDOWN_MS) {
            Log.d(TAG, "fetchChannels: skipped (last failure ${(System.currentTimeMillis() - lastFailureTimestamp) / 1000}s ago, cooldown ${FAILURE_COOLDOWN_MS / 1000}s)")
            return cached ?: emptyList()
        }

        return try {
            val channels = fetchFromApi()
            cachedChannels = channels
            cacheTimestamp = System.currentTimeMillis()
            lastFailureTimestamp = 0L
            Log.d(TAG, "Fetched ${channels.size} OTF channels")
            channels
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch OTF channels: ${e.message}")
            lastFailureTimestamp = System.currentTimeMillis()
            cached ?: emptyList()
        }
    }

    /**
     * Retourne les URLs m3u8 pour une chaîne par sa clé (catId ou normalizedKey).
     */
    suspend fun getUrlsForChannel(key: String): List<String> {
        Log.d(TAG, "getUrlsForChannel: looking for key='$key'")
        val channels = fetchChannels()
        Log.d(TAG, "getUrlsForChannel: ${channels.size} channels in cache")
        // D'abord chercher par catId (numérique), puis par normalizedKey
        val catIdInt = key.toIntOrNull()
        val match = if (catIdInt != null && catIdInt > 0) {
            channels.firstOrNull { it.catId == catIdInt }
        } else {
            channels.firstOrNull { it.normalizedKey == key }
        }
        if (match != null) {
            Log.d(TAG, "getUrlsForChannel: FOUND '${match.name}' (catId=${match.catId}) → ${match.urls.size} URLs: ${match.urls.firstOrNull()}")
        } else {
            Log.w(TAG, "getUrlsForChannel: NOT FOUND for key='$key'")
        }
        return match?.urls ?: emptyList()
    }

    private fun fetchFromApi(): List<OtfChannel> {
        val deviceId = "sf" + android.os.Build.FINGERPRINT.hashCode().toUInt().toString(16).padStart(8, '0') + "otf"
        val hash = otfEncrypt("5wF${deviceId}_Opd")

        val formBody = FormBody.Builder()
            .add("DeviceID", deviceId)
            .add("hash", hash)
            .build()
        val req = Request.Builder()
            .url(OTF_API_URL)
            .post(formBody)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            .build()

        val response = NetworkClient.default.newCall(req).execute()
        val body = response.body?.string()
        response.close()

        if (body.isNullOrBlank() || body.length < 50) {
            Log.w(TAG, "OTF TV: empty/short response (code=${response.code})")
            return emptyList()
        }
        if (body.contains("denied") || body.contains("error")) {
            Log.w(TAG, "OTF TV: API error: ${body.take(100)}")
            return emptyList()
        }

        val decrypted = otfDecrypt(body)
        Log.d(TAG, "OTF TV: decrypted ${decrypted.length} chars")

        val fixed = decrypted
            .replace(Regex(",\\s*\\]"), "]")
            .replace(Regex(",\\s*\\}"), "}")
        val json = JSONObject(fixed)
        val streams = json.optJSONArray("Streams") ?: return emptyList()

        val result = mutableListOf<OtfChannel>()
        for (g in 0 until streams.length()) {
            val group = streams.optJSONObject(g) ?: continue
            val groupName = group.optString("name", "").trim()
            val channels = group.optJSONArray("Channels") ?: continue
            for (c in 0 until channels.length()) {
                val ch = channels.optJSONObject(c) ?: continue
                val name = ch.optString("name", "").trim()
                if (name.isBlank()) continue

                val key = normalize(name)
                if (key.isEmpty()) continue

                val catId = ch.optInt("CatID", 0)

                // Logo : le champ s'appelle "thumbnail" dans l'API OTF
                val logo = ch.optString("thumbnail", "").trim().ifBlank {
                    ch.optString("logo", "").trim().ifBlank {
                        ch.optString("image", "").trim()
                    }
                }.ifBlank { null }

                val vq = ch.optJSONArray("vq")
                val urls = mutableListOf<String>()
                if (vq != null) {
                    for (q in 0 until vq.length()) {
                        val quality = vq.optJSONObject(q) ?: continue
                        val url = quality.optString("url", "").trim()
                        if (url.startsWith("http")) urls.add(url)
                    }
                }

                if (urls.isNotEmpty()) {
                    result.add(OtfChannel(name, key, catId, urls, logo, groupName))
                }
            }
        }

        Log.d(TAG, "OTF TV: parsed ${result.size} channels with URLs")
        return result
    }

    private fun otfEncrypt(plaintext: String): String {
        val keySpec = SecretKeySpec(OTF_AES_KEY.toByteArray(Charsets.UTF_8), "AES")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)
        val iv = cipher.iv
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = iv + ct
        return Base64.encodeToString(combined, Base64.DEFAULT)
    }

    private fun otfDecrypt(encrypted: String): String {
        val keySpec = SecretKeySpec(OTF_AES_KEY.toByteArray(Charsets.UTF_8), "AES")
        val raw = Base64.decode(encrypted, Base64.DEFAULT)
        val iv = raw.copyOfRange(0, 16)
        val ct = raw.copyOfRange(16, raw.size)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    /** Normalise un nom de chaîne : minuscules, accents → ASCII, sans espaces ni caractères spéciaux. */
    private fun normalize(name: String): String {
        // Décomposer les accents (NFD) puis supprimer les diacritiques
        val nfd = java.text.Normalizer.normalize(name.lowercase(), java.text.Normalizer.Form.NFD)
        return nfd.replace(Regex("\\p{InCombiningDiacriticalMarks}"), "")
            .replace(Regex("[^a-z0-9]"), "")
    }

    /**
     * 2026-06-04 (user "trier les chaînes OTF dans l'ordre des chaînes France") :
     * ordre TNT français standard pour les chaînes mainstream + bouquets Canal+
     * + sport + cinéma. Les chaînes hors de cette liste passent ensuite par ordre
     * alphabétique. À utiliser avec [sortChannelsFrenchTntOrder].
     */
    private val FRENCH_TNT_ORDER: List<String> = listOf(
        // Bouquet TNT clair (canaux 1-27)
        "tf1", "france2", "france3", "canal", "canalplus", "france5", "m6",
        "arte", "c8", "w9", "tmc", "tfx", "nrj12", "lcp", "publicsenat",
        "france4", "franceinfo", "cnews", "cstar", "gulli",
        "tf1seriesfilms", "tf1seriefilms", "lequipe", "6ter",
        "rmcstory", "rmcdecouverte", "cherie25",
        // 22-30 — chaînes locales/régionales
        "lci", "franceinfotv",
        // Bouquet Canal+ payantes
        "canalplus", "canalplussport", "canalplussport360", "canalplussportplus",
        "canalplusgrandsport", "canalplusfoot",
        "canalpluscinema", "canalplusgrandsclassiques", "canalplusgrandsecran",
        "canalplusbox", "canalplusinternational", "canalpluskids",
        "canalplusseries", "canalplusdocs", "canalplusofflive",
        // Sport
        "beinsports1", "beinsports2", "beinsports3", "beinsportsmax4",
        "beinsportsmax5", "beinsportsmax6", "beinsportsmax7", "beinsportsmax8",
        "rmcsport1", "rmcsport2", "rmcsport3", "rmcsport4",
        "eurosport1", "eurosport2",
        "ligue1plus", "infosport", "infosportplus",
        "dazn1", "dazn2",
        // Cinéma
        "ocsmax", "ocscity", "ocschoc", "ocsgeants",
        "tcmcinema", "tcm", "tcmclassic",
        "polar", "actionchasseetpeche",
        "paramountchannel", "paramount", "wbtv", "warnertv",
        // Découverte
        "natgeo", "natgeowild", "natgeohd",
        "discovery", "discoveryscience", "discoveryinvestigation",
        "histoire", "rmcstoryplus", "ushuaiatv", "voyage",
        "planeteplus", "planetenoplus", "trekplus",
        // Info / Politique
        "bfmtv", "bfmparis", "bfmlyon", "bfmbusiness",
        "francetv", "tv5monde",
        // Jeunesse
        "boomerang", "cartoonnetwork", "disneychannel", "disneyjunior",
        "nickelodeon", "nickjr", "tiji", "piwiplus", "gullimax",
        // Musique
        "mtv", "mtvhits", "mcm", "mcmtop", "mtvclub",
        "trace", "tracetropical", "traceurban",
        // Style de vie
        "13rue", "13erue",
    )

    /**
     * Trie une liste de chaînes par ordre TNT français (chaînes mainstream)
     * puis alphabétique. Insensible à la casse et aux accents/séparateurs grâce
     * au normalizedKey utilisé en clé. Utilisé par OtfPlayerActivity (mobile)
     * et OtfPlayerTvActivity (TV) pour le panel chaînes / boutons prev/next.
     */
    fun sortChannelsFrenchTntOrder(channels: List<OtfChannel>): List<OtfChannel> {
        return channels.sortedWith(
            compareBy<OtfChannel> { ch ->
                val idx = FRENCH_TNT_ORDER.indexOf(ch.normalizedKey)
                if (idx < 0) Int.MAX_VALUE else idx
            }.thenBy { it.name.lowercase() }
        )
    }

    /** Retourne le logo d'une chaîne par sa clé normalisée. */
    suspend fun getLogoForChannel(normalizedKey: String): String? {
        val channels = fetchChannels()
        return channels.firstOrNull { it.normalizedKey == normalizedKey }?.logo
    }

    /** Retourne la liste des groupes (langues/pays) disponibles. */
    suspend fun getGroups(): List<String> {
        val channels = fetchChannels()
        return channels.map { it.group }.distinct().filter { it.isNotBlank() }
    }

    /** Retourne les chaînes d'un groupe spécifique. */
    suspend fun getChannelsByGroup(groupName: String): List<OtfChannel> {
        val channels = fetchChannels()
        return channels.filter { it.group == groupName }
    }

    /** Groupe sélectionné par l'utilisateur (persiste en SharedPrefs). */
    var selectedGroup: String
        get() {
            val ctx = com.streamflixreborn.streamflix.StreamFlixApp.instance
            return ctx.getSharedPreferences("otf_prefs", android.content.Context.MODE_PRIVATE)
                .getString("selected_group", "") ?: ""
        }
        set(value) {
            val ctx = com.streamflixreborn.streamflix.StreamFlixApp.instance
            ctx.getSharedPreferences("otf_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putString("selected_group", value).apply()
            // PAS de clearCache() — le cache contient TOUTES les chaînes,
            // le filtrage par groupe se fait dans getHome(). Vider le cache
            // forcerait un re-fetch API de 5s → chargement infini.
        }

    fun clearCache() {
        cachedChannels = null
        cacheTimestamp = 0
    }
}
