package com.streamflixreborn.streamflix.activities

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
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
 * 2026-06-09 (user "une API avec autant d'images ça doit être sympa et surtout
 * en 4K") : galerie en ligne de wallpapers via Wallhaven.cc.
 *
 * UI : EditText de recherche + RecyclerView en grille. Tap sur une miniature
 * → dialog preview + bouton "Appliquer comme fond". L'image est téléchargée
 * dans le cache local de l'app puis settée via AppearanceManager.
 *
 * Pagination simple : scroll en bas → charge la page suivante.
 */
class WallhavenGalleryActivity : AppCompatActivity() {

    private lateinit var etSearch: EditText
    private lateinit var btnSearch: Button
    private lateinit var pbLoading: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var rv: RecyclerView

    private val items = mutableListOf<WallhavenService.Wallpaper>()
    private val adapter = WallpapersAdapter()

    private var currentQuery: String = ""
    private var currentPage: Int = 1
    private var lastPage: Int = 1
    private var loading: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wallhaven_gallery)

        etSearch = findViewById(R.id.et_search)
        btnSearch = findViewById(R.id.btn_search)
        pbLoading = findViewById(R.id.pb_loading)
        tvEmpty = findViewById(R.id.tv_empty)
        rv = findViewById(R.id.rv_wallpapers)

        // Grille : 2 colonnes sur mobile, 4 sur TV/grand écran
        val colCount = if (isTvLayout()) 4 else 2
        rv.layoutManager = GridLayoutManager(this, colCount)
        rv.adapter = adapter

        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (loading || currentPage >= lastPage) return
                val lm = rv.layoutManager as GridLayoutManager
                if (lm.findLastVisibleItemPosition() >= items.size - 4) {
                    fetchNextPage()
                }
            }
        })

        btnSearch.setOnClickListener { runSearch() }
        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch()
                true
            } else false
        }

        // Première charge : top wallpapers (sans query).
        runSearch()
    }

    private fun isTvLayout(): Boolean = try {
        packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
    } catch (_: Throwable) { false }

    private fun runSearch() {
        currentQuery = etSearch.text.toString().trim()
        currentPage = 1
        items.clear()
        adapter.notifyDataSetChanged()
        // Hide keyboard
        try {
            getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(
                etSearch.windowToken, 0
            )
        } catch (_: Throwable) {}
        fetchPage(1, replace = true)
    }

    private fun fetchNextPage() {
        fetchPage(currentPage + 1, replace = false)
    }

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
                Toast.makeText(this@WallhavenGalleryActivity,
                    "Erreur réseau : ${e.message}", Toast.LENGTH_LONG).show()
                if (items.isEmpty()) tvEmpty.visibility = View.VISIBLE
            }
            result.onSuccess { sr ->
                lastPage = sr.lastPage
                currentPage = sr.currentPage
                val sizeBefore = items.size
                if (replace) {
                    items.clear()
                    items.addAll(sr.wallpapers)
                    adapter.notifyDataSetChanged()
                } else {
                    items.addAll(sr.wallpapers)
                    adapter.notifyItemRangeInserted(sizeBefore, sr.wallpapers.size)
                }
                if (items.isEmpty()) tvEmpty.visibility = View.VISIBLE
            }
        }
    }

    private fun onWallpaperClicked(wp: WallhavenService.Wallpaper) {
        // Dialog preview avec bouton Appliquer.
        val dialogView = LayoutInflater.from(this)
            .inflate(R.layout.item_wallhaven_thumb, null, false)
        val iv = dialogView.findViewById<ImageView>(R.id.iv_thumb)
        Glide.with(this)
            .load(wp.fullUrl)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .into(iv)
        AlertDialog.Builder(this)
            .setTitle("Aperçu (${wp.resolution})")
            .setView(dialogView)
            .setPositiveButton("Appliquer") { dlg, _ ->
                dlg.dismiss()
                applyWallpaper(wp)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun applyWallpaper(wp: WallhavenService.Wallpaper) {
        pbLoading.visibility = View.VISIBLE
        Toast.makeText(this, "Téléchargement…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val file = WallhavenService.downloadToLocal(this@WallhavenGalleryActivity, wp)
            pbLoading.visibility = View.GONE
            if (file == null) {
                Toast.makeText(this@WallhavenGalleryActivity,
                    "Échec du téléchargement", Toast.LENGTH_LONG).show()
                return@launch
            }
            val uri = android.net.Uri.fromFile(file)
            AppearanceManager.setWallpaperUri(this@WallhavenGalleryActivity, uri)
            Toast.makeText(this@WallhavenGalleryActivity,
                "Fond appliqué — reviens à l'accueil pour voir le résultat",
                Toast.LENGTH_LONG).show()
            finish()
        }
    }

    inner class WallpapersAdapter : RecyclerView.Adapter<WallpaperVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WallpaperVH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_wallhaven_thumb, parent, false)
            return WallpaperVH(v)
        }
        override fun onBindViewHolder(holder: WallpaperVH, position: Int) {
            val wp = items[position]
            holder.bind(wp)
        }
        override fun getItemCount(): Int = items.size
    }

    inner class WallpaperVH(view: View) : RecyclerView.ViewHolder(view) {
        private val iv: ImageView = view.findViewById(R.id.iv_thumb)
        private val tvRes: TextView = view.findViewById(R.id.tv_resolution)
        fun bind(wp: WallhavenService.Wallpaper) {
            Glide.with(itemView.context)
                .load(wp.thumbUrl)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .into(iv)
            tvRes.text = wp.resolution
            itemView.setOnClickListener { onWallpaperClicked(wp) }
        }
    }
}
