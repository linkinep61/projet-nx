package com.streamflixreborn.streamflix.utils

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.streamflixreborn.streamflix.R

/**
 * « Gérer les sources » — panneau unique des sources de backup ET des extracteurs.
 *
 * 2026-08-11 (user : « transforme le panneau Gérer les sources comme tu as fait pour la radio
 *   et les autres, pour qu'il soit confortable à utiliser, et que les options Quitter / Annuler
 *   / OK soient au tout début, qu'on soit pas obligé de défiler jusqu'en bas pour tout ça ») :
 *
 *   L'AlertDialog d'origine mettait ses boutons SOUS la liste. Avec 152 lignes, valider
 *   demandait de défiler jusqu'au bout — et sur une liste dont on vient de tout décocher,
 *   c'est le geste le plus pénible qui soit. L'en-tête est donc FIXE en haut : titre, actions,
 *   bascule « tout », recherche. Seule la liste défile.
 *
 *   Palette et gestes repris de [RadioPickerDialog] pour rester cohérent avec le reste.
 *
 * ── CE QUE LE PANNEAU RÉUNIT (et pourquoi) ────────────────────────────────────────────────
 *   • SOURCES de backup — Cloudstream, Movix, Wiflix… (user : « si je veux désactiver
 *     Cloudstream, ils doivent être affichés au même endroit »). Décochée = jamais interrogée.
 *   • EXTRACTEURS — LuluVdo, Emmmmbed… Décoché = ses liens sortent du pool.
 *   • Noms désactivés HORS registre, pour qu'un hébergeur coupé par appui long reste toujours
 *     recochable ici (sinon : invisible et irrécupérable).
 *
 *   Les DÉSACTIVÉS sont remontés en tête (user : « pour qu'ils soient réactivables plus
 *   facilement »).
 */
object SourcesPickerDialog {

    private const val SURFACE = "#14171C"
    private const val CARD = "#20242C"
    private const val CARD_DARK = "#1B1E24"
    private const val BORDER = "#2F343E"
    private const val DIVIDER = "#23262E"
    private const val ACCENT = "#4C8DF6"
    private const val DANGER = "#E2574C"
    private const val TXT = "#F5F6F8"
    private const val TXT2 = "#9AA0AA"

    private data class Ligne(
        val cle: String,
        val libelle: String,
        val estSource: Boolean,
        val estMaitre: Boolean = false,
    )

