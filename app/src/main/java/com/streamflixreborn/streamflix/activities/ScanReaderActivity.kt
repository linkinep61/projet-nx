package com.streamflixreborn.streamflix.activities

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.ScansProvider
import kotlinx.coroutines.launch

/**
 * 2026-10-20 — Lecteur de scans (profil « Scans » de l'onglet Animés).
 *
 *  • Pages à la suite, défilement vertical (comme un webtoon / la liseuse du site).
 *  • Téléphone : pincer pour zoomer, double appui = zoom ×2 / retour, appui simple = barres.
 *  • Télécommande : ▲▼ = défiler d'un écran, ◀▶ = chapitre précédent / suivant, OK = barres.
 *  • Reprise : la page où l'on s'est arrêté est retenue par chapitre, et le dernier chapitre lu par série.
 *
 * Ouvert À LA PLACE du lecteur vidéo : les fragments lecteur appellent [redirigerSiScan] dès leur
 * création, qui lance cette activité et referme le lecteur vidéo.
 */
class ScanReaderActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_CHAPITRE = "chapitre"
        private const val EXTRA_SERIE = "serie"
        private const val EXTRA_SERIE_TITRE = "serieTitre"
        private const val EXTRA_EPISODE = "episode"
        private const val PREFS = "scans_lecture"

        fun estScan(id: String?) = id?.startsWith(ScansProvider.ID_PREFIX) == true

        fun ouvrir(context: Context, chapitreId: String, serieId: String?, serieTitre: String?,
                   episode: Video.Type.Episode? = null) {
            context.startActivity(
                Intent(context, ScanReaderActivity::class.java)
                    .putExtra(EXTRA_CHAPITRE, chapitreId)
                    .putExtra(EXTRA_SERIE, serieId)
                    .putExtra(EXTRA_SERIE_TITRE, serieTitre)
                    .putExtra(EXTRA_EPISODE, episode as java.io.Serializable?)
            )
        }

        /** Appelé en tout début de création des fragments lecteur : true = c'est un scan, on a
         *  lancé la liseuse et le lecteur vidéo se referme tout seul (il ne doit rien initialiser). */
        fun redirigerSiScan(fragment: Fragment, id: String, videoType: Video.Type?): Boolean {
            if (!estScan(id)) return false
            val ep = videoType as? Video.Type.Episode
            ouvrir(fragment.requireContext(), id, ep?.tvShow?.id, ep?.tvShow?.title, ep)
            Handler(Looper.getMainLooper()).post {
                runCatching { fragment.findNavController().popBackStack() }
            }
            return true
        }

        fun dernierChapitreLu(context: Context, serieId: String): String? =
            context.getSharedPreferences(PREFS, MODE_PRIVATE).getString("dernier_$serieId", null)
    }

    private lateinit var liste: RecyclerView
    private lateinit var conteneurZoom: FrameLayout
    private lateinit var barreHaut: LinearLayout
    private lateinit var barreBas: LinearLayout
    /** 2026-10-24 (demande d'abonnés) : barre de progression pour sauter vite dans le chapitre. */
    private lateinit var barreCurseur: LinearLayout
    private lateinit var basConteneur: LinearLayout
    private lateinit var curseur: android.widget.SeekBar
    private lateinit var curseurTotal: TextView
    private lateinit var curseurPage: TextView
    private var curseurTire = false
    private lateinit var titreVue: TextView
    private lateinit var pageVue: TextView
    private lateinit var chargement: ProgressBar
    private lateinit var btnPrec: TextView
    private lateinit var btnSuiv: TextView
    private lateinit var btnZoomPlus: TextView
    private lateinit var radioBtn: TextView

    private val adaptateur = PagesAdapter()
    private var chapitre: ScansProvider.Chapitre? = null
    private var serieId: String? = null
    private var serieTitre: String? = null
    /** Épisode (chapitre) tel que la fiche l'a ouvert : affiche, saison, numéro — pour « Continuer à regarder ». */
    private var episodeRef: Video.Type.Episode? = null
    /** Numéro du chapitre affiché (suit ◀ / ▶ depuis celui de [episodeRef]). */
    private var numeroCourant = 0
    private var barresVisibles = true
    private var echelle = 1f
    private var radioLanceeIci = false
    private val estTele by lazy {
        packageManager.hasSystemFeature("android.software.leanback")
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 2026-10-23 (user : « 395 Mo, on gaspille quelque part ») : en entrant dans le lecteur, on vide
        //   le cache d'images en mémoire (jaquettes de l'accueil gardées « au cas où ») — mesuré sur
        //   l'Oppo : ~50 Mo rendus. Elles se rechargent depuis le disque au retour à l'accueil.
        runCatching { Glide.get(this).clearMemory() }
        requestedOrientation = if (estTele) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        WindowCompat.getInsetsController(window, window.decorView).run {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        // 2026-10-23 (user : « la veille de la TV se lance pendant que je lis des scans ») : comme pour
        //   une vidéo, l'écran reste allumé tant qu'un chapitre est ouvert (ni Mode Ambiant ni mise en
        //   veille) ; Android retire ce réglage tout seul quand on quitte le lecteur.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        serieId = intent.getStringExtra(EXTRA_SERIE)
        serieTitre = intent.getStringExtra(EXTRA_SERIE_TITRE)
        @Suppress("DEPRECATION")
        episodeRef = intent.getSerializableExtra(EXTRA_EPISODE) as? Video.Type.Episode
        numeroCourant = episodeRef?.number ?: 0

        val racine = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        liste = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ScanReaderActivity)
            adapter = adaptateur
            // 2026-10-23 (boîtier 2 Go qui plante en ouvrant One Piece) : en mode léger, 1 seule page
            //   gardée hors écran au lieu de 4.
            // 2026-10-23 (box qui plante sur les scans, mode léger éteint) : 2 pages hors écran au lieu
            //   de 4 pour tout le monde.
            setItemViewCacheSize(if (com.streamflixreborn.streamflix.utils.UserPreferences.modeLeger) 1 else 2)
            isFocusable = true
            isFocusableInTouchMode = true
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = majPage()
            })
        }
        // Largeur de lecture : tout l'écran en portrait ; sur un écran large (TV, téléphone couché)
        // on centre une colonne pour que la page reste lisible sans défiler à l'infini.
        conteneurZoom = FrameLayout(this)
        conteneurZoom.addView(liste, FrameLayout.LayoutParams(largeurColonne(), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL))
        racine.addView(conteneurZoom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        chargement = ProgressBar(this).apply { isIndeterminate = true }
        racine.addView(chargement, FrameLayout.LayoutParams(dp(48f).toInt(), dp(48f).toInt(), Gravity.CENTER))

        // ── barre du haut : retour · titre · page ──
        barreHaut = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f).toInt(), dp(28f).toInt(), dp(16f).toInt(), dp(12f).toInt())
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xE6000000.toInt(), 0x00000000))
        }
        val retour = bouton("←") { finish() }
        titreVue = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 16f; maxLines = 2
            setPadding(dp(8f).toInt(), 0, dp(8f).toInt(), 0)
        }
        pageVue = TextView(this).apply { setTextColor(0xFFDDDDDD.toInt()); textSize = 14f }
        // 2026-10-20 (user) : la radio est disponible PENDANT la lecture des scans (et seulement là :
        //   si c'est la liseuse qui l'a lancée, elle s'arrête quand on la quitte).
        // 2026-10-23 (user : « problème de positionnement des touches sur mobile ») : écran étroit →
        //   libellés courts, sinon « Précédent / Suivant » sont écrasés en fines barres verticales.
        val etroit = resources.configuration.screenWidthDp < 600
        radioBtn = bouton(if (etroit) "♪" else "♪ Radio") {
            radioLanceeIci = true
            runCatching { com.streamflixreborn.streamflix.utils.RadioPickerDialog.show(this, this) }
                .onFailure { toast("Radio indisponible : ${it.message}") }
        }
        barreHaut.addView(retour)
        barreHaut.addView(titreVue, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        barreHaut.addView(pageVue)

        racine.addView(barreHaut, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // ── barre du bas : chapitre précédent / suivant ──
        // 2026-10-24 (user : « que tout fasse un tout, pas un truc aussi large ») : un seul petit panneau
        //   arrondi, centré en bas — boutons compacts en haut, barre de progression dessous, même largeur.
        barreBas = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        // ── barre de progression : « 12 ━━━●━━━ 203 » — glisser = sauter à la page ──
        //   Télécommande : ▼ depuis les boutons pour l'atteindre, ◀▶ = quelques pages, ▲ = retour aux boutons.
        // 2026-10-24 (user : « comme la barre des films », ne doit pas cacher l'écriture) : une simple
        //   ligne fine, sans fond, numéros en petit aux deux bouts (ombrés pour rester lisibles).
        curseurPage = TextView(this).apply {
            setTextColor(0xFFE0E0E0.toInt()); textSize = 10f; minWidth = dp(24f).toInt(); gravity = Gravity.CENTER
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
        }
        curseurTotal = TextView(this).apply {
            setTextColor(0xFFE0E0E0.toInt()); textSize = 10f; minWidth = dp(24f).toInt(); gravity = Gravity.CENTER
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
        }
        curseur = android.widget.SeekBar(this).apply {
            isFocusable = true
            progressTintList = android.content.res.ColorStateList.valueOf(0xFFE53935.toInt())
            thumbTintList = android.content.res.ColorStateList.valueOf(0xFFE53935.toInt())
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x80FFFFFF.toInt())
            setPadding(dp(8f).toInt(), 0, dp(8f).toInt(), 0)
            // Pas de fond : au focus télécommande, seul le point grossit.
            setOnFocusChangeListener { v, f -> v.scaleY = if (f) 1.6f else 1f }
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    if (autoCollant) arreterAuto()
                    (liste.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(p, 0)
                    curseurPage.text = "${p + 1}"
                    pageVue.text = "${p + 1} / ${adaptateur.pages.size}"
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar) { curseurTire = true }
                override fun onStopTrackingTouch(sb: android.widget.SeekBar) { curseurTire = false; majPage() }
            })
        }
        barreCurseur = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4f).toInt(), 0, 0)
            addView(curseurPage)
            addView(curseur, LinearLayout.LayoutParams(0, dp(22f).toInt(), 1f))
            addView(curseurTotal)
        }
        val panneau = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8f).toInt(), dp(6f).toInt(), dp(8f).toInt(), dp(4f).toInt())
            background = GradientDrawable().apply { cornerRadius = dp(18f); setColor(0xCC111114.toInt()) }
            addView(barreBas, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(barreCurseur, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        basConteneur = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(16f).toInt())
            addView(panneau, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // Boutons compacts du panneau (même style que [bouton], en plus petit).
        fun petit(texte: String, action: () -> Unit) = bouton(texte, action).apply {
            textSize = 14f
            minWidth = dp(40f).toInt()
            setPadding(dp(10f).toInt(), dp(6f).toInt(), dp(10f).toInt(), dp(6f).toInt())
            background = GradientDrawable().apply { cornerRadius = dp(10f); setColor(0x00FFFFFF) }
            setOnFocusChangeListener { v, f -> (v.background as? GradientDrawable)?.setColor(if (f) 0x66FFFFFF else 0x00FFFFFF) }
        }
        btnPrec = petit("◀") { chapitre?.precedent?.let { charger(it) } }
        btnSuiv = petit("▶") { chapitre?.suivant?.let { charger(it) } }
        // Zoom − / + (indispensable à la télécommande, pratique aussi au doigt).
        val zoomMoins = petit("−") { zoomer(-0.25f) }
        val zoomPlus = petit("+") { zoomer(+0.25f) }
        btnZoomPlus = zoomPlus
        radioBtn.text = "♪"
        radioBtn.textSize = 14f
        radioBtn.minWidth = dp(40f).toInt()
        radioBtn.setPadding(dp(10f).toInt(), dp(6f).toInt(), dp(10f).toInt(), dp(6f).toInt())
        radioBtn.background = GradientDrawable().apply { cornerRadius = dp(10f); setColor(0x00FFFFFF) }
        radioBtn.setOnFocusChangeListener { v, f -> (v.background as? GradientDrawable)?.setColor(if (f) 0x66FFFFFF else 0x00FFFFFF) }
        // 2026-10-23 (user) : bouton « Auto » entre − et + : lecture qui descend toute seule, lentement ;
        //   re-cliquer, une flèche ▲▼, OK ou toucher l'écran l'arrête.
        btnAuto = petit("Auto") { if (autoCollant) arreterAuto() else lancerLectureAuto() }
        // 2026-10-24 (user : « rester appuyé sur OK sur défilement auto, on nous propose la vitesse ») :
        //   appui long (OK maintenu à la télécommande, doigt maintenu au téléphone) → choix de la vitesse.
        btnAuto.isLongClickable = true
        btnAuto.setOnLongClickListener { choisirVitesseAuto(); true }
        fun marge() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(2f).toInt(); marginEnd = dp(2f).toInt()
        }
        barreBas.addView(btnPrec, marge())
        barreBas.addView(zoomMoins, marge())
        barreBas.addView(btnAuto, marge())
        barreBas.addView(zoomPlus, marge())
        // Radio dans la barre du bas : atteignable à la télécommande (◀▶ après OK).
        barreBas.addView(radioBtn, marge())
        barreBas.addView(btnSuiv, marge())
        racine.addView(basConteneur, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        setContentView(racine)
        installerGestes()
        liste.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: android.view.MotionEvent): Boolean {
                if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN && autoCollant) arreterAuto()
                return false
            }
        })

        val id = intent.getStringExtra(EXTRA_CHAPITRE)
        if (id == null) { finish(); return }
        charger(id, reprendre = true)
    }

    /** Largeur de lecture : tout l'écran en portrait ; sur un écran large (TV, téléphone couché),
     *  une colonne centrée pour que la page reste lisible sans défiler à l'infini. */
    // 2026-10-21 (user : « certains scans sont un peu flous ou pas trop lisibles ») —
    //   NETTETÉ DES PAGES. Avant : Glide ramenait chaque page à la taille de l'ImageView
    //   (FIT_CENTER, borné par l'écran) et en couleurs réduites (RGB_565, réglage global de
    //   l'app). Une page large perdait sa définition, un long webtoon était réduit à
    //   quelques centaines de pixels de large, et le zoom (+ / pincement) agrandissait
    //   cette version réduite → flou. Désormais : décodage à la LARGEUR utile × 2 (le zoom
    //   reste net), hauteur libre, couleurs complètes — avec un plafond de pixels par page
    //   pour la mémoire (plus bas en mode léger, qui garde aussi le RGB_565).
    private val pixelsMaxPage: Int
        get() = if (com.streamflixreborn.streamflix.utils.UserPreferences.modeLeger) 3_000_000 else 12_000_000

    private val echellePage = object : com.bumptech.glide.load.resource.bitmap.DownsampleStrategy() {
        override fun getScaleFactor(sw: Int, sh: Int, rw: Int, rh: Int): Float {
            if (sw <= 0 || sh <= 0) return 1f
            val parLargeur = if (rw > 0) rw.toFloat() / sw else 1f
            val plafond = kotlin.math.sqrt(pixelsMaxPage.toDouble() / (sw.toDouble() * sh)).toFloat()
            return minOf(1f, parLargeur, plafond)
        }
        override fun getSampleSizeRounding(sw: Int, sh: Int, rw: Int, rh: Int) =
            com.bumptech.glide.load.resource.bitmap.DownsampleStrategy.SampleSizeRounding.QUALITY
    }

    /** 2026-10-23 (user : « lecteur économe pour tout le monde, mais le zoom doit rester net ») :
     *  pages décodées à la largeur de l'écran (rendu identique sans zoom) ; dès qu'on zoome, les pages
     *  sont rechargées en × 2 (comme avant). En mode léger : × 1 toujours. */
    private val qualiteZoom: Boolean
        get() = echelle > 1f && !com.streamflixreborn.streamflix.utils.UserPreferences.modeLeger

    private fun optionsPage(zoom: Boolean = qualiteZoom): com.bumptech.glide.request.RequestOptions {
        val m = resources.displayMetrics
        val colonne = if (m.widthPixels > m.heightPixels)
            (m.heightPixels * 1.05f).toInt().coerceAtMost(m.widthPixels) else m.widthPixels
        val leger = com.streamflixreborn.streamflix.utils.UserPreferences.modeLeger
        return com.bumptech.glide.request.RequestOptions()
            // Décodage à la largeur de l'écran ; × 2 seulement une fois zoomé (hors mode léger).
            .override(colonne * (if (zoom) 2 else 1), com.bumptech.glide.request.target.Target.SIZE_ORIGINAL)
            .downsample(echellePage)
            .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)  // 2 octets/pixel : indispensable pour les webtoons (690 × 15 000)
            .apply { if (leger) dontTransform() else transform(NetteteScan) }
    }
    private fun largeurColonne(): Int {
        val m = resources.displayMetrics
        return if (m.widthPixels > m.heightPixels) (m.heightPixels * 1.05f).toInt().coerceAtMost(m.widthPixels)
            else ViewGroup.LayoutParams.MATCH_PARENT
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation du téléphone : on recalcule la colonne (l'activité n'est pas recréée).
        val lp = liste.layoutParams as FrameLayout.LayoutParams
        lp.width = largeurColonne()
        liste.layoutParams = lp
        echelle = 1f; appliquerEchelle(0f, 0f)
    }

    private fun bouton(texte: String, action: () -> Unit) = TextView(this).apply {
        text = texte
        setTextColor(Color.WHITE)
        textSize = 16f
        gravity = Gravity.CENTER
        setPadding(dp(14f).toInt(), dp(10f).toInt(), dp(14f).toInt(), dp(10f).toInt())
        isFocusable = true
        isClickable = true
        background = GradientDrawable().apply { cornerRadius = dp(10f); setColor(0x33FFFFFF) }
        setOnFocusChangeListener { v, f -> (v.background as? GradientDrawable)?.setColor(if (f) 0x99FFFFFF.toInt() else 0x33FFFFFF) }
        setOnClickListener { action() }
    }

    // ───────────────────────── chargement ─────────────────────────

    private fun charger(id: String, reprendre: Boolean = false) {
        sauverPosition()
        // Numéro du chapitre suivi depuis celui ouvert par la fiche (◀ / ▶ / « Chapitre suivant »).
        chapitre?.let { ancien ->
            when (id) {
                ancien.suivant -> numeroCourant++
                ancien.precedent -> numeroCourant = (numeroCourant - 1).coerceAtLeast(0)
            }
        }
        chargement.visibility = View.VISIBLE
        lifecycleScope.launch {
            val ch = runCatching { ScansProvider.chargerChapitre(id) }.getOrElse {
                chargement.visibility = View.GONE
                Toast.makeText(this@ScanReaderActivity, "Chapitre indisponible : ${it.message}", Toast.LENGTH_LONG).show()
                if (chapitre == null) finish()
                return@launch
            }
            chargement.visibility = View.GONE
            if (ch.images.isEmpty()) {
                Toast.makeText(this@ScanReaderActivity, "Aucune page trouvée pour ce chapitre", Toast.LENGTH_LONG).show()
                if (chapitre == null) finish()
                return@launch
            }
            chapitre = ch
            echelle = 1f; appliquerEchelle(0f, 0f)
            titreVue.text = listOfNotNull(serieTitre, ch.titre.takeIf { it != serieTitre }).joinToString("\n")
            btnPrec.alpha = if (ch.precedent != null) 1f else 0.35f
            btnSuiv.alpha = if (ch.suivant != null) 1f else 0.35f
            adaptateur.pages = ch.images
            val pos = if (reprendre) prefs().getInt("pos_${ch.id}", 0) else 0
            (liste.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos.coerceIn(0, ch.images.size - 1), 0)
            serieId?.let { prefs().edit().putString("dernier_$it", ch.id).apply() }
            // Le chapitre ouvert entre tout de suite dans « Continuer à regarder ».
            enregistrerReprise(ch, pos.coerceIn(0, ch.images.size - 1), fini = false)
            majPage()
            afficherBarres(true)
            liste.requestFocus()
        }
    }

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    private fun premiereVisible() = (liste.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition().coerceAtLeast(0)

    private fun sauverPosition() {
        val ch = chapitre ?: return
        prefs().edit().putInt("pos_${ch.id}", premiereVisible()).apply()
        // Fin du chapitre = dernière page (ou le pied « Chapitre suivant ») visible.
        val derniere = (liste.layoutManager as LinearLayoutManager).findLastVisibleItemPosition()
        enregistrerReprise(ch, premiereVisible(), fini = ch.images.isNotEmpty() && derniere >= ch.images.size - 1)
    }

    /**
     * 2026-10-24 (user : « les scans commencés qui apparaissent comme les séries ») — le chapitre
     *   en cours va dans « Continuer à regarder », comme un épisode : position = page, durée = nombre
     *   de pages (la barre de progression montre où on en est). Un seul chapitre en cours par série ;
     *   un chapitre lu jusqu'au bout est marqué vu et sort de la liste. Le clic sur la vignette rouvre
     *   la liseuse (via [redirigerSiScan]) à la page retenue.
     */
    private fun enregistrerReprise(ch: ScansProvider.Chapitre, page: Int, fini: Boolean) {
        val sid = serieId ?: return
        val provider = com.streamflixreborn.streamflix.utils.UserPreferences.currentProvider ?: return
        val ctx = applicationContext
        val ref = episodeRef
        val numero = numeroCourant
        val titreSerie = serieTitre ?: ref?.tvShow?.title ?: ""
        val total = ch.images.size.toLong()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching {
                val db = com.streamflixreborn.streamflix.database.AppDatabase.getInstance(ctx)
                val tvDao = db.tvShowDao()
                val epDao = db.episodeDao()
                val serie = tvDao.getById(sid) ?: com.streamflixreborn.streamflix.models.TvShow(
                    id = sid, title = titreSerie, poster = ref?.tvShow?.poster, banner = ref?.tvShow?.banner,
                ).also { tvDao.insert(it) }
                val ep = epDao.getById(ch.id) ?: com.streamflixreborn.streamflix.models.Episode(
                    id = ch.id,
                    number = numero,
                    title = ch.titre,
                    poster = ref?.poster ?: serie.poster,
                    tvShow = serie,
                    season = com.streamflixreborn.streamflix.models.Season(
                        number = ref?.season?.number ?: 1,
                        title = ref?.season?.title,
                    ),
                )
                if (fini) {
                    ep.isWatched = true
                    ep.watchedDate = java.util.Calendar.getInstance()
                    ep.watchHistory = null
                } else {
                    ep.isWatched = false
                    ep.watchedDate = null
                    ep.watchHistory = com.streamflixreborn.streamflix.models.WatchItem.WatchHistory(
                        lastEngagementTimeUtcMillis = System.currentTimeMillis(),
                        lastPlaybackPositionMillis = page.toLong(),
                        durationMillis = total,
                    )
                }
                epDao.save(ep)
                val cache = com.streamflixreborn.streamflix.utils.UserDataCache
                if (fini) cache.removeEpisodeFromContinueWatching(ctx, provider, ep.id)
                else cache.syncEpisodeToCache(ctx, provider, ep)
                // Un seul chapitre « en cours » par série : les autres quittent la liste.
                if (!fini) {
                    epDao.getEpisodesByTvShowId(sid)
                        .filter { it.id != ch.id && it.watchHistory != null }
                        .forEach { autre ->
                            autre.watchHistory = null
                            epDao.update(autre)
                            cache.removeEpisodeFromContinueWatching(ctx, provider, autre.id)
                        }
                }
                tvDao.save(serie.copy().apply {
                    merge(serie)
                    isWatching = !fini || epDao.hasAnyWatchHistoryForTvShow(sid)
                })
            }.onFailure { android.util.Log.w("ScanReader", "reprise non enregistrée : ${it.message}") }
        }
    }

    private fun majPage() {
        val n = adaptateur.pages.size
        if (n == 0) { pageVue.text = ""; return }
        val lm = liste.layoutManager as LinearLayoutManager
        val p = (lm.findLastVisibleItemPosition().takeIf { it >= 0 } ?: 0) + 1
        pageVue.text = "$p / $n"
        if (!curseurTire) {
            curseur.max = (n - 1).coerceAtLeast(1)
            // Télécommande : ◀▶ avancent d'environ 1/25e du chapitre (au moins 1 page).
            curseur.keyProgressIncrement = (n / 25).coerceAtLeast(1)
            // Même numéro que le compteur du haut (dernière page visible).
            curseur.progress = (p - 1).coerceIn(0, n - 1)
            curseurPage.text = "$p"
            curseurTotal.text = "$n"
        }
    }

    override fun onPause() {
        super.onPause()
        sauverPosition()
    }

    // ───────────────────────── barres, zoom, gestes ─────────────────────────

    private fun afficherBarres(visible: Boolean) {
        barresVisibles = visible
        val v = if (visible) View.VISIBLE else View.GONE
        barreHaut.visibility = v
        basConteneur.visibility = v
    }

    private fun appliquerEchelle(px: Float, py: Float) {
        val avant = qualiteZoom
        echelle = echelle.coerceIn(1f, 3f)
        if (!avant && qualiteZoom) liste.post {
            adaptateur.notifyItemRangeChanged(0, adaptateur.itemCount)
        }
        conteneurZoom.pivotX = px
        conteneurZoom.pivotY = py
        conteneurZoom.scaleX = echelle
        conteneurZoom.scaleY = echelle
        if (echelle == 1f) conteneurZoom.translationX = 0f
    }

    private fun zoomer(delta: Float) {
        echelle = (echelle + delta).coerceIn(1f, 3f)
        appliquerEchelle(conteneurZoom.width / 2f, conteneurZoom.height / 2f)
        if (echelle == 1f) conteneurZoom.translationX = 0f
        toast("Zoom ${(echelle * 100).toInt()} %")
    }

    /** Zoom à la télécommande : ◀▶ déplacent la page au lieu de changer de chapitre. */
    private fun deplacer(sens: Int) {
        val max = conteneurZoom.width * (echelle - 1f) / 2f
        conteneurZoom.translationX = (conteneurZoom.translationX + sens * conteneurZoom.width * 0.2f).coerceIn(-max, max)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 2026-10-27 (photo mémoire Oppo : ~45 Mo de pages encore en mémoire 1 min après la
        //   fermeture) : liseuse fermée → pages rendues tout de suite (utile sur mini-box si on
        //   lance une vidéo juste après).
        if (isFinishing) runCatching { Glide.get(this).clearMemory() }
        if (radioLanceeIci && isFinishing) {
            runCatching { com.streamflixreborn.streamflix.utils.MiniPlayerController.stopAsync() }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installerGestes() {
        val pincement = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                echelle *= d.scaleFactor
                appliquerEchelle(d.focusX, d.focusY)
                return true
            }
        })
        val gestes = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { afficherBarres(!barresVisibles); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                echelle = if (echelle > 1.2f) 1f else 2f
                appliquerEchelle(e.x, e.y)
                return true
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (echelle > 1f) {
                    val max = conteneurZoom.width * (echelle - 1f) / 2f
                    conteneurZoom.translationX = (conteneurZoom.translationX - dx).coerceIn(-max, max)
                }
                return false
            }
        })
        liste.setOnTouchListener { _, ev ->
            pincement.onTouchEvent(ev)
            gestes.onTouchEvent(ev)
            ev.pointerCount > 1   // pendant un pincement, on ne laisse pas la liste défiler
        }
    }

    // ───────────────────────── télécommande ─────────────────────────

    // 2026-10-23 (user : « quand on reste appuyé, une lecture fluide en mode automatique, pas
    //   saccadée ») — DÉFILEMENT CONTINU. Avant : flèche maintenue = la télécommande répète la touche
    //   ~20 fois/s et chaque répétition faisait un saut sec (scrollBy) → à-coups. Désormais, dès la
    //   1re répétition, la liste glisse à CHAQUE IMAGE de l'écran (postOnAnimation), à vitesse
    //   constante (montée douce en 0,4 s), et s'arrête net au relâchement. Appui court inchangé.
    private var autoSens = 0
    private var autoDebut = 0L
    private var autoDernier = 0L
    private var autoReste = 0f
    /** true = lancé par le bouton « Auto » (continue après relâchement) ; vitesse de lecture lente. */
    private var autoCollant = false
    private var autoVitesse = 0.6f
    private lateinit var btnAuto: TextView
    private val autoPas = object : Runnable {
        override fun run() {
            if (autoSens == 0 || isFinishing) return
            val maintenant = android.os.SystemClock.uptimeMillis()
            val dt = (maintenant - autoDernier).coerceIn(0L, 50L) / 1000f
            autoDernier = maintenant
            val montee = ((maintenant - autoDebut) / 400f).coerceIn(0.25f, 1f)
            // ~60 % de la hauteur d'écran par seconde : une vitesse de lecture.
            autoReste += liste.height * autoVitesse * montee * dt
            if (!liste.canScrollVertically(autoSens)) { arreterAuto(); return }
            val d = autoReste.toInt()
            if (d != 0) { liste.scrollBy(0, autoSens * d); autoReste -= d }
            liste.postOnAnimation(this)
        }
    }
    private fun demarrerAuto(sens: Int) {
        if (autoSens == sens) return
        if (!autoCollant) autoVitesse = 0.6f
        liste.stopScroll()
        autoSens = sens
        autoDebut = android.os.SystemClock.uptimeMillis()
        autoDernier = autoDebut
        autoReste = 0f
        liste.removeCallbacks(autoPas)
        liste.postOnAnimation(autoPas)
    }
    private fun arreterAuto() {
        autoSens = 0
        liste.removeCallbacks(autoPas)
        if (autoCollant) {
            autoCollant = false
            if (::btnAuto.isInitialized) btnAuto.text = "Auto"
        }
    }

    /** Vitesses de la lecture auto, en secondes pour faire défiler un écran (10 s = l'ancienne vitesse fixe). */
    private val vitessesAuto = listOf(20 to "Très lent", 14 to "Lent", 10 to "Normal", 7 to "Rapide", 5 to "Très rapide")
    private fun secondesParEcran(): Int = prefs().getInt("auto_secondes", 10).takeIf { s -> vitessesAuto.any { it.first == s } } ?: 10

    /** Appui long sur « Auto » : liste des vitesses ; le choix est mémorisé et lance (ou ajuste) la lecture auto. */
    private fun choisirVitesseAuto() {
        val actuelle = secondesParEcran()
        val libelles = vitessesAuto.map { (sec, nom) -> "$nom — $sec s par écran" }.toTypedArray()
        val coche = vitessesAuto.indexOfFirst { it.first == actuelle }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Vitesse du défilement auto")
            .setSingleChoiceItems(libelles, coche) { d, i ->
                val (sec, nom) = vitessesAuto[i]
                prefs().edit().putInt("auto_secondes", sec).putBoolean("auto_astuce_vue", true).apply()
                d.dismiss()
                if (autoCollant) autoVitesse = 1f / sec else lancerLectureAuto()
                toast("Défilement auto : $nom")
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** Bouton « Auto » : un écran toutes les N secondes (réglable par appui long), jusqu'à ce qu'on l'arrête. */
    private fun lancerLectureAuto() {
        arreterAuto()
        // autoCollant AVANT demarrerAuto : sinon demarrerAuto remet la vitesse « flèche maintenue » (0,6).
        autoCollant = true
        autoVitesse = 1f / secondesParEcran()
        // 2026-10-24 (user) : plus de message « Astuce : appui long sur Auto » (c'est dans le guide).
        demarrerAuto(+1)
        btnAuto.text = "⏸ Stop"
        afficherBarres(false)
        liste.requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && autoSens != 0 && !autoCollant &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || event.keyCode == KeyEvent.KEYCODE_DPAD_UP)) {
            arreterAuto(); return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        if (autoCollant && event.keyCode in setOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER) && currentFocus != btnAuto) {
            arreterAuto(); return true
        }
        // Barres affichées et focus sur un bouton : on laisse ◀▶/OK naviguer entre les boutons.
        val focusBouton = currentFocus != null && currentFocus != liste &&
            (currentFocus?.parent == barreBas || currentFocus?.parent == barreHaut || currentFocus == curseur)
        // Barre de progression à la télécommande (tout en bas) : ▼ depuis un bouton du bas → curseur ;
        //   ▲ depuis le curseur → retour aux boutons ; ◀▶ sur le curseur = la SeekBar avance de quelques pages.
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && currentFocus?.parent == barreBas) {
            curseur.requestFocus(); return true
        }
        if (currentFocus == curseur) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { btnZoomPlus.requestFocus(); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { liste.requestFocus(); afficherBarres(false); return true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val pas = curseur.keyProgressIncrement * (if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                    val p = (curseur.progress + pas).coerceIn(0, curseur.max)
                    curseur.progress = p
                    (liste.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(p, 0)
                    curseurPage.text = "${p + 1}"
                    pageVue.text = "${p + 1} / ${adaptateur.pages.size}"
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { liste.requestFocus(); afficherBarres(false); return true }
            }
        }
        // 2026-10-20 (user : « sur la télé ça descend avec des gros à-coups ») : défilement fin.
        //   ▲▼ = un tiers d'écran par appui (on ne saute plus de bulles) ; flèche MAINTENUE =
        //   défilement continu, doux, qui s'arrête dès qu'on relâche (comme le doigt sur téléphone).
        //   Page+/Page− et Chaîne+/− gardent le grand saut (80 % d'écran) pour aller vite.
        val grandPas = (liste.height * 0.8f).toInt()
        val petitPas = (liste.height * 0.33f).toInt()
        fun defiler(sens: Int, grand: Boolean) {
            if (focusBouton) liste.requestFocus()
            when {
                grand -> liste.smoothScrollBy(0, sens * grandPas)
                event.repeatCount > 0 -> demarrerAuto(sens)
                else -> liste.smoothScrollBy(0, sens * petitPas)
            }
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> { defiler(+1, false); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { defiler(-1, false); return true }
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { defiler(+1, true); return true }
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { defiler(-1, true); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (!focusBouton) {
                    if (echelle > 1f && event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { deplacer(-1); return true }
                    // 2026-10-23 (user : « on peut appuyer sans faire exprès et on perd l'endroit ») : ▶ ne
                    //   change plus de chapitre — il affiche la barre, curseur sur « Suivant » (OK pour valider).
                    if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { afficherBarres(true); btnSuiv.requestFocus(); return true }
                    chapitre?.suivant?.let { charger(it) } ?: toast("Dernier chapitre"); return true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (!focusBouton) {
                    if (echelle > 1f && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { deplacer(+1); return true }
                    // 2026-10-23 : ◀ affiche la barre, curseur sur « Précédent » (OK pour valider).
                    if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { afficherBarres(true); btnPrec.requestFocus(); return true }
                    chapitre?.precedent?.let { charger(it) } ?: toast("Premier chapitre"); return true
                }
            }
            KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_NUMPAD_ADD, KeyEvent.KEYCODE_ZOOM_IN -> { zoomer(+0.25f); return true }
            KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT, KeyEvent.KEYCODE_ZOOM_OUT -> { zoomer(-0.25f); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                // OK sur la page : affiche les barres et place le curseur sur « + » (◀▶ pour choisir
                //   Précédent / − / + / Suivant, OK pour valider, ▲▼ pour revenir aux pages).
                if (!focusBouton) { afficherBarres(true); btnZoomPlus.requestFocus(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    // Retour : si les barres sont affichées, on les masque d'abord (et on rend la main aux pages) ;
    //   un second Retour quitte la liseuse.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (barresVisibles) {
            afficherBarres(false)
            liste.requestFocus()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    // ───────────────────────── pages ─────────────────────────

    private inner class PagesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var pages: List<String> = emptyList()
            @SuppressLint("NotifyDataSetChanged")
            set(v) { field = v; notifyDataSetChanged() }

        override fun getItemCount() = pages.size + 1   // + pied « chapitre suivant »
        override fun getItemViewType(position: Int) = if (position < pages.size) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == 1) {
                val pied = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(0, dp(32f).toInt(), 0, dp(80f).toInt())
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                return object : RecyclerView.ViewHolder(pied) {}
            }
            val iv = ImageView(parent.context).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                minimumHeight = dp(400f).toInt()
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            return object : RecyclerView.ViewHolder(iv) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (position >= pages.size) {
                val pied = holder.itemView as LinearLayout
                pied.removeAllViews()
                val fin = TextView(pied.context).apply {
                    text = "Fin du chapitre"; setTextColor(0xFFAAAAAA.toInt()); textSize = 14f; gravity = Gravity.CENTER
                }
                pied.addView(fin)
                chapitre?.suivant?.let { suivant ->
                    pied.addView(bouton("Chapitre suivant  ▶") { charger(suivant) }, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16f).toInt() })
                }
                return
            }
            val iv = holder.itemView as ImageView
            iv.minimumHeight = dp(400f).toInt()
            val url = GlideUrl(pages[position], LazyHeaders.Builder().apply {
                ScansProvider.headersPour(pages[position]).forEach { (k, v) -> addHeader(k, v) }
            }.build())
            val zoom = qualiteZoom
            Glide.with(iv).load(url)
                .diskCacheStrategy(DiskCacheStrategy.DATA)
                .apply(optionsPage(zoom))
                // En passant au × 2, la page déjà affichée (× 1, en mémoire) reste visible pendant le
                //   rechargement : pas de page vide ni de saut de défilement.
                .apply { if (zoom) thumbnail(Glide.with(iv).load(url).apply(optionsPage(false)).onlyRetrieveFromCache(true)) }
                .listener(object : com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> {
                    override fun onLoadFailed(e: com.bumptech.glide.load.engine.GlideException?, model: Any?,
                        target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>, isFirstResource: Boolean): Boolean = false
                    override fun onResourceReady(resource: android.graphics.drawable.Drawable, model: Any,
                        target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>?,
                        dataSource: com.bumptech.glide.load.DataSource, isFirstResource: Boolean): Boolean {
                        iv.minimumHeight = 0
                        return false
                    }
                })
                .into(iv)
            // Pré-chargement des 2 pages suivantes.
            // Mode léger : 1 page préparée d'avance au lieu de 2.
            for (k in 1..(if (zoom) 2 else 1)) pages.getOrNull(position + k)?.let { u ->
                Glide.with(iv).load(GlideUrl(u, LazyHeaders.Builder().apply {
                    ScansProvider.headersPour(u).forEach { (a, b) -> addHeader(a, b) }
                }.build())).diskCacheStrategy(DiskCacheStrategy.DATA).apply(optionsPage()).preload()
            }
        }
    }
}

/**
 * 2026-10-21 (user : « un filtre de netteté ») — certaines pages de scans sont petites à la
 * source (mesuré sur Sushiscan : pages de texte en 1024 × 1418 pour un écran de 1080 de
 * large) : aucune résolution de plus à aller chercher. On renforce donc légèrement les
 * contours (masque flou 3×3 : pixel + K × (pixel − moyenne des voisins)), UNIQUEMENT sur les
 * pages de moins de 1600 px de large ; les grandes pages ont déjà leur détail. Calcul fait
 * par Glide en arrière-plan, une fois par page (le résultat est mis en cache mémoire).
 */
object NetteteScan : com.bumptech.glide.load.resource.bitmap.BitmapTransformation() {
    private const val ID = "scan-nettete-v1"
    private const val K = 0.6f
    private const val LARGEUR_MAX = 1600

    override fun transform(
        pool: com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool,
        src: android.graphics.Bitmap, outWidth: Int, outHeight: Int,
    ): android.graphics.Bitmap {
        val w = src.width
        val h = src.height
        if (w > LARGEUR_MAX || w < 3 || h < 3 || w.toLong() * h > 4_000_000L) return src  // pas sur les longs webtoons
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val sortie = px.copyOf()
        for (y in 1 until h - 1) {
            val l0 = (y - 1) * w; val l1 = y * w; val l2 = (y + 1) * w
            for (x in 1 until w - 1) {
                var sr = 0; var sg = 0; var sb = 0
                // 9 voisins sans allocation (une page = ~1,5 million de pixels)
                var dy = 0
                while (dy < 3) {
                    val base = (if (dy == 0) l0 else if (dy == 1) l1 else l2) + x - 1
                    var dx = 0
                    while (dx < 3) {
                        val v = px[base + dx]
                        sr += (v shr 16) and 0xFF; sg += (v shr 8) and 0xFF; sb += v and 0xFF
                        dx++
                    }
                    dy++
                }
                val c = px[l1 + x]
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                val nr = (r + K * (r - sr / 9f)).toInt().coerceIn(0, 255)
                val ng = (g + K * (g - sg / 9f)).toInt().coerceIn(0, 255)
                val nb = (b + K * (b - sb / 9f)).toInt().coerceIn(0, 255)
                sortie[l1 + x] = (c and -0x1000000) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        val res = pool.get(w, h, src.config ?: android.graphics.Bitmap.Config.RGB_565)
        res.setPixels(sortie, 0, w, 0, 0, w, h)
        return res
    }

    override fun updateDiskCacheKey(messageDigest: java.security.MessageDigest) {
        messageDigest.update(ID.toByteArray(Charsets.UTF_8))
    }
    override fun equals(other: Any?) = other is NetteteScan
    override fun hashCode() = ID.hashCode()
}
