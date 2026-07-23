package com.streamflixreborn.streamflix.car

import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import com.streamflixreborn.streamflix.utils.RadioCatalog
import com.streamflixreborn.streamflix.utils.RadioFavoritesStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 2026-07-23 (projet Android Auto, LAB) — LISTE RADIO (surface voiture).
 * Stations depuis RadioCatalog. Lecture via CarRadioController (persiste à la navigation).
 * Favoris : bouton filtre « Favoris » + bouton ❤ qui (dé)favorise la station EN LECTURE
 * (Android Auto n'autorise pas l'appui long sur une ligne → on favorise la station courante).
 */
@androidx.media3.common.util.UnstableApi
class OnyxCarListScreen(
    carContext: CarContext,
    private val kind: Kind,
) : Screen(carContext) {

    enum class Kind(val title: String) {
        FAVORITES("Favoris"),
        CONTINUE("Continuer à regarder"),
        RADIO("Radio"),
    }

    private data class RadioItem(val id: String, val name: String, val url: String, val fallbacks: List<String>)

    private var rows: List<RadioItem> = emptyList()
    private var loading = true
    private var showFavoritesOnly = false

    init {
        lifecycleScope.launch {
            rows = withContext(Dispatchers.IO) { runCatching { loadRadios() }.getOrDefault(emptyList()) }
            loading = false
            invalidate()
        }
    }

    private suspend fun loadRadios(): List<RadioItem> {
        if (kind != Kind.RADIO) return emptyList()
        val stations = runCatching { RadioCatalog.list() }.getOrDefault(emptyList())
        val items = stations
            .filter { !it.streamUrl.isNullOrBlank() }
            .map { RadioItem(it.id, it.name, it.streamUrl!!, it.fallbackUrls) }
        Log.i(TAG, "radios chargées: ${items.size}")
        return items
    }

    override fun onGetTemplate(): Template {
        if (loading) {
            return ListTemplate.Builder()
                .setLoading(true)
                .setTitle(kind.title)
                .setHeaderAction(Action.BACK)
                .build()
        }

        val favIds = runCatching { RadioFavoritesStore.all() }.getOrDefault(emptySet())
        val playing = CarRadioController.currentName
        val visible = if (showFavoritesOnly) rows.filter { it.id in favIds } else rows

        val limit = runCatching {
            carContext.getCarService(androidx.car.app.constraints.ConstraintManager::class.java)
                .getContentLimit(androidx.car.app.constraints.ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        }.getOrDefault(12)

        val builder = ItemList.Builder()
        if (visible.isEmpty()) {
            builder.setNoItemsMessage(
                if (showFavoritesOnly) "Aucun favori — ajoute-les dans ONYX sur le téléphone"
                else "Aucune radio disponible",
            )
        } else {
            visible.take(limit).forEach { item ->
                val row = Row.Builder().setTitle(item.name.take(60))
                val marks = buildList {
                    if (item.name == playing) add("▶ en lecture")
                    if (item.id in favIds) add("★")
                }
                if (marks.isNotEmpty()) row.addText(marks.joinToString("  "))
                row.setOnClickListener {
                    CarRadioController.play(carContext, item.name, item.url, item.fallbacks)
                    invalidate()
                }
                builder.addItem(row.build())
            }
        }

        // ⚠ ListTemplate ActionStrip = MAX 1 action AVEC TITRE TEXTE (+ 1 action en ICÔNE).
        //   Au-delà : IllegalArgumentException « Action list exceeded max number of 1 actions with
        //   custom titles » → CRASH. Donc : Favoris = ICÔNE étoile (toggle filtre) ; Arrêter = TITRE.
        val strip = ActionStrip.Builder()
        val starIconRes = if (showFavoritesOnly)
            com.streamflixreborn.streamflix.R.drawable.ic_favorite_enable
        else com.streamflixreborn.streamflix.R.drawable.ic_star
        strip.addAction(
            Action.Builder()
                .setIcon(
                    androidx.car.app.model.CarIcon.Builder(
                        androidx.core.graphics.drawable.IconCompat.createWithResource(carContext, starIconRes),
                    ).build(),
                )
                .setOnClickListener {
                    showFavoritesOnly = !showFavoritesOnly
                    invalidate()
                }
                .build(),
        )
        if (playing != null) {
            strip.addAction(
                Action.Builder()
                    .setTitle("Arrêter")
                    .setOnClickListener {
                        CarRadioController.stop()
                        invalidate()
                    }
                    .build(),
            )
        }

        return ListTemplate.Builder()
            .setSingleList(builder.build())
            .setTitle(kind.title)
            .setHeaderAction(Action.BACK)
            .setActionStrip(strip.build())
            .build()
    }

    companion object {
        private const val TAG = "OnyxCarList"
    }
}
