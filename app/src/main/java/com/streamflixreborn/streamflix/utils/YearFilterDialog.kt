package com.streamflixreborn.streamflix.utils

import android.content.Context

/**
 * 2026-08-04 — Sélecteur d'ANNÉE du catalogue (cf. [YearFilter]).
 *
 * Ouvert depuis la première ligne du sélecteur de filtres, lui-même appelé par un SECOND CLIC
 * sur « Films » ou « Séries TV » (menu TV comme menu du bas mobile).
 *
 * ⚠ Le rendu n'est plus dessiné ici : il est délégué à [OnyxChoixDialog], la feuille commune.
 *   C'est ce qui garantit que ce sélecteur et celui des genres restent identiques — le user
 *   avait justement relevé que les deux ne se ressemblaient pas.
 */
object YearFilterDialog {

    /**
     * @param type films ou séries — les deux réglages sont indépendants.
     * @param onChoisi appelé après enregistrement, pour que l'appelant recharge sa liste.
     */
    fun show(
        context: Context,
        providerName: String,
        type: YearFilter.Type,
        onChoisi: (YearFilter.Plage) -> Unit,
    ) {
        val courant = YearFilter.get(providerName, type)
        val choix = YearFilter.choix()

        OnyxChoixDialog.show(
            context = context,
            icone = if (type == YearFilter.Type.FILMS) "🎬" else "📺",
            titre = if (type == YearFilter.Type.FILMS) "Année des films" else "Année des séries",
            sousTitre = "Actuellement : ${courant.libelle}",
            options = choix.mapIndexed { i, plage ->
                OnyxChoixDialog.Option(
                    libelle = plage.libelle,
                    selectionne = plage.debut == courant.debut && plage.fin == courant.fin,
                    // Trait sous « Toutes les années » : elle annule le filtre, les suivantes
                    //   le posent. Deux natures différentes, on les sépare visuellement.
                    separateurApres = i == 0,
                )
            },
        ) { index ->
            val plage = choix[index]
            YearFilter.set(providerName, type, plage)
            onChoisi(plage)
        }
    }
}
