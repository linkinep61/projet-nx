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
 * telle heure », rien de plus. Il n'est câblé que sur Vavoo pour l'instant (décision user :
 * « si c'était un trop gros chantier pour les autres, tu t'arrêtes juste Vavoo »), mais
 * l'étendre à Mon IPTV ou World Live sera un branchement, pas une réécriture.
 */
object EpgStore {

    private const val TAG = "EpgStore"
    private const val URL_GUIDE = "https://epg.pw/xmltv/epg_FR.xml.gz"

    /** Nom du cache disque. Le guide couvre plusieurs jours ; 12 h de fraîcheur suffisent. */
    private const val FICHIER_CACHE = "epg_fr.bin"
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
    fun cle(nom: String?): String {
        if (nom.isNullOrBlank()) return ""
        val sansAccents = Normalizer.normalize(nom, Normalizer.Form.NFD)
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
            val cache = File(context.cacheDir, FICHIER_CACHE)
            val fraisSurDisque = cache.exists() &&
                System.currentTimeMillis() - cache.lastModified() < FRAICHEUR_MS
            val octets = if (fraisSurDisque) {
                runCatching { cache.readBytes() }.getOrNull()
            } else {
                telecharger()?.also { runCatching { cache.writeBytes(it) } }
                    ?: runCatching { cache.takeIf { c -> c.exists() }?.readBytes() }.getOrNull()
            } ?: run { Log.w(TAG, "guide indisponible (réseau et cache vides)"); return@withLock }

            val t0 = System.currentTimeMillis()
            val table = analyser(octets)
            if (table.isEmpty()) { Log.w(TAG, "guide analysé mais vide"); return@withLock }
            parChaine = table
            chargeA = System.currentTimeMillis()
            Log.i(
                TAG,
                "guide prêt : ${table.size} chaînes, " +
                    "${table.values.sumOf { it.size }} programmes retenus, " +
                    "analysé en ${System.currentTimeMillis() - t0} ms" +
                    if (fraisSurDisque) " (cache disque)" else " (téléchargé)",
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

    private fun telecharger(): ByteArray? = try {
        val req = Request.Builder().url(URL_GUIDE)
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
     */
    private const val DECALAGE_SOURCE_MS = -8L * 60 * 60 * 1000

    /**
     * `20260811203000 +0000` → millisecondes epoch, correction de source comprise.
     * Le décalage explicite est facultatif ; absent, on considère l'heure en UTC.
     * 42 000 programmes × 2 bornes : pas de SimpleDateFormat ni de Calendar ici,
     * on calcule à la main, sans allocation.
     */
    private fun horodatage(brut: String?): Long {
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
        return ms + DECALAGE_SOURCE_MS
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
    private fun analyser(octets: ByteArray): Map<String, List<Programme>> {
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
                                debut = horodatage(p.getAttributeValue(null, "start"))
                                fin = horodatage(p.getAttributeValue(null, "stop"))
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
