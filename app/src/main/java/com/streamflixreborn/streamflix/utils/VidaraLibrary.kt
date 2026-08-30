package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.TvShow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Ma bibliotheque — copie Vidara (serveur SUPPLEMENTAIRE a VOE).
 *
 * 2026-09-02 : jumeau de [VoeLibrary]. Memes fichiers (migres depuis VOE, noms
 * conserves), meme rattachement (identifiant TMDB en tete du nom, sinon titre +
 * n0 de suite + duree), mais servis depuis le compte Vidara. Sert de SERVEUR DE
 * SECOURS : quand la fiche d'un film est ouverte dans un autre provider, le
 * fichier deja migre sur Vidara apparait a cote de VOE.
 *
 * Source affichee : « ONYX · Vidara » (meme bibliotheque perso que « ONYX · VOE »,
 * autre hebergeur). L'extraction est faite par VidaraExtractor (vidara.so est
 * dans ses alias) : on ne fournit ici que l'URL d'embed https://vidara.so/e/<code>.
 *
 * API Vidara (DIFFERENTE de VOE) : https://api.vidara.so/v1 , parametre = api_key.
 *   /video/list   -> result.videos[] {filecode, title, length(MINUTES), ...}, 100/page
 *   /folder/list  -> result.folders[] {fld_id, name}  (a plat) — sert a reperer
 *                    les dossiers de clips musicaux, exclus du rattachement VOD.
 *
 * Cle : BuildConfig.VIDARA_API_KEY (local.properties). Vide = inactif, 0 reseau.
 */
object VidaraLibrary {

    private const val TAG = "VidaraLibrary"
    private const val API = "https://api.vidara.so/v1"
    private const val EMBED = "https://vidara.so"
    private const val TTL_MS = 10 * 60 * 1000L

    val actif: Boolean get() = BuildConfig.VIDARA_API_KEY.isNotBlank()

    data class Fichier(
        val code: String,
        val nomBrut: String,
        val dureeSec: Int,
        val estClip: Boolean,
        /** Chemin complet du dossier (separateur " / "), pour la navigation TV Hub. */
        val dossier: String = "",
        /** Affiche eventuelle (l'index publie n'en fournit pas -> null, comble par TMDB). */
        val poster: String? = null,
    ) {
        /** Nom sans extension : sert au rattachement (il garde l'identifiant TMDB). */
        val titre: String get() = nomBrut.substringBeforeLast('.', nomBrut).trim()
        /** Nom LISIBLE (sans l'identifiant TMDB de tete). Cf. VoeLibrary.titreAffiche. */
        val titreAffiche: String
            get() = titre.replace(RE_ID_TETE, "").trim().ifBlank { titre }
        val embed: String get() = "$EMBED/e/$code"
    }

    /** « ONYX · Vidara » apres re-etiquetage par BackupRegistry.wrap(). */
    fun serveurDe(code: String): Video.Server =
        Video.Server(
            id = "livehub::vidara::$code",
            name = "Vidara",
            src = "$EMBED/e/$code",
        )

    // ─────────────────────────────────────────────────────── appel API Vidara
    //  Meme precaution que VoeLibrary : passer par NetworkClient.default
    //  (DNS-over-HTTPS) — sur certains telephones le resolveur systeme ne
    //  connait pas api.vidara.so (DNS filtre). Cadence + reessai anti-429.
    private val verrouCadence = Any()
    private const val ESPACEMENT_MS = 300L
    @Volatile private var dernierAppel = 0L

