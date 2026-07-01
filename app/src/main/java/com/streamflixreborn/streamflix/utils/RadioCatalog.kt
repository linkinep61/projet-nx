package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.streamflixreborn.streamflix.providers.Dric4rTvProvider
import com.streamflixreborn.streamflix.models.TvShow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

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

    data class RadioStation(
        val id: String,
        val name: String,
        val poster: String?,
        val streamUrl: String? = null,  // URL directe pour les stations RadioBrowser
    )

    /** Retourne la liste complète des radios (Dric4rTV + RadioBrowser FR).
     *  Charge en parallèle au premier appel. Safe. */
    suspend fun list(): List<RadioStation> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (cache.isNotEmpty() && now - lastLoad < CACHE_TTL_MS) {
            return@withContext cache
        }
        try {
            val (dric, browser) = coroutineScope {
                val a = async { loadDric4rTvRadios() }
                val b = async { loadBrowserRadios() }
                a.await() to b.await()
            }
            val all = mergeAndDedup(dric, browser)
            if (all.isNotEmpty()) {
                cache = all
                lastLoad = now
                Log.d(TAG, "Loaded ${all.size} radios (Dric=${dric.size}, Browser=${browser.size})")
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
     *   1. Cache si frais → 1 emit final immédiat.
     *   2. Sinon : Dric4rTV (instant ~17 radios) → 1er emit.
     *      Puis RadioBrowser (~5-10s) → 2e emit avec toutes les radios.
     *
     *  Retourne un Flow<List<RadioStation>> auquel le dialog peut s'abonner. */
    fun loadProgressive(): kotlinx.coroutines.flow.Flow<List<RadioStation>> =
        kotlinx.coroutines.flow.flow {
            val now = System.currentTimeMillis()
            if (cache.isNotEmpty() && now - lastLoad < CACHE_TTL_MS) {
                emit(cache)
                return@flow
            }
            val dric = loadDric4rTvRadios()
            if (dric.isNotEmpty()) {
                val firstPass = mergeAndDedup(dric, emptyList())
                emit(firstPass)
            }
            val browser = loadBrowserRadios()
            val full = mergeAndDedup(dric, browser)
            if (full.isNotEmpty()) {
                cache = full
                lastLoad = System.currentTimeMillis()
                Log.d(TAG, "Progressive load complete: ${full.size} radios (Dric=${dric.size}, Browser=${browser.size})")
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
        dric.forEach { if (seenNames.add(it.name.lowercase().trim())) all.add(it) }
        browser.forEach { if (seenNames.add(it.name.lowercase().trim())) all.add(it) }
        return all
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
            name = "radio Nova",
            poster = "https://upload.wikimedia.org/wikipedia/fr/6/6a/Radio_Nova.png",
            streamUrl = "http://nova-dance.ice.infomaniak.ch/nova-dance-256.aac",
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

    private suspend fun loadBrowserRadios(): List<RadioStation> {
        return try {
            RadioBrowserClient.fetchFrenchStations(5000).map { s ->
                RadioStation(
                    id = "radio::browser::${s.uuid}",
                    name = s.name,
                    poster = s.favicon,
                    streamUrl = s.url,
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "loadBrowserRadios failed: ${t.message}")
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
