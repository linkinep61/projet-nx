package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate
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
 *  - Ramené en mono 16 kHz, découpé en trames de 32 ms, le détecteur de voix Silero (réseau de
 *    neurones, sait écarter la musique) dit « parle / ne parle pas ». Il ne comprend pas les mots : la langue n'a pas d'importance.
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
    // 2026-10-22 : trame de 512 échantillons (32 ms) pour Silero, 3 200 (200 ms) pour YAMNet.
    private val trame = ShortArray(3200)
    @Volatile private var tailleTrame = 512
    @Volatile private var attributionMs = 0.0
    @Volatile private var minTrames = 5
    private val executeur = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val enFile = java.util.concurrent.atomic.AtomicInteger(0)
    private var trameN = 0
    private var trameDebutMs = 0.0
    private var vadSilero: VadSilero? = null
    private var vadYamnet: com.konovalov.vad.yamnet.VadYamnet? = null
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
        if (UserPreferences.modeLeger) { Log.d(TAG, "mode léger : écoute désactivée"); return }
        // Silero exige Android 7 (l'appli démarre dès Android 6 : rien sur les plus anciens).
        if (android.os.Build.VERSION.SDK_INT < 24) return
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
        try {
            if (detecteur == null) {
                if (android.os.Process.is64Bit() && !banni("silero")) {
                    val v = VadSilero(
                        context.applicationContext,
                        sampleRate = SampleRate.SAMPLE_RATE_16K,
                        frameSize = FrameSize.FRAME_SIZE_512,
                        mode = Mode.NORMAL,
                        silenceDurationMs = 300,
                        speechDurationMs = 50,
                    )
                    vadSilero = v
                    tailleTrame = 512; attributionMs = 0.0; minTrames = 5
                    detecteur = { t -> v.isSpeech(t) }
                } else if (!banni("yamnet")) {
                    // 2026-10-22 (user : « t'as beau avoir coupé l'écoute, faut que tu trouves une
                    //   solution » — TV en ARM 32 bits) : le moteur ONNX de Silero y plante en code
                    //   natif (SIGBUS, alignement mémoire). On prend YAMNet (Google, TensorFlow Lite,
                    //   stable en 32 bits) : il classe chaque fenêtre de ~1 s (voix, musique,
                    //   bruits…). Une décision toutes les 200 ms, attribuée au CENTRE de la fenêtre
                    //   analysée (fin − 0,49 s).
                    val y = com.konovalov.vad.yamnet.VadYamnet(
                        context.applicationContext,
                        sampleRate = com.konovalov.vad.yamnet.config.SampleRate.SAMPLE_RATE_16K,
                        frameSize = com.konovalov.vad.yamnet.config.FrameSize.FRAME_SIZE_975,
                        mode = com.konovalov.vad.yamnet.config.Mode.NORMAL,
                    )
                    vadYamnet = y
                    tailleTrame = 3200; attributionMs = 200.0 - 487.0; minTrames = 1
                    val voix = Regex("""(?i)speech|conversation|narration|monologue|babbling""")
                    detecteur = { t -> voix.containsMatchIn(y.classifyAudio(t).label) }
                } else {
                    Log.w(TAG, "aucun détecteur de voix sûr sur cet appareil → pas d'écoute")
                    return
                }
                nomDetecteur = if (vadSilero != null) "silero" else "yamnet"
                Log.i(TAG, "détecteur de voix : $nomDetecteur")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "détecteur de voix indisponible : ${t.message}")
            val nom = if (android.os.Process.is64Bit() && !banni("silero")) "silero" else "yamnet"
            prefs.edit().putInt("chutes_$nom", 2).commit()
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
            Toast.makeText(context, "Calage des sous-titres en cours… (environ 30 s après les premières paroles)", Toast.LENGTH_SHORT).show()

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
