package com.streamflixreborn.streamflix.banc

import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.BackupRegistry
import com.streamflixreborn.streamflix.utils.SanteSources
import com.streamflixreborn.streamflix.utils.TMDb3
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 2026-11-02 (user : « au lieu de s'embêter avec l'interface Android, trouver un moyen de tester
 * tous les serveurs plus facilement […] une recherche qui prend toutes les sources directement »)
 *
 * BANC DE TEST DES SERVEURS - version de TEST uniquement (source set « debug »).
 *   1. Un titre (ou un numéro TMDB) : toutes les sources sont interrogées par le MÊME chemin que
 *      le lecteur (BackupRegistry.fetchAll).
 *   2. Chaque serveur trouvé est extrait par le MÊME chemin que la lecture (BackupRegistry.getVideo),
 *      puis son flux est ouvert dans un lecteur invisible : démarre-t-il, en combien de temps, à
 *      quel débit ? Sinon : pourquoi (introuvable, refusé, délai, extracteur, décodeur).
 *   3. Vert / rouge / orange par serveur ; un appui lit le serveur dans le lecteur du haut.
 *   4. Rapport : <stockage appli>/files/banc/rapport.tsv + lignes « BANC » dans le journal,
 *      relus par outils/tester_serveurs.ps1 sur le PC.
 * Limite : le lecteur du banc est un lecteur simple (pas le téléchargement parallèle ni les
 *   réglages réseau spéciaux du vrai lecteur) - il dit si un serveur DÉMARRE, pas s'il tient
 *   tout le film.
 * Les recherches du banc ne comptent PAS dans le bilan de santé de la base (SanteSources.horsBilan).
 */
class BancTestActivity : ComponentActivity() {

    private class Ligne(val server: Video.Server, val source: String, val vue: TextView) {
        @Volatile var video: Video? = null
    }

    private class Sonde(val ok: Boolean, val cause: String, val demarrageMs: Long?, val mbits: Double?, val av: String = "")

    private lateinit var champTitre: EditText
    private lateinit var champSaison: EditText
    private lateinit var champEpisode: EditText
    private lateinit var resume: TextView
    private lateinit var liste: LinearLayout
    private lateinit var vueLecteur: PlayerView
    private var lecteurVue: ExoPlayer? = null
    private var travail: Job? = null
    private val lignes = mutableListOf<Ligne>()
    private val rapport by lazy { File(getExternalFilesDir("banc"), "rapport.tsv") }
    @Volatile private var nbOk = 0
    @Volatile private var nbKo = 0
    @Volatile private var nbEnCours = 0
    @Volatile private var collecteFinie = false
    private var titreCourant = ""
    @Volatile private var fichierTitre: File? = null

