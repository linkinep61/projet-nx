package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Ma bibliothèque — les fichiers hébergés sur le compte VOE personnel.
 *
 * Deux usages, une seule source :
 *   • LiveTvHubProvider affiche un dossier navigable (« Ma bibliothèque ») ;
 *   • BackupRegistry s'en sert pour proposer le fichier comme serveur
 *     supplémentaire quand la fiche du film est ouverte dans un autre provider.
 *
 * Clé API : BuildConfig.VOE_API_KEY, alimentée par local.properties.
 * Vide = fonctionnalité inactive, AUCUN appel réseau.
 *
 * 2026-08-17 : le FTP annoncé par VOE (ftp.voe-network.net) ne résout nulle
 *   part — l'envoi se fait par /api/upload/server. Sans rapport avec la
 *   lecture, mais évite de rechercher un FTP qui n'existe pas.
 */
object VoeLibrary {

    private const val TAG = "VoeLibrary"
    private const val BASE = "https://voe.sx"
    private const val TTL_MS = 10 * 60 * 1000L

    val actif: Boolean get() = BuildConfig.VOE_API_KEY.isNotBlank()

    data class Fichier(
        val code: String,
        /** Nom brut tel que VOE l'a enregistré, extension comprise. */
        val nomBrut: String,
        val dossier: String,
        val poster: String?,
        val dureeSec: Int,
    ) {
        /** Nom sans extension. Sert au RATTACHEMENT (il garde l'identifiant TMDB). */
        val titre: String get() = nomBrut.substringBeforeLast('.', nomBrut).trim()

        /**
         * Nom LISIBLE, pour l'écran uniquement.
         *
         * ⚠ 2026-08-17 (user « l'identifiant TMDB empêche la lecture du nom
         *   complet du film dans ce dossier ») : depuis le renommage des 516
         *   fichiers, les tuiles s'appelaient « 237584 - Mojave ». Le numéro
         *   est un outil interne de rapprochement, il ne dit rien à personne
         *   et il vole la place du titre, qui se fait tronquer.
         *   On le retire donc À L'AFFICHAGE, et seulement là : `titre` le
         *   conserve, sinon le rapprochement par identifiant ne trouverait
         *   plus rien.
         */
        val titreAffiche: String
            get() = titre.replace(Regex("""^\d{2,8}\s*-\s*"""), "").trim().ifBlank { titre }

        /** URL d'embed canonique — VoeExtractor a « https://voe.sx » dans ses alias. */
        val embed: String get() = "$BASE/e/$code"
    }

    @Volatile private var cache: List<Fichier> = emptyList()
    @Volatile private var cacheTs: Long = 0L

    /** Une seule lecture à la fois : deux appels concurrents doublaient le
     *  nombre de requêtes et déclenchaient la limitation de débit de VOE. */
    private val verrou = kotlinx.coroutines.sync.Mutex()

    /** Cadence : au moins ESPACEMENT_MS entre deux appels VOE, toutes origines
     *  confondues (navigation, préchauffage, recherche, rattachement). */
    private val verrouCadence = Any()
    private const val ESPACEMENT_MS = 300L
    @Volatile private var dernierAppel = 0L

