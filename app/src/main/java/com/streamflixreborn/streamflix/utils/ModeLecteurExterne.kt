package com.streamflixreborn.streamflix.utils

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.UserDataCache.toEpisode
import com.streamflixreborn.streamflix.utils.UserDataCache.toMovie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 2026-10-25 (demande d'un utilisateur, validée par le user) — mode « lecteur externe » complet,
 * réservé à la TV (le user : « les lecteurs externes c'est plus pour la télé »).
 *
 * Quand « toujours utiliser le lecteur externe » est coché — ou après un appui LONG sur
 * « Regarder » / une carte d'épisode —, le lecteur ONYX ne démarre plus rien : la LISTE DES
 * SERVEURS s'affiche d'abord, le serveur choisi part directement dans VLC (ou le lecteur
 * mémorisé). Au retour de VLC :
 *  - lecture très courte / pas de réponse → la liste revient, le serveur est marqué ✕ ;
 *  - épisode fini → on propose l'épisode suivant (sa liste de serveurs) ou d'arrêter ;
 *  - sinon → on referme, la reprise est dans « Continuer à regarder ».
 * Les radios ne sont pas concernées (demande user).
 *
 * Style : celui du menu Radio (RadioPickerDialog), validé par le user. La fenêtre couvre tout
 * l'écran (image du titre assombrie) : le lecteur, vide, n'est jamais visible derrière.
 */
object ModeLecteurExterne {

    private const val TAG = "ModeLecteurExterne"

    /** En dessous, on considère que le serveur n'a pas marché dans VLC → retour à la liste. */
    const val LECTURE_COURTE_MS = 30_000L

    /** 2026-10-25 (idée du user) : appui LONG sur « Regarder » ou sur un épisode → mode
     *  externe pour CE titre seulement, même si « toujours lecteur externe » n'est pas coché. */
    @Volatile var uneFoisPourId: String? = null

    fun actif(id: String?): Boolean =
        (UserPreferences.alwaysUseExternalPlayer || (id != null && id == uneFoisPourId)) &&
            !MiniPlayerController.isRadioChannel(id)

    /** À appeler quand le lecteur se ferme : l'appui long ne vaut que pour une lecture. */
    fun oublierUneFois(id: String?) { if (id != null && id == uneFoisPourId) uneFoisPourId = null }

    /** Position de reprise mémorisée (0 si rien), comme le lecteur interne (−10 s). */
    suspend fun positionReprise(ctx: Context, database: AppDatabase, type: Video.Type): Long =
        withContext(Dispatchers.IO) {
            try {
                val provider = UserPreferences.currentProvider
                val item = when (type) {
                    is Video.Type.Movie -> provider?.let {
                        UserDataCache.read(ctx, it)?.continueWatchingMovies?.find { m -> m.id == type.id }?.toMovie()
                    } ?: database.movieDao().getById(type.id)
                    is Video.Type.Episode -> provider?.let {
                        UserDataCache.read(ctx, it)?.continueWatchingEpisodes?.find { e -> e.id == type.id }?.toEpisode()
                    } ?: database.episodeDao().getById(type.id)
                }
                val pos = item?.watchHistory?.lastPlaybackPositionMillis ?: 0L
                (pos - 10_000L).coerceAtLeast(0L)
            } catch (e: Exception) {
                Log.w(TAG, "reprise illisible : ${e.message}"); 0L
            }
        }

    /** Appui long sur un épisode : lecteur externe, ou favori (le comportement d'avant). */
    fun menuEpisode(ctx: Context, id: String, lancer: () -> Unit, onFavori: () -> Unit) {
        val s = Style(ctx)
        val d = Dialog(ctx)
        val feuille = s.feuille()
        feuille.addView(s.entete("Épisode", null))
        val lignes = listOf(
            s.ligneAction("▶", "Lire dans un lecteur externe", accent = true) { d.dismiss(); uneFoisPourId = id; lancer() },
            s.ligneAction("♥", "Ajouter / retirer des favoris", accent = false) { d.dismiss(); onFavori() },
        )
        lignes.forEach { feuille.addView(it, s.pleineLargeur(bas = 8)) }
        s.montrerCentre(d, feuille, largeurDp = 460)
        lignes.first().requestFocus()
    }

    /** « Épisode terminé » → propose le suivant. */
    fun proposerSuivant(ctx: Context, libelleSuivant: String, onSuivant: () -> Unit, onArreter: () -> Unit) {
        val s = Style(ctx)
        val d = Dialog(ctx)
        var choisi = false
        val feuille = s.feuille()
        feuille.addView(s.entete("Épisode terminé", null))
        feuille.addView(s.texte2("Suivant : $libelleSuivant"), s.pleineLargeur(bas = 12))
        val suivant = s.ligneAction("▶", "Choisir un serveur pour l'épisode suivant", accent = true) {
            choisi = true; d.dismiss(); onSuivant()
        }
        val arreter = s.ligneAction("⏹", "Arrêter", accent = false) { choisi = true; d.dismiss(); onArreter() }
        feuille.addView(suivant, s.pleineLargeur(bas = 8))
        feuille.addView(arreter, s.pleineLargeur())
        d.setOnDismissListener { if (!choisi) onArreter() }
        s.montrerCentre(d, feuille, largeurDp = 520)
        suivant.requestFocus()
    }
}

/** Palette et briques du menu Radio (cf. RadioPickerDialog), partagées par les fenêtres du mode externe. */
internal class Style(private val ctx: Context) {
    companion object {
        const val SURFACE = "#15171C"
        const val CARD = "#20242C"
        const val BORDER = "#2F343E"
        const val DIVIDER = "#23262E"
        const val ACCENT = "#E23B3B"
        const val TXT = "#F5F6F8"
        const val TXT2 = "#9AA0AA"
        const val TXT_CHIP = "#DDE0E5"
        const val AVATAR_BG = "#2A2E37"
    }

    private val densite = ctx.resources.displayMetrics.density
    fun dp(v: Int) = (v * densite).toInt()
    fun c(s: String) = Color.parseColor(s)

    fun arrondi(fond: String, rayon: Int, bord: String? = null) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(rayon).toFloat()
        setColor(c(fond))
        if (bord != null) setStroke(dp(1), c(bord))
    }

    /** Liseré blanc de focus (télécommande), comme la radio. */
    fun focusTv(v: View) {
        v.isFocusable = true
        v.foreground = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_focus_white_border)
    }

    fun feuille() = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = arrondi(SURFACE, 20)
        setPadding(dp(16), dp(14), dp(16), dp(12))
    }

    fun pleineLargeur(bas: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(bas) }

    fun texte2(t: String) = TextView(ctx).apply {
        text = t; textSize = 13f
        setTextColor(c(TXT2))
        setPadding(dp(2), 0, dp(2), 0)
    }

    /** En-tête : pastille ▶ + titre + compteur optionnel + fermer optionnel. */
    fun entete(titre: String, compteur: TextView?, fermer: TextView? = null) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(2), 0, dp(2), dp(10))
        addView(TextView(ctx).apply {
            text = "▶"; textSize = 14f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = arrondi(ACCENT, 9)
        }, LinearLayout.LayoutParams(dp(30), dp(30)))
        addView(TextView(ctx).apply {
            text = titre; textSize = 18f
            setTextColor(c(TXT))
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(10), 0, dp(8), 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (compteur != null) addView(compteur)
        if (fermer != null) addView(fermer)
    }

    fun pastilleCompteur() = TextView(ctx).apply {
        text = "…"; textSize = 12f
        setTextColor(c(TXT_CHIP))
        background = arrondi(AVATAR_BG, 999)
        setPadding(dp(9), dp(2), dp(9), dp(2))
    }

    fun boutonFermer(onClic: () -> Unit) = TextView(ctx).apply {
        text = "✕"; textSize = 18f; gravity = Gravity.CENTER
        setTextColor(c(TXT2))
        setPadding(dp(12), dp(6), dp(8), dp(6))
        isClickable = true
        focusTv(this)
        setOnClickListener { onClic() }
    }

    /** Ligne d'action « carte » focusable (menus courts). */
    fun ligneAction(glyphe: String, libelle: String, accent: Boolean, onClic: () -> Unit) =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = arrondi(CARD, 12, BORDER)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            focusTv(this)
            setOnClickListener { onClic() }
            addView(TextView(ctx).apply {
                text = glyphe; textSize = 15f; gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = arrondi(if (accent) ACCENT else AVATAR_BG, 8)
            }, LinearLayout.LayoutParams(dp(34), dp(34)))
            addView(TextView(ctx).apply {
                text = libelle; textSize = 15f
                setTextColor(c("#EAECEF"))
                setPadding(dp(12), 0, 0, 0)
            })
        }

    /** Petite fenêtre centrée sur fond transparent (menus courts). */
    fun montrerCentre(d: Dialog, feuille: View, largeurDp: Int) {
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        d.setContentView(feuille)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.show()
        val ecran = ctx.resources.displayMetrics.widthPixels
        d.window?.setLayout(minOf(dp(largeurDp), (ecran * 0.9f).toInt()), ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}

/**
 * Fenêtre « liste des serveurs » du mode lecteur externe. Elle se remplit au fur et à mesure
 * (les sources de secours arrivent après les premières) : [serveurs] est relu régulièrement.
 * Plein écran (image [fond] assombrie) → le lecteur vide n'est jamais visible ; elle reste
 * ouverte pendant la lecture dans VLC (pas de flash du lecteur à l'aller ni au retour).
 */
class ListeServeursExterne(
    private val ctx: Context,
    private val titre: String,
    private val fond: String?,
    private val serveurs: () -> List<Video.Server>,
    /** [onyx] = true → lire dans le lecteur ONYX (bascule en haut de la liste), sinon externe. */
    private val onChoix: (srv: Video.Server, onyx: Boolean) -> Unit,
    private val onFermer: () -> Unit,
) {
    /** 2026-10-25 (user) : bascule « Lecteur externe / Lecteur ONYX » — par défaut externe. */
    private var choixOnyx = false
    private val s = Style(ctx)
    private var dialog: Dialog? = null
    private var etat: TextView? = null
    private var compteur: TextView? = null
    private var liste: ListView? = null
    private val affiches = mutableListOf<Video.Server>()
    private var signature = emptyList<String>()
    private var enCours: String? = null
    private val echoues = mutableSetOf<String>()
    private var message: String? = null
    private var fermeParCode = false
    private val handler = Handler(Looper.getMainLooper())
    private val tic = object : Runnable {
        override fun run() { rafraichir(); handler.postDelayed(this, 800) }
    }

    private val adaptateur = object : BaseAdapter() {
        override fun getCount() = affiches.size
        override fun getItem(position: Int) = affiches[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val srv = affiches[position]
            val echoue = srv.id in echoues
            val charge = srv.id == enCours
            val lettre = srv.name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "•"
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(s.dp(6), s.dp(10), s.dp(6), s.dp(10))
                addView(TextView(ctx).apply {
                    text = lettre; textSize = 14f; gravity = Gravity.CENTER
                    setTextColor(Color.parseColor(if (charge) "#FFFFFF" else Style.TXT_CHIP))
                    background = s.arrondi(if (charge) Style.ACCENT else Style.AVATAR_BG, 8)
                }, LinearLayout.LayoutParams(s.dp(34), s.dp(34)))
                addView(TextView(ctx).apply {
                    text = srv.name; textSize = 15f
                    setTextColor(Color.parseColor(if (echoue) Style.TXT2 else "#EAECEF"))
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    if (echoue) paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { leftMargin = s.dp(12); rightMargin = s.dp(8) })
                if (charge || echoue) addView(TextView(ctx).apply {
                    text = if (charge) "⟳" else "✕"; textSize = 16f
                    setTextColor(Color.parseColor(if (charge) Style.ACCENT else Style.TXT2))
                    setPadding(s.dp(8), 0, s.dp(4), 0)
                })
            }
        }
    }

    val visible: Boolean get() = dialog?.isShowing == true

    fun afficher(msg: String? = null) {
        message = msg
        enCours = null
        if (visible) { rafraichir(true); return }

        val racine = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK) }
        // Image du titre, assombrie : le lecteur (vide) n'est jamais visible derrière.
        if (!fond.isNullOrBlank()) {
            val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            racine.addView(img, FrameLayout.LayoutParams(-1, -1))
            // applicationContext : le lecteur met en pause les images de l'activité à son ouverture.
            runCatching {
                com.bumptech.glide.Glide.with(ctx.applicationContext).load(optimizeArtworkUrl(fond, 780)).centerCrop().into(img)
            }
        }
        racine.addView(View(ctx).apply { setBackgroundColor(Color.parseColor("#B30B0C10")) },
            FrameLayout.LayoutParams(-1, -1))

        val feuille = s.feuille()
        val cpt = s.pastilleCompteur()
        feuille.addView(s.entete(titre, cpt, s.boutonFermer { dialog?.cancel() }))
        // Bascule segmentée (comme Radios / Musique) : où lire le serveur choisi.
        fun onglet(libelle: String) = TextView(ctx).apply {
            text = libelle; textSize = 14f; gravity = Gravity.CENTER
            setPadding(0, s.dp(9), 0, s.dp(9))
            isClickable = true
            s.focusTv(this)
        }
        val ongletExterne = onglet("📺  Lecteur externe")
        val ongletOnyx = onglet("▶  Lecteur ONYX")
        fun peindre() {
            ongletExterne.background = if (!choixOnyx) s.arrondi(Style.ACCENT, 9) else null
            ongletOnyx.background = if (choixOnyx) s.arrondi(Style.ACCENT, 9) else null
            ongletExterne.setTextColor(Color.parseColor(if (!choixOnyx) "#FFFFFF" else Style.TXT_CHIP))
            ongletOnyx.setTextColor(Color.parseColor(if (choixOnyx) "#FFFFFF" else Style.TXT_CHIP))
            rafraichir(true)
        }
        ongletExterne.setOnClickListener { choixOnyx = false; message = null; peindre() }
        ongletOnyx.setOnClickListener { choixOnyx = true; message = null; peindre() }
        val segment = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = s.arrondi(Style.CARD, 12)
            setPadding(s.dp(4), s.dp(4), s.dp(4), s.dp(4))
            addView(ongletExterne, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ongletOnyx, LinearLayout.LayoutParams(0, -2, 1f))
        }
        feuille.addView(segment, s.pleineLargeur(bas = 10))
        // Télécommande : ▲ depuis la liste → l'onglet actif ; ◀ ▶ entre les deux onglets ; ▼ → liste.
        ongletExterne.nextFocusRightId = View.generateViewId().also { ongletOnyx.id = it }
        ongletOnyx.nextFocusLeftId = View.generateViewId().also { ongletExterne.id = it }
        val tv = s.texte2("")
        feuille.addView(tv, s.pleineLargeur(bas = 10))
        val lv = ListView(ctx).apply {
            adapter = adaptateur
            divider = ColorDrawable(Color.parseColor(Style.DIVIDER))
            dividerHeight = s.dp(1)
            selector = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_list_selector_tv)
            isDrawSelectorOnTop = true
            isFocusable = true
            id = View.generateViewId()
            ongletExterne.nextFocusDownId = id; ongletOnyx.nextFocusDownId = id
            setOnItemClickListener { _, _, position, _ ->
                val srv = affiches.getOrNull(position) ?: return@setOnItemClickListener
                enCours = srv.id
                message = "Ouverture de ${srv.name}…"
                rafraichir(true)
                onChoix(srv, choixOnyx)
            }
        }
        // ▲ depuis la liste → l'onglet actif (pas celui qui est géométriquement le plus proche).
        lv.setOnKeyListener { _, code, ev ->
            if (ev.action == android.view.KeyEvent.ACTION_DOWN && code == android.view.KeyEvent.KEYCODE_DPAD_UP &&
                lv.selectedItemPosition <= 0) {
                (if (choixOnyx) ongletOnyx else ongletExterne).requestFocus(); true
            } else false
        }
        val dm = ctx.resources.displayMetrics
        feuille.addView(lv, LinearLayout.LayoutParams(-1, (dm.heightPixels * 0.55f).toInt()))
        racine.addView(feuille, FrameLayout.LayoutParams(
            minOf(s.dp(640), (dm.widthPixels * 0.9f).toInt()), -2, Gravity.CENTER))

        etat = tv; compteur = cpt; liste = lv
        peindre()
        fermeParCode = false
        signature = emptyList()
        dialog = Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            setContentView(racine)
            setOnDismissListener {
                handler.removeCallbacks(tic)
                if (!fermeParCode) onFermer()
            }
        }
        rafraichir(true)
        dialog?.show()
        lv.requestFocus()
        handler.postDelayed(tic, 800)
    }

    /** Le ViewModel extrait ce serveur (aussi quand le choix vient d'ailleurs). */
    fun marquerChargement(srv: Video.Server) {
        if (!visible) return
        enCours = srv.id
        message = "Ouverture de ${srv.name}…"
        rafraichir(true)
    }

    /** La vidéo est partie dans le lecteur externe : la fenêtre reste derrière VLC. */
    fun enLecture(srv: Video.Server) {
        enCours = srv.id
        message = "Lecture de ${srv.name} dans le lecteur externe…"
        rafraichir(true)
    }

    /** Le dernier serveur envoyé au lecteur externe n'a pas marché (retour immédiat) → ✕. */
    fun marquerEchecDernier() {
        enCours?.let { echoues += it }
        enCours = null
    }

    fun echec(srv: Video.Server) {
        echoues += srv.id
        enCours = null
        message = "${srv.name} ne répond pas, choisis-en un autre."
        rafraichir(true)
    }

    /** Ferme sans déclencher [onFermer]. */
    fun fermer() {
        fermeParCode = true
        handler.removeCallbacks(tic)
        try { dialog?.dismiss() } catch (_: Exception) {}
        dialog = null
    }

    private fun rafraichir(force: Boolean = false) {
        val l = serveurs()
        val nouvelle = l.map { "${it.id}|${it.name}" }
        if (force || nouvelle != signature) {
            // Pas de notify inutile toutes les 800 ms (garde la sélection télécommande stable).
            signature = nouvelle
            affiches.clear(); affiches.addAll(l)
            adaptateur.notifyDataSetChanged()
        }
        compteur?.text = l.size.toString()
        etat?.text = message ?: when {
            l.isEmpty() -> "Recherche des serveurs…"
            choixOnyx -> "Choisis un serveur : il démarrera dans le lecteur ONYX."
            else -> "Choisis un serveur : il s'ouvrira dans le lecteur externe."
        }
    }
}
