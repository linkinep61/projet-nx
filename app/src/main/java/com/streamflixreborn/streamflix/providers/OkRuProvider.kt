package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/**
 * OkRuProvider — backup NATIF par TITRE (ok.ru, 2026-08-08).
 *
 * Origine : le user a trouvé « Star Trek » (1966) en VF sur ok.ru alors qu'AUCUN des neuf
 * sites FR de l'app ne l'avait — vérifié un par un le même soir. ok.ru est plein de vieilles
 * séries et de films FR que les sites de streaming n'indexent plus.
 *
 * ── PROTOCOLE (rétro-conçu dans le Chrome du user, hook fetch/XHR) ────────────────────
 * La recherche N'EST PAS un GET : tous les `GET /video/search?...` renvoient 0 octet, ce qui
 * m'a fait croire à tort à un mur de connexion. C'est un POST JSON, et il répond en ANONYME :
 *
 *   POST https://ok.ru/web-api/v2/video/fetchSearchResult
 *   {"id":1,"parameters":{"mode":"Movie","searchQuery":"…","count":30,"screen":"VIDEO_SEARCH"}}
 *   → result.videos.list[].movie = { id, title, duration (ms), width, height }
 *
 * ⚠ Le champ s'appelle `searchQuery`, PAS `query` : avec `query` l'API répond
 *   `api.invalid-parameter: searchQuery -> Missing parameter`. C'est elle qui donne son schéma
 *   dans son message d'erreur (`3 known properties: filter, parameters, id`).
 *
 * Puis la lecture :
 *   GET https://ok.ru/video/<id>  →  attribut `data-options` (JSON échappé HTML)
 *   → flashvars.metadata.videos[]        = 4 à 6 définitions MP4 directes (mobile→full)
 *   → flashvars.metadata.hlsManifestUrl  = HLS de secours
 *
 * ⚠ Les URLs de flux portent `srcIp=…` : elles sont LIÉES À L'IP qui a demandé la page, et
 *   un `expires=` limite leur durée de vie. On résout donc AU MOMENT DE LA LECTURE, sur
 *   l'appareil qui lit — jamais de mise en cache d'un client à l'autre.
 */
object OkRuProvider {
    private const val TAG = "OkRuProvider"
    private const val BASE = "https://ok.ru"
    const val SRC_PREFIX = "okru::"

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"

