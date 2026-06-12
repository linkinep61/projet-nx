package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * BoxXtemusProvider — 3BoxTV (backup IPTV agrégateur).
 *
 * 2026-05-11 : SCAFFOLD non enregistré dans Provider.providers — disponible
 * uniquement comme code de référence/préparation. À activer si Vavoo / Vegeta
 * / Ola / WiTV deviennent saturés ou tombent.
 *
 * ─── Source ───
 * Playlist : https://box.xtemus.com/?playlist=u256y494u21596x2
 * Team / chat support : https://3box-tv.tumblr.com/
 * Compatible WisePlay (Google Play). Agrège plusieurs upstreams pour résilience.
 *
 * ─── Architecture ───
 * Le JSON principal renvoie 12 catégories. Chaque catégorie possède une URL
 * `rebrand.ly` (paramètre `q=` = URL base64) qui redirige vers un export TSV
 * Google Sheets. Le TSV contient les chaînes de la catégorie.
 *
 *   PLAYLIST_URL → JSON top-level
 *   {
 *     "name": "ᶾᵇᵒᵡᵀᵛ ᵛ²",
 *     "groups": [
 *       { "name": "TF1+", "image": "...", "url": "https://rebrand.ly/u3?q=<base64-Sheets-URL>" },
 *       { "name": "France Télévisions", ... },
 *       { "name": "Canal+", ... },
 *       { "name": "M6+", ... },
 *       { "name": "RMC BFM Play", ... },
 *       { "name": "RTBF / Suisse", ... },
 *       { "name": "TNT Sat", ... },
 *       { "name": "Cinéma", ... },
 *       { "name": "Replay", ... },
 *       { "name": "Sports", ... },
 *       { "name": "Programme Soir", ... },
 *       { "name": "IPTV Daily", ... }
 *     ]
 *   }
 *
 * Pour chaque rebrand.ly, OkHttp suit la redirection (followRedirects=true)
 * et on lit le TSV depuis docs.google.com/spreadsheets/.../pub?output=tsv.
 *
 * ─── TODO (blocker activation) ───
 *   1. Récupérer un échantillon TSV (une catégorie au choix) pour confirmer
 *      le schéma EXACT des colonnes. Hypothèses actuelles (à valider) :
 *
 *      Variante A (3 colonnes, simple) :
 *        Nom\tURL stream\tLogo
 *
 *      Variante B (4 colonnes, avec EPG) :
 *        Nom\tURL stream\tLogo\ttvg-id
 *
 *      Variante C (5+ colonnes WisePlay) :
 *        Catégorie\tNom\tURL\tLogo\tEPG ID\t...
 *
 *      Le parser `parseTsv()` ci-dessous est tolérant : il détecte la colonne
 *      "URL" via le pattern http(s) sur la première ligne data, et la colonne
 *      "Nom" comme la première colonne string non-URL. Mais une fois le vrai
 *      format connu, on peut figer l'index pour plus de robustesse.
 *
 *   2. Enregistrer dans Provider.providers (line ~124 de Provider.kt) :
 *      BoxXtemusProvider to ProviderSupport(movies = false, tvShows = true,
 *                          group = ProviderGroup.IPTV, enrichHome = false),
 *
 *   3. Ajouter logo_boxxtemus.png dans res/drawable/.
 *
 *   4. (Optionnel) Si une catégorie expose un XMLTV pour l'EPG, ajouter un
 *      parser séparé qui lit le `tvg-id` du TSV + le XMLTV pour afficher
 *      le programme en cours sur chaque tuile.
 *
 * ─── Avantages anticipés vs Vavoo ───
 *   - Pas d'auth/signature → pas de cassure quand l'API Vavoo change ses tokens
 *   - URLs streams directes (probablement HLS) → pas de resolve endpoint
 *   - Catégorisation native via les sheets → home déjà organisé
 *   - EPG dispo si activable
 *
 * ─── Limites anticipées ───
 *   - Dépendance Google Sheets (si Google bloque docs.google.com pour TSV
 *     pub, plus rien ne marche). Sur App Engine pas de souci, sur Tahiti
 *     parfois latence.
 *   - rebrand.ly add un hop de redirect → +200-500ms par catégorie au load
 *   - 12 fetches HTTP au boot (1 par catégorie) → load lourd. Mitigation :
 *     coroutineScope { awaitAll } parallèle + cache 30 min.
 */
object BoxXtemusProvider : Provider, IptvProvider {

    override val name = "3BoxTV"
    override val baseUrl = "https://box.xtemus.com"
    override val logo = "android.resource://${BuildConfig.APPLICATION_ID}/drawable/logo_3boxtv"
    override val language = "fr"

    private const val TAG = "BoxXtemusProvider"
    private const val PLAYLIST_URL = "https://box.xtemus.com/?playlist=u256y494u21596x2"
    private const val CACHE_DURATION = 30 * 60 * 1000L // 30 min

    // ───────── IptvProvider contract ─────────
    private val _additionalServersFlow = MutableSharedFlow<Video.Server>(replay = 0)
    override val additionalServersFlow: SharedFlow<Video.Server> = _additionalServersFlow.asSharedFlow()


    // ───────── HTTP client ─────────
    // followRedirects=true : on suit le hop rebrand.ly → docs.google.com automatiquement.
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // ───────── Data classes ─────────

    /** Une catégorie (groupe) extraite du JSON top-level. */
    data class BxtCategory(
        val name: String,        // "TF1+", "Sports", etc.
        val logo: String,        // URL d'image de la catégorie (gif/png)
        val sheetUrl: String,    // URL rebrand.ly (redirige vers TSV Google Sheets)
    )

    /** Une chaîne au sein d'une catégorie, extraite du TSV.
     *
     *  IMPORTANT : dans le JSON 3BoxTV, `name` est le PROGRAMME en cours
     *  (ex: "🌖›Face à Darius Rochebin"), pas le nom de la chaîne. Le vrai
     *  nom de la chaîne est dans `infold` (ex: "LCI", "TF1", "TMC"). On garde
     *  les deux : `channelName` (= infold ou cleaned name) en title, et
     *  `programmeName` (= cleaned name) en overview/subtitle. */
    data class BxtChannel(
        val id: String,          // unique : "bxt::${category}::${infold_or_slug}"
        val name: String,        // PROGRAMME en cours (cleaned du moon emoji prefix)
        val channelName: String, // VRAI nom de chaîne (infold) — "LCI", "TF1", "TMC"
        val logo: String,        // URL logo chaîne (peut être vide → fallback)
        val streamUrl: String,   // URL HLS/MPEG-TS directe (1ère étape)
        val userAgent: String,   // UA spécial à envoyer (anti-bot 3BoxTV)
        val referer: String,     // Referer à envoyer (anti-bot 3BoxTV)
        val category: String,    // nom de la catégorie parente
        val canonicalKey: String,// pré-calculé via normalizeKey (cross-provider favoris)
    )

    // ───────── Registry ─────────
    private val channelRegistry = mutableListOf<BxtChannel>()
    private val registryLock = Any()
    private val registryMutex = Mutex()
    @Volatile private var registryLoaded = false
    @Volatile private var lastLoadTime = 0L

    // 2026-06-10 : cache RAM des URLs c3v9 résolues par chaîne (clé = channelId,
    //   valeur = (url, expiration ms). Le JWT SFR a typiquement exp = iat + 8h
    //   mais on cache 25 min pour être large. Au 2e clic, démarrage instantané
    //   comme Wiseplay sans refaire le pipeline JS.
    private data class CachedSfrUrl(val url: String, val expiresAt: Long)
    private val sfrUrlCache = java.util.concurrent.ConcurrentHashMap<String, CachedSfrUrl>()
    private val SFR_CACHE_TTL_MS = 25 * 60 * 1000L

    /** Charge le JSON + tous les TSV en parallèle. Cache 30 min. */
    private suspend fun ensureRegistry() {
        val now = System.currentTimeMillis()
        synchronized(registryLock) {
            if (registryLoaded && (now - lastLoadTime) < CACHE_DURATION) return
        }
        registryMutex.withLock {
            // Re-check après lock
            if (registryLoaded && (System.currentTimeMillis() - lastLoadTime) < CACHE_DURATION) return
            loadAllChannels()
            synchronized(registryLock) {
                registryLoaded = true
                lastLoadTime = System.currentTimeMillis()
            }
        }
    }

    private suspend fun loadAllChannels() = withContext(Dispatchers.IO) {
        try {
            // 1) Fetch JSON top-level
            val categories = fetchPlaylistCategories()
            Log.d(TAG, "Loaded ${categories.size} categories from playlist")

            // 2) Fetch les TSV de toutes les catégories en parallèle
            val allChannels = coroutineScope {
                categories.map { cat ->
                    async {
                        try {
                            fetchCategoryChannels(cat)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to load category '${cat.name}': ${e.message}")
                            emptyList()
                        }
                    }
                }.awaitAll().flatten()
            }

            synchronized(registryLock) {
                // 2026-06-10 (user "TF1 sur Wiseplay marche pas chez nous") :
                //   préserve les BxtChannel externes (= injectés par
                //   resolveExternalChannel pour World Live) sinon ils sont
                //   effacés ici → getVideo ne trouve plus la chaîne et le
                //   pipeline TF1 DIRECT SFR ne se déclenche pas.
                val externals = channelRegistry.filter { it.id.startsWith("ext-") }
                channelRegistry.clear()
                channelRegistry.addAll(allChannels)
                channelRegistry.addAll(externals)
            }
            Log.d(TAG, "Registry loaded: ${allChannels.size} channels across ${categories.size} categories")
        } catch (e: Exception) {
            Log.e(TAG, "loadAllChannels failed: ${e.message}", e)
        }
    }

    /** GET PLAYLIST_URL → parse JSON top-level → liste de catégories. */
    private fun fetchPlaylistCategories(): List<BxtCategory> {
        val request = Request.Builder()
            .url(PLAYLIST_URL)
            .header("User-Agent", "Mozilla/5.0")
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""
        response.close()
        if (!response.isSuccessful) throw Exception("HTTP ${response.code} on PLAYLIST_URL")

        val json = JSONObject(body)
        val groups = json.optJSONArray("groups") ?: JSONArray()
        val out = mutableListOf<BxtCategory>()
        for (i in 0 until groups.length()) {
            val g = groups.optJSONObject(i) ?: continue
            val name = g.optString("name", "").trim()
            val image = g.optString("image", "").trim()
            val url = g.optString("url", "").trim()
            if (name.isNotBlank() && url.isNotBlank()) {
                out.add(BxtCategory(name = name, logo = image, sheetUrl = url))
            }
        }
        return out
    }

    /** GET category.sheetUrl → parse TSV → liste de chaînes.
     *
     *  2026-05-11 : rebrand.ly retourne HTTP 403 sur les requêtes mobiles sans UA
     *  navigateur complet. Solution : on décode nous-mêmes le param `q=` base64
     *  (qui contient l'URL Google Sheets directe) et on hit Google Sheets sans
     *  passer par rebrand.ly. Google Sheets `pub?output=tsv` est public, pas
     *  d'auth ni de check UA. */
    private fun fetchCategoryChannels(cat: BxtCategory): List<BxtChannel> {
        val effectiveUrl = decodeRebrandlyUrl(cat.sheetUrl)
        if (effectiveUrl == null) {
            // Décodage du q-param échoué → on ne tente PAS rebrand.ly direct
            // (HTTP 403 systématique + redirige vers la page Tumblr du site).
            Log.w(TAG, "Skip '${cat.name}': failed to decode rebrand.ly q-param")
            return emptyList()
        }
        Log.d(TAG, "Fetching '${cat.name}' from: $effectiveUrl")

        val request = Request.Builder()
            .url(effectiveUrl)
            .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
            .header("Accept", "text/tab-separated-values, text/plain, */*")
            .header("Accept-Language", "en-US,en;q=0.9,fr;q=0.8")
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""
        response.close()
        if (!response.isSuccessful) throw Exception("HTTP ${response.code} on ${cat.name} sheet (effectiveUrl=$effectiveUrl)")

        return parseTsv(body, cat)
    }

    /** 2026-06-10 : factorisé dans utils/PlaylistParserHelpers.kt
     *  (partagé avec WorldLiveTvProvider). Wrapper avec logs. */
    private fun decodeRebrandlyUrl(url: String): String? {
        val decoded = com.streamflixreborn.streamflix.utils
            .PlaylistParserHelpers.decodeRebrandlyUrl(url)
        if (decoded == null) Log.w(TAG, "Failed to decode rebrand.ly: ${url.take(80)}")
        return decoded
    }

