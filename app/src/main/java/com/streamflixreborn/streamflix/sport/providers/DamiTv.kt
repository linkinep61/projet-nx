package com.streamflixreborn.streamflix.sport.providers

import android.util.Log
import com.streamflixreborn.streamflix.sport.ProviderError
import com.streamflixreborn.streamflix.sport.SportEvent
import com.streamflixreborn.streamflix.sport.StreamSource
import com.streamflixreborn.streamflix.sport.TtlCache
import com.streamflixreborn.streamflix.sport.utils.DecodeEnc
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Source PRIMAIRE du module sport : damitv (brief § 3).
 *
 * Vérifié le 25/10/2026 dans le navigateur : `ondemand.st/papi/api/streams` → 200, JSON,
 * 122 événements / 11 sports ; `/embed/?id=…` et `/player/hls/?resolve=…` → 200, sans
 * X-Frame-Options ni CSP. Le site s'affiche sous damitv.st mais l'API et le lecteur vivent
 * sur ondemand.st : TOUS les chemins relatifs sont préfixés par [API_BASE].
 *
 * Contraintes de la source respectées : cache 60 s, délai 8 s, UNE seule nouvelle tentative,
 * pas de backoff ; lecture par la page d'embed (aucune résolution de m3u8 au MVP).
 */
object DamiTv {

    const val ID = "damitv"
    private const val TAG = "SportDamiTv"
    const val API_BASE = "https://ondemand.st"
    /** Miroir à essayer UNE fois si `/embed/` répond 404 (brief § 8). */
    private const val MIROIR = "https://damitv.st"

    private val cache = TtlCache<List<SportEvent>>(60_000L)

    private val client by lazy {
        NetworkClient.default.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
    }

    /** Événements normalisés (cache 60 s). Lève [ProviderError] si l'API est injoignable
     *  et qu'aucune liste n'a encore été chargée. */
    suspend fun getStreams(): List<SportEvent> = cache.get { charger() }

    /** Dernière liste connue, même périmée (pour rouvrir un serveur sans réseau). */
    fun derniereListe(): List<SportEvent> = cache.derniere() ?: emptyList()

    val chargements: Int get() = cache.chargements

    fun toEmbed(chemin: String?): String {
        val c = chemin.orEmpty().trim()
        return if (c.startsWith("http")) c else API_BASE + c
    }

    private suspend fun charger(): List<SportEvent> = withContext(Dispatchers.IO) {
        val corps = getUneRetry("$API_BASE/papi/api/streams")
        val racine = JSONObject(corps)
        if (!racine.optBoolean("success", true)) throw ProviderError(ID, 200, "success=false")
        val categories = racine.optJSONArray("streams") ?: throw ProviderError(ID, 200, "pas de « streams »")
        val out = ArrayList<SportEvent>()
        for (i in 0 until categories.length()) {
            val cat = categories.optJSONObject(i) ?: continue
            val nomCat = cat.optString("category")
            val liste = cat.optJSONArray("streams") ?: continue
            for (k in 0 until liste.length()) {
                val e = liste.optJSONObject(k) ?: continue
                runCatching { normaliser(e, nomCat) }.getOrNull()?.let { out += it }
            }
        }
        Log.i(TAG, "${out.size} événements (${out.count { it.enDirect }} en direct)")
        out
    }

    private fun normaliser(e: JSONObject, categorie: String): SportEvent? {
        val id = e.optString("id").ifBlank { return null }
        // Gardes du brief : `teams` peut valoir {} et `name` peut être vide.
        val equipes = e.optJSONObject("teams")
        val dom = equipes?.optJSONObject("home")?.optString("name").orEmpty()
        val ext = equipes?.optJSONObject("away")?.optString("name").orEmpty()
        val ligue = e.optString("league").takeUnless { it == "null" }.orEmpty()
        val titre = e.optString("name").trim()
            .ifBlank { if (dom.isNotBlank() && ext.isNotBlank()) "$dom vs $ext" else "" }
            .ifBlank { ligue }.ifBlank { return null }
        val sources = ArrayList<StreamSource>()
        val tab = e.optJSONArray("sources")
        if (tab != null) for (i in 0 until tab.length()) {
            val s = tab.optJSONObject(i) ?: continue
            val embed = s.optString("embed")
            if (embed.isBlank()) continue
            sources += StreamSource(
                name = s.optString("name").ifBlank { "Lecteur ${i + 1}" },
                embedUrl = toEmbed(embed),
                decodedUrl = decoderSiEnc(embed),
            )
        }
        val principal = e.optString("iframe").ifBlank { e.optString("embed") }
        if (sources.isEmpty() && principal.isNotBlank()) sources += StreamSource("HD", toEmbed(principal), decoderSiEnc(principal))
        if (sources.isEmpty()) return null
        // Les horodatages sont en SECONDES.
        val debut = e.optLong("starts_at", 0L).takeIf { it > 0 }?.times(1000)
        val fin = e.optLong("ends_at", 0L).takeIf { it > 0 }?.times(1000)
        return SportEvent(
            id = id, provider = ID, title = titre, league = ligue,
            category = e.optString("category_name").ifBlank { categorie },
            poster = e.optString("poster"),
            startsAt = debut, endsAt = fin,
            // `status` existe déjà : on l'utilise tel quel, sans le recalculer.
            status = e.optString("status").ifBlank { "upcoming" },
            viewers = e.optInt("viewers", 0),
            sources = sources,
            embedUrl = if (principal.isNotBlank()) toEmbed(principal) else sources.first().embedUrl,
        )
    }

    /** `…&url=enc%3A<b64>` → URL en clair (information). « /dl/undefined.m3u8 » est normal
     *  chez eux : on ne le « corrige » pas. Échec = null, on garde l'URL brute. */
    private fun decoderSiEnc(embed: String): String? {
        val p = Regex("[?&]url=(enc(?:%3A|:)[^&]+)", RegexOption.IGNORE_CASE).find(embed)?.groupValues?.get(1)
            ?: return null
        return DecodeEnc.decodeEnc(p)
    }

    private fun getUneRetry(url: String): String {
        var derniere: Exception? = null
        repeat(2) { essai ->
            try {
                val req = okhttp3.Request.Builder().url(url)
                    .header("Accept", "application/json").build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw ProviderError(ID, r.code)
                    return r.body?.string() ?: throw ProviderError(ID, r.code, "corps vide")
                }
            } catch (e: Exception) {
                derniere = e
                Log.w(TAG, "essai ${essai + 1}/2 KO : ${e.message}")
            }
        }
        throw derniere as? ProviderError ?: ProviderError(ID, -1, derniere?.message)
    }

    /**
     * Brief § 8 : `/embed/` en 404 → on réessaie UNE fois le même chemin sur le miroir, puis on
     * abandonne proprement (null). Les autres formes d'URL sont rendues sans vérification.
     */
    suspend fun embedJouable(url: String): String? = withContext(Dispatchers.IO) {
        if (!url.contains("/embed/")) return@withContext url
        fun code(u: String): Int = runCatching {
            client.newCall(okhttp3.Request.Builder().url(u).build()).execute().use { it.code }
        }.getOrDefault(-1)
        val c = code(url)
        if (c != 404) return@withContext url
        val miroir = if (url.startsWith(API_BASE)) url.replaceFirst(API_BASE, MIROIR) else url.replaceFirst(MIROIR, API_BASE)
        val c2 = code(miroir)
        Log.w(TAG, "embed 404 → miroir : $c2")
        if (c2 in 200..399) miroir else null
    }
}
