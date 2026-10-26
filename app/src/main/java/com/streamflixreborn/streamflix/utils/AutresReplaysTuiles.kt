package com.streamflixreborn.streamflix.utils

import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.providers.LiveTvHubProvider
import com.streamflixreborn.streamflix.providers.OnyxFilmsProvider

/**
 * 2026-10-27 (user : « modifier le dossier Autres Replays pour qu'il apparaisse comme notre
 * nouveau dossier Chaînes TV » ; « pour Mix FR il faut faire des petits dossiers… exactement
 * pareil ») : Autres Replays et Mix FR s'ouvrent sur une GRILLE DE TUILES, comme Chaînes TV
 * (même rendu, appui long = contrôle parental, contenu chargé au clic).
 *
 * Les tuiles réutilisent le préfixe des sous-dossiers de [SportHub] (`livehub::folder::sportdos_…`) :
 * le clic passe ainsi par le même chemin que Chaînes TV, sans rien changer aux écrans.
 *   - `sportdos_ar~<id>`   : sous-dossier d'Autres Replays (Multi Live, Replays, Mix FR…)
 *   - `sportdos_mix~<slug>` : petit dossier de Mix FR (un par groupe de data.m3u)
 *
 * Verrou des petits dossiers de Mix FR : MÊME clé qu'avant (`agg:Mix FR::<slug>`, cf. la vue
 * agrégée de LiveHubFolderDialog) → un dossier déjà verrouillé (Zone 18@) le reste.
 */
object AutresReplaysTuiles {
    const val PREFIXE_AR = "ar~"
    const val PREFIXE_MIX = "mix~"
    private const val CLE_VERROU_MIX = "agg:Mix FR"
    /** 2026-10-27 (user : « un petit logo sur Autres Replays ») : lecture + flèche de retour,
     *  même style que les cartes Chaînes TV / Ciné Films. Aussi sur ses tuiles. */
    const val LOGO = "https://cdn-icons-png.flaticon.com/512/11344/11344991.png"

    const val MULTI_LIVE = "ar~multilive"
    const val REPLAYS = "ar~replays"
    const val MIX_FR = "ar~mixfr"
    const val CLIP = "ar~clip"
    const val IPTV_WEB = "ar~iptvweb"
    const val OTF_FILMS = "ar~otffilms"
    const val WORLDWIDE = "ar~worldwide"

    private class Tuile(val id: String, val titre: String)

    private fun tuiles(): List<Tuile> = buildList {
        add(Tuile(MULTI_LIVE, "📡 Multi Live"))
        // 2026-10-27 (user : « il n'y a plus rien dedans… ce dossier ne sert plus à rien ») :
        //   tuile « Replays » retirée — ses dernières chaînes (Brut, Tech & Co) sont dans BFM Play,
        //   tout le reste est dans TF1+ / M6+ / France TV / Arte. Le code d'ouverture reste
        //   (un ancien dossier favori « Replays » s'ouvre encore).
        add(Tuile(MIX_FR, "📁 Mix FR"))
        if (LiveTvHubProvider.WORLDWIDE_ACTIF) add(Tuile(WORLDWIDE, "🌍 WorldWide"))
        if (VidaraLibrary.disponible) add(Tuile(CLIP, "📚 Clip / série"))
        add(Tuile(IPTV_WEB, "📡 IPTV du web (FR)"))
        if (!OnyxFilmsProvider.REMPLACE_TV_HUB) add(Tuile(OTF_FILMS, "🎬 OTF Films (VF)"))
    }

    /** Titres des petits dossiers de Mix FR vus au dernier affichage (slug → nom). */
    private val titresMix = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun estTuile(id: String) = id.startsWith(PREFIXE_AR) || id.startsWith(PREFIXE_MIX)

    private fun tuile(id: String, titre: String) =
        TvShow(id = "livehub::folder::${SportHub.PREFIXE_SOUS_DOSSIER}$id", title = titre).apply {
            providerName = "TV Hub"
            // 2026-10-27 (user) : à l'intérieur, le logo de Chaînes TV ; [LOGO] reste sur la carte.
            poster = SportHub.LOGO_TV
            banner = SportHub.LOGO_TV
        }

    /** Accueil d'Autres Replays : la grille, sans aucun chargement. */
    fun accueil(nom: String): List<Category> =
        listOf(Category(name = nom, list = tuiles().map { tuile(it.id, it.titre) }))

    fun slug(nom: String): String =
        TvHubFolderLockStore.subFolderKey(CLE_VERROU_MIX, nom).substringAfter("$CLE_VERROU_MIX::")

    // Même ordre que l'ancienne liste de Mix FR (vue agrégée).
    private val ORDRE = listOf(
        "Séries", "Films", "Émissions", "Chaînes", "Divertissement", "Téléfilms", "Info", "Docs",
        "Sport", "Jeunesse", "Impact", "Programmes", "Direct",
    )

    /** Groupes de Mix FR (hors Live MIX / Multi Live) : nom du petit dossier → chaînes. */
    fun groupesMix(cats: List<Category>): List<Pair<String, List<TvShow>>> {
        val groupes = LinkedHashMap<String, MutableList<TvShow>>()
        for (cat in cats) {
            if (cat.name.equals("Live MIX", ignoreCase = true) ||
                cat.name.startsWith("Multi Live", ignoreCase = true)) continue
            val items = (cat.list as? List<*>)?.filterIsInstance<TvShow>().orEmpty()
            if (items.isEmpty()) continue
            val nom = if (" - " in cat.name) cat.name.substringAfter(" - ").trim() else "Programmes"
            groupes.getOrPut(nom) { mutableListOf() }.addAll(items)
        }
        return groupes.entries
            .sortedWith(compareBy({ ORDRE.indexOf(it.key).let { i -> if (i < 0) 99 else i } }, { it.key }))
            .map { it.key to it.value.distinctBy { s -> s.id } }
    }

    /** Grille des petits dossiers de Mix FR. */
    fun grilleMix(cats: List<Category>): List<Category> {
        val groupes = groupesMix(cats)
        groupes.forEach { (nom, _) -> titresMix[slug(nom)] = nom }
        return listOf(Category(name = "Mix FR", list = groupes.map { (nom, _) -> tuile(PREFIXE_MIX + slug(nom), nom) }))
    }

    /** Contenu d'un petit dossier de Mix FR (null = introuvable). */
    fun contenuMix(cats: List<Category>, id: String): Pair<String, List<TvShow>>? {
        val s = id.removePrefix(PREFIXE_MIX)
        return groupesMix(cats).firstOrNull { slug(it.first) == s }
    }

    /** Clé de verrou d'un petit dossier de Mix FR (même clé qu'avant), sinon null. */
    fun cleVerrou(id: String): String? =
        if (id.startsWith(PREFIXE_MIX)) "$CLE_VERROU_MIX::${id.removePrefix(PREFIXE_MIX)}" else null

    /** Libellé pour la fenêtre du code parental. */
    fun titre(id: String): String? = when {
        id.startsWith(PREFIXE_MIX) -> titresMix[id.removePrefix(PREFIXE_MIX)]
        id.startsWith("Mix FR::") -> titresMix[id.removePrefix("Mix FR::")]
        else -> tuiles().firstOrNull { it.id == id }?.titre
    }
}
