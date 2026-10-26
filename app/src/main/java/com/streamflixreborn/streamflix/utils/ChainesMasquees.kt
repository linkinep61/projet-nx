package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.streamflixreborn.streamflix.fragments.player.settings.IptvBannedChannels

/**
 * 2026-10-28 (user : « un menu dans les paramètres du TV Hub, Chaînes masquées ») :
 * préférence « Chaînes masquées » ajoutée aux réglages du TV Hub (mobile et TV).
 * Clic → liste à cocher des chaînes masquées → « Réafficher » les fait revenir.
 * Rien n'est chargé tant que le user n'ouvre pas la liste.
 */
object ChainesMasquees {

    private const val CLE = "tvhub_chaines_masquees"

    private fun resume(): String {
        val n = IptvBannedChannels.count()
        return if (n == 0) "Aucune chaîne masquée (appui long sur une chaîne › Masquer)"
        else "$n chaîne${if (n > 1) "s" else ""} masquée${if (n > 1) "s" else ""} — toucher pour en réafficher"
    }

    fun ajouter(ctx: Context, cat: PreferenceCategory) {
        val existante = cat.findPreference<Preference>(CLE)
        if (existante != null) { existante.summary = resume(); return }
        cat.addPreference(Preference(ctx).apply {
            key = CLE
            title = "🙈 Chaînes masquées"
            isIconSpaceReserved = false
            summary = resume()
            setOnPreferenceClickListener { pref ->
                ouvrir(ctx) { pref.summary = resume() }
                true
            }
        })
    }

    private fun ouvrir(ctx: Context, apres: () -> Unit) {
        val liste = IptvBannedChannels.liste()
        if (liste.isEmpty()) {
            Toast.makeText(ctx, "Aucune chaîne masquée", Toast.LENGTH_SHORT).show()
            return
        }
        val noms = liste.map { it.second }.toTypedArray()
        val coches = BooleanArray(liste.size)
        AlertDialog.Builder(ctx)
            .setTitle("Chaînes masquées — cocher pour réafficher")
            .setMultiChoiceItems(noms, coches) { _, i, oui -> coches[i] = oui }
            .setPositiveButton("Réafficher") { _, _ ->
                var n = 0
                liste.forEachIndexed { i, (cle, _) -> if (coches[i]) { IptvBannedChannels.reafficher(cle); n++ } }
                if (n > 0) rafraichir(ctx)
                Toast.makeText(ctx, if (n == 0) "Aucune chaîne cochée" else "$n chaîne(s) réaffichée(s)", Toast.LENGTH_SHORT).show()
                apres()
            }
            .setNeutralButton("Tout réafficher") { _, _ ->
                liste.forEach { IptvBannedChannels.reafficher(it.first) }
                rafraichir(ctx)
                Toast.makeText(ctx, "${liste.size} chaîne(s) réaffichée(s)", Toast.LENGTH_SHORT).show()
                apres()
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun rafraichir(ctx: Context) {
        runCatching { com.streamflixreborn.streamflix.providers.LiveTvHubProvider.clearHomeCache() }
        runCatching { UserPreferences.currentProvider?.let { HomeCacheStore.clear(ctx, it) } }
    }
}
