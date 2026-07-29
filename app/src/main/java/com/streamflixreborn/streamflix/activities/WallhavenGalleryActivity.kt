package com.streamflixreborn.streamflix.activities

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.utils.AppearanceManager
import com.streamflixreborn.streamflix.utils.WallhavenService
import kotlinx.coroutines.launch

/**
 * 2026-06-09 : galerie de fonds d'écran (Wallhaven.cc).
 *
 * 2026-07-25 (user « améliore le système de fond d'écran comme la radio ») : REFONTE. Feuille sombre,
 *   en-tête, rangée de CATÉGORIES/playlists (intégrées + perso), barre de recherche arrondie, ajout
 *   par URL (🔗), grille de vignettes arrondies, et APERÇU plein écran sombre (Appliquer/Annuler) —
 *   remplace l'ancien aperçu cassé.
 */
class WallhavenGalleryActivity : AppCompatActivity() {

    private lateinit var etSearch: EditText
    private lateinit var pbLoading: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var rv: RecyclerView
    private lateinit var llCategories: LinearLayout

    private val items = mutableListOf<WallhavenService.Wallpaper>()
    private val adapter = WallpapersAdapter()

    private var currentQuery: String = ""
    private var currentPage: Int = 1
    private var lastPage: Int = 1
    private var loading: Boolean = false

    // Catégories intégrées : libellé → requête (vide = suggérés/top).
    private val builtInCategories = listOf(
        "Suggérés" to "",
        "Cinéma" to "movie cinematic",
        "Nature" to "nature landscape",
        "Anime" to "anime",
        "Espace" to "space galaxy",
        "Ville" to "city night",
        "Sombre" to "dark amoled minimal",
        "Abstrait" to "abstract",
    )
    private var activeCategory: String = "Suggérés"

    private val prefs by lazy { getSharedPreferences("wallpaper_gallery", MODE_PRIVATE) }
    private fun customCategories(): List<String> =
        prefs.getStringSet("custom_cats", emptySet())?.toList()?.sorted() ?: emptyList()
    private fun addCustomCategory(kw: String) {
        val set = prefs.getStringSet("custom_cats", emptySet())!!.toMutableSet()
        set.add(kw)
        prefs.edit().putStringSet("custom_cats", set).apply()
    }
    // 2026-07-29 (user : « une recherche/thème mal marqué ne peut pas être supprimé ») — retrait
    //   d'un thème personnalisé (appui long sur son chip).
    private fun removeCustomCategory(kw: String) {
        val set = prefs.getStringSet("custom_cats", emptySet())!!.toMutableSet()
        set.remove(kw)
        prefs.edit().putStringSet("custom_cats", set).apply()
    }

