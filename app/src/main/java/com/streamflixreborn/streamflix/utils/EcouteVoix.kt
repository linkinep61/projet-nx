package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 2026-10-22 (user : « je suis incapable de me servir du recalage », « on ne sait pas si la
 * voix parle avant ou après si on ne connaît pas la langue », « essaye, au pire on le
 * désactivera dans le mode léger », « une fois que c'est calé, ça s'arrête ») —
 * RECALAGE DES SOUS-TITRES À L'ÉCOUTE DES VOIX (méthode ffsubsync, référence = l'audio).
 *
 *  - Le son DÉJÀ décodé pour la lecture est lu au passage dans l'AudioSink, avec son heure
 *    exacte : aucun décodage en plus, aucun saut, aucun micro.
 *  - Ramené en mono 16 kHz, découpé en trames de 16 ms, le détecteur de voix TEN VAD (réseau de
 *    neurones, sait écarter la musique) dit « parle / ne parle pas ». Il ne comprend pas les mots : la langue n'a pas d'importance.
 *    (2026-10-27 : remplace Silero et YAMNet, ~33 Mo à eux deux ; le 1er calage vient de
 *    l'écoute EN AVANCE, voir EcouteAvance.)
 *  - Toutes les 15 s : alignement des répliques sur la voix (RecalageAuto.alignerSurVoix).
 *    1er réglage dès ~1 min d'écoute, réglage définitif (dérive comprise) après ~8 min,
 *    puis l'écoute S'ARRÊTE.
 */
object EcouteVoix {
    private const val TAG = "EcouteVoix"
    const val PAS = 200L
    private const val ACTIF_GLOBAL = true

    /**
     * AudioSink des lecteurs : celui de Media3, enveloppé pour LIRE au passage le son décodé
     * avec son heure exacte (presentationTimeUs) — aucun décodage ni saut en plus.
     */
    fun audioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
        val base = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
        return object : androidx.media3.exoplayer.audio.ForwardingAudioSink(base) {
            override fun configure(inputFormat: androidx.media3.common.Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
                brut = inputFormat.sampleMimeType == androidx.media3.common.MimeTypes.AUDIO_RAW
                sr = inputFormat.sampleRate; ch = inputFormat.channelCount; enc = inputFormat.pcmEncoding
                ratio = if (sr > 0) sr / 16000.0 else 1.0
                super.configure(inputFormat, specifiedBufferSize, outputChannels)
            }
            // Heures des tampons = temps « moteur » = heure du média + ce décalage.
            override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
                decalageFluxUs = outputStreamOffsetUs
                super.setOutputStreamOffsetUs(outputStreamOffsetUs)
            }
            override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
                // Un même tampon est re-présenté tant que la sortie est pleine : on ne le lit
                //   qu'une fois (même heure = même tampon).
                if (actif && brut && presentationTimeUs != dernierPts) {
                    dernierPts = presentationTimeUs
                    analyser(buffer.duplicate(), presentationTimeUs - decalageFluxUs)
                }
                return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            }
        }
    }

    // ── capture (thread de lecture) ────────────────────────────────────────────────────────
    @Volatile private var actif = false
    @Volatile private var brut = false
    private var dernierPts = Long.MIN_VALUE
    @Volatile private var decalageFluxUs = 0L
    private var sr = 0
    private var ch = 0
    private var enc = C.ENCODING_INVALID
    private var ratio = 1.0
    private var phase = 0.0
    private var acc = 0.0
    private var accN = 0
    // 2026-10-27 : trame de 256 échantillons (16 ms) pour TEN VAD.
    private val trame = ShortArray(TenVad.TRAME)
    @Volatile private var tailleTrame = TenVad.TRAME
    @Volatile private var attributionMs = 0.0
    @Volatile private var minTrames = 5
    private val executeur = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val enFile = java.util.concurrent.atomic.AtomicInteger(0)
    private var trameN = 0
    private var trameDebutMs = 0.0
    private var vadTen: TenVad? = null
    private var detecteur: ((ShortArray) -> Boolean)? = null
    private var journalise = false

    private val verrou = Any()
    private val parle = HashMap<Int, Int>()
    private val total = HashMap<Int, Int>()

    private fun analyser(b0: ByteBuffer, ptsUs: Long) {
        val v = detecteur ?: return
        if (sr <= 0 || ch <= 0) return
        try {
            val b = b0.order(ByteOrder.nativeOrder())
            val flottant = enc == C.ENCODING_PCM_FLOAT
            if (!flottant && enc != C.ENCODING_PCM_16BIT) return
            if (!journalise) { journalise = true; Log.d(TAG, "son reçu : $sr Hz, $ch canaux, encodage $enc, heure média ${ptsUs / 1000} ms (décalage moteur ${decalageFluxUs / 1000} ms)") }
            val taille = if (flottant) 4 else 2
            var tMs = ptsUs / 1000.0
            val pasMs = 1000.0 / sr
            // Tampon non contigu au précédent (saut) : la trame en cours est abandonnée.
            if (trameN > 0 && kotlin.math.abs(tMs - (trameDebutMs + trameN * 1000.0 / 16000 )) > 200) { trameN = 0; phase = 0.0; acc = 0.0; accN = 0 }
            while (b.remaining() >= taille * ch) {
                var somme = 0.0
                for (c in 0 until ch) somme += if (flottant) b.float * 32767.0 else b.short.toDouble()
                if (trameN == 0 && accN == 0) trameDebutMs = tMs
                acc += somme / ch; accN++
                tMs += pasMs
                phase += 1.0
                if (phase >= ratio) {
                    phase -= ratio
                    trame[trameN++] = (acc / accN).toInt().coerceIn(-32768, 32767).toShort()
                    acc = 0.0; accN = 0
                    if (trameN == tailleTrame) {
                        trameN = 0
                        // 2026-10-22 : l'analyse quitte le fil du son (YAMNet prend plusieurs dizaines
                        //   de ms sur une TV : le faire ici couperait le son). File limitée : si
                        //   l'appareil ne suit pas, des trames sont sautées, jamais le son.
                        val copie = trame.copyOf(tailleTrame)
                        val bin = ((trameDebutMs + attributionMs).toLong() / PAS).toInt()
                        if (enFile.get() < 8) {
                            enFile.incrementAndGet()
                            executeur.execute {
                                val voix = try { v(copie) } catch (_: Throwable) { false }
                                enFile.decrementAndGet()
                                synchronized(verrou) {
                                    total[bin] = (total[bin] ?: 0) + 1
                                    if (voix) parle[bin] = (parle[bin] ?: 0) + 1
                                }
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "capture : ${t.message}")
        }
    }

    // ── pilotage (thread principal) ────────────────────────────────────────────────────────
    private val portee = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var tache: Job? = null
    private var ecouteur: Player.Listener? = null
    private var lecteur: Player? = null

    val enCours: Boolean get() = tache?.isActive == true

    /** Secondes de film écoutées depuis le lancement (affichage dans le panneau). */
    fun secondesEcoutees(): Long = synchronized(verrou) { total.size * PAS / 1000 }

    /**
     * Lance l'écoute pour caler [repliques] (heures du fichier affiché). [surResultat] reçoit
     * chaque réglage appliqué (message pour l'utilisateur).
     */
    fun lancer(context: Context, player: Player, repliques: List<RecalageAuto.Replique>, source: String? = null, surResultat: (String) -> Unit = {}) {
        // 2026-10-22 : le détecteur WebRTC prenait la musique pour de la voix (Fight Club : 907
        //   « voix » / 64 silences → faux calages) → remplacé par Silero (réseau de neurones qui
        //   distingue voix / musique / bruits). Interrupteur général conservé.
        // 2026-10-22 (user : sous-titre téléchargé « en échec », rien à l'écran) — si l'écoute ne
        //   démarre pas (pas de détecteur, mode léger, trop peu de répliques…), le calque ne doit
        //   JAMAIS rester masqué « en attente du calage ». Il ne l'est que si l'écoute part vraiment.
        SyncSousTitres.enAttenteCalage = false
        if (!ACTIF_GLOBAL) { Log.d(TAG, "écoute des voix désactivée (détecteur pas assez fiable)"); return }
        // 2026-10-23 : mode léger seulement (une TV ordinaire, 192 Mo par appli, garde le calage).
        if (UserPreferences.modeLeger) { Log.d(TAG, "mode léger : écoute désactivée"); return }
        if (repliques.size < 30) return
        arreter()
        // 2026-10-22 (user : « en cas de problème sur certaines télés, est-ce que l'appli va
        //   planter ? mets un système où ça ne plante pas, et ça échoue simplement ») : un plantage
        //   NATIF (moteur du détecteur) ne peut pas être rattrapé. On pose donc une marque « écoute
        //   en cours » avant de démarrer et on l'enlève à l'arrêt normal. Si l'appli redémarre et
        //   trouve la marque, c'est que le détecteur l'a fait tomber : après 2 fois, ce détecteur
        //   est BANNI sur cet appareil (on essaie l'autre, sinon plus d'écoute) — l'appli ne
        //   replantera pas, le calage par les voix est simplement abandonné.
        val prefs = context.applicationContext.getSharedPreferences("ecoute_voix", Context.MODE_PRIVATE)
        // 2026-10-22 — FAUX BANNISSEMENT constaté sur la télé (« aucun détecteur de voix sûr »,
        //   le calage ne se faisait plus) : chaque mise à jour de l'appli, ou fermeture forcée,
        //   pendant une écoute laissait la marque et comptait comme une chute du détecteur.
        //   On ne compte plus que les VRAIS plantages natifs (raison de sortie fournie par Android
        //   11+) ; et les compteurs gonflés par l'ancienne règle sont remis à zéro une fois.
        if (!prefs.getBoolean("garde_v2", false)) {
            val e = prefs.edit()
            prefs.all.keys.filter { it.startsWith("chutes_") }.forEach { e.remove(it) }
            e.putBoolean("garde_v2", true).commit()
        }
        prefs.getString("en_cours", null)?.let { tombe ->
            val plantageNatif: Boolean? = if (android.os.Build.VERSION.SDK_INT >= 30) runCatching {
                val am = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()?.reason ==
                    android.app.ApplicationExitInfo.REASON_CRASH_NATIVE
            }.getOrNull() else null
            if (plantageNatif == false) {
                prefs.edit().remove("en_cours").commit()
                Log.i(TAG, "l'écoute précédente ($tombe) a été interrompue (pas un plantage) → non comptée")
            } else {
                val n = prefs.getInt("chutes_$tombe", 0) + 1
                prefs.edit().remove("en_cours").putInt("chutes_$tombe", n).commit()
                Log.w(TAG, "l'écoute précédente ($tombe) n'a pas fini proprement (chute n°$n)")
            }
        }
        fun banni(nom: String) = prefs.getInt("chutes_$nom", 0) >= 2
        // 2026-10-27 (user : « retire tout ce qu'on n'a plus besoin ») : un seul détecteur pour
        //   tous les appareils, 32 et 64 bits — TEN VAD (~0,5 Mo). Silero (ONNX, plantait en 32
        //   bits) et YAMNet (MediaPipe) sont retirés de l'appli (~33 Mo).
        try {
            if (detecteur == null) {
                if (banni("tenvad")) {
                    Log.w(TAG, "détecteur TEN VAD tombé deux fois sur cet appareil → pas d'écoute")
                    return
                }
                val v = TenVad.creer() ?: run {
                    Log.w(TAG, "détecteur TEN VAD indisponible sur cet appareil → pas d'écoute")
                    return
                }
                vadTen = v
                tailleTrame = TenVad.TRAME; attributionMs = 0.0; minTrames = 5
                detecteur = { t -> v.voix(t) }
                nomDetecteur = "tenvad"
                Log.i(TAG, "détecteur de voix : $nomDetecteur")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "détecteur de voix indisponible : ${t.message}")
            prefs.edit().putInt("chutes_tenvad", 2).commit()
            return
        }
        prefs.edit().putString("en_cours", nomDetecteur).commit()
        prefsMarque = prefs
        lecteur = player
        trameN = 0; acc = 0.0; accN = 0; phase = 0.0; dernierPts = Long.MIN_VALUE; journalise = false
        actif = true
        // 2026-10-22 (user : « ne pas afficher les sous-titres tant que ce n'est pas calé ») :
        //   le calque reste vide jusqu'au premier calage trouvé (ou la fin de l'écoute).
        SyncSousTitres.enAttenteCalage = SyncSousTitres.masquerProchainCalage
        SyncSousTitres.masquerProchainCalage = true
        val pos = player.currentPosition
        Log.i(TAG, "écoute des voix lancée à ${pos / 1000} s (${repliques.size} répliques)")
        // 2026-10-22 (user : « préciser que ça peut être un peu long à débuter ») : le calage attend
        //   les premières répliques ; un long générique le retarde d'autant.
        // 2026-10-22 (user) : vérification SILENCIEUSE pour le français OpenSubtitles automatique ;
        //   message seulement pour un sous-titre téléchargé (masqué le temps du calage).
        if (SyncSousTitres.enAttenteCalage)
            Toast.makeText(context, "Calage des sous-titres en cours…", Toast.LENGTH_SHORT).show()

        // 2026-10-27 (user : « il faut préparer l'essai ») — ESSAI : écoute en avance avec TEN VAD.
        //   Le 1er calage vient de là (quelques secondes) ; l'écoute en direct (au rythme du film)
        //   continue À CÔTÉ pour comparer dans le journal (« COMPARAISON ») et garde le réglage
        //   définitif (dérive) à ~8 min.
        avanceApplique = false
        avanceEnCours = false
        val video = SyncSousTitres.videoCourante
        if (video != null && video.drmType == null) {
            val ctxApp = context.applicationContext
            avanceEnCours = true
            EcouteAvance.lancer(ctxApp, video, pos) { r ->
                avanceEnCours = false
                if (r == null || !actif) return@lancer
                portee.launch {
                    val reglage = withContext(Dispatchers.Default) {
                        // 2026-10-27 (user, Spider-Man : « le décalage est complètement raté ») :
                        //   sous-titre fait pour 25 images/s sur une vidéo à 23,976 (rapport 0,959,
                        //   ~15 s de glissement sur 6 min). Sans dérive autorisée, l'écoute en avance
                        //   a pris un faux compromis (11,8 s, score 0,27) au lieu de ~33,6 s. Avec
                        //   6 min de son, la dérive se mesure bien : on la laisse libre, comme le
                        //   réglage définitif.
                        RecalageAuto.alignerSurVoix(r.voix, r.silences, repliques, rapportLibre = true)
                    }
                    Log.i(TAG, "COMPARAISON avance/TEN VAD en ${r.dureeReelleMs / 1000} s : " +
                        (reglage?.let { "${it.first} ms, rapport ${it.second}" } ?: "aucun calage sûr"))
                    if (reglage != null && actif) {
                        val (d, rr) = reglage
                        reglageAvance = reglage
                        avanceApplique = true
                        SyncSousTitres.decalageMs = d
                        SyncSousTitres.rapport = rr
                        SyncSousTitres.enAttenteCalage = false
                        SyncSousTitres.surCalageEchoue = null
                        // 2026-10-27 (user : « toujours le message calé 0 seconde ») : rien à
                        //   annoncer quand le sous-titre était déjà juste.
                        if (kotlin.math.abs(d) >= 150) surResultat("Sous-titres calés (${SyncSousTitres.libelleDecalage()})")
                    }
                }
            }
        }

        tache = portee.launch {
            val debut = System.currentTimeMillis()
            var premierFait = false
            var precedent: Pair<Long, Double>? = null
            var references: List<Pair<String, List<RecalageAuto.Replique>>>? = null
            while (isActive) {
                delay(15_000)
                val (idx, zeros, couvertS) = synchronized(verrou) {
                    val pleins = total.keys.filter { (total[it] ?: 0) >= minTrames }
                    val uns = pleins.filter { (parle[it] ?: 0) * 2 >= (total[it] ?: 0) }
                    val sil = pleins.filter { (parle[it] ?: 0) * 4 < (total[it] ?: 0) }
                    Triple(uns.sorted().toIntArray(), sil.sorted().toIntArray(), total.size * PAS / 1000)
                }
                val ecoule = (System.currentTimeMillis() - debut) / 1000
                Log.d(TAG, "écouté ${couvertS} s, ${idx.size} tranches de voix")
                val definitif = couvertS >= 480 || ecoule >= 900
                if (!premierFait && idx.size < 60 && !definitif) continue
                // 2026-10-22 (user : « OpenSubtitles se cale instantanément, aide-toi de ça ») :
                //   un AUTRE sous-titre de la vidéo (le français OpenSubtitles trouvé par l'identifiant
                //   du film) que les voix confirment déjà calé sert de RÉFÉRENCE : le recalage
                //   fichier contre fichier (tout le film, dérive comprise) est bien plus sûr que
                //   les voix seules.
                if (references == null) references = withContext(Dispatchers.IO) { referencesCandidates(source) }
                var surReference: Pair<Long, Double>? = null
                var nomReference = ""
                var referenceSansAccord = false
                for ((nom, rep) in references.orEmpty()) {
                    val sc = RecalageAuto.scoreVoix(idx, zeros, rep)
                    Log.d(TAG, "référence « $nom » : score voix sans réglage ${"%.3f".format(sc)}")
                    if (sc >= 0.5) {
                        surReference = withContext(Dispatchers.Default) { RecalageAuto.aligner(rep, repliques) }
                        nomReference = nom
                        if (surReference != null) break
                        // Référence confirmée par les voix, mais aucun calage possible dessus :
                        //   ce sous-titre ne correspond pas à cette version du film.
                        referenceSansAccord = true
                    }
                }
                if (surReference == null && referenceSansAccord) { echec("aucun accord avec la référence"); break }
                if (surReference != null) {
                    SyncSousTitres.surCalageEchoue = null
                    val (d, r) = surReference
                    SyncSousTitres.decalageMs = d
                    SyncSousTitres.rapport = r
                    SyncSousTitres.enAttenteCalage = false
                    Log.i(TAG, "calé sur la référence « $nomReference » : $d ms, rapport $r → écoute arrêtée")
                    if (d != 0L || r != 1.0) surResultat("Sous-titres calés (${SyncSousTitres.libelleDecalage()}" +
                        (if (r != 1.0) ", dérive ${SyncSousTitres.libelleDerive()})" else ")"))
                    break
                }
                val reglage = withContext(Dispatchers.Default) {
                    RecalageAuto.alignerSurVoix(idx, zeros, repliques, rapportLibre = definitif)
                }
                val confirme = reglage != null && precedent != null &&
                    precedent!!.second == reglage.second && kotlin.math.abs(precedent!!.first - reglage.first) <= 400
                precedent = reglage
                if (reglage != null && (confirme || definitif)) {
                    Log.i(TAG, "COMPARAISON direct/$nomDetecteur après ${couvertS} s écoutées : " +
                        "${reglage.first} ms, rapport ${reglage.second}" +
                        (reglageAvance?.let { " (avance/TEN VAD : ${it.first} ms, rapport ${it.second})" } ?: ""))
                }
                // Essai : le calage en avance est déjà appliqué → l'écoute directe ne fait que
                //   comparer, sauf pour le réglage définitif (dérive) qui reste le sien.
                if (reglage != null && confirme && !definitif && avanceApplique) {
                    premierFait = true
                } else if (avanceEnCours && !definitif) {
                    // 2026-10-27 (user, Overgeared : « un peu le bordel jusqu'à 1 min 58 ») : avec
                    //   1 min de son seulement, l'écoute directe a appliqué un FAUX calage (−66 s,
                    //   « confirmé » deux fois) avant que l'écoute en avance (6 min, juste : 0,2 s)
                    //   ne finisse. Tant que l'avance tourne, l'écoute directe n'applique rien.
                    continue
                } else if (reglage != null && (confirme || definitif)) {
                    val (d, r) = reglage
                    val change = kotlin.math.abs(d - SyncSousTitres.decalageMs) >= 150 || r != SyncSousTitres.rapport
                    SyncSousTitres.decalageMs = d
                    SyncSousTitres.rapport = r
                    SyncSousTitres.enAttenteCalage = false
                    SyncSousTitres.surCalageEchoue = null
                    if (change) surResultat("Sous-titres calés (${SyncSousTitres.libelleDecalage()}" +
                        (if (r != 1.0) ", dérive ${SyncSousTitres.libelleDerive()})" else ")"))
                    premierFait = true
                }
                // ~3 min écoutées, beaucoup de voix, et toujours aucun calage sûr : ce sous-titre
                //   ne correspond pas à cette version du film.
                if (!premierFait && (definitif || (couvertS >= 180 && idx.size >= 150))) { echec("aucun calage sûr après ${couvertS} s"); break }
                if (definitif) {
                    Log.i(TAG, "réglage définitif : ${SyncSousTitres.decalageMs} ms, rapport ${SyncSousTitres.rapport} → écoute arrêtée")
                    break
                }
            }
            arreter()
        }
    }

    /** Calage impossible : on le signale au lecteur (qui passe au sous-titre suivant). */
    private fun echec(raison: String) {
        Log.i(TAG, "calage impossible ($raison)")
        SyncSousTitres.surCalageEchoue?.let { SyncSousTitres.surCalageEchoue = null; it() }
    }

    /** Nouvelle vidéo : les voix écoutées ne valent plus. */
    fun oublier() {
        arreter()
        EcouteAvance.arreter()
        reglageAvance = null
        synchronized(verrou) { parle.clear(); total.clear() }
    }

    /** Autres sous-titres de la vidéo (hors celui affiché), lus depuis leur fichier. */
    private fun referencesCandidates(source: String?): List<Pair<String, List<RecalageAuto.Replique>>> =
        SyncSousTitres.videoCourante?.subtitles.orEmpty()
            .filter { it.file != source && it.file.substringBefore('#') != source }
            .mapNotNull { st ->
                RecalageAuto.repliquesDe(st.file, SyncSousTitres.videoCourante?.headers)
                    ?.takeIf { it.size >= 30 }?.let { st.label to it }
            }.take(3)

    private var nomDetecteur = ""
    @Volatile private var avanceApplique = false
    @Volatile private var avanceEnCours = false
    @Volatile private var reglageAvance: Pair<Long, Double>? = null
    @Volatile private var prefsMarque: android.content.SharedPreferences? = null

    fun arreter() {
        // Arrêt propre → la marque « écoute en cours » est retirée, et le détecteur blanchi.
        prefsMarque?.let { p -> p.edit().remove("en_cours").putInt("chutes_$nomDetecteur", 0).apply() }
        prefsMarque = null
        actif = false
        // Écoute terminée sans calage : le sous-titre s'affiche tel quel.
        SyncSousTitres.enAttenteCalage = false
        tache?.cancel()
        tache = null
        try { ecouteur?.let { lecteur?.removeListener(it) } } catch (_: Throwable) {}
        ecouteur = null
        lecteur = null
    }
}
