package com.streamflixreborn.streamflix.providers

import android.util.Base64
import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/**
 * AdkamiProvider — backup NATIF par TITRE STRICT (2026-08-07).
 *
 * Site : https://hentai.adkami.com — animes VOSTFR. Catalogue ADULTE, ajouté à la demande
 *   explicite du user (07/08 : « même celui-là pour les adultes, il y a souvent des bons animes
 *   qui ont rien à voir »). Il vient de l'écosystème Aniyomi, pas de Cloudstream.
 *
 * ⚠ LEUR EXTENSION EST PÉRIMÉE SUR UN POINT. Elle lit `div#video div.video-iframe.video-block
 *   [data-url][data-name]`. Vérifié en direct le 07/08 : ces éléments n'existent plus. Le seul
 *   `.video-block` de la page ne porte AUCUN attribut, et les deux `[data-url]` restants sont des
 *   boutons de partage. L'URL chiffrée est désormais sur le **`data-src` de l'iframe**.
 *   → On lit donc `iframe[data-src]`, et on ignore complètement `data-url`.
 *
 * Chiffrement maison, relevé dans leur source puis rejoué avec succès dans le navigateur :
 *   data-src = "https://www.youtube.com/embed/" + base64( pour chaque octet : (175 XOR octet)
 *              moins le caractère courant de la clé, la clé bouclant sur sa longueur - 1 )
 *   clé = "ETEfazefzeaZa13MnZEe"
 *
 * ⚠ Le résultat est une URL en PROTOCOLE RELATIF (« //hote/chemin »), pas une URL complète —
 *   c'est ce qui m'a fait croire un instant que le déchiffrement échouait. On préfixe `https:`.
 *
 * ⚠ CORRESPONDANCE STRICTE (règle posée par le user le 07/08, après « on a déjà été pas mal
 *   embêtés avec des mauvais matchs ») : égalité du titre normalisé, jamais de préfixe.
 */
object AdkamiProvider {
    private const val TAG = "AdkamiProvider"
    private const val BASE = "https://hentai.adkami.com"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36"
    private const val PREFIXE_LEURRE = "https://www.youtube.com/embed/"
    private const val CLE = "ETEfazefzeaZa13MnZEe"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** « _Summer - OAV 1 vostfr » → on retire le numéro d'épisode et la langue pour comparer. */
    private val SUFFIXE = Regex(
        """\s*[-–]\s*(?:OAV|OVA|EPISODE|EP|SAISON|S)\s*\d+.*$|\s*\b(?:vostfr|vf|vo)\b\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val NUM = Regex("""(\d+)""")

    suspend fun fetchAdkamiBackup(
        title: String,
        episode: Int = 0,
        videoType: Video.Type? = null,
    ): List<Video.Server> = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext emptyList()

        val cibleEp = if (videoType is Video.Type.Episode) episode.coerceAtLeast(1) else 1
        val fiche = rechercher(title, cibleEp) ?: return@withContext emptyList()

        val html = get(fiche) ?: return@withContext emptyList()
        val serveurs = Jsoup.parse(html).select("iframe[data-src]").mapIndexedNotNull { i, f ->
            val brut = f.attr("data-src").trim()
            val url = dechiffrer(brut) ?: return@mapIndexedNotNull null
            val hote = runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull() ?: "lecteur"
            // Suffixe unique : deux lecteurs du même hébergeur ne doivent pas se déduplicquer
            //   l'un l'autre en aval (leçon Wiflix du 31/07).
            // ⚠ Pas de préfixe « Adkami » : BackupRegistry le pose déjà.
            Video.Server(id = "adkami_${cibleEp}_$i", name = hote, src = url)
        }

        Log.i(TAG, "'$title' ép.$cibleEp → ${serveurs.size} serveur(s)")
        serveurs
    }

    /**
     * Cherche la fiche de l'ÉPISODE demandé. Le site indexe chaque épisode comme une entrée
     * distincte (« Titre - OAV 1 vostfr »), d'où la double condition : titre strictement égal
     * une fois nettoyé, ET numéro d'épisode correspondant.
     */
    private fun rechercher(title: String, episode: Int): String? {
        val q = URLEncoder.encode(title, "UTF-8")
        val html = get("$BASE/video?search=$q") ?: return null
        val cible = normaliser(title)

        return Jsoup.parse(html).select("div.video-item-list").mapNotNull { item ->
            val href = item.selectFirst("a[href]")?.attr("href") ?: return@mapNotNull null
            val texte = item.text().trim().ifBlank { return@mapNotNull null }

            val num = NUM.find(texte)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val nom = normaliser(SUFFIXE.replace(texte, ""))

            // ⚠ ÉGALITÉ STRICTE — voir l'avertissement en tête de fichier.
            if (nom != cible || num != episode) return@mapNotNull null
            if (href.startsWith("http")) href else "$BASE/${href.removePrefix("/")}"
        }.firstOrNull()
    }

    /** base64 → XOR 175 → soustraction de la clé → URL protocole-relatif → https. */
    private fun dechiffrer(encode: String): String? = try {
        val part = if (encode.startsWith(PREFIXE_LEURRE)) encode.removePrefix(PREFIXE_LEURRE) else encode
        val octets = Base64.decode(part, Base64.DEFAULT)
        val sb = StringBuilder(octets.size)
        var k = 0
        for (o in octets) {
            sb.append(((175 xor (o.toInt() and 0xFF)) - CLE[k].code).toChar())
            k = if (k > CLE.length - 2) 0 else k + 1
        }
        val brut = sb.toString()
        when {
            brut.startsWith("//") -> "https:$brut"
            brut.startsWith("http") -> brut
            else -> { Log.w(TAG, "déchiffrement inattendu (ni // ni http)"); null }
        }
    } catch (e: Exception) {
        Log.e(TAG, "déchiffrement échoué : ${e.message}"); null
    }

    private fun normaliser(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private fun get(url: String): String? = try {
        val req = Request.Builder().url(url)
            .header("User-Agent", UA).header("Referer", "$BASE/").build()
        client.newCall(req).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else { Log.w(TAG, "GET → ${r.code}"); null }
        }
    } catch (e: Exception) { Log.e(TAG, "GET échoué : ${e.message}"); null }
}