    // ── Helpers style ──────────────────────────────────────────────────────────
    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private fun rounded(fill: String, radius: Int, stroke: String? = null, strokeW: Int = 1) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radius).toFloat()
            setColor(Color.parseColor(fill))
            if (stroke != null) setStroke(dp(strokeW), Color.parseColor(stroke))
        }

    private val isTv by lazy { isTvLayout() }
    // Liseré blanc de focus visible à la télécommande (n'apparaît qu'en état focus → inoffensif ailleurs).
    private fun tvFocus(v: View) {
        if (isTv) {
            v.isFocusable = true
            v.foreground = androidx.core.content.ContextCompat.getDrawable(
                this, com.streamflixreborn.streamflix.R.drawable.bg_focus_white_border)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wallhaven_gallery)

        etSearch = findViewById(R.id.et_search)
        pbLoading = findViewById(R.id.pb_loading)
        tvEmpty = findViewById(R.id.tv_empty)
        rv = findViewById(R.id.rv_wallpapers)
        llCategories = findViewById(R.id.ll_categories)

        findViewById<View>(R.id.search_row).background = rounded("#20242C", 12, "#2F343E", 1)
        findViewById<TextView>(R.id.tv_close).apply { setOnClickListener { finish() }; tvFocus(this) }
        findViewById<TextView>(R.id.btn_url).apply { setOnClickListener { promptUrl() }; tvFocus(this) }
        tvFocus(etSearch)

        val colCount = if (isTvLayout()) 4 else 2
        rv.layoutManager = GridLayoutManager(this, colCount)
        rv.adapter = adapter

        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (loading || currentPage >= lastPage) return
                val lm = rv.layoutManager as GridLayoutManager
                if (lm.findLastVisibleItemPosition() >= items.size - 4) fetchNextPage()
            }
        })

        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                activeCategory = ""
                buildCategories()
                runSearch(etSearch.text.toString().trim())
                true
            } else false
        }

        buildCategories()
        runSearch("") // Suggérés au démarrage

        // TV : point d'entrée D-pad sur la 1re catégorie.
        if (isTv) llCategories.post { llCategories.getChildAt(0)?.requestFocus() }
    }

    private fun buildCategories() {
        llCategories.removeAllViews()
        val customs = customCategories()
        val all = builtInCategories.map { it.first to it.second } +
            customs.map { it to it } +
            listOf("＋ Playlist" to "__add__")
        all.forEach { (label, query) ->
            val isAdd = query == "__add__"
            val isCustom = !isAdd && customs.contains(label) // thème ajouté par l'utilisateur
            val active = label == activeCategory && !isAdd
            val chip = TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(Color.parseColor(if (active) "#FFFFFF" else if (isAdd) "#8E7BE0" else "#C9CDD4"))
                background = if (active) rounded("#E23B3B", 999)
                    else rounded("#20242C", 999, "#2F343E", 1)
                setPadding(dp(14), dp(7), dp(14), dp(7))
                isClickable = true; isFocusable = true
                setOnClickListener {
                    if (isAdd) { promptCustomCategory(); return@setOnClickListener }
                    activeCategory = label
                    etSearch.setText("")
                    buildCategories()
                    runSearch(query)
                }
                // 2026-07-29 : appui long sur un thème PERSO (bouton violet) → suppression.
                if (isCustom) {
                    setOnLongClickListener {
                        val msg = TextView(this@WallhavenGalleryActivity).apply {
                            text = "Supprimer le thème « $label » ?"
                            setTextColor(Color.parseColor("#C9CDD4")); textSize = 15f
                            setPadding(dp(4), dp(4), dp(4), dp(4))
                        }
                        darkDialog("Supprimer", msg) {
                            removeCustomCategory(label)
                            if (activeCategory == label) { activeCategory = "Suggérés"; runSearch("") }
                            buildCategories()
                        }
                        true
                    }
                }
            }
            tvFocus(chip)
            llCategories.addView(chip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) })
        }
    }

    private fun promptCustomCategory() {
        val input = darkInput("Mot-clé (ex : voiture, montagne…)")
        darkDialog("Ajouter une playlist", input) {
            val kw = input.text?.toString()?.trim().orEmpty()
            if (kw.isNotBlank()) {
                addCustomCategory(kw)
                activeCategory = kw
                buildCategories()
                runSearch(kw)
            }
        }
    }

    private fun promptUrl() {
        val input = darkInput("https://exemple.com/image.jpg")
        darkDialog("Ajouter une image par URL", input) {
            val url = input.text?.toString()?.trim().orEmpty()
            if (url.startsWith("http")) {
                applyWallpaper(WallhavenService.Wallpaper("url", url, url, "", emptyList()))
            } else if (url.isNotEmpty()) {
                Toast.makeText(this, "L'URL doit commencer par http", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun isTvLayout(): Boolean = try {
        packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
    } catch (_: Throwable) { false }

    private fun runSearch(query: String) {
        currentQuery = query
        currentPage = 1
        items.clear()
        adapter.notifyDataSetChanged()
        try {
            getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(etSearch.windowToken, 0)
        } catch (_: Throwable) {}
        fetchPage(1, replace = true)
    }

    private fun fetchNextPage() = fetchPage(currentPage + 1, replace = false)

    private fun fetchPage(page: Int, replace: Boolean) {
        loading = true
        pbLoading.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE
        lifecycleScope.launch {
            val ratio = if (isTvLayout()) "16x9" else null
            val result = WallhavenService.search(currentQuery, page, ratio)
            loading = false
            pbLoading.visibility = View.GONE
            if (isDestroyed) return@launch
            result.onFailure { e ->
                Toast.makeText(this@WallhavenGalleryActivity, "Erreur réseau : ${e.message}", Toast.LENGTH_LONG).show()
                if (items.isEmpty()) tvEmpty.visibility = View.VISIBLE
            }
            result.onSuccess { sr ->
                lastPage = sr.lastPage
                currentPage = sr.currentPage
                val sizeBefore = items.size
                if (replace) {
                    items.clear(); items.addAll(sr.wallpapers); adapter.notifyDataSetChanged()
                } else {
                    items.addAll(sr.wallpapers); adapter.notifyItemRangeInserted(sizeBefore, sr.wallpapers.size)
                }
                if (items.isEmpty()) tvEmpty.visibility = View.VISIBLE
            }
        }
    }

    // ── Aperçu plein écran sombre ────────────────────────────────────────────────
    private fun onWallpaperClicked(wp: WallhavenService.Wallpaper) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded("#15171C", 20)
            setPadding(dp(14), dp(14), dp(14), dp(12))
        }
        box.addView(TextView(this).apply {
            text = if (wp.resolution.isNotBlank()) "Aperçu · ${wp.resolution}" else "Aperçu"
            textSize = 16f; setTextColor(Color.parseColor("#F5F6F8"))
            setPadding(0, 0, 0, dp(12))
        })
        val img = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = rounded("#20242C", 12)
        }
        Glide.with(this).load(wp.fullUrl).diskCacheStrategy(DiskCacheStrategy.AUTOMATIC).into(img)
        box.addView(img, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(360)))

        val cancel = pillButton("Annuler", "#20242C", "#C9CDD4", "#2F343E")
        val apply = pillButton("Appliquer", "#E23B3B", "#FFFFFF", null)
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
        }
        btnRow.addView(cancel)
        btnRow.addView(apply, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(10) })
        box.addView(btnRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) })

        tvFocus(cancel); tvFocus(apply)
        val d = AlertDialog.Builder(this).setView(box).create()
        d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        cancel.setOnClickListener { d.dismiss() }
        apply.setOnClickListener { d.dismiss(); applyWallpaper(wp) }
        // TV : focus initial sur Appliquer pour pouvoir valider à la télécommande.
        d.setOnShowListener { apply.requestFocus() }
        d.show()
    }

    private fun pillButton(text: String, fill: String, textColor: String, stroke: String?) =
        TextView(this).apply {
            this.text = text; textSize = 14f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor(textColor))
            background = rounded(fill, 10, stroke, 1)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            isClickable = true; isFocusable = true
        }

    private fun darkInput(hint: String) = EditText(this).apply {
        this.hint = hint
        setSingleLine(true)
        setTextColor(Color.parseColor("#F5F6F8"))
        setHintTextColor(Color.parseColor("#7E848E"))
        background = rounded("#20242C", 12, "#2F343E", 1)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun darkDialog(title: String, input: View, onOk: () -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded("#15171C", 20)
            setPadding(dp(18), dp(18), dp(18), dp(14))
        }
        box.addView(TextView(this).apply {
            text = title; textSize = 17f; setTextColor(Color.parseColor("#F5F6F8"))
            setPadding(0, 0, 0, dp(14))
        })
        box.addView(input, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val cancel = pillButton("Annuler", "#20242C", "#C9CDD4", "#2F343E")
        val ok = pillButton("OK", "#E23B3B", "#FFFFFF", null)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        row.addView(cancel)
        row.addView(ok, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(10) })
        box.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) })
        tvFocus(cancel); tvFocus(ok)
        val d = AlertDialog.Builder(this).setView(box).create()
        d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        cancel.setOnClickListener { d.dismiss() }
        ok.setOnClickListener { d.dismiss(); onOk() }
        d.setOnShowListener { input.requestFocus() }
        d.show()
    }

    private fun applyWallpaper(wp: WallhavenService.Wallpaper) {
        pbLoading.visibility = View.VISIBLE
        Toast.makeText(this, "Téléchargement…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val file = WallhavenService.downloadToLocal(this@WallhavenGalleryActivity, wp)
            pbLoading.visibility = View.GONE
            if (file == null) {
                Toast.makeText(this@WallhavenGalleryActivity, "Échec du téléchargement", Toast.LENGTH_LONG).show()
                return@launch
            }
            AppearanceManager.setWallpaperUri(this@WallhavenGalleryActivity, android.net.Uri.fromFile(file))
            Toast.makeText(this@WallhavenGalleryActivity,
                "Fond appliqué — reviens à l'accueil pour voir le résultat", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    inner class WallpapersAdapter : RecyclerView.Adapter<WallpaperVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WallpaperVH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_wallhaven_thumb, parent, false)
            return WallpaperVH(v)
        }
        override fun onBindViewHolder(holder: WallpaperVH, position: Int) = holder.bind(items[position])
        override fun getItemCount(): Int = items.size
    }

    inner class WallpaperVH(view: View) : RecyclerView.ViewHolder(view) {
        private val iv: ImageView = view.findViewById(R.id.iv_thumb)
        private val tvRes: TextView = view.findViewById(R.id.tv_resolution)
        fun bind(wp: WallhavenService.Wallpaper) {
            Glide.with(itemView.context).load(wp.thumbUrl).diskCacheStrategy(DiskCacheStrategy.AUTOMATIC).into(iv)
            tvRes.text = wp.resolution
            itemView.setOnClickListener { onWallpaperClicked(wp) }
            tvFocus(itemView)
        }
    }
}