    // ── Écran ─────────────────────────────────────────────────────────────────────
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SanteSources.horsBilan = true
        val racine = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(18, 18, 18))
            setPadding(dp(8), dp(28), dp(8), dp(8))
        }
        vueLecteur = PlayerView(this).apply { setBackgroundColor(Color.BLACK) }
        racine.addView(vueLecteur, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200)))

        val ligneSaisie = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun champ(indice: String) = EditText(this).apply {
            hint = indice; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); textSize = 14f; isSingleLine = true
        }
        champTitre = champ("Titre ou tmdb:123")
        champSaison = champ("S").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        champEpisode = champ("É").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val bouton = Button(this).apply {
            text = "Tester"
            setOnClickListener {
                val t = champTitre.text.toString().trim()
                val tmdb = t.removePrefix("tmdb:").takeIf { t.startsWith("tmdb:") }
                val s = champSaison.text.toString().toIntOrNull() ?: 0
                val e = champEpisode.text.toString().toIntOrNull() ?: 0
                lancer(if (tmdb == null) t else null, tmdb, if (s > 0) "tv" else null, s, e)
            }
        }
        // 2026-11-02 (user : « une fois testé, se focus que sur les serveurs rouges au lieu de marteler
        //   tous les serveurs ») - ne rejoue que les serveurs en échec / orange du test précédent.
        val boutonRouges = Button(this).apply {
            text = "Rouges"
            setOnClickListener {
                val t = champTitre.text.toString().trim()
                val tmdb = t.removePrefix("tmdb:").takeIf { t.startsWith("tmdb:") }
                val s = champSaison.text.toString().toIntOrNull() ?: 0
                val e = champEpisode.text.toString().toIntOrNull() ?: 0
                lancer(if (tmdb == null) t else null, tmdb, if (s > 0) "tv" else null, s, e, rouges = true)
            }
        }
        ligneSaisie.addView(champTitre, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        ligneSaisie.addView(champSaison, LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT))
        ligneSaisie.addView(champEpisode, LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT))
        ligneSaisie.addView(bouton)
        ligneSaisie.addView(boutonRouges)
        racine.addView(ligneSaisie)

        resume = TextView(this).apply { setTextColor(Color.LTGRAY); textSize = 13f; text = "Tape un titre puis « Tester »." }
        racine.addView(resume)
        liste = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        racine.addView(ScrollView(this).apply { addView(liste) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(racine)
        lireIntent()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        lireIntent()
    }

    /** Lancement depuis le PC : adb shell am start -n …/BancTestActivity --es q "titre" [--es tmdb 123 --es type tv --ei s 1 --ei e 2] */
    private fun lireIntent() {
        val i = intent ?: return
        val q = i.getStringExtra("q")
        val tmdb = i.getStringExtra("tmdb")
        if (q.isNullOrBlank() && tmdb.isNullOrBlank()) return
        val s = i.getIntExtra("s", 0)
        val e = i.getIntExtra("e", 0)
        champTitre.setText(q ?: "tmdb:$tmdb")
        if (s > 0) champSaison.setText("$s")
        if (e > 0) champEpisode.setText("$e")
        lancer(q, tmdb, i.getStringExtra("type"), s, e, rouges = i.getBooleanExtra("rouges", false))
    }

    override fun onDestroy() {
        travail?.cancel()
        lecteurVue?.release()
        SanteSources.horsBilan = false
        super.onDestroy()
    }

    // ── Déroulé d'un test ─────────────────────────────────────────────────────────
    private fun lancer(q: String?, tmdb: String?, type: String?, s: Int, e: Int, rouges: Boolean = false) {
        travail?.cancel()
        lignes.clear(); liste.removeAllViews()
        nbOk = 0; nbKo = 0; nbEnCours = 0; collecteFinie = false; fichierTitre = null
        travail = lifecycleScope.launch {
            resume.text = "Recherche du titre…"
            val vt = try { resoudre(q, tmdb, type, s, e) } catch (ex: Exception) {
                resume.text = "Titre introuvable sur TMDB : ${ex.message}"; null
            } ?: run { if (!resume.text.startsWith("Titre")) resume.text = "Titre introuvable sur TMDB"; ecrireFin(); return@launch }
            val (tmdbId, titre) = when (vt) {
                is Video.Type.Movie -> vt.id to vt.title
                is Video.Type.Episode -> vt.tvShow.id to "${vt.tvShow.title} S${vt.season.number}E${vt.number}"
            }
            titreCourant = if (rouges) "$titre (rouges seulement)" else titre
            // Mode « rouges » : on relit le dernier rapport DE CE TITRE (un fichier par titre/épisode,
            //   donc d'autres tests entre-temps ne le perdent pas) et on saute seulement les VERTS -
            //   un serveur nouveau, absent du test précédent, est testé.
            fichierTitre = File(rapport.parentFile, "titre_${tmdbId}_${s}_${e}.tsv")
            val precedent = runCatching { fichierTitre!!.readLines() }.getOrDefault(emptyList())
            val dejaOk = precedent.filter { it.startsWith("OK\t") }.mapNotNull { it.split('\t').getOrNull(2) }.toSet()
            if (rouges && precedent.isEmpty()) {
                resume.text = "Pas de test précédent pour « $titre » : lance d'abord « Tester »."; ecrireLigne("FIN\taucun test précédent pour ce titre"); return@launch
            }
            ecrireDebut(titre, tmdbId)
            // Les verts du test précédent sont recopiés tels quels : le rapport reste complet et un
            //   nouveau passage « Rouges » saura toujours lesquels sauter.
            if (rouges) precedent.filter { it.startsWith("OK\t") }.forEach { ecrireLigne(it) }
            majResume()
            val vus = ConcurrentHashMap.newKeySet<String>()
            // 2026-11-02 (user : « ça devrait pouvoir être testé un par un ») - UNE lecture à la fois,
            //   comme dans l'appli (deux lectures simultanées saturaient le même CDN, ex. Vidara).
            val semExtraction = Semaphore(3)
            val semLecture = Semaphore(1)
            coroutineScope {
                val portee = this
                withTimeoutOrNull(180_000L) {
                    BackupRegistry.fetchAll(tmdbId, vt, titleHint = if (vt is Video.Type.Movie) vt.title else (vt as Video.Type.Episode).tvShow.title)
                        .collect { lot ->
                            for (srv in lot) {
                                if (!vus.add(srv.id)) continue
                                // 2026-11-02 (user : « Ciné Films, Cinélux… ça sert à rien de les essayer, c'est de l'IPTV ») :
                                //   sources IPTV jamais testées (rien à réparer de notre côté).
                                if (SOURCES_IPTV.any { srv.id.removePrefix(BackupRegistry.PREFIX).startsWith("$it::") }) continue
                                if (rouges && srv.name in dejaOk) continue   // vert au test précédent : on ne le rejoue pas
                                val l = ajouterLigne(srv)
                                portee.launch { tester(l, semExtraction, semLecture) }
                            }
                        }
                }
                collecteFinie = true
                majResume()
            }
            ecrireFin()
            majResume()
        }
    }

    private suspend fun resoudre(q: String?, tmdb: String?, type: String?, s: Int, e: Int): Video.Type? = withContext(Dispatchers.IO) {
        fun film(id: String, titre: String, date: String?) = Video.Type.Movie(id = id, title = titre, releaseDate = date ?: "", poster = "", imdbId = null)
        fun serie(id: String, titre: String, date: String?) = Video.Type.Episode(
            id = "$id-${s.coerceAtLeast(1)}-${e.coerceAtLeast(1)}", number = e.coerceAtLeast(1), title = null, poster = null, overview = null,
            tvShow = Video.Type.Episode.TvShow(id = id, title = titre, poster = null, banner = null, releaseDate = date, imdbId = null),
            season = Video.Type.Episode.Season(number = s.coerceAtLeast(1), title = null),
        )
        val veutSerie = type == "tv" || (type == null && s > 0)
        if (!tmdb.isNullOrBlank()) {
            val id = tmdb.trim().toInt()
            return@withContext if (veutSerie) {
                val d = TMDb3.TvSeries.details(id, language = "fr-FR"); serie(id.toString(), d.name, d.firstAirDate)
            } else {
                val d = TMDb3.Movies.details(id, language = "fr-FR"); film(id.toString(), d.title, d.releaseDate)
            }
        }
        val resultats = TMDb3.Search.multi(q ?: return@withContext null, language = "fr-FR").results
        val choix = resultats.firstOrNull {
            when (type) {
                "movie" -> it is TMDb3.Movie
                "tv" -> it is TMDb3.Tv
                else -> if (s > 0) it is TMDb3.Tv else (it is TMDb3.Movie || it is TMDb3.Tv)
            }
        }
        when (choix) {
            is TMDb3.Movie -> film(choix.id.toString(), choix.title, choix.releaseDate)
            is TMDb3.Tv -> serie(choix.id.toString(), choix.name, choix.firstAirDate)
            else -> null
        }
    }

    private suspend fun ajouterLigne(srv: Video.Server): Ligne = withContext(Dispatchers.Main) {
        val source = srv.id.removePrefix(BackupRegistry.PREFIX).substringBefore("::").ifBlank { "?" }
        val vue = TextView(this@BancTestActivity).apply {
            textSize = 13f; setPadding(dp(4), dp(6), dp(4), dp(6)); setTextColor(Color.GRAY)
        }
        val l = Ligne(srv, source, vue)
        vue.text = "○ ${srv.name}\n    en attente"
        vue.setOnClickListener { jouer(l) }
        lignes += l
        liste.addView(vue)
        nbEnCours++
        majResume()
        l
    }

    private suspend fun tester(l: Ligne, semExtraction: Semaphore, semLecture: Semaphore) {
        afficher(l, Color.LTGRAY, "◌", "extraction…")
        val t0 = SystemClock.elapsedRealtime()
        val video = try {
            // 2026-11-02 : sur le thread IO, comme le lecteur. Sinon les extracteurs qui ne basculent
            //   pas eux-mêmes (TokyVideo, ok.ru, sonde d'hôte inconnu…) tombent en
            //   NetworkOnMainThreadException, maquillée en « SSLHandshakeException » vide.
            semExtraction.withPermit { withTimeout(45_000L) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { BackupRegistry.getVideo(l.server) } } }
        } catch (ex: Throwable) {
            if (ex is CancellationException && ex !is TimeoutCancellationException) throw ex
            // Message vide (ex. SSLHandshakeException) → on remonte la chaîne des causes.
            val detail = generateSequence(ex as Throwable?) { it.cause }.mapNotNull { it.message?.takeIf { m -> m.isNotBlank() } }.firstOrNull() ?: ""
            if (ex is javax.net.ssl.SSLException) android.util.Log.w("BANC", "trace TLS ${l.server.name}", ex)
            val cause = if (ex is TimeoutCancellationException) "délai : extraction > 45 s" else "extracteur : ${Extractor.classifyError(ex)} · ${ex.javaClass.simpleName} ${detail.take(120)}"
            terminer(l, Sonde(false, cause, null, null), SystemClock.elapsedRealtime() - t0); return
        }
        val tExtraction = SystemClock.elapsedRealtime() - t0
        l.video = video
        val refus = when {
            video.source.isBlank() -> "extracteur : aucune adresse de flux"
            video.needsWebViewClick -> "navigateur requis (clic)"
            video.drmType != null -> "protégé (DRM ${video.drmType}) - non testé ici"
            else -> null
        }
        if (refus != null) { terminer(l, Sonde(false, refus, null, null), tExtraction); return }
        afficher(l, Color.LTGRAY, "◌", "extrait en ${tExtraction / 1000.0} s, en file pour la lecture…")
        val extraitA = SystemClock.elapsedRealtime()
        val sonde = semLecture.withPermit {
            // 2026-11-02 : dans l'appli, la lecture suit l'extraction de quelques secondes. Ici un
            //   serveur peut attendre son tour plusieurs minutes et son lien expirer (upbolt → 403
            //   4 min après). Au-delà de 45 s d'attente, on ré-extrait juste avant de lire.
            var v = video
            // Serveurs lus via le navigateur caché (partagé entre extractions) : toujours ré-extraits
            //   juste avant la lecture, pour que ce navigateur soit bien celui de CE serveur.
            val viaNavigateur = video.webViewUrl != null || video.source.contains("dropcdn.io")
            if (viaNavigateur || SystemClock.elapsedRealtime() - extraitA > 45_000L) {
                afficher(l, Color.LTGRAY, "◌", "lien rafraîchi avant lecture…")
                v = runCatching { withTimeout(45_000L) { BackupRegistry.getVideo(l.server) } }.getOrNull() ?: video
                l.video = v
            }
            afficher(l, Color.WHITE, "▶", "lecture en cours (8 s)…")
            sonder(v)
        }
        terminer(l, sonde, tExtraction)
    }

    // ── Lecteur invisible ─────────────────────────────────────────────────────────
    // 2026-11-02 (user : « NetMirror n'est pas cassé, c'est que tu n'as pas réussi à recréer la
    //   connexion ») - le banc se connecte comme le VRAI lecteur. Copie fidèle du choix de
    //   PlayerMobileFragment.createHttpDataSourceFactory (needsCronet / needsDoH / Vidara en
    //   parallèle / DefaultHttp + repli TLS) : à garder en phase si ces listes changent.
    private fun banqueCronet(url: String) = listOf(".strmd.st", "vidzy.", "fsvid.", "cfglobalcdn.com", "anime-sama.",
        "uqload.is", "uqload.cx", "uqload.co", "uqload.to", "uqload.net", "strm5.uqload", "strm.uqload", "abyssa",
        "abysscdn", "citron-edge", "97bf1.com").any { url.contains(it, ignoreCase = true) }

    private fun banqueDoH(url: String) = listOf("sprintcdn", "r66nv9ed.com", "cloudatacdn.com", "cdndirector.dailymotion.com",
        "dmcdn.net", "xlivetv.com", "anime-sama.", "vmwesa", "acek-cdn", "/play/movie.php", "/play/live.php")
        .any { url.contains(it, ignoreCase = true) } ||
        com.streamflixreborn.streamflix.utils.RoutesLecture.exigeClientExtraction(url)

    private val moteurCronet: org.chromium.net.CronetEngine? by lazy {
        runCatching { org.chromium.net.CronetEngine.Builder(applicationContext).enableQuic(true).enableHttp2(true).build() }.getOrNull()
    }
    private val executeurCronet by lazy { java.util.concurrent.Executors.newFixedThreadPool(4) }

    /** Fabrique réseau choisie comme le vrai lecteur, et son nom (affiché dans le rapport). */
    private fun fabriqueReseau(url: String, uaVideo: String? = null): Pair<HttpDataSource.Factory, String> {
        val nc = com.streamflixreborn.streamflix.utils.NetworkClient
        if (url.contains("sendvid.com", true) || url.contains("s1q2105.com", true) || url.contains("97bf1.com", true))
            return com.streamflixreborn.streamflix.utils.ParallelRangeDataSource.Factory(nc.default, if (url.contains("sendvid.com", true)) 4 else 8) to "parallèle"
        // Comme PlayerMobileFragment (bloc « Custom UA from extractor ») : si l'extracteur fournit son
        //   propre User-Agent, la fabrique système est recréée AVEC cet UA (DefaultHttpDataSource ignore
        //   l'UA passé en en-tête). Sans ça : upbolt, Lulustream… → 403. Pas pour DoH ni les hôtes Cronet.
        val hote = runCatching { java.net.URL(url).host.lowercase() }.getOrDefault("")
        if (!uaVideo.isNullOrBlank() && uaVideo != nc.USER_AGENT && !banqueDoH(url) &&
            listOf("uqload", "abyssa", "abysscdn", "citron-edge", "vidzy").none { hote.contains(it) }) {
            return com.streamflixreborn.streamflix.utils.LiveReconnectingHttpDataSource.Factory(
                androidx.media3.datasource.DefaultHttpDataSource.Factory().setUserAgent(uaVideo)
                    .setConnectTimeoutMs(15_000).setReadTimeoutMs(30_000).setAllowCrossProtocolRedirects(true)
            ) to "système (UA de l'extracteur)"
        }
        if (!banqueCronet(url)) {
            if (banqueDoH(url)) return OkHttpDataSource.Factory(
                okhttp3.OkHttpClient.Builder().dns(com.streamflixreborn.streamflix.utils.DnsResolver.doh).build()
            ).setUserAgent(nc.USER_AGENT) to "DoH"
            val base = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setUserAgent(nc.USER_AGENT).setConnectTimeoutMs(15_000).setReadTimeoutMs(30_000).setAllowCrossProtocolRedirects(true)
            return com.streamflixreborn.streamflix.utils.TlsFallbackHttpDataSource.Factory(
                com.streamflixreborn.streamflix.utils.LiveReconnectingHttpDataSource.Factory(base),
                OkHttpDataSource.Factory(nc.default).setUserAgent(nc.USER_AGENT),
            ) to "système"
        }
        val moteur = moteurCronet ?: return OkHttpDataSource.Factory(nc.default).setUserAgent(nc.USER_AGENT) to "OkHttp (pas de Cronet)"
        val ua = when {
            url.contains("uqload", true) || url.contains("abyssa", true) || url.contains("abysscdn", true) ->
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"
            url.contains("upbolt", true) || url.contains("vidzy", true) ->
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
            else -> nc.USER_AGENT
        }
        return androidx.media3.datasource.cronet.CronetDataSource.Factory(moteur, executeurCronet)
            .setUserAgent(ua).setConnectionTimeoutMs(30_000).setReadTimeoutMs(30_000) to "Cronet"
    }

    /** Navigateur caché à utiliser pour lire [v], comme PlayerMobileFragment (isLuluVdoCdn / isNetuCfglobalcdn / estDropcdnWebView). */
    private fun navigateurDeLecture(v: Video): android.webkit.WebView? {
        val src = v.source.lowercase()
        val wvUrl = v.webViewUrl.orEmpty().lowercase()
        if (src.contains("dropcdn.io")) com.streamflixreborn.streamflix.extractors.DroploadExtractor.sharedWebView?.let { return it }
        val lulu = wvUrl.isNotBlank() && (listOf("tnmr.org", "luluvdo", "lulustream", "luluvid", "lulucdn").any { src.contains(it) } ||
            listOf("luluvdo", "lulustream", "luluvid", "livavid", "lulust.com").any { wvUrl.contains(it) })
        if (lulu && src.startsWith("data:")) com.streamflixreborn.streamflix.extractors.LuluVdoExtractor.sharedWebView?.let { return it }
        if (v.webViewUrl != null && (src.startsWith("data:") || src.contains("cfglobalcdn.com")))
            com.streamflixreborn.streamflix.extractors.NetuExtractor.sharedWebView?.let { return it }
        return null
    }

    private fun creerLecteur(v: Video, ecoute: TransferListener?): ExoPlayer {
        val uaVideo = v.headers?.get("User-Agent")
        val (http, _) = fabriqueReseau(v.source, uaVideo)
        http.setDefaultRequestProperties(mapOf("User-Agent" to (uaVideo ?: com.streamflixreborn.streamflix.utils.NetworkClient.USER_AGENT)) + (v.headers ?: emptyMap()))
        // Comme le vrai lecteur (needsWebViewDs) : LuluVdo (liste « data: »), Netu (cfglobalcdn) et
        //   Dropload (dropcdn.io) sont lus À TRAVERS le navigateur caché de l'extraction - leur CDN
        //   refuse toute autre connexion (403).
        val navigateur = navigateurDeLecture(v)
        val base: DataSource.Factory = if (navigateur != null)
            com.streamflixreborn.streamflix.utils.WebViewDataSource.Factory(navigateur) else http
        // L'écoute des octets (débit) se branche sur la source de données créée par le lecteur.
        val avecEcoute = if (ecoute == null) base else DataSource.Factory {
            base.createDataSource().also { it.addTransferListener(ecoute) }
        }
        // Comme le vrai lecteur : décodeurs FFmpeg (son Dolby AC3/EAC3…) en complément du matériel,
        //   et SauteImageDataSource (retire les fausses en-têtes PNG/JPEG collées devant la vidéo).
        val rendus = io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory(this).apply {
            setEnableDecoderFallback(true)
            setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        }
        // 2026-11-03 (saut à 90 % : FSVid « 403 » au banc) : comme le vrai lecteur, on passe aussi par
        //   FiltreManifesteHls, qui nettoie le manifeste ET ajoute la clé `gk` aux segments Vidzy/FSVid
        //   (sans elle, leur CDN refuse les segments au-delà des premiers). Sans ça, le banc
        //   accusait à tort ces serveurs de « couper avant la fin ».
        val sources = com.streamflixreborn.streamflix.utils.FiltreManifesteHls.Fabrique(
            com.streamflixreborn.streamflix.utils.SauteImageDataSource.Factory(
                // DefaultDataSource : accepte aussi les listes de lecture « data: » (Lulustream, Wiflix Lecteur 3).
                androidx.media3.datasource.DefaultDataSource.Factory(this, avecEcoute)))
        val p = ExoPlayer.Builder(this, rendus).setMediaSourceFactory(DefaultMediaSourceFactory(sources)).build()
        val mime = when {
            v.type?.contains("mpegurl", true) == true || v.source.contains(".m3u8", true) -> MimeTypes.APPLICATION_M3U8
            v.type?.contains("dash", true) == true || v.source.contains(".mpd", true) -> MimeTypes.APPLICATION_MPD
            else -> null
        }
        val element = MediaItem.Builder().setUri(v.source).apply { if (mime != null) setMimeType(mime) }.build()
        if (mime == MimeTypes.APPLICATION_M3U8) {
            // 2026-11-03 : même lecteur HLS que PlayerMobileFragment (HlsExtractorFactoryAud(0, true)).
            //   (Essai FLAG_ALLOW_NON_IDR_KEYFRAMES pour Nakios Premium 1 : sans effet, retiré.)
            p.setMediaSource(androidx.media3.exoplayer.hls.HlsMediaSource.Factory(sources)
                .setAllowChunklessPreparation(true)
                .setExtractorFactory(com.streamflixreborn.streamflix.utils.media3.HlsExtractorFactoryAud(0, true))
                .createMediaSource(element))
        } else {
            p.setMediaItem(element)
        }
        return p
    }

    private fun causeLecture(err: PlaybackException): String {
        val http = generateSequence(err as Throwable) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
        return when {
            http == 404 || http == 410 -> "introuvable : fichier supprimé (HTTP $http)"
            http == 401 || http == 403 -> "refusé par le serveur (HTTP $http)"
            http != null && http >= 500 -> "serveur en panne (HTTP $http)"
            http != null -> "lecture : HTTP $http"
            err.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                err.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                err.errorCode == PlaybackException.ERROR_CODE_TIMEOUT -> "délai / connexion (${err.errorCodeName})"
            err.errorCode in 3000..3999 -> "format illisible (${err.errorCodeName})"
            err.errorCode in 4000..4999 -> "décodeur (${err.errorCodeName})"
            else -> "lecture : ${err.errorCodeName} ${err.cause?.message?.take(70) ?: ""}"
        }
    }

    private suspend fun sonder(v: Video): Sonde = withContext(Dispatchers.Main) {
        val octets = AtomicLong(0)
        val ecoute = object : TransferListener {
            override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
            override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
            override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) { octets.addAndGet(bytesTransferred.toLong()) }
            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
        }
        val p = creerLecteur(v, ecoute)
        // 2026-11-02 (user : « on peut pas savoir directement s'il y a le son et l'image ») - le
        //   lecteur invisible dessine sur un écran caché (SurfaceTexture) : on sait si la PREMIÈRE
        //   IMAGE est réellement décodée et affichée, et si le SON avance (horloge audio).
        val texture = android.graphics.SurfaceTexture(0).apply { setDefaultBufferSize(320, 180) }
        val surface = android.view.Surface(texture)
        p.setVideoSurface(surface)
        var image = false
        var son = false
        var tailleImage = ""
        p.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onRenderedFirstFrame(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) { image = true }
            override fun onVideoSizeChanged(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.height > 0) tailleImage = "${videoSize.height}p"
            }
            override fun onAudioPositionAdvancing(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) { son = true }
        })
        val pret = CompletableDeferred<String?>()   // null = prêt, sinon la cause de l'échec
        var erreurApres: String? = null
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY && !pret.isCompleted) pret.complete(null) }
            override fun onPlayerError(error: PlaybackException) {
                val c = causeLecture(error)
                if (!pret.isCompleted) pret.complete(c) else erreurApres = c
            }
        })
        val t0 = SystemClock.elapsedRealtime()
        try {
            p.volume = 0f
            p.playWhenReady = true
            p.prepare()
            val fini = withTimeoutOrNull(25_000L) { pret.await(); true }
            if (fini == null) return@withContext Sonde(false, "délai : ne démarre pas en 25 s (${octets.get() / 1024} Ko reçus)", null, null)
            val cause = pret.await()
            if (cause != null) return@withContext Sonde(false, cause, null, null)
            val tDemarrage = SystemClock.elapsedRealtime() - t0
            // 2026-11-02 (user : « faire quelques secondes de lecture pour voir vraiment si ça
            //   lance ») - le serveur doit JOUER 8 s de vidéo (position qui avance), en 20 s au plus.
            val o0 = octets.get()
            val tLecture0 = SystemClock.elapsedRealtime()
            val pos0 = p.currentPosition
            while (p.currentPosition - pos0 < LECTURE_EXIGEE_MS && erreurApres == null &&
                SystemClock.elapsedRealtime() - tLecture0 < 20_000L) delay(250L)
            val lu = p.currentPosition - pos0
            val duree = (SystemClock.elapsedRealtime() - tLecture0).coerceAtLeast(1L)
            erreurApres?.let { return@withContext Sonde(false, "démarre puis coupe après ${lu / 1000} s : $it", tDemarrage, null) }
            val mbits = (octets.get() - o0) * 8.0 / (duree / 1000.0) / 1_000_000.0
            // 2026-11-02 (user : « ce serveur a renvoyé une vidéo pub de 8 secondes ») : 8 s lues ne
            //   suffisent pas, une pub ou un extrait passait pour « OK ». On lit la DURÉE totale du
            //   média : moins de 2 min pour un film / un épisode = pub, bande-annonce ou extrait.
            val dureeMedia = p.duration
            val dureeTxt = if (dureeMedia > 0) "%dh%02d".format(dureeMedia / 3_600_000, (dureeMedia / 60_000) % 60) else "durée ?"
            if (dureeMedia in 1..DUREE_MIN_MS) return@withContext Sonde(false,
                "pub / extrait : la vidéo ne dure que ${dureeMedia / 1000} s", tDemarrage, mbits)
            if (lu < LECTURE_EXIGEE_MS) return@withContext Sonde(false,
                "démarre puis bloque : ${lu / 1000} s lues en ${duree / 1000} s", tDemarrage, mbits)
            // 2026-11-03 (user : « tester une vraie lecture… pour chaque épisode ») — SAUT VERS LA FIN.
            //   8 s lues au début ne prouvent pas que tout le film se charge (Vidara coupait à 1h04 sur
            //   The Dog Stars). On saute à 90 % de la durée et la lecture doit y repartir : au moins
            //   3 s lues après le saut, en 20 s maximum. Ignoré si la durée est inconnue (direct).
            var finTxt = ""
            if (image && son && dureeMedia > DUREE_MIN_MS && p.isCurrentMediaItemSeekable) {
                val cible = dureeMedia * 9 / 10
                val cibleTxt = "%dh%02d".format(cible / 3_600_000, (cible / 60_000) % 60)
                p.seekTo(cible)
                val tSaut = SystemClock.elapsedRealtime()
                var dernierJournal = 0L
                while (erreurApres == null && SystemClock.elapsedRealtime() - tSaut < 20_000L &&
                    !(p.playbackState == Player.STATE_READY && p.currentPosition - cible >= 3_000L)) {
                    // 2026-11-03 DIAGNOSTIC (Livavid : morceaux de la fin reçus, lecture qui ne repart pas).
                    val ecoule = SystemClock.elapsedRealtime() - tSaut
                    if (ecoule - dernierJournal >= 1_000L) {
                        dernierJournal = ecoule
                        android.util.Log.d("BancSaut", "t+${ecoule / 1000}s état=${p.playbackState} pos=${p.currentPosition / 1000}s " +
                            "cible=${cible / 1000}s tampon=${p.bufferedPosition / 1000}s joue=${p.isPlaying} " +
                            "pwr=${p.playWhenReady} suppr=${p.playbackSuppressionReason}")
                    }
                    delay(250L)
                }
                erreurApres?.let { return@withContext Sonde(false,
                    "coupe avant la fin : saut à $cibleTxt (90 %) → $it", tDemarrage, mbits) }
                if (p.currentPosition - cible < 3_000L) {
                    // 2026-11-03 DIAGNOSTIC : adresse COMPLÈTE du flux (le rapport la tronque) pour
                    //   l'analyser depuis le PC (playlist, morceaux de la fin, horodatage).
                    v.source.chunked(900).forEachIndexed { i, bout -> android.util.Log.d("BancSaut", "source[$i] $bout") }
                    android.util.Log.d("BancSaut", "pistes : " + p.currentTracks.groups.joinToString { g ->
                        (0 until g.length).joinToString { j -> "${g.getTrackFormat(j).sampleMimeType}:${g.isTrackSelected(j)}" } })
                }
                if (p.currentPosition - cible < 3_000L) return@withContext Sonde(false,
                    "coupe avant la fin : saut à $cibleTxt (90 %), la lecture ne repart pas en 20 s", tDemarrage, mbits)
                finTxt = " · fin ✓"
            }
            when {
                image && son -> Sonde(true, "OK", tDemarrage, mbits, "image ${tailleImage.ifBlank { "?" }} ✓ son ✓ · $dureeTxt$finTxt")
                !image && !son -> Sonde(false, "ni image ni son pendant la lecture", tDemarrage, mbits)
                !image -> Sonde(false, "pas d'image (son ✓)", tDemarrage, mbits)
                else -> Sonde(false, "pas de son (image ${tailleImage.ifBlank { "?" }} ✓)", tDemarrage, mbits)
            }
        } finally {
            p.release()
            surface.release()
            texture.release()
        }
    }

    /** Appui sur une ligne : lit le serveur dans le lecteur du haut (son coupé au départ). */
    private fun jouer(l: Ligne) {
        val v = l.video ?: run { resume.text = "« ${l.server.name} » n'a pas encore d'adresse de flux."; return }
        lecteurVue?.release()
        lecteurVue = creerLecteur(v, null).also {
            vueLecteur.player = it
            it.playWhenReady = true
            it.prepare()
        }
        resume.text = "Lecture : ${l.source} · ${l.server.name}"
    }

    // ── Affichage et rapport ──────────────────────────────────────────────────────
    private suspend fun afficher(l: Ligne, couleur: Int, puce: String, etat: String) = withContext(Dispatchers.Main) {
        l.vue.setTextColor(couleur)
        l.vue.text = "$puce ${l.server.name}\n    $etat"
    }

    private suspend fun terminer(l: Ligne, s: Sonde, tExtraction: Long) {
        val orange = !s.ok && (s.cause.startsWith("navigateur") || s.cause.startsWith("protégé") || s.cause.startsWith("pas d") ||
            s.cause.contains("aucun sous-titre", true) /* écarté exprès : VOSTFR sans sous-titres FR */)
        val couleur = when { s.ok -> Color.rgb(102, 187, 106); orange -> Color.rgb(255, 167, 38); else -> Color.rgb(239, 83, 80) }
        val detail = if (s.ok) "OK · ${s.av} · démarre en ${"%.1f".format((s.demarrageMs ?: 0) / 1000.0)} s · ${"%.1f".format(s.mbits ?: 0.0)} Mbit/s · extraction ${"%.1f".format(tExtraction / 1000.0)} s"
                     else s.cause
        afficher(l, couleur, if (s.ok) "●" else "✕", detail)
        if (s.ok) nbOk++ else nbKo++
        nbEnCours--
        val champs = listOf(
            if (s.ok) "OK" else if (orange) "LIMITE" else "KO",
            l.source, l.server.name,
            (if (s.ok) "OK · ${s.av}" else s.cause).replace('\t', ' ').replace('\n', ' '),
            "%.1f".format(tExtraction / 1000.0),
            s.demarrageMs?.let { "%.1f".format(it / 1000.0) } ?: "",
            s.mbits?.let { "%.1f".format(it) } ?: "",
            (l.video?.source ?: l.server.src).take(160),
        )
        ecrireLigne(champs.joinToString("\t"))
        majResume()
    }

    private suspend fun majResume() = withContext(Dispatchers.Main) {
        val total = lignes.size
        val etat = if (collecteFinie && nbEnCours <= 0) "terminé" else if (collecteFinie) "dernières vérifications" else "sources en cours"
        resume.text = "$titreCourant : $total serveurs · $nbOk OK · $nbKo en échec · $etat"
    }

    @Synchronized private fun ecrireLigne(t: String) {
        Log.i(TAG, t)
        runCatching { rapport.appendText(t + "\n") }
    }

    private fun ecrireDebut(titre: String, tmdb: String) {
        runCatching { rapport.parentFile?.mkdirs(); rapport.writeText("") }
        ecrireLigne("DEBUT\t$titre\ttmdb $tmdb\t${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.FRANCE).format(java.util.Date())}")
        ecrireLigne("resultat\tsource\tserveur\tcause\textraction_s\tdemarrage_s\tmbits\tadresse")
    }

    private suspend fun ecrireFin() {
        // Attend la fin des derniers tests (les extractions lancées continuent après la collecte).
        while (nbEnCours > 0) delay(500L)
        ecrireLigne("FIN\t${lignes.size} serveurs\t$nbOk OK\t$nbKo en échec")
        fichierTitre?.let { f -> runCatching { rapport.copyTo(f, overwrite = true) } }
    }

    companion object {
        private const val TAG = "BANC"
        /** Lecture réelle exigée avant de déclarer un serveur « OK ». */
        private const val LECTURE_EXIGEE_MS = 8_000L
        /** 2026-11-02 : en dessous, ce n'est pas le film / l'épisode (pub, bande-annonce, extrait). */
        private const val DUREE_MIN_MS = 120_000L
        /** Sources IPTV : pas testées par le banc. */
        private val SOURCES_IPTV = setOf("Ciné Films", "Cinélux", "OTF TV", "Mon IPTV")
    }
}