    private fun get(chemin: String, params: Map<String, String> = emptyMap()): JSONObject? {
        val qs = (params + ("api_key" to BuildConfig.VIDARA_API_KEY))
            .entries.joinToString("&") { (k, v) ->
                "$k=" + java.net.URLEncoder.encode(v, "UTF-8")
            }
        synchronized(verrouCadence) {
            val ecart = System.currentTimeMillis() - dernierAppel
            if (ecart < ESPACEMENT_MS) {
                try { Thread.sleep(ESPACEMENT_MS - ecart) } catch (_: InterruptedException) {}
            }
            dernierAppel = System.currentTimeMillis()
        }
        var attente = 500L
        repeat(4) { essai ->
            try {
                val req = okhttp3.Request.Builder()
                    .url("$API$chemin?$qs")
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Accept", "application/json")
                    .build()
                NetworkClient.default.newCall(req).execute().use { r ->
                    val corps = r.body?.string().orEmpty()
                    if (r.code == 429) {
                        Log.d(TAG, "$chemin : 429, reessai dans $attente ms")
                    } else {
                        val j = runCatching { JSONObject(corps) }.getOrNull()
                        if (j != null && j.optInt("status") == 200) return j
                        Log.w(TAG, "$chemin : reponse inexploitable (http ${r.code})")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "$chemin KO (essai ${essai + 1}) : ${e.message}")
            }
            if (essai < 3) {
                try { Thread.sleep(attente) } catch (_: InterruptedException) {}
                attente *= 2
            }
        }
        return null
    }

    // ─────────────────────────────────────────────────── liste rapide + cache
    @Volatile private var cacheRapide: List<Fichier> = emptyList()
    @Volatile private var cacheRapideTs: Long = 0L
    private val verrou = kotlinx.coroutines.sync.Mutex()

    /** Dossier de clips musicaux ? (nom « clip » ou « musique », accents/casse
     *  neutralises). Ces fichiers portent des titres courts et communs qui
     *  faussent le rattachement VOD — on les exclut, exactement comme VoeLibrary. */
    private fun estDossierClip(nom: String): Boolean {
        val d = java.text.Normalizer.normalize(nom, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "").lowercase()
        return d.contains("clip") || d.contains("musique")
    }

    private fun codeDe(o: JSONObject): String =
        listOf("filecode", "file_code", "code").firstNotNullOfOrNull {
            o.optString(it).takeIf { s -> s.isNotBlank() }
        } ?: ""

    private suspend fun toutRapide(): List<Fichier> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (cacheRapide.isNotEmpty() && now - cacheRapideTs < TTL_MS) return@withContext cacheRapide
        verrou.lock()
        try {
            if (cacheRapide.isNotEmpty() &&
                System.currentTimeMillis() - cacheRapideTs < TTL_MS) return@withContext cacheRapide

            // 1. codes des clips : dossiers dont le nom porte « clip »/« musique »
            //    (le gros « Musique rock anime » compris). Vidara ne rend pas le
            //    chemin par video, on releve donc les codes dossier par dossier.
            val clips = HashSet<String>()
            val fdl = get("/folder/list")?.optJSONObject("result")?.optJSONArray("folders")
            val clipFolders = mutableListOf<String>()
            for (i in 0 until (fdl?.length() ?: 0)) {
                val o = fdl?.optJSONObject(i) ?: continue
                if (estDossierClip(o.optString("name"))) {
                    clipFolders += o.optString("fld_id").ifBlank { o.optString("code") }
                }
            }
            for (fid in clipFolders) {
                if (fid.isBlank()) continue
                var page = 1
                while (page <= 40) {
                    val res = get("/video/list", mapOf(
                        "fld_id" to fid, "page" to "$page", "per_page" to "100",
                    ))?.optJSONObject("result") ?: break
                    val arr = res.optJSONArray("videos") ?: break
                    if (arr.length() == 0) break
                    for (i in 0 until arr.length()) {
                        val c = codeDe(arr.optJSONObject(i) ?: continue)
                        if (c.isNotBlank()) clips += c
                    }
                    if (page >= res.optInt("total_pages", page)) break
                    page++
                }
            }

            // 2. toutes les videos, 100 par page.
            val out = mutableListOf<Fichier>()
            var page = 1
            while (page <= 200) {
                val res = get("/video/list", mapOf("page" to "$page", "per_page" to "100"))
                    ?.optJSONObject("result") ?: break
                val arr = res.optJSONArray("videos") ?: break
                if (arr.length() == 0) break
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val code = codeDe(o)
                    if (code.isBlank()) continue
                    val nom = o.optString("title").ifBlank { o.optString("name") }
                    if (nom.isBlank()) continue
                    out += Fichier(
                        code = code,
                        nomBrut = nom,
                        // Vidara renvoie la duree en MINUTES (verifie : 51 pour un
                        //   episode de 51 min, 4 pour un clip de 4 min).
                        dureeSec = o.optInt("length", 0) * 60,
                        estClip = code in clips,
                    )
                }
                if (page >= res.optInt("total_pages", page)) break
                page++
            }
            // Un echec (liste vide) ne doit JAMAIS ecraser un cache garni.
            if (out.isEmpty()) return@withContext cacheRapide
            cacheRapide = out
            cacheRapideTs = now
            Log.d(TAG, "Vidara : ${out.size} video(s), dont ${clips.size} clip(s)")
            out
        } finally {
            verrou.unlock()
        }
    }

    // ─────────────────────────── rattachement (logique identique a VoeLibrary)
    private val RE_ID_TETE = Regex("""^(\d{2,8})\s*-\s*""")
    private val ROMAINS = mapOf(
        "ii" to 2, "iii" to 3, "iv" to 4, "v" to 5, "vi" to 6,
        "vii" to 7, "viii" to 8, "ix" to 9, "x" to 10,
    )

    /** Numero de suite porte par un titre (absent = 1). Cf. VoeLibrary. */
    private fun numeroDeSuite(titre: String): Int {
        val base = titre.substringBeforeLast('.', titre)
        for (mot in base.split(Regex("""[\s:\-–_/\\\[\]()]+"""))) {
            if (mot.isBlank()) continue
            val m = mot.lowercase()
            if (Regex("""^(19|20)\d{2}$""").matches(m)) continue
            if (Regex("""^s\d{1,2}e\d{1,3}$""").matches(m)) continue
            val n = m.toIntOrNull() ?: ROMAINS[m] ?: continue
            if (n in 2..30) return n
        }
        return 1
    }

    /** Le fichier partage-t-il le MOT DE TETE du titre demande ? Cf. VoeLibrary. */
    private fun motTeteCouvert(candidat: String, titresConnus: Collection<String>): Boolean {
        val cw = BackupRegistry.sigWords(candidat)
        if (cw.isEmpty()) return true
        fun proche(a: String, b: String): Boolean {
            if (a == b) return true
            val court = if (a.length <= b.length) a else b
            val long = if (a.length <= b.length) b else a
            return court.length >= 4 && long.startsWith(court) && long.length - court.length <= 2
        }
        return titresConnus.any { t ->
            val qw = BackupRegistry.sigWords(t)
            val tete = qw.firstOrNull() ?: return@any false
            cw.any { proche(it, tete) }
        }
    }

    /** Duree du fichier compatible avec celle annoncee par TMDB ? Cf. VoeLibrary. */
    private fun dureeCompatible(fichierSec: Int, basSec: Int?, hautSec: Int?): Boolean {
        if (fichierSec <= 0) return true
        val bas = basSec ?: return true
        val haut = hautSec ?: bas
        val marge = maxOf(600, (haut * 0.25).toInt())
        return fichierSec >= bas - marge && fichierSec <= haut + marge
    }

    /**
     * Fichier(s) Vidara correspondant a la fiche ouverte, en serveurs de secours.
     * Signature IDENTIQUE a VoeLibrary.serveursPour (appelee de la meme facon par
     * BackupRegistry) : identifiant TMDB (films), sinon SxxExx (series), sinon
     * titre + n0 de suite + duree. Clips musicaux exclus.
     */
    suspend fun serveursPour(
        tmdbId: String?,
        titresConnus: Collection<String>,
        annee: Int?,
        estUnFilm: Boolean,
        titrePrincipal: String? = null,
        dureeMinSec: Int? = null,
        dureeMaxSec: Int? = null,
        saison: Int = 0,
        episode: Int = 0,
    ): List<Video.Server> {
        if (!actif) return emptyList()
        val fichiers = try {
            toutRapide().filterNot { it.estClip }
        } catch (e: Exception) {
            Log.w(TAG, "lecture Vidara KO : ${e.message}")
            return emptyList()
        }
        if (fichiers.isEmpty()) return emptyList()

        // 1. identifiant TMDB en tete du nom — FILMS uniquement (cf. VoeLibrary :
        //    TMDB numerote films et series separement, les identifiants ecrits
        //    dans les noms sont tous des identifiants de FILM).
        val exacts = tmdbId?.takeIf { it.isNotBlank() && estUnFilm }?.let { id ->
            fichiers.filter { RE_ID_TETE.find(it.titre)?.groupValues?.get(1) == id }
        }.orEmpty()
        if (exacts.isNotEmpty()) {
            Log.d(TAG, "Vidara : rattachement TMDB $tmdbId -> ${exacts.size}")
            return exacts.map { serveurDe(it.code) }
        }

        // 2. episode de serie : SxxExx + mot de tete de la serie.
        if (!estUnFilm && saison > 0 && episode > 0) {
            val marque = Regex("(?i)s0*${saison}[ ._-]?e0*${episode}(?!\\d)")
            val episodes = fichiers.filter { f ->
                val propre = RE_ID_TETE.replace(f.titre, "")
                marque.containsMatchIn(propre) && motTeteCouvert(propre, titresConnus)
            }
            if (episodes.isNotEmpty()) {
                Log.d(TAG, "Vidara : rattachement S${saison}E${episode} -> ${episodes.size}")
            }
            return episodes.map { serveurDe(it.code) }
        }

        // 3. titre + garde-fous (mot de tete, n0 de suite, duree).
        if (titresConnus.isEmpty()) return emptyList()
        val parTitre = fichiers.filter { f ->
            val t = RE_ID_TETE.replace(f.titre, "")
            BackupRegistry.workMatches(t, titresConnus, annee, estUnFilm)
        }
        val attendu = numeroDeSuite(titrePrincipal ?: titresConnus.first())
        val retenus = parTitre.filter { f ->
            val propre = RE_ID_TETE.replace(f.titre, "")
            motTeteCouvert(propre, titresConnus) &&
                numeroDeSuite(propre) == attendu &&
                dureeCompatible(f.dureeSec, dureeMinSec, dureeMaxSec)
        }
        if (retenus.isNotEmpty()) Log.d(TAG, "Vidara : rattachement titre -> ${retenus.size}")
        return retenus.map { serveurDe(it.code) }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  TV HUB — dossier « Film / série » depuis l'INDEX PUBLIÉ (nx-data)
    // ═══════════════════════════════════════════════════════════════════════
    //  2026-09-04 (décision user « Index publié ») : la tuile « Film / série »
    //  du TV Hub lit l'index PUBLIC data/vidara.json (même dépôt que voe.json),
    //  et NON l'API Vidara — donc AUCUNE clé dans l'APK partagé. L'index porte
    //  le chemin complet du dossier pour chaque fichier ; on reconstruit
    //  l'arborescence en mémoire et on navigue niveau par niveau, exactement
    //  comme VoeLibrary le faisait pour VOE. La lecture reste publique
    //  (https://vidara.so/e/<code>, extraite par VidaraExtractor).
    //
    //  INDÉPENDANT de la partie API ci-dessus (serveur de secours BackupRegistry,
    //  gouvernée par VIDARA_API_KEY). Caches distincts. Ici : zéro clé, un GET.

    private const val INDEX_URL =
        "https://raw.githubusercontent.com/xdata-mix/nx-data/main/data/vidara.json"
    /** L'index est régénéré une fois par jour : inutile de le retélécharger souvent. */
    private const val TTL_NAV_MS = 6 * 60 * 60 * 1000L
    /** Séparateur de chemin, aligné sur VoeLibrary pour que enfantsDe marche pareil. */
    private const val SEP = " / "

    /** Index public = URL sans authentification : la tuile est TOUJOURS proposée,
     *  son contenu se charge au clic (comme les dossiers Replay). */
    val disponible: Boolean get() = true

    @Volatile private var cacheNav: List<Fichier> = emptyList()
    @Volatile private var cacheNavTs: Long = 0L
    private val verrouNav = kotlinx.coroutines.sync.Mutex()

    /** Cache déjà chargé, lisible sans coroutine (le dialogue est synchrone). */
    fun cacheActuel(): List<Fichier> = cacheNav

    /** Normalise « Films/Action » vers le séparateur interne « Films / Action ». */
    private fun normaliseChemin(brut: String): String =
        brut.split('/').map { it.trim() }.filter { it.isNotBlank() }.joinToString(SEP)

    /** Télécharge l'index public (ou rend le cache s'il est encore frais). */
    suspend fun toutNav(forcer: Boolean = false): List<Fichier> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forcer && cacheNav.isNotEmpty() && now - cacheNavTs < TTL_NAV_MS) return@withContext cacheNav
        verrouNav.withLock {
            val t = System.currentTimeMillis()
            if (!forcer && cacheNav.isNotEmpty() && t - cacheNavTs < TTL_NAV_MS) return@withLock cacheNav
            val corps = try {
                // Contournement du cache CDN (cf. VoeCommunaute) : le paramètre change
                //   toutes les heures — assez pour percer le cache raw.githubusercontent,
                //   pas assez pour re-télécharger le fichier à chaque ouverture.
                val antiCache = System.currentTimeMillis() / 3_600_000L
                val req = okhttp3.Request.Builder()
                    .url("$INDEX_URL?h=$antiCache")
                    .header("Cache-Control", "no-cache")
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Accept", "application/json")
                    .build()
                NetworkClient.default.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) { Log.w(TAG, "index inaccessible (http ${r.code})"); return@withLock cacheNav }
                    r.body?.string().orEmpty()
                }
            } catch (e: Exception) { Log.w(TAG, "index KO : ${e.message}"); return@withLock cacheNav }

            val doc = runCatching { JSONObject(corps) }.getOrNull()
                ?: run { Log.w(TAG, "index illisible"); return@withLock cacheNav }
            val tableau = doc.optJSONArray("items")
                ?: run { Log.w(TAG, "index sans champ items"); return@withLock cacheNav }

            val liste = ArrayList<Fichier>(tableau.length())
            for (i in 0 until tableau.length()) {
                val o = tableau.optJSONObject(i) ?: continue
                val code = o.optString("code").trim()
                if (code.isEmpty()) continue
                val nom = o.optString("titre").ifBlank { o.optString("nom") }
                if (nom.isBlank()) continue
                val chemin = normaliseChemin(o.optString("dossier").trim())
                val estClipDossier = java.text.Normalizer
                    .normalize(chemin, java.text.Normalizer.Form.NFD)
                    .replace(Regex("\\p{Mn}+"), "").lowercase()
                    .let { it.contains("clip") || it.contains("musique") }
                liste += Fichier(
                    code = code,
                    nomBrut = nom,
                    // L'index publie la durée en SECONDES (vérifié : 7500 = 125 min).
                    dureeSec = o.optInt("duree", 0),
                    estClip = estClipDossier,
                    dossier = chemin.ifBlank { "Sans dossier" },
                )
            }
            // Un échec (liste vide) ne doit JAMAIS écraser un cache garni.
            if (liste.isEmpty() && cacheNav.isNotEmpty()) {
                Log.w(TAG, "index vide — on garde les ${cacheNav.size} entrées précédentes")
                return@withLock cacheNav
            }
            cacheNav = liste
            cacheNavTs = System.currentTimeMillis()
            Log.i(TAG, "index Vidara chargé : ${liste.size} fichiers, " +
                "${liste.map { it.dossier }.distinct().size} dossier(s)")
            // 2026-09-05 (user « ça charge tout d'un coup ») : AVANT, on attendait ici
            //   completerFiches(liste) sur TOUTE la bibliothèque (~600 fiches TMDB,
            //   20-30 s) et le dialogue restait bloqué derrière le verrou. Comme
            //   VoeLibrary : on rend la liste TOUT DE SUITE, chaque niveau complète
            //   ses propres fiches à l'ouverture (cf. niveau()), et le reste se
            //   télécharge en fond, sans bloquer personne.
            completerEnFond(liste)
            liste
        }
    }

    // ───────────────────────────────────────────────────────────── navigation
    /**
     * Contenu IMMÉDIAT d'un niveau : sous-dossiers directs (avec compteur) et
     * fichiers posés à ce niveau. `chemin` vide = racine. Zéro réseau, lit le
     * cache déjà chargé. Cf. VoeLibrary.enfantsDe.
     */
    fun enfantsDe(chemin: String): Pair<List<Pair<String, Int>>, List<Fichier>> {
        val tous = cacheNav
        val prefixe = if (chemin.isBlank()) "" else chemin + SEP
        val fichiersIci = tous.filter {
            it.dossier == chemin || (chemin.isBlank() && it.dossier == "Sans dossier")
        }
        val sousDossiers = LinkedHashMap<String, Int>()
        for (f in tous) {
            val d = f.dossier
            if (d == chemin || d == "Sans dossier") continue
            if (!d.startsWith(prefixe)) continue
            val reste = d.removePrefix(prefixe)
            val enfant = reste.substringBefore(SEP)
            if (enfant.isBlank()) continue
            val cheminEnfant = if (chemin.isBlank()) enfant else chemin + SEP + enfant
            sousDossiers[cheminEnfant] = (sousDossiers[cheminEnfant] ?: 0) + 1
        }
        return sousDossiers.entries.sortedBy { it.key.lowercase() }.map { it.key to it.value } to
            fichiersIci.sortedBy { titrePour(it).lowercase() }
    }

    /** Un niveau, même forme que VoeLibrary.Niveau (pour LiveHubFolderDialog). */
    data class Niveau(
        val sousDossiers: List<Pair<String, String>>,
        val fichiers: List<Fichier>,
    )

    /** Pas de fld_id ici (index publié) : on charge tout l'index puis on lit le
     *  niveau demandé en mémoire. Une seule requête (le fichier), quel que soit
     *  le dossier ouvert. */
    suspend fun niveau(chemin: String): Niveau {
        toutNav()
        val (sous, fics) = enfantsDe(chemin)
        // Comme VoeLibrary.niveau : seules les fiches TMDB DU NIVEAU OUVERT sont
        //   attendues (quelques requêtes, mises en cache disque ensuite). Le reste
        //   de la bibliothèque se complète en fond.
        runCatching { completerFiches(fics) }
        return Niveau(sous.map { it.first to "" }, fics.sortedBy { titrePour(it).lowercase() })
    }

    private val completionFondEnCours = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Fiches TMDB de toute la bibliothèque, EN FOND, une seule à la fois,
     *  écriture disque au fil de l'eau (cf. completerFiches). */
    private fun completerEnFond(fichiers: List<Fichier>) {
        if (BuildConfig.TMDB_API_KEY.isBlank()) return
        if (!completionFondEnCours.compareAndSet(false, true)) return
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try { completerFiches(fichiers) }
            catch (e: Exception) { Log.w(TAG, "fiches en fond KO : ${e.message}") }
            finally { completionFondEnCours.set(false) }
        }
    }

    /** L'index publié n'a pas d'identifiant de dossier : rien à exposer. */
    fun idDe(chemin: String): String? = null

    /** Tuile d'un sous-dossier : rouvre le dialogue un cran plus bas. */
    fun tuileDossier(chemin: String, nb: Int): TvShow =
        TvShow(
            id = "livehub::folder::vidaradir_$chemin",
            title = if (nb >= 0) "📁 ${chemin.substringAfterLast(SEP)} ($nb)"
                    else "📁 ${chemin.substringAfterLast(SEP)}",
        ).apply { providerName = "TV Hub" }

    /** Tuile d'un film : titre officiel TMDB + affiche si connus, sinon nom nettoyé. */
    fun tuileFilm(f: Fichier): TvShow =
        TvShow(id = "livehub::vidara::${f.code}", title = titrePour(f))
            .copy(poster = posterPour(f), banner = posterPour(f))
            .apply { providerName = "TV Hub" }

    /** Sections « ONYX - <dossier> » (le préfixe est capté par la FolderDef
     *  "ma_bibliotheque" côté LiveTvHubProvider). Version réseau. */
    suspend fun sections(): List<Category> {
        val fichiers = toutNav()
        if (fichiers.isEmpty()) return emptyList()
        return sectionsAPlat(fichiers)
    }

    /** Sections construites UNIQUEMENT depuis la mémoire (zéro réseau) : accueil
     *  instantané, la tuile est de toute façon rendue par alwaysShowKeys. */
    fun sectionsSiDejaCharge(): List<Category> {
        val fichiers = cacheNav
        if (fichiers.isEmpty()) return emptyList()
        return sectionsAPlat(fichiers)
    }

    private fun sectionsAPlat(fichiers: List<Fichier>): List<Category> =
        fichiers.groupBy { it.dossier }
            .toSortedMap()
            .map { (dossier, liste) ->
                Category(
                    // ⚠ PREFIXE SANS ACCENT, comme VoeLibrary : il DOIT être identique
                    //   au motif de la FolderDef ("^ONYX - .*$"). Le libellé affiché,
                    //   lui, garde ses accents.
                    name = "ONYX - $dossier",
                    list = liste.sortedBy { titrePour(it).lowercase() }.map { tuileFilm(it) },
                )
            }

    private val prechauffageNavEnCours = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Charge l'index EN FOND après le démarrage de l'accueil : au clic sur
     *  « Film / série » le cache est déjà chaud → ouverture immédiate. */
    fun prechauffer() {
        if (cacheNav.isNotEmpty() && System.currentTimeMillis() - cacheNavTs < TTL_NAV_MS) return
        if (!prechauffageNavEnCours.compareAndSet(false, true)) return
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try { Log.d(TAG, "préchauffage Vidara : ${toutNav().size} fichier(s) prêts") }
            catch (e: Exception) { Log.w(TAG, "préchauffage KO : ${e.message}") }
            finally { prechauffageNavEnCours.set(false) }
        }
    }

    /** Fichier de l'index par code (résolution getTvShow / getServers). */
    fun fichierNavDe(code: String): Fichier? = cacheNav.firstOrNull { it.code == code }

    // ─────────────────────────────────── jaquettes et titres TMDB (cf. VoeLibrary)
    data class Fiche(val titre: String, val poster: String?)

    private const val CLE_FICHES = "vidara_fiches_tmdb"
    private val fiches = java.util.concurrent.ConcurrentHashMap<String, Fiche>()
    @Volatile private var fichesLues = false

    private fun lireFichesDisque() {
        if (fichesLues) return
        fichesLues = true
        val brut = UserPreferences.cacheBrut(CLE_FICHES) ?: return
        runCatching {
            val o = JSONObject(brut)
            for (k in o.keys()) {
                val e = o.getJSONObject(k)
                fiches[k] = Fiche(e.optString("t"), e.optString("p").takeIf { it.isNotBlank() })
            }
        }
    }

    @Synchronized private fun ecrireFichesDisque() {
        runCatching {
            val o = JSONObject()
            for ((k, v) in fiches) o.put(k, JSONObject().put("t", v.titre).put("p", v.poster ?: ""))
            UserPreferences.ecrireCacheBrut(CLE_FICHES, o.toString())
        }
    }

    private fun idTmdbDe(f: Fichier): String? = RE_ID_TETE.find(f.titre)?.groupValues?.get(1)

    /** Fiche TMDB d'un fichier, si son nom porte un identifiant déjà téléchargé. */
    fun ficheDe(f: Fichier): Fiche? = idTmdbDe(f)?.let { fiches[it] }

    /** Ce qu'il faut AFFICHER : titre officiel si connu, sinon nom de fichier nettoyé. */
    fun titrePour(f: Fichier): String = ficheDe(f)?.titre?.takeIf { it.isNotBlank() } ?: f.titreAffiche

    /** Ce qu'il faut AFFICHER : affiche TMDB si connue, sinon rien (index sans vignette). */
    fun posterPour(f: Fichier): String? = ficheDe(f)?.poster ?: f.poster

    private suspend fun completerFiches(fichiers: List<Fichier>) {
        if (BuildConfig.TMDB_API_KEY.isBlank()) return
        lireFichesDisque()
        // containsKey explicite (KT-18053 : `in` appelle containsValue sur ConcurrentHashMap).
        val manquants = fichiers.mapNotNull { idTmdbDe(it) }.distinct().filter { !fiches.containsKey(it) }
        if (manquants.isEmpty()) return
        val jetons = kotlinx.coroutines.sync.Semaphore(8)
        // 2026-09-05 : écriture disque tous les 20 résultats, pas seulement à la
        //   fin — si l'application est quittée en cours de route, l'acquis reste.
        val depuisEcriture = java.util.concurrent.atomic.AtomicInteger(0)
        kotlinx.coroutines.coroutineScope {
            manquants.forEach { id ->
                launch(Dispatchers.IO) {
                    jetons.withPermit {
                        // Une autre passe (niveau ouvert / fond) l'a peut-être déjà prise.
                        if (fiches.containsKey(id)) return@withPermit
                        runCatching {
                            val req = okhttp3.Request.Builder()
                                .url("https://api.themoviedb.org/3/movie/$id?api_key=${BuildConfig.TMDB_API_KEY}&language=fr-FR")
                                .header("Accept", "application/json").build()
                            NetworkClient.default.newCall(req).execute().use { r ->
                                val j = JSONObject(r.body?.string().orEmpty())
                                val t = j.optString("title").ifBlank { j.optString("original_title") }
                                val p = j.optString("poster_path").takeIf { it.isNotBlank() }
                                if (t.isNotBlank()) {
                                    fiches[id] = Fiche(t, p?.let { "https://image.tmdb.org/t/p/w500$it" })
                                    if (depuisEcriture.incrementAndGet() >= 20) {
                                        depuisEcriture.set(0)
                                        ecrireFichesDisque()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        ecrireFichesDisque()
    }

}

