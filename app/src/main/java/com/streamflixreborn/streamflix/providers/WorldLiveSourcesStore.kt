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
        // 2026-06-12 (user "tu mets iptv-org en priorite") : iptv-org est une
        //   playlist M3U publique communautaire maintenue par une org GitHub
        //   sérieuse (= pas un service tiers qui empoisonne ses URLs comme
        //   rsseverything). Contient TF1, France 2-5, M6, Canal+, BFM, etc.
        //   avec des URLs CDN officielles. Marche depuis Tahiti.
        Source("iptv-org FR (par défaut)", "https://iptv-org.github.io/iptv/countries/fr.m3u", isBuiltin = true),
        Source("3box TV", "https://box.xtemus.com/?playlist=u256y494u21596x2", isBuiltin = true),
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

    /** 2026-06-13 (user "masque la source 3 TV Box du picker, on la retire pas
     *  / si quelqu'un la rajoute manuellement par nom elle se reactive
     *  automatiquement") : masquage PROVISOIRE de "3box TV" parmi les
     *  built-in. Le code reste intact, la source reste utilisable, mais elle
     *  est filtree de allSources(). Reactivation auto si l'user a deja ajoute
     *  manuellement une source dont le nom contient "3box" (case insensitive)
     *  ou "3 tv box" -> on remet alors le builtin (= reactive). Sinon il
     *  apparaitra juste comme une source perso normale.
     *  Pour reactiver permanent : flip HIDE_3BOXTV_BUILTIN a false. */
    private const val HIDE_3BOXTV_BUILTIN: Boolean = true

    private fun isUserReactivated(userSources: List<Source>): Boolean {
        return userSources.any { src ->
            val n = src.name.lowercase()
            n.contains("3box") || n.contains("3 box") ||
            n.contains("3 tv box") || n.contains("3tvbox") ||
            n.contains("3 tvbox") || n.contains("3boxtv")
        }
    }

    /** Toutes les sources (built-in connues + ajoutées par l'user).
     *  2026-06-13 (user "pourquoi elle est apparue en predefini quand je l'ai
     *  ajoutee manuellement") : SI l'user a une source perso 3box-like, on
     *  ne reactive PAS le built-in (= sinon doublon visuel confus). La source
     *  perso suffit. Le built-in ne reapparait que si l'user retire toutes
     *  ses sources 3box perso. Plus simple, plus clair. */
    fun allSources(context: Context): List<Source> {
        val out = mutableListOf<Source>()
        val userList = list(context)
        val hasUser3box = isUserReactivated(userList)
        // Le built-in 3box est masque SI HIDE_3BOXTV_BUILTIN actif. La presence
        // d'une source perso 3box-like remplace deja le built-in (= pas de
        // doublon).
        BUILTIN_SOURCES.forEach { src ->
            val isBox = src.name.contains("3box", ignoreCase = true) ||
                src.name.contains("3 box", ignoreCase = true)
            if (isBox && (HIDE_3BOXTV_BUILTIN || hasUser3box)) return@forEach
            out.add(src)
        }
        out.addAll(userList)
        return out
    }

    /** 2026-06-13 (user "si la personne connait pas le nom de la source on met
     *  que 3 TV Box dans URL au lieu de l'URL complete, ca fonctionne") :
     *  alias texte -> URL reelle pour eviter a l'user de taper/copier une URL
     *  technique. Si l'user tape par exemple "3 TV Box" / "3boxTv" / "3box"
     *  dans le champ URL au lieu d'un lien https://, on traduit silencieusement
     *  vers la vraie URL. Etendre cette map pour d'autres sources si besoin.
     *  Le matching est case-insensitive et tolere les espaces. */
    private val URL_ALIASES: List<Pair<List<String>, String>> = listOf(
        // 3box TV / 3 TV Box
        listOf("3box", "3boxtv", "3 box", "3 boxtv", "3 box tv",
            "3tvbox", "3 tvbox", "3 tv box", "3 box-tv", "trois tv box",
            "trois box tv", "trois box", "trois boxtv") to
            "https://box.xtemus.com/?playlist=u256y494u21596x2",
    )

    /** Traduit un alias texte en URL reelle si trouve. Sinon retourne l'input. */
    private fun resolveUrlAlias(input: String): String {
        val trimmed = input.trim()
        // Si c'est deja une URL valide, on touche pas
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)) return trimmed
        // Normalise : lowercase + collapse espaces multiples
        val normalized = trimmed.lowercase().replace(Regex("\\s+"), " ").trim()
        for ((aliases, realUrl) in URL_ALIASES) {
            if (aliases.any { it.lowercase() == normalized }) return realUrl
        }
        return trimmed
    }

    fun add(context: Context, name: String, url: String) {
        val current = list(context).toMutableList()
        val resolvedUrl = resolveUrlAlias(url)
        current.add(Source(name.trim(), resolvedUrl))
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

    /** 2026-06-13 (user "j'arrive pas a supprimer la source box que j'ai mis
     *  tout a l'heure") : variante robuste de remove qui prend l'index dans
     *  allSources(context). Le UI affiche allSources() et passe directement
     *  son index → on retrouve la source visible, on confirme qu'elle est
     *  perso (= pas un built-in qu'on ne peut pas supprimer), et on supprime
     *  via sa position dans list(context). Resout le bug ou le calcul
     *  `indexInAll - BUILTIN_SOURCES.size` donnait un personIdx faux quand
     *  le masquage filtrait un built-in. Retourne true si suppression OK. */
    fun removeAtAllIndex(context: Context, indexInAll: Int): Boolean {
        val all = allSources(context)
        if (indexInAll !in all.indices) return false
        val source = all[indexInAll]
        if (source.isBuiltin) return false  // pas supprimable
        val userList = list(context).toMutableList()
        val personIdx = userList.indexOfFirst { it.name == source.name && it.url == source.url }
        if (personIdx < 0) return false
        userList.removeAt(personIdx)
        save(context, userList)
        // Si l'active etait apres dans la liste visible, decaler de 1
        val activeIdx = getActiveIndex(context)
        if (activeIdx > indexInAll) setActiveIndex(context, (activeIdx - 1).coerceAtLeast(0))
        return true
    }

    fun update(context: Context, index: Int, name: String, url: String) {
        val current = list(context).toMutableList()
        if (index in current.indices) {
            // Pareil que add() : traduit alias texte vers URL reelle si match
            current[index] = Source(name.trim(), resolveUrlAlias(url))
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
