package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.widget.Toast

/**
 * 2026-08-04 — Sélecteur de filtres du catalogue : ANNÉE (première ligne) puis GENRES.
 *
 * Ouvert par un SECOND CLIC sur « Films » ou « Séries TV », aussi bien depuis le menu latéral
 * TV que depuis le menu du bas mobile.
 *
 * Pourquoi ce fichier existe : le même sélecteur vivait en TROIS exemplaires — `MainTvActivity`,
 * `MoviesMobileFragment`, `TvShowsMobileFragment`. Chaque retouche devait être faite trois fois,
 * et l'oubli d'une seule les faisait diverger. Tout est ici désormais ; les appelants ne
 * fournissent que le provider, le type et de quoi recharger leur liste.
 *
 * ⚠ ORDRE DES LIGNES — l'année est en TÊTE, avant « Tous les genres ». Demande explicite du
 *   user : « ça évite encore un super défilement pour un truc qui va être beaucoup utilisé ».
 *   Ne pas la déplacer en pied de liste.
 */
object GenreYearPicker {

    /**
     * ⚠ DEUX rappels distincts, et non un seul : sur mobile le changement de GENRE doit passer
     *   par `viewModel.setGenreFilter(id)` (qui met à jour l'état du ViewModel avant de
     *   recharger), alors qu'un changement d'ANNÉE se contente d'un rechargement. Les
     *   confondre laisserait le genre sans effet sur mobile.
     *
     * @param onGenre reçoit l'identifiant du genre retenu (`null` = tous).
     * @param onAnnee appelé après un changement d'année ; l'année est déjà enregistrée.
     */
    fun show(
        context: Context,
        providerName: String,
        type: YearFilter.Type,
        onGenre: (String?) -> Unit,
        onAnnee: () -> Unit,
    ) {
        val genresDispo = GenreFilter.isSupported(providerName)
        val anneeDispo = YearFilter.estSupporte(providerName)

        // Provider sans genres : on va droit au choix de l'année, sans écran intermédiaire vide.
        if (!genresDispo) {
            if (anneeDispo) ouvrirAnnees(context, providerName, type, onAnnee)
            return
        }

        // 2026-08-18 : la liste ET le choix mémorisé dépendent de l'onglet — les genres
        //   TMDB des séries ont leurs propres identifiants (cf. GenreFilter.genresSeries).
        val genres = GenreFilter.genresForProvider(providerName, type)
        val genreCourant = GenreFilter.get(providerName, type)
        val decalage = if (anneeDispo) 1 else 0

        val lignes = mutableListOf<OnyxChoixDialog.Option>()
        if (anneeDispo) {
            val plage = YearFilter.get(providerName, type)
            lignes.add(
                OnyxChoixDialog.Option(
                    libelle = "📅  Année : ${plage.libelle}",
                    // Coche posée d'office sur l'année (demande user), pas sur le genre.
                    selectionne = true,
                    separateurApres = true,
                )
            )
        }
        lignes.add(OnyxChoixDialog.Option("Tous les genres", selectionne = genreCourant == null))
        genres.forEach { g ->
            lignes.add(OnyxChoixDialog.Option(g.name, selectionne = g.id == genreCourant?.id))
        }

        OnyxChoixDialog.show(
            context = context,
            icone = if (type == YearFilter.Type.FILMS) "🎬" else "📺",
            titre = if (type == YearFilter.Type.FILMS) "Filtrer les films" else "Filtrer les séries",
            sousTitre = "Genre : ${genreCourant?.name ?: "tous"}",
            options = lignes,
        ) { index ->
            if (anneeDispo && index == 0) {
                ouvrirAnnees(context, providerName, type, onAnnee)
                return@show
            }
            val rang = index - decalage
            val nouveau = if (rang == 0) null else genres[rang - 1]
            if (nouveau?.id != genreCourant?.id) {
                GenreFilter.set(providerName, nouveau, type)
                onGenre(nouveau?.id)
                Toast.makeText(
                    context.applicationContext,
                    if (nouveau != null) "Genre : ${nouveau.name}" else "Genre : tous",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun ouvrirAnnees(
        context: Context,
        providerName: String,
        type: YearFilter.Type,
        onAnnee: () -> Unit,
    ) {
        YearFilterDialog.show(context, providerName, type) { choisie ->
            onAnnee()
            Toast.makeText(
                context.applicationContext,
                "Année : ${choisie.libelle}",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
}
