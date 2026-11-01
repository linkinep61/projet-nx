package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.TvShow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 2026-11-01 (user : « dans le TV Hub, pouvoir déplacer les rangées… mettre Favoris Replay tout en
 * haut… en restant appuyé, ou mieux dans Paramètres du fournisseur actuel, réorganiser son
 * interface » + « appui long sur une jaquette → Ajouter à… choisir la catégorie… créer deux ou
 * trois catégories supplémentaires pour classer ce qu'on veut ») :
 *   · ORDRE DES RANGÉES du TV Hub (par profil) : appliqué à la fin de getHome ;
 *   · CATÉGORIES PERSO (3 au plus, par profil) : rangées « 🏷 <nom> » remplies par appui long.
 * Réglages : Paramètres › Paramètres du fournisseur actuel (TV Hub) › « Réorganiser le TV Hub » et
 * « Mes catégories ». Appui long sur une carte : « Ajouter à une catégorie » et « Déplacer la rangée ».
 */
object TvHubOrganisation {
    private const val TAG = "TvHubOrganisation"
    private const val PREFS = "tvhub_organisation"
    const val MAX_CATEGORIES = 5   // 2026-11-01 (user : « au moins 5 »)
    const val PREFIXE_RANGEE = "🏷 "

    private fun prefs() = StreamFlixApp.instance.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun profil() = ProfileManager.currentProfileIdOrDefault()

    // ─────────────────────────── Ordre des rangées ───────────────────────────

    /** Noms des rangées du dernier accueil construit (avant tri) — pour le réglage. */
    @Volatile var dernieresRangees: List<String> = emptyList()
        private set
    /** id de carte → nom de rangée (dernier accueil construit), pour « Déplacer la rangée ». */
    @Volatile private var rangeeParId: Map<String, String> = emptyMap()

    fun ordre(): List<String> = try {
        val a = JSONArray(prefs().getString("ordre_${profil()}", "[]") ?: "[]")
        (0 until a.length()).map { a.getString(it) }
    } catch (_: Exception) { emptyList() }

    private fun ecrireOrdre(l: List<String>) {
        prefs().edit().putString("ordre_${profil()}", JSONArray(l).toString()).apply()
    }

    /**
     * Trie les rangées selon l'ordre enregistré. Les rangées connues prennent, dans l'ordre choisi,
     * les places occupées par les rangées connues ; les autres (nouvelles) restent où elles sont.
     */
    fun appliquerOrdre(sections: List<Category>): List<Category> {
        dernieresRangees = sections.map { it.name }
        rangeeParId = buildMap {
            sections.forEach { c -> (c.list as? List<*>)?.forEach { (it as? TvShow)?.id?.let { id -> putIfAbsent(id, c.name) } } }
        }
        return trier(sections) { it.name }
    }

    private fun <T> trier(elements: List<T>, nom: (T) -> String): List<T> {
        val ordre = ordre()
        if (ordre.isEmpty()) return elements
        val rang = ordre.withIndex().associate { it.value to it.index }
        val connues = elements.filter { nom(it) in rang }.sortedBy { rang[nom(it)] }
        val it = connues.iterator()
        return elements.map { s -> if (nom(s) in rang) it.next() else s }
    }

    fun signature(): String = ordre().joinToString("|") + "#" + categories().joinToString("|") { c ->
        c.nom + ":" + c.elements.joinToString(",") { it.id }
    }

    fun rangeeDe(id: String): String? = rangeeParId[id]

    private fun deplacer(nom: String, action: Int) {
        // On part de l'ordre AFFICHÉ (rangées réelles), pour que monter/descendre suive l'écran.
        val affiche = appliquerOrdreNoms(dernieresRangees).toMutableList()
        val i = affiche.indexOf(nom)
        if (i < 0) return
        affiche.removeAt(i)
        val j = when (action) {
            0 -> 0                                   // tout en haut
            1 -> (i - 1).coerceAtLeast(0)            // monter
            2 -> (i + 1).coerceAtMost(affiche.size)  // descendre
            else -> affiche.size                     // tout en bas
        }
        affiche.add(j, nom)
        ecrireOrdre(affiche)
        Log.d(TAG, "rangée « $nom » → position ${j + 1}/${affiche.size}")
    }

    private fun appliquerOrdreNoms(noms: List<String>): List<String> = trier(noms) { it }

    // ─────────────────────────── Catégories perso ───────────────────────────

    data class Element(val id: String, val titre: String, val poster: String?, val banner: String?, val estFilm: Boolean)
    data class Categorie(val nom: String, val elements: List<Element>)

    fun categories(): List<Categorie> = try {
        val a = JSONArray(prefs().getString("perso_${profil()}", "[]") ?: "[]")
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val el = o.optJSONArray("elements") ?: JSONArray()
            Categorie(o.optString("nom"), (0 until el.length()).map { k ->
                val e = el.getJSONObject(k)
                Element(e.optString("id"), e.optString("titre"), e.optString("poster").ifBlank { null },
                    e.optString("banner").ifBlank { null }, e.optBoolean("film"))
            })
        }.filter { it.nom.isNotBlank() }
    } catch (e: Exception) { Log.w(TAG, "lecture catégories : ${e.message}"); emptyList() }

    private fun ecrireCategories(l: List<Categorie>) {
        val a = JSONArray()
        l.forEach { c ->
            val el = JSONArray()
            c.elements.forEach { e ->
                el.put(JSONObject().put("id", e.id).put("titre", e.titre).put("poster", e.poster ?: "")
                    .put("banner", e.banner ?: "").put("film", e.estFilm))
            }
            a.put(JSONObject().put("nom", c.nom).put("elements", el))
        }
        prefs().edit().putString("perso_${profil()}", a.toString()).apply()
    }

    /** Rangées « 🏷 <nom> » (catégories non vides). */
    fun rangeesPerso(): List<Category> = categories().filter { it.elements.isNotEmpty() }.map { c ->
        Category(name = PREFIXE_RANGEE + c.nom, list = c.elements.map { e ->
            TvShow(id = e.id, title = e.titre, poster = e.poster, banner = e.banner ?: e.poster).apply {
                providerName = "TV Hub"
                isMovie = e.estFilm
            }
        })
    }

    private fun basculerDans(nom: String, show: TvShow): Boolean {
        val l = categories().toMutableList()
        val i = l.indexOfFirst { it.nom == nom }
        if (i < 0) return false
        val c = l[i]
        val dedans = c.elements.any { it.id == show.id }
        l[i] = c.copy(elements = if (dedans) c.elements.filterNot { it.id == show.id }
            else c.elements + Element(show.id, show.title ?: show.id, show.poster, show.banner, show.isMovie))
        ecrireCategories(l)
        // Chaînes « dynamiques » (FAST, Dric4r…) : leur adresse n'est pas reconstructible depuis
        //   l'id → on la mémorise comme pour un favori, sinon la carte ne se lirait plus au redémarrage.
        if (!dedans) runCatching { com.streamflixreborn.streamflix.providers.LiveTvHubProvider.persistFastFavorite(show.id) }
        return !dedans
    }

    private fun creer(nom: String): Boolean {
        val l = categories()
        if (l.size >= MAX_CATEGORIES || l.any { it.nom.equals(nom, true) }) return false
        ecrireCategories(l + Categorie(nom, emptyList()))
        return true
    }

    private fun renommer(ancien: String, nouveau: String) {
        ecrireCategories(categories().map { if (it.nom == ancien) it.copy(nom = nouveau) else it })
        val o = ordre()
        if (o.isNotEmpty()) ecrireOrdre(o.map { if (it == PREFIXE_RANGEE + ancien) PREFIXE_RANGEE + nouveau else it })
    }

    private fun supprimer(nom: String) {
        ecrireCategories(categories().filterNot { it.nom == nom })
        ecrireOrdre(ordre().filterNot { it == PREFIXE_RANGEE + nom })
    }

    // ─────────────────────────── Fenêtres ───────────────────────────

    /** Rafraîchit le TV Hub après un changement. */
    fun rafraichir(ctx: Context) {
        runCatching { com.streamflixreborn.streamflix.providers.LiveTvHubProvider.clearHomeCache() }
        runCatching { UserPreferences.currentProvider?.let { HomeCacheStore.clear(ctx, it) } }
        runCatching { ProviderChangeNotifier.notifyProviderChanged(forceRelaunch = true) }
    }

    /** Appui long sur une carte : « Ajouter à une catégorie… ». */
    fun ouvrirAjouterA(ctx: Context, show: TvShow) {
        val cats = categories()
        val libelles = cats.map { c ->
            (if (c.elements.any { it.id == show.id }) "✓ " else "   ") + c.nom
        } + if (cats.size < MAX_CATEGORIES) listOf("＋ Nouvelle catégorie…") else emptyList()
        if (libelles.isEmpty()) return
        AlertDialog.Builder(ctx)
            .setTitle("Ajouter « ${show.title} » à…")
            .setItems(libelles.toTypedArray()) { _, i ->
                if (i < cats.size) {
                    val ajoute = basculerDans(cats[i].nom, show)
                    Toast.makeText(ctx, if (ajoute) "Ajouté à « ${cats[i].nom} »" else "Retiré de « ${cats[i].nom} »",
                        Toast.LENGTH_SHORT).show()
                    rafraichir(ctx)
                } else {
                    demanderNom(ctx, "Nouvelle catégorie", "") { nom ->
                        if (creer(nom)) {
                            basculerDans(nom, show)
                            Toast.makeText(ctx, "Catégorie « $nom » créée — ${show.title} ajouté", Toast.LENGTH_SHORT).show()
                            rafraichir(ctx)
                        } else Toast.makeText(ctx, "Nom déjà pris ou $MAX_CATEGORIES catégories au maximum", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** Menu de déplacement d'UNE rangée (appui long sur une carte, ou depuis la liste). */
    fun ouvrirDeplacerRangee(ctx: Context, nom: String, apres: (() -> Unit)? = null) {
        val actions = arrayOf("⏫ Tout en haut", "⬆ Monter d'une place", "⬇ Descendre d'une place", "⏬ Tout en bas")
        AlertDialog.Builder(ctx)
            .setTitle("Rangée « $nom »")
            .setItems(actions) { _, a ->
                deplacer(nom, a)
                rafraichir(ctx)
                val pos = appliquerOrdreNoms(dernieresRangees).indexOf(nom) + 1
                Toast.makeText(ctx, "« $nom » en position $pos", Toast.LENGTH_SHORT).show()
                apres?.invoke()
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    /** Liste des rangées dans l'ordre affiché → choisir une rangée à déplacer. */
    fun ouvrirReorganiser(ctx: Context) {
        val noms = appliquerOrdreNoms(dernieresRangees)
        if (noms.isEmpty()) {
            Toast.makeText(ctx, "Ouvrez d'abord le TV Hub une fois, puis revenez ici", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(ctx)
            .setTitle("Réorganiser le TV Hub — choisir une rangée")
            .setItems(noms.mapIndexed { i, n -> "${i + 1}.  $n" }.toTypedArray()) { _, i ->
                ouvrirDeplacerRangee(ctx, noms[i]) { ouvrirReorganiser(ctx) }
            }
            .setNeutralButton("Ordre d'origine") { _, _ ->
                ecrireOrdre(emptyList()); rafraichir(ctx)
                Toast.makeText(ctx, "Ordre d'origine rétabli", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    /** Gestion des catégories perso : créer, renommer, supprimer. */
    fun ouvrirMesCategories(ctx: Context) {
        val cats = categories()
        val libelles = cats.map { "${it.nom}  (${it.elements.size})" } +
            if (cats.size < MAX_CATEGORIES) listOf("＋ Nouvelle catégorie…") else emptyList()
        AlertDialog.Builder(ctx)
            .setTitle("Mes catégories (${cats.size}/$MAX_CATEGORIES)")
            .setItems(libelles.toTypedArray()) { _, i ->
                if (i >= cats.size) {
                    demanderNom(ctx, "Nouvelle catégorie", "") { nom ->
                        if (creer(nom)) Toast.makeText(ctx,
                            "« $nom » créée — appui long sur une jaquette › Ajouter à une catégorie", Toast.LENGTH_LONG).show()
                        else Toast.makeText(ctx, "Nom déjà pris", Toast.LENGTH_SHORT).show()
                        ouvrirMesCategories(ctx)
                    }
                    return@setItems
                }
                val c = cats[i]
                AlertDialog.Builder(ctx)
                    .setTitle(c.nom)
                    .setItems(arrayOf("✏ Renommer", "🗑 Supprimer la catégorie")) { _, a ->
                        if (a == 0) demanderNom(ctx, "Renommer « ${c.nom} »", c.nom) { nouveau ->
                            renommer(c.nom, nouveau); rafraichir(ctx); ouvrirMesCategories(ctx)
                        } else AlertDialog.Builder(ctx)
                            .setMessage("Supprimer « ${c.nom} » et ses ${c.elements.size} élément(s) ?")
                            .setPositiveButton("Supprimer") { _, _ -> supprimer(c.nom); rafraichir(ctx); ouvrirMesCategories(ctx) }
                            .setNegativeButton("Annuler", null).show()
                    }
                    .setNegativeButton("Retour") { _, _ -> ouvrirMesCategories(ctx) }
                    .show()
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun demanderNom(ctx: Context, titre: String, initial: String, ok: (String) -> Unit) {
        val champ = EditText(ctx).apply { setText(initial); setSingleLine(); setSelection(text.length) }
        AlertDialog.Builder(ctx)
            .setTitle(titre)
            .setView(champ)
            .setPositiveButton("Valider") { _, _ ->
                val nom = champ.text.toString().trim().take(30)
                if (nom.isNotEmpty()) ok(nom)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** Préférences ajoutées aux réglages du TV Hub (mobile et TV). */
    fun ajouterReglages(ctx: Context, cat: PreferenceCategory) {
        if (cat.findPreference<Preference>("tvhub_reorganiser") == null) {
            cat.addPreference(Preference(ctx).apply {
                key = "tvhub_reorganiser"
                title = "↕ Réorganiser le TV Hub"
                summary = "Choisir l'ordre des rangées (Favoris, Favoris Replay, Dossiers…)"
                isIconSpaceReserved = false
                setOnPreferenceClickListener { ouvrirReorganiser(ctx); true }
            })
        }
        if (cat.findPreference<Preference>("tvhub_mes_categories") == null) {
            cat.addPreference(Preference(ctx).apply {
                key = "tvhub_mes_categories"
                title = "🏷 Mes catégories"
                summary = "Jusqu'à $MAX_CATEGORIES rangées à vous, remplies par appui long sur une jaquette"
                isIconSpaceReserved = false
                setOnPreferenceClickListener { ouvrirMesCategories(ctx); true }
            })
        }
    }
}