    fun show(ctx: Context) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun rounded(fill: String, radius: Int, stroke: String? = null) =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(radius).toFloat()
                setColor(Color.parseColor(fill))
                if (stroke != null) setStroke(dp(1), Color.parseColor(stroke))
            }

        val estTv = runCatching {
            val pm = ctx.packageManager
            pm.hasSystemFeature("android.software.leanback") ||
                !pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)
        }.getOrDefault(false)
        fun focusTv(v: View) {
            if (estTv) v.foreground = androidx.core.content.ContextCompat
                .getDrawable(ctx, R.drawable.bg_focus_white_border)
            v.isFocusable = true
        }

        val store = ExtractorToggleStore
        val sourcesBackup = BackupRegistry.BACKUP_SOURCES
        val actives = UserPreferences.sourcesBackupActives().toMutableSet()
        val disabled = store.getDisabled().toMutableSet()
        val providerName = UserPreferences.currentProvider?.name ?: ""
        val favoris = if (providerName.isNotEmpty())
            store.getFavorites(providerName).toMutableSet() else mutableSetOf()

        val noms = (store.allExtractorNames() + store.desactivesHorsRegistre())
            .distinctBy { it.lowercase() }

        fun estActive(l: Ligne) =
            if (l.estSource) l.cle in actives else l.cle !in disabled

        // 2026-08-11 (user : « NetMirror est passé à la trappe, il s'est affiché quand même ») :
        //   TROISIÈME famille — les PROVIDERS interrogés en backup par la boucle générique du
        //   registre. Ils ne sont ni dans BACKUP_SOURCES ni parmi les extracteurs, donc ils
        //   n'apparaissaient nulle part et étaient indésactivables. Les logs montraient
        //   NetMirror en train d'interroger Netflix/Prime Video pendant que tout était décoché.
        val clesSources = sourcesBackup.map { it.first }.toSet()
        val providers = runCatching {
            com.streamflixreborn.streamflix.providers.Provider.providers.keys
                .map { it.name }
                .filter { it.isNotBlank() && it !in clesSources }
                .distinct()
        }.getOrDefault(emptyList())

        // Un provider est actif par défaut : on ne le considère coupé que s'il a été refusé
        // explicitement. Sans cette amorce, ils apparaîtraient tous décochés au 1ᵉʳ ouverture.
        val refusees = UserPreferences.sourcesRefusees()
        providers.forEach { if (it !in refusees) actives.add(it) }
        val univers = clesSources + providers.toSet()

        val toutes = buildList {
            sourcesBackup.forEach { (cle, libelle) -> add(Ligne(cle, libelle, true)) }
            providers.forEach { nom -> add(Ligne(nom, nom, true)) }
            noms.forEach { nom -> add(Ligne(nom.lowercase(), nom, false)) }
        }

        // Liste affichée : maître en tête, puis désactivés, puis le reste — alphabétique.
        var filtre = ""
        fun construire(): List<Ligne> {
            val visibles = toutes
                .filter { filtre.isBlank() || it.libelle.contains(filtre, ignoreCase = true) }
                .sortedWith(
                    compareBy<Ligne> { if (estActive(it)) 1 else 0 }
                        .thenBy { it.libelle.lowercase() },
                )
            return listOf(Ligne("", "", false, estMaitre = true)) + visibles
        }

        var lignes = construire()

        // ── conteneur ────────────────────────────────────────────────────────────────
        val racine = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(SURFACE, 20)
            setPadding(dp(14), dp(14), dp(14), dp(10))
        }

        val titre = TextView(ctx).apply {
            text = if (providerName.isNotEmpty()) "Gérer les sources — $providerName"
                   else "Gérer les sources"
            setTextColor(Color.parseColor(TXT))
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        racine.addView(titre)

        val compteur = TextView(ctx).apply {
            setTextColor(Color.parseColor(TXT2))
            textSize = 12f
            setPadding(0, dp(2), 0, dp(10))
        }
        racine.addView(compteur)

        // ── actions EN HAUT (la demande du user) ─────────────────────────────────────
        val barre = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(10))
        }
        fun bouton(libelle: String, couleurFond: String, couleurTexte: String): TextView =
            TextView(ctx).apply {
                text = libelle
                setTextColor(Color.parseColor(couleurTexte))
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                background = rounded(couleurFond, 10, BORDER)
                setPadding(dp(10), dp(9), dp(10), dp(9))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(6) }
                focusTv(this)
            }

        val dialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)

        val btnOk = bouton("Enregistrer", ACCENT, "#FFFFFF")
        val btnAnnuler = bouton("Annuler", CARD, TXT2)
        val btnReinit = bouton("Réinitialiser", CARD, TXT2)
        barre.addView(btnOk)
        barre.addView(btnAnnuler)
        barre.addView(btnReinit)
        racine.addView(barre)

        // ── recherche ────────────────────────────────────────────────────────────────
        val recherche = EditText(ctx).apply {
            hint = "Rechercher une source ou un extracteur…"
            setHintTextColor(Color.parseColor(TXT2))
            setTextColor(Color.parseColor(TXT))
            textSize = 14f
            background = rounded(CARD_DARK, 10, BORDER)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            maxLines = 1
            isSingleLine = true
        }
        racine.addView(recherche)

        val liste = ListView(ctx).apply {
            divider = android.graphics.drawable.ColorDrawable(Color.parseColor(DIVIDER))
            dividerHeight = 1
            itemsCanFocus = true   // TV : le D-pad doit atteindre la case, pas la rangée
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            ).apply { topMargin = dp(10) }
        }
        racine.addView(liste)

        lateinit var adaptateur: BaseAdapter

        fun majCompteur() {
            val coupes = toutes.count { !estActive(it) }
            compteur.text = if (coupes == 0) "${toutes.size} au total · aucune désactivée"
                else "${toutes.size} au total · $coupes désactivée${if (coupes > 1) "s" else ""} (en haut)"
        }

        adaptateur = object : BaseAdapter() {
            override fun getCount() = lignes.size
            override fun getItem(p: Int) = lignes[p]
            override fun getItemId(p: Int) = p.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val vue = convertView ?: android.view.LayoutInflater.from(ctx)
                    .inflate(R.layout.item_extractor_toggle, parent, false)
                val cb = vue.findViewById<CheckBox>(R.id.cb_enabled)
                val coeur = vue.findViewById<ImageView>(R.id.iv_favorite)
                val ligne = lignes[position]

                cb.setOnCheckedChangeListener(null)
                cb.setTextColor(Color.parseColor(TXT))

                if (ligne.estMaitre) {
                    coeur.visibility = View.GONE
                    val toutActif = toutes.all { estActive(it) }
                    cb.text = if (toutActif) "TOUT DÉSACTIVER" else "TOUT ACTIVER"
                    cb.setTextColor(Color.parseColor(if (toutActif) DANGER else ACCENT))
                    cb.isChecked = toutActif
                    cb.setOnCheckedChangeListener { _, coche ->
                        toutes.forEach { l ->
                            if (l.estSource) {
                                if (coche) actives.add(l.cle) else actives.remove(l.cle)
                            } else {
                                if (coche) disabled.remove(l.cle) else disabled.add(l.cle)
                            }
                        }
                        majCompteur()
                        notifyDataSetChanged()
                    }
                    focusTv(cb)
                    return vue
                }

                cb.text = if (ligne.estSource) "Source · ${ligne.libelle}" else ligne.libelle
                cb.isChecked = estActive(ligne)
                cb.setOnCheckedChangeListener { _, coche ->
                    if (ligne.estSource) {
                        if (coche) actives.add(ligne.cle) else actives.remove(ligne.cle)
                    } else {
                        if (coche) disabled.remove(ligne.cle) else disabled.add(ligne.cle)
                    }
                    majCompteur()
                }
                focusTv(cb)

                // Le cœur « favori » ne concerne que les extracteurs.
                if (ligne.estSource || providerName.isEmpty()) {
                    coeur.visibility = View.GONE
                } else {
                    coeur.visibility = View.VISIBLE
                    fun icone(fav: Boolean) {
                        coeur.setImageResource(
                            if (fav) R.drawable.ic_favorite_enable else R.drawable.ic_favorite_disable,
                        )
                        coeur.alpha = if (fav) 1.0f else 0.4f
                    }
                    icone(ligne.cle in favoris)
                    coeur.setOnClickListener {
                        if (ligne.cle in favoris) favoris.remove(ligne.cle) else favoris.add(ligne.cle)
                        icone(ligne.cle in favoris)
                    }
                    cb.nextFocusRightId = R.id.iv_favorite
                    coeur.nextFocusLeftId = R.id.cb_enabled
                }
                return vue
            }
        }
        liste.adapter = adaptateur
        majCompteur()

        recherche.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                filtre = s?.toString()?.trim().orEmpty()
                lignes = construire()
                adaptateur.notifyDataSetChanged()
                liste.setSelection(0)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        btnOk.setOnClickListener {
            store.setDisabled(disabled - store.forces)
            UserPreferences.setSourcesBackupActives(actives, univers)
            if (providerName.isNotEmpty()) store.setFavorites(providerName, favoris)
            val coupes = toutes.count { !estActive(it) }
            Toast.makeText(
                ctx,
                if (coupes == 0) "Toutes les sources activées"
                else "$coupes désactivée${if (coupes > 1) "s" else ""}",
                Toast.LENGTH_SHORT,
            ).show()
            dialog.dismiss()
        }
        btnAnnuler.setOnClickListener { dialog.dismiss() }
        btnReinit.setOnClickListener {
            disabled.clear()
            actives.clear()
            actives.addAll(univers)
            lignes = construire()
            majCompteur()
            adaptateur.notifyDataSetChanged()
        }

        dialog.setContentView(racine)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xCC000000.toInt()))
            val largeur = (ctx.resources.displayMetrics.widthPixels * if (estTv) 0.55 else 0.92).toInt()
            val hauteur = (ctx.resources.displayMetrics.heightPixels * 0.88).toInt()
            setLayout(largeur, hauteur)
        }
        dialog.show()
    }
}
