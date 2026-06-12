package com.streamflixreborn.streamflix.providers

import android.content.Context
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * 2026-06-10 — Stockage des sources World TV personnalisées (= playlists JSON
 *  format 3box-tv). L'user peut en ajouter plusieurs et choisir l'active.
 *  Similar à Wiseplay où on ajoute plusieurs playlists et on les liste.
 *
 * Storage SharedPrefs : JSON array de {name, url}.
 * activeIndex = index dans la liste de la source active (0 par défaut = la
 *  source built-in si pas d'autres ajoutées).
 *
 * URL par défaut = box.xtemus.com (= la source historique du provider).
 */
object WorldLiveSourcesStore {
    private const val PREF_LIST = "world_live_sources_list"
    private const val PREF_ACTIVE = "world_live_sources_active_index"
    data class Source(val name: String, val url: String, val isBuiltin: Boolean = false)

    /** 2026-06-10 (user "ajouter toutes les sources qu'on connaît déjà") :
     *  catalogues populaires pré-remplis. Activable en un clic.
     *  Note : seules les URLs PUBLIQUES sont incluses. La source par défaut
     *  de l'app (built-in World TV) reste accessible automatiquement comme
     *  fallback si rien n'est sélectionné. */
    // 2026-06-11 (user "mettre 3boxTv en priorité pour les nouveaux users") :
    //   3boxTv v2 en index 0 (= défaut) car c'est la playlist compatible
    //   avec notre GenericStreamResolver asm168 (= TF1/F2/M6/Canal+ live FR
    //   qui marche tout seul, sans hardcoding).
    //   World TV (= 18k chaînes mais lent au boot 22s + flux moins fiables)
    //   passe en index 1 (= toujours dispo via le picker source).
    //   Dric4rTV reste en index 2.
    val BUILTIN_SOURCES: List<Source> = listOf(
        Source("3box TV (par défaut)", "https://box.xtemus.com/?playlist=u256y494u21596x2", isBuiltin = true),
        // 2026-06-12 : iptv-org est une playlist M3U publique communautaire
        //   maintenue par une org GitHub sérieuse. Contient TF1, France 2-5,
        //   M6, Canal+, BFM, etc. avec des URLs CDN officielles. Marche
        //   depuis Tahiti.
        Source("iptv-org FR (alternative)", "https://iptv-org.github.io/iptv/countries/fr.m3u", isBuiltin = true),
        Source("World TV", "https://box.xtemus.com/?playlist=y274y486q2x2841586r2", isBuiltin = true),
        Source("Dric4rTV", "http://dric4rt.free.fr/1.json", isBuiltin = true),
    )

    /** Liste des sources stockées (= persos seulement, sans les built-in). */
    fun list(context: Context): List<Source> {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        val raw = sp.getString(PREF_LIST, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull {
                val o = arr.optJSONObject(it) ?: return@mapNotNull null
                Source(o.optString("name").trim(), o.optString("url").trim())
            }.filter { it.name.isNotBlank() && it.url.isNotBlank() }
        } catch (_: Throwable) { emptyList() }
    }

    /** Toutes les sources (built-in connues + ajoutées par l'user). */
    fun allSources(context: Context): List<Source> {
        val out = mutableListOf<Source>()
        out.addAll(BUILTIN_SOURCES)
        out.addAll(list(context))
        return out
    }

    fun add(context: Context, name: String, url: String) {
        val current = list(context).toMutableList()
        current.add(Source(name.trim(), url.trim()))
        save(context, current)
    }

    /** index = index dans la liste DES SOURCES PERSO uniquement (sans built-in). */
    fun remove(context: Context, index: Int) {
        val current = list(context).toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            save(context, current)
            // Si la source active était dans les persos et après index supprimé,
            // réajuster (activeIndex référence allSources, donc personIndex = activeIdx - builtinCount).
            val activeIdx = getActiveIndex(context)
            val builtinCount = BUILTIN_SOURCES.size
            if (activeIdx >= builtinCount && (activeIdx - builtinCount) >= index) {
                setActiveIndex(context, (activeIdx - 1).coerceAtLeast(0))
            }
        }
    }

    fun update(context: Context, index: Int, name: String, url: String) {
        val current = list(context).toMutableList()
        if (index in current.indices) {
            current[index] = Source(name.trim(), url.trim())
            save(context, current)
        }
    }

    private fun save(context: Context, list: List<Source>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("name", it.name).put("url", it.url))
        }
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putString(PREF_LIST, arr.toString()).apply()
    }

    /** Index dans allSources() (0 = built-in). */
    fun getActiveIndex(context: Context): Int {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getInt(PREF_ACTIVE, 0).coerceAtLeast(0)
    }

    fun setActiveIndex(context: Context, index: Int) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putInt(PREF_ACTIVE, index.coerceAtLeast(0)).apply()
    }

    /** URL de la source actuellement active. */
    fun getActiveUrl(context: Context): String {
        val all = allSources(context)
        val idx = getActiveIndex(context).coerceIn(0, all.size - 1)
        return all[idx].url
    }

    fun getActiveName(context: Context): String {
        val all = allSources(context)
        val idx = getActiveIndex(context).coerceIn(0, all.size - 1)
        return all[idx].name
    }
}
