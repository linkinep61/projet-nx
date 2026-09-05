package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.util.Log
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.text.Normalizer
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * EpgStore — guide des programmes (EPG) des chaînes françaises.
 *
 * 2026-08-11 (user : « regarde si sur les chaînes Vavoo on peut avoir l'EPG… comme dans mon
 * IPTV, on a la place sur la gauche quand on a le mini lecteur, pour la version télé »).
 *
 * ── POURQUOI CE FICHIER EXISTE ────────────────────────────────────────────────────────
 * Vavoo ne fournit AUCUNE donnée de programme. Son catalogue rend exactement six champs —
 * `type`, `url`, `group`, `ids/id`, `logo`, `name` — vérifié dans son parseur. Pas de
 * `tvg-id`, pas de `url-tvg`. Le guide doit donc venir d'ailleurs et être rapproché des
 * chaînes par leur NOM.
 *
 * ── SOURCE RETENUE, ET POURQUOI ───────────────────────────────────────────────────────
 * `epg.pw/xmltv/epg_FR.xml.gz` — mesuré le 2026-08-11 :
 *   • 516 chaînes françaises, 41 888 programmes
 *   • 3 Mo compressés, téléchargés en 0,4 s ; 16,2 Mo une fois décompressés
 *   • domaine DÉJÀ utilisé par l'app (LiveTvHubProvider s'en sert pour les chaînes WorldWide)
 *
 * Deux autres pistes ont été écartées sur mesure :
 *   • `iptv-org.github.io/epg/guides/…` → 404, ces chemins n'existent plus.
 *   • `epgshare01.online` → fonctionne et utilise la convention `RMC.Story.fr`, donc
 *     compatible avec les `tvg-id` des playlists… mais **45 Mo décompressés pour la seule
 *     France**, contre 16. Sur la Chromecast, non.
 *
 * ⚠ CONSÉQUENCE À CONNAÎTRE : epg.pw NUMÉROTE ses chaînes (`443174` pour TF1). Les `tvg-id`
 *   des playlists (`TF1.fr`, `Kanali7.al`…) n'y correspondent JAMAIS — mesuré : 0 sur 1394.
 *   Le rapprochement se fait donc par NOM NORMALISÉ, et uniquement par là.
 *
 * ── TAUX DE CORRESPONDANCE MESURÉ ─────────────────────────────────────────────────────
 * Sur les 83 chaînes françaises curées de Vavoo : **66 reconnues, soit 80 %**, sans aucune
 * table d'équivalence. Les 17 restantes ne sont pas des échecs de rapprochement — ce sont
 * des chaînes DISPARUES ou RENOMMÉES (C8 et NRJ12 ont quitté la TNT, OCS a été absorbé,
 * Paramount Channel est devenu Paramount Network). D'où [ALIAS] plus bas.
 *
 * ── PIÈGE DU FORMAT ───────────────────────────────────────────────────────────────────
 * Les titres sont annoncés `lang="zh"` alors qu'ils sont en français :
 *     <title lang="zh">Familles nombreuses : la vie en XXL</title>
 * Filtrer sur l'attribut de langue ne rendrait donc RIEN. On l'ignore.
 * Les horaires sont en UTC (`20260811000000 +0000`) : conversion locale obligatoire.
 *
 * ── PORTÉE ────────────────────────────────────────────────────────────────────────────
 * Ce service est INDÉPENDANT du provider : il expose « quel programme sur telle chaîne, à
 * telle heure », rien de plus. Câblé d'abord sur Vavoo (décision user : « si c'était un trop
 * gros chantier pour les autres, tu t'arrêtes juste Vavoo »), puis étendu le 2026-09-05 à
 * World Live et aux grilles des dossiers TV Hub : plusieurs guides fusionnés (voir
 * [GUIDES_DE_BASE] / [PAYS_OPTIONNELS]) et nettoyage des noms de playlist (voir [cle]).
 */
object EpgStore {

    private const val TAG = "EpgStore"

