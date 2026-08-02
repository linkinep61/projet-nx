package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/**
 * IAnimeProvider — backup NATIF par TITRE (2026-08-07).
 *
 * Site : https://www.ianimes.eu — animes VF et VOSTFR (Boruto VOSTFR : 293 épisodes).
 * Repéré via l'écosystème **Aniyomi** (`goddivor/anime-extensions-repo`), absent de Cloudstream.
 *
 * Chaîne vérifiée EN DIRECT dans le navigateur avant d'écrire (règle du projet) :
 *   POST /result.php          corps `s=<titre>`
 *       → fiches dans `table:has(td.card-title)`, lien `a[href*='liste.php?manga=']`,
 *         titre dans `td.card-title span[title]` préfixé « [Streaming] - »
 *   GET  /liste.php?manga=<slug>
 *       → épisodes : `a[href$=.htm]`, texte « Episode 001 »
 *   GET  <episode>.htm
 *       → `iframe[src]` : vidmoly.biz, voe.sx, streamtape.com, my.mail.ru
 *         (les quatre ont déjà leur extracteur chez nous)
 *
 * ⚠ CORRESPONDANCE STRICTE (demande explicite du user, 07/08 : « qu'on reste bien sur du strict
 *   niveau recherche pour pas avoir de mauvais matchs »). C'est indispensable ici : chercher
 *   « naruto » sur ce site ne remonte QUE des « Boruto: Naruto Next Generations ». Une comparaison
 *   par préfixe ou par inclusion collerait donc Boruto sur une fiche Naruto. On exige l'égalité du
 *   titre normalisé, une fois retiré le seul marqueur de langue « (VF) » / « (VOSTFR) ».
 *
 * ⚠ Le site sépare les versions : une fiche VF et une fiche VOSTFR portent le MÊME titre. On rend
 *   les serveurs des deux et on laisse le tri par langue de l'app faire son travail, comme ailleurs.
 */
object IAnimeProvider {
    private const val TAG = "IAnimeProvider"
    private const val BASE = "https://www.ianimes.eu"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** « BORUTO… (VOSTFR) » → on retire ce marqueur avant de comparer. */
    private val MARQUEUR_LANGUE = Regex("""\s*\((?:VF|VOSTFR|VOST|VO)\)\s*$""", RegexOption.IGNORE_CASE)
    private val NUM = Regex("""(\d+)""")

    suspend fun fetchIAnimeBackup(
        title: String,
        episode: Int = 0,
        videoType: Video.Type? = null,
    ): List<Video.Server> = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext emptyList()

        val fiches = rechercher(title)
        if (fiches.isEmpty()) return@withContext emptyList()

        val cible = if (videoType is Video.Type.Episode) episode.coerceAtLeast(1) else 1
        val serveurs = mutableListOf<Video.Server>()

        for ((slug, langue) in fiches) {
            val liste = get("$BASE/liste.php?manga=$slug") ?: continue
            val doc = Jsoup.parse(liste)

            val lien = doc.select("a[href]")
                .mapNotNull { a ->
                    val h = a.attr("href").takeIf { it.endsWith(".htm") } ?: return@mapNotNull null
                    val n = NUM.find(a.text())?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
                    n to h
                }
                .firstOrNull { it.first == cible }
                ?.second ?: continue

            val page = get(if (lien.startsWith("http")) lien else "$BASE/${lien.removePrefix("/")}") ?: continue
            Jsoup.parse(page).select("iframe[src]").forEachIndexed { i, f ->
                val src = f.attr("src").trim()
                if (src.startsWith("http")) {
                    val hote = runCatching { java.net.URI(src).host.removePrefix("www.") }.getOrNull() ?: "lecteur"
                    // Suffixe unique : deux lecteurs du même hébergeur ne doivent pas se
                    //   déduplicquer l'un l'autre en aval (leçon Wiflix du 31/07).
                    serveurs += Video.Server(
                        id = "ianime_${slug}_${cible}_$i",
                        // ⚠ Pas de préfixe « iAnime » : BackupRegistry le pose déjà.
                        name = "$hote ($langue)",
                        src = src,
                    )
                }
            }
        }

        Log.i(TAG, "'$title' ép.$cible → ${serveurs.size} serveur(s) sur ${fiches.size} fiche(s)")
        serveurs
    }

    /** Renvoie les fiches dont le titre est STRICTEMENT celui demandé (slug, langue). */
    private fun rechercher(title: String): List<Pair<String, String>> {
        val html = post("$BASE/result.php", FormBody.Builder().add("s", title).build())
            ?: return emptyList()
        val cible = normaliser(title)

        return Jsoup.parse(html).select("table:has(td.card-title)").mapNotNull { carte ->
            val href = carte.selectFirst("a[href*=liste.php?manga=]")?.attr("href")
                ?: return@mapNotNull null
            val span = carte.selectFirst("td.card-title span")
            val brut = (span?.attr("title")?.takeIf { it.isNotBlank() } ?: span?.text().orEmpty())
                .removePrefix("[Streaming] - ").trim()
            if (brut.isBlank()) return@mapNotNull null

            val langue = MARQUEUR_LANGUE.find(brut)?.value?.trim()?.trim('(', ')')?.uppercase() ?: "VO"
            val sansLangue = MARQUEUR_LANGUE.replace(brut, "")

            // ⚠ ÉGALITÉ STRICTE, pas de startsWith ni de contains — voir l'avertissement en tête.
            if (normaliser(sansLangue) != cible) return@mapNotNull null

            val slug = href.substringAfter("manga=").substringBefore('&')
            slug to langue
        }.distinctBy { it.first }
    }

    private fun normaliser(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private fun post(url: String, corps: FormBody): String? = try {
        val req = Request.Builder().url(url).post(corps)
            .header("User-Agent", UA).header("Referer", "$BASE/").build()
        client.newCall(req).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else { Log.w(TAG, "POST → ${r.code}"); null }
        }
    } catch (e: Exception) { Log.e(TAG, "POST échoué : ${e.message}"); null }

    private fun get(url: String): String? = try {
        val req = Request.Builder().url(url)
            .header("User-Agent", UA).header("Referer", "$BASE/").build()
        client.newCall(req).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else { Log.w(TAG, "GET → ${r.code}"); null }
        }
    } catch (e: Exception) { Log.e(TAG, "GET échoué : ${e.message}"); null }
}
