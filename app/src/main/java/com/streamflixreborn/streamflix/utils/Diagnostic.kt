package com.streamflixreborn.streamflix.utils

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.util.Log
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.streamflixreborn.streamflix.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 2026-10-26 (user : « fais une version avec un report de crash, prends un maximum de relevés ») :
 *   VERSION DE DIAGNOSTIC uniquement (BuildConfig.DIAGNOSTIC, compilée avec -Pdiag). Rien ne
 *   tourne dans les versions normales.
 *
 *   Relevés : appareil + mémoire toutes les 5 s (Java, natif, système, PSS toutes les 30 s),
 *   alertes mémoire Android, écrans ouverts, plantages (pile complète), figeages > 5 s (pile du
 *   thread principal), cause officielle de la fermeture précédente (ApplicationExitInfo, Android 11+)
 *   et derniers avertissements/erreurs du journal de l'appli.
 *
 *   Pourquoi pas le simple gestionnaire de plantage : une appli TUÉE par Android faute de mémoire
 *   ne déclenche AUCUNE exception (c'est pour ça qu'aucun rapport n'était remonté en août). On
 *   détecte donc aussi « la session précédente s'est arrêtée alors que l'appli était à l'écran ».
 *
 *   Envoi : au démarrage suivant, une fenêtre propose d'envoyer le rapport. Il est mis en ligne sur
 *   paste.rs (texte, lien non répertorié) puis l'appli affiche un QR code + le lien ; sur téléphone,
 *   un bouton ouvre Telegram avec le lien. Si l'envoi échoue, le rapport s'affiche à l'écran.
 */
object Diagnostic {
    private const val TAG = "Diagnostic"
    private const val MAX_LIGNES = 600
    private lateinit var app: Application
    private val heure = SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE)
    private val lignes = ArrayDeque<String>()
    @Volatile private var ecran = "démarrage"
    @Volatile private var activitesVisibles = 0
    @Volatile private var dernierTrim = "-"
    @Volatile private var rapportEnAttente: String? = null
    @Volatile private var fenetreMontree = false
    private var demarreA = 0L

    private val dossier get() = File(app.filesDir, "diagnostic").apply { mkdirs() }
    private val fJournal get() = File(dossier, "journal_en_cours.txt")
    private val fPrecedent get() = File(dossier, "journal_precedent.txt")
    private val fCrash get() = File(dossier, "plantage.txt")
    private val fEtat get() = File(dossier, "etat.txt")
    private val fRapport get() = File(dossier, "rapport_a_envoyer.txt")

    fun demarrer(application: Application) {
        app = application
        demarreA = System.currentTimeMillis()
        try {
            preparerRapportDeLaSessionPrecedente()
        } catch (t: Throwable) {
            Log.w(TAG, "rapport précédent illisible", t)
        }
        noter("===== DÉMARRAGE ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) =====")
        infosAppareil().lines().forEach { noter("  $it") }
        ecrireEtat("premier_plan_au_demarrage")
        installerGestionnairePlantage()
        installerAlertesMemoire()
        installerSuiviEcrans()
        lancerReleves()
        lancerSurveillanceFigeage()
    }

    // ---------------------------------------------------------------- journal

    @Synchronized
    fun noter(texte: String) {
        val l = "${heure.format(Date())} $texte"
        lignes.addLast(l)
        while (lignes.size > MAX_LIGNES) lignes.removeFirst()
        Log.i(TAG, texte)
    }

    @Synchronized
    private fun sauverJournal() {
        try {
            fJournal.writeText(lignes.joinToString("\n"))
        } catch (_: Throwable) {}
    }

    private fun ecrireEtat(etat: String) {
        try { fEtat.writeText("$etat|${System.currentTimeMillis()}|$ecran") } catch (_: Throwable) {}
    }

    // ---------------------------------------------------------------- relevés

    private fun mo(octets: Long) = "${octets / (1024 * 1024)} Mo"

    private fun infosAppareil(): String {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val dm = app.resources.displayMetrics
        val stockage = try {
            val s = StatFs(app.filesDir.absolutePath); mo(s.availableBytes) + " libres / " + mo(s.totalBytes)
        } catch (_: Throwable) { "?" }
        val webview = try {
            app.packageManager.getPackageInfo(
                if (Build.VERSION.SDK_INT >= 26) android.webkit.WebView.getCurrentWebViewPackage()?.packageName ?: "com.google.android.webview"
                else "com.google.android.webview", 0
            ).versionName
        } catch (_: Throwable) { "?" }
        val tv = (app.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
        // currentProvider n'est PAS lu ici : sa lecture peut charger toutes les sources (mémoire).
        val leger = try { UserPreferences.modeLeger.toString() } catch (_: Throwable) { "?" }
        return """
            Appareil : ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
            Processeur : ${Build.SUPPORTED_ABIS.joinToString()} — ${Runtime.getRuntime().availableProcessors()} cœurs
            Type : ${if (tv) "TV" else "téléphone/tablette"} — disposition appli : ${BuildConfig.APP_LAYOUT.ifBlank { "auto" }}
            Mémoire appareil : ${mo(mi.totalMem)} au total, ${mo(mi.availMem)} libres, seuil critique ${mo(mi.threshold)}
            Mémoire allouée à l'appli : ${am.memoryClass} Mo (grande : ${am.largeMemoryClass} Mo), appareil faible mémoire : ${am.isLowRamDevice}
            Java max : ${mo(Runtime.getRuntime().maxMemory())}
            Écran : ${dm.widthPixels}x${dm.heightPixels}, densité ${dm.densityDpi} dpi
            Stockage interne : $stockage
            WebView : $webview
            Mode léger : $leger
        """.trimIndent()
    }

    private fun releve(avecPss: Boolean): String {
        val rt = Runtime.getRuntime()
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val pss = if (avecPss) try {
            val m = Debug.MemoryInfo(); Debug.getMemoryInfo(m)
            " | réel(PSS) ${m.totalPss / 1024} Mo (java ${m.dalvikPss / 1024}, natif ${m.nativePss / 1024}, autre ${m.otherPss / 1024})"
        } catch (_: Throwable) { "" } else ""
        return "MÉM java ${mo(rt.totalMemory() - rt.freeMemory())}/${mo(rt.maxMemory())}" +
            " | natif ${mo(Debug.getNativeHeapAllocatedSize())}" +
            " | système libre ${mo(mi.availMem)}${if (mi.lowMemory) " (BAS !)" else ""}" +
            " | threads ${Thread.activeCount()} | écran $ecran" +
            (if (activitesVisibles > 0) "" else " | arrière-plan") + pss
    }

    private fun lancerReleves() {
        Thread({
            var n = 0
            while (true) {
                try {
                    Thread.sleep(5_000L)
                    n++
                    noter(releve(avecPss = n % 6 == 0))
                    ecrireEtat(if (activitesVisibles > 0) "premier_plan" else "arriere_plan")
                    sauverJournal()
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (_: Throwable) {}
            }
        }, "DiagReleves").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    private fun installerAlertesMemoire() {
        app.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                dernierTrim = when (level) {
                    ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE (mémoire un peu juste)"
                    ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW (mémoire basse)"
                    ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL (mémoire critique)"
                    ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN (appli masquée)"
                    ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
                    ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE"
                    ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE (prochaine à être tuée)"
                    else -> "niveau $level"
                }
                noter("ALERTE MÉMOIRE ANDROID : $dernierTrim — ${releve(true)}")
                sauverJournal()
            }
            override fun onLowMemory() {
                noter("ALERTE MÉMOIRE ANDROID : onLowMemory — ${releve(true)}")
                sauverJournal()
            }
            override fun onConfigurationChanged(newConfig: Configuration) {}
        })
    }

    // ---------------------------------------------------------------- écrans

    private val suiviFragments = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
            // Le conteneur de navigation n'est pas un écran : on garde le vrai écran affiché.
            if (f is androidx.navigation.fragment.NavHostFragment) return
            ecran = f.javaClass.simpleName
            noter("écran → ${f.javaClass.simpleName}")
        }
    }

    private fun installerSuiviEcrans() {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) {
                noter("activité créée : ${a.javaClass.simpleName}")
                (a as? FragmentActivity)?.supportFragmentManager
                    ?.registerFragmentLifecycleCallbacks(suiviFragments, true)
            }
            override fun onActivityStarted(a: Activity) { activitesVisibles++ }
            override fun onActivityResumed(a: Activity) {
                ecran = a.javaClass.simpleName
                noter("activité au premier plan : ${a.javaClass.simpleName}")
                ecrireEtat("premier_plan")
                // Fenêtre d'envoi : sur la 1re activité principale, quelques secondes après.
                val r = rapportEnAttente
                if (r != null && !fenetreMontree && a.javaClass.simpleName.startsWith("Main")) {
                    fenetreMontree = true
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!a.isFinishing) proposerEnvoi(a, r)
                    }, 2_500L)
                }
            }
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {
                activitesVisibles = (activitesVisibles - 1).coerceAtLeast(0)
                if (activitesVisibles == 0) {
                    noter("appli passée en arrière-plan")
                    ecrireEtat("arriere_plan")
                    sauverJournal()
                }
            }
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {
                noter("activité fermée : ${a.javaClass.simpleName}${if (a.isFinishing) " (quittée)" else ""}")
            }
        })
    }

    // ---------------------------------------------------------------- plantages et figeages

    private fun installerGestionnairePlantage() {
        val precedent = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, erreur ->
            try {
                val sw = java.io.StringWriter()
                sw.append("PLANTAGE le ${heure.format(Date())} sur le thread « ${thread.name} »\n")
                sw.append("Écran : $ecran — dernière alerte mémoire : $dernierTrim\n")
                try { sw.append(releve(true)).append("\n\n") } catch (_: Throwable) {}
                erreur.printStackTrace(java.io.PrintWriter(sw))
                fCrash.writeText(sw.toString())
                noter("!!! PLANTAGE : ${erreur.javaClass.simpleName}: ${erreur.message}")
                ecrireEtat("plantage")
                sauverJournal()
            } catch (_: Throwable) {}
            precedent?.uncaughtException(thread, erreur)
        }
    }

    /** Thread principal bloqué plus de 5 s → on note sa pile (une fois par blocage). */
    private fun lancerSurveillanceFigeage() {
        val principal = Looper.getMainLooper().thread
        val handler = Handler(Looper.getMainLooper())
        Thread({
            try { Thread.sleep(20_000L) } catch (_: InterruptedException) { return@Thread }
            while (true) {
                try {
                    val repondu = java.util.concurrent.atomic.AtomicBoolean(false)
                    handler.post { repondu.set(true) }
                    var attente = 0L
                    var note = false
                    while (!repondu.get()) {
                        Thread.sleep(500L); attente += 500L
                        if (attente >= 5_000L && !note) {
                            note = true
                            val pile = principal.stackTrace.take(25).joinToString("\n      at ")
                            noter("!!! FIGEAGE > 5 s (écran $ecran) — ${releve(false)}\n      at $pile")
                            sauverJournal()
                        }
                    }
                    if (note) noter("fin du figeage après ${attente / 1000} s")
                    Thread.sleep(1_000L)
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (_: Throwable) {}
            }
        }, "DiagFigeage").apply { isDaemon = true }.start()
    }

    // ---------------------------------------------------------------- rapport de la session précédente

    private fun preparerRapportDeLaSessionPrecedente() {
        val etat = try { fEtat.readText() } catch (_: Throwable) { "" }
        val etatNom = etat.substringBefore('|')
        val etatEcran = etat.split('|').getOrNull(2) ?: "?"
        val etatHeure = etat.split('|').getOrNull(1)?.toLongOrNull()
        val plantage = try { if (fCrash.exists()) fCrash.readText() else null } catch (_: Throwable) { null }
        val sorties = sortiesAndroid()
        val sortieAnormale = sorties.firstOrNull()?.let { it.second } ?: false

        // Journal de la session précédente → fichier « précédent » (le nouveau repart de zéro).
        try {
            fPrecedent.delete()
            if (fJournal.exists()) fJournal.renameTo(fPrecedent)
        } catch (_: Throwable) {}

        val fermeeAuPremierPlan = etatNom.startsWith("premier_plan")
        if (plantage == null && !fermeeAuPremierPlan && !sortieAnormale && !fRapport.exists()) return

        // Un rapport pas encore envoyé (« Plus tard ») est conservé, le nouveau passe devant.
        val ancien = try { if (fRapport.exists()) fRapport.readText() else "" } catch (_: Throwable) { "" }
        val cause = when {
            plantage != null -> "PLANTAGE de l'appli (erreur dans le code, détail ci-dessous)."
            sorties.firstOrNull()?.first?.contains("MANQUE DE MÉMOIRE") == true ->
                "Fermée par Android par MANQUE DE MÉMOIRE."
            fermeeAuPremierPlan ->
                "Arrêtée alors qu'elle était à l'écran, sans erreur dans l'appli : très probablement tuée par Android (mémoire) ou par un plantage interne (WebView, lecteur, pilote vidéo)."
            else -> "Fermeture anormale signalée par Android."
        }
        val sb = StringBuilder()
        sb.append("ONYX — RAPPORT DE DIAGNOSTIC\n")
        sb.append("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) — rapport créé le ${heure.format(Date())}\n\n")
        sb.append("CAUSE PROBABLE : $cause\n")
        sb.append("Dernier écran : $etatEcran")
        if (etatHeure != null) sb.append(" — dernier signe de vie : ${heure.format(Date(etatHeure))}")
        sb.append("\n\n== APPAREIL ==\n").append(infosAppareil()).append("\n")
        if (sorties.isNotEmpty()) {
            sb.append("\n== FERMETURES ENREGISTRÉES PAR ANDROID (récentes d'abord) ==\n")
            sorties.forEach { sb.append(it.first).append("\n") }
        } else if (Build.VERSION.SDK_INT < 30) {
            sb.append("\n(Android ${Build.VERSION.RELEASE} ne donne pas la cause officielle des fermetures — Android 11 minimum.)\n")
        }
        if (plantage != null) sb.append("\n== PLANTAGE ==\n").append(plantage).append("\n")
        val journal = try { if (fPrecedent.exists()) fPrecedent.readLines().takeLast(150) else emptyList() } catch (_: Throwable) { emptyList() }
        if (journal.isNotEmpty()) {
            sb.append("\n== DERNIERS RELEVÉS AVANT LA FERMETURE (${journal.size} lignes) ==\n")
            journal.forEach { sb.append(it).append("\n") }
        }
        sb.append("\n== JOURNAL ANDROID DE L'APPLI (avertissements et erreurs) ==\n").append(journalAndroid()).append("\n")
        if (ancien.isNotBlank()) sb.append("\n\n########## RAPPORT PRÉCÉDENT NON ENVOYÉ ##########\n").append(ancien.take(60_000))

        val rapport = sb.toString().take(200_000)
        try { fRapport.writeText(rapport) } catch (_: Throwable) {}
        try { fCrash.delete() } catch (_: Throwable) {}
        rapportEnAttente = rapport
    }

    /** Fermetures récentes vues par Android 11+ : (texte, anormale ?). */
    private fun sortiesAndroid(): List<Pair<String, Boolean>> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        return try {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.getHistoricalProcessExitReasons(app.packageName, 0, 5)
                // Seulement le processus principal (pas les processus isolés du WebView).
                .filter { it.timestamp < demarreA && it.processName == app.packageName }
                .map { e ->
                    val raison = when (e.reason) {
                        android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "MANQUE DE MÉMOIRE (tuée par Android)"
                        android.app.ApplicationExitInfo.REASON_CRASH -> "PLANTAGE (erreur Java)"
                        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "PLANTAGE NATIF (lecteur, WebView, pilote…)"
                        android.app.ApplicationExitInfo.REASON_ANR -> "FIGEAGE (« l'appli ne répond pas »)"
                        android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "TROP DE RESSOURCES"
                        android.app.ApplicationExitInfo.REASON_SIGNALED -> "TUÉE PAR UN SIGNAL (souvent manque de mémoire)"
                        android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "fermée par l'appli elle-même"
                        android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "fermée par l'utilisateur"
                        android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "arrêtée par l'utilisateur"
                        android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "changement d'autorisation"
                        android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "un composant dont elle dépend s'est arrêté"
                        android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "échec au démarrage"
                        android.app.ApplicationExitInfo.REASON_OTHER -> "autre (système)"
                        else -> "inconnue (${e.reason})"
                    }
                    val premierPlan = e.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
                    val anormale = premierPlan && e.reason in setOf(
                        android.app.ApplicationExitInfo.REASON_LOW_MEMORY, android.app.ApplicationExitInfo.REASON_CRASH,
                        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE, android.app.ApplicationExitInfo.REASON_ANR,
                        android.app.ApplicationExitInfo.REASON_SIGNALED, android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
                        android.app.ApplicationExitInfo.REASON_OTHER, android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED
                    )
                    var texte = "- ${heure.format(Date(e.timestamp))} : $raison" +
                        " — ${if (premierPlan) "à l'écran" else "en arrière-plan"}" +
                        " — mémoire ${e.pss / 1024} Mo (PSS), ${e.rss / 1024} Mo (RSS)" +
                        (e.description?.let { " — « $it »" } ?: "")
                    if (e.reason == android.app.ApplicationExitInfo.REASON_ANR || e.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE) {
                        val trace = try {
                            e.traceInputStream?.bufferedReader()?.readLines()?.take(80)?.joinToString("\n    ")
                        } catch (_: Throwable) { null }
                        if (!trace.isNullOrBlank()) texte += "\n    $trace"
                    }
                    texte to anormale
                }
        } catch (_: Throwable) { emptyList() }
    }

    /** Avertissements/erreurs encore présents dans le journal Android pour notre appli (même UID). */
    private fun journalAndroid(): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", "1500", "*:W"))
        val l = p.inputStream.bufferedReader().readLines().takeLast(250)
        p.destroy()
        // Pas d'adresses complètes dans un rapport mis en ligne : on coupe les paramètres d'URL.
        l.joinToString("\n") { it.replace(Regex("\\?[^\\s\"']+"), "?…") }
    } catch (t: Throwable) { "illisible : ${t.message}" }

    // ---------------------------------------------------------------- envoi

    private fun proposerEnvoi(a: Activity, rapport: String) {
        try {
            AlertDialog.Builder(a)
                .setTitle("Rapport de diagnostic")
                .setMessage("ONYX s'est fermée de façon inattendue la dernière fois.\n\nEnvoyer le rapport au développeur ? Il contient seulement des informations techniques (appareil, mémoire, erreurs).")
                .setPositiveButton("Envoyer") { _, _ -> envoyer(a, rapport) }
                .setNeutralButton("Plus tard", null)
                .setNegativeButton("Ignorer") { _, _ ->
                    try { fRapport.delete() } catch (_: Throwable) {}
                    rapportEnAttente = null
                }
                .show()
        } catch (t: Throwable) {
            Log.w(TAG, "fenêtre d'envoi impossible", t)
        }
    }

    private fun envoyer(a: Activity, rapport: String) {
        android.widget.Toast.makeText(a, "Envoi du rapport…", android.widget.Toast.LENGTH_SHORT).show()
        Thread({
            val lien = try {
                val c = URL("https://paste.rs/").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                c.outputStream.use { it.write(rapport.toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                val corps = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText()?.trim()
                c.disconnect()
                if (code in 200..299 && corps != null && corps.startsWith("https://")) corps else null
            } catch (t: Throwable) {
                Log.w(TAG, "envoi paste.rs échoué", t); null
            }
            Handler(Looper.getMainLooper()).post {
                if (a.isFinishing) return@post
                if (lien != null) {
                    try { fRapport.delete() } catch (_: Throwable) {}
                    rapportEnAttente = null
                    noter("rapport envoyé : $lien")
                    montrerLien(a, lien)
                } else {
                    montrerTexte(a, rapport)
                }
            }
        }, "DiagEnvoi").start()
    }

    private fun montrerLien(a: Activity, lien: String) {
        val dp = a.resources.displayMetrics.density
        val boite = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt(), 0)
        }
        try {
            boite.addView(ImageView(a).apply {
                setImageBitmap(QrUtils.generate(lien, 600))
                val t = (220 * dp).toInt()
                layoutParams = LinearLayout.LayoutParams(t, t)
            })
        } catch (_: Throwable) {}
        boite.addView(TextView(a).apply {
            text = "Scannez ce QR code avec votre téléphone et envoyez le lien au développeur sur Telegram :\n\n$lien"
            gravity = Gravity.CENTER
            setTextIsSelectable(true)
            setPadding(0, (12 * dp).toInt(), 0, 0)
        })
        val telegram = listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram")
            .firstOrNull { try { a.packageManager.getPackageInfo(it, 0); true } catch (_: Throwable) { false } }
        val d = AlertDialog.Builder(a)
            .setTitle("Rapport envoyé ✅")
            .setView(boite)
            .setPositiveButton("Fermer", null)
        if (telegram != null) {
            d.setNeutralButton("Ouvrir Telegram") { _, _ ->
                try {
                    a.startActivity(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        setPackage(telegram)
                        putExtra(Intent.EXTRA_TEXT, "Rapport ONYX : $lien")
                    })
                } catch (_: Throwable) {}
            }
        }
        d.show()
    }

    private fun montrerTexte(a: Activity, rapport: String) {
        val dp = a.resources.displayMetrics.density
        val texte = TextView(a).apply {
            text = rapport.take(20_000)
            textSize = 11f
            setTextIsSelectable(true)
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), 0)
        }
        AlertDialog.Builder(a)
            .setTitle("Envoi impossible — prenez une photo de l'écran")
            .setView(ScrollView(a).apply { addView(texte) })
            .setPositiveButton("Fermer", null)
            .show()
    }
}
