package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.CloudstreamProvider
import com.streamflixreborn.streamflix.providers.CoflixSourceProvider
import com.streamflixreborn.streamflix.providers.CoflixWikiProvider
import com.streamflixreborn.streamflix.providers.DessinAnimeNetProvider
import com.streamflixreborn.streamflix.providers.MovieboxProvider
import com.streamflixreborn.streamflix.providers.MovixProvider
import com.streamflixreborn.streamflix.providers.NakiosProvider
import com.streamflixreborn.streamflix.providers.PapadustreamV2Provider
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.VoirDramaProvider
import com.streamflixreborn.streamflix.providers.WebJsProvider
import com.streamflixreborn.streamflix.providers.WebflixProvider
import com.streamflixreborn.streamflix.providers.WiflixProvider
import com.streamflixreborn.streamflix.providers.AdkamiProvider
import com.streamflixreborn.streamflix.providers.IAnimeProvider
import com.streamflixreborn.streamflix.providers.VostfreeProvider
import com.streamflixreborn.streamflix.providers.YablomProvider
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
    // ⚠ 2026-08-11 (user : « j'ai vu ok.ru, Cloudstream qui sont toujours présents » après un
    //   « tout désactiver ») : QUATRE sources émises manquaient à cette liste — `archive.org`,
    //   `Moviebox`, `NetMirror` et `ok.ru`. Comme `isBackupSourceEnabled` renvoyait `true` pour
    //   tout nom absent d'ici, elles étaient INDÉSACTIVABLES et n'apparaissaient nulle part.
    //   Vérification faite en extrayant les 23 `emit("…")` du fichier et en les comparant à
    //   cette liste — c'est la seule méthode fiable, l'œil ne suffit pas sur 2 000 lignes.
    //   ⚠ RÈGLE : tout nouvel `emit("X")` DOIT ajouter "X" ici, sinon la source échappe au
    //   réglage utilisateur sans que personne ne s'en aperçoive.
    val BACKUP_SOURCES: List<Pair<String, String>> = listOf(
        "ONYX" to "ONYX (mes fichiers hébergés)",
        "Partage" to "Partage de la communauté (fichiers des amis)",
        "Vegeta VOD" to "Vegeta VOD (films/séries FR des serveurs Vegeta)",
        "Cloudstream" to "Cloudstream",
        "ok.ru" to "ok.ru (VF/VOSTFR)",
        "archive.org" to "archive.org (vieux films/séries)",
        "Moviebox" to "Moviebox",
        "NetMirror" to "NetMirror (Netflix/Prime/Hotstar)",
        "Movix" to "Movix",
        "Frembed" to "Frembed",
        "Vidzy" to "Vidzy (par TMDB)",
        "Nakios" to "Nakios",
        "LoiFlix" to "LoiFlix",
        "Nabistream" to "Nabistream (dramas)",
        "Purstream" to "Purstream (films/séries FR)",
        "Rutube" to "Rutube (films/séries FR)",
        "TV Hub" to "TV Hub (France.tv/Arte gratuit)",
        "FileSearch" to "FileSearch (fichiers directs)",
        "Webflix" to "Webflix",
        "Yablom" to "Yablom (films FR)",
        "Vostfree" to "Vostfree (animes VF/VOSTFR)",
        "iAnime" to "iAnime (animes VF/VOSTFR)",
        "Adkami" to "Adkami (animes VOSTFR, adulte)",
        "Coflix Boston" to "Coflix Boston",
        "CoflixWiki" to "CoflixWiki",
        "DessinAnimeNet" to "DessinAnime.net",
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

    /**
     * Pays dont on récupère les titres alternatifs TMDB pour nourrir `knownTitles`.
     *
     * 2026-08-07 — US/GB ajoutés. Cas prouvé, *House of the Dragon* (tmdbId 94997) : flemmix
     *   indexe la fiche « Game Of Thrones: House of the Dragon », et TMDB connaît EXACTEMENT
     *   ce titre… mais sous `US`. Filtrés sur FR+JP seuls, on ne l'avait pas dans
     *   `knownTitles` → la comparaison stricte échouait et Wiflix rendait 0 serveur sur une
     *   série qu'il possède bel et bien.
     *   ⚠ On n'assouplit RIEN : la comparaison reste une ÉGALITÉ. On élargit seulement la
     *   liste des titres OFFICIELS de la MÊME fiche TMDB — donc aucun risque de mauvais match.
     */
    private val ALT_COUNTRIES = setOf("FR", "JP", "US", "GB")

    /**
     * Sources dont chaque requête passe par un challenge Cloudflare : elles sont
     * structurellement lentes, pas défaillantes. Elles ont droit à un budget de résolution
     * plus large UNE FOIS la fiche identifiée (cf. `resolveAndFetchServers` plus bas).
     */
    private val SLOW_CF_SOURCES = setOf("Wiflix")

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
        "Coflix Boston", "CoflixWiki", "Nakios", "Moiflix", "DessinAnime", "FrenchAnime", "Wiflix", "Dramacool"
    )
    // 2026-08-02 : 2 → 4 jetons sur les appareils à faible mémoire. Avec 8 sources lourdes et
    //   seulement 2 places, une source lente formait immédiatement une file d'attente : les
    //   suivantes ne pouvaient pas émettre « dans la foulée ». 4 reste prudent — le garde-fou
    //   mémoire d'origine visait les WebView simultanées, or plusieurs de ces sources
    //   (Nakios, CoflixWiki…) sont en réalité de simples appels HTTP, sans WebView.
    private val heavyGate = Semaphore(if (LOW_RAM) 4 else 16)

    // ── VAGUES DE LANCEMENT (2026-08-08) ────────────────────────────────────────────
    //   Classement établi sur MESURE (film « Obsession », Chromecast), pas à l'intuition.
    //   VAGUE 1 : répondent en 1 à 5 s — API ou id direct. Elles partent tout de suite.
    //   VAGUE 3 : 19 à 31 s — scraping multi-pages derrière Cloudflare. Deux secondes de
    //             retard ne changent rien pour elles et libèrent la machine pour la vague 1.
    //   VAGUE 2 : tout le reste (5 à 10 s), léger décalage.
    private val SOURCES_VAGUE_1 = setOf(
        // 2026-08-17 : mes propres fichiers passent devant tout le reste.
        "ONYX", "Partage", "Vegeta VOD",
        "NetMirror", "Vidzy", "Frembed", "Movix", "Embed", "Yablom",
        "FileSearch", "Nabistream", "Webflix", "TV Hub", "CoflixWiki", "Nakios", "Rutube",
    )
    private val SOURCES_VAGUE_3 = setOf(
        "aplouf", "FrenchStream", "1Jour1Film", "Papadustream V2", "Papadustream",
        "Wiflix", "VoirDrama", "Moiflix", "Dramacool",
    )
    private fun retardDeVague(source: String): Long = when (source) {
        in SOURCES_VAGUE_1 -> 0L
        in SOURCES_VAGUE_3 -> 2_000L
        else -> 700L
    }

    /** 2026-07-21 — ANTI FAUX POSITIF sur la « casse silencieuse ».
     *  Ces sources renvoient LÉGITIMEMENT 0 hors de leur périmètre : un site d'animes ne trouvera
     *  jamais un film live-action, un site de dramas asiatiques non plus. Sans garde-fou elles
     *  franchissaient le seuil et créaient des issues bidon (constaté : VoirDrama, DessinAnime et
     *  DessinAnimeNet à 4/8 sur « Enola Holmes 3 »), exactement comme le faux positif Movix #28.
     *  Un système d'alerte bruyant finit ignoré → on ne compte QUE dans leur périmètre. */
    private val ANIME_ORIENTED_SOURCES = setOf(
        "DessinAnime", "DessinAnimeNet", "AniCloud", "AnimeSama",
        "VoirAnime", "FrenchManga", "FrenchAnime", "FRAnime", "Franime",
    )
    private val DRAMA_ORIENTED_SOURCES = setOf("VoirDrama", "Dramacool", "DramaCool")

    /** 2026-08-07 — Sites GÉNÉRALISTES de films et séries, écartés sur un contenu anime.
     *  Constaté sur *Mashle* et *Katainaka no Ossan* : Coflix Boston remontait 6 puis 13 serveurs
     *  dont un seul lisible. Ces sites indexent en titre français/commercial et accrochent
     *  n'importe quelle œuvre dès qu'on leur présente des titres étrangers. Ils n'ont pas
     *  vocation à couvrir un anime japonais — les sources anime et mixtes, elles, ne sont pas
     *  touchées (le filtre large avait justement été retiré le 09/07 pour cette raison). */
    private val GENERALIST_SOURCES = setOf(
        "Coflix Boston", "CoflixWiki", "Moviebox", "Papadustream V2", "Webflix", "LoiFlix",
    )

    // ── Mémoire des échecs par source (2026-08-16) ───────────────────────────
    /**
     * Dernier instant où une source n'a RIEN rendu. Sert au repli conditionnel de Movix :
     * celui-ci agrège les liens d'autres sites (FrenchStream, Wiflix…) que le registre
     * interroge DÉJÀ en direct — donc en marche normale ses copies sont des doublons, fusionnés
     * par la déduplication après avoir coûté du réseau pour rien.
     *
     * On ne coupe pas ces endpoints pour autant : quand la source directe tombe (site
     * injoignable, rapprochement de titre en échec), Movix garde ses liens en cache côté
     * serveur et reste le seul à les fournir. On consulte donc le résultat de la collecte
     * PRÉCÉDENTE — pas de dépendance à l'ordre d'exécution, et ça se répare tout seul dès que
     * la source directe refonctionne.
     */
    private val derniersEchecs = ConcurrentHashMap<String, Long>()
    private const val FENETRE_ECHEC_MS = 30 * 60 * 1000L

    private fun noterEchec(source: String) { derniersEchecs[source] = System.currentTimeMillis() }
    private fun noterSucces(source: String) { derniersEchecs.remove(source) }

    /** Vrai si [source] n'a rien rendu lors d'une collecte récente (< 30 min). */
    fun aEchoueRecemment(source: String): Boolean {
        val t = derniersEchecs[source] ?: return false
        if (System.currentTimeMillis() - t > FENETRE_ECHEC_MS) { derniersEchecs.remove(source); return false }
        return true
    }

    /**
     * Vrai si Movix doit rappeler ses endpoints SECONDAIRES (les copies de FrenchStream,
     * Wiflix, purstream, j1f, cpasmal…).
     *
     * ⚠ 2026-08-16, SOIR — REND DÉSORMAIS TOUJOURS `true` (décision user : « redonner à Movix
     * la totalité de ses moyens pour être sûr de ne pas oublier de serveur »).
     *
     * L'ancienne règle (« inutile tant que la source directe répond ») se décidait AU DÉPART de
     * la collecte, avant de savoir ce que la source directe allait rendre. Mesuré sur « Nando
     * entre deux mondes » : les quatre copies ont été coupées à 20:43:48, et quatre secondes
     * plus tard Wiflix rendait 0, Purstream 0, et 1Jour1Film un HTTP 403. Trois endpoints
     * muets alors que leur équivalent direct n'avait rien ramené. La mémoire d'échec ci-dessus
     * ne servait qu'à la lecture SUIVANTE, jamais à celle en cours.
     *
     * Les vrais doublons sont déjà éliminés en aval par la dédup (langBucket|normSrc) — elle
     * MESURE au lieu de supposer. Le coût est quelques requêtes de plus en parallèle, pas des
     * serveurs en plus à l'écran.
     *
     * [aEchoueRecemment] et [noterEchec] restent en place : ils servent au journal et
     * redeviendront le pilote si on veut rebrider un jour.
     */
    @Suppress("UNUSED_PARAMETER")
    fun movixSecondaireUtile(sourceDirecte: String): Boolean = true

    /** 2026-07-21 : domaine représentatif d'une source de backup, pour que l'issue « provider
     *  cassé » indique un hôte exploitable (le reporter extrait l'hôte de l'URL fournie). */
    private fun backupProbeUrl(source: String): String? = when (source) {
        "Nakios" -> "https://nakios.live"
        "LoiFlix" -> "https://zoolingz.com"
        "AfterDark" -> "https://afterdark06.mom"
        "Nabistream" -> "https://nabistream.mom"
        "Purstream" -> "https://purstream.store"
        "Rutube" -> "https://rutube.ru"
        "TV Hub" -> "https://api.arte.tv"
        "FileSearch" -> "https://filesearch.tools"
        "Movix" -> "https://movix.date"
        "Coflix Boston" -> "https://coflix.boston"
        "CoflixWiki" -> "https://kokoflix.lol"
        "Moviebox", "Cloudstream" -> "https://api.aoneroom.com"
        "Webflix" -> "https://webflix.lol"
        "Frembed" -> "https://frembed.icu"
        "Vidzy" -> "https://vidzy.org"
        "Papadustream V2" -> "https://papadustream.rip"
        "Yablom" -> "https://yablom.com"
        "Vostfree" -> "https://vostfree.ws"
        "iAnime" -> "https://www.ianimes.eu"
        "Adkami" -> "https://hentai.adkami.com"
        "DessinAnimeNet" -> "https://dessinanime.net"
        "AniCloud" -> "https://anicloud.to"
        "1Jour1Film" -> "https://1jour1film.pro"
        else -> null
    }

    // 2026-07-07 : pacing mémoire RETIRÉ (régressait la Chromecast :
    //   heapTight()=true en PERMANENCE sur Chromecast car heap normal=80%
    //   → mutex 12s par backup → sérialise tout → timeout 45s → 0 serveurs).
    //   L'app marchait très bien sans.

    // 2026-07-12 (user « on fait comme avant : les backups CF passent en dernier, après tous les
    //   backups qui n'en ont pas, SAUF Wiflix qui reste dans la boucle générique ») : 2ᵉ vague CF
    //   RESTAURÉE. Les providers CF/WebView-lourds sont retardés de CF_SECOND_WAVE_MS → les backups
    //   sans CF (rapides) remontent leurs serveurs en PREMIER, les CF arrivent après. Wiflix N'EST
    //   PAS dans le set → il part en 1ʳᵉ vague (jamais retardé).
    // 2026-07-12 v2 : la 2ᵉ vague ne concerne QUE les providers CF orientés FILMS (qui ne tournent
    //   que pour les films). Les providers ANIME (FrenchAnime, VoirAnime, FrenchManga, Franime,
    //   VoirDrama, DessinAnime) NE SONT PLUS retardés : pour un anime ce sont EUX les serveurs
    //   utiles → les retarder de 5s repoussait inutilement l'affichage.
    // 2026-08-01 : Cpasmal et Cpasmieux retirés de ce set (sources supprimées, cf. plus bas).
    private val WEBVIEW_HEAVY_PROVIDERS = setOf(
        "aplouf", "FrenchStream", "Papadustream", "1Jour1Film"
    )
    // ⚠ 2026-08-02 (user : « à chaque fois qu'un provider est appelé, le serveur doit être émis
    //   dans la foulée — les serveurs doivent être instantanés ») : 5000 ms → 800 ms.
    //   Ce délai servait à faire remonter les sources rapides AVANT les lourdes, faute de tri
    //   fiable à l'époque. Ce n'est plus nécessaire : l'ordre est maintenant décidé par le tri
    //   (langue → FileSearch → définition → débit), qui replace chaque serveur à sa place quelle
    //   que soit son heure d'arrivée. Retarder l'arrivée pour influencer l'ordre revenait donc à
    //   faire attendre l'utilisateur pour rien — c'était l'essentiel des ~5 s entre deux lots.
    //   On garde 800 ms : de quoi laisser les sources instantanées (API) partir devant, sans que
    //   ce soit perceptible.
    private const val CF_SECOND_WAVE_MS = 800L

    // 2026-07-14 (user : « à la base on parlait QUE de Moviebox ») : le blocage films-only
    //   ne visait QUE Moviebox (qui ramenait la mauvaise saison sur les séries). Les autres
    //   providers (Cpasmal, Cpasmieux, aplouf, Papadustream, FrenchStream, 1Jour1Film) avaient
    //   été embarqués à tort → RETIRÉS : ils tournent de nouveau sur les séries/épisodes aussi.
    //   Moviebox reste seul bridé aux films.
    private val FILM_ONLY_PROVIDERS = setOf(
        "Moviebox"
    )

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
    /**
     * 2026-08-01 (retour testeur : « AnimeSama / Hajime no Ippo E3 — les liens VoirAnime ne
     * proposent pas le bon épisode ») : rejette un candidat dont le SOUS-TITRE diffère.
     *
     * Beaucoup d'animes nomment chaque saison différemment. Mesuré sur ce cas :
     *   demandé  : « Hajime no Ippo : The Fighting »   (saison 1)
     *   VoirAnime: « Hajime no Ippo: Rising (VF) »     (saison 3 !)
     * Les mots « hajime no ippo » suffisaient à valider le match, et on servait donc
     * l'épisode 3 de *Rising* à la place de celui de *The Fighting* — Wiflix, DessinAnime et
     * NetMirror, eux, retournaient bien « Larmes de joie ».
     *
     * Règle : si le titre demandé ET le candidat portent chacun un sous-titre (après « : »),
     * ils doivent partager au moins un mot significatif. Si l'un des deux n'en a pas, on
     * accepte (beaucoup de fiches sont nommées sans sous-titre).
     */
    fun sousTitreDe(s: String): Set<String> {
        val apres = s.substringAfter(':', "").substringBefore('(')
        return if (apres.isBlank()) emptySet() else sigWords(apres)
    }

    /**
     * @param titrePrincipal le titre RÉELLEMENT demandé (surtout PAS la liste des titres
     *   alternatifs TMDB). Mesuré sur Hajime no Ippo : `knownTitles` contient
     *   « Hajime No Ippo: Rising » et « … New Challenger » — c'est-à-dire les noms des AUTRES
     *   SAISONS. Comparer à cette liste revenait à valider « Rising » avec « Rising » : le
     *   filtre s'auto-annulait. On ne compare donc qu'au titre principal.
     */
    fun sousTitreCompatible(candidateTitle: String, titrePrincipal: String): Boolean {
        val cand = sousTitreDe(candidateTitle)
        val attendu = sousTitreDe(titrePrincipal)
        // Le titre demandé porte un sous-titre → le candidat doit partager un mot.
        if (attendu.isNotEmpty()) return cand.isEmpty() || attendu.any { it in cand }
        // Le titre demandé n'en a PAS (ex. « Hajime no Ippo ») → on refuse les candidats qui
        //   en ajoutent un (« … : Rising », « … : New Challenger ») : ce sont d'autres saisons.
        return cand.isEmpty()
    }

    /** 2026-07-06 (user "recherche stricte TMDB — le matching backup ramène la mauvaise saison") :
     *  rejette un candidat backup dont le TITRE déclare une saison DIFFÉRENTE de celle demandée
     *  (ex : "Jujutsu Kaisen Saison 2" pour une requête S1). Si le candidat ne déclare aucune
     *  saison → ACCEPTÉ (beaucoup de fiches S1 n'ont pas de "Saison 1"). Films → toujours ok. */
    fun seasonTitleOk(candidateTitle: String, isMovieTarget: Boolean, targetSeason: Int): Boolean {
        if (isMovieTarget) return true
        // 1) Forme explicite « Saison N » / « Season N » / « SN ».
        Regex("""(?i)\bsaison\s*(\d+)|\bseason\s*(\d+)|\bs(\d+)\b""").find(candidateTitle)?.let { m ->
            val declared = (m.groupValues[1].ifBlank { m.groupValues[2].ifBlank { m.groupValues[3] } }).toIntOrNull()
            if (declared != null) return declared == targetSeason
        }
        // 2) 2026-07-16 (bug VoirAnime : « Classroom of the Elite 4 (VF) » = saison 4 accepté pour S1) :
        //    les sites anime (VoirAnime…) nomment leurs saisons « Titre N » — un NUMÉRO en SUFFIXE,
        //    éventuellement suivi d'un tag langue/qualité NU ou ENTRE PARENTHÈSES (« 4 (VF) », « 4 VOSTFR »).
        //    On retire d'abord ces décorations finales, puis on lit le numéro en bout de titre.
        //    Numéro 2..50 uniquement (évite « 100 »/années/titres-nombres), n° 1 = base laissé permissif.
        var base = candidateTitle.trim()
        repeat(3) {
            base = base
                .replace(Regex("""(?i)\s*[\(\[]\s*(?:vf|vostfr|vost|vo|multi|fr|hd|fhd|uhd|4k|\d{3,4}p)[^)\]]*[\)\]]\s*$"""), "")
                .replace(Regex("""(?i)\s+(?:vf|vostfr|vost|vo|multi|fr|hd|fhd|uhd|4k|\d{3,4}p)\s*$"""), "")
                .trim()
        }
        val trailing = Regex("""(?:^|\s)(\d{1,2})$""").find(base)?.groupValues?.get(1)?.toIntOrNull()
        if (trailing != null && trailing in 2..50) {
            return trailing == targetSeason
        }
        return true
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
        // 2026-07-13 (user « la série "H" : du moment que ça matche H + l'année, ça doit passer ») :
        //   un titre connu ULTRA-COURT (1-2 caractères, ex « H ») est rejeté par titleMatches (il
        //   exige l'exact → « H - Saison 1 VF » ≠ « h »). On l'accepte SI le token exact apparaît
        //   ISOLÉ dans le candidat ET que l'année du candidat colle (±1). L'année = discriminant.
        //   (Si le site ne met pas l'année dans le titre → pas d'année à comparer → on retombe sur
        //   le matching normal ; seuls les providers par ID TMDB trouvent alors.)
        val shortKnown = knownTitles.firstOrNull { t ->
            t.trim().replace(Regex("[^a-zA-Z0-9]"), "").length in 1..2
        }
        if (shortKnown != null) {
            val token = shortKnown.trim().lowercase().replace(Regex("[^a-z0-9]"), "")
            // 2026-07-23 (log OPPO : FrenchStream « H - Saison 1 » rejeté) : le titre du candidat
            //   est DÉCORÉ « H - Saison N » et NE CONTIENT PAS l'année (juste un n° de saison) →
            //   l'ancienne voie « token isolé + année DANS le titre » échouait. On NETTOIE d'abord
            //   le candidat (retrait « - Saison N », parenthèses, .html — via cleanTitle) puis on
            //   exige l'ÉGALITÉ STRICTE avec le token court. Ex : « H - Saison 4 » → « H » == « h ».
            //   Sûr : égalité exacte après nettoyage (pas un simple contains), et la bonne saison
            //   est vérifiée en aval par seasonTitleOk. On a déjà l'id TMDB + effectiveYear comme
            //   discriminants → pas besoin de l'année dans le titre.
            val cleanedCand = cleanTitle(candidateTitle).lowercase().replace(Regex("[^a-z0-9]"), "")
            if (cleanedCand == token) return true
            // Ancienne voie conservée : token isolé + année PRÉSENTE dans le titre candidat (±1).
            if (targetYear != null) {
                val cy = yearIn(candidateTitle)
                val tokenIsolated = Regex("(?i)(?<![a-z0-9])" + Regex.escape(shortKnown.trim()) + "(?![a-z0-9])")
                    .containsMatchIn(candidateTitle)
                if (tokenIsolated && cy != null && kotlin.math.abs(cy - targetYear) <= 1) return true
            }
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
    // 2026-08-20 : rendu PUBLIC pour que DownloadManager nomme les fichiers avec
    //   EXACTEMENT le titre qui servira ensuite à les rechercher (`key.title` passe
    //   par ici). Dupliquer cette logique ailleurs, c'est se garantir une divergence
    //   le jour où l'une des deux copies évolue.
    fun cleanTitle(raw: String): String {
        var t = raw
        // Retire les blocs entre parenthèses : (Version Couleur), (VF), (Film), (Anime), etc.
        t = t.replace(Regex("\\s*\\([^)]*\\)"), "")
        // Retire "– Saison N" / "- Saison N" en fin (la saison est déjà dans key.season)
        t = t.replace(Regex("\\s*[–-]\\s*[Ss]aison\\s*\\d+.*$"), "")
        // Retire le suffixe .html (résidu slug Wiflix)
        t = t.replace(Regex("\\.html$"), "")
        return t.trim()
    }

    private fun keyOf(videoType: Video.Type, titleHint: String?, episodeIdHint: String? = null): Key = when (videoType) {
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
            // 2026-07-13 : VRAIE saison depuis l'id d'épisode si présent (« …/saison4/… » → 4).
            //   Sur AnimeSama VF+VOSTFR, videoType.season.number = la LANGUE (1/2), pas la saison.
            //   L'id, lui, porte le vrai dossier « saisonN ». Fallback = season.number (≤0 → 1).
            season = (episodeIdHint?.let { Regex("(?i)saison\\s*(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() })
                ?: videoType.season.number.let { if (it <= 0) 1 else it },
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
    /**
     * Lance la collecte À L'AVANCE, sans rien afficher, pour que le cache soit chaud quand
     * l'utilisateur appuiera sur lecture.
     *
     * ── 2026-08-06 : POURQUOI ─────────────────────────────────────────────────────────────
     *   Plainte user : « j'ai lancé une première fois la série, j'avais pas les serveurs que
     *   je convoitais ; je relance dans la foulée, ils sont là ». Longtemps mis sur le compte
     *   d'un rejet de serveurs — c'était faux. Le journal, deux lancements consécutifs sur la
     *   même série, prouve que RIEN n'est écarté : seuls les délais changent.
     *
     *              1er lancement (à froid)      2e lancement (cache chaud)
     *     FrenchStream   34,4 s → 8 serveurs        13,6 s → 8 serveurs
     *     1Jour1Film     31,3 s → 3 serveurs        11,8 s → 3 serveurs
     *     NetMirror       9,9 s → 2 serveurs         7,7 s → 2 serveurs
     *
     *   Chaque source doit d'abord CHERCHER le titre sur son site (13 à 29 s à froid) avant
     *   de résoudre ses serveurs. Le lecteur, lui, démarre en quelques secondes : on regardait
     *   une liste encore en train de se remplir.
     *
     *   D'où ce pré-chauffage, déclenché depuis la FICHE du film ou de la série — ces
     *   quelques secondes où l'utilisateur choisit son épisode sont gratuites.
     *   Sans effet visible, sans blocage : on consomme le flux et on jette, seul le cache
     *   des sources nous intéresse. Une même clé n'est chauffée qu'une fois.
     */
    fun prechauffer(
        tmdbId: String?,
        videoType: Video.Type,
        exclude: Set<String> = emptySet(),
        titleHint: String? = null,
        isAnimeProvider: Boolean = false,
        episodeIdHint: String? = null,
    ) {
        // ⚠ 2026-08-06 : DÉSACTIVÉ le jour même de son ajout. L'idée reste bonne (les sources
        //   mettent 30 s à froid, autant les lancer depuis la fiche), mais l'implémentation
        //   ÉTRANGLE l'application : cette collecte de fond retient les jetons de `heavyGate`,
        //   et la collecte réelle lancée par la lecture n'en obtient plus → écran sans serveurs,
        //   HANGDUMP dans `CrossProviderResolver`. Constaté par le user : « plus rien ne marche ».
        //   Pour le réactiver il faudra d'abord : soit sortir le pré-chauffage du sémaphore,
        //   soit l'ANNULER dès qu'une vraie collecte démarre sur la même clé.
        //   Les 4 appels depuis les fiches (série/film × mobile/TV) sont laissés en place :
        //   ils deviennent inoffensifs, et la réactivation ne demandera que de retirer ce garde.
        if (PRECHAUFFAGE_DESACTIVE) return

        val cle = keyOf(videoType, titleHint, episodeIdHint).toString()
        if (!prechauffes.add(cle)) return
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                android.util.Log.i(TAG, "pré-chauffage lancé pour « ${titleHint ?: tmdbId} »")
                fetchAll(tmdbId, videoType, exclude, titleHint, isAnimeProvider, episodeIdHint)
                    .collect { /* on ne veut que remplir les caches */ }
                android.util.Log.i(TAG, "pré-chauffage terminé pour « ${titleHint ?: tmdbId} »")
            } catch (e: Exception) {
                // Un pré-chauffage qui échoue n'a aucune conséquence : la collecte normale
                // repartira de zéro au moment de la lecture.
                android.util.Log.w(TAG, "pré-chauffage interrompu : ${e.message}")
                prechauffes.remove(cle)
            }
        }
    }

    /** Interrupteur du pré-chauffage — voir l'explication dans `prechauffer`. */
    private const val PRECHAUFFAGE_DESACTIVE = true

    /** Clés déjà pré-chauffées dans cette session — évite de relancer à chaque aller-retour. */
    private val prechauffes = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

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
        // 2026-07-13 : id d'épisode brut du provider (ex AnimeSama « slug/saison4/vostfr/2 »).
        //   Pour les anime VF+VOSTFR, `videoType.season.number` = la LANGUE (VOSTFR=1), PAS la vraie
        //   saison — celle-ci n'est QUE dans ce chemin. On l'extrait dans keyOf (« saison4 » → 4).
        episodeIdHint: String? = null,
    ): Flow<List<Video.Server>> = channelFlow {
        val key = keyOf(videoType, titleHint, episodeIdHint)

        // 2026-07-04 : si tmdbId est null mais qu'on a un titre, on RÉSOUT le tmdbId
        //   via TMDB Search.multi → débloque Nakios/Movix/Embed/Webflix pour les
        //   providers à slug (FrenchAnime, AnimeSama, FrenchManga, etc.).
        val resolvedTmdbId: String? = run {
            val initial: String? = if (!tmdbId.isNullOrBlank()) {
                tmdbId
            } else if (key.title.isNotBlank()) {
                // 2026-07-23 (user « H : FrenchStream a 4 serveurs mais ne se propage pas ;
                //   et lui ne reçoit pas non plus ») : log OPPO = `title='H' tmdbId=null
                //   year=null`. Le gate était `>= 2` → aucune résolution d'id pour « H » →
                //   0 backup par id sur les providers SANS année (FrenchStream/slug). On
                //   ouvre à TOUT titre non vide : le `titleMatches` interne est déjà EXACT
                //   pour ≤2 caractères (nc==nq), donc « H » ne matche qu'une fiche TMDB
                //   nommée exactement « H » → zéro faux positif.
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
                    //
                    // 2026-08-08 (user : « c'est pas le vrai Star Trek qui est diffusé »,
                    //   « regarde tous les serveurs qui matchent pas sur le bon ») — RACINE DU
                    //   MAUVAIS MATCH, vérifiée en direct sur l'API TMDB :
                    //     /3/search/multi?query=Star Trek&language=fr-FR
                    //       1. id=1855 « Star Trek: Voyager »  1995
                    //       2. id=253  « Star Trek »           1966   ← le bon
                    //   « Star Trek: Voyager » passait le test SOUS-TITRE, et comme on prenait le
                    //   PREMIER candidat, tout le registre backup repartait sur tmdbId=1855 :
                    //   Vidzy (qui travaille par id) servait Voyager, et Nakios / FrenchStream /
                    //   1Jour1Film cherchaient « Star Trek: Voyager » comme titre alternatif.
                    //   NetMirror, lui, reçoit tmdb=253 directement du lecteur — d'où le constat
                    //   du user : « NetMirror lui est vraiment bon ».
                    //
                    //   Ordre de préférence désormais :
                    //     1. titre EXACT (match dans LES DEUX SENS) **et** année à ±1 an
                    //     2. titre EXACT seul
                    //     3. année seule
                    //     4. à défaut, le 1er candidat (comportement d'avant)
                    //   Le sous-titre (« Mushoku Tensei » ⊂ « Mushoku Tensei: Jobless Reincarnation »)
                    //   reste accepté, mais il ne peut plus DOUBLER un titre exact.
                    val titreDe = fun(item: Any?): String? = when {
                        wantMovie && item is TMDb3.Movie -> item.title
                        !wantMovie && item is TMDb3.Tv -> item.name
                        else -> null
                    }
                    val anneeDe = fun(item: Any?): Int? = when (item) {
                        is TMDb3.Movie -> item.releaseDate?.toString()?.take(4)?.toIntOrNull()
                        is TMDb3.Tv -> item.firstAirDate?.toString()?.take(4)?.toIntOrNull()
                        else -> null
                    }
                    val anneeCible = key.year
                    val candidats = results.results.filter { item ->
                        val t = titreDe(item)
                        t != null && (titleMatches(t, key.title) || subtitlePrefix(t))
                    }
                    val exacts = candidats.filter { item ->
                        val t = titreDe(item) ?: return@filter false
                        titleMatches(t, key.title) && titleMatches(key.title, t)
                    }
                    val bonneAnnee = fun(item: Any?): Boolean {
                        if (anneeCible == null) return false
                        val a = anneeDe(item) ?: return false
                        return kotlin.math.abs(a - anneeCible) <= 1
                    }
                    val match = exacts.firstOrNull { bonneAnnee(it) }
                        ?: exacts.firstOrNull()
                        ?: candidats.firstOrNull { bonneAnnee(it) }
                        ?: candidats.firstOrNull()
                    if (match != null) {
                        Log.i(TAG, "tmdbId résolu par recherche : '${key.title}' (${anneeCible ?: "?"}) → '${titreDe(match)}' ${anneeDe(match) ?: "?"} parmi ${candidats.size} candidat(s)")
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
        // 2026-08-07 — RATTRAPAGE ANILIST quand TMDB n'a pas résolu l'œuvre.
        //   Mesuré sur la box, deux animes ouverts depuis AnimeSama :
        //     Yani Neko    tmdbId RÉSOLU → knownTitles = 6 titres → iAnime rend 6 serveurs
        //     Katainaka…   tmdbId = null → knownTitles = 1 titre  → les 14 sources rendent 0
        //   AnimeSama indexe en ROMAJI ; quand TMDB ne reconnaît pas ce romaji, la liste se
        //   réduit à ce seul titre et plus aucune source française ne peut aboutir — elles
        //   rangent l'œuvre sous son titre français. AniList, lui, connaît le romaji et rend
        //   l'anglais + le natif + les synonymes ; l'anglais est ensuite reconnu par TMDB.
        //   ⚠ AJOUT SEULEMENT : le titre d'origine reste en tête et continue d'être essayé le
        //   premier (« activer les 2 leviers plutôt », user 07/08).
        //   ⚠ 2026-08-07, MÊME JOUR — user : « Coflix te ramène des serveurs mais y en a 0 qui
        //   fonctionnent ». Diagnostic : sans tmdbId, `isAnimeContent` restait FAUX, donc le
        //   garde-fou existant (sauter Coflix/Moviebox/Papadustream sur un anime) ne s'armait
        //   pas — et mes 19 titres, dont des synonymes polonais/portugais/indonésiens, ont donné
        //   à ces sites généralistes de quoi accrocher n'importe quelle œuvre. 13 faux serveurs.
        //   Or si AniList RECONNAÎT l'œuvre, c'est par construction un anime : on arme donc le
        //   garde-fou ici, sans attendre TMDB.
        var animeSelonAniList = false
        if (resolvedTmdbId.isNullOrBlank() && key.title.isNotBlank()) {
            val alias = AniListTitres.titresPour(key.title)
            if (alias.isNotEmpty()) {
                alias.forEach { knownTitles.add(it) }
                animeSelonAniList = true
            }
        }
        // 2026-07-23 : année EFFECTIVE. Certains providers (FrenchStream/slug) n'envoient
        //   PAS d'année (key.year=null) → les backups par TITRE d'un titre ultra-court (« H »)
        //   restent bloqués (le matcher a besoin de l'année comme discriminant). On la déduit
        //   de la fiche TMDB résolue (bloc details ci-dessous) → title-based backups OK partout.
        var effectiveYear: Int? = key.year
        // 2026-07-09 : détection contenu anime par la langue originale TMDB.
        //   Quand originalLanguage="ja" → le contenu est japonais (anime/drama).
        //   On traite comme anime → skip les backups non-anime (CoflixWiki, Moviebox,
        //   Papadustream…) qui ne trouvent rien et polluent avec des faux positifs
        //   (ex: film "Apple" sur un anime "Chainsmoker Cat"). Fonctionne QUEL QUE
        //   SOIT le provider natif (NetMirror, AnimeSama, etc.).
        // 2026-08-07 : amorcé par AniList quand TMDB n'a rien résolu (voir plus haut). Une œuvre
        //   reconnue par AniList EST un anime — le garde-fou s'arme donc sans attendre TMDB, et
        //   les sources généralistes (Coflix, Moviebox, Papadustream…) sont écartées comme elles
        //   doivent l'être. Sans ça, elles accrochent n'importe quoi sur un titre étranger.
        var isAnimeContent = animeSelonAniList
        // 2026-07-12 (user « les providers dessins animés n'ont AUCUN film → pour un film non-anime
        //   on peut les sauter ; l'inverse n'est pas vrai car les providers films ont parfois des
        //   dessins animés ») : filtre À SENS UNIQUE. Si TMDB confirme que le contenu N'EST PAS de
        //   l'animation (genre 16 absent, genres non vides), on saute les providers spécialisés
        //   anime/dessins animés (ils n'auront jamais un film live-action) → ~5s de recherche en
        //   moins. On ne fait JAMAIS l'inverse.
        var notAnimationConfirmed = false
        // 2026-08-08 (user : « on a pas mal de serveurs appelés pour rien alors qu'ils n'auront
        //   jamais rien ») : la LANGUE D'ORIGINE TMDB sert à écarter les catalogues spécialisés
        //   (dramas asiatiques…) sur un contenu qui n'en relève pas. null = inconnue → on ne
        //   saute rien (filtre à sens unique, comme pour l'animation).
        var langueOriginale: String? = null
        // 2026-08-11 — DURÉE ATTENDUE (secondes), lue sur la fiche TMDB déjà chargée juste en
        //   dessous : aucune requête supplémentaire. Sert de discriminant aux backups par
        //   TITRE qui, sans elle, ne savent pas distinguer une bande-annonce du film.
        //   Mesuré sur archive.org / « La Nuit des morts-vivants » : trois items au titre,
        //   à l'année et à la langue exacts, contenant des fichiers de 200 s et 68 s.
        //   ⚠ AJOUT SEULEMENT : rien d'existant ne la consomme, ok.ru continue de recevoir
        //   `runtimeS = null` et son garde-fou interne, inchangé.
        //   ⚠ DEUX bornes, pas une. TMDB rend une LISTE de durées pour une série, et beaucoup
        //   de séries mélangent les formats — La Quatrième Dimension a des épisodes de 25 min
        //   (saisons 1-3 et 5) et de 51 min (saison 4). Un plancher calculé sur la plus longue
        //   écarte tous les courts : mesuré, l'épisode S1E03 de 1471 s était rejeté face à un
        //   plancher de 2448 s. Plancher sur la plus COURTE, plafond sur la plus LONGUE.
        var runtimeSecondes: Int? = null
        var runtimeMaxSecondes: Int? = null
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
                        d.alternativeTitles?.all()
                            ?.filter { (it.iso31661?.uppercase() ?: "") in ALT_COUNTRIES }
                            ?.mapNotNull { it.title?.takeIf { t -> t.length >= 3 } }
                            ?.forEach { knownTitles.add(it) }
                        // Détection anime : langue originale japonaise
                        if (d.originalLanguage == "ja") isAnimeContent = true
                        langueOriginale = d.originalLanguage?.lowercase()
                        // Confirmé PAS de l'animation : genres connus ET genre 16 (Animation) absent
                        if (d.genres.isNotEmpty() && d.genres.none { it.id == 16 }) notAnimationConfirmed = true
                        // Durée du FILM (TMDB la donne en minutes) : une seule valeur, les
                        //   deux bornes sont donc identiques.
                        runtimeSecondes = d.runtime?.takeIf { it > 0 }?.times(60)
                        runtimeMaxSecondes = runtimeSecondes
                        // Année déduite si le provider n'en a pas fourni.
                        if (effectiveYear == null) effectiveYear = d.releaseDate?.take(4)?.toIntOrNull()
                    } else {
                        val d = TMDb3.TvSeries.details(
                            seriesId = idInt,
                            language = "fr-FR",
                            appendToResponse = listOf(TMDb3.Params.AppendToResponse.Tv.ALTERNATIVE_TITLES),
                        )
                        if (d.name.isNotBlank()) knownTitles.add(d.name)
                        if (d.originalName.isNotBlank()) knownTitles.add(d.originalName)
                        // Titres alternatifs FR + JP (romaji)
                        d.alternativeTitles?.all()
                            ?.filter { (it.iso31661?.uppercase() ?: "") in ALT_COUNTRIES }
                            ?.mapNotNull { it.title?.takeIf { t -> t.length >= 3 } }
                            ?.forEach { knownTitles.add(it) }
                        // Détection anime : langue originale japonaise
                        if (d.originalLanguage == "ja") isAnimeContent = true
                        langueOriginale = d.originalLanguage?.lowercase()
                        if (d.genres.isNotEmpty() && d.genres.none { it.id == 16 }) notAnimationConfirmed = true
                        // Durée d'un ÉPISODE. TMDB rend une LISTE parce qu'une série peut
                        //   mélanger les formats : La Quatrième Dimension déclare 25 ET 51 min.
                        //   On garde les DEUX extrêmes — la plus courte fait le plancher, la
                        //   plus longue le plafond. (Première version : `maxOrNull()` pour les
                        //   deux, ce qui écartait tous les épisodes courts de la série.)
                        val durees = d.episodeRuntime.filter { it > 0 }
                        runtimeSecondes = durees.minOrNull()?.times(60)
                        runtimeMaxSecondes = durees.maxOrNull()?.times(60)
                        // Année déduite si le provider n'en a pas fourni.
                        if (effectiveYear == null) effectiveYear = d.firstAirDate?.take(4)?.toIntOrNull()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "knownTitles TMDB details KO: ${e.message}")
            }
        }

        // ═════════════════════════════════════════════════════════════════════
        // 2026-08-04 (user : « pourquoi aucun serveur pour ce film ? ») — TITRE FRANÇAIS
        //   DE SECOURS.
        //
        //   Constat sur « The Odyssey » (TMDB 1698863, 2026) : 1Jour1Film AVAIT le film, sous
        //   le titre « L'ODYSSEE (2026) ». Rejeté — `sharesWord=false` — parce que
        //   `knownTitles` ne contenait que « The Odyssey ». La fiche détaillée TMDB en fr-FR
        //   n'avait rendu aucun titre français distinct pour ce film.
        //
        //   Recours : la même recherche que CoflixSourceProvider utilise déjà avec succès
        //   (`Coflix fallback FR title TMDB: 'The Odyssey' -> 'L'Odyssée'` dans les logs).
        //
        //   ⚠ Élargir `knownTitles` assouplit le matcher — c'est ce mécanisme qui avait donné
        //   le mauvais « Joker ». D'où DEUX sécurités indépendantes :
        //     1. l'identifiant TMDB renvoyé doit être CELUI DU FILM DEMANDÉ (garantit la bonne
        //        œuvre, pas un homonyme) ;
        //     2. le gate d'année de `workMatches` reste actif — « L'Odyssée » de 2016 (Cousteau)
        //        face à une cible 2026 est écarté sur l'écart de dix ans.
        //
        //   N'est tenté QUE si la fiche détaillée n'a rien apporté (un seul titre connu) :
        //   aucune requête supplémentaire dans le cas normal.
        if (knownTitles.size <= 1 && !resolvedTmdbId.isNullOrBlank() && key.title.isNotBlank()) {
            try {
                // Branches séparées : `Movie` et `TvShow` ne partagent pas de supertype
                //   exposant `id`/`title`, un if/else unifié ne compile pas.
                var idFr: String? = null
                var titreFr = ""
                if (key.isMovie) {
                    TmdbUtils.getMovie(key.title, effectiveYear, language = "fr-FR")?.let {
                        idFr = it.id; titreFr = it.title
                    }
                } else {
                    TmdbUtils.getTvShow(key.title, effectiveYear, language = "fr-FR")?.let {
                        idFr = it.id; titreFr = it.title
                    }
                }
                if (idFr == resolvedTmdbId && titreFr.isNotBlank() &&
                    knownTitles.none { it.equals(titreFr, ignoreCase = true) }
                ) {
                    knownTitles.add(titreFr)
                    Log.i(TAG, "knownTitles + titre FR de secours : '${key.title}' → '$titreFr'")
                }
            } catch (e: Exception) {
                Log.w(TAG, "titre FR de secours KO: ${e.message}")
            }
        }

        // 2026-07-09 : si le contenu est japonais (anime), traiter comme anime
        //   QUEL QUE SOIT le provider natif. Évite CoflixWiki/Moviebox/Papadustream
        //   qui polluent avec des faux positifs sur du contenu anime.
        val effectiveAnime = isAnimeProvider || isAnimeContent
        if (isAnimeContent && !isAnimeProvider) {
            Log.i(TAG, "fetchAll contenu ANIME détecté (originalLanguage=ja) → effectiveAnime=true")
        }

        Log.i(TAG, "fetchAll title='${key.title}' knownTitles=$knownTitles tmdbId=$resolvedTmdbId year=${key.year} effectiveYear=$effectiveYear season=${key.season} ep=${key.episode}")
        val seen = ConcurrentHashMap.newKeySet<String>()

        // Dédup (par LANGUE + URL normalisée, ne fusionne JAMAIS VF/VOSTFR/VO) + envoi.
        //   Extrait de emit() pour être réutilisable par la boucle CF séquentielle.
        fun pushServers(source: String, servers: List<Video.Server>) {
            if (servers.isEmpty()) {
                Log.i(TAG, "$source → 0 (vide/timeout)")
                noterEchec(source)
                return
            }
            noterSucces(source)
            val fresh = servers.filter { it.src.isNotBlank() && seen.add(langBucket(it.name) + "|" + normSrc(it.src)) }
            Log.i(TAG, "$source → ${fresh.size} neufs / ${servers.size} bruts")
            if (fresh.isNotEmpty()) trySend(fresh.map { wrap(source, it) })
        }

        suspend fun emit(source: String, fetch: suspend () -> List<Video.Server>) {
            if (source in exclude) return
            // 2026-08-08 (user : « on peut pas se permettre de faire attendre des serveurs qui
            //   ont répondu en un quart de seconde, ils doivent arriver en premier, et pendant
            //   ce temps-là ça libère plein de choses ») — VAGUES DE LANCEMENT.
            //
            //   MESURE, film « Obsession » sur la Chromecast (T0 = départ du registre) :
            //     Nabistream 1,7 s · Yablom 2,0 s · FileSearch 3,1 s · Vidzy 3,2 s
            //     NetMirror 3,3 s · Webflix 3,4 s · CoflixWiki 3,6 s · Nakios 4,9 s
            //     LoiFlix 7,3 s · Coflix Boston 8,7 s
            //     Papadustream V2 19,4 s · aplouf 19,7 s · Frembed 20,8 s
            //     FrenchStream 23,2 s · 1Jour1Film 31,4 s
            //
            //   Les 20,8 s de Frembed ne viennent PAS du réseau : son API répond à 2,7 s, puis
            //   dix secondes s'écoulent entre deux `Log.d` séparés par un simple `map` sans
            //   aucun appel. C'est de la FAMINE : une vingtaine de sources démarrent en même
            //   temps sur un appareil à faible mémoire et se disputent CPU, sockets et DNS.
            //   Retarder les lentes de deux secondes ne leur coûte rien (elles mettent 20 à
            //   31 s de toute façon, et la lecture ne les attend pas) mais rend la machine aux
            //   rapides pendant leur fenêtre utile.
            val retard = retardDeVague(source)
            if (retard > 0L) kotlinx.coroutines.delay(retard)
            // 2026-07-10 (user "pouvoir désactiver les backups UN PAR UN, pas tous d'un coup") :
            //   chaque source de backup a un toggle (Paramètres). Point de gate UNIQUE (tous les
            //   emit dédiés ET la boucle générique passent ici).
            if (!com.streamflixreborn.streamflix.utils.UserPreferences.isBackupSourceEnabled(source)) {
                Log.i(TAG, "$source → DÉSACTIVÉ par l'utilisateur (skip)")
                return
            }
            // 2026-08-07 (user : « les providers animes ne sont pas censés matcher avec des choses
            //   non animes comme Coflix » — constaté sur Mashle : Coflix Boston remonte 6 serveurs,
            //   et sur un autre anime 13 dont 1 seul lisible, « c'est de la pollution quand même »).
            //   ⚠ Le filtre `effectiveAnime` de la boucle générique avait été RETIRÉ le 09/07 parce
            //   qu'il était trop large (DessinAnime héberge du contenu mixte). On ne le rétablit
            //   donc PAS en bloc : on écarte nommément les seuls sites GÉNÉRALISTES de films et
            //   séries, qui n'ont pas vocation à couvrir un anime japonais. Les sources anime, les
            //   sources mixtes et toutes les autres restent intactes.
            //   ⚠ On teste `isAnimeContent`, PAS `effectiveAnime`. La différence compte :
            //   `effectiveAnime` vaut aussi vrai dès que le provider natif s'appelle « …Anime »
            //   ou « …Manga » (DessinAnime, AnimeSama…). Or DessinAnime est adossé à TMDB et
            //   couvre TOUT le dessin animé, occidental compris — user, 07/08 : « c'est le seul
            //   provider TMDB dessin animé qui doit tout avoir ». Couper Coflix sur un dessin
            //   animé américain ouvert depuis DessinAnime lui retirerait des serveurs légitimes.
            //   On ne coupe donc que sur du contenu RÉELLEMENT japonais (TMDB originalLanguage=ja,
            //   ou œuvre reconnue par AniList).
            if (isAnimeContent && source in GENERALIST_SOURCES) {
                Log.i(TAG, "$source → SAUTÉ : source généraliste sur un contenu anime")
                return
            }
            // 2026-07-21 (user : « comment ça se fait qu'on n'a pas reçu d'alerte pour ce genre de
            //   cas — à la base on avait mis le système d'alerte exprès pour ça ») :
            //   TROU D'ALERTE comblé. Le reporter n'était branché QUE sur `Extractor.extract` et sur
            //   le getServers/runEndpoint de Movix + Cloudstream. Les sources de BACKUP passent
            //   TOUTES par ce point unique et n'étaient JAMAIS surveillées → Nakios a pu mourir
            //   complètement (page de statut `nakios.online` disparue → domaine jamais résolu →
            //   0 serveur à chaque fois) sans qu'aucune issue ne soit créée.
            //   Désormais : 0 serveur répété = « casse silencieuse », et une erreur réseau
            //   (DNS/connect/SSL) = « provider cassé ». Dédup GitHub déjà gérée par le reporter.
            var failure: Throwable? = null
            val servers = try {
                // ⚠ 2026-08-02 (user : « à chaque fois qu'un provider est appelé, le serveur doit
                //   être émis dans la foulée — je ne vois pas pourquoi il y a une attente ») :
                //   DÉLAIS RAMENÉS À 25 s POUR LES SOURCES LOURDES.
                //   Elles passent par un sémaphore limité à 2 jetons sur les appareils à faible
                //   mémoire (Chromecast). Avec 60 s, un seul hôte muet — nakios.org, constaté
                //   bloqué en lecture socket dans un HANGDUMP — confisquait un jeton une minute
                //   entière, et les six autres sources lourdes attendaient leur tour : d'où les
                //   serveurs qui arrivaient par paquets étalés sur ~20 s au lieu d'affluer.
                //   25 s laisse largement le temps à une source WebView + Cloudflare (mesuré :
                //   8 à 18 s dans le pire cas), tout en libérant vite la place quand l'hôte est
                //   mort. Les sources LÉGÈRES (API) gardent 60 s : elles ne bloquent personne.
                // ⚠ 2026-08-02, CORRECTION : 25 s était TROP COURT et cassait Wiflix.
                //   Mesuré dans les logs : Wiflix répond en ~31 s, aplouf en ~33 s, 1Jour1Film en
                //   ~32 s, FrenchStream en ~24 s. Ces sources scrapent plusieurs pages derrière
                //   Cloudflare — elles sont lentes, mais elles RAMÈNENT des serveurs.
                //   À 25 s, Wiflix passait de « serveurs VOE/Uqload » à « 0 (vide/timeout) ».
                //   40 s : laisse finir les plus lentes tout en libérant le jeton bien avant les
                //   60 s d'origine, qui étaient le vrai problème quand un hôte est mort.
                // ⚠ 2026-08-06, MÊME CAUSE, TROISIÈME CORRECTION : 40 s restait trop court À
                //   FROID. Ces sources doivent CHERCHER le titre (13 à 29 s la première fois,
                //   avant que les caches soient chauds) PUIS résoudre les serveurs. Le budget
                //   couvrait à peine la recherche. Mesuré : FrenchStream 34,4 s et 1Jour1Film
                //   31,3 s au tout premier passage — donc au-delà du plafond, d'où des « 0
                //   serveurs » alors que la fiche était trouvée.
                //   Ces attentes ne bloquent personne : les serveurs arrivent au fil de l'eau et
                //   la lecture démarre sans les attendre. Et depuis le pré-chauffage lancé
                //   depuis la fiche, ce temps est de toute façon consommé avant la lecture.
                // ⚠ 2026-08-06, ANNULÉ LE JOUR MÊME : j'avais monté ces plafonds à 75/90 s pour
                //   laisser finir les sources lentes à froid. Combiné au pré-chauffage (qui
                //   lance une collecte complète en arrière-plan), ça a FIGÉ l'application :
                //   le pré-chauffage retenait les jetons de `heavyGate` pendant 75 s, donc la
                //   collecte réelle déclenchée par la lecture n'en obtenait plus aucun.
                //   Journal : HANGDUMP dans `CrossProviderResolver.resolveAndFetchServers`.
                //   Retour aux valeurs éprouvées. Ne les remonter QUE si le pré-chauffage est
                //   sorti du sémaphore ou annulé au démarrage d'une vraie collecte.
                if (source in HEAVY_SOURCES) {
                    heavyGate.withPermit { withTimeoutOrNull(40_000) { fetch() } ?: emptyList() }
                } else {
                    withTimeoutOrNull(60_000) { fetch() } ?: emptyList()
                }
            } catch (e: Exception) {
                failure = e
                Log.w(TAG, "$source failed: ${e.message}"); emptyList()
            }
            // 2026-08-21 : le signalement automatique vers GitHub a été retiré ; en cas
            //   d'échec on se contente du journal (ligne « $source failed » ci-dessus).
            pushServers(source, servers)
        }

        // ── Sources FEUILLES (pas de sous-backup → aucune récursion) ──────────────
        // 2026-09-04 (decision user, « supprimer totalement VOE sauf l'extracteur ») :
        //   l'emission VoeLibrary.serveursPour a ete RETIREE. Le compte VOE est
        //   inactif (« aucun compte actif ») et la bibliotheque perso a migre sur
        //   Vidara — l'emission « ONYX » ci-dessous (VidaraLibrary) sert desormais
        //   les MEMES fichiers. VoeExtractor reste en place pour les liens voe.sx
        //   que d'autres providers peuvent encore renvoyer.
        // 2026-09-02 : MÊME bibliothèque perso, servie depuis Vidara (serveur
        //   SUPPLÉMENTAIRE, migration VOE -> Vidara en cours). Réutilise la
        //   source « ONYX » : les fichiers apparaissent en « ONYX · Vidara » à
        //   côté de « ONYX · VOE », sous le même interrupteur. src = vidara.so/e/<code>
        //   -> résolu par VidaraExtractor (aucun changement de getVideo requis).
        //   Rattachement identique (VidaraLibrary est le jumeau de VoeLibrary).
        //   Clé VIDARA_API_KEY vide ⇒ aucun appel réseau.
        launch { emit("ONYX") {
            VidaraLibrary.serveursPour(
                tmdbId = resolvedTmdbId,
                titresConnus = knownTitles,
                annee = key.year,
                estUnFilm = key.isMovie,
                titrePrincipal = key.title,
                dureeMinSec = runtimeSecondes,
                dureeMaxSec = runtimeMaxSecondes,
                saison = key.season,
                episode = key.episode,
            )
        } }
        // 2026-09-05 : « Partage de la communaute » (index public des amis, Vidara) —
        //   memes fichiers que le dossier du TV Hub, proposes ici comme serveurs
        //   « Partage · <prenom> ». Aucune cle : l'index est un GET public.
        launch { emit("Partage") {
            VidaraCommunaute.serveursPour(
                tmdbId = resolvedTmdbId,
                titresConnus = knownTitles,
                annee = key.year,
                estUnFilm = key.isMovie,
                titrePrincipal = key.title,
                dureeMinSec = runtimeSecondes,
                dureeMaxSec = runtimeMaxSecondes,
                saison = key.season,
                episode = key.episode,
            )
        } }
        // 2026-09-06 (user : « sur Movix, Les Anges de la téléréalité, les serveurs Vegeta ne
        //   s'affichent pas sur les saisons ») : films + séries FR des panels Vegeta (index
        //   nx-data, cf. VegetaVod) proposés comme serveurs « Vegeta VOD · serveur N » sur
        //   n'importe quel provider. Rattachement par identifiant TMDB, sinon titre exact,
        //   sinon préfixe de titre pour les séries. URL directe, lue par VegetaVod.video.
        launch { emit("Vegeta VOD") {
            VegetaVod.serveursPour(
                tmdbId = resolvedTmdbId,
                titresConnus = knownTitles,
                annee = key.year,
                estUnFilm = key.isMovie,
                saison = key.season,
                episode = key.episode,
            )
        } }
        // 2026-07-06 : backups films/séries — SKIP sur provider anime (P3).
        // 2026-07-12 : Coflix RÉACTIVÉ — coflix.boston est en ligne (WordPress, WP REST API).
        //   CoflixSourceProvider mis à jour pour le nouveau format (cfServers inline + WP search).
        //   Essaie tous les titres connus (alt TMDB inclus) comme CoflixWiki.
        launch { emit("Coflix Boston") {
            var result = emptyList<Video.Server>()
            for (titleTry in knownTitles) {
                if (titleTry.isBlank()) continue
                result = if (key.isMovie) CoflixSourceProvider.getMovieSources(titleTry, key.year)
                          else CoflixSourceProvider.getEpisodeSources(titleTry, key.year, key.season, key.episode)
                if (result.isNotEmpty()) break
            }
            result
        } }
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
        // 2026-07-14 : DessinAnime.net (dessins animés + animes FR). CMS turc, structure propre
        //   (saison/épisode dans l'URL → zéro devinette d'id). Résolution POST /ajax/embed →
        //   iframe (emmmmbed & co, extracteurs existants). Matching titre STRICT.
        // 2026-08-08 : catalogue de DESSINS ANIMÉS → sauté quand TMDB confirme que le contenu
        //   n'est pas de l'animation (mesuré sur Star Trek : lancé pour rien). Filtre à sens
        //   unique, comme partout : si TMDB ne donne pas de genres, il part comme avant.
        if (!(notAnimationConfirmed && !isAnimeContent)) launch { emit("DessinAnimeNet") {
            var result = emptyList<Video.Server>()
            for (titleTry in knownTitles) {
                if (titleTry.isBlank()) continue
                result = if (key.isMovie) DessinAnimeNetProvider.getMovieSources(titleTry)
                          else DessinAnimeNetProvider.getEpisodeSources(titleTry, key.season, key.episode)
                if (result.isNotEmpty()) break
            }
            result
        } }
        // 2026-08-01 (décision user : « ne perds pas de temps avec ces deux-là, vire-les
        //   complètement ») : Cpasmieux ET Cpasmal RETIRÉS.
        //   Constat vérifié en direct sur les deux fiches du même film :
        //     • cpasmieux.life → le bloc lecteur affiche « Connectez-vous maintenant !
        //       Ça ne prend que 30 secondes pour regarder Le film » → AUCUN lien à extraire
        //       sans compte (d'où `extractServers → 0 serveurs`, qui était donc le
        //       comportement CORRECT, pas un parsing cassé).
        //     • cpasmal.my → mêmes symptômes (bloc `.fplayer`/`.video-box` vides, message de
        //       connexion, zéro iframe), le lecteur étant chargé en AJAX réservé aux membres.
        //       En prime il coûtait un « WebView Global Timeout » à CHAQUE film.
        //   Les deux tournent sur le même CMS (DataLife) et sont passés derrière un mur
        //   d'inscription : ils ne peuvent plus rien rapporter, seulement retarder la liste.
        //   Pour les réactiver un jour, il faudrait gérer un compte (comme TF1Auth/M6Auth).
        // 2026-07-10 : ANCIEN emit("Moviebox") par TITRE (getMovieboxSourcesByTitle = h5 search
        //   token-gaté → renvoyait 0 + doublonnait le log) SUPPRIMÉ. Le SEUL Moviebox est le
        //   nouveau emit par tmdbId (plus bas, API mobile signée). "ce qui en reste à part le
        //   nouveau backup" (user).
        // Papadustream ancien (captcha) — RETIRÉ (user 2026-07-07 "le papastream qui a captcha, vire-le").
        // Seul PapadustreamV2 (sans captcha) reste actif.
        // Nakios + Moiflix = via helpers exposés (WebJS). Nakios a besoin du tmdbId.
        // 2026-07-21 : Nakios n'a PLUS d'API par tmdbId (site refait en Laravel SSR) → il lui faut
        //   le TITRE pour dériver le slug de la fiche. On lui passe tous les titres connus.
        launch { emit("Nakios") {
            var res = emptyList<Video.Server>()
            for (t in knownTitles) {
                if (t.isBlank()) continue
                res = NakiosProvider.fetchNakiosBackupServers(
                    resolvedTmdbId ?: "", videoType, key.season, key.episode, titleHint = t,
                )
                if (res.isNotEmpty()) break
            }
            res
        } }
        // 2026-07-22 : LoiFlix (zoolingz.com + movix.bet) — MÊME moteur que Nakios, SOURCE SÉPARÉE
        //   (NakiosProvider.fetchLoiflixBackupServers, indépendante du chemin Nakios). Titre requis.
        launch { emit("LoiFlix") {
            var res = emptyList<Video.Server>()
            for (t in knownTitles) {
                if (t.isBlank()) continue
                res = NakiosProvider.fetchLoiflixBackupServers(
                    videoType, key.season, key.episode, titleHint = t,
                )
                if (res.isNotEmpty()) break
            }
            res
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

            // ── NetMirror EN ACCÈS DIRECT ────────────────────────────────────────────
            // 2026-08-08 (user : « fais en sorte que NetMirror arrive plus rapidement s'il est
            //   le seul à fournir certaines vieilles séries ») — MESURÉ sur Star Trek (1966) :
            //     10:08:20,3  départ de la collecte
            //     10:08:26,6  « MATCH trouvé id=253 »   ← 6,3 s passées dans p.search()
            //     10:08:34,9  serveur prêt
            //   Il passait par la boucle générique, qui commence TOUJOURS par `p.search(titre)`.
            //   Or la recherche de NetMirror est elle-même une recherche TMDB : on refaisait
            //   donc, pour lui seul, un travail que le registre venait de terminer — on connaît
            //   déjà `resolvedTmdbId`. En l'appelant directement, ces 6,3 s disparaissent.
            //   Sûr : `INLINE_BACKUPS_DISABLED = true` ⇒ son `getServers` ne rend que ses
            //   serveurs natifs et ne rappelle pas le registre (aucune récursion).
            //   Il est ajouté à `dedicated` plus bas pour ne pas être relancé par la boucle.
            if (!exclude.contains("NetMirror")) {
                launch {
                    emit("NetMirror") {
                        com.streamflixreborn.streamflix.providers.NetMirrorProvider
                            .getServers(resolvedTmdbId, videoType)
                    }
                }
            }

            // ── SERVEUR EMBED TMDB — Videasy VOSTFR uniquement ──────────────
            // 2026-07-03 (user "serveurs quasiment illimités" → "que du VF/VOSTFR,
            //   le reste ça sert à rien") : seul Videasy "fr" sert du VOSTFR.
            launch { emit("Embed") {
                // 2026-07-10 (user « Videasy Embed n'est QU'en VOSTFR mais arrive tout le temps en
                //   premier ») : Videasy est 100% API → il gagne la course sur les serveurs VF natifs
                //   (scrapés, plus lents). On lui donne une longueur de retard pour que les VF sortent
                //   d'abord ; il reste dispo, juste plus en tête.
                // 2026-08-02 : 5 s → 1 s. Videasy est en VOSTFR et arrivait trop tôt ; on le
                //   retardait pour qu'il ne prenne pas la tête. C'est désormais le tri par langue
                //   qui s'en charge (les VOSTFR passent derrière les VF), donc plus besoin de
                //   pénaliser son arrivée — et donc de faire patienter l'utilisateur.
                kotlinx.coroutines.delay(1_000L)
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

                // ⚠ 2026-08-11 — NE PAS RAJOUTER VixSrc / Vidsrc.net / VidLink / Vidsrc.Ru /
                //   2Embed ICI. Fait ce jour-là, puis défait le jour même, deux fois.
                //
                //   Ils vivaient dans TmdbProvider. Quand le user a demandé que TMDb cesse
                //   d'émettre des serveurs (« il peut recevoir, mais il n'émet pas »), je les
                //   ai déplacés ici pour ne rien lui faire perdre. Erreur : ce sont des
                //   services d'embed INTERNATIONAUX, leur défaut est l'anglais.
                //
                //   Sans marqueur de langue dans leur nom, le tri les prenait pour du VF et
                //   les mettait EN TÊTE, devant les vrais serveurs français. Le user l'a vu en
                //   une ouverture : « ils me proposent des serveurs qui ne sont pas dans la
                //   bonne langue ». J'ai alors proposé de les étiqueter « VO » pour qu'ils
                //   descendent au fond — il a tranché, et il a raison : « si tu penses que ces
                //   serveurs ne diffuseront jamais du VF ou du VOSTFR, ça sert à rien de les
                //   garder là ». C'est le cas. Ils ne servent pas de français.
                //
                //   C'était déjà la décision du 2026-07-03 (« que du VF/VOSTFR, le reste ça
                //   sert à rien »). Règle de l'app : VF ou VOSTFR, rien d'autre.
                //
                //   Seul Videasy « fr » reste, parce qu'il sert bien du VOSTFR et que son
                //   extracteur l'étiquette comme tel — donc trié correctement.
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

            // ── VIDZY (par tmdbId) — api.vidzy.org, VF/VOSTFR, sans clé ni compte ──────────
            //   2026-07-31 (user) : indexé PAR TMDB → aucun matching de titre, donc AUCUN
            //   mauvais film/série possible (contrairement aux providers qui cherchent par nom).
            //   /serie/{tmdb}/{s}/{e} et /movie/{tmdb} → iframe vidzy.cc lue par l'extracteur Vidzy.
            launch { emit("Vidzy") {
                com.streamflixreborn.streamflix.providers.VidzyTmdbProvider.fetchVidzyBackupServers(
                    tmdbId = resolvedTmdbId,
                    isMovie = key.isMovie,
                    season = key.season,
                    episode = key.episode,
                )
            } }

            // ── AFTERDARK — RETIRÉ le 2026-08-01 ────────────────────────────────────
            // DÉCISION user : « dorénavant on garde que les serveurs VF HD, il nous faut
            //   vraiment que ceux qui sont notifiés VF, pas de VOSTFR dans du VF » — après
            //   trois constats successifs de VOSTFR joué depuis un serveur AfterDark.
            // Pourquoi c'était IMPOSSIBLE à filtrer : cette source annonce « vf » sur
            //   TOUTES ses entrées (vérifié sur son propre site : FMX, VIDARA, LULUSTREAM,
            //   VIDSONIC, DDSTREAM, SAVE, VMOLY, FILELIONS… tous marqués VF, y compris ceux
            //   qui jouent du VOSTFR ; mesuré aussi via son API : 14 sources sur 15 en
            //   « vf » pour un même film). Son champ `language` ne vaut donc rien, et comme
            //   ses liens sont des pages d'EMBED (et non des manifestes HLS), la sonde qui
            //   lit les balises LANGUAGE ne peut pas trancher AVANT lecture.
            //   → Aucun moyen de garantir « pas de VOSTFR » en le gardant actif.
            // Au passage, il coûtait ~23 s sur CHAQUE film (tout le reste terminait à
            //   11:19:07, lui rendait la main à 11:19:30) + une WebView avec bypass CF et
            //   ad-gate, et sa page de gate a un UUID codé en dur.
            // Ce qu'on perd : ses copies alternatives chez les mêmes hébergeurs (utiles
            //   quand un fichier est supprimé ailleurs). `AfterDarkProvider` est CONSERVÉ —
            //   restaurer ce bloc suffit à réactiver la source.

            // ── NABISTREAM (par tmdbId) — dramas asiatiques VOSTFR, HLS tanastream + sous-titres FR ──
            launch {
                emit("Nabistream") {
                    com.streamflixreborn.streamflix.providers.NabistreamProvider
                        .fetchNabistreamBackupServers(resolvedTmdbId, videoType, key.season, key.episode, knownTitles.toList())
                }
            }

            // ── PURSTREAM (2026-08-16) — source FR qui héberge ses PROPRES flux ──
            //   Recherche par titre → confirmation par tmdbId EXACT sur la fiche (le tmdbId
            //   n'est PAS dans les résultats de recherche, seulement dans /sheet) → donc
            //   zéro risque d'homonyme. Le stream_url est un master HLS direct, sans jeton :
            //   ExoPlayer le lit nativement, aucun extracteur nécessaire.
            //   FILMS **ET** SÉRIES — c'est ce que le chemin Movix (api/purstream/movie/…)
            //   ne couvre pas : chez eux `purstream/tv` répond 404.
            launch {
                emit("Purstream") {
                    com.streamflixreborn.streamflix.providers.PurstreamProvider
                        .fetchPurstreamBackupServers(resolvedTmdbId, videoType, key.season, key.episode, knownTitles.toList())
                }
            }

            // ── TV HUB (par titre) — France.tv/Arte GRATUIT depuis le catalogue replay caché ──
            //   Matching STRICT (titre + saison/épisode). getVideo route auto vers le provider « TV Hub ».
            launch {
                emit("TV Hub") {
                    com.streamflixreborn.streamflix.providers.LiveTvHubProvider
                        .fetchTvHubReplayBackupServers(videoType, key.season, key.episode, knownTitles.toList())
                }
            }

            // ── FILESEARCH (par titre) — filesearch.tools, fichiers DIRECTS .mp4/.mkv multi-hôtes ──
            //   Matching STRICT (titre + année films / SxxExx séries). getVideo renvoie le fichier direct.
            launch {
                emit("FileSearch") {
                    com.streamflixreborn.streamflix.providers.FileSearchProvider
                        // 2026-08-08 : on transmet « l'œuvre est française » (langue d'origine TMDB).
                    //   FileSearch lève alors l'exigence d'un marqueur FR dans le nom du fichier —
                    //   voir le commentaire de fetchFileSearchBackupServers (cas Radarr).
                    .fetchFileSearchBackupServers(
                        videoType, key.season, key.episode, key.year, knownTitles.toList(),
                        oeuvreFrancaise = langueOriginale.equals("fr", ignoreCase = true),
                    )
                }
            }

            // ── OK.RU (par titre) — vieilles séries et films FR introuvables ailleurs ──
            // 2026-08-08 (user : « je viens de trouver un site qu'il faudrait absolument
            //   ajouter, avec une recherche stricte car il y a pas mal de mauvaises choses ») :
            //   ok.ru avait « Star Trek » (1966) en VF le soir où AUCUN des neuf sites FR de
            //   l'app ne l'avait. Matching strict à cinq filtres, dont la durée TMDB — voir
            //   le commentaire de OkRuProvider.retenir.
            //   Le titre FR de l'épisode sert de 2ᵉ preuve de version française quand le nom
            //   du fichier ne porte pas de tag (beaucoup d'uploads n'en ont pas).
            launch {
                emit("ok.ru") {
                    com.streamflixreborn.streamflix.providers.OkRuProvider
                        .fetchOkRuBackupServers(
                            titres = knownTitles.toList(),
                            saison = key.season,
                            episode = key.episode,
                            annee = effectiveYear,
                            // 1ʳᵉ version : le runtime TMDB n'est pas encore remonté jusqu'ici.
                            //   `null` ⇒ le provider applique son garde-fou de durée interne
                            //   (plancher 15 min pour un épisode, 55 min pour un film), qui
                            //   suffit déjà à écarter bandes-annonces et extraits. À affiner
                            //   en passant le vrai runtime quand on l'aura sous la main.
                            runtimeS = null,
                            titreEpisodeFr = null,
                        )
                }
            }

            // ── ARCHIVE.ORG (par titre) — vieilles séries et films FR du domaine public ──
            // 2026-08-11 (user : « tu vas ajouter archivesorg à l'application comme tu as fait
            //   pour ok.ru… on peut choper des très vieilles séries dedans ») : Internet Archive
            //   héberge des séries des années 50-70 doublées en français que plus aucun site de
            //   streaming n'indexe — La Quatrième Dimension, Zorro, Les Envahisseurs, Chapeau
            //   melon et bottes de cuir…
            //
            //   Un « item » archive.org est un CONTENEUR : celui de La Quatrième Dimension porte
            //   310 vidéos, soit la série entière. Le provider cherche donc l'item par titre,
            //   puis l'ÉPISODE par son nom de fichier — voir l'en-tête d'ArchiveOrgProvider.
            //
            //   Règle de langue STRICTE (la même que pour ok.ru) : VF ou VOSTFR prouvés, jamais
            //   de VO. Les sources qui n'ont que du VOSTFR sont étiquetées « VOSTFR » en clair.
            //
            //   ⚠ `runtimeSecondes` est le discriminant décisif ici : mesuré sur « La Nuit des
            //   morts-vivants », archive.org rend trois items au titre, à l'année et à la langue
            //   exacts dont les fichiers font 200 s et 68 s — des bandes-annonces. Seule la durée
            //   les écarte.
            launch {
                emit("archive.org") {
                    com.streamflixreborn.streamflix.providers.ArchiveOrgProvider
                        .fetchArchiveOrgBackupServers(
                            titres = knownTitles.toList(),
                            saison = key.season,
                            episode = key.episode,
                            annee = effectiveYear,
                            runtimeMinS = runtimeSecondes,
                            runtimeMaxS = runtimeMaxSecondes,
                        )
                }
            }

            // ── RUTUBE (par titre) — plateforme vidéo russe, films/séries FR sans pub ──
            // 2026-08-13 (user : « j'ai trouvé un nouveau site à intégrer… ça va faire comme
            //   ok.ru / archive.org, on aura du contenu supplémentaire ») : Rutube héberge des
            //   films et de vieilles séries doublés/sous-titrés FR que les sites de l'app
            //   n'indexent pas (l'exemple du user : « Inspecteur Derrick » en VF). API propre,
            //   anonyme, sans Cloudflare. Matching STRICT (titre complet + année/SxxExx + durée
            //   TMDB), langue par le titre (marqueur VF/VOSTFR ou titre FR demandé), is_paid
            //   écarté (anti-DRM). Voir RutubeProvider.
            // ── 2026-08-16 : RUTUBE RETIRÉ DE LA RECHERCHE FILMS/SÉRIES ──────────
            //   Décision user, après plusieurs faux positifs successifs : « là ça va plus du
            //   tout, je le vire carrément de l'équation des recherches films et séries ».
            //
            //   Rutube n'est pas un site de VOD : c'est une plateforme vidéo généraliste. Ses
            //   titres citent le nom de l'œuvre sans la contenir — clips musicaux avec une
            //   image de fond, génériques, conférences, vidéos de fans. Trois durcissements
            //   successifs (marqueurs hors-sujet, couverture inverse à 50 %, durée TMDB
            //   obligatoire sur les épisodes) ont chacun éliminé une famille de faux positifs
            //   sans jamais tarir la suivante — sur « GoT » il accroche jusqu'au verbe anglais
            //   *got* : « KORN - Got the Life », « Masterboy - I Got To Give It Up »…
            //   Application du principe du user : mieux vaut perdre la source qu'un mauvais
            //   contenu.
            //
            //   ⚠ SON RÔLE MUSICAL EST INTACT — il vit ailleurs et ne passe PAS par ici :
            //     • LiveTvHubProvider (fetchClipMeta / getVideo) — le TV hub
            //     • RutubeFolder (searchClipsPaged) — la navigation
            //     • RutubeExtractor → RutubeProvider.resolveById — la lecture
            //   Seul `fetchRutubeBackupServers` est débranché. Le routage getVideo « Rutube »
            //   plus bas RESTE nécessaire pour les serveurs déjà en favoris/historique.
            //   Pour réactiver : décommenter ce bloc.
            /*
            launch {
                emit("Rutube") {
                    com.streamflixreborn.streamflix.providers.RutubeProvider
                        .fetchRutubeBackupServers(
                            titres = knownTitles.toList(),
                            titreFr = key.title,
                            saison = key.season,
                            episode = key.episode,
                            annee = effectiveYear,
                            runtimeMinS = runtimeSecondes,
                            runtimeMaxS = runtimeMaxSecondes,
                            // Titre FR de l'épisode : Rutube nomme les épisodes par leur NOM
                            //   (« Inspecteur Derrick- Appel De Nuit »), jamais « S01E02 ». Sans
                            //   lui, aucun épisode Rutube ne matchait — l'exemple même du user.
                            titreEpisodeFr = (videoType as? com.streamflixreborn.streamflix.models.Video.Type.Episode)?.title,
                        )
                }
            }
            */

            // ── MOVIEBOX (par tmdbId) — API mobile signée aoneroom ────────────────
            // 2026-07-10 (user "on transforme Moviebox en backup principal pour tous
            //   les autres") : Moviebox rejoint le registre COMME les autres, par tmdbId.
            //   Identité par id (findSubjectId matche titre+année STRICT côté aoneroom) →
            //   zéro faux positif. Flux réels (CDN hakunaymatata + cookies CloudFront),
            //   lus via MovieboxExtractor (court-circuit direct). Films + séries/épisodes.
            // 2026-08-01 (DÉCISION user : « supprime-moi Moviebox ») : backup Moviebox RETIRÉ.
            //   Établi en direct sur moviebox.ph, sur le film qui échouait (« Les Spécialistes ») :
            //     • le film EST présent, avec même une fiche dédiée « [Version française] » ;
            //     • MAIS la page affiche « Déverrouiller maintenant » et ne charge qu'une
            //       BANDE-ANNONCE (mp4 de 112 s) — le film complet est derrière un mur premium ;
            //     • conséquence côté API : `play-info` renvoie bien le champ `signCookie`, mais
            //       VIDE (vérifié : `MVBX-STREAMKEYS … signCookieTrouve=false`). Or ce cookie
            //       CloudFront est obligatoire → le CDN répond HTTP 428 (Precondition Required),
            //       en 1080p COMME en 480p (les deux testés).
            //   La qualité affichée (« Français 1080p ») venait du champ `resolutions` déclaré
            //   par l'API, pas d'une sonde réelle : elle ne prouvait donc rien sur la lecture.
            //   → Aucun flux exploitable sans compte payant : on ne l'interroge plus.
            //   Le code de MovieboxProvider est CONSERVÉ (il sert encore aux backups croisés
            //   `mbbackup__` de DessinAnime et `nm_mb__` de NetMirror) ; il suffit de restaurer
            //   ce bloc pour réactiver la source si Moviebox rouvrait ses flux.
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
                    // 2026-07-13 — DIAGNOSTIC DÉFINITIF (screenshot user, Slime). AniCloud met le
                    //   Film / l'OAV / les spin-offs DANS la liste des saisons (positions 3,6,7) MAIS
                    //   les NOMME distinctement (« Film », « OAV », « The Slime Diaries ») ; les vraies
                    //   saisons portent le nom « Saison N » qui donne le VRAI numéro (« Saison 4 » =
                    //   vraie S4, à la position 5). AnimeSama, lui, sépare Saisons/Films/OAV en onglets
                    //   → sa « Saison N » = vraie saison N. DONC on matche la section AniCloud dont le
                    //   NOM est « Saison {key.season} » : ça exclut naturellement Film/OAV (user : « le
                    //   film et les OAV ne doivent pas être comptés comme des saisons »). Compter par
                    //   ORDRE était faux (le Film décalait tout). Épisode = local (étape 7).
                    val saisonRegex = Regex("(?:saison|season)\\s*(\\d+)", RegexOption.IGNORE_CASE)
                    var chosenName = ""
                    for (i in 0 until sections.length()) {
                        val sec = sections.getJSONObject(i)
                        val nm = sec.optString("name", "")
                        val m = saisonRegex.find(nm)
                        if (m != null && m.groupValues[1].toIntOrNull() == key.season) {
                            targetSectionId = sec.optInt("id", -1); chosenName = nm; break
                        }
                    }
                    if (targetSectionId < 0) {
                        Log.i(TAG, "AniCloud anime/$acSlug : aucune section nommée « Saison ${key.season} » (Film/OAV exclus) → skip")
                        return@emit emptyList()
                    }
                    Log.i(TAG, "AniCloud anime/$acSlug : saison ${key.season} → section « $chosenName » (id=$targetSectionId)")
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
                    // NUMÉRO LOCAL (algo user « chaque saison repart à l'épisode 1 ») : dans la section,
                    //   le 1ᵉʳ épisode = épisode 1, quel que soit son numéro affiché (absolu ou non).
                    //   Cible = min(episode_number) + key.episode - 1. Gère AniCloud absolu (77,78…) ET
                    //   par-saison (1,2…). Toutes langues de cet épisode.
                    var minNum = Int.MAX_VALUE
                    for (i in 0 until episodes.length()) {
                        val n = episodes.getJSONObject(i).optInt("episode_number", -1)
                        if (n in 1 until minNum) minNum = n
                    }
                    val targetNum = if (minNum == Int.MAX_VALUE) key.episode else minNum + key.episode - 1
                    for (i in 0 until episodes.length()) {
                        val ep = episodes.getJSONObject(i)
                        if (ep.optInt("episode_number", -1) == targetNum) targetEps.add(ep)
                    }
                    Log.i(TAG, "AniCloud section=$targetSectionId : local ép ${key.episode} → absolu $targetNum (base $minNum)")
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
        // 2026-07-17 (user « la série H : sur Wiflix 3 serveurs, sur Movix 1… avec les
        //   backups ça devrait pas arriver ») : la boucle générique (Wiflix / FrenchStream /
        //   VoirDrama… recherche par TITRE) était sautée dès que le titre faisait 1 caractère
        //   (« H ») → aucun de ces backups ne remontait ; seuls les providers par ID TMDB
        //   (Frembed) agrégeaient. On l'AUTORISE pour 1 caractère UNIQUEMENT si l'ANNÉE est
        //   connue : le matcher (matchesKnownTitles → branche shortKnown+année, ~L372) exige
        //   déjà « token exact isolé dans le candidat + année ±1 » = discriminant fort, zéro
        //   faux positif. Sans année (pas de discriminant) → toujours sauté, comportement inchangé.
        if (key.title.length >= 2 || (key.title.isNotBlank() && effectiveYear != null)) {
            // 2026-07-04 : seuls les providers AVEC une source dédiée ci-dessus sont exclus
            //   de la boucle générique. Wiflix/Cloudstream/FrenchAnime (natifs) reviennent DANS
            //   la boucle générique par titre (matching STRICT). DessinAnime (WebJsProvider)
            //   INCLUS : INLINE_BACKUPS_DISABLED=true empêche toute récursion dans son
            //   getServers → safe comme backup source. Classé WEBVIEW_HEAVY → 2ᵉ vague (5s).
            // 2026-07-08 : Cloudstream RETIRÉ du set dedicated — il utilise l'API MovieBox+
            //   (aoneroom.com), PAS la même API que Movix. Ses serveurs NE sont PAS des doublons.
            //   Le remettre dans la boucle générique (recherche par titre via findSubjectId).
            // 2026-08-08 : NetMirror rejoint les sources dédiées — il est désormais appelé
            //   directement avec `resolvedTmdbId` (voir plus haut), la boucle générique ne
            //   doit plus le relancer via `p.search()`.
            val dedicated = setOf("CoflixWiki", "Coflix Boston", "Moviebox", "Nakios", "Movix", "Frembed", "NetMirror")
            // 2026-07-12 (user) : providers SPÉCIALISÉS anime/dessins animés qui n'hébergent AUCUN
            //   film live-action → à sauter quand le contenu est confirmé non-animation. DessinAnime
            //   EXCLU de cette liste (user « dessin animé lui doit recevoir tout » = contenu mixte).
            val animeOnlyProviders = setOf("AnimeSama", "VoirAnime", "FrenchManga", "FrenchAnime", "FRAnime", "Franime")
            val skipAnimeOnly = notAnimationConfirmed && !isAnimeContent
            if (skipAnimeOnly) Log.i(TAG, "fetchAll contenu NON-animation confirmé (genre 16 absent) → skip providers anime-only")

            // 2026-08-08 (user : « on a pas mal de serveurs appelés pour rien alors qu'ils
            //   n'auront jamais rien ») — DEUX FAMILLES DE PLUS, mesurées sur Star Trek (1966,
            //   série américaine live-action) où elles ont toutes rendu 0 serveur :
            //     VoirDrama   24 703 ms   → catalogue de dramas asiatiques
            //     DessinAnime  6 528 ms   → catalogue de dessins animés
            //   Elles ne trouveront JAMAIS une série américaine live-action des années 60.
            //   Même principe qu'au-dessus, filtre à sens unique : on ne saute que sur une
            //   information POSITIVE de TMDB (langue d'origine connue / genres connus).
            //   Si TMDB ne dit rien, tout part comme avant.
            val LANGUES_ASIATIQUES = setOf("ko", "ja", "zh", "cn", "th", "vi", "id", "tl")
            val dramaAsiatiqueProviders = setOf("VoirDrama")
            val skipDramaAsiatique = langueOriginale != null && langueOriginale !in LANGUES_ASIATIQUES
            if (skipDramaAsiatique) Log.i(TAG, "fetchAll langue d'origine '$langueOriginale' non asiatique → skip VoirDrama")
            // Dessins animés : même règle que l'anime (genre 16 absent = jamais chez eux).
            val dessinAnimeProviders = setOf("DessinAnime")
            if (skipAnimeOnly) Log.i(TAG, "fetchAll → skip providers dessins animés")
            Provider.providers.keys
                .filter { p ->
                    // 2026-07-12 : providers orientés FILMS → sautés pour les séries/épisodes.
                    !(!key.isMovie && p.name in FILM_ONLY_PROVIDERS) &&
                    // Filtre à sens unique : non-animation → on saute les providers anime purs.
                    !(skipAnimeOnly && p.name in animeOnlyProviders) &&
                    // … et les catalogues de dessins animés, pour la même raison.
                    !(skipAnimeOnly && p.name in dessinAnimeProviders) &&
                    // Contenu non asiatique → on saute les catalogues de dramas asiatiques.
                    !(skipDramaAsiatique && p.name in dramaAsiatiqueProviders) &&
                    p.name !in exclude && p.name !in dedicated &&
                        // 2026-07-09 (user « remets aplouf dans les backups ») : aplouf RÉACTIVÉ
                        //   comme source de backup (recherche par titre via la boucle générique).
                        // 2026-07-09 (user "retire les serveurs Cloudstream des backups, qu'il soit
                        //   que sur son propre provider") : Cloudstream ne fournissait PLUS de backup
                        //   aux autres providers → disponible uniquement sur le provider Cloudstream.
                        // 2026-08-08 (décision user — EXCLUSION LEVÉE) : mesuré sur Star Trek (1966,
                        //   tmdb 253) avec Movix en provider actif. Les 9 sources FR interrogées ne
                        //   l'ont PAS (vérifié à la main sur flemmix, coflix, nakios, 1jour1film,
                        //   fs16 : elles n'ont que les films et les séries dérivées), et Cloudstream
                        //   n'apparaissait dans AUCUNE ligne du log — non pas parce qu'il ne trouvait
                        //   rien, mais parce que cette ligne l'écartait avant l'appel.
                        //   API MovieBox+ interrogée en direct (api6.aoneroom.com, signature HMAC-MD5
                        //   reproduite) : subjectId 4465955628443386568, « Star Trek » 1966-09-08,
                        //   subjectType 2 (série), saison 1 / 30 épisodes, flux MP4 DIRECTS en
                        //   360-480-720-1080p + sous-titres FR (get-ext-captions lan="fr").
                        //   → seule source, avec NetMirror, à avoir cette série, et la seule en 1080p.
                        //   Si ça ramène trop de bruit sur d'autres titres, remettre la ligne
                        //   ci-dessous plutôt que de bricoler ailleurs.
                        // !p.name.equals("Cloudstream", ignoreCase = true) &&
                        // 2026-07-23 (décision user — RÉACTIVATION de l'exclusion du 09/07) :
                        //   NetMirror REMIS dans les backups pour en faire profiter les autres
                        //   providers (il est peu utilisé en solo). Vérifié : il joue films ET
                        //   séries (« La Petite Maison dans la Prairie » OK) ; l'ancien « Video ID
                        //   not found » était CONTENT-SPECIFIC (contenu qu'il n'a pas), pas un bug —
                        //   et ce cas est géré en dead-content (aucun blacklist de l'extracteur).
                        //   Anti-récursion OK : sur son propre onglet il est déjà dans `exclude`
                        //   (= setOf(providerName), cf. PlayerViewModel). Les users qui n'en veulent
                        //   pas le coupent via le toggle par-source (Paramètres).
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
                    // 2026-07-12 : 2ᵉ vague CF restaurée. Les providers CF/WebView-lourds (WEBVIEW_HEAVY,
                    //   Wiflix EXCLU) attendent CF_SECOND_WAVE_MS avant de chercher → les backups sans CF
                    //   passent en premier.
                    launch {
                        if (p.name in WEBVIEW_HEAVY_PROVIDERS) {
                            kotlinx.coroutines.delay(CF_SECOND_WAVE_MS)
                        }
                        emit(p.name) {
                        // 2026-07-09 : DIAGNOSTIC complet pour chaque provider de la boucle générique.
                        //   Log le résultat de chaque étape (search, type, matching, resolve, servers)
                        //   pour comprendre POURQUOI un provider ne remonte pas de serveurs.
                        fun typeOk(item: com.streamflixreborn.streamflix.adapters.AppAdapter.Item): Boolean =
                            if (key.isMovie) item is com.streamflixreborn.streamflix.models.Movie
                            else item is com.streamflixreborn.streamflix.models.TvShow

                        fun titreDe(item: Any): String =
                            (item as? com.streamflixreborn.streamflix.models.Movie)?.title
                                ?: (item as? com.streamflixreborn.streamflix.models.TvShow)?.title ?: ""
                        fun idDe(item: Any): String =
                            (item as? com.streamflixreborn.streamflix.models.Movie)?.id
                                ?: (item as? com.streamflixreborn.streamflix.models.TvShow)?.id ?: ""
                        // 2026-08-01 : l'année peut être dans le TITRE (« … (2026) ») ou dans
                        //   l'IDENTIFIANT/slug (« les-specialistes-vf-1985 ») — mesuré : pour
                        //   « Les Spécialistes », le titre renvoyé est nu (« LES SPECIALISTES »)
                        //   et SEUL le slug porte l'année. On regarde donc les deux.
                        fun anneeDe(item: Any): Int? =
                            Regex("""\b(19|20)\d{2}\b""")
                                .findAll(titreDe(item) + " " + idDe(item))
                                .map { it.value.toInt() }
                                .lastOrNull()

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
                            // 2026-08-27 : plafond ramene de 30 a 5 (sa valeur d'origine).
                            //   Le 30 avait ete pose pour diagnostiquer « Ca » (18 resultats
                            //   FrenchStream dont on ne voyait que les 5 premiers). Utile ce
                            //   jour-la, nuisible en permanence : 28 sources x 30 lignes x 2
                            //   passes = plusieurs centaines de lignes par fiche ouverte, en
                            //   Log.i donc VISIBLE EN PRODUCTION. Ca noie le tampon logcat —
                            //   mesure sur l'Oppo le meme jour : 45 secondes d'historique
                            //   seulement, la manip du user etait deja perdue quand j'ai lu.
                            //   On garde en revanche l'annee et le slug ajoutes ce jour-la :
                            //   5 candidats bien decrits valent mieux que 30 mal decrits.
                            searchResults.take(5).forEachIndexed { i, item ->
                                val itemTitle = (item as? com.streamflixreborn.streamflix.models.Movie)?.title
                                    ?: (item as? com.streamflixreborn.streamflix.models.TvShow)?.title ?: "?"
                                val itemType = when (item) {
                                    is com.streamflixreborn.streamflix.models.Movie -> "Movie"
                                    is com.streamflixreborn.streamflix.models.TvShow -> "TvShow"
                                    else -> item.javaClass.simpleName
                                }
                                val tok = typeOk(item)
                                val wm = if (tok) workMatches(itemTitle, knownTitles, effectiveYear, key.isMovie) else false
                                val st = if (tok && wm) seasonTitleOk(itemTitle, key.isMovie, key.season) else false
                                Log.i(TAG, "DIAG [${p.name}]   [$i] '$itemTitle' type=$itemType " +
                                    "annee=${anneeDe(item)} id=${idDe(item)} " +
                                    "typeOk=$tok workMatch=$wm seasonOk=$st")
                            }
                        }

                        // 2026-08-01 (user : « 1Jour1Film n'a qu'un serveur et il ne marche pas ») :
                        //   on prenait le PREMIER candidat valide. Sur les titres HOMONYMES, c'est
                        //   souvent le mauvais. Cas mesuré : « Les Spécialistes » (2026, In the Grey)
                        //   → le site propose AUSSI « LES SPECIALISTES (1985) », film français
                        //   homonyme. On tombait sur la fiche 1985 (durée 1 h 29 au lieu de 1 h 38),
                        //   donc sur ses serveurs, au lieu de `in-the-grey-vf-2026` et de ses
                        //   3 lecteurs VF fonctionnels.
                        //   → Parmi les candidats VALIDES, on préfère désormais celui dont le titre
                        //     porte l'ANNÉE du film demandé (ces sites l'affichent : « … (2026) »).
                        //     À défaut d'année explicite, comportement inchangé (1ᵉʳ valide).
                        val candidatsValides = searchResults.filter { item ->
                            if (!typeOk(item)) return@filter false
                            val t = (item as? com.streamflixreborn.streamflix.models.Movie)?.title
                                ?: (item as? com.streamflixreborn.streamflix.models.TvShow)?.title
                                ?: return@filter false
                            workMatchesStrict(t, knownTitles, key.year, key.isMovie) &&
                                seasonTitleOk(t, key.isMovie, key.season) &&
                                sousTitreCompatible(t, key.title)
                        }
                        val anneeVoulue = key.year?.takeIf { it > 1800 }
                        // 2026-08-02 (Hajime no Ippo) : le SOUS-TITRE sert à PRÉFÉRER, pas à
                        //   rejeter. Mesuré sur VoirAnime, qui renvoie 10 fiches pour
                        //   « hajime no ippo » : Rising (S3), New Challenger (S2), Champion
                        //   Road, Mashiba vs Kimura… ET « Hajime no Ippo (VF) » (S1, la bonne).
                        //   Toutes passent workMatches — on prenait donc la 1ʳᵉ, c.-à-d. Rising.
                        //   → Quand le titre demandé n'a PAS de sous-titre, on privilégie le
                        //     candidat qui n'en a pas non plus ; s'il en a un, on privilégie
                        //     celui qui partage un mot avec lui.
                        //   Mais on ne REJETTE jamais sur ce seul critère : Wiflix ne propose que
                        //   « Hajime no Ippo : The Fighting » (= la saison 1, bon contenu) et le
                        //   rejeter faisait perdre un provider valide.
                        fun scoreSousTitre(c: Any): Int =
                            if (sousTitreCompatible(titreDe(c), key.title)) 0 else 1
                        val triés = candidatsValides.sortedBy { scoreSousTitre(it) }
                        var match = if (anneeVoulue != null) {
                            val compatible = triés.firstOrNull { c ->
                                val a = anneeDe(c); a == null || kotlin.math.abs(a - anneeVoulue) <= 1
                            }
                            val premier = candidatsValides.firstOrNull()
                            if (compatible !== premier && premier != null) {
                                Log.i(
                                    TAG,
                                    "DIAG [${p.name}] CANDIDAT PRÉFÉRÉ : '${titreDe(compatible ?: premier)}' " +
                                        "au lieu de '${titreDe(premier)}' (année=${anneeDe(premier)} vs $anneeVoulue, sous-titre)",
                                )
                            }
                            compatible
                        } else {
                            val choisi = triés.firstOrNull()
                            val premier = candidatsValides.firstOrNull()
                            if (choisi !== premier && premier != null && choisi != null) {
                                Log.i(
                                    TAG,
                                    "DIAG [${p.name}] CANDIDAT PRÉFÉRÉ : '${titreDe(choisi)}' " +
                                        "au lieu de '${titreDe(premier)}' (sous-titre plus proche de '${key.title}')",
                                )
                            }
                            choisi
                        }
                        // 2026-07-08 : essayer TOUS les titres connus (alternatifs TMDB inclus)
                        if (match == null && knownTitles.size > 1) {
                            Log.i(TAG, "DIAG [${p.name}] pas de match primaire, essai titres alternatifs: $knownTitles")
                            for (altQuery in knownTitles) {
                                if (altQuery.equals(key.title, ignoreCase = true)) continue
                                if (altQuery.isBlank()) continue
                                val altResults = try { p.search(altQuery, 1) } catch (_: Exception) { emptyList() }
                                Log.i(TAG, "DIAG [${p.name}] alt search('$altQuery') → ${altResults.size} résultats")
                                // 2026-08-13 (user « Inspecteur Derrick n'a aucun serveur alors
                                //   qu'il y en a ») : la recherche alternative REMONTE des
                                //   résultats (« Derrick » → 4 sur 1Jour1Film, 1 sur
                                //   FrenchStream) puis les jette sans dire pourquoi — seule la
                                //   recherche PRINCIPALE détaillait ses candidats. On journalise
                                //   ici chaque candidat et le verdict de CHAQUE garde-fou, pour
                                //   savoir lequel rejette au lieu de le deviner.
                                altResults.take(6).forEachIndexed { iAlt, cand ->
                                    val tc = (cand as? com.streamflixreborn.streamflix.models.Movie)?.title
                                        ?: (cand as? com.streamflixreborn.streamflix.models.TvShow)?.title
                                    if (tc != null) {
                                        Log.i(
                                            TAG,
                                            "DIAG [${p.name}]   alt[$iAlt] '$tc' type=${typeOk(cand)} " +
                                                "annee=${anneeDe(cand)} " +
                                                "work=${workMatchesStrict(tc, knownTitles, effectiveYear, key.isMovie)} " +
                                                "saison=${seasonTitleOk(tc, key.isMovie, key.season)} " +
                                                "sousTitre=${sousTitreCompatible(tc, key.title)}",
                                        )
                                    } else {
                                        Log.i(TAG, "DIAG [${p.name}]   alt[$iAlt] (ni Movie ni TvShow) → ignoré")
                                    }
                                }
                                match = altResults.firstOrNull { item ->
                                    if (!typeOk(item)) return@firstOrNull false
                                    val t = (item as? com.streamflixreborn.streamflix.models.Movie)?.title
                                        ?: (item as? com.streamflixreborn.streamflix.models.TvShow)?.title
                                        ?: return@firstOrNull false
                                    // 2026-08-01 : même garde-fou homonyme que sur la recherche
                                    //   principale — l'année du slug/titre doit être compatible.
                                    val a = anneeDe(item)
                                    val anneeOk = anneeVoulue == null || a == null ||
                                        kotlin.math.abs(a - anneeVoulue) <= 1
                                    anneeOk &&
                                        workMatchesStrict(t, knownTitles, effectiveYear, key.isMovie) &&
                                        seasonTitleOk(t, key.isMovie, key.season) &&
                                        // 2026-08-02 : rejet sur le sous-titre CONSERVÉ (faux
                                        //   positifs type Wiflix sur une série qu'il n'a pas).
                                        sousTitreCompatible(t, key.title)
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
                                // 2026-08-01 (testeur : « Hajime no Ippo E3, VoirAnime ne propose
                                //   pas le bon épisode ») : ce fallback accepte un résultat unique
                                //   dès qu'il partage UN mot. Or « Hajime no Ippo: Rising » partage
                                //   « hajime » avec « Hajime no Ippo : The Fighting »… alors que
                                //   Rising est la SAISON 3, une autre série. Le sous-titre doit
                                //   donc être compatible ici AUSSI (il ne l'était que sur la
                                //   recherche principale — d'où le trou).
                                // 2026-08-02 (user : « Wiflix ne possède pas cet anime, à mon avis
                                //   c'était un faux positif ») : le sous-titre RESTE un motif de
                                //   rejet ici. Ce fallback accepte un résultat unique dès qu'il
                                //   partage UN mot — c'est précisément ce qui laissait Wiflix
                                //   revendiquer « Hajime no Ippo : The Fighting » alors qu'il n'a
                                //   pas la série. Rejet confirmé UTILE : « pas de serveur plutôt
                                //   qu'un mauvais serveur ».
                                val stOk = sousTitreCompatible(trustTitle, key.title)
                                if (sharesWord && seasonOk && stOk) {
                                    match = cand
                                    Log.i(TAG, "DIAG [${p.name}] FALLBACK confiance search unique: '$trustTitle' (1 résultat typé, mot commun OK)")
                                } else {
                                    Log.i(TAG, "DIAG [${p.name}] FALLBACK REFUSÉ '$trustTitle' (sharesWord=$sharesWord seasonOk=$seasonOk sousTitreOk=$stOk) → 0 serveur")
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
                        // ── 2026-08-06 (user : « est-ce qu'on ferme trop tôt les portes pour
                        //   l'arrivée des serveurs ? ») — OUI, et c'était bien vu ─────────────
                        //   Journal de deux collectes successives sur la même série :
                        //     1re passe : FrenchStream « MATCH trouvé » puis → 0 serveurs (22,0 s)
                        //                 1Jour1Film  « MATCH trouvé » puis → 0 serveurs (23,2 s)
                        //     2e passe  : FrenchStream → 8 serveurs (13,6 s)
                        //                 1Jour1Film  → 3 serveurs (11,8 s)
                        //   Ces sources n'étaient pas bredouilles : elles avaient trouvé la bonne
                        //   fiche et se faisaient couper PENDANT la résolution des serveurs.
                        //   Or une source qui a déjà identifié le titre est précisément celle
                        //   qu'il faut le moins interrompre — on sait qu'elle a le contenu.
                        //   La recherche du titre, elle, garde ses plafonds : c'est là qu'il faut
                        //   renoncer vite quand une source ne connaît pas l'œuvre.
                        // ⚠ Remis à 30 s le 2026-08-06 avec les plafonds ci-dessus : la hausse à
                        //   45 s participait au blocage. Ne pas la refaire isolément.
                        // 2026-08-07 (user : « il devrait pas être coupé ») — budget ÉLARGI pour
                        //   les seules sources derrière Cloudflare. Journal du jour, House of the
                        //   Dragon S3E3 : Wiflix a rendu ses 3 serveurs à 41 747 ms… soit 2 ms
                        //   APRÈS la coupure. Une source qui a déjà identifié la fiche est celle
                        //   qu'il faut le moins interrompre. ⚠ Ciblé : les autres restent à 30 s,
                        //   la hausse GLOBALE à 45 s du 06/08 avait aggravé le blocage.
                        //   La mémoire de slug côté WiflixProvider fait tomber les passages
                        //   suivants sous les 30 s de toute façon — ceci ne sert qu'au 1er.
                        val budget = if (p.name in SLOW_CF_SOURCES) 50_000L else 30_000L
                        val servers = CrossProviderResolver.resolveAndFetchServers(p, match, videoType, timeoutMs = budget)
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
            // 2026-08-02 : 8 s → 1,5 s (même raison : c'est le tri qui ordonne, pas l'horloge).
            delay(1500)
            WebflixProvider.fetchWebflixBackupServers(resolvedTmdbId, videoType, key.season, key.episode)
        } }
        // ── JetAnime : RETIRÉ le 07/08, à la demande du user, le jour même de son ajout.
        //   La mécanique fonctionnait (recherche par slug, page d'épisode, résolution AJAX
        //   WordPress `doo_player_ajax`), mais le seul lecteur du site renvoyait un lien MORT :
        //   `down-paradise.com/v/pym-jtme63wly35` redirige vers l'accueil de l'hébergeur, et
        //   l'API JetAnime resservait exactement la même URL — donc pas une expiration, c'est
        //   ce qu'ils stockent. Un seul lecteur par épisode, mort : aucun intérêt.
        //   Le provider reste dans `Desktop\streamflix_backups\` si on veut le reprendre.
        // ── Adkami (NATIF, par TITRE STRICT) — animes VOSTFR, catalogue adulte (demande du user).
        // 2026-08-07 : ⚠ leur extension lit `div[data-url]`, périmé — l'URL chiffrée est sur le
        //   `data-src` de l'iframe. Chiffrement maison (base64 + XOR 175 + soustraction de clé).
        // 2026-08-08 (user : « je vois pas pourquoi eux sont appelés pour des séries et films,
        //   ils devraient rester du côté des animes ») — GARDE-FOU INVERSÉ.
        //
        //   Adkami, iAnime et Vostfree sont des catalogues EXCLUSIVEMENT anime. Ils étaient
        //   lancés sur tout, y compris Star Trek (1966), où ils ont tourné pour rien en
        //   monopolisant réseau et threads pendant que NetMirror attendait son tour.
        //
        //   J'avais d'abord posé le garde-fou habituel (« sauter si TMDB CONFIRME que ce n'est
        //   pas de l'animation ») : trop faible, il laissait passer tous les cas où TMDB ne
        //   renvoie pas de genres. Ces trois-là ne sont pas des sources généralistes, la règle
        //   doit donc être l'inverse — ils ne partent QUE si le contenu est reconnu anime :
        //     • provider natif anime (`isAnimeProvider`), ou
        //     • langue d'origine japonaise chez TMDB, ou
        //     • œuvre reconnue par AniList
        //   = exactement `effectiveAnime`, déjà calculé plus haut.
        //   Les providers de dessins animés (DessinAnime, DessinAnimeNet), eux, ne bougent pas :
        //   leur catalogue est mixte (décision user du 09/07).
        if (key.title.length >= 2 && effectiveAnime) launch { emit("Adkami") {
            var result = emptyList<Video.Server>()
            for (titleTry in knownTitles) {
                if (titleTry.isBlank()) continue
                result = AdkamiProvider.fetchAdkamiBackup(titleTry, key.episode, videoType)
                if (result.isNotEmpty()) break
            }
            result
        } }
        // ── iAnime (NATIF, par TITRE STRICT) — animes VF/VOSTFR, fiches séparées par langue.
        // 2026-08-07 : issu d'Aniyomi. ⚠ La correspondance DOIT rester stricte : une recherche
        //   « naruto » sur ce site ne remonte que des « Boruto: Naruto Next Generations ».
        if (key.title.length >= 2 && effectiveAnime) launch { emit("iAnime") {
            var result = emptyList<Video.Server>()
            for (titleTry in knownTitles) {
                if (titleTry.isBlank()) continue
                result = IAnimeProvider.fetchIAnimeBackup(titleTry, key.episode, videoType)
                if (result.isNotEmpty()) break
            }
            result
        } }
        // ── Vostfree (NATIF, par TITRE) — animes VF/VOSTFR, films ET séries.
        // 2026-08-07 : venu de l'écosystème Aniyomi, pas de Cloudstream. Moteur DLE, donc même
        //   mécanique que Wiflix/FrenchStream. ⚠ Numérotation ABSOLUE des épisodes (One Piece
        //   747, pas S15E12) — le provider compare au numéro brut.
        if (key.title.length >= 2 && effectiveAnime) launch { emit("Vostfree") {
            var result = emptyList<Video.Server>()
            for (titleTry in knownTitles) {
                if (titleTry.isBlank()) continue
                result = VostfreeProvider.fetchVostfreeBackup(titleTry, key.season, key.episode, videoType)
                if (result.isNotEmpty()) break
            }
            result
        } }
        // ── Yablom (NATIF, par TITRE) — petit catalogue FR, FILMS UNIQUEMENT, lecteur ShareCloudy.
        // 2026-08-06 : seul provider français absent de chez nous après le balayage des 26 dépôts
        //   Cloudstream officiels. API JSON, pas de Cloudflare, une seule requête de recherche
        //   puis une page → très peu coûteux. Essaie les titres alternatifs comme Papadustream.
        if (key.title.length >= 2 && key.isMovie) launch { emit("Yablom") {
            var result = emptyList<Video.Server>()
            for (titleTry in knownTitles) {
                if (titleTry.isBlank()) continue
                result = YablomProvider.fetchYablomBackup(titleTry, key.year, videoType)
                if (result.isNotEmpty()) break
            }
            result
        } }
        // ── PapadustreamV2 (NATIF, par TITRE strict) — nouvelle version du site (films+séries).
        // 2026-07-08 : essaie tous les titres connus (alt TMDB inclus).
        // 2026-08-08 (user : « regarde … papadustream ») — BRIDE FILMS LEVÉE.
        //   Le commentaire d'en-tête annonçait « films+séries » alors que la condition portait
        //   `key.isMovie` : sur une série, Papadustream V2 n'était jamais lancé (constaté sur
        //   Star Trek — aucune ligne dans le log). Or `fetchPapadustreamV2Backup` reçoit déjà
        //   `key.season` / `key.episode` et sait donc traiter un épisode : c'est la condition
        //   qui était en retard sur le provider, pas l'inverse. Il reste en 2ᵉ vague (CF).
        //   Si les séries se révèlent bruyantes chez lui, remettre `&& key.isMovie`.
        if (key.title.length >= 2) launch { kotlinx.coroutines.delay(CF_SECOND_WAVE_MS); emit("Papadustream V2") {  // CF → 2ᵉ vague
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
            "Nabistream" -> com.streamflixreborn.streamflix.providers.NabistreamProvider.getVideo(orig)
            // 2026-08-16 : l'URL Purstream est deja le master HLS final (aucun jeton) → route directe.
            "Purstream" -> com.streamflixreborn.streamflix.providers.PurstreamProvider.getVideo(orig)
            "FileSearch" -> com.streamflixreborn.streamflix.providers.FileSearchProvider.getVideo(orig)
            "Papadustream V2" -> PapadustreamV2Provider.getVideo(orig)
            // 2026-08-08 : ok.ru — flux résolu À LA LECTURE (URLs liées à l'IP + `expires`).
            "ok.ru" -> com.streamflixreborn.streamflix.providers.OkRuProvider.getVideo(server)
            // 2026-08-13 : Rutube — flux résolu À LA LECTURE (m3u8 à token éphémère via
            //   /api/play/options). server.id = "rutube::<32hex>".
            "Rutube" -> com.streamflixreborn.streamflix.providers.RutubeProvider.getVideo(server)
            // 2026-08-11 : archive.org — `src` EST déjà l'URL du fichier (stable, pas de
            //   signature ni d'expiration). Le getVideo ne fait qu'y poser le bon type MIME.
            "archive.org" -> com.streamflixreborn.streamflix.providers.ArchiveOrgProvider.getVideo(server)
            // 2026-09-06 : Vegeta VOD — `src` est l'URL Xtream directe (mkv/mp4), l'extension
            //   est le dernier segment de l'id d'origine (cf. VegetaVod.video).
            "Vegeta VOD" -> VegetaVod.video(orig)
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
