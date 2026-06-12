package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.streamflixreborn.streamflix.providers.Dric4rTvProvider
import com.streamflixreborn.streamflix.models.TvShow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * 2026-06-08 (user "à gauche du cœur tu vas mettre une petite radio. Dedans
 * dans un premier temps tu vas ramener les radios du TV hub utilisables sans
 * écran") : catalogue de chaînes radio accessibles depuis n'importe quel
 * écran de l'app via le bouton radio à côté du cœur.
 *
 * Sources :
 *  - Dric4rTV (cat R4di0) : 17 radios curated FR
 *  - RadioBrowser API : ~500 radios FR les plus populaires (par votes)
 *
 * Les IDs Dric4rTV gardent le préfixe `livehub::dric4rtv::r4di0::...` pour
 * que MiniPlayerController.isRadioChannel(id) renvoie true → pas de fullscreen.
 * Les IDs RadioBrowser ont le préfixe `radio::browser::<uuid>` (= toutes traitées
 * comme radio par isRadioChannel via le préfixe `radio::`).
 */
object RadioCatalog {

    private const val TAG = "RadioCatalog"

    @Volatile private var cache: List<RadioStation> = emptyList()
    @Volatile private var lastLoad = 0L
    private const val CACHE_TTL_MS = 30 * 60 * 1000L  // 30 min

    data class RadioStation(
        val id: String,
        val name: String,
        val poster: String?,
        val streamUrl: String? = null,  // URL directe pour les stations RadioBrowser
    )

    /** Retourne la liste complète des radios (Dric4rTV + RadioBrowser FR).
     *  Charge en parallèle au premier appel. Safe. */
    suspend fun list(): List<RadioStation> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (cache.isNotEmpty() && now - lastLoad < CACHE_TTL_MS) {
            return@withContext cache
        }
        try {
            val (dric, browser) = coroutineScope {
                val a = async { loadDric4rTvRadios() }
                val b = async { loadBrowserRadios() }
                a.await() to b.await()
            }
            val all = mergeAndDedup(dric, browser)
            if (all.isNotEmpty()) {
                cache = all
                lastLoad = now
                Log.d(TAG, "Loaded ${all.size} radios (Dric=${dric.size}, Browser=${browser.size})")
            }
            all.ifEmpty { cache }
        } catch (t: Throwable) {
            Log.w(TAG, "list() failed: ${t.message}")
            cache
        }
    }

    /** 2026-06-08 (user "tu peux pas faire un truc pour que ça s'affiche en
     *  différé ? On a déjà les premières chaînes qui s'affichent direct,
     *  le reste vient au fur et à mesure") : chargement progressif :
     *   1. Cache si frais → 1 emit final immédiat.
     *   2. Sinon : Dric4rTV (instant ~17 radios) → 1er emit.
     *      Puis RadioBrowser (~5-10s) → 2e emit avec toutes les radios.
     *
     *  Retourne un Flow<List<RadioStation>> auquel le dialog peut s'abonner. */
    fun loadProgressive(): kotlinx.coroutines.flow.Flow<List<RadioStation>> =
        kotlinx.coroutines.flow.flow {
            val now = System.currentTimeMillis()
            if (cache.isNotEmpty() && now - lastLoad < CACHE_TTL_MS) {
                emit(cache)
                return@flow
            }
            val dric = loadDric4rTvRadios()
            if (dric.isNotEmpty()) {
                val firstPass = mergeAndDedup(dric, emptyList())
                emit(firstPass)
            }
            val browser = loadBrowserRadios()
            val full = mergeAndDedup(dric, browser)
            if (full.isNotEmpty()) {
                cache = full
                lastLoad = System.currentTimeMillis()
                Log.d(TAG, "Progressive load complete: ${full.size} radios (Dric=${dric.size}, Browser=${browser.size})")
            }
            emit(full)
        }.flowOn(Dispatchers.IO)

    /** Merge + dedup (par nom lowercase trim). Dric4rTV d'abord (curated). */
    private fun mergeAndDedup(
        dric: List<RadioStation>,
        browser: List<RadioStation>,
    ): List<RadioStation> {
        val all = mutableListOf<RadioStation>()
        val seenNames = HashSet<String>()
        dric.forEach { if (seenNames.add(it.name.lowercase().trim())) all.add(it) }
        browser.forEach { if (seenNames.add(it.name.lowercase().trim())) all.add(it) }
        return all
    }

    private suspend fun loadDric4rTvRadios(): List<RadioStation> {
        return try {
            val sections = Dric4rTvProvider.getHome()
            val radioCat = sections.firstOrNull { cat ->
                cat.name.equals("R4di0", ignoreCase = true) ||
                cat.name.contains("R4di0", ignoreCase = true)
            }
            (radioCat?.list as? List<*>)?.mapNotNull { item ->
                val tv = item as? TvShow ?: return@mapNotNull null
                RadioStation(
                    id = tv.id,
                    name = tv.title,
                    poster = tv.poster,
                    streamUrl = null,
                )
            } ?: emptyList()
        } catch (t: Throwable) {
            Log.w(TAG, "loadDric4rTvRadios failed: ${t.message}")
            emptyList()
        }
    }

    private suspend fun loadBrowserRadios(): List<RadioStation> {
        return try {
            RadioBrowserClient.fetchFrenchStations(5000).map { s ->
                RadioStation(
                    id = "radio::browser::${s.uuid}",
                    name = s.name,
                    poster = s.favicon,
                    streamUrl = s.url,
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "loadBrowserRadios failed: ${t.message}")
            emptyList()
        }
    }

    /** Sous-liste favoris (= radios dont l'id est dans RadioFavoritesStore). */
    suspend fun favorites(): List<RadioStation> {
        val favIds = RadioFavoritesStore.all()
        if (favIds.isEmpty()) return emptyList()
        return list().filter { it.id in favIds }
    }
}