    /**
     * Un guide = une source XMLTV, son cache disque, et le DÉCALAGE propre à la source (voir
     * [DECALAGE_EPG_PW] : epg.pw estampille l'heure de Shanghai en `+0000`, les autres non).
     */
    data class Guide(val code: String, val url: String, val fichier: String, val decalageMs: Long)

    /**
     * ── PLUSIEURS GUIDES, FUSIONNÉS ──────────────────────────────────────────────────
     * 2026-09-05 (user : « dans World Live, avoir dans la jaquette le programme, comme sur
     * Vavoo ») : les playlists de World Live sont surtout françaises (Mix FR, FAST FR,
     * paradis, iptv-org FR), mais epg.pw FR ne connaît PAS les chaînes FAST. Mesuré avec
     * `epg_couverture.py`, par nom normalisé, avant → après ajout des trois guides FAST :
     *   Mix FR       7 % → 55 %   (le reste = Multi Live, Zone 18@… : rien à guider)
     *   FAST FR      2 % → 51 %   (Samsung TV+ 303, Plex 306, Pluto 234 reconnues)
     *   paradis      0 % → 70 %   (grâce aussi au nettoyage des noms, voir [cle])
     *   iptv-org FR  5 % → 66 %
     * Les guides i.mjh.nz sont minuscules (0,2 à 0,6 Mo) et en VRAI UTC — pas de −8 h.
     */
    private val GUIDES_DE_BASE: List<Guide> = listOf(
        Guide("FR", "https://epg.pw/xmltv/epg_FR.xml.gz", "epg_fr.bin", DECALAGE_EPG_PW),
        Guide("PLUTO_FR", "https://i.mjh.nz/PlutoTV/fr.xml.gz", "epg_pluto_fr.bin", 0L),
        Guide("SAMSUNG_FR", "https://i.mjh.nz/SamsungTVPlus/fr.xml.gz", "epg_samsung_fr.bin", 0L),
        Guide("PLEX_FR", "https://i.mjh.nz/Plex/fr.xml.gz", "epg_plex_fr.bin", 0L),
    )

    /**
     * Guides étrangers OPTIONNELS (World Live a des playlists portugaises, anglaises…).
     * Désactivés par défaut : chacun pèse 1,5 à 3,6 Mo à télécharger, la Chromecast n'a pas
     * à les payer pour des chaînes que la plupart des gens ne regardent pas. Activables dans
     * « Mes sources World TV » → « Guide TV ». epgshare01 écrit de vrais décalages horaires
     * (`+0100`), donc décalage 0.
     */
    val PAYS_OPTIONNELS: List<Pair<Guide, String>> = listOf(
        Guide("PT", "https://epgshare01.online/epgshare01/epg_ripper_PT1.xml.gz", "epg_pt.bin", 0L) to "Portugal",
        Guide("GB", "https://epgshare01.online/epgshare01/epg_ripper_UK1.xml.gz", "epg_gb.bin", 0L) to "Royaume-Uni",
        Guide("ES", "https://epgshare01.online/epgshare01/epg_ripper_ES1.xml.gz", "epg_es.bin", 0L) to "Espagne",
        Guide("IT", "https://epgshare01.online/epgshare01/epg_ripper_IT1.xml.gz", "epg_it.bin", 0L) to "Italie",
        Guide("DE", "https://epgshare01.online/epgshare01/epg_ripper_DE1.xml.gz", "epg_de.bin", 0L) to "Allemagne",
        Guide("BR", "https://epgshare01.online/epgshare01/epg_ripper_BR1.xml.gz", "epg_br.bin", 0L) to "Brésil",
        Guide("CA", "https://epg.pw/xmltv/epg_CA.xml.gz", "epg_ca.bin", DECALAGE_EPG_PW) to "Canada",
    )

    private const val PREF_PAYS = "epg_pays_supplementaires"

