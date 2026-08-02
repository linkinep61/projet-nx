package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.google.gson.Gson
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/**
 * FileSearchProvider — backup NATIF par TITRE (filesearch.tools, 2026-07-24).
 *
 * Indexeur d'**open-directories** multi-hôtes : renvoie des fichiers VIDÉO **DIRECTS**
 * (.mp4/.mkv/.avi/.webm) hébergés un peu partout (archive.org, IP perso, serveurs VOD).
 * **AUCUNE protection** (pas de Cloudflare, pas de token, pas d'ad-gate).
 *
 * API (reverse dans le Chrome du user) :
 *   GET /api/files/search?q=<titre>&page=1&sort_by=time&order=desc&category=video
 *   → { files:[{name, path, size, size_bytes, time}], total, pages }
 *   `path` = URL DIRECTE du fichier → lue en NATIF par ExoPlayer (aucun extracteur).
 *
 * Pièges gérés :
 *   - L'API casse sur les tirets/guillemets/ponctuation (« Invalid search syntax ») → requête
 *     nettoyée (alphanumérique + espaces ; les accents sont normalisés côté API mais on les
 *     retire aussi pour être sûr).
 *   - Les `name` sont des fichiers BRUTS avec tags de release → matching STRICT obligatoire
 *     (titre + année pour les films, SxxExx pour les séries) sinon on sert le mauvais fichier.
 *
 * Source BACKUP uniquement (par titre) : appelée par BackupRegistry. Non browsable.
 */
object FileSearchProvider {
    private const val TAG = "FileSearchProvider"
    private const val BASE = "https://filesearch.tools"
    const val SRC_PREFIX = "filesearch::"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Safari/537.36"

    private val DIRECT_EXTS = setOf("mp4", "mkv", "avi", "webm", "m4v", "mov")
    private val AUDIO_EXTS = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma")
    // 2026-08-02 (demande user) : 12 → 20 serveurs retenus. FileSearch sert des fichiers directs
    //   (meilleure qualité, lecture native), autant en garder davantage maintenant qu'ils sont
    //   correctement triés — les meilleurs remontent, les faibles restent en bas de liste.
    private const val MAX_SERVERS = 20
    /** Plafond de COLLECTE, avant tri. Large exprès : c'est le tri qui décide des retenus. */
    private const val MAX_CANDIDATS = 80

    /** Au-delà, le débit soutenu devient trop élevé pour un flux d'open-directory.
     *  25 Go ≈ 26 Mbit/s de moyenne sur un film de 2 h — déjà exigeant, mais tenable. */
    private const val PLAFOND_GO = 25.0

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private data class SearchResp(val files: List<FileItem>? = null, val total: Int = 0)
    private data class FileItem(val name: String? = null, val path: String? = null, val size: String? = null)