    /** Parse l'export Google Sheets d'une catégorie 3BoxTV.
     *
     *  2026-05-11 : DÉCOUVERTE — ce n'est PAS du TSV à colonnes. Chaque "ligne"
     *  de la cellule A est un fragment JSON. Format réel :
     *    Row 0 : `#N/A`
     *    Row 1 : `{"name":"...","image":"...","author":"...","stations":[`  (header)
     *    Row 2+: `{"url":"https://...","name":"...","image":"..."},`        (1 chaîne par row)
     *    ...
     *    Last  : `]}` (fermeture)
     *
     *  Stratégie : pour chaque ligne, on cherche un objet JSON avec un `url`.
     *  On strip les délimiteurs orphelins (`,`, `]`, `}` orphelins) pour
     *  extraire un objet JSON parsable, puis on lit `url`, `name`, `image`. */
    private fun parseTsv(tsvText: String, cat: BxtCategory): List<BxtChannel> {
        val lines = tsvText.split("\n", "\r\n")
            .map { it.trim() }
            .filter { it.isNotBlank() && it != "#N/A" }

        val out = mutableListOf<BxtChannel>()
        var skippedHeader = false
        var seenIds = mutableSetOf<String>()
        for ((idx, rawLine) in lines.withIndex()) {
            // Nettoie les trailing commas et brackets orphelins
            val cleaned = rawLine
                .trim()
                .trimStart('[', ',')
                .trimEnd(',', ']')
                .trim()
            if (cleaned.isBlank() || !cleaned.startsWith("{")) continue

            // Cherche un JSON objet valide. Si la ligne se termine par `},]` ou `},`
            // on doit garder le `}` final mais virer la suite.
            val jsonObjStr = extractFirstJsonObject(cleaned) ?: continue
            val obj = try {
                JSONObject(jsonObjStr)
            } catch (e: Exception) {
                Log.v(TAG, "Skip non-JSON line $idx in '${cat.name}': ${cleaned.take(80)}")
                continue
            }

            val url = obj.optString("url").trim()
            val rawName = obj.optString("name").trim()
            val infold = obj.optString("infold").trim()
            // 2026-05-12 : optim home — les URLs molotov.tv des jaquettes sont en
            // 480x480 PNG (~200-400 KB chacune). Pour 60+ chaînes visibles à la fois
            // sur la home, ça représente ~20 MB de PNG à télécharger en parallèle →
            // home très lente sur Chromecast/TV. On force 120x120 dans l'URL : même
            // CDN, même endpoint, juste plus petit (~5-15 KB par image, soit 15× moins
            // de données). Pas de perte visuelle car les cards font ~150px à l'affichage.
            val image = obj.optString("image").trim()
                .replace("/i/480x480/", "/i/120x120/")
                .replace("/i/720x720/", "/i/120x120/")
                .replace("/i/1080x1080/", "/i/120x120/")

            // Header row (a 'stations' field but no 'url') → skip
            if (url.isBlank() || !url.startsWith("http", ignoreCase = true)) {
                if (!skippedHeader && obj.has("stations")) {
                    skippedHeader = true
                    Log.d(TAG, "'${cat.name}' header row: ${rawName.ifBlank { "(no name)" }}")
                }
                continue
            }

            // 2026-05-11 : les URLs `c3v9.short.gy/me/...` mènent à une chaîne de
            // pages anti-bot html.bet (JS + geo IP + proxy expansion). On les
            // résout en runtime via un WebView intégré dans getVideo. Pas de
            // filtre à l'import — toutes les chaînes sont disponibles, on
            // détecte au moment de la lecture.

            // Clean le programme : retire les emojis moon (🌑🌒🌓🌔🌕🌖🌗🌘) +
            // le marqueur "›" qui sépare l'emoji du nom. Java regex ne supporte
            // pas \u{XXXXX} pour les codepoints >U+FFFF — on liste les emojis
            // explicitement (chacun est une paire de surrogates valide en string).
            val cleanedProg = rawName
                .replace("🌑", "").replace("🌒", "").replace("🌓", "").replace("🌔", "")
                .replace("🌕", "").replace("🌖", "").replace("🌗", "").replace("🌘", "")
                .replace("🌙", "").replace("🌚", "").replace("🌛", "").replace("🌜", "")
                .replace("›", "")
                .replace("•", "")
                .trim()
                .ifBlank { "Chaîne ${idx + 1}" }

            // Channel name : preferer infold (chaîne réelle : "LCI", "TF1", etc.).
            // Fallback : nom programme nettoyé.
            val channelName = infold.ifBlank { cleanedProg }

            // ID basé sur infold (stable) si dispo, sinon programme slug.
            val slug = (infold.ifBlank { cleanedProg })
                .lowercase().replace(Regex("[^a-z0-9]"), "")
                .take(40).ifBlank { idx.toString() }
            val catSlug = cat.name.lowercase().replace(Regex("[^a-z0-9]"), "")
            var id = "bxt::$catSlug::$slug"
            if (!seenIds.add(id)) {
                id = "bxt::$catSlug::$slug-$idx"
                seenIds.add(id)
            }

            // 2026-05-11 : 3BoxTV embarque dans le `userAgent` une URL après `@` qui
            // est la VRAIE URL stream après les pages anti-bot. On extrait + on
            // garde le UA + referer pour envoyer les bons headers à ExoPlayer.
            val userAgent = obj.optString("userAgent").trim()
            val referer = obj.optString("referer").trim()

            out.add(
                BxtChannel(
                    id = id,
                    name = cleanedProg,
                    channelName = channelName,
                    logo = image,
                    streamUrl = url,
                    userAgent = userAgent,
                    referer = referer,
                    category = cat.name,
                    canonicalKey = normalizeKey(channelName),
                )
            )
        }
        Log.d(TAG, "${cat.name}: parsed ${out.size} channels from ${lines.size} JSONL lines")
        return out
    }

    /** Extrait le premier objet JSON `{...}` complet d'une string (en gérant
     *  les accolades imbriquées et les strings échappées). Retourne null si
     *  pas d'objet bien-formé. */
    /** 2026-06-10 : factorisé dans utils/PlaylistParserHelpers.kt. */
    private fun extractFirstJsonObject(s: String): String? =
        com.streamflixreborn.streamflix.utils
            .PlaylistParserHelpers.extractFirstJsonObject(s)

    /** Reprend l'algorithme normalizeKey de VavooProvider pour partager le
     *  manualLogoMap (mêmes chaînes FR : TF1, Canal+, BFM, etc.). */
    private fun normalizeKey(name: String): String {
        return name.lowercase()
            .replace(Regex("\\s*\\.[a-z]\\s*$"), "")
            .replace(Regex("\\[.*?\\]"), " ")
            .replace(Regex("\\(.*?\\)"), " ")
            .replace(Regex("[éèêë]"), "e")
            .replace(Regex("[àâä]"), "a")
            .replace(Regex("[ùûü]"), "u")
            .replace(Regex("[îï]"), "i")
            .replace(Regex("[ôö]"), "o")
            .replace("ç", "c")
            .replace(Regex("\\b(fhd|uhd|hd|sd|4k|raw|hevc|h265|ppv|backup|alt|premium|gold|fullhd|full|1080p?|720p?|480p?|test|ott|raw)\\b"), " ")
            .replace(Regex("\\+\\s?1\\b"), " ")
            .replace("+", "plus")
            .replace("&", "and")
            .replace(Regex("^(fr|france)\\s*[:|.\\-]\\s*"), "")
            .replace(Regex("\\s+(fr|french|francais|france)\\s*$"), "")
            .replace(Regex("[^a-z0-9]"), "")
    }

    // ───────── Provider contract ─────────

    override suspend fun getHome(): List<Category> {
        return try {
            ensureRegistry()
            val snapshot: List<BxtChannel> = synchronized(registryLock) { channelRegistry.toList() }
            if (snapshot.isEmpty()) return emptyList()

            val sections = mutableListOf<Category>()

            // ★ Favoris EN TÊTE (parité avec Vavoo / Vegeta / Ola / WiTV)
            try {
                val favKeys = com.streamflixreborn.streamflix.utils.IptvFavoritesStore
                    .getAllCanonicalFavorites()
                if (favKeys.isNotEmpty()) {
                    val byKey = snapshot.groupBy { it.canonicalKey }
                    val favItems = favKeys.mapNotNull { key ->
                        val matching = byKey[key]?.firstOrNull() ?: return@mapNotNull null
                        matching.toTvShow()
                    }
                    if (favItems.isNotEmpty()) {
                        sections.add(Category(name = "Favoris", list = favItems))
                    }
                }
            } catch (_: Throwable) { /* favoris store indisponible → ignore */ }

            // Group par catégorie + tri interne par channelName (LCI, TF1, TMC, TFX, ...)
            // Préserve l'ordre du JSON top-level pour les catégories. À l'intérieur
            // de chaque catégorie, on trie alphabétiquement par chaîne réelle (infold)
            // pour avoir un ordre stable et prévisible — pas le bordel programme-name.
            val byCategory = linkedMapOf<String, MutableList<BxtChannel>>()
            for (ch in snapshot) {
                byCategory.getOrPut(ch.category) { mutableListOf() }.add(ch)
            }
            for ((catName, channels) in byCategory) {
                val sorted = channels.sortedWith(
                    compareBy<BxtChannel> { channelDisplayOrder(it.channelName) }
                        .thenBy { it.channelName.lowercase() }
                )
                sections.add(
                    Category(
                        name = catName,
                        list = sorted.map { it.toTvShow() },
                    )
                )
            }
            sections
        } catch (e: Exception) {
            Log.e(TAG, "getHome failed: ${e.message}", e)
            emptyList()
        }
    }

    /** Liste ordonnée des IDs de toutes les chaînes (pour navigation prev/next
     *  dans le grand player). L'ordre suit catégorie → ordre dans la catégorie. */
    fun getOrderedChannelIds(): List<String> {
        synchronized(registryLock) {
            return channelRegistry.map { it.id }
        }
    }

    /** Renvoie l'ID de la chaîne précédente dans l'ordre du home (utilisé par
     *  les boutons prev/next du player IPTV). */
    fun getPreviousChannelId(currentId: String): String? {
        val ids = getOrderedChannelIds()
        val idx = ids.indexOf(currentId)
        if (idx <= 0) return null
        return ids[idx - 1]
    }

    /** Renvoie l'ID de la chaîne suivante dans l'ordre du home. */
    fun getNextChannelId(currentId: String): String? {
        val ids = getOrderedChannelIds()
        val idx = ids.indexOf(currentId)
        if (idx < 0 || idx >= ids.size - 1) return null
        return ids[idx + 1]
    }

    /** Construit un TvShow propre depuis une BxtChannel.
     *  Title = channelName ("TF1", "LCI") + programme en suffixe.
     *  Overview = programme en cours, fournit un sous-titre lisible. */
    private fun BxtChannel.toTvShow(): TvShow {
        // Title : "TF1 — Bref S2E5" si infold dispo, sinon juste le programme
        val title = if (channelName.isNotBlank() && channelName != name) {
            "$channelName — $name"
        } else {
            channelName.ifBlank { name }
        }
        return TvShow(
            id = id,
            title = title,
            poster = logo.ifBlank { "" },
            banner = logo.ifBlank { "" },
            overview = "En direct : $name",
            providerName = this@BoxXtemusProvider.name,
        )
    }

    /** Ordre canonique d'affichage des chaînes FR. Plus bas = affiché en premier.
     *  Map basé sur les infold connus de 3BoxTV (LCI, TF1, TMC, ...).
     *  Les chaînes non listées tombent à 9999 (fin) et sont alors triées alpha. */
    private fun channelDisplayOrder(channelName: String): Int {
        val key = channelName.lowercase().replace(Regex("[^a-z0-9]"), "")
        return CHANNEL_ORDER[key] ?: 9999
    }

    // Ordre TNT FR classique : TF1, France 2-5, M6, Arte, BFM, etc.
    // Étendu avec les sous-chaînes des groupes (LCI, TMC, TFX, etc.).
    private val CHANNEL_ORDER: Map<String, Int> = listOf(
        // TF1 group
        "tf1", "tmc", "tfx", "tf1seriesfilms", "tf1sf", "lci",
        // France TV group
        "france2", "france3", "france4", "france5", "franceinfo",
        // M6 group
        "m6", "w9", "6ter", "girondins",
        // Canal+
        "canalplus", "canalpluscinema", "canalplusseries", "canalplussport", "canalplusfoot",
        "canalplusfamily", "canalplusdocs", "canalplusboxoffice", "canalplusgrandecran",
        // Generaliste / Info
        "c8", "cnews", "cstar", "bfmtv", "bfmbusiness", "bfmparisidf", "bfmregions",
        "arte", "gulli", "nrj12", "rmcstory", "rmcdecouverte", "tf1info",
        // Sport / Cinéma / Doc
        "lequipe", "beinsports1", "beinsports2", "beinsports3",
        "ocsmax", "ocsgeants", "ocschoc", "ocscity",
        "cinepluspremier", "cineplusfrisson", "cineplusemotion", "cineplusclassic", "cineplusclub",
        "natgeo", "discoverychannel", "histoiretv", "ushuaiatv", "planeteplus",
        // Musique
        "mtv", "mcm", "m6music", "nrjhits", "traceurban", "tracetropical", "mezzo",
        // Enfants
        "disneychannel", "disneyjr", "boomerang", "cartoonnetwork", "nickelodeon", "tiji",
        // Étranger / Régional
        "tv5monde", "france24", "euronews",
    ).withIndex().associate { (idx, ch) -> ch.lowercase().replace(Regex("[^a-z0-9]"), "") to idx }

    /** Affiché à l'écran lors du zapping (overlay channel switch). */
    fun getChannelDisplayName(channelId: String): String? {
        synchronized(registryLock) {
            return channelRegistry.find { it.id == channelId }?.channelName
        }
    }

    /** Logo affiché lors du zapping. */
    fun getChannelPoster(channelId: String): String? {
        synchronized(registryLock) {
            val ch = channelRegistry.find { it.id == channelId } ?: return null
            return ch.logo.ifBlank { fallbackLogoUrlFor(ch.channelName) }
        }
    }

    /** Convertit un ID 3BoxTV en clé canonique cross-provider pour favoris. */
    fun toCanonicalKey(channelId: String): String? {
        synchronized(registryLock) {
            return channelRegistry.find { it.id == channelId }?.canonicalKey
        }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (page > 1) return emptyList()
        ensureRegistry()
        val snapshot: List<BxtChannel>
        synchronized(registryLock) { snapshot = channelRegistry.toList() }
        val q = query.lowercase().trim()
        if (q.isBlank()) return emptyList()
        return snapshot
            .filter { it.name.lowercase().contains(q) || it.canonicalKey.contains(q.replace(Regex("[^a-z0-9]"), "")) }
            .map { ch ->
                TvShow(
                    id = ch.id,
                    title = ch.name,
                    poster = ch.logo.ifBlank { "" },
                    providerName = name,
                )
            }
    }

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()

    override suspend fun getTvShows(page: Int): List<TvShow> {
        ensureRegistry()
        val snapshot: List<BxtChannel>
        synchronized(registryLock) { snapshot = channelRegistry.toList() }
        // Pagination simple : 60 par page
        val pageSize = 60
        val from = (page - 1) * pageSize
        if (from >= snapshot.size) return emptyList()
        return snapshot
            .subList(from, minOf(from + pageSize, snapshot.size))
            .map { ch ->
                TvShow(
                    id = ch.id,
                    title = ch.name,
                    poster = ch.logo.ifBlank { "" },
                    providerName = name,
                )
            }
    }

    override suspend fun getMovie(id: String): Movie =
        throw UnsupportedOperationException("BoxXtemus is IPTV-only")

    override suspend fun getTvShow(id: String): TvShow {
        ensureRegistry()
        val ch = channelById(id)
        if (ch == null) {
            return TvShow(
                id = id,
                title = id.substringAfterLast("::"),
                providerName = name,
            )
        }
        return ch.toTvShow()
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = emptyList()

    override suspend fun getGenre(id: String, page: Int): Genre =
        Genre(id = id, name = id)

    override suspend fun getPeople(id: String, page: Int): People =
        People(id = id, name = "")

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        ensureRegistry()
        val ch = channelById(id) ?: return emptyList()
        // 2026-05-11 : on encode l'ID de la chaîne dans le server.id (suffixé)
        // pour pouvoir retrouver les headers UA/referer custom dans getVideo.
        // Le src est l'URL D'ORIGINE de la chaîne (c3v9.short.gy/...) — pas
        // un placeholder. Ainsi tout code path qui lit src directement (sans
        // appeler getVideo) obtient au moins une URL HTTP valide.
        return listOf(
            Video.Server(
                id = "bxt-ch::${ch.id}",
                name = "${ch.channelName} [France TV]",
                src = ch.streamUrl,
            )
        )
    }

