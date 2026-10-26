package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import com.streamflixreborn.streamflix.providers.OnyxFilmsProvider
import kotlinx.coroutines.launch

/**
 * 2026-10-26 (user : « quand on reste appuyé sur le dossier film ou le dossier série, on puisse
 *   trier… je choisis que OTF, que partage de la communauté ») : choix de la source affichée
 *   dans l'onglet Films ou Séries d'ONYX Films. Téléphone : appui long ou second clic sur
 *   l'onglet ; télé : second clic sur Films / Séries dans le menu (même geste que les genres).
 *   Même feuille sombre que le sélecteur de genres (OnyxChoixDialog), lisible à la télécommande.
 */
object OnyxFiltre {

    fun concerne(): Boolean = UserPreferences.currentProvider === OnyxFilmsProvider

    @Volatile private var enCours = false

    fun show(ctx: Context, scope: LifecycleCoroutineScope, series: Boolean, recharger: () -> Unit) {
        if (enCours) return
        enCours = true
        scope.launch {
            // 2026-10-26 (vu sur l'Oppo) : juste après l'ouverture de l'appli, les catalogues se
            //   chargent encore (~10 s) et rien ne montrait que l'appui avait été pris en compte →
            //   on réappuyait, sans effet. Un message si la liste n'est pas prête tout de suite.
            val attente = launch {
                kotlinx.coroutines.delay(500)
                Toast.makeText(ctx, "Chargement des sources…", Toast.LENGTH_SHORT).show()
            }
            val choix = try { runCatching { OnyxFilmsProvider.choixFiltre(series) }.getOrNull() }
                        finally { enCours = false; attente.cancel() } ?: return@launch
            val actuel = if (series) OnyxFilmsProvider.filtreSeries else OnyxFilmsProvider.filtreFilms
            // 2026-10-26 (user : « quand on affiche tous les films, on n'a aucun moyen de les
            //   trier ») : même feuille, même geste — la source en haut, le tri dessous.
            val triActuel = if (series) OnyxFilmsProvider.triSeries else OnyxFilmsProvider.triFilms
            val tris = OnyxFilmsProvider.TRIS
            val options = choix.mapIndexed { k, (libelle, cle) ->
                OnyxChoixDialog.Option(libelle, selectionne = cle == actuel,
                    separateurApres = k == choix.lastIndex, section = if (k == 0) "Source" else null)
            } + tris.mapIndexed { k, (cle, libelle) ->
                OnyxChoixDialog.Option(libelle, selectionne = cle == triActuel,
                    section = if (k == 0) "Trier par" else null)
            }
            OnyxChoixDialog.show(
                context = ctx,
                icone = if (series) "📺" else "🎬",
                titre = if (series) "Séries" else "Films",
                options = options,
            ) { i ->
                if (i in choix.indices) {
                    val nouveau = choix[i].second
                    if (nouveau != actuel) {
                        if (series) OnyxFilmsProvider.filtreSeries = nouveau
                        else OnyxFilmsProvider.filtreFilms = nouveau
                        recharger()
                        Toast.makeText(ctx, choix[i].first, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val (cle, libelle) = tris.getOrNull(i - choix.size) ?: return@show
                    if (cle != triActuel) {
                        if (series) OnyxFilmsProvider.triSeries = cle
                        else OnyxFilmsProvider.triFilms = cle
                        recharger()
                        Toast.makeText(ctx, "Tri : $libelle", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}
