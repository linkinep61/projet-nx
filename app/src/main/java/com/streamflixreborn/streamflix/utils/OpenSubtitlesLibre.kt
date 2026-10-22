package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * 2026-10-22 (user : « le truc qu'ils utilisent pour les sous-titres est intéressant ») :
 * relevé dans le lecteur Vidsrc (subtitles.js) — il interroge l'ANCIENNE API REST
 * d'OpenSubtitles, `rest.opensubtitles.org/search/…`, avec l'en-tête `X-User-Agent`.
 * Contrairement à l'API officielle (clés + quota de téléchargements), elle ne demande RIEN :
 * vérifié depuis le PC — Fight Club : 22 sous-titres français ; House of the Dragon S1E1 : 8.
 * Les fichiers arrivent en .gz par `SubDownloadLink`, décompressés et mis en cache ici.
 *
 * Utilisé par le lecteur : serveur VO/VOSTFR SANS piste française → on cherche ici AVANT
 * de traduire (une vraie traduction humaine vaut mieux que la traduction automatique).
 */
object OpenSubtitlesLibre {

    private const val TAG = "OpenSubtitlesLibre"
    private val client by lazy { NetworkClient.default.newBuilder().callTimeout(10, TimeUnit.SECONDS).build() }

    private fun imdbNum(imdb: String?): String? =
        imdb?.trim()?.removePrefix("tt")?.trimStart('0')?.takeIf { it.isNotBlank() && it.all(Char::isDigit) }

    /** Meilleur sous-titre dans [langue] (code OpenSubtitles « fre », « eng »…) → fichier local, ou null. */
    /** Mots qui identifient une release (source, définition, groupe), comparés au nom du fichier vidéo. */
    private fun motsRelease(nom: String?): Set<String> = nom.orEmpty().lowercase()
        .split(Regex("""[^a-z0-9]+""")).filter { it.length >= 2 && it !in setOf("mkv", "mp4", "avi", "srt", "vtt", "sub", "ass", "fr", "fre", "french", "vostfr") }.toSet()
    private val MOTS_UTILES = setOf("2160p", "1080p", "720p", "480p", "web", "webrip", "webdl", "dl", "bluray",
        "brrip", "bdrip", "hdtv", "dvdrip", "hdrip", "amzn", "nf", "dsnp", "hmax", "atvp", "remux", "proper", "repack",
        "extended", "director", "cut", "imax")

    /**
     * 2026-10-22 (user : « écarter les pistes dont on est sûr qu'elles ne matcheront pas, là c'est
     * n'importe quoi ») — piste de COMMENTAIRES du DVD (réalisateur, acteurs) : ce ne sont pas les
     * dialogues du film. Ex. Fight Club « DVDRip-XCT_track8_fre » : « Sur cette piste, les
     * commentaires de David Fincher… ». Repérée par le nom, ou par les premières répliques.
     */
    private val MOT_COMMENTAIRE = Regex("""(?i)(commentaires?|commentary|commentaries|kommentar|commento|comentarios?|director'?s.?comment)""")
    fun nomCommentaires(nom: String?): Boolean = nom != null && Regex("""(?i)comment""").containsMatchIn(nom)
    fun texteCommentaires(texte: String): Boolean {
        val debut = texte.lineSequence().filter { it.isNotBlank() && !it.contains("-->") && !it.trim().all(Char::isDigit) }
            .take(40).joinToString(" ")
        return MOT_COMMENTAIRE.containsMatchIn(debut)
    }

    /** Liens déjà reconnus comme pistes de commentaires (écartés des listes pour la session). */
    val rejetes: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    /**
     * @param filtreIso (langue = "all") n'accepte que les sous-titres dont la langue (ISO 639-1)
     *   passe ce filtre — sert à trouver une AUTRE langue à traduire en français.
     * @param surLangue reçoit la langue (ISO 639-1) du fichier rendu.
     */
    suspend fun chercher(
        videoType: Video.Type, langue: String = "fre", nomFichierVideo: String? = null,
        filtreIso: ((String) -> Boolean)? = null, surLangue: ((String) -> Unit)? = null,
    ): File? = withContext(Dispatchers.IO) {
        // 2026-10-22 (user, AnimeSama + MoviesFree : « les sous-titres n'ont pas débuté, mais dans
        //   les téléchargements ils sont disponibles, ils auraient dû débuter tout seuls ») — sans
        //   identifiant IMDb (fiches AnimeSama & co), on ne cherchait rien, alors que la recherche
        //   manuelle passe par le TITRE et trouvait le bon fichier. Même repli ici : le titre, avec
        //   saison + épisode pour une série.
        fun requete(titre: String?): String? = titre?.trim()?.takeIf { it.isNotBlank() }?.let {
            "query-" + java.net.URLEncoder.encode(it.lowercase(), "UTF-8").replace("+", "%20")
        }
        val (imdb, chemin) = when (videoType) {
            is Video.Type.Movie -> imdbNum(videoType.imdbId)?.let { it to "imdbid-$it" }
                ?: requete(videoType.title)?.let { "titre" to it }
            is Video.Type.Episode -> imdbNum(videoType.tvShow.imdbId)?.let {
                it to "episode-${videoType.number}/imdbid-$it/season-${videoType.season.number}"
            } ?: requete(videoType.tvShow.title)?.let {
                "titre" to "episode-${videoType.number}/$it/season-${videoType.season.number}"
            }
        } ?: run { Log.d(TAG, "ni identifiant IMDb ni titre → rien à chercher"); return@withContext null }

        val dossier = File(StreamFlixApp.instance.applicationContext.cacheDir, "st_opensubtitles").apply { mkdirs() }
        TraductionSousTitres.menage(dossier)
        val cle = "$langue-${chemin.replace('/', '_')}"
        dossier.listFiles()?.firstOrNull { it.name.startsWith("$cle.") && it.length() > 50 }?.let {
            // « all » : la langue est inscrite dans le nom (cle.<iso>.ext).
            if (langue == "all") surLangue?.invoke(it.name.removePrefix("$cle.").substringBefore('.'))
            return@withContext it
        }

        val json = runCatching {
            client.newCall(
                Request.Builder().url("https://rest.opensubtitles.org/search/$chemin/sublanguageid-$langue")
                    .header("X-User-Agent", "trailers.to-UA").build()
            ).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
        }.getOrNull() ?: return@withContext null
        val liste = runCatching { JSONArray(json) }.getOrNull() ?: return@withContext null
        // Un seul fichier (pas de « CD1/CD2 »), format texte lisible, le plus téléchargé d'abord.
        val candidats = (0 until liste.length()).map { liste.getJSONObject(it) }
            .filter { it.optString("SubSumCD", "1") == "1" && it.optString("SubFormat").lowercase() in setOf("srt", "vtt", "ssa", "ass") }
            .filter { !nomCommentaires(it.optString("SubFileName") + " " + it.optString("MovieReleaseName")) && it.optString("SubDownloadLink") !in rejetes }
            .filter { filtreIso == null || filtreIso(it.optString("ISO639").lowercase()) }
            .let { liste0 ->
                // 2026-10-22 : comme le lecteur Vidsrc — si on connaît le nom du fichier vidéo, le
                //   sous-titre de la MÊME release (source, définition, groupe) passe devant : c'est
                //   lui qui est calé. Sinon, le plus téléchargé.
                val video = motsRelease(nomFichierVideo)
                val groupe = video.lastOrNull()
                liste0.sortedWith(compareByDescending<org.json.JSONObject> { c ->
                    if (video.isEmpty()) 0 else {
                        val st = motsRelease(c.optString("MovieReleaseName") + " " + c.optString("SubFileName"))
                        st.count { it in video && it in MOTS_UTILES } + (if (groupe != null && groupe in st && groupe !in MOTS_UTILES) 3 else 0)
                    }
                }.thenByDescending { it.optString("SubDownloadsCnt").toLongOrNull() ?: 0L })
            }
        Log.d(TAG, "imdb $imdb $chemin [$langue] : ${liste.length()} résultat(s), ${candidats.size} utilisable(s)")
        for (c in candidats.take(3)) {
            val lien = c.optString("SubDownloadLink").takeIf { it.startsWith("http") } ?: continue
            val brut = runCatching {
                client.newCall(Request.Builder().url(lien).build()).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    // 2026-10-22 (AnimeSama) : certains fichiers (liens « filead ») arrivent NON
                    //   compressés malgré le « .gz » → le décompresseur levait une erreur et le
                    //   sous-titre était perdu sans un mot. On regarde la signature gzip (1F 8B).
                    val recu = r.body!!.bytes()
                    val octets = if (recu.size > 2 && recu[0] == 0x1f.toByte() && recu[1] == 0x8b.toByte())
                        GZIPInputStream(recu.inputStream()).readBytes() else recu
                    val jeu = runCatching { charset(c.optString("SubEncoding").ifBlank { "UTF-8" }) }.getOrDefault(Charsets.UTF_8)
                    String(octets, jeu)
                }
            }.onFailure { Log.w(TAG, "téléchargement illisible : ${it.message}") }.getOrNull() ?: continue
            // 2026-10-22 (AnimeSama) : les animes sont souvent en .ass (pas de « --> ») — ils étaient
            //   écartés ici, d'où « pas de français » alors qu'un fichier existait. Convertis en VTT.
            val ass = ConversionAss.versVtt(brut)
            val texte = ass ?: brut
            if (!texte.contains("-->")) { Log.w(TAG, "aucune réplique lisible : ${c.optString("SubFileName")} (${brut.take(60).replace('\n', ' ')})"); continue }
            if (texteCommentaires(texte)) {
                rejetes.add(lien)
                Log.i(TAG, "écarté (piste de commentaires) : ${c.optString("SubFileName")}")
                continue
            }
            val ext = if (ass != null) "vtt" else c.optString("SubFormat").lowercase().let { if (it == "vtt") "vtt" else "srt" }
            val iso = c.optString("ISO639").lowercase()
            val f = File(dossier, if (langue == "all") "$cle.$iso.$ext" else "$cle.$ext")
            f.writeText(texte)
            surLangue?.invoke(iso)
            Log.i(TAG, "sous-titre [$langue] retenu : ${c.optString("SubFileName")} (${c.optString("SubDownloadsCnt")} téléchargements)")
            return@withContext f
        }
        null
    }
}