    /** Candidats demandés à l'API. Au-delà ce n'est plus que du bruit. */
    private const val NB_RESULTATS = 30

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .followRedirects(true)
            .dns(DnsResolver.doh)
            .build()
    }

    // ── normalisation ────────────────────────────────────────────────────────────────
    private fun sansAccents(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    private fun norm(s: String): String =
        sansAccents(s).lowercase()
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Marqueurs de version française — audio VF ou sous-titres FR. */
    private val MARQUEUR_FR = Regex(
        "\\b(fren|french|truefrench|vff?|vfq|vfi|multi|vostfr|francais|version francaise)\\b"
    )

    /** Marqueurs de langue étrangère : rejet immédiat (ok.ru est très international). */
    private val MARQUEUR_ETRANGER = Regex(
        "\\b(hun|ita|ger|deu|esp|spa|rus|pol|cze|tur|ned|dut|swe|nor|fin|dan|por|jap|kor|chi|hindi)\\b"
    )

    /**
     * Vocabulaire NEUTRE d'un nom de release : ces mots ne disent rien de l'œuvre, ils ne
     * doivent donc pas compter comme « mot en trop » dans le contrôle de franchise ci-dessous.
     */
    private val MOTS_NEUTRES = (
        "french truefrench vf vff vfq vfi vostfr multi fren web webrip webdl bluray brrip " +
        "dvdrip hdtv x264 x265 h264 h265 hevc avc aac ac3 dts 480p 720p 1080p 2160p 4k hd sd " +
        "rip season saison episode ep complete integrale www fr en " +
        // mots-outils : ils n'identifient aucune œuvre
        "the a an of and or les la le de du des un une " +
        // « The Original Series » / « TOS » désignent la série MÈRE, pas une dérivée : les
        //   laisser passer est voulu. « next », « generation », « voyager », « discovery »…
        //   n'y sont surtout PAS — c'est exactement ce que le contrôle doit attraper.
        "original series serie tos"
        ).split(" ").toSet()

    /** Marqueur de position SxxExx / Exx / 1x21 dans un nom de release. */
    private val RE_MARQUEUR_POS = Regex("^(s\\d{1,3}(e\\d{1,3})?|e\\d{1,3}|\\d{1,2}x\\d{1,3})$")

    /**
     * Retire ce qui n'appartient pas au nom de l'œuvre : préfixe d'uploader entre crochets
     * (`[WwW.VoirFilms.org]-…`) et noms de domaine collés au titre. Sans ce nettoyage, le
     * contrôle de franchise recalait à tort deux versions FRANÇAISES légitimes de S01E21,
     * uniquement parce que l'uploader avait signé son fichier.
     */
    private val RE_SIGNATURE = Regex(
        "(www\\.|\\.(com|org|net|co|io|tv|me|cc|to)\\b|^\\s*(source|\\d{3,4}p)\\s*$)",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 2026-08-25 (user : « OK.RU a mis un mauvais match ») — SIGNATURE D'UPLOADER COLLEE.
     *
     *   L'ancienne expression etait `\\b\\S+\\.(org|com|…)\\b`. Or un nom de release n'a
     *   AUCUNE espace : `\\S+` avalait tout depuis le debut de la chaine jusqu'au domaine
     *   final. Sur « Nailed.It.S01E02.FRENCH.WEBRip.XviD-WWW.ADDSERIE.COM », le nettoyage
     *   rendait une chaine VIDE — plus de jetons, donc plus de marqueur SxxExx, donc le
     *   controle de franchise etait saute et la video passait. C'est comme ca que deux
     *   episodes de « Nailed It! » ont ete servis pour « Ça » (titre alternatif « It »).
     *
     *   Le jeton de domaine ne doit pas traverser les points : on n'avale que
     *   « ADDSERIE.COM », « SERIE-VOSTFR.ME », « VoirFilms.org ».
     */
    private val RE_DOMAINE = Regex(
        "\\b[A-Za-z0-9-]+\\.(org|com|net|co|io|tv|me|cc|to)\\b",
        RegexOption.IGNORE_CASE,
    )

    private fun nettoyerTitre(t: String): String =
        // ⚠ On ne vide un crochet QUE s'il contient une signature d'uploader (un domaine, un
        //   « www. », un « [720p] », un « [source] »). Vider tous les crochets était un vrai
        //   trou : « Star Trek [La Nouvelle Generation] S03-E12.FRENCH » passait le contrôle
        //   de franchise puisqu'on venait d'effacer « La Nouvelle Generation » — soit
        //   exactement le mauvais match qu'on cherche à éliminer. Mesuré sur ok.ru.
        Regex("\\[([^\\]]*)\\]").replace(t) { m ->
            if (RE_SIGNATURE.containsMatchIn(m.groupValues[1])) " " else " ${m.groupValues[1]} "
        }.replace(RE_DOMAINE, " ")

    private data class Candidat(
        val id: String,
        val titre: String,
        val dureeS: Int,
        val hauteur: Int,
    )

    private fun rechercher(requete: String): List<Candidat> {
        val corps = JSONObject().apply {
            put("id", 1)
            put("parameters", JSONObject().apply {
                put("mode", "Movie")
                put("searchQuery", requete)
                put("count", NB_RESULTATS)
                put("screen", "VIDEO_SEARCH")
            })
        }.toString()

        val req = Request.Builder()
            .url("$BASE/web-api/v2/video/fetchSearchResult")
            .post(corps.toRequestBody("application/json".toMediaType()))
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .header("Accept-Language", "fr-FR,fr;q=0.9")
            .header("Origin", BASE)
            .header("Referer", "$BASE/video/showcase")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()

        val txt = try {
            client.newCall(req).execute().use { r -> r.body?.string() }
        } catch (e: Exception) {
            Log.w(TAG, "recherche '$requete' : ${e.message}"); null
        } ?: return emptyList()

        return try {
            val liste = JSONObject(txt).optJSONObject("result")
                ?.optJSONObject("videos")?.optJSONArray("list") ?: return emptyList()
            (0 until liste.length()).mapNotNull { i ->
                val m = liste.optJSONObject(i)?.optJSONObject("movie") ?: return@mapNotNull null
                val id = m.optString("id", "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Candidat(
                    id = id,
                    titre = m.optString("title", ""),
                    dureeS = (m.optLong("duration", 0L) / 1000L).toInt(),
                    hauteur = m.optInt("height", 0),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "parse recherche : ${e.message}"); emptyList()
        }
    }

    /**
     * MATCHING STRICT (exigence user : « il y a pas mal de mauvaises choses »).
     * ok.ru rend 9 400 résultats sur « Star Trek », en russe, anglais, hongrois… Cinq filtres
     * cumulés, validés sur des cas RÉELS (Star Trek S01E19 et S01E21) :
     *   1. titre de l'œuvre présent (normalisé : sans accents ni ponctuation) ;
     *   2. aucun mot de série dérivée (Strange New Worlds, Discovery, Voyager…) ;
     *   3. SxxExx pour un épisode, année pour un film ;
     *   4. durée à −20/+25 % du runtime TMDB — élimine bandes-annonces, extraits, compilations ;
     *   5. signal FRANÇAIS : marqueur de release (FRENCH/VF/TRUEFRENCH/VOSTFR…) OU le titre
     *      français de l'épisode donné par TMDB ; et rejet sur marqueur de langue étrangère.
     *
     * ⚠ Le point 5 mérite l'explication. L'interface d'ok.ru LOCALISE les titres : elle
     *   affichait « Star Trek TOS S01 E19 Demain, c'est hier » pour une vidéo dont le vrai
     *   titre — dans la page comme dans l'API — est `Star.trek.TOS.s01e19.Tomorrow.is.Yesterday`,
     *   donc de la VO. Se fier à l'affichage du site aurait servi de la VO présentée comme
     *   française. On ne matche QUE sur le titre brut de l'API.
     *
     * Mesuré sur S01E21 : 3 candidats → 1 retenu (le FRENCH) ; le hongrois et la VO écartés.
     * Mesuré sur S01E19 : 2 candidats → 0 retenu (il n'existe qu'en VO et en hongrois).
     */
    private fun retenir(
        candidats: List<Candidat>,
        titreOeuvre: String,
        saison: Int,
        episode: Int,
        annee: Int?,
        runtimeS: Int?,
        titreEpisodeFr: String?,
        motsInterdits: Set<String>,
        vocabulaire: Set<String> = emptySet(),
    ): List<Candidat> {
        val nOeuvre = norm(titreOeuvre)
        if (nOeuvre.isBlank()) return emptyList()
        val estEpisode = saison > 0 && episode > 0

        return candidats.filter { c ->
            val n = norm(c.titre)
            val compact = n.replace(" ", "")

            if (!n.contains(nOeuvre)) return@filter false
            if (motsInterdits.any { n.contains(it) }) return@filter false

            if (estEpisode) {
                val re = Regex("(s0*${saison}e0*${episode}|${saison}x0*${episode})(?![0-9])")
                if (!re.containsMatchIn(compact)) return@filter false
            } else if (annee != null && annee > 1900) {
                if (!n.contains(annee.toString())) return@filter false
            }

            // ── contrôle de franchise ────────────────────────────────────────────────
            // Tout ce qui précède le marqueur SxxExx (ou l'année, pour un film) doit
            //   appartenir à l'œuvre : vocabulaire de ses titres connus, vocabulaire neutre
            //   d'un nom de release, ou un nombre. UN SEUL mot en trop = autre série de la
            //   franchise. Sans ça, « Star Trek The Next Generation S01E21 FRENCH » passait
            //   tous les autres tests : il contient « star trek », il a bien S01E21, il est
            //   bien français — et ce n'est pas l'épisode demandé.
            if (vocabulaire.isNotEmpty()) {
                val mots = norm(nettoyerTitre(c.titre)).split(" ").filter { it.isNotBlank() }
                val iMarqueur = mots.indexOfFirst { m ->
                    RE_MARQUEUR_POS.matches(m) ||
                        (!estEpisode && annee != null && m == annee.toString())
                }
                // 2026-08-25 : FILET — le controle de franchise ne doit jamais etre saute
                //   en silence. Pour un episode, le marqueur SxxExx a DEJA ete exige plus
                //   haut sur le titre brut : s'il a disparu des jetons nettoyes, c'est que
                //   le nettoyage a mange le nom de l'oeuvre. Dans ce cas on ne sait plus
                //   juger — et « on ne sait pas » doit valoir REFUS, pas acceptation.
                if (estEpisode && iMarqueur < 0) {
                    Log.d(TAG, "écarté « ${c.titre} » — nettoyage a effacé le nom " +
                        "(marqueur S${saison}E${episode} absent après nettoyage)")
                    return@filter false
                }
                if (iMarqueur > 0) {
                    val enTrop = mots.take(iMarqueur).filter { m ->
                        !m.all(Char::isDigit) && m !in MOTS_NEUTRES && m !in vocabulaire
                    }
                    if (enTrop.isNotEmpty()) {
                        Log.d(TAG, "écarté « ${c.titre} » — mot(s) en trop : ${enTrop.joinToString(", ")}")
                        return@filter false
                    }
                }
            }

            if (runtimeS != null && runtimeS > 0) {
                if (c.dureeS < runtimeS * 0.80 || c.dureeS > runtimeS * 1.25) return@filter false
            } else {
                // Garde-fou quand le runtime TMDB n'est pas fourni : un plancher suffit à
                //   écarter bandes-annonces, génériques et extraits, qui sont le gros du bruit.
                val plancher = if (estEpisode) 15 * 60 else 55 * 60
                if (c.dureeS in 1 until plancher) return@filter false
            }

            if (MARQUEUR_ETRANGER.containsMatchIn(n)) return@filter false

            MARQUEUR_FR.containsMatchIn(n) ||
                (!titreEpisodeFr.isNullOrBlank() && n.contains(norm(titreEpisodeFr)))
        }
    }

    /**
     * Point d'entrée backup.
     *
     * @param titres         titres connus de l'œuvre (TMDB + alternatifs), essayés dans l'ordre.
     * @param titreEpisodeFr titre FRANÇAIS de l'épisode (TMDB) — 2ᵉ preuve de version française
     *                       quand le nom du fichier ne porte aucun tag de release.
     * @param runtimeS       durée attendue en secondes (TMDB) — le discriminant le plus efficace.
     *
     * La requête est bâtie au FORMAT NOM DE RELEASE (`Titre.Avec.Points.S01E21`) : c'est celui
     * qui rend les résultats les plus précis sur ok.ru — mesuré 2 à 3 candidats, contre 9 400
     * pour une recherche en langage naturel.
     */
    suspend fun fetchOkRuBackupServers(
        titres: List<String>,
        saison: Int,
        episode: Int,
        annee: Int?,
        runtimeS: Int?,
        titreEpisodeFr: String? = null,
        motsInterdits: Set<String> = emptySet(),
    ): List<Video.Server> = withContext(Dispatchers.IO) {
        // On écarte les titres qui ne laissent RIEN après normalisation (titre japonais,
        //   coréen, cyrillique de TMDB) : `norm` ne garde que [a-z0-9], donc la requête
        //   partait vide et ok.ru répondait 0 brut. Deux allers-retours réseau gaspillés
        //   par œuvre — mesuré sur Star Trek (『宇宙大作戦／スタートレック』 → 0 brut).
        val propres = titres
            .mapNotNull { it.trim().takeIf(String::isNotBlank) }
            .filter { norm(it).isNotBlank() }
            .distinct().take(3)
        if (propres.isEmpty()) return@withContext emptyList()
        val estEpisode = saison > 0 && episode > 0
        val out = LinkedHashMap<String, Video.Server>()

        // Vocabulaire de l'œuvre : tous les mots de tous ses titres connus (TMDB + alternatifs).
        //   Sert au contrôle de franchise dans `retenir` — cf. son commentaire.
        val vocabulaire = propres.flatMap { norm(it).split(" ") }.filter { it.isNotBlank() }.toSet()

        for (titre in propres) {
            // DEUX formes de requête, obligatoirement les deux : le moteur d'ok.ru découpe le
            //   nom en jetons, et « S01E01 » et « S01 E01 » ne sont PAS le même jeton.
            //   Mesuré épisode par épisode sur Star Trek 1966 — aucune des deux n'englobe
            //   l'autre :
            //     S01E01 → compact 0 résultat FR, espacé 1 ([S01.E01] Star Trek - La Cage…)
            //     S03E12 → compact 2 résultats FR, espacé 1
            //   Ne garder que la forme compacte, c'était perdre l'épisode pilote ; ne garder
            //   que l'espacée, c'était en perdre d'autres. On lance les deux et on fusionne.
            val requetes = if (estEpisode) listOf(
                "${titre.replace(' ', '.')}.S%02dE%02d".format(saison, episode),
                "$titre S%02d E%02d".format(saison, episode),
            ) else if (annee != null && annee > 1900) listOf(
                "${titre.replace(' ', '.')}.$annee",
                "$titre $annee",
            ) else listOf(titre.replace(' ', '.'))

            val bruts = LinkedHashMap<String, Candidat>()
            for (r in requetes) for (c in rechercher(r)) bruts.putIfAbsent(c.id, c)
            val gardes = retenir(
                bruts.values.toList(), titre, saison, episode, annee, runtimeS,
                titreEpisodeFr, motsInterdits, vocabulaire,
            )
            val requete = requetes.joinToString(" + ")
            Log.i(TAG, "ok.ru '$requete' → ${bruts.size} bruts, ${gardes.size} retenus")
            for (c in gardes) {
                if (out.containsKey(c.id)) continue
                // `null` = VO sans sous-titres FR → on ne propose pas la source du tout.
                val langue = etiquetteLangue(c) ?: continue
                val def = if (c.hauteur > 0) " · ${c.hauteur}p" else ""
                out[c.id] = Video.Server(
                    id = SRC_PREFIX + c.id,
                    name = "ok.ru$def · $langue",
                    src = "$BASE/video/${c.id}",
                )
            }
            if (out.isNotEmpty()) break
        }
        out.values.toList()
    }

    private val RE_VOSTFR = Regex("\\b(vostfr|vost|subfr)\\b")
    private val RE_VF = Regex("\\b(french|truefrench|vff?|vfq|vfi|francais)\\b")

    /**
     * VF, VOSTFR… ou rien du tout ?
     *
     * Le tag du nom de release tranche dans la plupart des cas, sans aucune requête. Pour les
     * `multi`, qui ne veulent rien dire, on va lire le manifeste DASH. Mesuré sur
     * « [S01.E01] Star Trek - La Cage.multi.webdl.720 » : le lecteur du site ouvre sur des
     * sous-titres ANGLAIS et garde le français en second — c'est de la VO sous-titrée
     * annoncée comme du « multi ». L'étiqueter VF aurait été un mensonge.
     *
     * ⚠ ok.ru ne déclare JAMAIS la langue de l'audio : une seule `AdaptationSet contentType=
     *   "audio"`, sans attribut `lang`. On ne peut donc pas mesurer la version, seulement la
     *   déduire des sous-titres. D'où la règle, posée par le user (« on ne peut pas tomber
     *   sur du VO ; s'il n'y a pas de sous-titres, on n'en veut pas ») :
     *
     *   • tag FRENCH/TRUEFRENCH/VF…      → « VF »      (aucune requête)
     *   • tag VOSTFR                     → « VOSTFR »  (aucune requête)
     *   • piste de sous-titres FR        → « VOSTFR »
     *   • pas de piste FR, ou sondage impossible → `null` : LA SOURCE EST ÉCARTÉE.
     *
     *   Le dernier cas est le point important : plutôt que de risquer une étiquette « VF »
     *   posée à tort sur de la VO, on ne propose pas la source du tout. Mieux vaut un serveur
     *   de moins qu'un serveur qui ment.
     *
     * @return l'étiquette à afficher, ou `null` si la source doit être écartée.
     */
    private fun etiquetteLangue(c: Candidat): String? {
        val n = norm(c.titre)
        if (RE_VOSTFR.containsMatchIn(n)) return "VOSTFR"
        if (RE_VF.containsMatchIn(n)) return "VF"
        return try {
            val dash = metadonnees("$BASE/video/${c.id}").optString("ondemandDash", "")
            val pistes = if (dash.isBlank()) emptyList() else pistesSousTitres(dash)
            if (pistes.any { it.first.startsWith("fr", ignoreCase = true) }) {
                Log.i(TAG, "sondé « ${c.titre} » → VOSTFR (piste FR au manifeste)")
                "VOSTFR"
            } else {
                Log.i(
                    TAG,
                    "ÉCARTÉ « ${c.titre} » → VO sans sous-titres français " +
                        "(pistes: ${pistes.joinToString { it.first }.ifBlank { "aucune" }})",
                )
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "ÉCARTÉ ${c.id} : langue indéterminable (${e.message})")
            null
        }
    }

    /** Métadonnées du lecteur (`data-options` → `flashvars.metadata`) d'une page vidéo. */
    private fun metadonnees(page: String): JSONObject {
        val req = Request.Builder()
            .url(page)
            .header("User-Agent", UA)
            .header("Accept-Language", "fr-FR,fr;q=0.9")
            .build()
        val html = client.newCall(req).execute().use { it.body?.string() }
            ?: throw Exception("ok.ru : page vide")

        val brut = Regex("data-options=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            ?: throw Exception("ok.ru : data-options absent")
        val json = JSONObject(android.text.Html.fromHtml(brut, 0).toString())

        val flashvars = json.optJSONObject("flashvars")
            ?: throw Exception("ok.ru : flashvars absent")
        return when (val m = flashvars.opt("metadata")) {
            is JSONObject -> m
            is String -> JSONObject(m)
            else -> throw Exception("ok.ru : metadata absente")
        }
    }

    /**
     * Pistes de sous-titres, en (langue, URL du WebVTT).
     *
     * ⚠ ok.ru ne les expose QUE dans le manifeste DASH (`ondemandDash`). Ni le MP4 ni le HLS
     *   ne les portent — c'est pour ça qu'on ne voyait aucun sous-titre alors que le lecteur
     *   du site les affiche : il lit le DASH, nous le MP4. Mesuré sur « La Cage » (S01E01) :
     *   deux `AdaptationSet contentType="text"`, `en` et `fr`, un fichier .vtt complet chacun.
     */
    private fun pistesSousTitres(dashUrl: String): List<Pair<String, String>> = try {
        val req = Request.Builder().url(dashUrl)
            .header("User-Agent", UA)
            .header("Referer", "$BASE/")
            .build()
        val mpd = client.newCall(req).execute().use { it.body?.string() } ?: ""
        val base = dashUrl.substringBeforeLast('/') + "/"
        Regex("<AdaptationSet contentType=\"text\"[^>]*lang=\"([^\"]+)\"[\\s\\S]*?<BaseURL>([^<]+)</BaseURL>")
            .findAll(mpd)
            .map { m ->
                val u = m.groupValues[2].let { if (it.startsWith("http")) it else base + it }
                // `toSubtitleMimeType()` déduit le format de la FIN de l'URL, or ok.ru sert ses
                //   pistes sous un nom `blob_….src` : sans ce suffixe ExoPlayer croit à du
                //   SubRip et n'affiche rien. Le serveur ignore le paramètre (vérifié : 200 +
                //   même corps `WEBVTT` avec et sans).
                m.groupValues[1] to "$u?ext=vtt"
            }
            .toList()
    } catch (e: Exception) {
        Log.w(TAG, "manifeste DASH illisible : ${e.message}"); emptyList()
    }

    fun getVideo(server: Video.Server): Video {
        val id = server.id.removePrefix(SRC_PREFIX)
        val page = server.src.takeIf { it.startsWith("http") } ?: "$BASE/video/$id"
        val meta = metadonnees(page)

        // Ordre de qualité croissant tel que le sert ok.ru.
        val ordre = listOf("mobile", "lowest", "low", "sd", "hd", "full", "quad", "ultra")
        var meilleure: String? = null
        var rang = -1
        meta.optJSONArray("videos")?.let { arr ->
            for (i in 0 until arr.length()) {
                val v = arr.optJSONObject(i) ?: continue
                val url = v.optString("url", "").takeIf { it.isNotBlank() } ?: continue
                val r = ordre.indexOf(v.optString("name", "").lowercase())
                if (r >= rang) { rang = r; meilleure = url }
            }
        }

        val hls = meta.optString("hlsManifestUrl", "").takeIf { it.isNotBlank() }
        val choisi = meilleure
        val source = choisi ?: hls ?: throw Exception("ok.ru : aucun flux")

        // Sous-titres : pris dans le DASH et rattachés au flux MP4 (chargement latéral).
        //   Le FRANÇAIS est marqué `default` → ExoPlayer l'ACTIVE tout seul à l'ouverture.
        //   C'est voulu : ces sources-là sont en VO, le site lui-même met l'ANGLAIS par
        //   défaut, et sans ça le user devrait aller chercher le FR à la main à chaque fois.
        //   Sur les rips VF (les 240p `FRENCH`) il n'y a aucune piste : la liste reste vide
        //   et rien ne change pour eux.
        val pistes = meta.optString("ondemandDash", "").takeIf { it.isNotBlank() }
            ?.let { pistesSousTitres(it) }.orEmpty()
            .map { (langue, url) ->
                val fr = langue.startsWith("fr", ignoreCase = true)
                Video.Subtitle(
                    label = if (fr) "Français" else langue.uppercase(),
                    file = url,
                    default = fr,
                )
            }

        Log.d(
            TAG,
            "getVideo $id → ${if (choisi != null) ordre.getOrElse(rang) { "mp4" } else "hls"}" +
                ", sous-titres: ${pistes.joinToString { it.label }.ifBlank { "aucun" }}",
        )

        return Video(
            source = source,
            type = if (choisi != null) androidx.media3.common.MimeTypes.VIDEO_MP4
                   else androidx.media3.common.MimeTypes.APPLICATION_M3U8,
            subtitles = pistes,
            headers = mapOf(
                "User-Agent" to UA,
                "Referer" to "$BASE/",
            ),
        )
    }
}
