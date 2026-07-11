package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.streamflixreborn.streamflix.StreamFlixApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 2026-06-08 (user "à gauche du cœur tu vas mettre une petite radio. Dedans
 * dans un premier temps tu vas ramener les radios du TV hub utilisables sans
 * écran") : catalogue de chaînes radio accessibles depuis n'importe quel
 * écran de l'app via le bouton radio à côté du cœur.
 *
 * Sources :
 *  - Dric4rTV (cat R4di0) : 17 radios curated FR
 *  - RadioBrowser API : ~500 radios FR les plus populaires (par votes)
 *
 * Les IDs Dric4rTV gardent le préfixe `livehub::dric4rtv::r4di0::...` pour
 * que MiniPlayerController.isRadioChannel(id) renvoie true → pas de fullscreen.
 * Les IDs RadioBrowser ont le préfixe `radio::browser::<uuid>` (= toutes traitées
 * comme radio par isRadioChannel via le préfixe `radio::`).
 */
object RadioCatalog {

    private const val TAG = "RadioCatalog"

    @Volatile private var cache: List<RadioStation> = emptyList()
    @Volatile private var lastLoad = 0L
    private const val CACHE_TTL_MS = 30 * 60 * 1000L  // 30 min

    // 2026-07-10 : persistence disque — survit aux redémarrages app.
    // Sur Chromecast, RadioBrowser échoue souvent (DNS/timeout) →
    // sans persistence, chaque restart = 17 chaînes hardcodées.
    private const val DISK_CACHE_FILE = "radio_catalog_cache.json"
    @Volatile private var diskLoaded = false

    data class RadioStation(
        val id: String,
        val name: String,
        val poster: String?,
        val streamUrl: String? = null,  // URL directe pour les stations RadioBrowser
    )

    /** Retourne la liste complète des radios (Dric4rTV + RadioBrowser FR).
     *  Charge en parallèle au premier appel. Safe.
     *  2026-07-10 : charge depuis le disque si le cache RAM est vide (restart app). */
    suspend fun list(): List<RadioStation> = withContext(Dispatchers.IO) {
        // 2026-07-10 : au premier appel, charger le cache disque (survit aux restarts)
        if (!diskLoaded) {
            diskLoaded = true
            val fromDisk = loadFromDisk()
            if (fromDisk.isNotEmpty() && cache.isEmpty()) {
                cache = fromDisk
                lastLoad = System.currentTimeMillis() - CACHE_TTL_MS + 5 * 60 * 1000L
                // ↑ on dit que le cache disque a été chargé il y a TTL-5min →
                //   dans 5 min il sera stale et on re-fetche. Mais en attendant
                //   l'utilisateur a ses ~2000 radios instantanément.
                Log.d(TAG, "Loaded ${fromDisk.size} radios from DISK cache")
            }
        }
        val now = System.currentTimeMillis()
        if (cache.isNotEmpty() && now - lastLoad < CACHE_TTL_MS) {
            return@withContext cache
        }
        try {
            val (dric, browser) = coroutineScope {
                val a = async { loadDric4rTvRadios() }
                val b = async { loadBrowserRadiosProtected() }
                a.await() to b.await()
            }
            val dricEnriched = enrichHardcodedWithLiveUrls(dric, browser)
            val all = mergeAndDedup(dricEnriched, browser)
            if (all.isNotEmpty()) {
                cache = all
                lastLoad = now
                Log.d(TAG, "Loaded ${all.size} radios (Dric=${dric.size}, Browser=${browser.size})")
                if (browser.isNotEmpty()) saveToDisk(all)
            }
            all.ifEmpty { cache }
        } catch (t: Throwable) {
            Log.w(TAG, "list() failed: ${t.message}")
            cache
        }
    }

    /** 2026-06-08 (user "tu peux pas faire un truc pour que ça s'affiche en
     *  différé ? On a déjà les premières chaînes qui s'affichent direct,
     *  le reste vient au fur et à mesure") : chargement progressif :
     *   1. Cache (RAM ou disque) si frais → 1 emit final immédiat.
     *   2. Sinon : Dric4rTV (instant ~17 radios) → 1er emit.
     *      Puis RadioBrowser (~5-10s) → 2e emit avec toutes les radios.
     *
     *  2026-07-10 : ajouté cache disque au premier chargement + fetch
     *  protégé NonCancellable (le dialog radio peut se fermer/rouvrir
     *  pendant le fetch → la coroutine se faisait cancel avant d'avoir
     *  essayé tous les miroirs → 17 chaînes seulement).
     *
     *  Retourne un Flow<List<RadioStation>> auquel le dialog peut s'abonner. */
    fun loadProgressive(): kotlinx.coroutines.flow.Flow<List<RadioStation>> =
        kotlinx.coroutines.flow.flow {
            // 2026-07-10 : charger le cache disque si RAM vide (restart app)
            if (!diskLoaded) {
                diskLoaded = true
                val fromDisk = loadFromDisk()
                if (fromDisk.isNotEmpty() && cache.isEmpty()) {
                    cache = fromDisk
                    lastLoad = System.currentTimeMillis() - CACHE_TTL_MS + 5 * 60 * 1000L
                    Log.d(TAG, "Progressive: loaded ${fromDisk.size} radios from DISK cache")
                }
            }
            val now = System.currentTimeMillis()
            if (cache.isNotEmpty() && now - lastLoad < CACHE_TTL_MS) {
                emit(cache)
                return@flow
            }
            // 1er emit : hardcodées (+ cache disque si dispo) — INSTANT
            val dric = loadDric4rTvRadios()
            if (dric.isNotEmpty()) {
                val firstPass = if (cache.size > dric.size) cache else mergeAndDedup(dric, emptyList())
                emit(firstPass)
            }
            // 2e emit : fetch RadioBrowser protégé contre l'annulation
            val browser = loadBrowserRadiosProtected()
            val dricEnriched = enrichHardcodedWithLiveUrls(dric, browser)
            val full = mergeAndDedup(dricEnriched, browser)
            if (full.isNotEmpty()) {
                cache = full
                lastLoad = System.currentTimeMillis()
                Log.d(TAG, "Progressive load complete: ${full.size} radios (Dric=${dric.size}, Browser=${browser.size})")
                if (browser.isNotEmpty()) saveToDisk(full)
            }
            emit(full)
        }.flowOn(Dispatchers.IO)

    /** Merge + dedup (par nom lowercase trim). Dric4rTV d'abord (curated). */
    private fun mergeAndDedup(
        dric: List<RadioStation>,
        browser: List<RadioStation>,
    ): List<RadioStation> {
        val all = mutableListOf<RadioStation>()
        val seenNames = HashSet<String>()
        dric.forEach { if (seenNames.add(normalizeName(it.name))) all.add(it) }
        browser.forEach { if (seenNames.add(normalizeName(it.name))) all.add(it) }
        return all
    }

    /** 2026-07-04 (user "Radio Nova qui fonctionnait — on peut pas mettre les
     *  17 radios au même endroit que les 5000 pour un rafraîchissement instantané") :
     *  Pour chaque radio hardcodée, cherche dans RadioBrowser une entrée qui a
     *  le MÊME nom (normalisé). Si trouvée → remplace `streamUrl` par la version
     *  live de RadioBrowser (rafraîchie automatiquement toutes les 30 min).
     *  Sinon → garde l'URL hardcodée en fallback (=radios locales absentes de
     *  RadioBrowser : CanalB, Prun', Jet FM, ICI Loire-Océan, HitWest, etc.).
     *
     *  On garde le POSTER hardcodé (souvent meilleur que le favicon RadioBrowser
     *  qui manque de résolution) et le NOM d'affichage hardcodé (curation).
     *  Seule la URL de stream est remplacée. L'ID hardcodé aussi est préservé
     *  (compat RadioFavoritesStore déjà saved par les users).
     *
     *  2026-07-04 v2 (user "aucune radio Nova qui fonctionne") : matching en
     *  3 passes pour gérer les variantes (ex "radio Nova" hardcodée vs "Nova"
     *  simple dans RadioBrowser, ou "FIP Groove" vs "FIP - Groove") :
     *   1) match EXACT normalisé (le plus fiable)
     *   2) match par ALIAS pré-définis (curation manuelle des noms connus)
     *   3) match PARTIEL (l'un contient l'autre + longueur suffisante),
     *      restreint aux entrées les plus populaires pour éviter les faux positifs
     */
    private fun enrichHardcodedWithLiveUrls(
        hardcoded: List<RadioStation>,
        browser: List<RadioStation>,
    ): List<RadioStation> {
        if (browser.isEmpty()) return hardcoded
        // Index les radios browser par nom normalisé pour lookup O(1).
        val browserByName = HashMap<String, RadioStation>(browser.size)
        browser.forEach { browserByName.putIfAbsent(normalizeName(it.name), it) }
        // Pour les fallbacks partiels (contains), on parcourt la liste triée par
        // votes (RadioBrowser trie par votes desc dans fetchFrenchStations).
        val browserOrdered = browser.filter { !it.streamUrl.isNullOrBlank() }
        var enrichedCount = 0
        val result = hardcoded.map { hc ->
            val liveUrl = findLiveUrlFor(hc, browserByName, browserOrdered)
            if (liveUrl != null && liveUrl != hc.streamUrl) {
                enrichedCount++
                hc.copy(streamUrl = liveUrl)
            } else {
                hc
            }
        }
        if (enrichedCount > 0) {
            Log.d(TAG, "Enrichi $enrichedCount/${hardcoded.size} radios curées avec URLs RadioBrowser live")
        }
        return result
    }

    /** 3 passes de matching pour trouver l'URL live d'une radio hardcodée. */
    private fun findLiveUrlFor(
        hc: RadioStation,
        browserByName: Map<String, RadioStation>,
        browserOrdered: List<RadioStation>,
    ): String? {
        val hcNorm = normalizeName(hc.name)
        // 1) match exact normalisé
        browserByName[hcNorm]?.streamUrl?.takeIf { it.isNotBlank() }?.let { return it }
        // 2) alias curated : certains noms hardcodés matchent des noms RadioBrowser
        //   spécifiques. Ex "radio Nova" (curation Dric4rTV) → "Nova" (RadioBrowser).
        BROWSER_ALIASES[hcNorm]?.forEach { alias ->
            browserByName[alias]?.streamUrl?.takeIf { it.isNotBlank() }?.let { return it }
        }
        // 3) fallback partiel : cherche une radio browser dont le nom contient le
        //   nom hardcodé (ou inversement). Restreint aux 300 radios les plus
        //   populaires (browserOrdered est trié par votes desc) pour éviter les
        //   faux positifs sur des micros-radios homonymes.
        if (hcNorm.length < 4) return null  // "rtl2" est OK, "fip" trop court
        val candidates = browserOrdered.take(300)
        val bestMatch = candidates.firstOrNull { b ->
            val bNorm = normalizeName(b.name)
            (bNorm.length >= 3) && (bNorm.contains(hcNorm) || hcNorm.contains(bNorm))
        }
        return bestMatch?.streamUrl?.takeIf { it.isNotBlank() }
    }

    /** Alias pour les cas où le nom hardcodé (curation Dric4rTV) diffère du nom
     *  utilisé dans RadioBrowser. Clé = nom hardcodé normalisé. Valeur = liste
     *  de noms RadioBrowser normalisés à essayer. */
    private val BROWSER_ALIASES: Map<String, List<String>> = mapOf(
        "radionova" to listOf("nova", "novaradio", "novaparis"),
        "rireetchansons" to listOf("rireetchansons", "rirechansons", "rireandchansons"),
        "rtl2" to listOf("rtldeux", "rtl2fr"),
        "virginradio" to listOf("virginradio", "virginradiofr", "virginradiofrance"),
        "europe2" to listOf("europedeux", "europe2fr"),
        "fipgroove" to listOf("fipgroove", "fipwebradiogroove", "fipwebgroove"),
        "fipreggae" to listOf("fipreggae", "fipwebradioreggae"),
        "fipnantes" to listOf("fipnantes", "fipwebradionantes"),
        "abcdiscofunk" to listOf("abcdiscofunk", "abclove", "abclovers", "discofunk"),
        "metropolys" to listOf("metropolys", "metropolysradio", "metropolys927"),
        "hitwest" to listOf("hitwest", "hitwestfm"),
        "djamradio" to listOf("djamradio", "djam"),
    )

    /** Normalise un nom de radio pour matching case-insensitive + espaces + accents.
     *  Ex : "Radio Nova" == "radio nova" == "RADIONOVA".
     *  2026-07-04 : ajouté pour l'enrichissement URL live des 17 hardcodées. */
    private fun normalizeName(name: String): String {
        return name.trim().lowercase()
            .replace(Regex("[éèêë]"), "e")
            .replace(Regex("[àâä]"), "a")
            .replace(Regex("[îï]"), "i")
            .replace(Regex("[ôö]"), "o")
            .replace(Regex("[ûü]"), "u")
            .replace(Regex("[ç]"), "c")
            .replace(Regex("[^a-z0-9]+"), "")  // retire espaces + apostrophes + tirets
    }

    /**
     * 2026-06-14 (user "lemieux pour l'instant c'est que les radios soit
     * directement dans l'application au moins elle reste là ça va marcher
     * tout le temps comme ça si on retire toute la source du TV hub on aura
     * toujours les radios") : 17 radios curatées Dric4rTV hardcodées en
     * dur dans l'app — INDÉPENDANTES de dric4rt.free.fr/radio.json.
     *
     * IDs identiques au format Dric4rTvProvider.slugify (compat favoris)
     * pour ne PAS casser les RadioFavoritesStore déjà saved par les users.
     *
     * URLs CDN directes (icecast/infomaniak/NRJ/RTL/France Bleu) qui ne
     * dépendent pas du serveur dric4rt.free.fr. Si une URL meurt un jour,
     * il suffit de la patcher ici + rebuild app.
     *
     * streamUrl != null → RadioPickerDialog appelle playRadioDirect (= pas
     * besoin d'un provider IPTV pour résoudre).
     */
    private val HARDCODED_DRIC4RTV_RADIOS = listOf(
        RadioStation(
            id = "livehub::dric4rtv::r4di0::canalb",
            name = "CanalB",
            poster = "https://canalb.fr/themes/custom/canalb/img/logo_header.png",
            streamUrl = "https://stream.levillage.org/canalb?1742558567358.mp3",
        ),
        RadioStation(
            id = "livehub::dric4rtv::r4di0::fip_groove",
            name = "FIP Groove",
            poster = "https://www.allzicradio.com/media/radios/fip-groove.png",
            streamUrl = "http://icecast.radiofrance.fr/fipgroove-midfi.mp3",
        ),
        RadioStation(
            id = "livehub::dric4rtv::r4di0::fip_reggae",
            name = "FIP Reggae",
            poster = "https://www.allzicradio.com/media/radios/fipwebradio_reggae.png",
            streamUrl = "http://direct.fipradio.fr/live/fip-webradio6.mp3",
        ),
        RadioStation(
            id = "livehub::dric4rtv::r4di0::fip_nantes",
            name = "FIP Nantes",
            poster = "https://upload.wikimedia.org/wikipedia/commons/thumb/1/16/FIP_logo_2021.svg/1200px-FIP_logo_2021.svg.png",
            streamUrl = "http://icecast.radiofrance.fr/fipnantes-midfi.mp3",
        ),
        RadioStation(
            id = "livehub::dric4rtv::r4di0::djam_radio",
            name = "Djam Radio",
            poster = "https://static.wixstatic.com/media/8f2aad_67ad3aceea004dd089cd8ffbae3b893e~mv2.png/v1/fill/w_200,h_200,al_c,q_85,usm_0.66_1.00_0.01,enc_avif,quality_auto/Logo%20Djam%20OK.png",
            streamUrl = "https://stream10.xdevel.com/audio15s976748-2280/stream/icecast.audio",
        ),
        RadioStation(
            id = "livehub::dric4rtv::r4di0::metropolys",
            name = "Metropolys",
            poster = "https://bocir-prod-bucket.s3.amazonaws.com/medias/fXRZGVmtA7/image/logo_metropolys_rds1713452063368-format1by1.jpg",
            streamUrl = "http://stream.rcs.revma.com/rvwymh32w42vv",
        ),
        RadioStation(
            id = "livehub::dric4rtv::r4di0::radio_nova",
            // 2026-07-04 (user "aucune radio Nova qui fonctionne") : renommé de
            //   "radio Nova" en "Nova" pour matcher plus facilement l'entrée
            //   principale de RadioBrowser. L'URL hardcodée (fallback) pointe
            //   maintenant vers le flux Nova principal (pas Nova Dance qui était
            //   une déclinaison — d'où le nom "nova-dance" dans l'ancienne URL).
            name = "Nova",
            poster = "https://upload.wikimedia.org/wikipedia/fr/6/6a/Radio_Nova.png",
            streamUrl = "https://novazz.ice.infomaniak.ch/novazz-128.mp3",
        ),
        RadioStation(
            id = "livehub::dric4rtv::r4di0::rire_chansons",
            name = "Rire & Chansons",
            poster = "https://upload.wikimedia.org/wikipedia/fr/8/84/Logo-rire-et-chansons-2020.png",
            streamUrl = "http://cdn.nrjaudio.fm/adwz1/fr/30443/mp3_128.mp3?origine=fluxradios&aw_0_1st.station=Rire-Chansons-OPEN-DU-RIRE",
        ),
        RadioStation(
            id = "livehub::dric4rtv::r4di0::jet_fm",
            name = "Jet FM",
            poster = "https://lesautrespossibles.fr/wp-content/uploads/2020/06/logo-jet-fm.png",
            streamUrl = "http://80.82.229.202/jetfm.mp3",
        ),
        RadioStation(
            id = "livehub::dric4rtv::r4di0::prun",
            name = "Prun'",
            poster = "https://www.univ-nantes.fr/medias/photo/logo-prun-couleur_1664352643358-png",
            streamUrl = "https://www.prun.net/stream/",
        ),
        RadioStation(
            id = "livehub::dric4rtv::r4di0::rtl2",
            name = "RTL2",
            poster = "https://upload.wikimedia.org/wikipedia/fr/5/51/RTL2.png",
            streamUrl = "http://icecast.rtl2.fr/rtl2-1-44-128?listen=webCwsBCggNCQgLDQUGBAcGBg",
        ),
        RadioStation(
            id = "livehub::dric4rtv::r4di0::europe_2",
            name = "Europe 2",
            poster = "https://www.europe2.fr/wp-content/uploads/europeradio/2022/10/news-750x410.jpeg",
            streamUrl = "http://europe2.lmn.fm/europe2.aac",
        ),
        RadioStation(
            id = "livehub::dric4rtv::r4di0::virgin_radio",
            name = "Virgin Radio",
            poster = "https://upload.wikimedia.org/wikipedia/commons/d/d1/VirginRadio.png",
            streamUrl = "https://virginradio.ice.infomaniak.ch/virgin-radio.mp3",
        ),
        RadioStation(
            id = "livehub::dric4rtv::r4di0::abc_disco_funk",
            name = "ABC Disco Funk",
            poster = "https://static.mytuner.mobi/media/tvos_radios/w5duv9cuuvab.png",
            streamUrl = "http://streaming.radionomy.com/ABC-DISCO-FUNK",
        ),
        RadioStation(
            id = "livehub::dric4rtv::r4di0::hot_fm",
            name = "Hot FM",
            poster = "https://pwaimg.listenlive.co/HOTFMKELATE_992391_config_station_logo_image_1412055702.png",
            streamUrl = "https://edge.iono.fm/xice/57_medium.aac",
        ),
        RadioStation(
            id = "livehub::dric4rtv::r4di0::hitwest",
            name = "HitWest",
            poster = "https://upload.wikimedia.org/wikipedia/fr/a/ad/Logo_Hit_West.png",
            streamUrl = "http://statslive.infomaniak.ch/playlist/hitwest/hitwest-high.mp3/playlist.pls",
        ),
        RadioStation(
            id = "livehub::dric4rtv::r4di0::ici_loire_ocean",
            name = "ICI Loire-Océan",
            poster = "https://upload.wikimedia.org/wikipedia/fr/thumb/0/0b/Ici_Loire_Oc%C3%A9an.svg/485px-Ici_Loire_Oc%C3%A9an.svg.png",
            streamUrl = "http://direct.francebleu.fr/live/fbloireocean-lofi.mp3",
        ),
    )

    /**
     * 2026-06-14 : retourne la liste hardcodée — plus aucune dépendance à
     * Dric4rTvProvider ni au serveur dric4rt.free.fr. Les radios continuent
     * de marcher même si on retire toute la source Dric4rTV du TV Hub.
     */
    private fun loadDric4rTvRadios(): List<RadioStation> {
        return HARDCODED_DRIC4RTV_RADIOS
    }

    /** 2026-06-29 (REPAIR — user "807 vs 790 : les 17 radios manquent du dossier
     *  Musique") : accès public aux 17 radios hardcodées (URLs CDN directes) pour
     *  que LiveTvHubProvider.fetchMusiqueCategoriesPublic les ré-injecte dans le
     *  dossier Musique du TV Hub (perdues quand Dric4rTV a été retiré du hub). */
    fun hardcodedDricRadios(): List<RadioStation> = HARDCODED_DRIC4RTV_RADIOS

    /** 2026-07-10 : version protégée de loadBrowserRadios —
     *  (1) NonCancellable pour que la coroutine finisse même si le collecteur
     *      du Flow annule (dialog radio fermé/rouvert pendant le fetch),
     *  (2) Retry 1× après 2s si le premier essai échoue (les 4 miroirs
     *      peuvent échouer à cause du DNS AdGuard sur Chromecast). */
    private suspend fun loadBrowserRadiosProtected(): List<RadioStation> {
        return withContext(NonCancellable) {
            for (attempt in 1..2) {
                try {
                    val stations = RadioBrowserClient.fetchFrenchStations().map { s ->
                        RadioStation(
                            id = "radio::browser::${s.uuid}",
                            name = s.name,
                            poster = s.favicon,
                            streamUrl = s.url,
                        )
                    }
                    if (stations.isNotEmpty()) {
                        Log.d(TAG, "loadBrowserRadios OK (attempt $attempt): ${stations.size} stations")
                        return@withContext stations
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "loadBrowserRadios attempt $attempt failed: ${t.message}")
                }
                if (attempt < 2) {
                    Log.d(TAG, "loadBrowserRadios: retry in 2s...")
                    delay(2000)
                }
            }
            Log.w(TAG, "loadBrowserRadios: all attempts failed, returning empty")
            emptyList()
        }
    }

    // ---- Persistence disque (2026-07-10) ----

    /** Sauvegarde le cache complet dans un fichier JSON interne.
     *  Format : [{id, name, poster, streamUrl}, ...]. ~200 Ko pour 2000 stations. */
    private fun saveToDisk(stations: List<RadioStation>) {
        try {
            val ctx = StreamFlixApp.instance
            val file = File(ctx.filesDir, DISK_CACHE_FILE)
            val arr = JSONArray()
            for (s in stations) {
                val o = JSONObject()
                o.put("id", s.id)
                o.put("name", s.name)
                if (s.poster != null) o.put("poster", s.poster)
                if (s.streamUrl != null) o.put("streamUrl", s.streamUrl)
                arr.put(o)
            }
            file.writeText(arr.toString())
            Log.d(TAG, "Saved ${stations.size} radios to disk (${file.length() / 1024} KB)")
        } catch (t: Throwable) {
            Log.w(TAG, "saveToDisk failed: ${t.message}")
        }
    }

    /** Charge le cache depuis le fichier JSON. Retourne emptyList si absent/corrompu. */
    private fun loadFromDisk(): List<RadioStation> {
        return try {
            val ctx = StreamFlixApp.instance
            val file = File(ctx.filesDir, DISK_CACHE_FILE)
            if (!file.exists()) return emptyList()
            val arr = JSONArray(file.readText())
            val out = ArrayList<RadioStation>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
                val name = o.optString("name").takeIf { it.isNotBlank() } ?: continue
                out.add(RadioStation(
                    id = id,
                    name = name,
                    poster = o.optString("poster").takeIf { it.isNotBlank() },
                    streamUrl = o.optString("streamUrl").takeIf { it.isNotBlank() },
                ))
            }
            out
        } catch (t: Throwable) {
            Log.w(TAG, "loadFromDisk failed: ${t.message}")
            emptyList()
        }
    }

    /** Sous-liste favoris (= radios dont l'id est dans RadioFavoritesStore). */
    suspend fun favorites(): List<RadioStation> {
        val favIds = RadioFavoritesStore.all()
        if (favIds.isEmpty()) return emptyList()
        return list().filter { it.id in favIds }
    }
}
