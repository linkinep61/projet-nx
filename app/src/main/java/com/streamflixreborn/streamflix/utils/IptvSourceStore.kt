package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.models.IptvSource
import org.json.JSONArray
import org.json.JSONObject

/**
 * 2026-05-12 : persistance des sources IPTV configurées par l'utilisateur,
 * stockées en JSON dans SharedPreferences (même fichier que UserPreferences).
 *
 * Pattern identique à [ProfileStore] : pas de DB Room, lecture/écriture
 * synchrone, suffisant pour 1-20 sources.
 */
object IptvSourceStore {
    private const val TAG = "IptvSourceStore"
    private const val KEY_SOURCES_JSON = "iptv_sources_json"

    private lateinit var prefs: SharedPreferences

    fun setup(context: Context) {
        prefs = context.getSharedPreferences(
            "${BuildConfig.APPLICATION_ID}.preferences",
            Context.MODE_PRIVATE,
        )
        // 2026-05-18 (user "Mon IPTV crash au démarrage chez plusieurs personnes
        //   à cause de l'addition des sources qui devrait pas avoir lieu") :
        //   auto-seed retiré. L'auto-seed ajoutait IPTV-Org FR + set last_id →
        //   tryAutoRestoreIptvSession bootait Mon IPTV au démarrage → fetch
        //   d'un M3U 5000 chaînes → crash mémoire sur low-end devices.
        //   L'utilisateur ajoute désormais ses sources manuellement via le
        //   tableau IptvSourcesActivity.
        // seedDefaultSourceIfEmpty()
    }

    /** 2026-05-13 (user "mets une source en permanence sur ce provider, comme ça
     *  les gens auront déjà une source à utiliser") : auto-ajoute iptv-org/fr.m3u
     *  comme source par défaut si la liste est vide. Permet aux nouveaux users de
     *  tester immédiatement sans avoir à ajouter manuellement. */
    private fun seedDefaultSourceIfEmpty() {
        if (prefs.getString(KEY_SOURCES_JSON, null) != null) return // déjà fait
        if (prefs.getBoolean("default_source_seeded", false)) return
        val defaultSource = IptvSource(
            id = "default_iptvorg_fr",
            type = IptvSource.Type.M3U,
            name = "IPTV-Org France (gratuit)",
            url = "https://iptv-org.github.io/iptv/countries/fr.m3u",
        )
        saveAll(listOf(defaultSource))
        prefs.edit().putBoolean("default_source_seeded", true).apply()
        // 2026-05-13 (user "auto-chargement de l'URL au démarrage") : set last_id
        // dès le seed pour que tryAutoRestoreIptvSession puisse skip le picker
        // des providers dès le 1er démarrage (une fois le M3U fetché).
        val context = com.streamflixreborn.streamflix.StreamFlixApp.instance
        context.getSharedPreferences("iptv_last_source", Context.MODE_PRIVATE)
            .edit().putString("last_id", defaultSource.id).apply()
        Log.d(TAG, "Default IPTV-Org FR source seeded + marked as last_id")
    }

    fun getAll(): List<IptvSource> {
        val json = prefs.getString(KEY_SOURCES_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                IptvSource(
                    id = obj.getString("id"),
                    type = IptvSource.Type.valueOf(obj.getString("type")),
                    name = obj.getString("name"),
                    url = obj.getString("url"),
                    mac = obj.optString("mac").takeIf { it.isNotBlank() },
                    serial = obj.optString("serial").takeIf { it.isNotBlank() },
                    userAgent = obj.optString("userAgent").takeIf { it.isNotBlank() },
                    referer = obj.optString("referer").takeIf { it.isNotBlank() },
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse sources JSON: ${e.message}")
            emptyList()
        }
    }

    fun getById(id: String): IptvSource? = getAll().firstOrNull { it.id == id }

    /** 2026-05-19 v85c (user "quand on rouvre la source la dernière source
     *  utilisée se charge normalement") : renvoie UNIQUEMENT la dernière
     *  source active (lue depuis SharedPreferences "iptv_last_source/last_id")
     *  si elle existe encore dans le store. Sinon renvoie toutes les sources
     *  (fallback first-launch / si l'user a supprimé la dernière source).
     *  Permet à MyIptvProvider d'afficher uniquement le contenu de la source
     *  que l'user a active, pas le mélange de toutes les sources cumulées. */
    fun getActiveOrAll(): List<IptvSource> {
        return try {
            val ctx = com.streamflixreborn.streamflix.StreamFlixApp.instance
            val lastId = ctx.getSharedPreferences("iptv_last_source", Context.MODE_PRIVATE)
                .getString("last_id", null)
            if (lastId != null) {
                val all = getAll()
                val active = all.firstOrNull { it.id == lastId }
                if (active != null) listOf(active) else all
            } else {
                getAll()
            }
        } catch (_: Throwable) {
            getAll()
        }
    }

    fun upsert(source: IptvSource) {
        val list = getAll().toMutableList()
        val idx = list.indexOfFirst { it.id == source.id }
        if (idx >= 0) list[idx] = source else list.add(source)
        saveAll(list)
    }

    fun delete(id: String) {
        val list = getAll().filter { it.id != id }
        saveAll(list)
    }

    /** Génère un id stable. */
    fun generateId(): String =
        "s_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"

    private fun saveAll(sources: List<IptvSource>) {
        val arr = JSONArray()
        sources.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("type", s.type.name)
                put("name", s.name)
                put("url", s.url)
                if (s.mac != null) put("mac", s.mac)
                if (s.serial != null) put("serial", s.serial)
                if (s.userAgent != null) put("userAgent", s.userAgent)
                if (s.referer != null) put("referer", s.referer)
            })
        }
        prefs.edit().putString(KEY_SOURCES_JSON, arr.toString()).apply()
    }
}