    // ── HTTP ──────────────────────────────────────────────────────────────────
    private fun get(url: String): String? = try {
        val req = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", UA)
            .header("Referer", "$BASE/")
            .build()
        client.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null }
    } catch (e: Exception) { Log.w(TAG, "get KO: ${e.message}"); null }

    // ── Normalisation ───────────────────────────────────────────────────────────
    private fun stripAccents(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    /** Titre → forme normalisée (minuscule, sans accent, alphanum+espaces). */
    private fun norm(s: String): String =
        stripAccents(s).lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    /** Nettoie la requête pour l'API (qui rejette tirets/guillemets/ponctuation). */
    private fun sanitizeQuery(s: String): String =
        stripAccents(s).replace(Regex("[^A-Za-z0-9 ]+"), " ").replace(Regex("\\s+"), " ").trim()

    private fun extToMime(ext: String): String? = when (ext) {
        "mp4", "m4v", "mov" -> androidx.media3.common.MimeTypes.VIDEO_MP4
        "mkv" -> androidx.media3.common.MimeTypes.VIDEO_MATROSKA
        "webm" -> androidx.media3.common.MimeTypes.VIDEO_WEBM
        "avi" -> "video/x-msvideo"
        else -> null
    }

    private fun extOf(url: String): String =
        Regex("\\.([a-z0-9]{2,4})(?:\\?|$)", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)?.lowercase() ?: ""

    private fun qualityOf(name: String): String =
        Regex("(2160p|1440p|1080p|720p|480p|4k)", RegexOption.IGNORE_CASE)
            .find(name)?.groupValues?.get(1)?.uppercase() ?: ""

    private fun langOf(name: String): String {
        val n = name.uppercase()
        return when {
            "MULTI" in n -> "MULTI"
            "TRUEFRENCH" in n -> "TRUEFRENCH"
            Regex("\\bVFF\\b").containsMatchIn(n) -> "VFF"
            // Audio français : FRENCH + toutes les variantes de VF (VF, VFQ québécois, VFI, VF2, VOF).
            "FRENCH" in n || Regex("\\bV(F|FQ|FI|F2|OF)\\b").containsMatchIn(n) -> "FR"
            // Sous-titres français (audio VO mais sous-titré FR) → PAS « full anglais », on garde.
            "VOSTFR" in n || "SUBFRENCH" in n || Regex("\\bST\\.?FR\\b").containsMatchIn(n) -> "VOSTFR"
            else -> ""
        }
    }

    /** Un fichier est « exploitable en français » s'il porte un marqueur audio FR OU sous-titres FR.
     *  Les open-directories de FileSearch sont internationaux : un fichier SANS aucun marqueur
     *  français est presque toujours 100 % anglais → on le vire (demande user : « s'il n'y a que
     *  l'audio anglais on vire le serveur »). Les releases FR sont quasi toujours taguées
     *  (FRENCH/TRUEFRENCH/MULTI/VF…), donc ce filtre ne sacrifie pas de vrai contenu français. */
    private fun isFrenchUsable(name: String): Boolean = langOf(name).isNotBlank()

    /**
     * 2026-08-05 — ON N'AFFICHE PLUS LES FICHIERS QU'ON SAIT NE PAS POUVOIR LIRE.
     *
     * Cas qui a déclenché ça : « FileSearch · 53.50 GB » proposé en tête sur Matrix Revolutions —
     *   `The.Matrix.Revolutions.2003.2160p.UHD.BluRay.REMUX.HEVC.HDR10.10bit.TrueHD.7.1.Atmos.mkv`.
     *   Le fichier se télécharge parfaitement (le site n'y est pour rien), mais le décodeur
     *   matériel de la box jette les images en continu (`VDA: error frame id …`, puis
     *   `discard bitstream count:` qui grimpe de 3840 à 4983 en vingt secondes).
     *
     * Deux raisons, cumulées :
     *   · un REMUX est le flux BRUT du disque Blu-ray — 53,5 Go pour 2 h 09 = **56 Mbit/s de
     *     moyenne**, avec des pointes au-delà de 100. Il faut tenir ce débit sans faiblir
     *     pendant deux heures, depuis un open-directory, souvent en Wi-Fi.
     *   · la piste audio est en TrueHD/Atmos, qu'Android ne sait PAS décoder en logiciel
     *     (uniquement retransmettre en HDMI vers un ampli compatible).
     *
     * Décision user : les ÉCARTER (plutôt que les classer en dernier), sur DEUX critères —
     *   la taille seule raterait un REMUX de 22 Go et écarterait à tort un bon 1080p de 26 Go.
     *
     * ⚠ Ne PAS durcir le plafond sans raison : un encodage 1080p ou 4K normal de 20 Go se lit
     *   très bien. C'est le débit d'un remux qui pose problème, pas le poids en soi.
     */
    private fun estIllisibleEnStreaming(name: String, taille: String?): Boolean {
        val n = name.uppercase()
        // Un REMUX en 4K = flux disque intact → injouable quelle que soit sa taille annoncée.
        val estRemux4k = n.contains("REMUX") &&
            (n.contains("2160P") || n.contains("UHD") || n.contains("4K"))
        if (estRemux4k) return true
        return tailleEnGo(taille) > PLAFOND_GO
    }

    /**
     * 2026-08-02 (user : « à partir du moment où ça fait un giga c'est déjà du 1080p ») :
     * qualité DÉDUITE DE LA TAILLE quand le nom ne la donne pas.
     *
     * Beaucoup de fichiers d'open-directory sont nommés « …2026.MultiHD.mkv » ou « …Multi.mp4 »,
     * sans mention de définition : `qualityOf` renvoyait alors une chaîne vide, le fichier ne
     * recevait aucun point de qualité, et un 1,39 Go passait devant un 3,96 Go — ce que montrait
     * la liste. Or pour un long métrage la taille est un indicateur fiable du niveau de définition.
     *
     * Seuils volontairement prudents (film d'environ 2 h) : on préfère sous-estimer plutôt que
     * d'annoncer une définition que le fichier n'a pas.
     */
    /**
     * ⚠ 2026-08-02, CORRECTION (user : « les serveurs sont marqués 1080p et en réalité c'est du
     * 720p ») : cette déduction NE SERT PLUS À L'AFFICHAGE.
     *
     * Le seuil « ≥ 1 Go = 1080p » s'est révélé faux dans les deux sens : un film de 2 h en 720p
     * dépasse allègrement 1,4 Go, tandis qu'un 1080p encodé en x265 peut tenir sous 2 Go. Le poids
     * seul ne permet donc pas d'affirmer une définition — l'annoncer revenait à présenter une
     * supposition comme une mesure, ce qui est pire que de ne rien afficher.
     *
     * Elle reste utilisée pour le CLASSEMENT (à titre relatif : entre deux fichiers du même film,
     * le plus lourd est le mieux encodé), jamais pour l'étiquette montrée à l'utilisateur.
     */
    private fun qualiteDepuisTaille(taille: String?): String {
        val t = taille?.trim()?.uppercase() ?: return ""
        val nombre = Regex("""([0-9]+(?:[.,][0-9]+)?)""").find(t)?.groupValues?.get(1)
            ?.replace(',', '.')?.toDoubleOrNull() ?: return ""
        val go = when {
            t.contains("GB") || t.contains("GO") -> nombre
            t.contains("MB") || t.contains("MO") -> nombre / 1024.0
            else -> return ""
        }
        // Seuils RESSERRÉS, uniquement pour le tri interne.
        return when {
            go >= 15.0 -> "2160P"
            go >= 3.0 -> "1080P"
            go >= 1.2 -> "720P"
            go > 0.0 -> "480P"
            else -> ""
        }
    }

    /**
     * Qualité AFFICHÉE : uniquement celle écrite dans le nom du fichier — une information factuelle,
     * fournie par celui qui a encodé le fichier. Rien n'est déduit ici : à défaut, on n'affiche
     * pas de définition, et c'est la TAILLE (déjà présente dans le libellé) qui renseigne
     * l'utilisateur. Mieux vaut une information manquante qu'une information fausse.
     */
    private fun qualiteEffective(name: String, taille: String?): String = qualityOf(name)

    /** Taille en gigaoctets, pour départager deux fichiers de même définition. */
    private fun tailleEnGo(taille: String?): Double {
        val t = taille?.trim()?.uppercase() ?: return 0.0
        val nombre = Regex("""([0-9]+(?:[.,][0-9]+)?)""").find(t)?.groupValues?.get(1)
            ?.replace(',', '.')?.toDoubleOrNull() ?: return 0.0
        return when {
            t.contains("GB") || t.contains("GO") -> nombre
            t.contains("MB") || t.contains("MO") -> nombre / 1024.0
            else -> 0.0
        }
    }

    /** Classement : FR/MULTI d'abord, puis meilleure qualité (du nom, ou estimée par le poids). */
    private fun frenchScore(name: String): Int {
        val l = langOf(name)
        val langPts = when (l) { "MULTI", "TRUEFRENCH", "VFF", "FR" -> 100; "VOSTFR" -> 40; else -> 0 }
        // Pour le TRI seulement : à défaut de mention dans le nom, on estime via la taille
        //   (le libellé la porte en fin de chaîne, ex. « FileSearch · MULTI · 3.96 GB »).
        val q = qualityOf(name).ifBlank { qualiteDepuisTaille(name.substringAfterLast(" · ", "")) }
        val qPts = when (q) { "2160P", "4K" -> 5; "1440P" -> 4; "1080P" -> 3; "720P" -> 2; "480P" -> 1; else -> 0 }
        return langPts + qPts
    }

    // ── FETCH (par titre, matching strict) ────────────────────────────────────────
    /**
     * @param oeuvreFrancaise TMDB dit que la LANGUE D'ORIGINE de l'œuvre est le français.
     *   2026-08-08 (user, sur « Cocorico » 2024 : « c'est une œuvre française, on est sûr
     *   d'avoir du français ») — le filtre `isFrenchUsable` exige un marqueur dans le nom
     *   (FRENCH/MULTi/VF/VOSTFR…). C'est juste pour une release scene, mais FAUX pour une
     *   bibliothèque Radarr, qui renomme proprement « Titre (Année).mkv » sans aucun tag :
     *   tous ces fichiers étaient jetés en silence. Constaté sur
     *   `104.152.211.92:9000/radarr/Cocorico (2024).mkv` (4,27 Go), présent et servi, mais
     *   jamais proposé — d'où le log « aucun fichier français → on garde les VOSTFR ».
     *   La règle d'origine (user : « s'il n'y a que l'audio anglais on vire le serveur »)
     *   reste INTACTE pour tout le reste : on ne lève l'exigence que si TMDB affirme que
     *   l'œuvre est française, auquel cas un fichier de ce film ne peut pas être 100 % anglais.
     *   Filtre à sens unique, comme l'animation et les dramas : langue inconnue → rien ne change.
     */
    suspend fun fetchFileSearchBackupServers(
        videoType: Video.Type,
        season: Int,
        episode: Int,
        year: Int?,
        titles: List<String>,
        oeuvreFrancaise: Boolean = false,
    ): List<Video.Server> = withContext(Dispatchers.IO) {
        val isMovie = videoType is Video.Type.Movie
        val wantTitles = titles.mapNotNull { it.trim().takeIf { t -> t.isNotBlank() } }.distinct()
        if (wantTitles.isEmpty()) return@withContext emptyList()
        val wantNorm = wantTitles.map { norm(it) }.filter { it.isNotBlank() }.toHashSet()
        if (wantNorm.isEmpty()) return@withContext emptyList()

        // Regex épisode (série) : S01E02 / 1x02, tolérant aux zéros/séparateurs.
        val seRegex = if (!isMovie && season > 0 && episode > 0)
            Regex("(?:s0*${season}e0*${episode}|(?<![0-9])${season}x0*${episode})(?![0-9])", RegexOption.IGNORE_CASE)
        else null

        val out = LinkedHashMap<String, Video.Server>() // dédup par URL
        for (title in wantTitles.take(3)) {
            val q = sanitizeQuery(title)
            if (q.isBlank()) continue
            val url = "$BASE/api/files/search?q=${URLEncoder.encode(q, "UTF-8")}" +
                "&page=1&sort_by=time&order=desc&category=video"
            val body = get(url) ?: continue
            val resp = try { gson.fromJson(body, SearchResp::class.java) } catch (e: Exception) { null } ?: continue
            val files = resp.files ?: continue

            for (f in files) {
                val path = f.path ?: continue
                if (path.isBlank() || out.containsKey(path)) continue
                if (extOf(path) !in DIRECT_EXTS) continue

                val rawName = try { URLDecoder.decode(f.name ?: "", "UTF-8") } catch (e: Exception) { f.name ?: "" }
                val nName = norm(rawName)                 // nom fichier normalisé
                val nCompact = nName.replace(" ", "")     // pour SxxExx collé

                // 1) le TITRE doit être présent dans le nom du fichier
                if (wantNorm.none { it.isNotBlank() && nName.contains(it) }) continue

                // 2) film → l'ANNÉE (si connue) doit figurer ; série → le SxxExx doit matcher
                if (isMovie) {
                    if (year != null && year > 1900 && !nName.contains(year.toString())) continue
                } else {
                    val re = seRegex ?: continue
                    if (!re.containsMatchIn(nName) && !re.containsMatchIn(nCompact)) continue
                }

                // 3) FILTRE LANGUE : on écarte les fichiers 100 % anglais (aucun marqueur FR audio
                //    ni sous-titre). Garde MULTI/TRUEFRENCH/FRENCH/VF* et VOSTFR (sous-titres FR).
                if (!oeuvreFrancaise && !isFrenchUsable(rawName)) continue

                // 4) FILTRE DÉBIT : on écarte ce que l'appareil ne pourra pas lire (remux 4K,
                //    ou fichier au-delà du plafond). Voir estIllisibleEnStreaming.
                if (estIllisibleEnStreaming(rawName, f.size)) continue

                // 2026-08-08 : sur une œuvre française, un fichier sans marqueur est du français
                //   non tagué (Radarr). On l'étiquette « FR » — sinon `langOf` renvoie vide, il
                //   sort du lot `enFrancais` plus bas et se ferait écarter par le tri au profit
                //   d'un VOSTFR, alors que c'est justement le meilleur candidat.
                val lang = langOf(rawName).ifBlank { if (oeuvreFrancaise) "FR" else "" }
                // Qualité du nom, ou déduite de la taille si le nom est muet.
                val qual = qualiteEffective(rawName, f.size)
                val label = buildString {
                    append("FileSearch")
                    if (lang.isNotBlank()) append(" · $lang")
                    if (qual.isNotBlank()) append(" · $qual")
                    f.size?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                }
                out[path] = Video.Server(id = SRC_PREFIX + path, name = label, src = path)
                // ⚠ 2026-08-02 (user : « le VOSTFR prend la place d'un serveur VF pour rien, car on
                //   a mis une limite à FileSearch ») : plus de coupure ICI. Le plafond était
                //   appliqué PENDANT la collecte, alors que le tri par langue n'intervient
                //   qu'APRÈS : les VOSTFR rencontrés en premier consommaient les 12 places et des
                //   VF trouvés plus loin étaient purement ignorés. On collecte tout, puis on trie,
                //   puis on plafonne — dans cet ordre.
                //   Garde-fou mémoire : on s'arrête bien au-delà du plafond, jamais à l'infini.
                if (out.size >= MAX_CANDIDATS) break
            }
            if (out.size >= MAX_CANDIDATS) break
        }

        // Tri : langue FR d'abord, puis définition, puis TAILLE décroissante — à définition égale,
        //   le fichier le plus lourd est le mieux encodé (débit supérieur). Sans ce dernier
        //   critère, un 1,39 Go pouvait s'afficher devant un 3,96 Go du même film.
        // 2026-08-02 (user : « s'il est détecté VOSTFR il devrait être sauté ») : les fichiers
        //   VOSTFR sont écartés — MAIS seulement s'il reste des fichiers en français. Sur un titre
        //   qui n'existe qu'en VOSTFR, les retirer supprimerait purement et simplement la source ;
        //   dans ce cas on les garde, mieux vaut du VOSTFR que rien.
        val enFrancais = out.values.filter {
            val l = langOf(it.name)
            l == "MULTI" || l == "TRUEFRENCH" || l == "VFF" || l == "FR"
        }
        val retenus = if (enFrancais.isNotEmpty()) {
            val ecartes = out.size - enFrancais.size
            if (ecartes > 0) Log.i(TAG, "FileSearch : $ecartes fichier(s) VOSTFR écarté(s) (${enFrancais.size} en français disponibles)")
            enFrancais
        } else {
            Log.i(TAG, "FileSearch : aucun fichier français → on garde les VOSTFR (${out.size})")
            out.values.toList()
        }

        // Tri D'ABORD (langue FR, puis définition, puis poids), plafond ENSUITE : les places
        //   reviennent ainsi aux MEILLEURS fichiers, pas aux premiers arrivés.
        val ranked = retenus.sortedWith(
            compareByDescending<Video.Server> { frenchScore(it.name) }
                .thenByDescending { tailleEnGo(it.name.substringAfterLast(" · ", "")) }
        ).take(MAX_SERVERS)
        Log.i(TAG, "FileSearch '${wantTitles.firstOrNull()}' → ${ranked.size} fichiers directs")
        ranked
    }

    // ── RECHERCHE AUDIO (musique — fichiers .mp3/.m4a directs) ─────────────────────
    /** Résultat audio direct (lu par le mini-player radio via son URL). */
    /**
     * @param thumbnail 2026-08-02 (user : « affiche les albums et les jaquettes à la place du
     *   petit carré… au moins quand c'est un album, si c'est une musique simple ça reste comme
     *   d'habitude ») : pochette à afficher dans la liste. Renseignée UNIQUEMENT pour les pistes
     *   d'un album (ZeffyrMusic) ; `null` pour une recherche de titres isolés → la pastille
     *   d'initiale habituelle est conservée. Valeur par défaut → les autres sources
     *   (FileSearch, NewPipe) restent inchangées.
     */
    data class AudioResult(
        val title: String,
        val url: String,
        val size: String,
        val thumbnail: String? = null,
    )

    /**
     * Recherche de fichiers AUDIO directs par titre/artiste (category=audio).
     * Pas de matching strict : l'utilisateur PARCOURT de la musique (comme une radio) →
     * on renvoie tout ce qui matche la requête, dédupliqué par URL.
     */
    suspend fun searchAudio(query: String, limit: Int = 300, maxPages: Int = 5): List<AudioResult> = withContext(Dispatchers.IO) {
        val q = sanitizeQuery(query)
        if (q.isBlank()) return@withContext emptyList()
        val out = LinkedHashMap<String, AudioResult>()
        for (page in 1..maxPages) {
            val url = "$BASE/api/files/search?q=${URLEncoder.encode(q, "UTF-8")}" +
                "&page=$page&sort_by=time&order=desc&category=audio"
            val body = get(url) ?: break
            val resp = try { gson.fromJson(body, SearchResp::class.java) } catch (e: Exception) { null } ?: break
            val files = resp.files ?: break
            if (files.isEmpty()) break
            for (f in files) {
                val path = f.path ?: continue
                if (path.isBlank() || out.containsKey(path)) continue
                if (extOf(path) !in AUDIO_EXTS) continue
                val name = try { URLDecoder.decode(f.name ?: "", "UTF-8") } catch (e: Exception) { f.name ?: "" }
                out[path] = AudioResult(
                    title = name.ifBlank { path.substringAfterLast('/') },
                    url = path,
                    size = f.size ?: "",
                )
                if (out.size >= limit) break
            }
            if (out.size >= limit) break
        }
        Log.i(TAG, "searchAudio '$q' → ${out.size} morceaux")
        out.values.toList()
    }

    // ── GETVIDEO (fichier DIRECT → lecture native) ────────────────────────────────
    fun getVideo(server: Video.Server): Video {
        val url = server.src
        return Video(
            source = url,
            type = extToMime(extOf(url)),
            headers = mapOf("User-Agent" to UA),
        )
    }
}
