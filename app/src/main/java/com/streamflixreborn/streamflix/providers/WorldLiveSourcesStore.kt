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
    // 2026-06-14 (user "les playlists par défaut il faut faire en sorte qu'elles
    //   soient supprimées si l'utilisateur veut les supprimer... ou même
    //   modifier") : 2 nouvelles structures pour rendre les built-in éditables :
    //    - PREF_REMOVED_BUILTINS = JSON array de noms originaux de built-in
    //      que l'user a supprimés (= retirés de allSources()).
    //    - PREF_BUILTIN_OVERRIDES = JSON object { originalName: {name, url} }
    //      pour les built-in dont l'user a édité le nom et/ou URL.
    //   Le tag isBuiltin reste true pour ces sources (= elles continuent à se
    //   distinguer visuellement avec [prédéfini]), mais elles deviennent
    //   modifiables/supprimables comme les sources perso.
    private const val PREF_REMOVED_BUILTINS = "world_live_removed_builtins"
    private const val PREF_BUILTIN_OVERRIDES = "world_live_builtin_overrides"
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
        // 2026-06-14 (user "mix m3u") : notre M3U Codeberg hébergé
        //   (TNT France, Premium FR prime-tv, Live Canal aab1, NetPlus CH,
        //   Samsung TV+, etc. ~560 chaînes). En tête de liste = source par
        //   défaut. Supprimable comme toute autre builtin.
        // 2026-06-15 : migré Codeberg → GitHub org xdata-mix (runners
        //   Codeberg public saturés en permanence). GitHub Actions
        //   ubuntu-latest = beaucoup plus fiable, cron */30 garanti,
        //   refresh autonome. Org name neutre (pas de username perso).
        Source("mix m3u", "https://raw.githubusercontent.com/xdata-mix/nx-data/main/data.m3u", isBuiltin = true),
        // 2026-06-17 (Phase 1 port Catchup TV & More Kotlin) : replay France.tv
        //   généré toutes les 6h par scripts/refresh_replays.py. URLs dans le
        //   M3U sont `francetv://<si_id>` — résolues à la lecture par
        //   FrancetvResolver (= k7.ftven.fr + hdfauth.ftven.fr depuis IP user FR).
        Source("Replay FR", "https://raw.githubusercontent.com/xdata-mix/nx-data/main/data-replay.m3u", isBuiltin = true),
        // 2026-06-24 : chaînes FAST (Samsung TV+, Pluto TV, Plex TV, LG Channels,
        //   Rakuten TV, Sony One). Fichier autonome rafraîchi par refresh_fast.yml
        //   toutes les 6h, indépendant de data.m3u et data-replay.m3u.
        Source("FAST FR", "https://raw.githubusercontent.com/xdata-mix/nx-data/main/data-fast.m3u", isBuiltin = true),
        // 2026-06-16 (user "ils ont bien un m3u permanent mis tout le temps à jour
        //   pourquoi on n'a pas mis directement ce lien M3U") : ajout source
        //   ParaTV direct = playlist principale du mainteneur Paradise-91 (~850
        //   chaînes France + Belgique + Suisse + Samsung TV+ + NetPlus + Canal+
        //   + RMC/BFM + Equidia + L'Équipe + TV5 Monde). Mise à jour quasi-
        //   instantanée (= 8000+ push depuis 2024 chez eux). Pas de copie locale,
        //   l'app fetch direct = toujours à jour. -highest.m3u = uniquement la
        //   meilleure résolution par chaîne (évite les doublons SSAI/backup).
        Source("paradis", "https://raw.githubusercontent.com/Paradise-91/ParaTV/main/playlists/paratv/main/paratv-highest.m3u", isBuiltin = true),
        // 2026-06-14 (user "IPTV du web") : panel Xtream Codes externe.
        //   Format host:port/get.php?username=X&password=Y → branche Xtream
        //   ajoutée dans WorldLiveTvProvider (player_api.php) qui donne les
        //   catégories propres pré-classées.
        // 2026-06-19 (user retour : "fait crasher certains appareils, obligé
        //   d'effacer les données") : la SOURCE est gardée (= marche chez
        //   l'user, le panel est joignable selon UA/IP). Le vrai fix est
        //   côté robustesse dans WorldLiveTvProvider (try/catch global
        //   parsing + fallback auto source 0 en cas d'exception répétée).
        Source("IPTV du web", "http://ultimateiptv.me:8080/get.php?username=cs22cs.suroot&password=DCV3bJ9zZ6nu&type=m3u", isBuiltin = true),
        // 2026-06-12 (user "tu mets iptv-org en priorite") : iptv-org est une
        //   playlist M3U publique communautaire maintenue par une org GitHub
        //   sérieuse (= pas un service tiers qui empoisonne ses URLs comme
        //   rsseverything). Contient TF1, France 2-5, M6, Canal+, BFM, etc.
        //   avec des URLs CDN officielles. Marche depuis Tahiti.
        Source("iptv-org FR", "https://iptv-org.github.io/iptv/countries/fr.m3u", isBuiltin = true),
        // 2026-06-14 (user "3tvbox a fermé, on nettoie sauf l'alias") :
        //   l'entree builtin 3boxTv est retiree. La pipeline generique
        //   (BoxXtemusProvider + GenericStreamResolver + WorldLiveTvProvider)
        //   reste intacte, donc si l'URL revient un jour, l'user n'aura qu'a
        //   l'ajouter manuellement via le picker ou taper "3 TV Box" dans le
        //   champ URL (l'alias URL_ALIASES plus bas la traduit toujours).
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

    /** Toutes les sources (built-in connues + ajoutées par l'user).
     *  Applique les suppressions et overrides éventuels des built-in. */
    fun allSources(context: Context): List<Source> {
        val removed = getRemovedBuiltins(context)
        val overrides = getBuiltinOverrides(context)
        val out = mutableListOf<Source>()
        for (b in BUILTIN_SOURCES) {
            if (b.name in removed) continue
            val ov = overrides[b.name]
            if (ov != null) {
                out.add(Source(ov.first, ov.second, isBuiltin = true))
            } else {
                out.add(b)
            }
        }
        out.addAll(list(context))
        return out
    }

    // ───────── Helpers pour built-in supprimés / modifiés ─────────

    private fun getRemovedBuiltins(context: Context): Set<String> {
        val raw = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_REMOVED_BUILTINS, null) ?: return emptySet()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.optString(it).trim() }
                .filter { it.isNotBlank() }.toSet()
        } catch (_: Throwable) { emptySet() }
    }

    private fun setRemovedBuiltins(context: Context, set: Set<String>) {
        val arr = JSONArray()
        set.forEach { arr.put(it) }
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putString(PREF_REMOVED_BUILTINS, arr.toString()).apply()
    }

    private fun getBuiltinOverrides(context: Context): Map<String, Pair<String, String>> {
        val raw = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_BUILTIN_OVERRIDES, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val out = mutableMapOf<String, Pair<String, String>>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.optJSONObject(k) ?: continue
                out[k] = v.optString("name").trim() to v.optString("url").trim()
            }
            out
        } catch (_: Throwable) { emptyMap() }
    }

    private fun setBuiltinOverrides(context: Context, map: Map<String, Pair<String, String>>) {
        val obj = JSONObject()
        map.forEach { (k, v) ->
            obj.put(k, JSONObject().put("name", v.first).put("url", v.second))
        }
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putString(PREF_BUILTIN_OVERRIDES, obj.toString()).apply()
    }

    /** Helper : retrouve le nom original d'un built-in à partir de la source
     *  actuellement visible (qui peut être un override avec un nom différent). */
    private fun findBuiltinOriginalName(context: Context, visibleSource: Source): String? {
        if (!visibleSource.isBuiltin) return null
        val overrides = getBuiltinOverrides(context)
        for (b in BUILTIN_SOURCES) {
            val ov = overrides[b.name]
            if (ov != null) {
                if (ov.first == visibleSource.name && ov.second == visibleSource.url) return b.name
            } else {
                if (b.name == visibleSource.name && b.url == visibleSource.url) return b.name
            }
        }
        return null
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
        // 2026-06-14 (user "les playlists par défaut il faut faire en sorte
        //   qu'elles soient supprimées si l'utilisateur veut les supprimer") :
        //   les built-in sont maintenant supprimables. On ajoute leur nom
        //   original au PREF_REMOVED_BUILTINS, et allSources() les filtrera.
        if (source.isBuiltin) {
            val originalName = findBuiltinOriginalName(context, source) ?: return false
            val removed = getRemovedBuiltins(context).toMutableSet()
            removed.add(originalName)
            setRemovedBuiltins(context, removed)
            // Clean l'override aussi si il existait
            val overrides = getBuiltinOverrides(context).toMutableMap()
            if (overrides.remove(originalName) != null) {
                setBuiltinOverrides(context, overrides)
            }
            val activeIdx = getActiveIndex(context)
            if (activeIdx > indexInAll) setActiveIndex(context, (activeIdx - 1).coerceAtLeast(0))
            return true
        }
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

    /** 2026-06-14 (user "ou même modifier") : nouvelle méthode update qui
     *  prend indexInAll et accepte aussi les built-in. Pour les built-in,
     *  stocke l'override dans PREF_BUILTIN_OVERRIDES sans toucher au catalogue
     *  hardcodé BUILTIN_SOURCES (ainsi un éventuel "Reset" est possible plus
     *  tard en effaçant juste l'override). */
    fun updateAtAllIndex(context: Context, indexInAll: Int, newName: String, newUrl: String): Boolean {
        val all = allSources(context)
        if (indexInAll !in all.indices) return false
        val source = all[indexInAll]
        val resolvedUrl = resolveUrlAlias(newUrl)
        if (source.isBuiltin) {
            val originalName = findBuiltinOriginalName(context, source) ?: return false
            val overrides = getBuiltinOverrides(context).toMutableMap()
            overrides[originalName] = newName.trim() to resolvedUrl
            setBuiltinOverrides(context, overrides)
            return true
        }
        val userList = list(context).toMutableList()
        val personIdx = userList.indexOfFirst { it.name == source.name && it.url == source.url }
        if (personIdx < 0) return false
        userList[personIdx] = Source(newName.trim(), resolvedUrl)
        save(context, userList)
        return true
    }

    /** Restaure tous les built-in supprimés/modifiés à leur état d'origine. */
    fun resetBuiltins(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .remove(PREF_REMOVED_BUILTINS)
            .remove(PREF_BUILTIN_OVERRIDES)
            .apply()
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
