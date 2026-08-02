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
 * VostfreeProvider — backup NATIF par TITRE (2026-08-07).
 *
 * Site : https://vostfree.ws — animes VF et VOSTFR, catalogue large (One Piece : 346 épisodes).
 * Repéré via l'écosystème **Aniyomi**, pas via Cloudstream : le dépôt `goddivor/anime-extensions-repo`
 *   publie 11 sources françaises, dont 4 absentes de chez nous (iAnime, JetAnime, Vostfree, HDS).
 *
 * Chaîne vérifiée EN DIRECT dans le navigateur avant d'écrire une ligne (règle du projet) :
 *   POST /index.php?do=search  (moteur DLE, comme Wiflix et FrenchStream)
 *       → « one piece » = 5 fiches
 *   GET  <fiche>.html
 *       → `select.new_player_selector option`  = la liste des épisodes (« Episode 0747 »)
 *       → `div#buttons_<n> div`                = les lecteurs de l'épisode n (id = `player_XXXX`)
 *       → `div#content_player_XXXX`            = l'IDENTIFIANT de la vidéo, PAS son URL
 *   Exemple relevé : content_player_1493 → « 3343198 » → video.sibnet.ru/shell.php?videoid=3343198
 *
 * ⚠ Le domaine redirige vers `ipv4.vostfree.ws`. On suit la redirection et on garde l'hôte final,
 *   sinon les requêtes suivantes repartent sur l'ancien et paient un aller-retour à chaque fois.
 *
 * ⚠ Numérotation ABSOLUE (épisode 747 d'One Piece, pas saison 15 épisode 12). On compare donc au
 *   numéro d'épisode brut, et on accepte « Film » pour les longs métrages.
 *
 * Lecture : rien de spécifique. Les URL reconstruites pointent vers des hébergeurs que nous
 *   savons déjà extraire (Sibnet, OK.ru, Uqload, Vidmoly, DoodStream, MixDrop, VOE).
 */
object VostfreeProvider {
    private const val TAG = "VostfreeProvider"
    private const val BASE = "https://vostfree.ws"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36"

    /** Hôte réellement servi (vostfree.ws redirige vers ipv4.vostfree.ws). Appris au 1er appel. */
    @Volatile
    private var hoteActif: String = BASE

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Préfixes relevés dans l'extension Aniyomi. Le site ne stocke que l'identifiant :
     * c'est ce préfixe qui reconstitue une URL exploitable par nos extracteurs.
     */
    private val PREFIXES = mapOf(
        "sibnet" to "https://video.sibnet.ru/shell.php?videoid=",
        "ok" to "https://ok.ru/videoembed/",
        "uqload" to "https://uqload.io/embed-",
        // 2026-08-07 : `vidmoly.to` (valeur de leur extension) rebondit vers `ww547.vidmoly.to`
        //   qui ne résout plus — vérifié dans le navigateur. Le domaine vivant est `vidmoly.biz`,
        //   celui que sert iAnime dans ses iframes.
        "mytv" to "https://vidmoly.biz/embed-",
    )

    /** Nom du bouton Vostfree → nom réel de l'hébergeur, pour l'UI et pour les préférences. */
    private val ALIAS_AFFICHAGE = mapOf(
        "mytv" to "Vidmoly",
        "ok" to "OK.ru",
        "sibnet" to "Sibnet",
        "uqload" to "Uqload",
        "doodstream" to "DoodStream",
        "mixdrop" to "MixDrop",
        "voe" to "VOE",
        "vudeo" to "Vudeo",
    )

    /** Ces hébergeurs-là donnent déjà une URL complète dans le `content_` : on ne préfixe pas. */
    private val DIRECTS = setOf("doodstream", "mixdrop", "voe", "vudeo")

    /** « Episode 0747 » → 747. « Film » → 1. */
    private val NUM_EPISODE = Regex("""(\d+)""")

    suspend fun fetchVostfreeBackup(
        title: String,
        season: Int = 0,
        episode: Int = 0,
        videoType: Video.Type? = null,
    ): List<Video.Server> = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext emptyList()

        val fiche = rechercher(title) ?: return@withContext emptyList()
        val html = get(fiche) ?: return@withContext emptyList()
        val doc = Jsoup.parse(html)

        // ── Choix de l'épisode ────────────────────────────────────────────────────────────
        //   Sur un film, la liste ne contient qu'une entrée « Film ». Sur une série, la
        //   numérotation est absolue : on cherche le numéro demandé tel quel.
        val options = doc.select("select.new_player_selector option")
        if (options.isEmpty()) return@withContext emptyList()

        val cible = if (videoType is Video.Type.Episode) episode else 1
        val index = options.indexOfFirst { opt ->
            val t = opt.text().trim()
            if (t.equals("Film", ignoreCase = true)) cible <= 1
            else NUM_EPISODE.find(t)?.groupValues?.get(1)?.toIntOrNull() == cible
        }
        if (index < 0) {
            Log.d(TAG, "'$title' : épisode $cible absent (${options.size} épisodes listés)")
            return@withContext emptyList()
        }

        // Le bloc de boutons porte le MÊME rang que l'option choisie.
        val blocs = doc.select("div[id^=buttons_]")
        val bloc = blocs.getOrNull(index) ?: return@withContext emptyList()

        val serveurs = bloc.select("div").mapIndexedNotNull { i, bouton ->
            val nom = bouton.text().trim()
            val idPlayer = bouton.id().takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
            val contenu = doc.selectFirst("div#content_$idPlayer")?.text()?.trim()
            if (contenu.isNullOrBlank()) return@mapIndexedNotNull null

            val cle = nom.lowercase()
            val src = when {
                cle in DIRECTS && contenu.startsWith("http") -> contenu
                PREFIXES.containsKey(cle) -> PREFIXES[cle] + contenu +
                    (if (cle == "uqload" || cle == "mytv") ".html" else "")
                contenu.startsWith("http") -> contenu
                else -> return@mapIndexedNotNull null
            }

            // Suffixe par index : deux lecteurs du même hébergeur ne doivent pas se
            //   déduplicquer l'un l'autre en aval (leçon Wiflix du 31/07).
            // ⚠ Ne PAS préfixer par « Vostfree » : BackupRegistry ajoute déjà le nom de la
            //   source, on obtiendrait « Vostfree · Vostfree — Sibnet ».
            // 2026-08-07 (user : « VIDMOLY je le vois plus dans les serveurs ») : Vostfree
            //   étiquette ses boutons avec des noms maison. « MyTV » = Vidmoly, « OK » =
            //   OK.ru. Non seulement c'est illisible côté UI, mais ExtractorRanker et
            //   UserPreferences (favoris, ratio d'image) indexent par NOM d'hébergeur :
            //   un bouton « MyTV » ne retombait sur aucune préférence Vidmoly existante.
            val nomAffiche = ALIAS_AFFICHAGE[cle] ?: nom
            Video.Server(id = "vostfree_${idPlayer}_$i", name = nomAffiche, src = src)
        }

        Log.i(TAG, "'$title' ép.$cible → ${serveurs.size} serveur(s)")
        serveurs
    }

    /** POST DLE, puis choix de la fiche dont le titre correspond vraiment. */
    private fun rechercher(title: String): String? {
        val corps = FormBody.Builder()
            .add("do", "search").add("subaction", "search")
            .add("search_start", "0").add("full_search", "0").add("result_from", "1")
            .add("story", title)
            .build()
        val html = post("$hoteActif/index.php?do=search", corps) ?: return null
        val doc = Jsoup.parse(html)
        val cible = normaliser(title)

        return doc.select("a[href*=.html]")
            .mapNotNull { a ->
                val href = a.attr("href").takeIf { it.contains(".html") } ?: return@mapNotNull null
                val texte = a.text().trim().takeIf { it.length > 2 } ?: return@mapNotNull null
                href to texte
            }
            .firstOrNull { (_, texte) ->
                // ⚠ CORRESPONDANCE STRICTE (demande du user, 07/08 : « rester sur du strict pour
                //   pas avoir de mauvais matchs »). Le site suffixe ses titres par « VOSTFR »,
                //   « FRENCH », « VF », « DDL », « Streaming » : on retire ces marqueurs, puis on
                //   exige l'ÉGALITÉ. Un `startsWith` collerait « One Piece Stampede » sur une
                //   recherche « One Piece » — exactement le faux positif à éviter.
                val nom = normaliser(texte.replace(Regex("(?i)\\b(vostfr|french|vf|ddl|streaming)\\b"), ""))
                nom == cible
            }
            ?.first
            .also { if (it == null) Log.d(TAG, "aucune fiche pour '$title'") }
    }

    private fun normaliser(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private fun post(url: String, corps: FormBody): String? = try {
        val req = Request.Builder().url(url).post(corps)
            .header("User-Agent", UA)
            .header("Referer", "$hoteActif/")
            .build()
        client.newCall(req).execute().use { r ->
            memoriserHote(r.request.url.toString())
            if (r.isSuccessful) r.body?.string() else { Log.w(TAG, "POST → ${r.code}"); null }
        }
    } catch (e: Exception) { Log.e(TAG, "POST échoué : ${e.message}"); null }

    private fun get(url: String): String? = try {
        val abs = if (url.startsWith("http")) url else "$hoteActif${if (url.startsWith("/")) "" else "/"}$url"
        val req = Request.Builder().url(abs)
            .header("User-Agent", UA)
            .header("Referer", "$hoteActif/")
            .build()
        client.newCall(req).execute().use { r ->
            memoriserHote(r.request.url.toString())
            if (r.isSuccessful) r.body?.string() else { Log.w(TAG, "GET → ${r.code}"); null }
        }
    } catch (e: Exception) { Log.e(TAG, "GET échoué : ${e.message}"); null }

    /** Retient l'hôte réellement servi après redirection (ipv4.vostfree.ws). */
    private fun memoriserHote(urlFinale: String) {
        runCatching {
            val u = java.net.URI(urlFinale)
            val racine = "${u.scheme}://${u.host}"
            if (racine != hoteActif && u.host.contains("vostfree")) {
                Log.i(TAG, "hôte actif → $racine")
                hoteActif = racine
            }
        }
    }
}