    /** Codes des guides optionnels activés par l'utilisateur (ex. « PT,GB »). */
    fun paysSupplementaires(context: Context): Set<String> =
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_PAYS, null).orEmpty()
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** Enregistre le choix et force un rechargement au prochain [prechargerSiNecessaire]. */
    fun definirPaysSupplementaires(context: Context, codes: Set<String>) {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putString(PREF_PAYS, codes.joinToString(",")).apply()
        chargeA = 0L
    }

    private fun guidesActifs(context: Context): List<Guide> {
        val codes = paysSupplementaires(context)
        return GUIDES_DE_BASE + PAYS_OPTIONNELS.map { it.first }.filter { it.code in codes }
    }

    /** Le guide couvre plusieurs jours ; 12 h de fraîcheur suffisent. */
    private const val FRAICHEUR_MS = 12L * 60 * 60 * 1000

    /**
     * Fenêtre conservée en mémoire, autour de maintenant. Le fichier fait 16 Mo et couvre
     * plusieurs jours ; on n'en garde que de quoi répondre « en cours » et « à suivre »
     * pendant une journée d'utilisation, sinon la Chromecast souffre.
     */
    private const val AVANT_MS = 6L * 60 * 60 * 1000
    private const val APRES_MS = 30L * 60 * 60 * 1000

    data class Programme(
        val debutMs: Long,
        val finMs: Long,
        val titre: String,
        val description: String?,
    )

    @Volatile private var parChaine: Map<String, List<Programme>> = emptyMap()
    @Volatile private var chargeA: Long = 0L
    private val verrou = Mutex()

    // ── normalisation ────────────────────────────────────────────────────────────────
    /**
     * Même esprit que `VavooProvider.normalizeKey` : on veut que « Canal+ Sport »,
     * « CANAL+ SPORT » et « canal plus sport » donnent la même clé.
     */
    /**
     * 2026-09-05 — NETTOYAGE DES DÉCORATIONS DE PLAYLIST, mesuré sur les sources World Live :
     *   « 48. France 2 [SSAI][1080p-france.tv] »  (paradis)   → « france2 »
     *   « 6ter (1080p) », « TF1 [Not 24/7] »       (iptv-org)  → « 6ter », « tf1 »
     *   « RTP Noticias_ 🇵🇹 », « RTP Mundo ᴸᴼᵂ »   (FreeTV)    → « rtpnoticias », « rtpmundo »
     * Sans ça, paradis était reconnue à 0 % ; avec, 70 %. On ne retire ENTRE PARENTHÈSES que
     * ce qui parle de qualité ou de disponibilité : « Canal+ (Sport) » doit rester distinct.
     */
    private val RE_NUMERO_TETE = Regex("""^\s*\d{1,4}\s*[.\-:)]\s*""")
    private val RE_CROCHETS = Regex("""\[[^\]]*]""")
    private val RE_PARENTHESES_QUALITE =
        Regex("""\((?:[^)]*(?:\d{3,4}[pi]|4k|uhd|hd|sd|24/7|geo)[^)]*)\)""", RegexOption.IGNORE_CASE)

    fun cle(nom: String?): String {
        if (nom.isNullOrBlank()) return ""
        val nettoye = nom
            .replace(RE_NUMERO_TETE, "")
            .replace(RE_CROCHETS, " ")
            .replace(RE_PARENTHESES_QUALITE, " ")
        val sansAccents = Normalizer.normalize(nettoye, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return sansAccents.lowercase()
            .replace("+", "plus")
            .replace("&", "and")
            .replace(Regex("[^a-z0-9]"), "")
    }

    /**
     * Chaînes que Vavoo nomme autrement que le guide, ou qui ont changé de nom.
     * Chaque entrée vient d'un cas RÉEL relevé dans les 17 non-reconnues.
     */
    private val ALIAS: Map<String, String> = mapOf(
        // Canal+ : le guide écrit « Canal+ Cinéma(s) », « Canal+ Séries »…
        "canalpluscinema" to "canalpluscinemas",
        "canalplusboxoffice" to "canalplusboxoffice",
        // Paramount Channel → renommée Paramount Network
        "paramountchannel" to "paramountnetwork",
        // Ciné+ a été renommé « Ciné+ Premier » → « Ciné+ OCS Premier » chez certains guides
        "cinepluspremier" to "cineplusocspremier",
        "histoiretv" to "histoire",
    )

    // ── API publique ─────────────────────────────────────────────────────────────────

    /** Le guide est-il utilisable ? (au moins une chaîne connue) */
    fun estPret(): Boolean = parChaine.isNotEmpty()

    /** Programme en cours sur cette chaîne, ou `null` si inconnue / pas de données. */
    fun enCours(nomChaine: String, instantMs: Long = System.currentTimeMillis()): Programme? =
        programmes(nomChaine).firstOrNull { instantMs in it.debutMs until it.finMs }

    /** Programme suivant, ou `null`. */
    fun aSuivre(nomChaine: String, instantMs: Long = System.currentTimeMillis()): Programme? =
        programmes(nomChaine).firstOrNull { it.debutMs > instantMs }

    private fun programmes(nomChaine: String): List<Programme> {
        val k = cle(nomChaine)
        if (k.isEmpty()) return emptyList()
        parChaine[k]?.let { return it }
        ALIAS[k]?.let { alias -> parChaine[alias]?.let { return it } }
        // Dernier recours : la chaîne peut porter un suffixe de qualité (« TF1 HD »,
        //   « M6 FHD ») que le guide n'a pas. On retente sans ce suffixe.
        val sansQualite = k.removeSuffix("fhd").removeSuffix("uhd")
            .removeSuffix("hd").removeSuffix("sd").removeSuffix("4k")
        if (sansQualite != k && sansQualite.length >= 2) {
            parChaine[sansQualite]?.let { return it }
            ALIAS[sansQualite]?.let { a -> parChaine[a]?.let { return it } }
        }
        return emptyList()
    }

    /**
     * Charge le guide si besoin. Idempotent, sans reprise si déjà frais.
     * À appeler depuis un contexte d'arrière-plan — jamais sur le thread principal.
     */
    suspend fun prechargerSiNecessaire(context: Context) = withContext(Dispatchers.IO) {
        if (parChaine.isNotEmpty() && System.currentTimeMillis() - chargeA < FRAICHEUR_MS) return@withContext
        verrou.withLock {
            if (parChaine.isNotEmpty() && System.currentTimeMillis() - chargeA < FRAICHEUR_MS) return@withLock
            val t0 = System.currentTimeMillis()
            // Chaque guide est téléchargé, mis en cache et analysé SÉPARÉMENT (son propre
            // décalage horaire), puis les tables sont fusionnées : en cas de doublon de nom
            // entre deux guides, la grille la plus fournie l'emporte — même règle qu'à
            // l'intérieur d'un guide (voir la fin d'[analyser]).
            val fusion = HashMap<String, List<Programme>>(1200)
            val bilan = StringBuilder()
            for (guide in guidesActifs(context)) {
                val cache = File(context.cacheDir, guide.fichier)
                val fraisSurDisque = cache.exists() &&
                    System.currentTimeMillis() - cache.lastModified() < FRAICHEUR_MS
                val octets = if (fraisSurDisque) {
                    runCatching { cache.readBytes() }.getOrNull()
                } else {
                    telecharger(guide.url)?.also { runCatching { cache.writeBytes(it) } }
                        ?: runCatching { cache.takeIf { c -> c.exists() }?.readBytes() }.getOrNull()
                }
                if (octets == null) { Log.w(TAG, "guide ${guide.code} indisponible (réseau et cache vides)"); continue }
                val table = analyser(octets, guide.decalageMs)
                for ((k, liste) in table) {
                    val actuelle = fusion[k]
                    if (actuelle == null || liste.size > actuelle.size) fusion[k] = liste
                }
                bilan.append(guide.code).append('=').append(table.size)
                    .append(if (fraisSurDisque) "(disque) " else "(web) ")
            }
            if (fusion.isEmpty()) { Log.w(TAG, "aucun guide exploitable"); return@withLock }
            parChaine = fusion
            chargeA = System.currentTimeMillis()
            Log.i(
                TAG,
                "guide prêt : ${fusion.size} chaînes, " +
                    "${fusion.values.sumOf { it.size }} programmes retenus, " +
                    "en ${System.currentTimeMillis() - t0} ms — $bilan",
            )
        }
    }

    /** Client dédié : 3 Mo à rapatrier, donc des délais plus larges que la moyenne de l'app. */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private fun telecharger(url: String): ByteArray? = try {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) { Log.w(TAG, "téléchargement HTTP ${r.code}"); null }
            else r.body?.bytes()
        }
    } catch (e: Exception) {
        Log.w(TAG, "téléchargement KO : ${e.message}"); null
    }

    // ── analyse XMLTV ────────────────────────────────────────────────────────────────

    /**
     * Le fichier est servi en `.gz`, mais si un jour le serveur ajoute `Content-Encoding:
     * gzip`, OkHttp décompresse tout seul et les octets arrivent en clair : le double
     * GZIPInputStream planterait alors sur toute la ligne. On regarde donc la signature
     * (1F 8B) au lieu de faire confiance à l'extension.
     */
    private fun flux(octets: ByteArray): InputStream {
        val brut = ByteArrayInputStream(octets)
        val gzippe = octets.size > 2 &&
            octets[0] == 0x1F.toByte() && octets[1] == 0x8B.toByte()
        return if (gzippe) GZIPInputStream(brut, 32 * 1024) else brut
    }

    /**
     * ⚠⚠ CORRECTION DE FUSEAU — NE PAS SUPPRIMER SANS REMESURER ⚠⚠
     *
     * epg.pw écrit `+0000` sur TOUS ses horodatages, mais les valeurs ne sont PAS en UTC :
     * elles sont en **UTC+8 (heure de Shanghai)**. C'est cohérent avec l'autre bizarrerie de
     * cette source, déjà notée en tête de fichier : `<title lang="zh">` sur des titres
     * français. Le fichier est fabriqué en Chine et l'heure locale y est estampillée UTC.
     *
     * Mesuré le 2026-08-11 sur des programmes qui ANNONCENT leur propre heure de diffusion
     * (méthode : on ne devine pas, on prend des titres qui contiennent l'heure) :
     *
     *   TF1  « JT 13h »                   déclaré 19:00  → réel 13:00   (−8 h wall-clock)
     *   TF1  « JT 20h »                   déclaré 02:00  → réel 20:00   (−8 h)
     *   TF1  « Les douze coups de midi »  déclaré 17:50  → réel 11:50   (−8 h)
     *
     * Sur 41 chaînes disposant d'une ancre fiable, **32 tombent exactement sur −8 h**. Les
     * autres sont des chaînes réellement situées dans un autre fuseau (Guyane 1ère,
     * Martinique 1ère, Polynésie 1ère, i24news, ORTM) : leur « Journal 20h » passe bien à
     * 20 h CHEZ ELLES. Ce n'est donc pas du bruit, et ça ne remet pas en cause le −8 h.
     *
     * Shanghai n'applique pas d'heure d'été : la correction est constante toute l'année.
     * Sans elle, le guide annonçait « Télématin » à 16 h 30 — vu à l'écran avant correction.
     *
     * ⚠ 2026-09-05 : cette correction ne vaut QUE pour epg.pw. Les guides i.mjh.nz et
     *   epgshare01 sont en vrai UTC / avec de vrais décalages (vérifié : « Catfish » Pluto
     *   annoncé `124400 +0000` pour 14 h 44 à Paris). D'où un décalage PAR GUIDE, porté par
     *   [Guide.decalageMs] et passé à [analyser].
     */
    private const val DECALAGE_EPG_PW = -8L * 60 * 60 * 1000

    /**
     * `20260811203000 +0000` → millisecondes epoch, correction de source comprise.
     * Le décalage explicite est facultatif ; absent, on considère l'heure en UTC.
     * 42 000 programmes × 2 bornes : pas de SimpleDateFormat ni de Calendar ici,
     * on calcule à la main, sans allocation.
     */
    private fun horodatage(brut: String?, decalageSourceMs: Long): Long {
        if (brut == null || brut.length < 14) return 0L
        for (i in 0 until 14) if (brut[i] !in '0'..'9') return 0L
        val an = brut.substring(0, 4).toInt()
        val mois = brut.substring(4, 6).toInt()
        val jour = brut.substring(6, 8).toInt()
        val heure = brut.substring(8, 10).toInt()
        val minute = brut.substring(10, 12).toInt()
        val seconde = brut.substring(12, 14).toInt()
        if (mois !in 1..12 || jour !in 1..31 || heure > 23 || minute > 59 || seconde > 60) return 0L

        var ms = (joursDepuisEpoque(an, mois, jour) * 86_400L +
            heure * 3_600L + minute * 60L + seconde) * 1_000L

        val reste = brut.substring(14).trim()
        if (reste.length >= 5 && (reste[0] == '+' || reste[0] == '-')) {
            val hh = reste.substring(1, 3).toIntOrNull() ?: 0
            val mm = reste.substring(3, 5).toIntOrNull() ?: 0
            val decalage = (hh * 3_600L + mm * 60L) * 1_000L
            ms += if (reste[0] == '+') -decalage else decalage
        }
        return ms + decalageSourceMs
    }

    /** « days from civil » (Howard Hinnant) — exact, y compris années bissextiles séculaires. */
    private fun joursDepuisEpoque(an: Int, mois: Int, jour: Int): Long {
        val y = if (mois <= 2) an - 1 else an
        val ere = (if (y >= 0) y else y - 399) / 400
        val anneeDansEre = y - ere * 400
        val jourDansAnnee = (153 * (if (mois > 2) mois - 3 else mois + 9) + 2) / 5 + jour - 1
        val jourDansEre = anneeDansEre * 365L + anneeDansEre / 4 - anneeDansEre / 100 + jourDansAnnee
        return ere * 146_097L + jourDansEre - 719_468L
    }

    /**
     * Parse le XMLTV EN FLUX (XmlPullParser) : 16 Mo décompressés ne sont jamais matérialisés
     * en String, et seuls les programmes de la fenêtre [-6 h, +30 h] sont conservés.
     *
     * ⚠ L'attribut `lang` de `<title>` est DÉLIBÉRÉMENT ignoré : epg.pw annonce `lang="zh"`
     *   sur des titres français. Filtrer dessus ne rendrait rien du tout.
     *
     * Les `<channel>` et les `<programme>` sont collectés séparément puis rapprochés à la
     * fin, pour ne dépendre d'aucun ordre d'apparition dans le fichier.
     */
    private fun analyser(octets: ByteArray, decalageSourceMs: Long): Map<String, List<Programme>> {
        val maintenant = System.currentTimeMillis()
        val borneBasse = maintenant - AVANT_MS
        val borneHaute = maintenant + APRES_MS

        val nomParId = HashMap<String, String>(700)
        val progParId = HashMap<String, MutableList<Programme>>(700)

        try {
            flux(octets).use { entree ->
                val p = Xml.newPullParser()
                p.setInput(entree, null) // encodage repris du prologue XML

                var idChaine: String? = null
                var nomPris = false

                var idProg: String? = null
                var debut = 0L
                var fin = 0L
                var titre: String? = null
                var description: String? = null

                var champ = 0 // 1 display-name, 2 title, 3 desc
                val tampon = StringBuilder(128)

                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    when (ev) {
                        XmlPullParser.START_TAG -> when (p.name) {
                            "channel" -> {
                                idChaine = p.getAttributeValue(null, "id")
                                nomPris = false
                            }
                            "display-name" -> if (idChaine != null && !nomPris) {
                                champ = 1; tampon.setLength(0)
                            }
                            "programme" -> {
                                idProg = p.getAttributeValue(null, "channel")
                                debut = horodatage(p.getAttributeValue(null, "start"), decalageSourceMs)
                                fin = horodatage(p.getAttributeValue(null, "stop"), decalageSourceMs)
                                titre = null; description = null
                            }
                            "title" -> if (idProg != null && titre == null) {
                                champ = 2; tampon.setLength(0)
                            }
                            "desc" -> if (idProg != null && description == null) {
                                champ = 3; tampon.setLength(0)
                            }
                        }

                        XmlPullParser.TEXT -> if (champ != 0) tampon.append(p.text)

                        XmlPullParser.END_TAG -> when (p.name) {
                            "display-name" -> {
                                if (champ == 1) {
                                    val n = tampon.toString().trim()
                                    val id = idChaine
                                    if (id != null && n.isNotBlank()) { nomParId[id] = n; nomPris = true }
                                }
                                champ = 0
                            }
                            "title" -> { if (champ == 2) titre = tampon.toString().trim(); champ = 0 }
                            "desc" -> { if (champ == 3) description = tampon.toString().trim(); champ = 0 }
                            "channel" -> idChaine = null
                            "programme" -> {
                                val id = idProg
                                val t = titre
                                if (id != null && !t.isNullOrBlank() &&
                                    debut > 0L && fin > debut &&
                                    fin > borneBasse && debut < borneHaute
                                ) {
                                    progParId.getOrPut(id) { ArrayList(48) }.add(
                                        Programme(debut, fin, t, description?.takeIf { it.isNotBlank() }),
                                    )
                                }
                                idProg = null; titre = null; description = null
                            }
                        }
                    }
                    ev = p.next()
                }
            }
        } catch (e: Exception) {
            // Un guide tronqué reste exploitable : on garde ce qui a été lu avant la coupure.
            Log.w(TAG, "analyse XMLTV interrompue : ${e.message}")
        }

        // id numérique → nom normalisé (voir l'avertissement en tête de fichier).
        //
        // ⚠ Plusieurs ids portent parfois le MÊME nom (« France 2 » existe en double chez
        //   epg.pw) avec des grilles qui ne se recouvrent pas. Les CONCATÉNER produirait une
        //   grille incohérente, où « en cours » peut tomber sur le doublon. On garde donc la
        //   grille la PLUS FOURNIE et on jette l'autre : mieux vaut une source unique et
        //   cohérente qu'un mélange.
        val parNom = HashMap<String, MutableList<Programme>>(nomParId.size)
        for ((id, liste) in progParId) {
            val nom = nomParId[id] ?: continue
            val k = cle(nom)
            if (k.isEmpty()) continue
            val actuelle = parNom[k]
            if (actuelle == null || liste.size > actuelle.size) parNom[k] = liste
        }

        // Le guide écrit parfois « TF1 HD » là où Vavoo écrit « TF1 ». On ajoute la clé sans
        // suffixe de qualité, mais SANS JAMAIS écraser une chaîne qui porte déjà ce nom.
        for ((k, liste) in parNom.toList()) {
            val nu = k.removeSuffix("fhd").removeSuffix("uhd")
                .removeSuffix("hd").removeSuffix("sd").removeSuffix("4k")
            if (nu != k && nu.length >= 2 && !parNom.containsKey(nu)) parNom[nu] = liste
        }

        // Tri chronologique + dédoublonnage : deux ids peuvent porter le même nom et
        // rediffuser exactement la même grille.
        val fige = HashMap<String, List<Programme>>(parNom.size)
        for ((k, liste) in parNom) {
            val trie = liste.sortedBy { it.debutMs }
            val propre = ArrayList<Programme>(trie.size)
            var dernierDebut = Long.MIN_VALUE
            for (pr in trie) {
                if (pr.debutMs == dernierDebut) continue
                propre.add(pr)
                dernierDebut = pr.debutMs
            }
            fige[k] = propre
        }
        return fige
    }
}