    override suspend fun getVideo(server: Video.Server): Video {
        // 2026-05-11 : Pipeline résolution 3BoxTV (cas multiples) :
        //   A) URL → RSS feed → CDATA → stream MPD/HLS (cas LCI live)
        //   B) URL → ftven auth JSON → stream HLS signé (cas France Info)
        //   C) URL → page anti-bot HTML qui check UA(date) → redirect via
        //      @URL embeddé dans le userAgent (cas TF1, M6, etc.)
        //
        // Pour (C), on EXTRAIT l'URL après `@` dans le userAgent custom de la
        // chaîne (stocké en BxtChannel.userAgent) — c'est là que le vrai
        // shortener final est caché. On y va directement au lieu de la page
        // anti-bot qui demande un browser pour résoudre.
        ensureRegistry()
        // 2026-06-10 : fast-path cache RAM. Si on a une URL SFR encore valide
        //   pour cette chaîne, return direct sans fetcher RSS / faire le pipeline.
        //   = démarrage instantané au 2e clic comme Wiseplay.
        val cachedFast = sfrUrlCache[server.id]
        if (cachedFast != null && cachedFast.expiresAt > System.currentTimeMillis()) {
            Log.d(TAG, "getVideo FAST cache HIT pour ${server.id}")
            return Video(
                source = cachedFast.url,
                type = "application/vnd.apple.mpegurl",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:115.0) Gecko/20100101 Firefox/115.0",
                    "Referer" to "https://u301.com/",
                ),
                subtitles = emptyList(),
            )
        }
        var src = server.src
        var customUa = "Mozilla/5.0 (Linux; Android 14) Chrome/131.0.0.0 Mobile Safari/537.36"
        var customReferer = "https://box.xtemus.com/"

        // 2026-05-11 : retrouve la chaîne via server.id (encodé "bxt-ch::ID") pour
        // récupérer les headers custom (userAgent, referer) requis par l'anti-bot.
        // Le src est déjà l'URL d'origine de la chaîne (mis là par getServers pour
        // tolérer les code paths qui shortcut getVideo).
        if (server.id.startsWith("bxt-ch::")) {
            val channelId = server.id.removePrefix("bxt-ch::")
            val ch = channelById(channelId)
            if (ch != null) {
                if (ch.userAgent.isNotBlank()) customUa = ch.userAgent
                if (ch.referer.isNotBlank()) customReferer = ch.referer
                // Aligne src avec streamUrl du registry (au cas où server.src ait
                // été altéré par downstream code).
                src = ch.streamUrl

                // 2026-06-12 (user "le chargement est trop long après le clic"):
                //   COURT-CIRCUIT pour les chaînes mappées (TF1+/F-TV/Canal+/M6+/
                //   Sports/etc.) : si latvdefranceShortcut peut résoudre
                //   directement, on SAUTE GenericResolver (qui fait 7 hops
                //   inutiles ~13s pour ces chaînes). Gain mesuré : 29s → ~10s
                //   sur TMC.
                val fastTrackLdf = try {
                    latvdefranceShortcut(
                        ch.channelName + " " + ch.name,
                        ch.userAgent.takeIf { it.isNotBlank() },
                    )
                } catch (_: Throwable) { null }
                if (fastTrackLdf != null) {
                    Log.d(TAG, "FAST-TRACK latvdefrance for ${ch.channelName}: $fastTrackLdf")
                    sfrUrlCache[server.id] = CachedSfrUrl(
                        fastTrackLdf, System.currentTimeMillis() + 30 * 60 * 1000L,
                    )
                    return Video(
                        source = fastTrackLdf,
                        type = "application/vnd.apple.mpegurl",
                        headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Linux; Android 14) " +
                                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                    "Chrome/131.0.0.0 Mobile Safari/537.36",
                            "Referer" to "https://www.latvdefrance.com/",
                        ),
                        subtitles = emptyList(),
                    )
                }

                // 2026-06-11 (user "modèle Wiseplay générique, arrête de
                //   hardcoder 60 chaînes") : AVANT les shortcuts hardcodés
                //   (ftven/tf1Mediainfo/latvdefrance/SFR), tenter une
                //   résolution GÉNÉRIQUE qui suit les redirects et décode
                //   les proxies pmpurl=base64 (= scailhol, rebrand.ly...).
                //   Si ça donne un m3u8/mpd, on prend cette URL et on saute
                //   tous les shortcuts. Sinon fallback sur asm163.
                val genericTry = try {
                    com.streamflixreborn.streamflix.utils.GenericStreamResolver
                        .resolve(
                            src,
                            buildMap {
                                if (ch.userAgent.isNotBlank()) put("User-Agent", ch.userAgent)
                                if (ch.referer.isNotBlank()) put("Referer", ch.referer)
                            },
                        )
                } catch (_: Throwable) { null }
                if (genericTry != null && (
                        genericTry.url.contains(".m3u8") ||
                        genericTry.url.contains(".mpd"))) {
                    Log.d(TAG, "GENERIC resolver SUCCESS: ${genericTry.url}")
                    val finalUa = genericTry.headers["User-Agent"] ?: customUa
                    val finalReferer = genericTry.headers["Referer"] ?: customReferer
                    sfrUrlCache[server.id] = CachedSfrUrl(
                        genericTry.url, System.currentTimeMillis() + 30 * 60 * 1000L,
                    )
                    return Video(
                        source = genericTry.url,
                        type = if (genericTry.url.contains(".m3u8"))
                            "application/vnd.apple.mpegurl"
                        else "application/dash+xml",
                        headers = mapOf(
                            "User-Agent" to finalUa,
                            "Referer" to finalReferer,
                        ),
                        subtitles = emptyList(),
                    )
                }

                // 2026-06-08 (user "France Info marche, pas les autres") :
                //   shortcut ftven pour les chaînes France Télévisions. Le
                //   pipeline html.bet du JSON 3BoxTV est cassé pour France
                //   2/3/4/5/Ô (le JS Nxt() exige un orchestre de 7 services
                //   externes), MAIS ftven.fr est l'endpoint d'auth officiel
                //   gratuit utilisé par France Info qui marche déjà. On
                //   override src direct vers l'URL ftven pour ces chaînes →
                //   pipeline ftven existant (ligne ~963) prend le relais →
                //   stream HLS signé. Testé curl 2026-06-08 sur 2/3/4/5 → 200 OK.
                val ftvenUrl = ftvenShortcutFor(ch)
                if (ftvenUrl != null) {
                    Log.d(TAG, "ftven shortcut for ${ch.channelName}: $ftvenUrl")
                    src = ftvenUrl
                    customUa = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                    customReferer = "https://www.france.tv/"
                } else {
                    // 2026-06-08 (user "fix LCI sans blacklister") : shortcut TF1
                    //   mediainfo pour LCI (la seule chaîne TF1+ sans DRM ni auth).
                    //   Les autres (TF1/TMC/TFX/TF1 Séries) exigent un compte
                    //   MyTF1 + DRM Widevine et restent cassées.
                    val tf1MediaUrl = tf1MediainfoUrlFor(ch)
                    if (tf1MediaUrl != null) {
                        Log.d(TAG, "TF1 mediainfo shortcut for ${ch.channelName}: $tf1MediaUrl")
                        src = tf1MediaUrl
                        customUa = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                        customReferer = "https://www.tf1.fr/"
                    } else if (latvdefranceShortcut(ch.channelName + " " + ch.name, ch.userAgent.takeIf { it.isNotBlank() })?.also { ldfUrl ->
                            Log.d(TAG, "TF1 LATVDEFRANCE shortcut → $ldfUrl")
                            src = ldfUrl
                            customUa = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                            customReferer = "https://www.latvdefrance.com/"
                            sfrUrlCache[server.id] = CachedSfrUrl(
                                ldfUrl, System.currentTimeMillis() + 30 * 60 * 1000L,
                            )
                        } != null) {
                        // déjà appliqué dans le also{}
                    } else {
                        // 2026-06-10 (user "TF1 fonctionne sur Wiseplay, pas
                        //   chez nous + chargement super long") : ROUTE DIRECTE
                        //   pour TF1 → skip le WebView décoy (scailhol.free.fr
                        //   qui retourne du HTML) et tente direct le pipeline
                        //   bypassFranceTvSfr (u301 → JWT SFR depuis l'IP de
                        //   l'app, comme Wiseplay). Gain ~15s + meilleur match
                        //   d'IP pour le CDN SFR.
                        val key = (ch.channelName + " " + ch.name).uppercase()
                        val isMainTf1Channel = Regex("\\bTF1\\b").containsMatchIn(key) &&
                                !Regex("\\bLCI\\b").containsMatchIn(key) &&
                                !Regex("\\bTMC\\b").containsMatchIn(key) &&
                                !Regex("\\bTFX\\b").containsMatchIn(key) &&
                                !Regex("\\bSERIES\\b").containsMatchIn(key)
                        if (isMainTf1Channel) {
                            try {
                                val directClient = okhttp3.OkHttpClient.Builder()
                                    .followRedirects(true)
                                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                                    .build()
                                val sfrDirect = bypassFranceTvSfr(customUa, directClient)
                                if (sfrDirect != null) {
                                    Log.d(TAG, "TF1 DIRECT SFR shortcut → $sfrDirect")
                                    src = sfrDirect
                                    customUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:115.0) Gecko/20100101 Firefox/115.0"
                                    customReferer = "https://u301.com/"
                                    // Cache pour 25 min — démarrage instantané au 2e clic
                                    sfrUrlCache[server.id] = CachedSfrUrl(
                                        sfrDirect, System.currentTimeMillis() + SFR_CACHE_TTL_MS,
                                    )
                                }
                            } catch (e: Throwable) {
                                Log.w(TAG, "TF1 DIRECT SFR failed: ${e.message}")
                            }
                        }
                    }
                }
            } else {
                Log.w(TAG, "Channel not found for $channelId, fallback to literal src")
            }
        }

        // 2026-05-12 : SOLUTION — bypass la page anti-bot c3v9/x0k7rxds.html.bet
        // dont le JS check date(userAgent)+referrer et FAIL sur Chromecast WebView
        // (document.referrer mal propagé). Le success path du JS fait :
        //   window.location.assign("https://" + navigator.userAgent)
        // ce qui construit une URL `https://<userinfo>@host/path` où userinfo =
        // le userAgent custom de 3BoxTV. On construit cette URL nous-mêmes et on
        // l'envoie direct à la WebView qui suit alors le pipeline normal
        // (geo check + token fetch sans fingerprint check).
        // 2026-06-08 (user "ça marche dans Wiseplay, fix ton code") : ANCIEN
        //   userinfo bypass URL `https://<UA>@p96ElA/...` était une URL INVALIDE
        //   (`p96ElA` n'est pas un host DNS). Mon code timeout 35s pour rien
        //   avant le fallback. Maintenant on saute direct au WebView sur
        //   l'URL c3v9.short.gy d'origine. Le JS de la page exécute le
        //   pipeline GetTKN() avec le UA custom dans navigator.userAgent
        //   (via shim onPageStarted), ce qui équivaut au userinfo bypass.
        if (src.contains("c3v9.short.gy/me/", ignoreCase = true)) {
            // 2026-05-11 : ANTI-BOT BYPASS via WebView pour les URLs `c3v9.short.gy/me/...`.
            // Le système 3BoxTV chaîne 2-3 pages html.bet qui font JS + geo IP check
            // + proxy URL expansion. On laisse un WebView exécuter ce JS avec le UA
            // et Referer fournis par le JSON source, et on intercepte le stream
            // final (m3u8/mpd/ts) quand le JS le déclenche.
            Log.d(TAG, "No @URL in userAgent, resolving via WebView: $src")
            // 2026-06-08 (user "tu peux pas faire un patch qui s'adapte") :
            //   retry automatique. Le pipeline JS 3BoxTV a une race condition
            //   entre mon shim Date (async) et le check JS du 1er RSS (sync).
            //   Si la race est perdue → on capture l'URL decoy pecon.us.
            //   Solution : retry. Au 2e ou 3e essai, le timing favorise notre
            //   shim. Boucle jusqu'à 3 essais ou stream valide.
            var webResolved: String? = null
            for (attempt in 1..3) {
                val result = extractViaWebView(src, customUa, customReferer)
                if (result == null) {
                    Log.w(TAG, "WebView extraction attempt $attempt returned null")
                    break  // erreur grave, pas de retry
                }
                val isDecoy = result.contains("pecon.us") ||
                    result.contains("supportduweb") ||
                    result.contains("videezy.com") ||
                    result.contains("No-Signal")
                if (!isDecoy) {
                    webResolved = result
                    if (attempt > 1) Log.d(TAG, "Pipeline JS recovered on attempt $attempt")
                    break
                }
                Log.w(TAG, "Attempt $attempt captured decoy ($result), retry...")
            }
            if (webResolved != null) {
                Log.d(TAG, "WebView extracted stream: $webResolved")
                src = webResolved
                // 2026-05-11 : après WebView extraction, on RESET les headers à
                // des valeurs standards. Le UA custom 3BoxTV (avec date + ~slXXX@)
                // est requis pour passer l'anti-bot html.bet, mais les CDN
                // downstream (netplus.ch, etc.) le rejettent. Le m3u8 extrait est
                // une URL signée (JWT) qui authentifie déjà la requête — pas
                // besoin du UA spécial.
                customUa = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                customReferer = "https://box.xtemus.com/"
            } else {
                Log.w(TAG, "WebView extraction failed for $src")
            }
        }
        var ua = customUa

        // 2026-05-12 (user "observe les logs") : CONSUME PRE-EXTRACT CACHE.
        // Si src pointe vers un host iframe connu (sharecloudy.com/iframe/...,
        // vidmoly, voe...) → Extractor.extract a déjà un cache (rempli par
        // pre-extract en background) ou peut lancer l'extracteur dédié.
        // C'est BEAUCOUP mieux que notre HTTP resolve qui suit juste les
        // redirects et chope du HTML d'embed → ExoPlayer PARSING_UNSUPPORTED.
        // Bug observé : Beast / "De si remarquables créatures" → pre-extract
        // trouvait le m3u8 en 2-4s, mais BoxXtemus.getVideo le re-résolvait en
        // HTTP et tombait sur du HTML moovbob.fr → empty src → player figé.
        val srcLowerPre = src.lowercase()
        val isIframeEmbed = srcLowerPre.contains("sharecloudy.com/iframe/") ||
            srcLowerPre.contains("/embed/") ||
            (srcLowerPre.contains("/iframe/") && !srcLowerPre.contains("c3v9.short.gy") &&
                !srcLowerPre.contains("html.bet"))
        val isAlreadyStreamFast = srcLowerPre.substringBefore('?').let {
            it.contains(".m3u8") || it.contains(".mpd") || it.endsWith(".ts") ||
                it.endsWith(".mp4") || it.endsWith(".webm")
        }
        if (isIframeEmbed && !isAlreadyStreamFast) {
            try {
                Log.d(TAG, "Trying Extractor.extract for iframe URL (cache or dedicated extractor): $src")
                val extracted = com.streamflixreborn.streamflix.extractors.Extractor.extract(src)
                if (extracted.source.isNotBlank() && extracted.source != src) {
                    Log.d(TAG, "Extractor.extract success: ${extracted.source.take(160)}")
                    // Merge with our headers (UA/Referer) to keep anti-bot compat.
                    val mergedHeaders = mutableMapOf<String, String>().apply {
                        putAll(extracted.headers ?: emptyMap())
                        // Only set our custom UA/Referer if extractor didn't already specify.
                        putIfAbsent("User-Agent", ua)
                        putIfAbsent("Referer", customReferer)
                    }
                    return Video(
                        source = extracted.source,
                        type = extracted.type,
                        subtitles = extracted.subtitles,
                        headers = mergedHeaders,
                    )
                }
                Log.d(TAG, "Extractor.extract returned empty/same source — falling back to HTTP resolve")
            } catch (e: Exception) {
                Log.w(TAG, "Extractor.extract failed for $src: ${e.message} — falling back to HTTP resolve")
            }
        }

        val resolveClient = okhttp3.OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        // 2026-05-12 : SKIP resolve step si src est déjà un stream URL identifié.
        // L'étape resolve sert à suivre les redirects c3v9 → tvradiozap → real.
        // Si WebView a déjà extrait un .m3u8/.mpd/.ts → c'est le vrai stream,
        // ExoPlayer fera le GET lui-même. Inutile de gaspiller 5-10s ici.
        // Ne s'applique pas au cas RSS feed (LCI live retourne du XML, doit être
        // resolved + parsed).
        val srcLowerForSkip = src.lowercase().substringBefore('?')
        val isAlreadyStream = srcLowerForSkip.contains(".m3u8") ||
            srcLowerForSkip.contains(".mpd") ||
            srcLowerForSkip.endsWith(".ts") ||
            srcLowerForSkip.endsWith(".mp4")
        if (isAlreadyStream) {
            Log.d(TAG, "Skip resolve step — src is already a stream URL: ${src.take(120)}")
        }

        // Étape 1+2 : Suivre les redirections HTTP (GET car certains servers
        // ne répondent pas correctement au HEAD).
        if (!isAlreadyStream) try {
            val req = okhttp3.Request.Builder()
                .url(src)
                .header("User-Agent", ua)
                .header("Accept", "*/*")
                .build()
            val resp = resolveClient.newCall(req).execute()
            val finalUrl = resp.request.url.toString()
            val contentType = resp.header("Content-Type", "").orEmpty().lowercase()
            val body = resp.body?.string() ?: ""
            resp.close()
            Log.d(TAG, "Resolved $src → $finalUrl (Content-Type=$contentType, body=${body.length} chars)")

            // 2026-05-12 : DETECTION DECOY HTML → fail fast plutôt que de donner
            // une URL HTML à ExoPlayer qui se vautrera en UnrecognizedInputFormat
            // et bouclera 48× en sticky mode pendant 30s.
            // Cas typique : WebView extraction a foiré, resolve HTTP suit
            // c3v9 → x0k7rxds.html.bet / 3j4jx9vm.html.bet (page HTML anti-bot).
            // throw → outer catch swallow le log, mais on remarque src="" après pour
            // empêcher ExoPlayer de tenter le HTML.
            val finalHost = try { java.net.URI(finalUrl).host ?: "" } catch (_: Exception) { "" }
            val isDecoyHtml = contentType.contains("text/html") &&
                (finalHost.contains("html.bet") || finalHost.contains("pecon.us") ||
                 finalHost.contains("supportduweb") || finalHost.contains("smotret.tv"))
            if (isDecoyHtml) {
                Log.w(TAG, "Resolved to decoy HTML page ($finalHost) — empty src to trigger fallback")
                throw java.io.IOException("Decoy HTML page: $finalHost")
            }

            // Étape 3 : si la réponse est un RSS feed (cas LCI live), parser pour
            // extraire le stream URL. EXCLUT explicitement DASH (application/dash+xml)
            // et HLS — ces formats sont gérés directement par ExoPlayer, pas via RSS.
            val isDashOrHls = contentType.contains("dash+xml") || contentType.contains("mpegurl") ||
                body.trimStart().startsWith("<MPD", ignoreCase = true) ||
                body.trimStart().startsWith("#EXTM3U")
            val isRss = !isDashOrHls && (contentType.contains("rss") ||
                body.trimStart().startsWith("<rss") ||
                (contentType.contains("xml") && body.contains("<channel", ignoreCase = true)))
            if (isRss && body.isNotBlank()) {
                val streamUrl = extractStreamUrlFromRss(body)
                if (streamUrl != null) {
                    Log.d(TAG, "Extracted stream from RSS: $streamUrl")
                    src = streamUrl
                } else {
                    // 2026-06-08 : rsseverything.com sert un decoy "No-Signal"
                    //   sur le 1er RSS et cache le vrai stream derrière un JS
                    //   `Nxt()` qui exige `re.test(navigator.userAgent)` avec
                    //   la date jjmmaaaa dans le UA. Le WebView a son propre
                    //   UA Chromium SANS la date → check fail → decoy. On
                    //   reproduit ici la logique JS Nxt() en Kotlin pur pour
                    //   construire l'URL du 2e RSS (qui contient le vrai
                    //   stream) directement depuis customUa.
                    val nxtRssUrl = resolveNxtRssUrl(ua)
                    if (nxtRssUrl != null) {
                        Log.d(TAG, "1er RSS = only decoys, fetch 2e RSS via Nxt(): $nxtRssUrl")
                        try {
                            val nxtReq = okhttp3.Request.Builder()
                                .url(nxtRssUrl)
                                .header("User-Agent", ua)
                                .header("Accept", "*/*")
                                .build()
                            val nxtResp = resolveClient.newCall(nxtReq).execute()
                            val nxtBody = nxtResp.body?.string() ?: ""
                            nxtResp.close()
                            val nxtStreamUrl = extractStreamUrlFromRss(nxtBody)
                            if (nxtStreamUrl != null) {
                                Log.d(TAG, "Extracted stream from 2e RSS (Nxt): $nxtStreamUrl")
                                src = nxtStreamUrl
                            } else {
                                Log.w(TAG, "2e RSS aussi only decoys — tente bypass SFR (geo-block Tahiti)")
                                // 2026-06-10 : cache RAM par chaîne (= démarrage instantané au 2e clic, comme Wiseplay)
                                val channelKey = server.id
                                val cached = sfrUrlCache[channelKey]
                                val wvUrl: String? = if (cached != null && cached.expiresAt > System.currentTimeMillis()) {
                                    Log.d(TAG, "bypassSfr cache HIT pour $channelKey")
                                    cached.url
                                } else {
                                    sfrUrlCache.remove(channelKey)
                                    val resolved = try {
                                        kotlinx.coroutines.withTimeoutOrNull(12_000L) {
                                            bypassViaSfrWebView(nxtRssUrl, ua)
                                        }
                                    } catch (_: Throwable) { null }
                                    if (resolved != null) {
                                        sfrUrlCache[channelKey] = CachedSfrUrl(
                                            resolved, System.currentTimeMillis() + SFR_CACHE_TTL_MS
                                        )
                                        Log.d(TAG, "bypassSfr cache STORE pour $channelKey")
                                    }
                                    resolved
                                }
                                if (wvUrl != null) {
                                    src = wvUrl
                                    customUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:115.0) Gecko/20100101 Firefox/115.0"
                                    customReferer = "https://u301.com/"
                                    ua = customUa
                                }
                                // 2026-06-10 : sinon tente le bypass SFR direct (reproduit GetTKN() Kotlin)
                                if (wvUrl == null) {
                                    val sfrBypassUrl = bypassFranceTvSfr(ua, resolveClient)
                                    if (sfrBypassUrl != null) {
                                        Log.d(TAG, "bypassSfr resolved: $sfrBypassUrl")
                                        src = sfrBypassUrl
                                        customUa = "Mozilla/5.0 (Windows NT 6.1; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"
                                        customReferer = "https://u301.com/"
                                        ua = customUa
                                    } else {
                                        src = finalUrl
                                    }
                                }
                                // Si wvUrl != null, src est déjà setté à wvUrl plus haut, ne pas écraser.
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Fetch 2e RSS échoué: ${e.message}")
                            src = finalUrl
                        }
                    } else {
                        src = finalUrl
                    }
                }
            } else {
                src = finalUrl
            }

            // 2026-05-12 (user "vidéo qui se lit pas") : GENERIC HTML DETECTION.
            // Si après resolve la réponse est text/html ET ne contient PAS de
            // marqueur de stream (DASH MPD, HLS playlist, RSS feed), c'est une
            // page d'embed (frembed.one/embed/...) qu'on ne sait pas extraire.
            // Mieux vaut empty src → "No source found" → fallback rapide que
            // de laisser ExoPlayer se vautrer sur le HTML pendant 30s.
            // Bug observé : Remarkably Bright Creatures (BoxXtemus) → frembed.one
            // retourne 56K de HTML, ExoPlayer crash en UnrecognizedInputFormatException
            // en boucle 33+ fois (cf hard cap PlayerTvFragment 2026-05-12).
            val srcLooksLikeStream = src.lowercase().substringBefore('?').let { s ->
                s.contains(".m3u8") || s.contains(".mpd") || s.endsWith(".ts") ||
                    s.endsWith(".mp4") || s.endsWith(".webm")
            }
            if (!srcLooksLikeStream && contentType.contains("text/html") && !isRss && !isDashOrHls) {
                Log.w(TAG, "Resolved to generic HTML page ($finalHost, ${body.length} chars) — not a stream, empty src to trigger fallback")
                src = ""
            }

            // 2026-06-08 (user "fix LCI") : TF1 mediainfo — pattern similaire
            //   à ftven mais pour le réseau TF1. JSON contient `delivery.url`
            //   avec URL DASH MPD signée. Seul LCI passe sans DRM.
            if (src.contains("mediainfo.tf1.fr")) {
                try {
                    val authReq = okhttp3.Request.Builder()
                        .url(src)
                        .header("User-Agent", ua)
                        .header("Accept", "application/json, */*")
                        .header("Referer", "https://www.tf1.fr/")
                        .build()
                    val authResp = resolveClient.newCall(authReq).execute()
                    val authBody = authResp.body?.string() ?: ""
                    authResp.close()
                    // Le champ delivery contient code et url. On ne suit que
                    //   si code=200 (sinon = PERMISSION_DENIED ou DRM requis).
                    val codeMatch = Regex(""""delivery"\s*:\s*\{[^\}]*"code"\s*:\s*(\d+)""")
                        .find(authBody)
                    val urlMatch = Regex(""""delivery"\s*:\s*\{[^\}]*"url"\s*:\s*"([^"]+)"""")
                        .find(authBody)
                    if (codeMatch?.groupValues?.get(1) == "200" && urlMatch != null) {
                        val realStream = urlMatch.groupValues[1].replace("\\/", "/")
                        Log.d(TAG, "TF1 mediainfo → real stream: $realStream")
                        src = realStream
                    } else {
                        val code = codeMatch?.groupValues?.get(1) ?: "?"
                        Log.w(TAG, "TF1 mediainfo permission denied (code=$code) — DRM/auth required")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "TF1 mediainfo resolution failed: ${e.message}")
                }
            }
            // Étape 4 : certains URLs (France TV/Info via ftven.fr) sont des endpoints
            // d'AUTH qui retournent un JSON avec l'URL HLS signée. On suit l'auth.
            if (src.contains("hdfauth.ftven.fr") || src.contains("format=json")) {
                try {
                    val authReq = okhttp3.Request.Builder()
                        .url(src)
                        .header("User-Agent", ua)
                        .header("Accept", "application/json, */*")
                        .build()
                    val authResp = resolveClient.newCall(authReq).execute()
                    val authBody = authResp.body?.string() ?: ""
                    authResp.close()
                    // Cherche un champ "url" dans le JSON
                    val urlMatch = Regex(""""url"\s*:\s*"([^"]+)"""").find(authBody)
                    if (urlMatch != null) {
                        val realStream = urlMatch.groupValues[1].replace("\\/", "/")
                        Log.d(TAG, "ftven auth JSON → real stream: $realStream")
                        src = realStream
                    } else {
                        Log.w(TAG, "ftven auth returned but no url field: ${authBody.take(200)}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "ftven auth resolution failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve stream for $src: ${e.message}")
            // 2026-05-12 : si l'erreur indique decoy HTML page, on force src empty
            // pour que PlayerViewModel.getVideo throw "No source found" → fallback
            // au prochain server au lieu de boucler ExoPlayer sur l'HTML.
            if (e.message?.contains("Decoy HTML page") == true) {
                src = ""
            }
        }

        // 2026-05-12 : final sanity check — si src est resté sur une URL anti-bot
        // (cas où WebView extraction + resolve ont échoué et qu'on n'a pas pu
        // détecter avant), on force empty pour éviter le loop ExoPlayer.
        if (src.contains("html.bet") || src.contains("c3v9.short.gy/me/") ||
            src.contains("pecon.us") || src.contains("supportduweb") ||
            src.contains("smotret.tv")) {
            Log.w(TAG, "Final src is decoy/unresolved ($src) — empty to trigger fallback")
            src = ""
        }

        val srcLower = src.lowercase().substringBefore('?')
        val mimeType = when {
            srcLower.contains(".m3u8") -> "application/x-mpegURL"
            srcLower.contains(".mpd") -> "application/dash+xml"
            srcLower.contains(".ts") -> "video/mp2t"
            srcLower.contains(".mp4") -> "video/mp4"
            else -> ""
        }
        // 2026-06-08 : les .ts alternatives ne fonctionnent pas (chaque .ts
        //   ne fait que 4s puis EOF — ExoPlayer le joue puis s'arrête).
        //   Approche abandonnée. Le vrai bug Canal 4 boucle est ailleurs
        //   (probablement DVR cache ou liveOffset trop agressif).
        return Video(
            source = src,
            type = mimeType,
            headers = mapOf(
                "User-Agent" to ua,
                "Referer" to customReferer,
            ),
        )
    }


    /** Extrait l'URL stream du premier <item> d'un feed RSS 3BoxTV.
     *  Format observé : <description><![CDATA['https://...mpd' ... ]]></description>
     *  L'URL est entre quotes simples au début de la CDATA. */
    private fun extractStreamUrlFromRss(rssBody: String): String? {
        // 2026-06-08 (user "no signal sur la TV") : rsseverything.com sert
        //   maintenant un decoy "No-Signal---Bad-TV.mp4" sur videezy.com comme
        //   PREMIÈRE URL du RSS pour piéger les scrappers. Le vrai stream est
        //   ailleurs dans le XML. On skip toutes les URLs decoy connues et on
        //   retourne la première VRAIE.
        fun isDecoy(url: String): Boolean {
            val lower = url.lowercase()
            return lower.contains("videezy.com") ||
                lower.contains("no-signal") ||
                lower.contains("no_signal") ||
                lower.contains("bad-tv") ||
                lower.contains("bad_tv") ||
                lower.contains("badtv")
        }

        // 1. Essaye CDATA d'abord (cas le plus courant — vrai stream signé)
        val cdataPattern = Regex(
            """<description>\s*<!\[CDATA\[\s*['"]?(https?://[^\s'"]+)""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val cdataUrl = cdataPattern.find(rssBody)?.groupValues?.get(1)
        if (cdataUrl != null && !isDecoy(cdataUrl)) {
            return cdataUrl
        }

        // 2. Fallback : itère TOUTES les URLs candidates, retourne la première
        //    qui n'est PAS un decoy. Si toutes sont decoy → return null (le
        //    caller marquera la chaîne cassée plutôt que de jouer le decoy).
        val streamPattern = Regex("""https?://[^\s'"<>]+\.(m3u8|mpd|ts|mp4)[^\s'"<>]*""")
        val allMatches = streamPattern.findAll(rssBody).map { it.value }.toList()
        val firstReal = allMatches.firstOrNull { !isDecoy(it) }
        if (firstReal != null) {
            Log.d(TAG, "extractStreamUrlFromRss: skipped ${allMatches.count { isDecoy(it) }} decoy(s), found real stream")
            return firstReal
        }
        // Aucune URL réelle → le RSS ne contient que des decoys (chaîne cassée
        // côté server). On ne joue PAS le decoy "No-Signal" pour éviter l'UX
        // trompeuse "no signal" alors qu'on pourrait afficher une vraie erreur.
        Log.w(TAG, "extractStreamUrlFromRss: only decoys (${allMatches.size}), no real stream")
        return null
    }

    /**
     * 2026-06-08 : reproduit la logique JS `Nxt()` du RSS de rsseverything.com
     * en Kotlin pur (sans WebView).
     *
     * Le 1er RSS contient un decoy + un JS qui ne s'exécute qu'avec un UA
     * Chrome contenant la date jjmmaaaa. Le WebView Android a son propre UA
     * sans la date → bypass impossible. Mais le JS est simple à reproduire :
     *
     *   ```js
     *   function Nxt(nxurl) {
     *     try {
     *       // Si nxurl contient "p96ElA/" :
     *       nxurl = "https://rsseverything.com/en/feed/" +
     *         nxurl.split("p96ElA/")[1].split("#")[0] + ".xml?"
     *     } catch(err) {
     *       // Sinon :
     *       nxurl = "https://" + nxurl.split("#")[0]
     *     }
     *     window.location.assign(nxurl)
     *   }
     *   Nxt(navigator.userAgent.split("@")[1])
     *   ```
     *
     * On extrait la partie après `@` du userAgent custom, on calcule l'URL
     * du 2e RSS, on la retourne. Le caller fetch ensuite ce 2e RSS et y
     * cherche le vrai stream.
     */
    /**
     * 2026-06-10 (user "Tahiti géo-bloqué") : bypass complet du pipeline JS
     * GetTKN() qui vérifie le country_code via geo.6play.fr → bloque les
     * IPs hors UE/France. On reproduit en Kotlin les étapes pertinentes du
     * pipeline JS sans les checks géoloc :
     *   1. Parse le UA custom pour extraire token SFR, level
     *   2. Construit l'URL SFR initiale : ncdn-live.pfd.sfr.net/shls/LIVE<token>/index.m3u8
     *   3. POST à u301.com/api/expand pour expand le shortlink
     *   4. Construit l'URL c3v9.short.gy finale avec le level encodé
     *   5. Fetch → récupère le m3u8 final
     */
    /**
     * 2026-06-10 (user "marche dans Wiseplay, autonome") : charge le RSS dans
     *  une WebView headless, laisse le JS GetTKN s'exécuter, et intercepte
     *  TOUTES les requêtes réseau pour capturer l'URL m3u8 SFR réelle.
     *  C'est exactement ce que Wiseplay fait en interne.
     */
    private suspend fun bypassViaSfrWebView(rssUrl: String, customUa: String): String? {
        // 1. Fetch le RSS XML via OkHttp pour extraire le HTML dans CDATA
        val htmlInCdata = try {
            val req = okhttp3.Request.Builder().url(rssUrl)
                .header("User-Agent", customUa).build()
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            resp.close()
            // Extrait le 1er CDATA du <description>
            val regex = Regex("""<description>\s*<!\[CDATA\[(.*?)]]>\s*</description>""", RegexOption.DOT_MATCHES_ALL)
            val matches = regex.findAll(body).map { it.groupValues[1].trim() }
                .filter { it.contains("<script", ignoreCase = true) }
                .toList()
            matches.firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "bypassViaSfrWebView: fetch RSS failed: ${e.message}"); null
        }
        if (htmlInCdata.isNullOrBlank()) {
            Log.w(TAG, "bypassViaSfrWebView: no HTML/JS found in RSS CDATA")
            return null
        }
        Log.d(TAG, "bypassViaSfrWebView: extracted HTML (${htmlInCdata.length} chars), loading in WebView")

        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
        kotlin.coroutines.suspendCoroutine<String?> { cont ->
            try {
                val activity = com.streamflixreborn.streamflix.StreamFlixApp.currentActivity ?: run {
                    Log.w(TAG, "bypassViaSfrWebView: no current activity"); cont.resumeWith(Result.success(null)); return@suspendCoroutine
                }
                val resumedFlag = java.util.concurrent.atomic.AtomicBoolean(false)
                fun resumeOnce(v: String?) {
                    if (resumedFlag.compareAndSet(false, true)) {
                        try { cont.resumeWith(Result.success(v)) } catch (_: Throwable) {}
                    }
                }

                val webView = android.webkit.WebView(activity).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = customUa
                    settings.loadsImagesAutomatically = false
                    settings.blockNetworkImage = true

                    fun fakeJson(json: String): android.webkit.WebResourceResponse {
                        val bytes = json.toByteArray(Charsets.UTF_8)
                        val resp = android.webkit.WebResourceResponse(
                            "application/json", "utf-8", java.io.ByteArrayInputStream(bytes)
                        )
                        resp.setStatusCodeAndReasonPhrase(200, "OK")
                        resp.responseHeaders = mapOf(
                            "Access-Control-Allow-Origin" to "*",
                            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
                            "Access-Control-Allow-Headers" to "*",
                            "Content-Type" to "application/json",
                        )
                        return resp
                    }
                    webViewClient = object : android.webkit.WebViewClient() {
                        override fun shouldInterceptRequest(view: android.webkit.WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                            val url = request.url.toString()
                            val host = try { request.url.host.orEmpty() } catch (_: Throwable) { "" }
                            // m3u8 SFR signé (via c3v9 ou direct) → on capture l'URL c3v9 finale.
                            if (host == "c3v9.short.gy" || ((url.contains(".m3u8") || url.contains("/shls/LIVE")) && url.contains("sfr.net"))) {
                                resumeOnce(url)
                                return android.webkit.WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(byteArrayOf()))
                            }
                            // Fake les 3 checks anti-bot du JS GetTKN (= comme Wiseplay).
                            if (host.contains("echo.hoppscotch.io"))
                                return fakeJson("""{"headers":{"x-requested-with":"com.wiseplaycom.iptv3u"}}""")
                            if (host.contains("geo.6play.fr"))
                                return fakeJson("""{"country_code":"FR","is_anonymous":false,"ip":"10.0.0.1"}""")
                            if (host.contains("ip-api.com") || host.contains("workers.dev"))
                                return fakeJson("""{"mobile":false,"proxy":false,"hosting":false}""")
                            // Bloque les ressources cosmétiques inutiles pour accélérer.
                            if (host.contains("googletagmanager") || host.contains("fonts.gstatic") ||
                                host.contains("fonts.googleapis") || host.contains("google-analytics") ||
                                url.endsWith(".css") || url.endsWith(".ico") || url.endsWith(".woff2") ||
                                url.endsWith(".woff") || url.endsWith(".png") || url.endsWith(".jpg")) {
                                return android.webkit.WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(byteArrayOf()))
                            }
                            return super.shouldInterceptRequest(view, request)
                        }
                    }
                    loadDataWithBaseURL("https://rsseverything.com/", htmlInCdata, "text/html", "utf-8", null)
                }

                // Timeout 10s (pipeline JS prend ~2-3s normalement)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try { webView.stopLoading(); webView.destroy() } catch (_: Throwable) {}
                    resumeOnce(null)
                }, 10_000)
            } catch (e: Exception) {
                Log.w(TAG, "bypassViaSfrWebView exception: ${e.message}")
                cont.resumeWith(Result.success(null))
            }
        }
        }
    }

    private suspend fun bypassFranceTvSfr(customUa: String, client: okhttp3.OkHttpClient): String? {
        return try {
            Log.d(TAG, "bypassSfr: customUa=${customUa.take(300)}")

            // 2026-06-10 (user "hier ça marchait, juste durci") : essai FALLBACK
            //   pays-non-listés en premier. Le JS GetTKN() route les pays hors
            //   FR/PT/ES/BE vers `rsseverything.com/feed/67acd004-...xml` qui
            //   contient (probablement) un stream direct, sans JWT IP-pinning.
            //   Tahiti (PF) tombe dans cette catégorie.
            try {
                val fallbackRssUrl = "https://rsseverything.com/feed/67acd004-6ce0-455e-a5b3-93143827b5b8c.xml"
                val fallbackReq = okhttp3.Request.Builder()
                    .url(fallbackRssUrl)
                    .header("User-Agent", customUa)
                    .header("Accept", "*/*")
                    .build()
                val fallbackResp = client.newCall(fallbackReq).execute()
                val fallbackBody = fallbackResp.body?.string() ?: ""
                fallbackResp.close()
                Log.d(TAG, "bypassSfr FALLBACK RSS (first 1500): ${fallbackBody.take(1500)}")
                val fallbackStream = extractStreamUrlFromRss(fallbackBody)
                if (fallbackStream != null) {
                    Log.d(TAG, "bypassSfr: SUCCESS via FALLBACK RSS → $fallbackStream")
                    return fallbackStream
                }
                Log.d(TAG, "bypassSfr: fallback RSS no stream → tente u301 pipeline")
            } catch (e: Exception) {
                Log.w(TAG, "bypassSfr: fallback RSS fetch failed: ${e.message}")
            }

            if (!customUa.contains("~sl") || !customUa.contains("~lv")) {
                Log.d(TAG, "bypassSfr: UA missing ~sl or ~lv markers, skip")
                return null
            }
            val token = customUa.substringAfter("~sl").substringBefore("@")
                .takeIf { it.isNotBlank() } ?: return null
            val level = customUa.substringAfter("~lv").substringBefore("~")
                .takeIf { it.isNotBlank() } ?: "5"
            Log.d(TAG, "bypassSfr: token=${token.take(50)}, level=$level")

            val sfrInitialUrl = "https://ncdn-live.pfd.sfr.net/shls/LIVE\$$token/index.m3u8?start=LIVE&end=END"
            val initialUrlForExpand = "https://xofix.4kb.dev/?url=$sfrInitialUrl"

            val expandBody = "{\"url\":\"$initialUrlForExpand\"}"
            val mediaJson = "application/json".toMediaTypeOrNull()
            val expandReq = okhttp3.Request.Builder()
                .url("https://u301.com/api/expand")
                .header("Referer", "https://u301.com/")
                .header("Origin", "https://u301.com")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 6.1; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0")
                .header("Content-Type", "application/json")
                .post(expandBody.toRequestBody(mediaJson))
                .build()
            val expandResp = client.newCall(expandReq).execute()
            val expandRespBody = expandResp.body?.string() ?: ""
            val expandSuccess = expandResp.isSuccessful
            expandResp.close()
            if (!expandSuccess) {
                Log.w(TAG, "bypassSfr: u301 expand status=${expandResp.code}")
                return null
            }
            Log.d(TAG, "bypassSfr: u301 response (first 500): ${expandRespBody.take(500)}")
            val expandJson = try { JSONObject(expandRespBody) } catch (_: Exception) {
                Log.w(TAG, "bypassSfr: u301 response not JSON")
                return null
            }
            val expandedUrl = expandJson.optString("expandedUrl", "")
            if (expandedUrl.isBlank()) {
                Log.w(TAG, "bypassSfr: expandedUrl missing")
                return null
            }
            Log.d(TAG, "bypassSfr: expandedUrl=$expandedUrl")

            if (expandedUrl.contains(".m3u8", ignoreCase = true)) {
                // Fallback minimal : on retourne l'expandedUrl (= probablement ne marchera
                //   pas direct à cause du check anti-bot SFR, mais c'est le dernier recours).
                val fixedUrl = expandedUrl
                    .replace("end=&", "end=END&")
                    .replace(Regex("end=$"), "end=END")
                return fixedUrl
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "bypassSfr exception: ${e.message}")
            null
        }
    }

    private fun resolveNxtRssUrl(customUa: String): String? {
        if (!customUa.contains('@')) return null
        val afterAt = customUa.substringAfter('@').takeIf { it.isNotBlank() } ?: return null
        return try {
            if (afterAt.contains("p96ElA/")) {
                val uuid = afterAt.substringAfter("p96ElA/").substringBefore('#')
                if (uuid.isBlank()) null
                else "https://rsseverything.com/en/feed/$uuid.xml?"
            } else {
                val beforeHash = afterAt.substringBefore('#')
                if (beforeHash.isBlank()) null else "https://$beforeHash"
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 2026-06-08 : map d'une chaîne France Télévisions → URL ftven directe.
     *
     * Le JSON 3BoxTV pointe ces chaînes vers le pipeline html.bet anti-bot
     * (cas C) qui est cassé en juin 2026. Mais ftven.fr (l'endpoint d'auth
     * officiel de FranceTV) est PUBLIC et gratuit, sans anti-bot.
     *
     * Pour court-circuiter html.bet, on construit nous-mêmes l'URL ftven en
     * matchant le nom de la chaîne (`channelName` ou `infold`). Le pipeline
     * ftven existant (`if (src.contains("hdfauth.ftven.fr"))` ligne ~965)
     * prend ensuite le relais : GET → JSON {"url":"<HLS signé>"} → stream.
     *
     * Retourne `null` si la chaîne n'est pas FranceTV → on laisse le pipeline
     * standard tenter sa chance (RSS direct, etc.).
     */
    private fun ftvenShortcutFor(ch: BxtChannel): String? {
        // 2026-06-08 (user "fix France TV Docs") : auto-detect d'abord — si
        //   le streamUrl contient déjà une référence à `live-thema.ftven.fr`
        //   ou `simulcast-p.ftven.fr` (cas chaînes thématiques : Docs, Slash,
        //   France 3 régions, Outre-Mer, etc.), on extrait directement
        //   le path. Couvre TOUTES les chaînes ftven sans hardcode.
        val decoded = ch.streamUrl
            .replace("%23", "#").replace("%2F", "/")
            .replace("%3A", ":")
        // 2026-06-08 (user "continue les investigations on aura toutes les
        //   chaînes") : élargi pour matcher TOUS les sous-domaines ftven —
        //   live-thema (Docs), live-series (Séries), live-music, live-info,
        //   simulcast-p (mainstream), etc. Pattern : `<sub>.ftven.fr/<path>`.
        val autoMatch = Regex(
            """(simulcast-p|live-[\w-]+)\.ftven\.fr/([\w._\-/]+?)(?=[#?\s]|$)"""
        ).find(decoded)
        if (autoMatch != null) {
            val host = autoMatch.groupValues[1]
            var path = autoMatch.groupValues[2].trimEnd('/')
            if (!path.endsWith(".m3u8")) path = "$path.m3u8"
            val finalUrl = "https://${host}.ftven.fr/${path}"
            return "https://hdfauth.ftven.fr/esi/TA?format=json&url=$finalUrl"
        }

        // Fallback : hardcode par nom de chaîne (France 2/3/4/5/Info).
        // channelName EST l'infold (LCI, TF1, FRANCE2, etc.) selon BxtChannel
        // L150. On combine aussi `name` (= programme en cours) pour booster la
        // détection si infold a un format inattendu.
        val key = (ch.channelName + " " + ch.name).uppercase()
        // 2026-06-08 (test curl + Chromecast) :
        //   - France 2/3/4/5 utilisent `hls_frN` (PAS `hls_monde_frN` qui
        //     retourne 503 — c'est l'URL "France métropole only").
        //   - France Info utilise `hls_monde_frinfo` (chaîne info mondiale).
        val ftvenChannel = when {
            // Strict : match juste la partie "FRANCE N" en évitant les confusions
            // ("France Bleu", "France 24", etc.).
            Regex("\\bFRANCE\\s*INFO\\b").containsMatchIn(key) ||
                Regex("\\bFRINFO\\b").containsMatchIn(key) -> "France_Info/hls_monde_frinfo"
            Regex("\\bFRANCE\\s*2\\b").containsMatchIn(key) ||
                Regex("\\bFR2\\b").containsMatchIn(key) -> "France_2/hls_fr2"
            Regex("\\bFRANCE\\s*3\\b").containsMatchIn(key) ||
                Regex("\\bFR3\\b").containsMatchIn(key) -> "France_3/hls_fr3"
            Regex("\\bFRANCE\\s*4\\b").containsMatchIn(key) ||
                Regex("\\bFR4\\b").containsMatchIn(key) -> "France_4/hls_fr4"
            Regex("\\bFRANCE\\s*5\\b").containsMatchIn(key) ||
                Regex("\\bFR5\\b").containsMatchIn(key) -> "France_5/hls_fr5"
            else -> null
        }
        return ftvenChannel?.let {
            "https://hdfauth.ftven.fr/esi/TA?format=json&url=https://simulcast-p.ftven.fr/simulcast/$it/index.m3u8"
        }
    }

    /**
     * 2026-06-08 (user "fix TF1+ catégorie") : shortcut TF1 mediainfo pour LCI.
     *
     *   Testé curl 2026-06-08 depuis Tahiti (PF) :
     *     - LCI : `delivery.code:200` + URL DASH MPD signée → ✅ MARCHE
     *     - TF1 : `PERMISSION_DENIED` + `drm:true` Widevine → ❌ nécessite MyTF1+DRM
     *     - TMC/TFX/TF1 Séries : `PERMISSION_DENIED` → ❌ nécessite MyTF1
     *
     *   Pour LCI on construit l'URL mediainfo qui sera fetched plus tard dans
     *   le pipeline (cf détection `mediainfo.tf1.fr` ligne ~994). Le JSON
     *   retourne `delivery.url` qu'on extrait → DASH MPD lisible direct.
     *
     *   Retourne null pour les chaînes non supportées.
     */
    private fun tf1MediainfoUrlFor(ch: BxtChannel): String? {
        val key = (ch.channelName + " " + ch.name).uppercase()
        val tf1Id = when {
            Regex("\\bLCI\\b").containsMatchIn(key) -> "L_LCI"
            // TF1/TMC/TFX/TF1 Séries demandent un compte MyTF1 + DRM → on
            // les laisse échouer dans le pipeline html.bet original plutôt
            // que de promettre quelque chose qui ne marchera pas.
            else -> null
        }
        return tf1Id?.let {
            "https://mediainfo.tf1.fr/mediainfocombo/$it?context=MYTF1&pver=5025000"
        }
    }

    /** 2026-06-11 (PCAPdroid sur Wiseplay révèle leur pipeline) : shortcut
     *  pour les chaînes FR (TF1, France 2/3/4/5, M6, etc.) via le RSS
     *  rsseverything.com/fr/feed/c779e9bb-... qui contient des `<script>`
     *  JS du style `if (UA contains "~tpolTF1~") fetch("latvdefrance.com/po/hls/.../index.m3u8?token=...")`.
     *  On parse en Kotlin et on extrait l'URL pour la chaîne demandée. */
    // 2026-06-12 — Cache du RSS rsseverything c779e9bb 5 min : le RSS coûte
    //   ~2s à fetch (avec UA signé), inutile de le retéléchargen à chaque clic.
    //   Keyed par signedUa (= différents UAs peuvent renvoyer des contenus
    //   différents — sécurité, garde un mapping ; en pratique 1 seul UA actif
    //   à la fois donc cache effectif).
    private val rssBodyCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()
    private val RSS_CACHE_TTL_MS = 5 * 60 * 1000L

    private fun latvdefranceShortcut(channelKey: String, signedUa: String? = null): String? {
        val key = channelKey.uppercase()
        // Mapping channelName uppercase → token de comparaison utilisé dans le RSS
        // 2026-06-11 (user "les chaînes du même type que TF1 sont déjà HS") :
        //   étendu massivement pour couvrir TOUTES les FR mainstream + Canal/
        //   beIN/RMC/Eurosport/etc. — le RSS rsseverything contient les tokens
        //   live pour ~50 chaînes FR, on s'y greffe.
        val rssKey = when {
            // TNT FR principale
            Regex("\\bTF1\\b").containsMatchIn(key) &&
                    !Regex("\\bLCI\\b|\\bTMC\\b|\\bTFX\\b|SERIES").containsMatchIn(key) -> "tf1"
            Regex("\\bLCI\\b").containsMatchIn(key) -> "lci"
            Regex("\\bTMC\\b").containsMatchIn(key) -> "tmc"
            Regex("\\bTFX\\b|\\bNT1\\b").containsMatchIn(key) -> "tfx"
            Regex("TF1.{0,5}SERIE|TF1.{0,5}SF\\b|\\bTFSF\\b|\\bHD1\\b").containsMatchIn(key) -> "tf1seriesfilms"
            // France TV
            Regex("\\bFRANCE\\s*2\\b|\\bFR2\\b").containsMatchIn(key) -> "france2"
            Regex("\\bFRANCE\\s*3\\b|\\bFR3\\b").containsMatchIn(key) -> "france3"
            Regex("\\bFRANCE\\s*4\\b|\\bFR4\\b").containsMatchIn(key) -> "france4"
            Regex("\\bFRANCE\\s*5\\b|\\bFR5\\b").containsMatchIn(key) -> "france5"
            Regex("FRANCE.{0,3}INFO|FRANCEINFO|\\bFR\\s*INFO\\b").containsMatchIn(key) -> "franceinfo"
            Regex("FRANCE\\s*24").containsMatchIn(key) -> "france24"
            // M6 group
            Regex("\\bM6\\b").containsMatchIn(key) -> "m6"
            Regex("\\bW9\\b").containsMatchIn(key) -> "w9"
            Regex("\\b6TER\\b").containsMatchIn(key) -> "6ter"
            Regex("\\bGULLI\\b").containsMatchIn(key) -> "gulli"
            Regex("CHERIE\\s*25|CHÉRIE\\s*25").containsMatchIn(key) -> "cherie25"
            Regex("\\bRTL\\s*9\\b").containsMatchIn(key) -> "rtl9"
            Regex("\\bTEVA\\b").containsMatchIn(key) -> "teva"
            Regex("PARIS.{0,3}PREMIERE|PARIS.{0,3}PREMIÈRE").containsMatchIn(key) -> "novo19"
            // Autres TNT
            Regex("\\bARTE\\b").containsMatchIn(key) -> "arte"
            Regex("\\bC8\\b").containsMatchIn(key) -> "c8"
            Regex("\\bCNEWS\\b").containsMatchIn(key) -> "cnews"
            Regex("\\bCSTAR\\b").containsMatchIn(key) -> "cstar"
            Regex("\\bBFM(\\s*TV)?\\b").containsMatchIn(key) &&
                    !Regex("BFM.{0,3}(SPORT|BUSINESS|REGION)").containsMatchIn(key) -> "bfmtv"
            Regex("L.?EQUIPE\\s*21|\\bEQUIPE\\s*21\\b").containsMatchIn(key) -> "lequipe21"
            Regex("CHAINE\\s*PARLEM|LACHAINEPARLEM|\\bLCP\\b").containsMatchIn(key) -> "lachaineparlementaire"
            Regex("TV5\\s*MONDE").containsMatchIn(key) -> "tv5monde"
            Regex("TV\\s*BREIZH").containsMatchIn(key) -> "tvbreizh"
            // Canal+
            Regex("CANAL\\+?\\s*CINEMA|CANAL.{0,3}CINEMA").containsMatchIn(key) -> "canalpluscinema"
            Regex("CANAL\\+?\\s*SPORT|CANAL.{0,3}SPORT").containsMatchIn(key) -> "canalplussport"
            Regex("CANAL\\+?\\s*FOOT|CANAL.{0,3}FOOT").containsMatchIn(key) -> "canalplusfoot"
            Regex("CANAL\\+?\\s*SERIE|CANAL.{0,3}SERIE").containsMatchIn(key) -> "canalplusseries"
            Regex("CANAL\\+?\\s*FAMILY|CANAL.{0,3}FAMILY").containsMatchIn(key) -> "canalplusfamily"
            Regex("CANAL\\+?\\s*DECALE|CANAL.{0,3}D[EÉ]CAL[EÉ]").containsMatchIn(key) -> "canalplusdecale"
            Regex("\\bCANAL\\+?\\b").containsMatchIn(key) -> "canalplus"
            // Sports
            Regex("BEIN.{0,3}SPORT.{0,3}1|BEINSPORTS1").containsMatchIn(key) -> "beinsports1"
            Regex("BEIN.{0,3}SPORT.{0,3}2|BEINSPORTS2").containsMatchIn(key) -> "beinsports2"
            Regex("BEIN.{0,3}SPORT.{0,3}3|BEINSPORTS3").containsMatchIn(key) -> "beinsports3"
            Regex("EUROSPORT.{0,3}1").containsMatchIn(key) -> "eurosport1"
            Regex("RMC\\s*SPORT\\s*1").containsMatchIn(key) -> "rmcsport1"
            Regex("RMC\\s*SPORT\\s*2").containsMatchIn(key) -> "rmcsport2"
            Regex("RMC\\s*D[EÉ]COUVERTE").containsMatchIn(key) -> "rmcdecouverte"
            Regex("RMC\\s*STORY").containsMatchIn(key) -> "rmcstory"
            Regex("\\bDAZN\\b").containsMatchIn(key) -> "dazn"
            Regex("AUTO.{0,3}MOTO|AUTOMOTO").containsMatchIn(key) -> "automotolachaine"
            Regex("GOLF\\s*CHANNEL").containsMatchIn(key) -> "golfchannel"
            Regex("\\bEQUIDIA\\b").containsMatchIn(key) -> "equidia"
            // Découverte / Doc
            Regex("CHASSE.{0,3}P[EÊ]CHE").containsMatchIn(key) -> "chassepeche"
            Regex("\\bANIMAUX\\b").containsMatchIn(key) -> "animaux"
            Regex("USHUAIA").containsMatchIn(key) -> "ushuaiatv"
            Regex("TOUTE.{0,3}HISTOIRE").containsMatchIn(key) -> "toutehistoire"
            Regex("SCIENCE.{0,3}VIE").containsMatchIn(key) -> "scienceandvie"
            Regex("\\bTREK\\b").containsMatchIn(key) -> "trek"
            Regex("CRIME.{0,3}DISTRICT|CRIMEDISTRICT").containsMatchIn(key) -> "crimedistrict"
            // Jeunesse / divers
            Regex("DISNEY.{0,3}CHANNEL").containsMatchIn(key) -> "disneychannel"
            Regex("\\bMANGAS?\\b").containsMatchIn(key) -> "mangas"
            Regex("\\bACTION\\b").containsMatchIn(key) -> "action"
            Regex("\\bAB1\\b").containsMatchIn(key) -> "ab1"
            Regex("\\bT18\\b").containsMatchIn(key) -> "t18"
            // Adulte (présent dans RSS)
            Regex("DORCEL").containsMatchIn(key) -> "dorceltv"
            Regex("\\bXXL\\b").containsMatchIn(key) -> "xxl"
            else -> return null
        }
        return try {
            val rssUrl = "https://rsseverything.com/fr/feed/c779e9bb-48ac-497a-aafc-f44683061c6a.xml"
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            // 2026-06-12 (logs OPPO Tahiti) : le RSS rsseverything bloque les
            // UA Chrome/standard et renvoie un décoy ("DailyArt Magazine
            // Stories") → regex ne match jamais → null → fallback bypassSfr
            // u301 → URL SFR signée avec IP CloudFlare US → 403 depuis Tahiti.
            // Le ch.userAgent contient déjà le UA signé `~tpol<token>~...` que
            // Wiseplay envoie ; quand fourni, on l'utilise pour passer
            // l'anti-bot. Fallback UA Chrome si pas de UA signé dispo.
            val effectiveUa = if (!signedUa.isNullOrBlank()) signedUa else
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
            // 2026-06-12 — Cache RSS body 5 min. Si déjà en cache et frais →
            // skip le fetch (= ~1s économisé par clic après le 1er).
            // Keyed par rssUrl (= constant `c779e9bb...xml`) — vérifié dans
            // les logs : les 3 chaînes TF1/TMC/F2 renvoient toutes 58031 chars
            // de body, c'est le MÊME RSS quel que soit le UA signé (la
            // signature ne fait que passer l'auth, pas changer le contenu).
            // Du coup le cache hit entre chaînes → 1 seul fetch RSS par 5min
            // pour TOUT World Live.
            val cacheKey = rssUrl
            val now = System.currentTimeMillis()
            val cached = rssBodyCache[cacheKey]
            val body = if (cached != null && (now - cached.first) < RSS_CACHE_TTL_MS) {
                Log.d(TAG, "latvdefranceShortcut[$rssKey]: RSS cache HIT (${cached.second.length} chars)")
                cached.second
            } else {
                val req = okhttp3.Request.Builder().url(rssUrl)
                    .header("User-Agent", effectiveUa)
                    .build()
                val fresh = client.newCall(req).execute().use {
                    if (!it.isSuccessful) return null
                    it.body?.string() ?: return null
                }
                rssBodyCache[cacheKey] = now to fresh
                Log.d(TAG, "latvdefranceShortcut[$rssKey]: RSS body ${fresh.length} chars, " +
                        "signedUa=${signedUa != null} (fetched + cached)")
                fresh
            }
            // 2026-06-12 (logs OPPO post-fix-UA) : le RSS débloqué fait 58 KB
            // mais la regex strict `==="<token>"...) {fetch((...)` ne match
            // pas car le JS du RSS est minifié sans `{` ni `((`, ex:
            //   `==="tmc"&&!/http/.test(document.referrer))fetch("...m3u8?token=...")`
            // Plus robuste : chercher la position du token literal `"<rssKey>"`
            // dans le body, puis prendre la 1ère URL m3u8 dans la fenêtre de
            // ~800 chars qui suit (= portée du bloc fetch correspondant).
            val tokenLiteral = "\"$rssKey\""
            val tokenIdx = body.indexOf(tokenLiteral, ignoreCase = true)
            var proximityUrl: String? = null
            if (tokenIdx >= 0) {
                // 2026-06-12 v3 (RSS change format : URL en clair →
                //   atob() base64 concaténés). On tente 2 stratégies :
                //   A) URL .m3u8/.mpd directe dans 5000 chars
                //   B) Déobfuscation `atob("base64")+"literal"+...`
                //   (= bypass générique de l'obfuscation)
                val windowEnd = (tokenIdx + 5000).coerceAtMost(body.length)
                val window = body.substring(tokenIdx, windowEnd)
                // Stratégie A
                val mediaInWindow = Regex(
                    """(https?:[^"'\s<>]*?\.(?:m3u8|mpd)(?:\?[^"'\s<>]*)?)""",
                    RegexOption.IGNORE_CASE,
                ).find(window)
                if (mediaInWindow != null) {
                    proximityUrl = mediaInWindow.value.replace("\\/", "/")
                    Log.d(TAG, "latvdefranceShortcut[$rssKey] PROXIMITY MATCH: $proximityUrl")
                } else {
                    // Stratégie B : tentative déobfuscation atob
                    val deobfuscated = com.streamflixreborn.streamflix.utils
                        .GenericStreamResolver.deobfuscateAtobUrlPublic(window)
                    if (deobfuscated != null) {
                        proximityUrl = deobfuscated
                        Log.d(TAG, "latvdefranceShortcut[$rssKey] DEOBFUSCATED MATCH: $proximityUrl")
                    } else {
                        Log.w(TAG, "latvdefranceShortcut[$rssKey]: token at $tokenIdx but no media. Sample (next 400): ${window.take(400)}")
                    }
                }
            } else {
                Log.w(TAG, "latvdefranceShortcut[$rssKey]: token literal not found in RSS")
            }
            if (proximityUrl != null) return proximityUrl
            // Tombe dans la regex strict (= fallback historique, souvent
            // inutile mais inoffensif).
            // Pattern : `==="<rssKey>"<JS_optionnel>) {fetch(("<url>")...`
            // 2026-06-12 — Le RSS peut contenir du JS ADDITIONNEL entre la
            // clôture du token literal et la parenthèse fermante de la
            // condition `if (...)`, ex:
            //   `==="w9"&&!/http/.test(document.referrer)) {fetch(...)`
            // Sans `[^)]*` la regex ne matchait QUE la forme stricte
            // `==="w9") {fetch(...)` → pour W9/F2/M6 etc. elle échouait → le
            // caller fallback sur TF1 → bug "toutes les chaînes jouent TF1"
            // signalé par les users. Symétrique du fix asm171 dans
            // GenericStreamResolver.kt.
            val pattern = Regex(
                """=\s*=\s*=\s*"$rssKey"\s*[^)]*\)\s*\{\s*fetch\s*\(\s*\(\s*"([^"]+)"""",
                RegexOption.IGNORE_CASE,
            )
            val match = pattern.find(body) ?: return null
            // L'URL dans le JS a des "/" échappés en "\/", on décode.
            match.groupValues[1].replace("\\/", "/")
        } catch (e: Throwable) {
            Log.w(TAG, "latvdefranceShortcut failed: ${e.message}"); null
        }
    }

    /** 2026-06-10 (user "tu compliques les choses, importe tous les réglages
     *  du TV Hub directement sur World TV") : DÉLÉGUE entièrement la
     *  résolution d'une chaîne externe au pipeline complet BoxXtemus —
     *  ftven + TF1 mediainfo + c3v9 WebView + RSS xtemus + Extractor.extract
     *  pour iframes + reset headers + cache pré-extract.
     *
     *  Injecte un BxtChannel temporaire dans le registry, appelle getVideo
     *  (= tout le pipeline existant), puis retire le channel après.
     *  Thread-safe via synchronized(registryLock). */
    suspend fun resolveExternalChannel(
        streamUrl: String,
        customUa: String,
        customReferer: String,
        channelName: String,
        channelKey: String,
    ): com.streamflixreborn.streamflix.models.Video {
        val fakeId = "ext-${channelKey.hashCode()}"
        val fakeCh = BxtChannel(
            id = fakeId,
            name = channelName,
            channelName = channelName,
            logo = "",
            streamUrl = streamUrl,
            userAgent = customUa,
            referer = customReferer,
            category = "external",
            canonicalKey = "ext$fakeId",
        )
        synchronized(registryLock) {
            channelRegistry.add(fakeCh)
        }
        try {
            val server = com.streamflixreborn.streamflix.models.Video.Server(
                id = "bxt-ch::$fakeId",
                name = channelName,
                src = streamUrl,
            )
            return getVideo(server)
        } finally {
            synchronized(registryLock) {
                channelRegistry.removeAll { it.id == fakeId }
            }
        }
    }

    // 2026-06-10 (user "code mort") : publicFtvenShortcut et
    //   publicTf1MediainfoUrl retirés — World TV délègue désormais le
    //   pipeline complet via resolveExternalChannel ci-dessus.

    private fun channelById(id: String): BxtChannel? {
        synchronized(registryLock) {
            return channelRegistry.firstOrNull { it.id == id }
        }
    }

    /** Résout les URLs anti-bot 3BoxTV (pattern c3v9.short.gy/me/...) via WebView.
     *
     *  Le WebView exécute le JS des pages html.bet (qui font UA check + geo IP
     *  detection + proxy URL expansion) avec l'User-Agent et le Referer
     *  fournis par le JSON source (champs `userAgent` et `referer` qui contiennent
     *  la date du jour + des marqueurs spécifiques que les pages anti-bot
     *  recherchent). On intercepte la requête `.m3u8` / `.mpd` / `.ts` finale
     *  qui résulte de la résolution complète.
     *
     *  Pattern identique à YflixExtractor / LpayerExtractor / VidMoLyExtractor :
     *  WebView caché, JS activé, shouldInterceptRequest pour capter le stream. */
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    /** 2026-06-10 (user "3box TV marche sur TV Hub mais pas sur World TV") :
     *  exposé en public pour que WorldLiveTvProvider puisse réutiliser tout
     *  le pipeline 3box (fakes UA-dependent + CORS proxy + retry decoy). */
    suspend fun extractViaWebView(
        url: String,
        userAgent: String,
        referer: String,
    ): String? = withContext(Dispatchers.Main) {
        // 2026-06-08 (user "les chaînes c3v9 marchaient avant") : 20s → 35s.
        //   Le pipeline JS complet (echo.hoppscotch + geo.6play + ip-api +
        //   mangaraiku + u301 + SFR) prend 15-25s sur Chromecast. À 20s on
        //   coupait juste avant que le m3u8 final soit produit.
        withTimeoutOrNull(35_000L) {
            suspendCancellableCoroutine { cont ->
                val context = com.streamflixreborn.streamflix.StreamFlixApp
                    .instance.applicationContext
                var resolved = false
                fun resolve(value: String?) {
                    if (!resolved && cont.isActive) {
                        resolved = true
                        cont.resume(value)
                    }
                }

                val webView = android.webkit.WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.userAgentString = userAgent
                    settings.mixedContentMode =
                        android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.mediaPlaybackRequiresUserGesture = false
                    // 2026-06-08 : version restaurée — pas de modif cache.
                    //   Émotion marchait sans ces modifs. Toute modif cache
                    //   cassait ce qui marchait → restore.
                }

                webView.webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onCreateWindow(
                        view: android.webkit.WebView?,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: android.os.Message?,
                    ): Boolean = false
                }

                webView.webViewClient = object : android.webkit.WebViewClient() {
                    // 2026-06-08 (user "les chaînes c3v9 marchaient avant, reproduire
                    //   le schéma de réparation") : capter aussi les NAVIGATIONS
                    //   (window.location.assign / href) vers les CDN connus,
                    //   pas seulement les requests fetch/XHR. Le pipeline JS
                    //   3BoxTV finit souvent par naviguer vers l'URL m3u8 du CDN
                    //   au lieu de la fetcher.
                    override fun shouldOverrideUrlLoading(
                        view: android.webkit.WebView?,
                        request: android.webkit.WebResourceRequest?,
                    ): Boolean {
                        val navUrl = request?.url?.toString() ?: return false
                        val navHost = request.url?.host ?: ""
                        // CDN connus = SFR, Akamai, CloudFront, Free
                        if (navHost.contains("pfd.sfr.net") ||
                            navHost.contains("akamaized.net") ||
                            navHost.contains("ncdn-live") ||
                            navHost.contains("hls-") ||
                            navUrl.contains(".m3u8") ||
                            navUrl.contains(".mpd")) {
                            Log.d(TAG, "WebView intercepted NAVIGATION to stream CDN: $navUrl")
                            resolve(navUrl)
                            return true  // bloque la navigation, on a notre URL
                        }
                        return false
                    }

                    override fun shouldInterceptRequest(
                        view: android.webkit.WebView?,
                        request: android.webkit.WebResourceRequest?,
                    ): android.webkit.WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: return null
                        val host = request.url?.host ?: ""
                        val method = request.method ?: "GET"

                        // 2026-06-08 DEBUG : log toutes les requests rsseverything
                        if (host.contains("rsseverything.com")) {
                            Log.d(TAG, "WV intercept RSS: $method $reqUrl")
                        }

                        // 2026-06-08 (user "ça fonctionne dans Wiseplay, y a un
                        //   bug quelque part") : Wiseplay native HTTP client n'a
                        //   PAS de CORS. WebView Android (Chromium) bloque les
                        //   fetches cross-origin du pipeline JS GetTKN() vers
                        //   spoo.me / supportduweb.com / geo.6play.fr / etc. →
                        //   le JS plante avec "Failed to fetch" → undefined →
                        //   "Cannot read properties of undefined (reading
                        //   'is_anonymous')". Fix : on proxifie ces requests
                        //   côté Kotlin (OkHttp, pas de CORS) et on injecte
                        //   les headers CORS dans la response → le JS peut
                        //   lire la response → pipeline complète son chemin.
                        // 2026-06-08 (user "ça marche dans Wiseplay") : FAKE
                        //   les responses geo + ip-api qui retournent que l'IP
                        //   Tahiti est "anonyme" ou "proxy". Le JS GetTKN()
                        //   vérifie is_anonymous=false ET proxy/hosting=false ;
                        //   sinon il navigue vers pecon.us placeholder (= dead
                        //   end). On simule une IP française non-proxy pour
                        //   passer ces 2 checks.
                        // 2026-06-08 : fake echo.hoppscotch.io avec lowercase
                        //   header name. Le JS lit `ply.headers["x-requested-with"]`
                        //   (lowercase). OkHttp envoie en CamelCase →
                        //   `headers["X-Requested-With"]` ≠ `["x-requested-with"]`
                        //   en JS (case-sensitive). On simule la response Wiseplay
                        //   avec lowercase pour que le pipeline passe ce check.
                        if (host.contains("echo.hoppscotch.io")) {
                            val fakeEcho = """{"headers":{"x-requested-with":"com.wiseplay","user-agent":"${userAgent.replace("\"", "\\\"")}","accept":"*/*"}}"""
                            Log.d(TAG, "WV FAKE echo.hoppscotch.io → x-requested-with=com.wiseplay")
                            return android.webkit.WebResourceResponse(
                                "application/json", "UTF-8", 200, "OK",
                                mapOf(
                                    "Access-Control-Allow-Origin" to "*",
                                    "Access-Control-Allow-Credentials" to "true",
                                ),
                                java.io.ByteArrayInputStream(fakeEcho.toByteArray(Charsets.UTF_8)),
                            )
                        }
                        if (host.contains("geo.6play.fr") && reqUrl.contains("geoInfo")) {
                            val fakeGeo = """{"is_anonymous":false,"country_code":"FR","ip":"82.66.0.1","country_name":"France"}"""
                            Log.d(TAG, "WV FAKE geo.6play.fr → is_anonymous=false, country=FR")
                            return android.webkit.WebResourceResponse(
                                "application/json", "UTF-8", 200, "OK",
                                mapOf(
                                    "Access-Control-Allow-Origin" to "*",
                                    "Access-Control-Allow-Credentials" to "true",
                                ),
                                java.io.ByteArrayInputStream(fakeGeo.toByteArray(Charsets.UTF_8)),
                            )
                        }
                        if ((host.contains("ip-api.com") || reqUrl.contains("ip-api.com")) &&
                            (reqUrl.contains("fields=mobile") || reqUrl.contains("/json/"))) {
                            val fakeIpApi = """{"proxy":false,"hosting":false,"mobile":false,"status":"success","country":"France","countryCode":"FR"}"""
                            Log.d(TAG, "WV FAKE ip-api → proxy=false, hosting=false")
                            return android.webkit.WebResourceResponse(
                                "application/json", "UTF-8", 200, "OK",
                                mapOf(
                                    "Access-Control-Allow-Origin" to "*",
                                    "Access-Control-Allow-Credentials" to "true",
                                ),
                                java.io.ByteArrayInputStream(fakeIpApi.toByteArray(Charsets.UTF_8)),
                            )
                        }

                        val needsCorsProxy = host.contains("spoo.me") ||
                            host.contains("supportduweb.com") ||
                            host.contains("smotret.tv") ||
                            host.contains("pecon.us") ||
                            host.contains("geo.6play.fr") ||
                            host.contains("ip-api.com") ||
                            host.contains("echo.hoppscotch.io") ||
                            host.contains("mangaraiku") ||
                            host.contains("u301.com") ||
                            host.contains("xbxjipeozxfosmsxlj.workers.dev") ||
                            host.contains("xofix.4kb.dev")
                        if (needsCorsProxy && method == "GET") {
                            try {
                                val builder = okhttp3.Request.Builder().url(reqUrl)
                                request.requestHeaders?.forEach { (k, v) ->
                                    if (!k.equals("Origin", ignoreCase = true) &&
                                        !k.equals("Referer", ignoreCase = true) &&
                                        !k.equals("Host", ignoreCase = true) &&
                                        !k.equals("Connection", ignoreCase = true)) {
                                        try { builder.header(k, v) } catch (_: Exception) {}
                                    }
                                }
                                builder.header("User-Agent", userAgent)
                                // 2026-06-08 (user "ça marche dans Wiseplay") :
                                //   le JS GetTKN() fait fetch echo.hoppscotch.io
                                //   et lit `headers["x-requested-with"]` pour
                                //   vérifier que ÇA vient de Wiseplay. Sans ce
                                //   header → undefined → re.test() plante L77.
                                //   On simule Wiseplay en ajoutant ce header.
                                builder.header("X-Requested-With", "com.wiseplay")
                                val proxyClient = okhttp3.OkHttpClient.Builder()
                                    .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                                    .followRedirects(true)
                                    .build()
                                val resp = proxyClient.newCall(builder.build()).execute()
                                val bodyBytes = resp.body?.bytes() ?: byteArrayOf()
                                val rawCt = resp.header("Content-Type") ?: "application/octet-stream"
                                val ct = rawCt.substringBefore(";").trim()
                                val charset = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE)
                                    .find(rawCt)?.groupValues?.get(1) ?: "UTF-8"
                                val respHeaders = mutableMapOf<String, String>(
                                    "Access-Control-Allow-Origin" to "*",
                                    "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
                                    "Access-Control-Allow-Headers" to "*",
                                    "Access-Control-Allow-Credentials" to "true",
                                )
                                resp.close()
                                Log.d(TAG, "WV CORS proxy: $host (${bodyBytes.size}b, $ct)")
                                return android.webkit.WebResourceResponse(
                                    ct, charset, 200, "OK", respHeaders,
                                    java.io.ByteArrayInputStream(bodyBytes),
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "WV CORS proxy failed for $host: ${e.message}")
                            }
                        }

                        // Capture stream m3u8/mpd/ts depuis n'importe quel host.
                        // 2026-06-08 : ajout .m3u (sans 8) — le JS GetTKN()
                        //   final fait fetch sur c3v9.short.gy/.../...#.m3u
                        //   qui est le hash résolu vers le vrai m3u8 final.
                        if (reqUrl.contains(".m3u8") || reqUrl.contains(".mpd") ||
                            reqUrl.contains(".m3u") ||
                            (reqUrl.contains(".ts") && !reqUrl.contains(".html"))) {
                            Log.d(TAG, "WebView intercepted stream: $reqUrl")
                            resolve(reqUrl)
                            return android.webkit.WebResourceResponse(
                                "text/plain", "utf-8", null,
                            )
                        }

                        // 2026-06-08 : capture aussi les requests vers CDN connus
                        //   même sans extension explicite (.m3u8 peut être absent
                        //   du path si le serveur signe l'URL différemment).
                        if (host.contains("pfd.sfr.net") || host.contains("ncdn-live") ||
                            host.contains("akamaized.net")) {
                            Log.d(TAG, "WebView intercepted CDN request: $reqUrl")
                            resolve(reqUrl)
                            return android.webkit.WebResourceResponse(
                                "text/plain", "utf-8", null,
                            )
                        }

                        // 2026-05-12 : INTERCEPT + REWRITE html.bet pages pour injecter
                        // notre override Date+navigator AVANT que le script inline de
                        // la page tourne. C'est la SEULE façon fiable d'override les
                        // primitives JS avant que le code anti-bot les lise (le shim
                        // injecté via evaluateJavascript dans onPageStarted arrive
                        // après que le inline script a déjà tourné).
                        // 2026-06-08 (user "ça marche dans Wiseplay") : élargi le
                        //   rewrite à rsseverything.com aussi. Le 2e RSS contient
                        //   un inline script qui s'exécute AVANT mon shim
                        //   onPageStarted (timing race) → check date avec
                        //   getUTCDate ORIGINAL (8) au lieu de date encodée UA (9)
                        //   → /862026/.test(UA contenant "962026") = false →
                        //   GetTKN() jamais appelé. Le rewrite injecte mon shim
                        //   DANS le HTML/CDATA avant le inline script.
                        // 2026-06-08 : rsseverything.com sert AUSSI le XSL
                        //   stylesheet (/preview.xsl) — pas un RSS feed →
                        //   skip. On rewrite seulement les .xml et /feed/.
                        val needsRewrite = host.contains("html.bet") ||
                            (host.contains("rsseverything.com") &&
                                (reqUrl.contains("/feed/") || reqUrl.endsWith(".xml") ||
                                    reqUrl.contains(".xml?")))
                        if (needsRewrite &&
                            !reqUrl.contains("favicon") && !reqUrl.contains("cdn-cgi") &&
                            !reqUrl.contains(".xsl")) {
                            try {
                                val rewriteClient = okhttp3.OkHttpClient.Builder()
                                    .followRedirects(true)
                                    .followSslRedirects(true)
                                    .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                                    .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                                    .build()
                                val origReq = okhttp3.Request.Builder()
                                    .url(reqUrl)
                                    .header("User-Agent", userAgent)
                                    .header("Referer", referer.ifBlank { "https://c3v9.short.gy/" })
                                    .build()
                                val origResp = rewriteClient.newCall(origReq).execute()
                                val origHtml = origResp.body?.string() ?: ""
                                origResp.close()
                                val escapedUaJs = userAgent.replace("\\", "\\\\").replace("'", "\\'")
                                // Extract date from UA (same as shim)
                                val dateMatchH = Regex("""MobileSafari(\d{6,7})_""").find(userAgent)
                                val (dayH, monthH, yearH) = if (dateMatchH != null) {
                                    val digits = dateMatchH.groupValues[1]
                                    val yr = digits.takeLast(4).toIntOrNull() ?: 2026
                                    val rest = digits.dropLast(4)
                                    when (rest.length) {
                                        2 -> Triple(rest.substring(0, 1).toInt(), rest.substring(1).toInt(), yr)
                                        3 -> {
                                            val d2 = rest.substring(0, 2).toIntOrNull() ?: 1
                                            val m1 = rest.substring(2).toIntOrNull() ?: 1
                                            if (d2 in 1..31 && m1 in 1..12) Triple(d2, m1, yr)
                                            else Triple(rest.substring(0, 1).toInt(), rest.substring(1).toInt(), yr)
                                        }
                                        else -> Triple(0, 0, 0)
                                    }
                                } else Triple(0, 0, 0)
                                val dateOverride = if (dayH > 0) """
                                    Date.prototype.getUTCDate = function() { return $dayH; };
                                    Date.prototype.getUTCMonth = function() { return ${monthH - 1}; };
                                    Date.prototype.getUTCFullYear = function() { return $yearH; };
                                    Date.prototype.getDate = function() { return $dayH; };
                                    Date.prototype.getMonth = function() { return ${monthH - 1}; };
                                    Date.prototype.getFullYear = function() { return $yearH; };
                                """ else """
                                    Date.prototype.getDate = Date.prototype.getUTCDate;
                                    Date.prototype.getMonth = Date.prototype.getUTCMonth;
                                    Date.prototype.getFullYear = Date.prototype.getUTCFullYear;
                                """
                                val overrideScript = """<script>
                                    (function(){
                                      try {
                                        Object.defineProperty(navigator, 'userAgent', { get: function(){ return '$escapedUaJs'; }, configurable: true });
                                        $dateOverride
                                        Date.prototype.getDay = Date.prototype.getUTCDay;
                                        Date.prototype.getHours = Date.prototype.getUTCHours;
                                        Date.prototype.getMinutes = Date.prototype.getUTCMinutes;
                                        Date.prototype.getSeconds = Date.prototype.getUTCSeconds;
                                        Date.prototype.getTimezoneOffset = function(){ return 0; };
                                      } catch(e){}
                                    })();
                                </script>"""
                                // Pour rsseverything : injecter dans le CDATA HTML
                                //   avant le <script> qui contient le check date.
                                val rewritten = if (host.contains("rsseverything.com")) {
                                    // Le HTML est dans <![CDATA[ ... ]]>. On injecte
                                    // notre script juste avant le <script> inline.
                                    if (origHtml.contains("<script>", ignoreCase = true)) {
                                        Regex("(?i)<script>").replaceFirst(origHtml, "$overrideScript<script>")
                                    } else {
                                        origHtml
                                    }
                                } else if (origHtml.contains("<head>", ignoreCase = true)) {
                                    Regex("(?i)<head>").replaceFirst(origHtml, "<head>$overrideScript")
                                } else if (origHtml.contains("<html", ignoreCase = true)) {
                                    Regex("(?i)(<html[^>]*>)").replaceFirst(origHtml, "$1<head>$overrideScript</head>")
                                } else {
                                    "<html><head>$overrideScript</head>$origHtml</html>"
                                }
                                Log.d(TAG, "WV REWROTE $reqUrl (orig=${origHtml.length}, new=${rewritten.length}, hasScript=${origHtml.contains("<script", ignoreCase = true)})")
                                val bytes = rewritten.toByteArray(Charsets.UTF_8)
                                // Content-Type auto-détecté : RSS XML si le content
                                //   commence par <?xml (cas rsseverything direct OU
                                //   redirect c3v9 → rsseverything).
                                val ct = if (origHtml.trimStart().startsWith("<?xml")) "text/xml" else "text/html"
                                return android.webkit.WebResourceResponse(
                                    ct, "UTF-8",
                                    java.io.ByteArrayInputStream(bytes),
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "Rewrite failed for $host: ${e.message}")
                            }
                        }
                        return null
                    }

                    override fun onPageStarted(
                        view: android.webkit.WebView?,
                        startedUrl: String?,
                        favicon: android.graphics.Bitmap?,
                    ) {
                        // 2026-05-12 : sur Chromecast WebView, navigator.userAgent
                        // peut ne pas être propagé correctement depuis settings.
                        // On le force via Object.defineProperty AVANT que le JS
                        // de la page lise navigator.userAgent dans son check date.
                        // Force-définit aussi navigator.userAgentData pour cohérence
                        // si la page le lit (Client Hints).
                        val escapedUa = userAgent.replace("\\", "\\\\").replace("'", "\\'")
                        // 2026-05-12 : timezone fix CRITIQUE — sur Chromecast en Tahiti
                        // (UTC-10), today.getDate() = jour local (11) alors que le marqueur
                        // date dans userAgent suit UTC (12). Le check anti-bot
                        // `re.test(navigator.userAgent)` échouait → décoy systématique.
                        // On force Date.prototype.getDate/getMonth/getFullYear à retourner
                        // les valeurs UTC pour matcher le marqueur du UA.
                        // 2026-06-08 (user "fix régression Tahiti") : EXTRACTION
                        //   de la date encodée dans le UA 3BoxTV. Le serveur
                        //   3BoxTV est en heure Paris (UTC+2/+1). Après minuit
                        //   Paris (= 22h UTC), il génère un UA avec la date du
                        //   LENDEMAIN ("962026" au lieu de "862026" si on est à
                        //   22:58 UTC le 8 juin → Paris est déjà le 9 juin).
                        //   Le check JS du RSS utilise `getUTCDate()` qui
                        //   retourne 8 → ne matche pas → écran noir.
                        //   FIX : on parse le UA pour extraire la date encodée
                        //   et on force Date.prototype.getUTCDate/Month/Year à
                        //   retourner ces valeurs. Le check JS matchera alors
                        //   forcément.
                        val dateMatch = Regex("""MobileSafari(\d{6,7})_""").find(userAgent)
                        val (uaDay, uaMonth, uaYear) = if (dateMatch != null) {
                            val digits = dateMatch.groupValues[1]
                            val year = digits.takeLast(4).toIntOrNull() ?: 2026
                            val rest = digits.dropLast(4)
                            // Heuristique : essayer d'abord 1d+1m, puis 2d+1m, puis 1d+2m
                            val parsed = when (rest.length) {
                                2 -> Triple(
                                    rest.substring(0, 1).toInt(),
                                    rest.substring(1).toInt(),
                                    year,
                                )
                                3 -> {
                                    // Format "DDM" (jour 2 chars + mois 1 char) ou "DMM"
                                    val d2 = rest.substring(0, 2).toIntOrNull() ?: 1
                                    val m1 = rest.substring(2).toIntOrNull() ?: 1
                                    val d1 = rest.substring(0, 1).toIntOrNull() ?: 1
                                    val m2 = rest.substring(1).toIntOrNull() ?: 1
                                    if (d2 in 1..31 && m1 in 1..12) Triple(d2, m1, year)
                                    else if (d1 in 1..31 && m2 in 1..12) Triple(d1, m2, year)
                                    else Triple(1, 1, year)
                                }
                                else -> Triple(1, 1, year)
                            }
                            parsed
                        } else {
                            Triple(0, 0, 0)  // pas de date dans UA → laisse Date original
                        }
                        Log.d(TAG, "UA encoded date: $uaDay/$uaMonth/$uaYear")
                        val shim = """
                            (function(){
                              try {
                                Object.defineProperty(navigator, 'userAgent', { get: () => '$escapedUa', configurable: true });
                                Object.defineProperty(navigator, 'webdriver', { get: () => undefined, configurable: true });
                                Object.defineProperty(navigator, 'maxTouchPoints', { get: () => 5, configurable: true });
                                Object.defineProperty(navigator, 'platform', { get: () => 'Linux armv81', configurable: true });
                                Object.defineProperty(navigator, 'vendor', { get: () => 'Google Inc.', configurable: true });
                                if (!window.chrome) window.chrome = { runtime: {} };
                                ${if (uaDay > 0) """
                                // Force Date methods à retourner la date encodée dans le UA
                                // (alignée sur le timezone serveur 3BoxTV = Paris).
                                Date.prototype.getUTCDate = function() { return $uaDay; };
                                Date.prototype.getUTCMonth = function() { return ${uaMonth - 1}; };
                                Date.prototype.getUTCFullYear = function() { return $uaYear; };
                                Date.prototype.getDate = function() { return $uaDay; };
                                Date.prototype.getMonth = function() { return ${uaMonth - 1}; };
                                Date.prototype.getFullYear = function() { return $uaYear; };
                                """ else """
                                Date.prototype.getDate = Date.prototype.getUTCDate;
                                Date.prototype.getMonth = Date.prototype.getUTCMonth;
                                Date.prototype.getFullYear = Date.prototype.getUTCFullYear;
                                """}
                                Date.prototype.getDay = Date.prototype.getUTCDay;
                                Date.prototype.getHours = Date.prototype.getUTCHours;
                                Date.prototype.getMinutes = Date.prototype.getUTCMinutes;
                                Date.prototype.getSeconds = Date.prototype.getUTCSeconds;
                                Date.prototype.getTimezoneOffset = function() { return 0; };
                              } catch(e) {}
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(shim, null)
                    }

                    override fun onPageFinished(
                        view: android.webkit.WebView?, finishedUrl: String?,
                    ) {
                        if (view == null || resolved) return
                        // 2026-05-12 : re-confirme navigator.userAgent + log ce que
                        // la page va lire. Permet de diagnostiquer si le check date
                        // de l'anti-bot JS passe.
                        val escapedUa = userAgent.replace("\\", "\\\\").replace("'", "\\'")
                        view.evaluateJavascript(
                            """
                            (function(){
                              try { Object.defineProperty(navigator, 'userAgent', { get: () => '$escapedUa', configurable: true }); } catch(e){}
                              var today = new Date();
                              var dateStr = today.getDate()+''+(today.getMonth()+1)+''+today.getFullYear();
                              return JSON.stringify({ua:navigator.userAgent.length, dateStr:dateStr, uaContainsDate:navigator.userAgent.indexOf(dateStr)>=0, ref:document.referrer});
                            })();
                            """.trimIndent()
                        ) { result -> Log.d(TAG, "WV JS check: $result") }
                        // Safety net : si après 15s rien capté, abandon
                        view.postDelayed({
                            if (!resolved) {
                                Log.w(TAG, "WebView timeout — no stream captured (finishedUrl=$finishedUrl)")
                                resolve(null)
                            }
                        }, 15_000L)
                    }
                }

                val headers = mutableMapOf<String, String>()
                if (referer.isNotBlank()) headers["Referer"] = referer
                webView.loadUrl(url, headers)

                cont.invokeOnCancellation {
                    resolved = true
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try { webView.stopLoading() } catch (_: Throwable) {}
                        try { webView.destroy() } catch (_: Throwable) {}
                    }
                }
            }
        }
    }

    // ───────── Fallback logo (parité avec Vavoo) ─────────
    // 2026-05-11 : ui-avatars colorisé par catégorie devinée. Utilisé par
    // TvShowViewHolder.setPoster() pour Glide error/fallback, et en interne
    // par getChannelPoster() quand la chaîne n'a pas de logo dans son TSV.
    fun fallbackLogoUrlFor(channelName: String): String = uiAvatarFallback(channelName)

    private fun uiAvatarFallback(name: String): String {
        val color = when (guessCategoryByName(name)) {
            "Sport" -> "388E3C"
            "Cinéma" -> "8E24AA"
            "Info" -> "D32F2F"
            "Musique" -> "F57C00"
            "Documentaire" -> "00897B"
            "Enfants" -> "F06292"
            else -> "1E88E5"
        }
        val initials = name
            .replace(Regex("[\\[\\(].*?[\\]\\)]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .take(3)
            .joinToString("+")
            .ifBlank { name.take(3) }
        val safeName = java.net.URLEncoder.encode(initials, "UTF-8")
        return "https://ui-avatars.com/api/?name=$safeName&background=$color&color=fff&size=512&font-size=0.5&bold=true&format=png"
    }

    private fun guessCategoryByName(name: String): String {
        val n = name.lowercase()
        return when {
            n.contains("sport") || n.contains("foot") || n.contains("rugby") ||
                n.contains("equidia") || n.contains("eurosport") -> "Sport"
            n.contains("cine") || n.contains("ocs") || n.contains("paramount") ||
                n.contains("warner") || n.contains("tcm") || n.contains("polar") -> "Cinéma"
            n.contains("news") || n.contains("info") || n.contains("bfm") ||
                n.contains("lci") || n.contains("france 24") -> "Info"
            n.contains("music") || n.contains("mtv") || n.contains("mcm") ||
                n.contains("nrj") || n.contains("trace") || n.contains("mezzo") -> "Musique"
            n.contains("nat geo") || n.contains("discovery") || n.contains("planete") ||
                n.contains("ushuaia") || n.contains("histoire") -> "Documentaire"
            n.contains("kids") || n.contains("disney") || n.contains("nickel") ||
                n.contains("gulli") || n.contains("tiji") || n.contains("boomerang") -> "Enfants"
            else -> "default"
        }
    }
}
