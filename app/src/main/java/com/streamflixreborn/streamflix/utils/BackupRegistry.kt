package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.CloudstreamProvider
import com.streamflixreborn.streamflix.providers.CoflixSourceProvider
import com.streamflixreborn.streamflix.providers.CoflixWikiProvider
import com.streamflixreborn.streamflix.providers.MovieboxProvider
import com.streamflixreborn.streamflix.providers.MovixProvider
import com.streamflixreborn.streamflix.providers.NakiosProvider
import com.streamflixreborn.streamflix.providers.PapadustreamV2Provider
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.VoirDramaProvider
import com.streamflixreborn.streamflix.providers.WebJsProvider
import com.streamflixreborn.streamflix.providers.WebflixProvider
import com.streamflixreborn.streamflix.providers.WiflixProvider
import com.streamflixreborn.streamflix.extractors.VideasyExtractor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * 2026-07-02 — REGISTRE DE BACKUPS CENTRALISÉ (refonte anti-spaghettis).
 *
 * But (user) : « désactiver tous les backups éparpillés et se réorganiser pour que chaque
 * provider fasse UN seul appel, avoir tous les backups possibles SANS DOUBLON ».
 *
 * Avant : Cloudstream→Coflix/Nakios, Movix→Coflix/Moiflix, XBACKUP→tout le monde…
 *   → chaque backup arrivait par plusieurs chemins, on comptait sur le dédup à l'affichage.
 * Maintenant : un provider appelle [fetchAll] UNE fois. Le registre interroge toutes les
 *   sources de backup en parallèle, DÉDUPLIQUE À LA SOURCE (par URL normalisée), et n'émet
 *   chaque flux qu'une seule fois. Les sources sont toutes « feuilles » ou « natif-seul »
 *   (elles n'appellent PAS le registre) → aucune récursion, aucun doublon transitif.
 *
 * Anti-récursion : [exclude] = noms de providers à ne PAS interroger (celui qui appelle +
 *   ceux dont on vient déjà d'avoir les sources natives).
 *
 * Lecture : les serveurs sont taggés `bkreg::<source>::<idOrig>`. [getVideo] ré-aiguille
 *   vers le getVideo de la source d'origine (ou Extractor pour les embeds hosts).
 */
object BackupRegistry {
    private const val TAG = "BackupRegistry"
    const val PREFIX = "bkreg::"

    // 2026-07-04 (user "désactiver un par un tous les backups de chaque provider") : kill-switch
    //   GLOBAL des backups INLINE éparpillés. true = chaque provider n'émet QUE ses serveurs
    //   NATIFS ; TOUS les backups passent par le registre central (fetchAll, mergé dans le
    //   flux progressif de PlayerViewModel). Évite les doublons registre ↔ inline.
    const val INLINE_BACKUPS_DISABLED = true

    // 2026-07-10 (user "pouvoir désactiver les backups UN PAR UN") : liste canonique des sources
    //   de backup, avec un libellé lisible. La VALEUR (1er) doit correspondre EXACTEMENT au nom
    //   passé à emit(...) — c'est la clé du gate (UserPreferences.isBackupSourceEnabled). Le 2ᵉ =
    //   libellé affiché dans le toggle. Sert à peupler le MultiSelect des Paramètres.
    val BACKUP_SOURCES: List<Pair<String, String>> = listOf(
        "Cloudstream" to "Cloudstream",
        "Movix" to "Movix",
        "Frembed" to "Frembed",
        "Moviebox" to "Moviebox",
        "Nakios" to "Nakios",
        "Webflix" to "Webflix",
        "CoflixWiki" to "CoflixWiki",
        "AniCloud" to "AniCloud (animes)",
        "Papadustream V2" to "Papadustream V2",
        "Embed" to "Embed (Videasy VOSTFR)",
        "Wiflix" to "Wiflix",
        "FrenchStream" to "FrenchStream",
        "1Jour1Film" to "1Jour1Film",
        "aplouf" to "aplouf",
        "AnimeSama" to "AnimeSama (animes)",
        "VoirAnime" to "VoirAnime (animes)",
        "FrenchManga" to "FrenchManga (animes)",
        "VoirDrama" to "VoirDrama (dramas)",
        "DessinAnime" to "DessinAnime",
    )

    /** Noms exacts (clé de gate) de toutes les sources de backup connues. */
    val BACKUP_SOURCE_KEYS: Set<String> = BACKUP_SOURCES.map { it.first }.toSet()

    // 2026-07-03 (user "optimiser pour la TV") : sur un device à faible RAM (Chromecast :
    //   largeHeap ≈146MB vs 512MB+ sur téléphone), lancer ~13 sources backup dont 6-7 à
    //   base de WebView EN MÊME TEMPS sature le heap → GC de 3s qui gèlent le thread
    //   principal (logcat OPPO OK, mais Chromecast : "GC 3,34s" puis "2,97s", "Skipped 186
    //   frames") → les serveurs s'affichent en un seul bloc au lieu de progressif. On détecte
    //   le low-RAM via Runtime.maxMemory() (AUCUN Context requis) et on limite les sources
    //   LOURDES (WebView) à 2 en parallèle. Les sources API légères (Movix/Moviebox/Embed/
    //   Papadustream) restent NON bridées. Sur mobile : permits=16 → comportement INCHANGÉ.
    private val LOW_RAM = Runtime.getRuntime().maxMemory() < 200L * 1024 * 1024
    private val HEAVY_SOURCES = setOf(
        "CoflixWiki", "Nakios", "Moiflix", "DessinAnime", "FrenchAnime", "Wiflix", "Dramacool"
    )
    private val heavyGate = Semaphore(if (LOW_RAM) 2 else 16)

    // 2026-07-07 : pacing mémoire RETIRÉ (régressait la Chromecast :
    //   heapTight()=true en PERMANENCE sur Chromecast car heap normal=80%
    //   → mutex 12s par backup → sérialise tout → timeout 45s → 0 serveurs).
    //   L'app marchait très bien sans.

    // 2026-07-08 (user "remets tout dans la liste normale") : WEBVIEW_HEAVY_PROVIDERS
    //   et la 2ᵉ vague CF SUPPRIMÉS. Tous les providers backup passent dans la même
    //   boucle sans délai spécial. Ceux qui sont désactivés restent commentés.
    // (ancien WEBVIEW_HEAVY_PROVIDERS : FrenchAnime, VoirAnime, FrenchManga, Franime, VoirDrama, DessinAnime)

    // ═══════════════════════════════════════════════════════════════════════
    //  POINT D'ENTRÉE HÉBERGÉ — backups web ajoutables SANS rebuild (2026-07-02)
    // ═══════════════════════════════════════════════════════════════════════
    //  (user "un point d'entrée que tu crées, on peut rajouter n'importe quoi
    //   dedans après, sans mise à jour de l'app").
    //  Le fichier JSON liste des backups web. À chaque entrée l'app crée un
    //  WebJsProvider (moteur JS hébergé) interrogé comme source du registre.
    //  Format : [{ "name":"MonBackup", "baseUrl":"https://site.tld",
    //             "jsUrl":"https://raw.githubusercontent.com/.../monbackup.js",
    //             "enableNativeServers":true, "tmdbPosters":false }]
    //  → ajouter une entrée + héberger le .js = nouveau backup partout, zéro build.
    private const val MANIFEST_URL =
        "https://raw.githubusercontent.com/linkinep61/projet-nx/main/provider_web/backups.json"
    private const val MANIFEST_TTL_MS = 30 * 60 * 1000L
    private val dynamicBackups = ConcurrentHashMap<String, WebJsProvider>()
    // 2026-07-02 : noms des backups dynamiques interrogés par TMDB id (getSourcesByTmdb)
    //   au lieu de par titre (searchServersByTitle). Manifeste : "byTmdb": true.
    private val dynamicByTmdb = java.util.Collections.synchronizedSet(HashSet<String>())
    @Volatile private var manifestLoadedAt = 0L
    private val manifestMutex = kotlinx.coroutines.sync.Mutex()
    private val manifestHttp by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /** 2026-07-02 (bouton « refresh provider ») : purge les backups web dynamiques +
     *  force le re-chargement du manifeste au prochain accès. */
    fun clearDynamic() {
        dynamicBackups.clear()
        dynamicByTmdb.clear()
        manifestLoadedAt = 0L
    }

    /** Charge (ou rafraîchit, TTL 30 min) le manifeste des backups web hébergés et
     *  crée un WebJsProvider par entrée non déjà enregistrée. Silencieux sur échec. */
    private suspend fun ensureDynamicBackups() {
        val now = System.currentTimeMillis()
        if (now - manifestLoadedAt < MANIFEST_TTL_MS && manifestLoadedAt != 0L) return
        manifestMutex.lock()
        try {
            if (System.currentTimeMillis() - manifestLoadedAt < MANIFEST_TTL_MS && manifestLoadedAt != 0L) return
            val body = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    manifestHttp.newCall(okhttp3.Request.Builder().url(MANIFEST_URL).build())
                        .execute().use { if (it.isSuccessful) it.body?.string() else null }
                } catch (_: Exception) { null }
            } ?: run { Log.w(TAG, "manifeste backups: fetch échoué"); return }
            val arr = try { org.json.JSONArray(body) } catch (_: Exception) { Log.w(TAG, "manifeste JSON invalide"); return }
            val registered = Provider.providers.keys.map { it.name }.toSet()
            var added = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val nm = o.optString("name").trim()
                val base = o.optString("baseUrl").trim()
                val js = o.optString("jsUrl").trim()
                if (nm.isBlank() || base.isBlank() || js.isBlank()) continue
                if (nm in registered || dynamicBackups.containsKey(nm)) continue
                dynamicBackups[nm] = WebJsProvider(
                    name = nm, baseUrl = base, jsUrl = js, logo = "", language = "fr",
                )
                if (o.optBoolean("byTmdb", false)) dynamicByTmdb.add(nm)
                added++
            }
            manifestLoadedAt = System.currentTimeMillis()
            Log.i(TAG, "manifeste backups chargé : ${dynamicBackups.size} backups web (+$added)")
        } finally {
            manifestMutex.unlock()
        }
    }

    // Params de query qui portent l'IDENTITÉ d'un serveur (à GARDER pour la dédup) :
    //   ex kokoflix.lol/tokyo_go.php?id=X vs ?id=Y = serveurs DIFFÉRENTS. On ne retire
    //   que les tokens de signature (t/token/expires/sig…) qui varient pour un même flux.
    private val IDENTITY_QS = setOf("id", "url", "e", "v", "file", "link", "ep", "episode")

    /** Bucket langue déduit du nom du serveur (vostfr/vo/vf) — pour ne jamais fusionner
     *  des langues différentes lors de la dédup (user "pas fusionner les VOSTFR avec les FR"). */
    private fun langBucket(name: String): String {
        val n = name.lowercase()
        return when {
            n.contains("vostfr") || n.contains("vost") || n.contains("sous-titr") -> "vostfr"
            Regex("""(^|[^a-z])vo([^a-z]|$)""").containsMatchIn(n) ||
                n.contains(Regex("""\b(raw|eng|english|jap|vosa)\b""")) -> "vo"
            else -> "vf"
        }
    }

    /** Normalise une URL src pour la dédup : host+path + params d'identité, SANS les
     *  tokens de signature. Fusionne les URLs vraiment identiques (même contenu, token
     *  différent) mais garde distincts les serveurs différents (résolveurs ?id=…). */
    private fun normSrc(src: String): String {
        val noFrag = src.substringBefore("#").trim()
        val base = noFrag.substringBefore("?").trimEnd('/').lowercase()
        val query = noFrag.substringAfter("?", "")
        if (query.isBlank()) return base
        val idParts = query.split("&").filter {
            it.substringBefore("=").lowercase() in IDENTITY_QS
        }.map { it.lowercase() }.sorted()
        return if (idParts.isEmpty()) base else "$base?${idParts.joinToString("&")}"
    }

    private val TITLE_STOPWORDS = setOf(
        "le", "la", "les", "un", "une", "des", "du", "de", "et", "the", "and", "of",
        "a", "an", "to", "in", "on", "saison", "season", "partie", "part", "vol", "tome",
        // 2026-07-04 (user "pas de mauvais films") : mots de DÉCORATION (langue/qualité/
        //   format) ignorés dans le matching — pas des mots d'identité. Permet « Naruto VF
        //   Complet » ≈ « Naruto » sans confondre avec « Naruto Uzumaki ».
        "vostfr", "truefrench", "french", "multi", "complet", "complete", "streaming",
        "stream", "gratuit", "film", "films", "serie", "series", "episode", "integrale",
        "integral", "voir", "regarder",
        // 2026-07-06 (socle TMDB — matching permissif sur décorations) : qualité/format/
        //   hébergeur/langue qui polluent les titres des sites → ignorés dans l'identité.
        "vff", "vfq", "vff", "hdlight", "hdrip", "webrip", "bdrip", "dvdrip", "bluray",
        "hdtv", "web", "uptobox", "1fichier", "uqload", "vostf", "vosta", "vostfr",
        "saga", "trilogie", "vol", "annee", "streamvf", "hda", "uhd",
    )

    /** Mots significatifs d'un titre (sans accents/ponctuation/stopwords, ≥ 3 lettres).
     *  2026-07-06 : retire aussi les TOKENS D'ANNÉE (19xx/20xx) — « Naruto 2022 » ≈ « Naruto »
     *  au niveau de l'identité. L'année reste comparée SÉPARÉMENT (gate discriminant) pour
     *  distinguer « Naruto 2002 » de « Naruto 2023 ». */
    private val YEAR_TOKEN = Regex("^(19|20)\\d{2}$")
    internal fun sigWords(s: String): Set<String> = java.text.Normalizer
        .normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "")             // strip accents (combining marks) → "é"→"e"
        .replace(Regex("[^a-zA-Z0-9 ]"), " ")     // other non-alphanum → space separator
        .lowercase()
        .split(Regex("\\s+"))
        .filter { it.length >= 3 && it !in TITLE_STOPWORDS && !YEAR_TOKEN.matches(it) }
        .toSet()

    /** Année (19xx/20xx) présente dans un titre, ou null. Sert au gate discriminant. */
    private fun yearIn(s: String): Int? =
        Regex("\\b(19|20)\\d{2}\\b").find(s)?.value?.toIntOrNull()

    /**
     * 2026-07-06 — Match d'ŒUVRE (remplace le matching par titre unique dans la boucle
     * backups). Permissif sur décorations/année neutre, strict sur l'identité + année
     * discriminante :
     *   - le candidat doit matcher UN des titres connus (TMDB : FR + original + alternatifs) ;
     *   - si le candidat porte une année ET qu'on connaît l'année cible → écart ≤ 1 sinon rejet
     *     (« Naruto 2002 » ≠ « Naruto 2023 »). Année absente → on ne rejette pas là-dessus.
     */
    /** 2026-07-06 (user "recherche stricte TMDB — le matching backup ramène la mauvaise saison") :
     *  rejette un candidat backup dont le TITRE déclare une saison DIFFÉRENTE de celle demandée
     *  (ex : "Jujutsu Kaisen Saison 2" pour une requête S1). Si le candidat ne déclare aucune
     *  saison → ACCEPTÉ (beaucoup de fiches S1 n'ont pas de "Saison 1"). Films → toujours ok. */
    fun seasonTitleOk(candidateTitle: String, isMovieTarget: Boolean, targetSeason: Int): Boolean {
        if (isMovieTarget) return true
        val m = Regex("""(?i)\bsaison\s*(\d+)|\bseason\s*(\d+)|\bs(\d+)\b""").find(candidateTitle) ?: return true
        val declared = (m.groupValues[1].ifBlank { m.groupValues[2].ifBlank { m.groupValues[3] } }).toIntOrNull() ?: return true
        return declared == targetSeason
    }

    fun workMatches(candidateTitle: String, knownTitles: Collection<String>, targetYear: Int?, isMovie: Boolean): Boolean {
        // Gate année UNIQUEMENT pour les films (remakes : « Dune 1984 » ≠ « Dune 2021 »).
        //   Pour les SÉRIES, l'année n'est pas fiable (une série longue s'étale sur des années,
        //   un site peut afficher l'année d'un arc/saison ≠ année de début TMDB) → pas de gate.
        if (isMovie) {
            val cy = yearIn(candidateTitle)
            if (cy != null && targetYear != null && kotlin.math.abs(cy - targetYear) > 1) return false
        }
        return knownTitles.any { it.isNotBlank() && titleMatches(candidateTitle, it) }
    }

    /**
     * 2026-07-09 (user « VoirAnime/CoflixWiki matchent n'importe quoi, faut être ultra strict ») :
     * variante BIDIRECTIONNELLE de workMatches. Exige que le titre candidat et le titre connu se
     * couvrent MUTUELLEMENT (aucun mot significatif en trop d'un côté NI de l'autre). Rejette les
     * matches SOUS-ENSEMBLE (« Solo » ⊄ « Solo Leveling », « Bridge » ⊄ « Bridge to Terabithia »)
     * que la version simple laissait passer via la couverture ≥50%.
     */
    fun workMatchesStrict(candidateTitle: String, knownTitles: Collection<String>, targetYear: Int?, isMovie: Boolean): Boolean {
        if (isMovie) {
            val cy = yearIn(candidateTitle)
            if (cy != null && targetYear != null && kotlin.math.abs(cy - targetYear) > 1) return false
        }
        return knownTitles.any { it.isNotBlank() && titleMatches(candidateTitle, it) && titleMatches(it, candidateTitle) }
    }

    /**
     * 2026-07-02 (user "trop radical, on a éliminé des bons serveurs Wiflix") : garde
     * anti-faux-match PAR MOTS-CLÉS PARTAGÉS (permissive). Garde les vrais matches (« Le
     * Seigneur des Anneaux » et ses variantes de sous-titre), ne rejette QUE les films
     * sans mot commun (ex Marvel). Seuil : ≥ 50% des mots du titre le plus court partagés.
     */
    fun titleMatches(candidate: String, query: String): Boolean {
        val cw = sigWords(candidate)
        val qw = sigWords(query)
        if (cw.isEmpty() || qw.isEmpty()) {
            // Fallback sous-chaîne si un titre n'a aucun mot significatif (titres très courts)
            val nc = candidate.lowercase().replace(Regex("[^a-z0-9]"), "")
            val nq = query.lowercase().replace(Regex("[^a-z0-9]"), "")
            if (nc.isEmpty() || nq.isEmpty()) return false
            // 2026-07-07 : garde anti-faux-match titres ultra-courts (user "K.O." matchait
            //   n'importe quoi contenant "ko"). Si le plus court des deux a ≤ 2 chars → exact
            //   seulement. Entre 3-4 chars → contains OK si le candidat ne dépasse pas 2× la
            //   longueur (évite "ko" dans "knockout" mais accepte "k.o." vs "k o").
            val shorter = minOf(nc.length, nq.length)
            return if (shorter <= 2) {
                nc == nq
            } else if (shorter <= 4) {
                nc == nq || (nc.contains(nq) && nc.length <= nq.length * 2) || (nq.contains(nc) && nq.length <= nc.length * 2)
            } else {
                nc.contains(nq) || nq.contains(nc)
            }
        }
        // 2026-07-03 (user "si je lance Astérix et Obélix Mission Cléopâtre faut pas
        //   que j'aie Mission Impossible ; faut le nom exact" + "peut être écrit
        //   différemment/anglais/japonais, ça peut être peaufiné") : STRICT sur
        //   l'identité, tolérant sur l'ORTHOGRAPHE. Deux mots "matchent" s'ils sont
        //   égaux OU si l'un est préfixe de l'autre (≥4 lettres) → gère pluriels/
        //   variantes ("titan"/"titans", "dragonball"/"dragon"). (Le pont FR↔EN↔JP
        //   se fait via les titres alternatifs TMDB, en amont — voir matchAnyTitle.)
        fun wordMatch(a: String, b: String): Boolean {
            if (a == b) return true
            val s = if (a.length <= b.length) a else b
            val l = if (a.length <= b.length) b else a
            // préfixe ≥4 lettres ET écart ≤2 → pluriel/variante ("titan"/"titans"),
            //   mais PAS "titan"/"titanic" (+2 ok mais bon) ni "man"/"manga".
            return s.length >= 4 && l.startsWith(s) && (l.length - s.length) <= 2
        }
        // 2026-07-04 (user "Naruto ≠ Naruto Uzumaki, pas de mauvais serveurs") : STRICT.
        //   Un mot d'IDENTITÉ du candidat ABSENT de la requête = mauvais film → rejet direct.
        //   (« Naruto Uzumaki »/« Naruto Shippuden » → « uzumaki »/« shippuden » en trop →
        //   rejetés pour la requête « Naruto ».) On accepte l'exact + les variantes de
        //   sous-titre plus courtes (candidat ⊆ requête, ex « Astérix Mission Cléopâtre »).
        val extra = cw.filter { c -> qw.none { q -> wordMatch(c, q) } }
        if (extra.isNotEmpty()) return false
        // Le candidat couvre ≥50% des mots de la requête (évite un match trop générique).
        val qCovered = qw.count { q -> cw.any { c -> wordMatch(q, c) } }
        return qCovered.toDouble() / qw.size >= 0.5
    }

    /**
     * 2026-07-03 : matching tolérant multi-langue. Le titre du site peut être écrit en
     * FR, EN ou JP (romaji) alors qu'on cherche avec le titre TMDB d'une langue. On
     * accepte le candidat s'il matche N'IMPORTE LEQUEL des titres connus de l'œuvre
     * (titre principal + titres alternatifs TMDB). Reste STRICT : chaque comparaison
     * passe par [titleMatches] (donc "Mission Impossible" ne matche aucun titre
     * d'"Astérix … Mission Cléopâtre").
     */
    fun matchesAnyTitle(candidate: String, queryTitles: Collection<String>): Boolean =
        queryTitles.any { it.isNotBlank() && titleMatches(candidate, it) }

    // 2026-07-04 (user "Movix · VOE, PAS Movix · Wiflix · VOE") : le label = nom du PROVIDER
    //   source (Movix, Cloudstream…) + le NOM DU SERVEUR réel (dernier segment de s.name),
    //   SANS le site intermédiaire d'où le provider a tiré le serveur. Ex si Movix renvoie
    //   « Wiflix · VOE » → on garde « Movix · VOE ». Si s.name est déjà un simple host → inchangé.
    private fun wrap(source: String, s: Video.Server): Video.Server {
        val host = s.name.substringAfterLast(" · ").trim().ifBlank { s.name }
        return Video.Server(
            id = "$PREFIX$source::${s.id}",
            name = "$source · $host",
            src = s.src,
            mirrors = s.mirrors,
        )
    }

    /** Extrait (titre, année, saison, épisode) exploitables pour les lookups par titre. */
    private data class Key(
        val title: String,
        val year: Int?,
        val season: Int,
        val episode: Int,
        val isMovie: Boolean,
    )

    // 2026-07-04 (reconnexion registre — phase 1) : webJsByTitle DÉSACTIVÉ — il appelait
    //   WebJsProvider.searchServersByTitle (méthode non restaurée). Les backups WebJS anime
    //   (DessinAnime) seront rebranchés quand cette méthode reviendra. FrenchAnime étant natif,
    //   il est déjà couvert par la boucle générique.

    /** Titre de secours dérivé de l'id provider quand videoType.title est vide (cas
     *  AnimeSama : « mushoku-tensei/saison1/vostfr/1 » → « mushoku tensei »). */
    fun titleFromId(id: String): String {
        val firstSeg = id.substringBefore("/").substringBefore("|").substringBefore("#")
        val noNum = firstSeg.replace(Regex("^\\d+-"), "")   // retire préfixe TMDB numérique
        return noNum.replace(Regex("[-_]+"), " ").replace(Regex("\\s+"), " ").trim()
    }

    // 2026-07-09 : nettoie les décorations Wiflix/provider du titre avant recherche TMDB/backup.
    // Ex: "Spider-Noir (Version Couleur) – Saison 1" → "Spider-Noir"
    // Sans ça, TMDB Search.multi refuse le titre décoré → tmdbId null → backups vides.
    private fun cleanTitle(raw: String): String {
        var t = raw
        // Retire les blocs entre parenthèses : (Version Couleur), (VF), (Film), (Anime), etc.
        t = t.replace(Regex("\\s*\\([^)]*\\)"), "")
        // Retire "– Saison N" / "- Saison N" en fin (la saison est déjà dans key.season)
        t = t.replace(Regex("\\s*[–-]\\s*[Ss]aison\\s*\\d+.*$"), "")
        // Retire le suffixe .html (résidu slug Wiflix)
        t = t.replace(Regex("\\.html$"), "")
        return t.trim()
    }

    private fun keyOf(videoType: Video.Type, titleHint: String?): Key = when (videoType) {
        is Video.Type.Movie -> Key(
            title = cleanTitle(videoType.title.ifBlank { titleHint.orEmpty() }),
            year = videoType.releaseDate.take(4).toIntOrNull(),
            season = 0, episode = 0, isMovie = true,
        )
        is Video.Type.Episode -> Key(
            title = cleanTitle(videoType.tvShow.title.ifBlank { titleHint.orEmpty() }),
            year = videoType.tvShow.releaseDate?.take(4)?.toIntOrNull(),
            // 2026-07-10 (user AnimeSama : saison lue `s0` → aucun backup ne matchait) : la saison 0
            //   (non précisée, ex. anime single-saison AnimeSama) est traitée comme SAISON 1 UNIQUEMENT
            //   pour les backups. AnimeSama natif garde son `0` (il joue via son propre id d'épisode,
            //   il n'utilise pas key.season). Bénéficie aux DEUX familles : scrapers (FrenchManga
            //   seasonOk → « Saison 1 ») ET providers TMDB (Embed/Movix/Cloudstream/Frembed → API saison 1).
            season = videoType.season.number.let { if (it <= 0) 1 else it },
            episode = videoType.number,
            isMovie = false,
        )
    }

    /**
     * Interroge TOUS les backups en parallèle, dédupliqués à la source. Flow progressif :
     * chaque backup émet dès qu'il a répondu ; les doublons (même URL) ne sont émis qu'une fois.
     *
     * @param tmdbId  id TMDB si connu (Nakios/Movix/Cloudstream en ont besoin) ; sinon null.
     * @param videoType type courant (Movie/Episode) — sert au lookup par titre + saison/épisode.
     * @param exclude noms de sources à SAUTER (le provider appelant + sources déjà couvertes).
     */
    fun fetchAll(
        tmdbId: String?,
        videoType: Video.Type,
        exclude: Set<String> = emptySet(),
        titleHint: String? = null,
        // 2026-07-06 (user « skip backups non-anime quand le provider est un anime ») :
        //   sur un provider du groupe ANIME (AnimeSama, FrenchManga…), les backups films/séries
        //   (Cloudstream/Movix/Wiflix/FrenchStream/Nakios/Coflix…) ne trouvent quasi jamais le
        //   bon anime JP et gaspillent réseau/CPU pendant la lecture. Si true : on NE lance QUE
        //   les backups ANIME (groupe ANIME dans la boucle générique) + Embed (Videasy VOSTFR).
        isAnimeProvider: Boolean = false,
    ): Flow<List<Video.Server>> = channelFlow {
        val key = keyOf(videoType, titleHint)

        // 2026-07-04 : si tmdbId est null mais qu'on a un titre, on RÉSOUT le tmdbId
        //   via TMDB Search.multi → débloque Nakios/Movix/Embed/Webflix pour les
        //   providers à slug (FrenchAnime, AnimeSama, FrenchManga, etc.).
        val resolvedTmdbId: String? = run {
            val initial: String? = if (!tmdbId.isNullOrBlank()) {
                tmdbId
            } else if (key.title.length >= 2) {
                try {
                    val results = TMDb3.Search.multi(key.title, language = "fr-FR")
                    val wantMovie = key.isMovie
                    // 2026-07-10 (user « les backups VOD ne remontent pas sur les anime ») : match
                    //   « SOUS-TITRE » — accepte un titre TMDB qui COMMENCE exactement par les mots du
                    //   titre cherché (ex. AnimeSama « Mushoku Tensei » ⊂ TMDB « Mushoku Tensei: Jobless
                    //   Reincarnation »). Sûr (préfixe de mots exact, pas un simple contains), débloque
                    //   la résolution du tmdbId → donc TOUS les backups VOD (Movix/Cloudstream/Embed/
                    //   Frembed/Nakios) sur les épisodes d'anime, sans desserrer titleMatches ailleurs.
                    val qWords = key.title.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
                        .split(" ").filter { it.isNotBlank() }
                    val subtitlePrefix = fun(cand: String): Boolean {
                        val cw = cand.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
                            .split(" ").filter { it.isNotBlank() }
                        return qWords.isNotEmpty() && cw.size >= qWords.size && cw.take(qWords.size) == qWords
                    }
                    // 2026-07-07 : valider que le titre TMDB retourné matche bien la recherche
                    // (évite qu'un titre court/ambigu comme « Crash » résolve le mauvais film)
                    val match = results.results.firstOrNull { item ->
                        val itemTitle = when {
                            wantMovie && item is TMDb3.Movie -> item.title
                            !wantMovie && item is TMDb3.Tv -> item.name
                            else -> null
                        }
                        itemTitle != null && (titleMatches(itemTitle, key.title) || subtitlePrefix(itemTitle))
                    }
                    val id = when (match) {
                        is TMDb3.Movie -> match.id.toString()
                        is TMDb3.Tv -> match.id.toString()
                        else -> null
                    }
                    if (id != null) Log.i(TAG, "fetchAll tmdbId RÉSOLU '$id' depuis titre '${key.title}'")
                    id
                } catch (e: Exception) {
                    Log.w(TAG, "fetchAll résolution tmdbId KO: ${e.message}")
                    null
                }
            } else null

            // 2026-07-09 (FIX CENTRAL) : pour un ÉPISODE dont l'id a été PASSÉ par le provider
            //   natif, cet id est PARFOIS l'id de l'ÉPISODE TMDB (≠ id SÉRIE) → tous les backups
            //   par id série + saison + épisode (Nakios/Webflix/Movix/Embed/Frembed) se prennent
            //   un 404 ET l'enrichissement titres rate. On VALIDE que c'est bien un id série ;
            //   sinon on RÉSOUT le vrai id série via titre+année. (Vérifié : FROM S2E2 avec l'id
            //   reçu = 404 ; avec l'id série 124364 = 5 serveurs.) On ne valide QUE le cas
            //   « épisode + id passé » (les id résolus par recherche sont déjà des id série).
            if (!key.isMovie && !tmdbId.isNullOrBlank() && !initial.isNullOrBlank()) {
                val idInt = initial.toIntOrNull()
                val validSeries = idInt != null && runCatching {
                    TMDb3.TvSeries.details(seriesId = idInt, language = "fr-FR"); true
                }.getOrDefault(false)
                if (!validSeries) {
                    val corrected = runCatching {
                        val clean = TitleNormalizer.cleanForTmdbSearch(key.title).ifBlank { key.title }
                        (TmdbUtils.getTvShow(clean, key.year, "fr-FR")
                            as? com.streamflixreborn.streamflix.models.TvShow)?.id
                    }.getOrNull()?.takeIf { it.isNotBlank() }
                    if (corrected != null) {
                        Log.i(TAG, "resolvedTmdbId CORRIGÉ (id épisode→série): '$initial' → '$corrected'")
                        corrected
                    } else initial
                } else initial
            } else initial
        }

        // 2026-07-06 (socle TMDB — match multi-langue) : on récupère les titres OFFICIELS de
        //   l'œuvre (FR + original EN/JP) via TMDB. Beaucoup de sites (surtout animes) indexent
        //   sous le titre anglais/japonais → chercher/matcher uniquement le titre FR loupe tout.
        //   Sûr : ce sont tous des titres de la MÊME fiche tmdbId → jamais une mauvaise œuvre.
        // 2026-07-08 : ajout des TITRES ALTERNATIFS TMDB (alternative_titles, pays FR).
        //   Certains sites indexent sous un titre régional/commercial différent du titre TMDB
        //   officiel (ex: « Bêêêêtective Privé » au lieu de « Les Moutons détectives »).
        //   append_to_response=alternative_titles = même requête, zéro appel API supplémentaire.
        val knownTitles = linkedSetOf<String>()
        if (key.title.isNotBlank()) knownTitles.add(key.title)
        // 2026-07-09 : détection contenu anime par la langue originale TMDB.
        //   Quand originalLanguage="ja" → le contenu est japonais (anime/drama).
        //   On traite comme anime → skip les backups non-anime (CoflixWiki, Moviebox,
        //   Papadustream…) qui ne trouvent rien et polluent avec des faux positifs
        //   (ex: film "Apple" sur un anime "Chainsmoker Cat"). Fonctionne QUEL QUE
        //   SOIT le provider natif (NetMirror, AnimeSama, etc.).
        var isAnimeContent = false
        if (!resolvedTmdbId.isNullOrBlank()) {
            try {
                val idInt = resolvedTmdbId.toIntOrNull()
                if (idInt != null) {
                    if (key.isMovie) {
                        val d = TMDb3.Movies.details(
                            movieId = idInt,
                            language = "fr-FR",
                            appendToResponse = listOf(TMDb3.Params.AppendToResponse.Movie.ALTERNATIVE_TITLES),
                        )
                        if (d.title.isNotBlank()) knownTitles.add(d.title)
                        if (d.originalTitle.isNotBlank()) knownTitles.add(d.originalTitle)
                        // Titres alternatifs FR + JP (romaji pour anime, ex: "Yani Neko").
                        // 2026-07-09 : JP ajouté car VoirAnime/etc. indexent par romaji,
                        //   absent de knownTitles quand on filtre FR seul → 0 résultat.
                        val altCountries = setOf("FR", "JP")
                        d.alternativeTitles?.all()
                            ?.filter { (it.iso31661?.uppercase() ?: "") in altCountries }
                            ?.mapNotNull { it.title?.takeIf { t -> t.isNotBlank() } }
                            ?.forEach { knownTitles.add(it) }
                        // Détection anime : langue originale japonaise
                        if (d.originalLanguage == "ja") isAnimeContent = true
                    } else {
                        val d = TMDb3.TvSeries.details(
                            seriesId = idInt,
                            language = "fr-FR",
                            appendToResponse = listOf(TMDb3.Params.AppendToResponse.Tv.ALTERNATIVE_TITLES),
                        )
                        if (d.name.isNotBlank()) knownTitles.add(d.name)
                        if (d.originalName.isNotBlank()) knownTitles.add(d.originalName)
                        // Titres alternatifs FR + JP (romaji)
                        val altCountries = setOf("FR", "JP")
                        d.alternativeTitles?.all()
                            ?.filter { (it.iso31661?.uppercase() ?: "") in altCountries }
                            ?.mapNotNull { it.title?.takeIf { t -> t.isNotBlank() } }
                            ?.forEach { knownTitles.add(it) }
                        // Détection anime : langue originale japonaise
                        if (d.originalLanguage == "ja") isAnimeContent = true
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "knownTitles TMDB details KO: ${e.message}")
            }
        }
        // 2026-07-09 : si le contenu est japonais (anime), traiter comme anime
        //   QUEL QUE SOIT le provider natif. Évite CoflixWiki/Moviebox/Papadustream
        //   qui polluent avec des faux positifs sur du contenu anime.
        val effectiveAnime = isAnimeProvider || isAnimeContent
        if (isAnimeContent && !isAnimeProvider) {
            Log.i(TAG, "fetchAll contenu ANIME détecté (originalLanguage=ja) → effectiveAnime=true")
        }

        Log.i(TAG, "fetchAll title='${key.title}' knownTitles=$knownTitles tmdbId=$resolvedTmdbId year=${key.year} season=${key.season} ep=${key.episode}")
        val seen = ConcurrentHashMap.newKeySet<String>()

        // Dédup (par LANGUE + URL normalisée, ne fusionne JAMAIS VF/VOSTFR/VO) + envoi.
        //   Extrait de emit() pour être réutilisable par la boucle CF séquentielle.
        fun pushServers(source: String, servers: List<Video.Server>) {
            if (servers.isEmpty()) { Log.i(TAG, "$source → 0 (vide/timeout)"); return }
            val fresh = servers.filter { it.src.isNotBlank() && seen.add(langBucket(it.name) + "|" + normSrc(it.src)) }
            Log.i(TAG, "$source → ${fresh.size} neufs / ${servers.size} bruts")
            if (fresh.isNotEmpty()) trySend(fresh.map { wrap(source, it) })
        }

        suspend fun emit(source: String, fetch: suspend () -> List<Video.Server>) {
            if (source in exclude) return
            // 2026-07-10 (user "pouvoir désactiver les backups UN PAR UN, pas tous d'un coup") :
            //   chaque source de backup a un toggle (Paramètres). Point de gate UNIQUE (tous les
            //   emit dédiés ET la boucle générique passent ici).
            if (!com.streamflixreborn.streamflix.utils.UserPreferences.isBackupSourceEnabled(source)) {
                Log.i(TAG, "$source → DÉSACTIVÉ par l'utilisateur (skip)")
                return
            }
            val servers = try {
                // 60s : ne pas couper une source lente. Sources LOURDES (WebView) via heavyGate.
                if (source in HEAVY_SOURCES) {
                    heavyGate.withPermit { withTimeoutOrNull(60_000) { fetch() } ?: emptyList() }
                } else {
                    withTimeoutOrNull(60_000) { fetch() } ?: emptyList()
                }
            } catch (e: Exception) {
                Log.w(TAG, "$source failed: ${e.message}"); emptyList()
            }
            pushServers(source, servers)
        }

        // ── Sources FEUILLES (pas de sous-backup → aucune récursion) ──────────────
        // 2026-07-06 : backups films/séries — SKIP sur provider anime (P3).
        // 2026-07-08 : ancien Coflix DÉSACTIVÉ (tous les domaines redirigent vers page de dons Telegram).
        // Remplacé par CoflixWiki (coflix.wiki, site refondu, API différente).
        // if (!isAnimeProvider) launch { emit("Coflix") {
        //     if (key.isMovie) CoflixSourceProvider.getMovieSources(key.title, key.year)
        //     else CoflixSourceProvider.getEpisodeSources(key.title, key.year, key.season, key.episode)
        // } }
        // 2026-07-08 : CoflixWiki essaie TOUS les titres connus (TMDB alt inclus) car
        //   le site peut indexer sous un titre régional différent (ex: « Bêêêêtective Privé »
        //   au lieu de « Les Moutons détectives »).
        // 2026-07-09 : effectiveAnime RETIRÉ — DessinAnime héberge du contenu mixte
        //   (anime + films live-action), et le matching strict empêche les faux positifs.
        //   Tous les backups tournent quel que soit le provider/contenu.
        launch { emit("CoflixWiki") {
            var result = emptyList<Video.Server>()
            for (titleTry in knownTitles) {
                if (titleTry.isBlank()) continue
                result = if (key.isMovie) CoflixWikiProvider.getMovieSources(titleTry, key.year)
                          else CoflixWikiProvider.getEpisodeSources(titleTry, key.year, key.season, key.episode)
                if (result.isNotEmpty()) break
            }
            result
        } }
        // 2026-07-10 : ANCIEN emit("Moviebox") par TITRE (getMovieboxSourcesByTitle = h5 search
        //   token-gaté → renvoyait 0 + doublonnait le log) SUPPRIMÉ. Le SEUL Moviebox est le
        //   nouveau emit par tmdbId (plus bas, API mobile signée). "ce qui en reste à part le
        //   nouveau backup" (user).
        // Papadustream ancien (captcha) — RETIRÉ (user 2026-07-07 "le papastream qui a captcha, vire-le").
        // Seul PapadustreamV2 (sans captcha) reste actif.
        // Nakios + Moiflix = via helpers exposés (WebJS). Nakios a besoin du tmdbId.
        if (!resolvedTmdbId.isNullOrBlank()) launch { emit("Nakios") {
            NakiosProvider.fetchNakiosBackupServers(resolvedTmdbId, videoType, key.season, key.episode)
        } }
        // 2026-07-04 (reconnexion registre — phase 1) : sources dédiées dont les méthodes ne
        //   sont pas encore restaurées → DÉSACTIVÉES pour l'instant. Wiflix, Cloudstream et
        //   VoirDrama sont couverts par la BOUCLE GÉNÉRIQUE par titre (retirés du set
        //   `dedicated` plus bas). Moiflix + DessinAnime-webjs seront rebranchés quand leurs
        //   méthodes (fetchMoiflixBackup, WebJsProvider.searchServersByTitle) seront prêtes.
        //   FrenchAnime est NATIF → couvert par la boucle générique.

        // ── Sources AGRÉGATEURS en mode NATIF-SEUL (pas de sous-backup) ───────────
        if (!resolvedTmdbId.isNullOrBlank()) {
            launch { emit("Movix") { MovixProvider.getServersAsBackup(resolvedTmdbId, videoType) } }

            // ── SERVEUR EMBED TMDB — Videasy VOSTFR uniquement ──────────────
            // 2026-07-03 (user "serveurs quasiment illimités" → "que du VF/VOSTFR,
            //   le reste ça sert à rien") : seul Videasy "fr" sert du VOSTFR.
            launch { emit("Embed") {
                // 2026-07-10 (user « Videasy Embed n'est QU'en VOSTFR mais arrive tout le temps en
                //   premier ») : Videasy est 100% API → il gagne la course sur les serveurs VF natifs
                //   (scrapés, plus lents). On lui donne une longueur de retard pour que les VF sortent
                //   d'abord ; il reste dispo, juste plus en tête.
                kotlinx.coroutines.delay(5_000L)
                val tmdbVt: Video.Type = if (key.isMovie) {
                    Video.Type.Movie(
                        id = resolvedTmdbId,
                        title = key.title,
                        releaseDate = key.year?.toString() ?: "",
                        poster = "",
                        imdbId = null,
                    )
                } else {
                    Video.Type.Episode(
                        id = resolvedTmdbId,
                        number = key.episode,
                        title = null,
                        poster = null,
                        overview = null,
                        tvShow = Video.Type.Episode.TvShow(
                            id = resolvedTmdbId,
                            title = key.title,
                            poster = null,
                            banner = null,
                            releaseDate = key.year?.toString(),
                            imdbId = null,
                        ),
                        season = Video.Type.Episode.Season(
                            number = key.season,
                            title = null,
                        ),
                    )
                }
                val servers = mutableListOf<Video.Server>()
                // Videasy "fr" → taggé "VOSTFR" par l'extracteur.
                runCatching { VideasyExtractor().server(tmdbVt, "fr") }.getOrNull()?.let { servers.add(it) }
                if (servers.isNotEmpty()) Log.i(TAG, "Embed TMDB → Videasy VOSTFR pour tmdbId=$resolvedTmdbId")
                servers
            } }

            // ── FREMBED (natif, par tmdbId) ──────────────────────────────────
            // 2026-07-09 (user « Frembed ne remonte rien ») : Frembed interroge son API
            //   UNIQUEMENT par id TMDB (api/films?id=<tmdb>&idType=tmdb ; api/series?id&sa&epi).
            //   La recherche par TITRE de la boucle générique renvoyait des films sans rapport
            //   (« Spider-Man Far From Home »…) → 0. On l'appelle donc DIRECT par tmdbId (et on
            //   le retire du set générique plus bas). Zéro faux positif (identité par id).
            launch { emit("Frembed") {
                // resolvedTmdbId est corrigé en amont (id SÉRIE garanti pour un épisode).
                val tmdbVt: Video.Type = if (key.isMovie) {
                    Video.Type.Movie(
                        id = resolvedTmdbId, title = key.title,
                        releaseDate = key.year?.toString() ?: "", poster = "", imdbId = null,
                    )
                } else {
                    Video.Type.Episode(
                        id = resolvedTmdbId, number = key.episode, title = null, poster = null, overview = null,
                        tvShow = Video.Type.Episode.TvShow(
                            id = resolvedTmdbId, title = key.title, poster = null, banner = null,
                            releaseDate = key.year?.toString(), imdbId = null,
                        ),
                        season = Video.Type.Episode.Season(number = key.season, title = null),
                    )
                }
                com.streamflixreborn.streamflix.providers.FrembedProvider.getServers(resolvedTmdbId, tmdbVt)
            } }

            // ── MOVIEBOX (par tmdbId) — API mobile signée aoneroom ────────────────
            // 2026-07-10 (user "on transforme Moviebox en backup principal pour tous
            //   les autres") : Moviebox rejoint le registre COMME les autres, par tmdbId.
            //   Identité par id (findSubjectId matche titre+année STRICT côté aoneroom) →
            //   zéro faux positif. Flux réels (CDN hakunaymatata + cookies CloudFront),
            //   lus via MovieboxExtractor (court-circuit direct). Films + séries/épisodes.
            launch { emit("Moviebox") {
                val tmdbInt = resolvedTmdbId.toIntOrNull()
                if (tmdbInt == null) emptyList() else {
                    val tmdbVt: Video.Type = if (key.isMovie) {
                        Video.Type.Movie(
                            id = resolvedTmdbId, title = key.title,
                            releaseDate = key.year?.toString() ?: "", poster = "", imdbId = null,
                        )
                    } else {
                        Video.Type.Episode(
                            id = resolvedTmdbId, number = key.episode, title = null, poster = null, overview = null,
                            tvShow = Video.Type.Episode.TvShow(
                                id = resolvedTmdbId, title = key.title, poster = null, banner = null,
                                releaseDate = key.year?.toString(), imdbId = null,
                            ),
                            season = Video.Type.Episode.Season(number = key.season, title = null),
                        )
                    }
                    com.streamflixreborn.streamflix.providers.MovieboxProvider
                        .getMovieboxSourcesByTmdbId(tmdbInt, tmdbVt)
                }
            } }
        }

        // ── ANICLOUD (anime FR, API REST, zéro CF) ──────────────────────────────
        // 2026-07-09 : backup anime-only via anicloud.top. API JSON ouverte (credentials:omit).
        //   Hosts players = sibnet/vidmoly/sendvid/vk → extracteurs existants.
        //   Matching STRICT : tmdb_id exact, sinon titleMatches.
        if (effectiveAnime && key.title.length >= 2) launch { emit("AniCloud") {
            val acBase = "https://anicloud.top/api"
            val servers = mutableListOf<Video.Server>()
            try {
                // 1) Recherche par titre
                val searchUrl = "$acBase/search?q=${java.net.URLEncoder.encode(key.title, "UTF-8")}"
                val searchReq = okhttp3.Request.Builder().url(searchUrl)
                    .header("Accept", "application/json")
                    .header("Referer", "https://anicloud.top/")
                    .build()
                val searchBody = manifestHttp.newCall(searchReq).execute().use { it.body?.string() }
                if (searchBody.isNullOrBlank()) { Log.w(TAG, "AniCloud search vide"); return@emit emptyList() }
                val searchJson = org.json.JSONObject(searchBody)
                val dataArr = searchJson.optJSONArray("data")
                if (dataArr == null || dataArr.length() == 0) {
                    Log.i(TAG, "AniCloud search '${key.title}' → 0 résultats")
                    return@emit emptyList()
                }

                // 2) Matching STRICT : d'abord tmdb_id exact, sinon titleMatches
                var matchIdx = -1
                // a) tmdb_id exact (le plus fiable)
                if (!resolvedTmdbId.isNullOrBlank()) {
                    for (i in 0 until dataArr.length()) {
                        val item = dataArr.getJSONObject(i)
                        val acTmdb = item.optString("tmdb_id", "")
                        if (acTmdb.isNotBlank() && acTmdb == resolvedTmdbId) {
                            matchIdx = i; break
                        }
                    }
                }
                // b) titleMatches strict (si pas de match tmdb_id)
                if (matchIdx < 0) {
                    for (i in 0 until dataArr.length()) {
                        val item = dataArr.getJSONObject(i)
                        val acName = item.optString("name", "")
                        if (acName.isNotBlank() && knownTitles.any { titleMatches(acName, it) }) {
                            matchIdx = i; break
                        }
                    }
                }
                if (matchIdx < 0) {
                    Log.i(TAG, "AniCloud search '${key.title}' → ${dataArr.length()} résultats mais AUCUN match strict")
                    return@emit emptyList()
                }

                val matched = dataArr.getJSONObject(matchIdx)
                val acId = matched.optInt("id", -1)
                val acName = matched.optString("name", "")
                val acSections = matched.optInt("sectionsCount", 0)
                Log.i(TAG, "AniCloud MATCH '$acName' id=$acId sections=$acSections")

                // 3) Déterminer si c'est un film ou une série
                val isAcMovie = key.isMovie || matched.optString("type", "").equals("movie", ignoreCase = true)

                // 4) Récupérer les sections (= saisons) via l'anime slug.
                //    2026-07-09 (vérifié en direct sur anicloud.top) : l'endpoint détail EST
                //    `/api/anime-loader?slug=<slug>` (renvoie {..., sections:[...]}). L'ancien
                //    `/api/anime/<slug>` renvoyait le SHELL HTML de la SPA → JSONObject plantait
                //    → 0 serveur AniCloud. Chaque section = {id, name:"Saison N", section_type,
                //    episode_count, languages:{vf,vostfr}}. Les épisodes portent language=vf|vostfr.
                val acSlug = matched.optString("slug", "")
                val animeUrl = "$acBase/anime-loader?slug=$acSlug"
                val animeReq = okhttp3.Request.Builder().url(animeUrl)
                    .header("Accept", "application/json")
                    .header("Referer", "https://anicloud.top/")
                    .build()
                val animeBody = manifestHttp.newCall(animeReq).execute().use { it.body?.string() }
                if (animeBody.isNullOrBlank()) { Log.w(TAG, "AniCloud anime/$acSlug vide"); return@emit emptyList() }
                val animeJson = org.json.JSONObject(animeBody)
                val sections = animeJson.optJSONArray("sections")
                if (sections == null || sections.length() == 0) {
                    Log.w(TAG, "AniCloud anime/$acSlug 0 sections"); return@emit emptyList()
                }

                // 5) Trouver la bonne section (saison)
                //    Pour un film : prendre la section section_type="film", sinon la 1ère.
                //    Pour une série : matching STRICT sur "Saison N" dans le nom,
                //    puis fallback = la section avec le PLUS d'épisodes par langue
                //    (= série principale, ex "Avec fillers" pour Bleach).
                //    2026-07-09 : AVANT, Regex("(\d+)") attrapait le "1" de "Partie 1"
                //    → "Thousand-Year Blood War Partie 1" matchait saison 1 = FAUX.
                var targetSectionId = -1
                if (isAcMovie) {
                    // Préférer la section de type "film" si elle existe
                    for (i in 0 until sections.length()) {
                        val sec = sections.getJSONObject(i)
                        if (sec.optString("section_type", "").equals("film", ignoreCase = true)) {
                            targetSectionId = sec.optInt("id", -1); break
                        }
                    }
                    if (targetSectionId < 0) targetSectionId = sections.getJSONObject(0).optInt("id", -1)
                } else {
                    // a) Matching STRICT : uniquement "Saison N" / "Season N" / "S N"
                    //    PAS "Partie N", "Part N", "Blood War 1", etc.
                    val saisonRegex = Regex("(?:saison|season|s)\\s*(\\d+)", RegexOption.IGNORE_CASE)
                    for (i in 0 until sections.length()) {
                        val sec = sections.getJSONObject(i)
                        val secName = sec.optString("name", "")
                        val m = saisonRegex.find(secName)
                        if (m != null) {
                            val secNum = m.groupValues[1].toIntOrNull() ?: continue
                            if (secNum == key.season) { targetSectionId = sec.optInt("id", -1); break }
                        }
                    }
                    // b) Fallback section_number de l'API (si fourni)
                    if (targetSectionId < 0) {
                        for (i in 0 until sections.length()) {
                            val sec = sections.getJSONObject(i)
                            val secNum = sec.optInt("section_number", -1)
                            if (secNum > 0 && secNum == key.season) {
                                targetSectionId = sec.optInt("id", -1); break
                            }
                        }
                    }
                    // c) Fallback : section de type "saison" avec le PLUS d'épisodes
                    //    par langue (= la série principale, ex "Avec fillers" pour Bleach).
                    //    On vérifie que l'épisode demandé peut exister dedans.
                    if (targetSectionId < 0) {
                        var bestId = -1; var bestCount = 0
                        for (i in 0 until sections.length()) {
                            val sec = sections.getJSONObject(i)
                            val secType = sec.optString("section_type", "")
                            if (!secType.equals("saison", ignoreCase = true)) continue
                            // Compter les épisodes par langue (VF ou VOSTFR, max)
                            val langs = sec.optJSONObject("languages")
                            val perLang = if (langs != null) {
                                maxOf(langs.optInt("vf", 0), langs.optInt("vostfr", 0))
                            } else sec.optInt("episode_count", 0) / 2
                            if (perLang > bestCount) { bestCount = perLang; bestId = sec.optInt("id", -1) }
                        }
                        if (bestId > 0 && bestCount >= key.episode) {
                            targetSectionId = bestId
                            Log.i(TAG, "AniCloud anime/$acSlug saison ${key.season} → fallback section principale (id=$bestId, $bestCount eps/lang)")
                        }
                    }
                    // d) Dernier recours : section unique
                    if (targetSectionId < 0 && sections.length() == 1) {
                        targetSectionId = sections.getJSONObject(0).optInt("id", -1)
                    }
                }
                if (targetSectionId < 0) {
                    Log.i(TAG, "AniCloud anime/$acSlug saison ${key.season} non trouvée dans ${sections.length()} sections")
                    return@emit emptyList()
                }

                // 6) Récupérer les épisodes de cette section
                val epUrl = "$acBase/anime-episodes?sectionId=$targetSectionId"
                val epReq = okhttp3.Request.Builder().url(epUrl)
                    .header("Accept", "application/json")
                    .header("Referer", "https://anicloud.top/")
                    .build()
                val epBody = manifestHttp.newCall(epReq).execute().use { it.body?.string() }
                if (epBody.isNullOrBlank()) { Log.w(TAG, "AniCloud episodes section=$targetSectionId vide"); return@emit emptyList() }
                val epJson = org.json.JSONObject(epBody)
                val episodes = epJson.optJSONArray("episodes")
                if (episodes == null || episodes.length() == 0) {
                    Log.i(TAG, "AniCloud section=$targetSectionId 0 épisodes"); return@emit emptyList()
                }

                // 7) Trouver le(s) épisode(s) correspondant(s) (numéro + toutes langues)
                //    Pour un film : tous les épisodes (souvent un seul). Pour une série : épisode N.
                val targetEps = mutableListOf<org.json.JSONObject>()
                if (isAcMovie) {
                    for (i in 0 until episodes.length()) targetEps.add(episodes.getJSONObject(i))
                } else {
                    for (i in 0 until episodes.length()) {
                        val ep = episodes.getJSONObject(i)
                        if (ep.optInt("episode_number", -1) == key.episode) targetEps.add(ep)
                    }
                }
                if (targetEps.isEmpty()) {
                    Log.i(TAG, "AniCloud section=$targetSectionId épisode ${key.episode} non trouvé")
                    return@emit emptyList()
                }

                // 8) Pour chaque épisode trouvé, récupérer les players
                for (ep in targetEps) {
                    val epId = ep.optInt("id", -1)
                    val epLang = ep.optString("language", "").uppercase()
                    val langLabel = when {
                        epLang.contains("VOSTFR", ignoreCase = true) -> "(VOSTFR)"
                        epLang.contains("VF", ignoreCase = true) || epLang.contains("FRENCH", ignoreCase = true) -> "(VF)"
                        else -> "($epLang)"
                    }
                    val playersUrl = "$acBase/anime-players?episodeId=$epId"
                    val plReq = okhttp3.Request.Builder().url(playersUrl)
                        .header("Accept", "application/json")
                        .header("Referer", "https://anicloud.top/")
                        .build()
                    val plBody = try { manifestHttp.newCall(plReq).execute().use { it.body?.string() } } catch (e: Exception) {
                        Log.w(TAG, "AniCloud players epId=$epId failed: ${e.message}"); null
                    }
                    if (plBody.isNullOrBlank()) continue
                    val plJson = org.json.JSONObject(plBody)
                    val players = plJson.optJSONArray("players") ?: continue

                    for (j in 0 until players.length()) {
                        val pl = players.getJSONObject(j)
                        val playerName = pl.optString("player_name", "Lecteur")
                        val playerUrl = pl.optString("player_url", "")
                        if (playerUrl.isBlank()) continue
                        servers.add(Video.Server(
                            id = playerUrl,
                            name = "AniCloud · $playerName $langLabel",
                            src = playerUrl,
                        ))
                    }
                }
                Log.i(TAG, "AniCloud '$acName' → ${servers.size} serveurs")
            } catch (e: Exception) {
                Log.w(TAG, "AniCloud failed: ${e.javaClass.simpleName}: ${e.message}")
            }
            servers
        } }

        // ── TOUS LES AUTRES PROVIDERS (non-IPTV) par TITRE → couverture COMPLÈTE ──
        //   2026-07-02 (user "tous les backups possibles et imaginables") : on interroge
        //   chaque provider VOD/anime restant (FrenchStream, Frembed, aplouf, NetMirror,
        //   AnimeSama, FrenchManga, VoirAnime, Franime, TMDb…) par recherche titre puis
        //   getServers (BATCH = ne rappelle PAS le registre → pas de récursion). Les
        //   WebJsProviders sont exclus (leur getServers déclenche leur propre XBACKUP →
        //   récursion ; Wiflix est déjà couvert en source dédiée ci-dessus).
        if (key.title.length >= 2) {
            // 2026-07-04 : seuls les providers AVEC une source dédiée ci-dessus sont exclus
            //   de la boucle générique. Wiflix/Cloudstream/FrenchAnime (natifs) reviennent DANS
            //   la boucle générique par titre (matching STRICT). DessinAnime (WebJsProvider)
            //   INCLUS : INLINE_BACKUPS_DISABLED=true empêche toute récursion dans son
            //   getServers → safe comme backup source. Classé WEBVIEW_HEAVY → 2ᵉ vague (5s).
            // 2026-07-08 : Cloudstream RETIRÉ du set dedicated — il utilise l'API MovieBox+
            //   (aoneroom.com), PAS la même API que Movix. Ses serveurs NE sont PAS des doublons.
            //   Le remettre dans la boucle générique (recherche par titre via findSubjectId).
            val dedicated = setOf("CoflixWiki", "Moviebox", "Nakios", "Movix", "Frembed")
            Provider.providers.keys
                .filter { p ->
                    p.name !in exclude && p.name !in dedicated &&
                        // 2026-07-09 (user « remets aplouf dans les backups ») : aplouf RÉACTIVÉ
                        //   comme source de backup (recherche par titre via la boucle générique).
                        // 2026-07-09 (user "retire les serveurs Cloudstream des backups, qu'il soit
                        //   que sur son propre provider") : Cloudstream ne fournit PLUS de backup aux
                        //   autres providers → disponible uniquement sur le provider Cloudstream.
                        !p.name.equals("Cloudstream", ignoreCase = true) &&
                        // 2026-07-09 (décision user) : NetMirror HORS backups — en backup son API
                        //   répond souvent « Video ID not found » (id non résolu) → serveur rouge sur
                        //   du contenu d'autres providers. Il reste sur SON PROPRE onglet, où il
                        //   résout le bon id et joue le vrai film reconstruit.
                        !p.name.equals("NetMirror", ignoreCase = true) &&
                        // 2026-07-08 (user "remets tous les serveurs CF dans la liste normale en dernier") :
                        //   DessinAnime remis dans les backups. Classé WEBVIEW_HEAVY → 2ᵉ vague (5s délai),
                        //   arrive après les backups légers. Précédemment exclu pour test/diagnostic.
                        // !p.name.equals("DessinAnime", ignoreCase = true) &&
                        // 2026-07-09 : Wiflix RÉACTIVÉ dans les backups même sur Movix.
                        //   L'exclusion précédente se basait sur le fait que l'API Movix
                        //   ramène Wiflix côté serveur, mais le user constate que ça ne
                        //   marche pas toujours → il manque des serveurs. Notre propre
                        //   WiflixProvider (backup via boucle générique) reste actif partout.
                        Provider.getGroup(p) != Provider.Companion.ProviderGroup.IPTV
                        // 2026-07-09 : filtre effectiveAnime RETIRÉ de la boucle générique.
                        //   Le matching strict (workMatchesStrict) empêche les faux positifs.
                        //   DessinAnime héberge du contenu mixte (anime + films live-action) →
                        //   bloquer les providers film = perte massive de serveurs (3 au lieu de 20+).
                }
                .forEach { p ->
                    // 2026-07-08 : plus de 2ᵉ vague CF, tous les backups partent ensemble.
                    launch {
                        emit(p.name) {
                        // 2026-07-09 : DIAGNOSTIC complet pour chaque provider de la boucle générique.
                        //   Log le résultat de chaque étape (search, type, matching, resolve, servers)
                        //   pour comprendre POURQUOI un provider ne remonte pas de serveurs.
                        fun typeOk(item: com.streamflixreborn.streamflix.adapters.AppAdapter.Item): Boolean =
                            if (key.isMovie) item is com.streamflixreborn.streamflix.models.Movie
                            else item is com.streamflixreborn.streamflix.models.TvShow

                        val t0 = System.currentTimeMillis()
                        val searchResults = try {
                            p.search(key.title, 1)
                        } catch (e: Exception) {
                            Log.w(TAG, "DIAG [${p.name}] search('${key.title}') EXCEPTION en ${System.currentTimeMillis()-t0}ms: ${e.javaClass.simpleName}: ${e.message}")
                            emptyList()
                        }
                        val searchMs = System.currentTimeMillis() - t0
                        Log.i(TAG, "DIAG [${p.name}] search('${key.title}') → ${searchResults.size} résultats en ${searchMs}ms")
                        if (searchResults.isNotEmpty()) {
                            searchResults.take(5).forEachIndexed { i, item ->
                                val itemTitle = (item as? com.streamflixreborn.streamflix.models.Movie)?.title
                                    ?: (item as? com.streamflixreborn.streamflix.models.TvShow)?.title ?: "?"
                                val itemType = when (item) {
                                    is com.streamflixreborn.streamflix.models.Movie -> "Movie"
                                    is com.streamflixreborn.streamflix.models.TvShow -> "TvShow"
                                    else -> item.javaClass.simpleName
                                }
                                val tok = typeOk(item)
                                val wm = if (tok) workMatches(itemTitle, knownTitles, key.year, key.isMovie) else false
                                val st = if (tok && wm) seasonTitleOk(itemTitle, key.isMovie, key.season) else false
                                Log.i(TAG, "DIAG [${p.name}]   [$i] '$itemTitle' type=$itemType typeOk=$tok workMatch=$wm seasonOk=$st")
                            }
                        }

                        var match = searchResults.firstOrNull { item ->
                            if (!typeOk(item)) return@firstOrNull false
                            val t = (item as? com.streamflixreborn.streamflix.models.Movie)?.title
                                ?: (item as? com.streamflixreborn.streamflix.models.TvShow)?.title
                                ?: return@firstOrNull false
                            workMatchesStrict(t, knownTitles, key.year, key.isMovie) &&
                                seasonTitleOk(t, key.isMovie, key.season)
                        }
                        // 2026-07-08 : essayer TOUS les titres connus (alternatifs TMDB inclus)
                        if (match == null && knownTitles.size > 1) {
                            Log.i(TAG, "DIAG [${p.name}] pas de match primaire, essai titres alternatifs: $knownTitles")
                            for (altQuery in knownTitles) {
                                if (altQuery.equals(key.title, ignoreCase = true)) continue
                                if (altQuery.isBlank()) continue
                                val altResults = try { p.search(altQuery, 1) } catch (_: Exception) { emptyList() }
                                Log.i(TAG, "DIAG [${p.name}] alt search('$altQuery') → ${altResults.size} résultats")
                                match = altResults.firstOrNull { item ->
                                    if (!typeOk(item)) return@firstOrNull false
                                    val t = (item as? com.streamflixreborn.streamflix.models.Movie)?.title
                                        ?: (item as? com.streamflixreborn.streamflix.models.TvShow)?.title
                                        ?: return@firstOrNull false
                                    workMatchesStrict(t, knownTitles, key.year, key.isMovie) &&
                                        seasonTitleOk(t, key.isMovie, key.season)
                                }
                                if (match != null) break
                            }
                        }
                        // 2026-07-09 : FALLBACK « confiance search unique ». Si le provider
                        //   retourne EXACTEMENT 1 résultat du bon type pour la query exacte,
                        //   on lui fait confiance même si workMatches échoue (titre romaji/JP ≠
                        //   titre FR/EN). Ex: FRAnime cherche "Chainsmoker Cat" → 1 résultat
                        //   "Yani Neko" (romaji) car le site a matché via un titre alternatif
                        //   interne. Sûr : 1 seul résultat = pas de confusion possible.
                        //   NE s'applique PAS aux recherches floues (DessinAnime 24 résultats
                        //   = "Chainsaw Man", "Black Cat"… → aucune confiance).
                        if (match == null) {
                            val typedResults = searchResults.filter { typeOk(it) }
                            if (typedResults.size == 1) {
                                val cand = typedResults[0]
                                val trustTitle = (cand as? com.streamflixreborn.streamflix.models.Movie)?.title
                                    ?: (cand as? com.streamflixreborn.streamflix.models.TvShow)?.title ?: "?"
                                // 2026-07-09 (user « VoirAnime matche n'importe quoi, c'est grave ») :
                                //   le fallback « 1 seul résultat = confiance aveugle » laissait passer
                                //   des œuvres SANS AUCUN rapport (le site renvoie 1 résultat non lié).
                                //   On ne fait désormais confiance QUE si le résultat unique partage au
                                //   moins UN mot significatif avec un titre connu ET ne déclare pas une
                                //   AUTRE saison. Sinon → refus (« pas de serveur > mauvais serveur »).
                                val sharesWord = knownTitles.any { kt ->
                                    val kw = sigWords(kt); kw.isNotEmpty() && sigWords(trustTitle).any { it in kw }
                                }
                                val seasonOk = seasonTitleOk(trustTitle, key.isMovie, key.season)
                                if (sharesWord && seasonOk) {
                                    match = cand
                                    Log.i(TAG, "DIAG [${p.name}] FALLBACK confiance search unique: '$trustTitle' (1 résultat typé, mot commun OK)")
                                } else {
                                    Log.i(TAG, "DIAG [${p.name}] FALLBACK REFUSÉ '$trustTitle' (sharesWord=$sharesWord seasonOk=$seasonOk) → 0 serveur")
                                }
                            }
                        }
                        if (match == null) {
                            Log.i(TAG, "DIAG [${p.name}] AUCUN MATCH → 0 serveurs (total ${System.currentTimeMillis()-t0}ms)")
                            return@emit emptyList()
                        }
                        val matchTitle = (match as? com.streamflixreborn.streamflix.models.Movie)?.title
                            ?: (match as? com.streamflixreborn.streamflix.models.TvShow)?.title ?: "?"
                        val matchId = (match as? com.streamflixreborn.streamflix.models.Movie)?.id
                            ?: (match as? com.streamflixreborn.streamflix.models.TvShow)?.id ?: "?"
                        Log.i(TAG, "DIAG [${p.name}] MATCH trouvé: '$matchTitle' id=$matchId → résolution serveurs…")
                        val servers = CrossProviderResolver.resolveAndFetchServers(p, match, videoType, timeoutMs = 30_000)
                        Log.i(TAG, "DIAG [${p.name}] → ${servers.size} serveurs (total ${System.currentTimeMillis()-t0}ms)")
                        servers
                    } }
                }
        }

        // ── Webflix (NATIF, par tmdbId) — MP4 VF direct, CDN drinkoflix (CF géré à la lecture).
        // 2026-07-06 : était DÉSACTIVÉ (« bloque l'extraction »). Réactivé 2026-07-08 avec
        //   DÉLAI 8s pour que les sources rapides (Movix/CoflixWiki/Nakios/1J1F) émettent
        //   leurs serveurs en premier. Webflix arrive en renfort tardif, ne bloque rien.
        if (!resolvedTmdbId.isNullOrBlank()) launch { emit("Webflix") {
            delay(8000)
            WebflixProvider.fetchWebflixBackupServers(resolvedTmdbId, videoType, key.season, key.episode)
        } }
        // ── PapadustreamV2 (NATIF, par TITRE strict) — nouvelle version du site (films+séries).
        // 2026-07-08 : essaie tous les titres connus (alt TMDB inclus).
        if (key.title.length >= 2) launch { emit("Papadustream V2") {
            var result = emptyList<Video.Server>()
            for (titleTry in knownTitles) {
                if (titleTry.isBlank()) continue
                result = PapadustreamV2Provider.fetchPapadustreamV2Backup(titleTry, key.year, key.season, key.episode)
                if (result.isNotEmpty()) break
            }
            result
        } }

        awaitClose { }
    }

    /**
     * Lecture d'un serveur backup (id `bkreg::<source>::<idOrig>`). Ré-aiguille vers le
     * getVideo de la source d'origine ; fallback = Extractor.extract (embeds hosts).
     */
    suspend fun getVideo(server: Video.Server): Video {
        val rest = server.id.removePrefix(PREFIX)
        val source = rest.substringBefore("::")
        val origId = rest.substringAfter("::")
        val orig = Video.Server(id = origId, name = server.name, src = server.src, mirrors = server.mirrors)
        // 2026-07-02 (user "un serveur dédupliqué qui ne marche pas") : peu importe la
        //   SOURCE gardée après dédup, une URL directe sur le CDN Cloudflare drinkoflix
        //   (Webflix/Nakios…) doit se lire avec le cf_clearance → on route TOUJOURS via
        //   NakiosProvider (STEALTH_UA + cookie). Sinon un doublon gardé d'une source sans
        //   cf_clearance donnerait 503 alors qu'une autre l'aurait joué.
        if (orig.src.contains("drinkoflix", ignoreCase = true))
            return NakiosProvider.getVideo(orig)
        return when (source) {
            "Moviebox" -> MovieboxProvider.getVideo(orig)
            // "Papadustream" (V1) supprimé — plus aucun emit V1 (2026-07-10).
            "Nakios" -> NakiosProvider.getVideo(orig)
            "Movix" -> MovixProvider.getVideo(orig)
            "Cloudstream" -> CloudstreamProvider.getVideo(orig)
            "Webflix" -> WebflixProvider.getVideo(orig)
            "Papadustream V2" -> PapadustreamV2Provider.getVideo(orig)
            else -> {
                // Backup web DYNAMIQUE (manifeste hébergé) → son getVideo (WebJsProvider).
                dynamicBackups[source]?.let { dyn ->
                    // CDN protégé Cloudflare drinkoflix (Webflix/Nakios) : lecture via le
                    //   cf_clearance déjà géré par NakiosProvider (STEALTH_UA + cookie).
                    if (orig.src.contains("drinkoflix", ignoreCase = true))
                        return NakiosProvider.getVideo(orig)
                    return dyn.getVideo(orig)
                }
                // Provider générique du registre (FrenchStream, AnimeSama, Frembed…) → son
                //   getVideo. Sinon (Coflix/Moiflix/Dramacool = leaf, hors map) → extraction
                //   générique (embed host / page).
                val prov = Provider.providers.keys.firstOrNull { it.name == source }
                if (prov != null) prov.getVideo(orig)
                else com.streamflixreborn.streamflix.extractors.Extractor.extract(orig.src, orig)
            }
        }
    }
}