    /**
     * ⚠ 2026-08-17 — NE PAS repasser à HttpURLConnection.
     * Première version faite comme ça : sur l'Oppo, les deux appels échouaient avec
     *   « Unable to resolve host "voe.sx": No address associated with hostname »
     * → 0 fichier, donc aucun dossier affiché. Le résolveur système du téléphone ne
     * connaît pas voe.sx (DNS filtré), exactement le piège déjà documenté dans
     * VoeExtractor. NetworkClient.default passe par DnsResolver.doh (DNS-over-HTTPS),
     * c'est ce qui fait marcher la lecture VOE — l'API doit emprunter le même chemin.
     */
    private fun get(chemin: String, params: Map<String, String> = emptyMap()): JSONObject? {
        val qs = (params + ("key" to BuildConfig.VOE_API_KEY))
            .entries.joinToString("&") { (k, v) ->
                "$k=" + java.net.URLEncoder.encode(v, "UTF-8")
            }
        // ⚠ 2026-08-17 — RÉESSAI OBLIGATOIRE, NE PAS LE RETIRER.
        //   VOE refuse les appels rapprochés : mesuré sur le compte, 7 appels
        //   d'affilée passent, LE 8e EST REFUSÉ (HTTP 429 « Too Many
        //   Attempts »). Or tout() fait UN APPEL PAR DOSSIER, puis deux pour la
        //   liste des fichiers : avec 8 dossiers ça fait 10 appels, donc le
        //   refus est certain. Sans réessai, un seul 429 faisait abandonner
        //   explorer(), sortir de la boucle file/list, et tout() repartait avec
        //   0 fichier — donc aucune section, donc plus de dossier « Film /
        //   série » au TV Hub. Autrement dit : plus l'utilisateur rangeait ses
        //   films, plus la bibliothèque devenait instable.
        //   Bonne nouvelle mesurée aussi : le blocage se lève IMMÉDIATEMENT
        //   (pas de punition qui dure), et avec 1,2 s d'écart 12 appels passent
        //   sans un seul refus. Une courte attente croissante suffit donc, et
        //   coûte moins qu'une pause systématique entre tous les appels.
        // ⚠ 2026-08-19 — CADENCE MINIMALE ENTRE DEUX APPELS, ajoutée après coup.
        //   Le commentaire ci-dessus dit qu'« avec 1,2 s d'écart, 12 appels
        //   passent sans un seul refus » — mais rien n'imposait cet écart. Or
        //   depuis aujourd'hui deux mécanismes appellent VOE en même temps : la
        //   navigation niveau par niveau (un appel par clic) et le préchauffage
        //   de fond (un appel par dossier, 87 dossiers). Résultat mesuré sur
        //   l'Oppo à 00:11, sans que rien ne tourne sur le PC :
        //       /api/folder/list : refus 429, nouvel essai dans 500 ms
        //       … quatorze fois d'affilée.
        //   L'application se refusait elle-même l'accès. On espace donc TOUS
        //   les appels, quelle que soit leur origine : mieux vaut 300 ms
        //   d'attente choisie qu'un 429 qui en coûte 3 500.
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
                    .url("$BASE$chemin?$qs")
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Accept", "application/json")
                    .build()
                NetworkClient.default.newCall(req).execute().use { r ->
                    val corps = r.body?.string().orEmpty()
                    if (r.code == 429) {
                        Log.d(TAG, "$chemin : refus 429, nouvel essai dans $attente ms")
                    } else {
                        val j = runCatching { JSONObject(corps) }.getOrNull()
                        if (j != null && j.optBoolean("success", false)) return j
                        Log.w(TAG, "$chemin : reponse inexploitable (http ${r.code})")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "$chemin KO (essai ${essai + 1}) : ${e.message}")
            }
            if (essai < 3) {
                try { Thread.sleep(attente) } catch (_: InterruptedException) {}
                attente *= 2                     // 0,5s -> 1s -> 2s
            }
        }
        return null
    }

    /** Liste complète du compte, dossier d'appartenance compris. Cache 10 min. */
    suspend fun tout(forcer: Boolean = false): List<Fichier> = withContext(Dispatchers.IO) {
        if (!actif) return@withContext emptyList()
        if (!forcer && cache.isNotEmpty() &&
            System.currentTimeMillis() - cacheTs < TTL_MS) return@withContext cache
        verrou.lock()
        try {
        val now = System.currentTimeMillis()
        // Re-test APRES le verrou : pendant l'attente, l'autre appel a peut-être
        //   déjà tout chargé — inutile de refaire les mêmes requêtes.
        if (!forcer && cache.isNotEmpty() && now - cacheTs < TTL_MS) return@withContext cache

        // 1+2. parcours RÉCURSIF de l'arborescence.
        //   ⚠ 2026-08-17 : la première version ne lisait que le premier niveau.
        //   Dès que l'utilisateur a rangé ses films en « Films / Action », tout
        //   devenait invisible. VOE imbrique bien les dossiers (le parent se
        //   désigne par son fld_code, pas par son fld_id) — il faut donc
        //   descendre. Le chemin complet sert de nom de catégorie.
        val parCode = HashMap<String, String>()          // code fichier → chemin dossier
        var nbDossiers = 0

        fun explorer(fid: String?, chemin: String, profondeur: Int) {
            if (profondeur > 4) return                   // garde-fou anti-boucle
            var page = 1
            while (page <= 40) {
                val p = HashMap<String, String>()
                if (fid != null) p["fld_id"] = fid
                p["page"] = "$page"
                val res = get("/api/folder/list", p)?.optJSONObject("result") ?: return
                if (page == 1) {
                    val sous = res.optJSONArray("folders")
                    for (i in 0 until (sous?.length() ?: 0)) {
                        val o = sous?.optJSONObject(i) ?: continue
                        val nom = o.optString("name").ifBlank { "?" }
                        nbDossiers++
                        // VOE tolère deux dossiers de même nom : on les fusionne
                        //   sous le même chemin, ce qui est le comportement attendu.
                        explorer(o.optString("fld_id"),
                                 if (chemin.isBlank()) nom else "$chemin / $nom",
                                 profondeur + 1)
                    }
                }
                val fic = res.optJSONObject("files") ?: return
                val data = fic.optJSONArray("data") ?: return
                for (i in 0 until data.length()) {
                    val o = data.optJSONObject(i) ?: continue
                    if (chemin.isNotBlank()) parCode[codeDe(o)] = chemin
                }
                if (fic.isNull("next_page_url")) break
                page++
            }
        }
        explorer(null, "", 0)

        // 3. tous les fichiers du compte (250 par page).
        val out = mutableListOf<Fichier>()
        var page = 1
        while (page <= 60) {
            val r = get("/api/file/list", mapOf("page" to "$page", "per_page" to "250"))
                ?.optJSONObject("result") ?: break
            val data = r.optJSONArray("data") ?: break
            for (i in 0 until data.length()) {
                val o = data.optJSONObject(i) ?: continue
                val code = codeDe(o)
                if (code.isBlank()) continue
                val nom = o.optString("title").ifBlank { o.optString("name") }
                if (nom.isBlank()) continue
                out += Fichier(
                    code = code,
                    nomBrut = nom,
                    dossier = parCode[code] ?: "Sans dossier",
                    poster = posterDe(o),
                    dureeSec = o.optInt("length", 0),
                )
            }
            if (r.isNull("next_page_url")) break
            page++
        }
        // ⚠ 2026-08-17 : NE JAMAIS remplacer un cache garni par une liste vide.
        //   Constaté sur l'Oppo avec 261 fichiers : deux lectures partaient en
        //   parallèle, la seconde se faisait limiter par VOE (429) et repartait
        //   avec 0 fichier — écrasant le résultat de la première. La
        //   bibliothèque disparaissait alors de l'écran jusqu'au rafraîchissement
        //   suivant. Un échec doit être sans effet, pas destructeur.
        if (out.isEmpty() && cache.isNotEmpty()) {
            Log.w(TAG, "lecture vide ignorée — on garde les ${cache.size} fichiers connus")
            return@withContext cache
        }
        cache = out
        cacheTs = now
        Log.d(TAG, "bibliothèque : ${out.size} fichier(s), $nbDossiers dossier(s)")
        // Titres officiels + affiches. Seules les fiches inconnues sont
        //   téléchargées, et le résultat est gardé sur le disque : ce coût
        //   n'est payé qu'une fois, pas à chaque lancement.
        runCatching { completerFiches(out) }
        out
        } finally {
            verrou.unlock()
        }
    }

    // ─────────────────────────────────────────── jaquettes et titres TMDB
    /**
     * 2026-08-17 (user « maintenant que t'as l'identifiant pour chaque film,
     *   on peut pas afficher directement la jaquette dessus avec le nom du
     *   film bien propre ? ») — oui, et c'est le vrai bénéfice du renommage.
     *
     * Jusqu'ici une tuile affichait le NOM DE FICHIER (« Hacksaw Ridge »,
     * « Fast and Furious 7 2015 HC ») et la vignette storyboard de VOE, qui
     * est une image quelconque prise au milieu du film. Avec l'identifiant on
     * a mieux : le titre officiel français et l'affiche.
     *
     * Le cache est PERSISTANT (SharedPreferences) : les ~550 fiches ne sont
     * téléchargées qu'une fois, jamais à chaque démarrage. Une fiche absente
     * n'est pas un problème — on retombe sur le nom de fichier nettoyé et la
     * vignette VOE.
     */
    data class Fiche(val titre: String, val poster: String?)

    private const val CLE_FICHES = "voe_fiches_tmdb"
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
        Log.d(TAG, "jaquettes déjà connues : ${fiches.size}")
    }

    private fun ecrireFichesDisque() {
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

    /** Ce qu'il faut AFFICHER : affiche TMDB si connue, sinon vignette VOE. */
    fun posterPour(f: Fichier): String? = ficheDe(f)?.poster ?: f.poster

    private suspend fun completerFiches(fichiers: List<Fichier>) {
        if (BuildConfig.TMDB_API_KEY.isBlank()) return
        lireFichesDisque()
        // containsKey explicite : sur ConcurrentHashMap, `in` appelle
        //   containsValue et le compilateur refuse l'ambiguïté (KT-18053).
        val manquants = fichiers.mapNotNull { idTmdbDe(it) }.distinct()
            .filter { !fiches.containsKey(it) }
        if (manquants.isEmpty()) return
        Log.d(TAG, "jaquettes à télécharger : ${manquants.size}")
        // 8 en parallèle : assez pour que 550 fiches passent en quelques
        //   secondes, assez peu pour ne pas se faire limiter par TMDB.
        val jetons = kotlinx.coroutines.sync.Semaphore(8)
        kotlinx.coroutines.coroutineScope {
            manquants.forEach { id ->
                launch(Dispatchers.IO) {
                    jetons.withPermit {
                        runCatching {
                            val req = okhttp3.Request.Builder()
                                .url("https://api.themoviedb.org/3/movie/$id" +
                                     "?api_key=${BuildConfig.TMDB_API_KEY}&language=fr-FR")
                                .header("Accept", "application/json")
                                .build()
                            NetworkClient.default.newCall(req).execute().use { r ->
                                val j = JSONObject(r.body?.string().orEmpty())
                                val t = j.optString("title").ifBlank { j.optString("original_title") }
                                val p = j.optString("poster_path").takeIf { it.isNotBlank() }
                                if (t.isNotBlank()) {
                                    fiches[id] = Fiche(t, p?.let { "https://image.tmdb.org/t/p/w500$it" })
                                }
                            }
                        }
                    }
                }
            }
        }
        ecrireFichesDisque()
        Log.d(TAG, "jaquettes connues : ${fiches.size}")
    }

    private fun codeDe(o: JSONObject): String =
        listOf("filecode", "file_code", "code").firstNotNullOfOrNull {
            o.optString(it).takeIf { s -> s.isNotBlank() }
        } ?: ""

    /** VOE renvoie 6 vignettes storyboard ; la L0 (100x100) est la plus sûre. */
    private fun posterDe(o: JSONObject): String? {
        val arr = o.optJSONArray("thumbnails") ?: return null
        for (i in 0 until arr.length()) {
            val u = arr.optJSONObject(i)?.optString("url").orEmpty()
            if (u.isNotBlank()) return u
        }
        return null
    }

    // ------------------------------------------------------------- navigation
    /** Cache deja charge, lisible sans coroutine (le dialogue est synchrone). */
    fun cacheActuel(): List<Fichier> = cache

    /** Separateur de chemin, aligne sur celui que construit `explorer()`. */
    private const val SEP = " / "

    /**
     * Contenu IMMEDIAT d'un niveau : les sous-dossiers directs et les fichiers
     * poses a ce niveau. `chemin` vide = racine.
     *
     * 2026-08-17 (user « ça va s'étaler sur des kilomètres si on met beaucoup
     *   de choses ») : avant, chaque dossier VOE devenait une rangee a plat
     *   nommee par son chemin complet — cinquante rangees cote a cote des que
     *   la bibliotheque grossit. On navigue maintenant niveau par niveau.
     */
    fun enfantsDe(chemin: String): Pair<List<Pair<String, Int>>, List<Fichier>> {
        val tous = cache
        val prefixe = if (chemin.isBlank()) "" else chemin + SEP
        val fichiersIci = tous.filter { it.dossier == chemin || (chemin.isBlank() && it.dossier == "Sans dossier") }
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
        return sousDossiers.entries.sortedBy { it.key.lowercase() }
            .map { it.key to it.value } to fichiersIci.sortedBy { it.titre.lowercase() }
    }

    /** Tuile d'un sous-dossier : rouvre le meme dialogue un cran plus bas.
     *  `nb` negatif = nombre inconnu (navigation niveau par niveau) : on
     *  n'affiche alors pas de compteur plutot que d'en inventer un. */
    fun tuileDossier(chemin: String, nb: Int): TvShow =
        TvShow(
            id = "livehub::folder::voedir_$chemin",
            title = if (nb >= 0) "📁 ${chemin.substringAfterLast(SEP)} ($nb)"
                    else "📁 ${chemin.substringAfterLast(SEP)}",
        ).apply { providerName = "TV Hub" }

    // ───────────────────────────────── navigation UN NIVEAU A LA FOIS
    /**
     * 2026-08-19 (user « le dossier films série met du temps à s'ouvrir quand
     *   même, tu pouvais pas faire un chargement progressif à l'intérieur ? »
     *   puis « au moins on a une ouverture rapide et le reste peu chargé en
     *   tâche de fond ») — il a raison, et c'est le vrai correctif.
     *
     * Jusqu'ici, ouvrir « Film / série » chargeait TOUTE la bibliothèque
     * (parcours complet de l'arborescence + 2000 fichiers) pour n'afficher que
     * TROIS cartes : Films, Série, Clip vidéo musique. Et ça empirait à chaque
     * dossier ajouté.
     *
     * Ici on ne demande QUE le dossier regardé : `/api/folder/list?fld_id=…`
     * renvoie ses sous-dossiers ET ses fichiers en une seule réponse. Une
     * requête (~0,6 s) par niveau, quel que soit le volume du compte.
     *
     * Le cache complet (`tout()`) reste utile pour la RECHERCHE, qui doit
     * trouver un film sans savoir où il est ; il se charge en fond via
     * `prechauffer()` et ne bloque plus personne.
     */
    data class Niveau(
        /** (chemin complet, fld_id) des sous-dossiers directs. */
        val sousDossiers: List<Pair<String, String>>,
        val fichiers: List<Fichier>,
    )

    /** chemin de dossier → fld_id, appris au fil de la descente. La racine
     *  n'a pas d'id : on part toujours d'elle, donc la carte est toujours
     *  garnie pour le chemin qu'on est en train de parcourir. */
    private val idsParChemin = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun idDe(chemin: String): String? = idsParChemin[chemin]

    suspend fun niveau(chemin: String): Niveau = withContext(Dispatchers.IO) {
        if (!actif) return@withContext Niveau(emptyList(), emptyList())
        val fid = if (chemin.isBlank()) null else idsParChemin[chemin]
        if (chemin.isNotBlank() && fid == null) {
            // On ne sait pas où c'est : plutôt que d'inventer, on retombe sur
            //   le cache complet s'il existe (cas d'un raccourci ou d'un
            //   dossier ouvert après un redémarrage).
            val (sous, fics) = enfantsDe(chemin)
            return@withContext Niveau(sous.map { it.first to "" }, fics)
        }
        val sousDossiers = mutableListOf<Pair<String, String>>()
        val fichiers = mutableListOf<Fichier>()
        var page = 1
        while (page <= 40) {
            val p = HashMap<String, String>()
            if (fid != null) p["fld_id"] = fid
            p["page"] = "$page"
            p["per_page"] = "250"
            val res = get("/api/folder/list", p)?.optJSONObject("result") ?: break
            if (page == 1) {
                val sous = res.optJSONArray("folders")
                for (i in 0 until (sous?.length() ?: 0)) {
                    val o = sous?.optJSONObject(i) ?: continue
                    val nom = o.optString("name")
                    if (nom.isBlank()) continue
                    val id = o.optString("fld_id")
                    val plein = if (chemin.isBlank()) nom else "$chemin$SEP$nom"
                    if (id.isNotBlank()) idsParChemin[plein] = id
                    sousDossiers += plein to id
                }
            }
            val fic = res.optJSONObject("files")
            val data = fic?.optJSONArray("data")
            for (i in 0 until (data?.length() ?: 0)) {
                val o = data?.optJSONObject(i) ?: continue
                val code = codeDe(o)
                if (code.isBlank()) continue
                val nom = o.optString("title").ifBlank { o.optString("name") }
                if (nom.isBlank()) continue
                fichiers += Fichier(
                    code = code,
                    nomBrut = nom,
                    dossier = chemin.ifBlank { "Sans dossier" },
                    poster = posterDe(o),
                    dureeSec = o.optInt("length", 0),
                )
            }
            if (fic == null || fic.isNull("next_page_url")) break
            page++
        }
        // Les fiches TMDB manquantes (titre propre + affiche) : sur un seul
        //   niveau c'est quelques requêtes, et le résultat est garde sur le
        //   disque, donc payé une seule fois dans la vie de l'application.
        runCatching { completerFiches(fichiers) }
        Niveau(sousDossiers.sortedBy { it.first.lowercase() },
               fichiers.sortedBy { titrePour(it).lowercase() })
    }

    /** Tuile d'un film. */
    fun tuileFilm(f: Fichier): TvShow =
        TvShow(id = "livehub::voe::${f.code}", title = titrePour(f))
            .copy(poster = posterPour(f), banner = posterPour(f))
            .apply { providerName = "TV Hub" }

    /** Sections pour le TV Hub : une par dossier VOE, nommées « Ma bibliothèque - X ».
     *  Le préfixe est ce que capte la FolderDef de LiveTvHubProvider. */
    suspend fun sections(): List<Category> {
        val fichiers = tout()
        if (fichiers.isEmpty()) return emptyList()
        // ⚠ 2026-08-17 — REVENU AU PLAT, et ne pas y retoucher sans avoir lu
        //   le gestionnaire de clic de LiveHubFolderDialog.
        //   Tentative de vraie arborescence : les sous-dossiers étaient émis
        //   comme tuiles « livehub::folder::voedir_<chemin> » à l'INTÉRIEUR du
        //   dialogue. Le dialogue les a traités comme des chaînes à lire et les
        //   a envoyées au lecteur → dossier « illisible » (constaté par l'user).
        //   Les tuiles-dossier ne sont reconnues qu'au niveau de l'ACCUEIL du
        //   TV Hub, pas à l'intérieur d'un dialogue déjà ouvert.
        //   Le code de navigation (enfantsDe / tuileDossier / branche voedir_
        //   du dialogue) est conservé, il faudra juste le brancher au bon
        //   endroit.
        return sectionsAPlat(fichiers)
    }

    /**
     * Sections construites UNIQUEMENT depuis ce qui est déjà en mémoire.
     * Aucune requête réseau, jamais : si le cache est vide, on rend une liste
     * vide et c'est le clic sur la carte qui déclenchera le chargement.
     *
     * 2026-08-19 (user « le chargement du TV hub je trouve super long depuis
     *   qu'on a ajouté notre dossier films série… le chargement du dossier
     *   devrait être effectué qu'au clic ») : mesuré sur son Oppo,
     *   `getHome: built+cached 5 sections in 30606ms`. Les 30 s venaient d'ici :
     *   `sections()` appelait `tout()`, qui parcourt TOUTE l'arborescence VOE
     *   (une requête par dossier ET par page), pagine tous les fichiers du
     *   compte, se prend des 429 avec attentes 0,5/1/2 s, puis fabrique une
     *   tuile pour les 1386 fichiers — alors que l'accueil n'affiche qu'UNE
     *   carte « 📁 Film / série », déjà rendue inconditionnellement par
     *   `alwaysShowKeys` (test de clé non vide, zéro réseau).
     *   Chaque dossier ajouté rendait l'accueil plus lent : d'où sa remarque.
     */
    fun sectionsSiDejaCharge(): List<Category> {
        if (!actif) return emptyList()
        val fichiers = cache
        if (fichiers.isEmpty()) return emptyList()
        return sectionsAPlat(fichiers)
    }

    /** Un seul préchauffage à la fois : sans ça, deux appels rapprochés
     *  doubleraient les requêtes et déclencheraient encore plus de 429. */
    private val prechauffageEnCours = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Charge la bibliothèque EN FOND, sans rien faire attendre.
     *
     * 2026-08-19 (user « au pire le dossier on peut le précharger une fois que
     *   le home a démarré, ça évite de cliquer dessus et ça ralentit pas
     *   l'ouverture ? … parce que je sens que ça va être chiant à ouvrir
     *   sinon ») : appelé par LiveTvHubProvider APRÈS la construction de
     *   l'accueil. Rend la main immédiatement ; quand l'utilisateur clique sur
     *   « Film / série », le cache est déjà chaud.
     *   Ne fait RIEN si la clé est vide, si le cache est encore frais, ou si un
     *   préchauffage tourne déjà.
     */
    fun prechauffer() {
        if (!actif) return
        if (cache.isNotEmpty() && System.currentTimeMillis() - cacheTs < TTL_MS) return
        if (!prechauffageEnCours.compareAndSet(false, true)) return
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val n = tout().size
                Log.d(TAG, "préchauffage terminé : $n fichier(s) prêts")
            } catch (e: Exception) {
                Log.w(TAG, "préchauffage KO : ${e.message}")
            } finally {
                prechauffageEnCours.set(false)
            }
        }
    }

    private fun sectionsAPlat(fichiers: List<Fichier>): List<Category> {
        return fichiers.groupBy { it.dossier }
            .toSortedMap()
            .map { (dossier, liste) ->
                Category(
                    // Le préfixe est ce que capte la FolderDef côté LiveTvHubProvider :
                    //   s'il change ici, il DOIT changer là-bas, sinon les sections
                    //   tombent dans le fourre-tout « Autres Replays ».
                    // ⚠ PREFIXE SANS ACCENT, VOLONTAIREMENT. Il doit être
                    //   identique au motif de la FolderDef côté LiveTvHubProvider.
                    //   Le 17/08 il contenait « série » : un outil en ligne de
                    //   commande a ré-encodé ce fichier, « é » est devenu « Ã© »,
                    //   le motif ne matchait plus et TOUTES les sections sont
                    //   ressorties en rangées libres sur l'accueil du TV Hub — le
                    //   dossier avait disparu. En ASCII, ça ne peut plus arriver.
                    //   Le libellé affiché, lui, reste « Film / série ».
                    name = "ONYX - $dossier",
                    // Titre officiel TMDB + affiche quand on les connaît,
                    //   sinon nom de fichier nettoyé + vignette VOE.
                    list = liste.sortedBy { titrePour(it).lowercase() }.map { tuileFilm(it) },
                )
            }
    }

    /** 2026-08-17 (user « l'appeler directement ONYX, ça nous permettrait de le
     *  différencier ») : dans une liste de serveurs, « Ma bibliothèque » ne dit
     *  rien. « ONYX » identifie tout de suite notre propre copie. */
    fun serveurDe(code: String, nom: String = "ONYX"): Video.Server =
        Video.Server(
            id = "livehub::voe::$code",
            name = nom,
            src = "$BASE/e/$code",
        )

    // ------------------------------------------------------- rattachement TMDB
    /**
     * Rattachement d'un fichier local à la fiche ouverte ailleurs dans l'app.
     *
     * Deux niveaux, du plus sûr au plus souple :
     *   1. identifiant TMDB en tête du nom (« 10681 - WALL-E (2008).avi ») —
     *      correspondance exacte, aucune ambiguïté possible ;
     *   2. sinon comparaison du titre nettoyé (année, mentions techniques et
     *      tags d'équipe retirés) avec les titres connus de la fiche, en
     *      réutilisant BackupRegistry.workMatches — le même comparateur que
     *      les autres sources, donc un comportement cohérent.
     */
    private val RE_ID_TETE = Regex("""^(\d{2,8})\s*-\s*""")

    private val ROMAINS = mapOf(
        "ii" to 2, "iii" to 3, "iv" to 4, "v" to 5, "vi" to 6,
        "vii" to 7, "viii" to 8, "ix" to 9, "x" to 10,
    )

    /**
     * Numéro de SUITE porté par un titre. Absent = 1.
     *
     * ⚠ 2026-08-17 (user « j'ai lancé Cendrillon 2, je me retrouve avec 3 matchs
     *   et sûrement 2 de mauvais ») — LE point aveugle du comparateur commun.
     *   BackupRegistry.sigWords() ne garde que les mots d'AU MOINS 3 CARACTÈRES,
     *   donc « 2 » et « 3 » sont purement et simplement jetés :
     *       « Cendrillon »   → { cendrillon }
     *       « Cendrillon 2 » → { cendrillon }
     *       « Cendrillon 3 » → { cendrillon }
     *   Les trois titres deviennent IDENTIQUES pour lui. Vérifié sur le compte :
     *   pour « Cendrillon 2 : Une vie de princesse », l'app proposait
     *   Cendrillon.avi (105 min, le classique de 1950) et Cendrillon 3.avi
     *   (74 min) en plus du bon fichier.
     *   L'information était pourtant DANS le nom du fichier — elle était juste
     *   détruite avant la comparaison. On la relit donc ici, avant tout.
     *
     *   Le sous-titre est retiré d'abord : le numéro n'est pas en fin de chaîne
     *   dans « Cendrillon 2 : Une vie de princesse », il est avant le « : ».
     *   Les chiffres romains sont acceptés pour les titres alternatifs TMDB
     *   (« Cinderella II: Dreams Come True » → 2).
     */
    internal fun numeroDeSuite(titre: String): Int {
        // ⚠ NE PAS chercher le numéro SEULEMENT en fin de chaîne.
        //   Première version faite comme ça, en coupant d'abord au « : ».
        //   Elle marchait sur « Cendrillon 2 : Une vie de princesse » (titre
        //   TMDB) mais PAS sur « Cendrillon 2 - Une vie de princesse » : selon
        //   la fiche d'où l'utilisateur ouvre le film, le séparateur est un
        //   deux-points, un tiret, ou rien du tout. Le titre finissait alors
        //   par « princesse », aucun numéro n'était lu, la cible tombait à 1,
        //   et le BON fichier « Cendrillon 2 » se faisait écarter — on passait
        //   de 3 faux matches à zéro match.
        //   On balaie donc tout le titre et on prend le PREMIER nombre isolé.
        val base = titre.substringBeforeLast('.', titre)           // extension
        for (mot in base.split(Regex("""[\s:\-–_/\\\[\]()]+"""))) {
            if (mot.isBlank()) continue
            val m = mot.lowercase()
            // Année : jamais un numéro de suite (« Madagascar (2005) »).
            if (Regex("""^(19|20)\d{2}$""").matches(m)) continue
            // Repère d'épisode : « S01E01 » n'est pas un numéro de suite.
            if (Regex("""^s\d{1,2}e\d{1,3}$""").matches(m)) continue
            val n = m.toIntOrNull() ?: ROMAINS[m] ?: continue
            if (n in 2..30) return n
        }
        return 1
    }

    /**
     * La durée du fichier est-elle compatible avec celle annoncée par TMDB ?
     *
     * 2026-08-17 (user « avec peut-être la durée du film ») : second garde-fou,
     *   indépendant du nom. BackupRegistry calcule déjà `runtimeSecondes` /
     *   `runtimeMaxSecondes` depuis la fiche TMDB déjà chargée (aucune requête
     *   en plus), et VOE renvoie la durée réelle de chaque fichier : il suffit
     *   de les comparer.
     *   Marge VOLONTAIREMENT large (10 min ou 25 %) : un .avi ripé n'a jamais
     *   exactement la durée TMDB (accélération PAL ~4 %, génériques, montages
     *   différents). Le but n'est pas d'être précis, c'est d'écarter l'énorme
     *   écart — Cendrillon 1950 (105 min) proposé pour un film de 73 min.
     *   Durée inconnue d'un côté ou de l'autre ⇒ on n'exclut pas.
     */
    /**
     * Le fichier partage-t-il le MOT DE TÊTE du titre demandé ?
     *
     * ⚠ 2026-08-17 (user « pourquoi Spider-Man 2 ne matche pas ») — le journal
     *   a montré pire que prévu : pour « Spider-Man 2 », le seul fichier retenu
     *   par le comparateur commun était **« Ip Man 3 »**.
     *   Mécanique du faux match, dans BackupRegistry.titleMatches :
     *       sigWords(« Ip Man 3 »)     = { man }     (« ip » 2 lettres, « 3 » 1 → jetés)
     *       sigWords(« Spider-Man 2 ») = { spider, man }
     *   Aucun mot du candidat n'est « en trop » (man ⊆ {spider, man}), et la
     *   couverture vaut 1/2 = 50 %, pile le seuil d'acceptation. Un unique mot
     *   banal partagé suffisait donc à valider n'importe quel film.
     *   Ce coup-ci le filtre « numéro de suite » l'a rattrapé par chance
     *   (3 ≠ 2) — avec « Ip Man 2 » il serait passé.
     *
     *   Règle ajoutée : le PREMIER mot significatif du titre demandé doit se
     *   retrouver dans le nom du fichier. « spider » n'est pas dans « Ip Man 3 »
     *   → rejeté. On la vérifie sur le titre connu que le fichier a réellement
     *   matché, pas sur le titre principal : sinon un fichier nommé en anglais
     *   serait recalé face à une fiche française, alors qu'il est bon.
     *
     *   Pourquoi ici et pas dans workMatches : ce seuil de 50 % est celui de
     *   TOUTES les sources (Movix, Cloudstream, FrenchStream…), réglé au fil de
     *   dizaines de cas. On durcit sur NOS fichiers, dont on maîtrise les noms.
     */
    /**
     * 2026-08-18 : vrai pour un fichier rangé dans le dossier des clips musicaux.
     * Le test porte sur le CHEMIN du dossier, pas sur le titre : un clip peut
     * s'appeler comme un film, c'est justement le problème. Accents et casse
     * neutralisés — VOE renvoie le libellé tel qu'il a été saisi.
     */
    private fun estUnClip(f: Fichier): Boolean {
        val d = java.text.Normalizer.normalize(f.dossier, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
        return d.contains("clip")
    }

    private fun motTeteCouvert(candidat: String, titresConnus: Collection<String>): Boolean {
        val cw = BackupRegistry.sigWords(candidat)
        if (cw.isEmpty()) return true                    // titre sans mot exploitable
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

    private fun dureeCompatible(fichierSec: Int, basSec: Int?, hautSec: Int?): Boolean {
        if (fichierSec <= 0) return true
        val bas = basSec ?: return true
        val haut = hautSec ?: bas
        val marge = maxOf(600, (haut * 0.25).toInt())
        return fichierSec >= bas - marge && fichierSec <= haut + marge
    }

    // ─────────────────────────── liste rapide, pour le RATTACHEMENT seulement
    @Volatile private var cacheRapide: List<Fichier> = emptyList()
    @Volatile private var cacheRapideTs: Long = 0L

    /**
     * Tous les fichiers du compte, en ~11 requêtes au lieu de ~95.
     *
     * ⚠ 2026-08-19 (user « il est apparu mais vraiment longtemps après ») :
     *   voilà pourquoi c'était si long. `serveursPour` appelait `tout()`, qui
     *   parcourt l'arborescence dossier par dossier — 87 dossiers, donc 87
     *   requêtes, donc des 429 en cascade. Plusieurs minutes avant que le
     *   serveur ONYX n'apparaisse dans la fiche.
     *
     *   Or pour RATTACHER un fichier on n'a besoin que de son code, de son nom
     *   et de sa durée — pas de savoir dans quel dossier il range.
     *   `/api/file/list` donne tout ça, 250 par page : 8 requêtes pour 2000
     *   fichiers. Le dossier ne sert qu'à une chose ici, écarter les clips : on
     *   lit donc UNIQUEMENT les dossiers « clip » (3 requêtes) pour connaître
     *   leurs codes.
     *
     *   `tout()` reste utilisé là où le rangement compte vraiment (navigation
     *   de secours, sections), et garde son cache de 10 min.
     */
    private suspend fun toutRapide(): List<Fichier> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (cacheRapide.isNotEmpty() && now - cacheRapideTs < TTL_MS) return@withContext cacheRapide
        // Si la liste complète est déjà chargée, elle est meilleure : on la prend.
        if (cache.isNotEmpty() && now - cacheTs < TTL_MS) return@withContext cache

        // 1. les codes des clips — on ne descend QUE dans les dossiers « clip ».
        val clips = HashSet<String>()
        fun lireClips(fid: String?) {
            var page = 1
            while (page <= 40) {
                val p = HashMap<String, String>()
                if (fid != null) p["fld_id"] = fid
                p["page"] = "$page"
                p["per_page"] = "250"
                val res = get("/api/folder/list", p)?.optJSONObject("result") ?: return
                if (page == 1) {
                    val sous = res.optJSONArray("folders")
                    for (i in 0 until (sous?.length() ?: 0)) {
                        val o = sous?.optJSONObject(i) ?: continue
                        val nom = java.text.Normalizer.normalize(o.optString("name"),
                            java.text.Normalizer.Form.NFD)
                            .replace(Regex("\\p{Mn}+"), "").lowercase()
                        if (fid != null || nom.contains("clip")) lireClips(o.optString("fld_id"))
                    }
                }
                val fic = res.optJSONObject("files")
                val data = fic?.optJSONArray("data")
                for (i in 0 until (data?.length() ?: 0)) {
                    val o = data?.optJSONObject(i) ?: continue
                    if (fid != null) clips += codeDe(o)
                }
                if (fic == null || fic.isNull("next_page_url")) break
                page++
            }
        }
        runCatching { lireClips(null) }

        // 2. tous les fichiers, 250 par page.
        val out = mutableListOf<Fichier>()
        var page = 1
        while (page <= 60) {
            val r = get("/api/file/list", mapOf("page" to "$page", "per_page" to "250"))
                ?.optJSONObject("result") ?: break
            val data = r.optJSONArray("data") ?: break
            for (i in 0 until data.length()) {
                val o = data.optJSONObject(i) ?: continue
                val code = codeDe(o)
                if (code.isBlank()) continue
                val nom = o.optString("title").ifBlank { o.optString("name") }
                if (nom.isBlank()) continue
                out += Fichier(
                    code = code,
                    nomBrut = nom,
                    dossier = if (code in clips) "Clip vidéo musique" else "",
                    poster = posterDe(o),
                    dureeSec = o.optInt("length", 0),
                )
            }
            if (r.isNull("next_page_url")) break
            page++
        }
        if (out.isEmpty()) return@withContext cacheRapide
        cacheRapide = out
        cacheRapideTs = now
        Log.d(TAG, "liste rapide : ${out.size} fichier(s), dont ${clips.size} clips")
        out
    }

    suspend fun serveursPour(
        tmdbId: String?,
        titresConnus: Collection<String>,
        annee: Int?,
        estUnFilm: Boolean,
        /** Titre RÉELLEMENT demandé — surtout pas la liste des alternatifs :
         *  c'est lui qui porte le bon numéro de suite. Même précaution que
         *  BackupRegistry.sousTitreCompatible, pour la même raison. */
        titrePrincipal: String? = null,
        dureeMinSec: Int? = null,
        dureeMaxSec: Int? = null,
        /**
         * Saison et episode demandes. ⚠ 2026-08-19 (user « quand j'ai recherche
         * la serie sur Movix elle n'a pas trouve le serveur ONYX avec Star
         * Trek ») : c'etait LE trou. BackupRegistry connait la saison et
         * l'episode depuis toujours (`Key(title, year, season, episode,
         * isMovie)`) mais ne les transmettait pas ici. On comparait donc
         * « Star Trek - S01E01 » au titre « Star Trek », et meme en cas de
         * match on n'avait aucun moyen de designer LE bon episode parmi 79.
         * Aucun rattachement de serie n'etait possible — ce n'etait pas un
         * mauvais match, c'etait l'absence de l'information.
         */
        saison: Int = 0,
        episode: Int = 0,
    ): List<Video.Server> {
        if (!actif) return emptyList()
        val fichiers = try {
            // ── 2026-08-18 (user, URGENT : « le dossier clip … ne doit surtout pas matcher
            //   pour les vidéos VOD, là ça vient de m'arriver sur un dessin animé. En aucun
            //   cas il ne doit être recherché, ce sont des clips ») ────────────────────────
            //   Les 535 clips musicaux portent des titres courts et très communs — « Alive »,
            //   « Away », « Angels », « Awake »… — que le comparateur de titres rapproche
            //   fatalement d'un film ou d'un dessin animé du même nom. Résultat : un clip de
            //   3 minutes proposé comme serveur d'un long métrage.
            //   Ils sont donc EXCLUS du rapprochement, avant même le test par identifiant.
            //   Ça ne change rien à leur lecture : ils restent visibles et lisibles dans le
            //   dossier « Clip vidéo musique » du TV Hub, c'est uniquement la recherche de
            //   serveurs VOD qui les ignore.
            //   2026-08-19 : toutRapide() et non tout() — voir son commentaire.
            //   tout() parcourait les 87 dossiers un par un (429 en cascade,
            //   plusieurs minutes avant l'apparition du serveur ONYX) alors que
            //   le rattachement n'a besoin que du code, du nom et de la durée.
            toutRapide().filterNot { estUnClip(it) }
        } catch (e: Exception) {
            Log.w(TAG, "lecture bibliothèque KO : ${e.message}")
            return emptyList()
        }
        if (fichiers.isEmpty()) return emptyList()

        // ⚠ 2026-08-17 (user « tu es sûr qu'avec les identifiants ça va être
        //   trouvé direct ? ») — LE PIÈGE : TMDB tient DEUX numérotations
        //   séparées, une pour les films et une pour les séries. L'identifiant
        //   550 désigne « Fight Club » côté film ET une série sans aucun
        //   rapport côté série. Les 516 identifiants écrits dans les noms de
        //   fichiers sont TOUS des identifiants de FILM (posés depuis
        //   /search/movie). Sans ce garde-fou, ouvrir la série n°38757
        //   proposerait « 38757 - Raiponce » comme serveur.
        //   Le rapprochement par identifiant est donc réservé aux films ; pour
        //   les séries on retombe sur la comparaison par titre, qui reste
        //   correcte. Le jour où on numérotera les épisodes, il faudra un
        //   préfixe distinct (« tv:<id> ») pour lever l'ambiguïté.
        val exacts = tmdbId?.takeIf { it.isNotBlank() && estUnFilm }?.let { id ->
            fichiers.filter { RE_ID_TETE.find(it.titre)?.groupValues?.get(1) == id }
        }.orEmpty()
        if (exacts.isNotEmpty()) {
            Log.d(TAG, "rattachement par identifiant TMDB $tmdbId : ${exacts.size}")
            return exacts.map { serveurDe(it.code, "VOE") }
        }

        // ── EPISODE DE SERIE : le numero decide, pas le titre ────────────────
        //   2026-08-19 — « Star Trek - S02E01 » ne peut pas ressembler a
        //   « Star Trek » pour un comparateur de titres, et c'est normal : ce
        //   n'est pas le meme objet. Pour un episode, la seule chose qui
        //   identifie vraiment le fichier, c'est SxxExx. On s'appuie dessus, et
        //   on ne garde que ce qui porte AUSSI le nom de la serie — sinon
        //   « Fringe - S02E01 » repondrait pour Star Trek.
        if (!estUnFilm && saison > 0 && episode > 0) {
            val marque = Regex("(?i)s0*${saison}[ ._-]?e0*${episode}(?!\\d)")
            val episodes = fichiers.filter { f ->
                val propre = RE_ID_TETE.replace(f.titre, "")
                marque.containsMatchIn(propre) &&
                    motTeteCouvert(propre, titresConnus)
            }
            if (episodes.isNotEmpty()) {
                Log.d(TAG, "rattachement episode S${saison}E${episode} : " +
                    "${episodes.size} (${episodes.first().titre})")
                return episodes.map { serveurDe(it.code, "VOE") }
            }
            Log.i(TAG, "aucun fichier pour S${saison}E${episode} de " +
                "${titresConnus.firstOrNull()}")
            return emptyList()
        }

        if (titresConnus.isEmpty()) return emptyList()
        val parTitre = fichiers.filter { f ->
            val t = RE_ID_TETE.replace(f.titre, "")
            BackupRegistry.workMatches(t, titresConnus, annee, estUnFilm)
        }
        // ── Deux garde-fous APRÈS le comparateur commun ─────────────────────
        //   2026-08-17 (user « les faux matches avec nos serveurs à nous ») :
        //   on ne touche PAS à workMatches, utilisé par Movix, Cloudstream,
        //   FrenchStream et tous les autres — un changement là-bas casserait
        //   des matches corrects sans qu'on s'en aperçoive. On filtre donc
        //   ici, uniquement sur NOS fichiers, où on connaît la vérité (nom du
        //   fichier + durée réelle renvoyée par VOE).
        val attendu = numeroDeSuite(titrePrincipal ?: titresConnus.first())
        val retenus = parTitre.filter { f ->
            val propre = RE_ID_TETE.replace(f.titre, "")
            if (!motTeteCouvert(propre, titresConnus)) {
                Log.i(TAG, "écarté (mot de tête absent) : ${f.titre}")
                return@filter false
            }
            val n = numeroDeSuite(propre)
            if (n != attendu) {
                Log.i(TAG, "écarté (suite n°$n ≠ n°$attendu) : ${f.titre}")
                return@filter false
            }
            if (!dureeCompatible(f.dureeSec, dureeMinSec, dureeMaxSec)) {
                Log.i(TAG, "écarté (durée ${f.dureeSec / 60} min, attendu " +
                    "${(dureeMinSec ?: 0) / 60}-${(dureeMaxSec ?: 0) / 60} min) : ${f.titre}")
                return@filter false
            }
            true
        }
        if (parTitre.size != retenus.size) {
            Log.i(TAG, "faux matches ecartes : ${parTitre.size - retenus.size} " +
                "sur ${parTitre.size} (demande : ${titrePrincipal ?: titresConnus.first()})")
        }
        if (retenus.isNotEmpty()) {
            Log.d(TAG, "rattachement par titre : ${retenus.size} (${titresConnus.first()})")
        }
        return retenus.map { serveurDe(it.code, "VOE") }
    }
}
