package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
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
                channelRegistry.clear()
                channelRegistry.addAll(allChannels)
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

    /** Décode une URL rebrand.ly de type `https://rebrand.ly/u3?q=<base64>` en
     *  son URL cible.
     *
     *  Format particulier des q values 3BoxTV : ce N'EST PAS un simple
     *  base64-encoded-URL. C'est un base64 split par un `?` littéral :
     *    <base64 du path>?<base64 du query string>
     *  Donc pour décoder on doit splitter sur `?`, décoder chaque partie en
     *  base64 standard, puis rejoindre avec un `?`. */
    private fun decodeRebrandlyUrl(url: String): String? {
        val qParam = Regex("[?&]q=([^&]+)").find(url)?.groupValues?.get(1) ?: return null
        return try {
            val parts = qParam.split("?")
            val decoded = parts.map { part ->
                // Pad to multiple of 4 si nécessaire (defensive — normalement les
                // q values 3BoxTV sont déjà alignés mais on protège quand même).
                val padded = part + "=".repeat((4 - part.length % 4) % 4)
                String(android.util.Base64.decode(padded, android.util.Base64.DEFAULT), Charsets.UTF_8)
            }
            val result = decoded.joinToString("?")
            if (result.startsWith("http")) result else {
                Log.w(TAG, "Decoded result doesn't start with http: '$result'")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to base64-decode rebrand.ly q-param: ${e.message}")
            null
        }
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
    private fun extractFirstJsonObject(s: String): String? {
        if (!s.startsWith("{")) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in s.indices) {
            val c = s[i]
            if (escape) { escape = false; continue }
            if (c == '\\') { escape = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            if (c == '{') depth++
            else if (c == '}') {
                depth--
                if (depth == 0) return s.substring(0, i + 1)
            }
        }
        return null
    }

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
                name = "${ch.channelName} [3BoxTV]",
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
        val userinfoBypassUrl = if (customUa.contains('@') && src.contains("c3v9.short.gy/me/", ignoreCase = true)) {
            "https://$customUa"
        } else null
        Log.d(TAG, "BXT userinfo bypass URL: ${userinfoBypassUrl?.take(200)}")
        if (userinfoBypassUrl != null) {
            // Charge directement l'URL "humaine" (userinfo-based) dans la WebView.
            // Skip TOUT le check date/referrer de x0k7rxds.html.bet.
            Log.d(TAG, "Bypass html.bet via userinfo URL, WebView extraction starting")
            val webResolved = extractViaWebView(userinfoBypassUrl, customUa, customReferer)
            if (webResolved != null) {
                Log.d(TAG, "WebView extracted via userinfo: $webResolved")
                src = webResolved
                customUa = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                customReferer = "https://box.xtemus.com/"
            } else {
                Log.w(TAG, "userinfo bypass extraction failed, falling back to standard WebView")
                val webResolved2 = extractViaWebView(src, customUa, customReferer)
                if (webResolved2 != null) {
                    src = webResolved2
                    customUa = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                    customReferer = "https://box.xtemus.com/"
                }
            }
        } else if (src.contains("c3v9.short.gy/me/", ignoreCase = true)) {
            // 2026-05-11 : ANTI-BOT BYPASS via WebView pour les URLs `c3v9.short.gy/me/...`.
            // Le système 3BoxTV chaîne 2-3 pages html.bet qui font JS + geo IP check
            // + proxy URL expansion. On laisse un WebView exécuter ce JS avec le UA
            // et Referer fournis par le JSON source, et on intercepte le stream
            // final (m3u8/mpd/ts) quand le JS le déclenche.
            Log.d(TAG, "No @URL in userAgent, resolving via WebView: $src")
            val webResolved = extractViaWebView(src, customUa, customReferer)
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
        val ua = customUa

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
                    src = finalUrl
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
        // Regex pour capturer l'URL entre quotes simples dans la première CDATA description
        val cdataPattern = Regex(
            """<description>\s*<!\[CDATA\[\s*['"]?(https?://[^\s'"]+)""",
            RegexOption.DOT_MATCHES_ALL,
        )
        cdataPattern.find(rssBody)?.let { match ->
            return match.groupValues[1]
        }
        // Fallback : cherche n'importe quelle URL http(s) qui ressemble à un stream
        val streamPattern = Regex("""https?://[^\s'"<>]+\.(m3u8|mpd|ts|mp4)[^\s'"<>]*""")
        return streamPattern.find(rssBody)?.value
    }

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
    private suspend fun extractViaWebView(
        url: String,
        userAgent: String,
        referer: String,
    ): String? = withContext(Dispatchers.Main) {
        withTimeoutOrNull(20_000L) {
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
                    override fun shouldInterceptRequest(
                        view: android.webkit.WebView?,
                        request: android.webkit.WebResourceRequest?,
                    ): android.webkit.WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: return null
                        val host = request.url?.host ?: ""

                        // Block trackers/decoy hosts (pings, not streams).
                        // pecon.us = leopard.hosting placeholder "PLEASE CLEAR THE CACHE"
                        // que c3v9 sert quand fingerprint match bot/Cast.
                        if (host.contains("supportduweb") || host.contains("smotret.tv") ||
                            host.contains("pecon.us")) {
                            Log.d(TAG, "WV BLOCKED decoy: $host")
                            return android.webkit.WebResourceResponse("text/plain", "utf-8", null)
                        }

                        // Capture stream m3u8/mpd/ts depuis n'importe quel host.
                        if (reqUrl.contains(".m3u8") || reqUrl.contains(".mpd") ||
                            (reqUrl.contains(".ts") && !reqUrl.contains(".html"))) {
                            Log.d(TAG, "WebView intercepted stream: $reqUrl")
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
                        if (host.contains("html.bet") &&
                            !reqUrl.contains("favicon") && !reqUrl.contains("cdn-cgi")) {
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
                                // Override Date methods AVANT le inline script.
                                // Force navigator.userAgent au cas où.
                                val overrideScript = """<script>
                                    (function(){
                                      try {
                                        Object.defineProperty(navigator, 'userAgent', { get: function(){ return '$escapedUaJs'; }, configurable: true });
                                        Date.prototype.getDate = Date.prototype.getUTCDate;
                                        Date.prototype.getMonth = Date.prototype.getUTCMonth;
                                        Date.prototype.getFullYear = Date.prototype.getUTCFullYear;
                                        Date.prototype.getDay = Date.prototype.getUTCDay;
                                        Date.prototype.getHours = Date.prototype.getUTCHours;
                                        Date.prototype.getMinutes = Date.prototype.getUTCMinutes;
                                        Date.prototype.getSeconds = Date.prototype.getUTCSeconds;
                                        Date.prototype.getTimezoneOffset = function(){ return 0; };
                                      } catch(e){}
                                    })();
                                </script>"""
                                val rewritten = if (origHtml.contains("<head>", ignoreCase = true)) {
                                    Regex("(?i)<head>").replaceFirst(origHtml, "<head>$overrideScript")
                                } else if (origHtml.contains("<html", ignoreCase = true)) {
                                    Regex("(?i)(<html[^>]*>)").replaceFirst(origHtml, "$1<head>$overrideScript</head>")
                                } else {
                                    "<html><head>$overrideScript</head>$origHtml</html>"
                                }
                                Log.d(TAG, "WV REWROTE $host (orig=${origHtml.length}, new=${rewritten.length})")
                                val bytes = rewritten.toByteArray(Charsets.UTF_8)
                                return android.webkit.WebResourceResponse(
                                    "text/html", "UTF-8",
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
                        val shim = """
                            (function(){
                              try {
                                Object.defineProperty(navigator, 'userAgent', { get: () => '$escapedUa', configurable: true });
                                Object.defineProperty(navigator, 'webdriver', { get: () => undefined, configurable: true });
                                Object.defineProperty(navigator, 'maxTouchPoints', { get: () => 5, configurable: true });
                                Object.defineProperty(navigator, 'platform', { get: () => 'Linux armv81', configurable: true });
                                Object.defineProperty(navigator, 'vendor', { get: () => 'Google Inc.', configurable: true });
                                if (!window.chrome) window.chrome = { runtime: {} };
                                Date.prototype.getDate = Date.prototype.getUTCDate;
                                Date.prototype.getMonth = Date.prototype.getUTCMonth;
                                Date.prototype.getFullYear = Date.prototype.getUTCFullYear;
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
