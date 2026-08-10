package com.streamflixreborn.streamflix.utils

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.ui.PinDialog

/**
 * 2026-08-10 (user « le mieux ce serait de fusionner, et ajouter une petite case
 *   supplémentaire pour choisir les providers que l'on veut verrouiller ») :
 *   dialogues du contrôle parental, partagés entre les réglages TV et mobile.
 *
 * Avant, l'application avait DEUX contrôles parentaux qui s'ignoraient :
 *   - le cadenas ([ProviderLockStore]) : son propre code, verrouille des providers
 *     et des dossiers du TV Hub. C'est celui qui servait réellement.
 *   - le bloc « Contrôle parental » des réglages : un PIN parental séparé, un PIN
 *     administrateur de secours, et le filtre d'âge TMDb. Comme le PIN parental
 *     n'était jamais défini, tout le bloc restait sans effet.
 *
 * Désormais il n'y a plus qu'un code, celui du cadenas, et le PIN administrateur
 * a disparu — il n'existait que pour rattraper un verrouillage après trop d'essais
 * ratés sur un code que personne n'utilisait.
 */
object ParentalSettingsUi {

    /** Tous les providers connus, dédoublonnés et triés — la liste proposée à cocher. */
    private fun allProviderNames(): List<String> =
        Provider.providers.keys.map { it.name }.distinct().sorted()

    fun hasCode(context: Context): Boolean = ProviderLockStore.hasPin(context)

    fun lockedCount(context: Context): Int = ProviderLockStore.getLockedProviders(context).size

    /**
     * Création ou changement du code parental.
     * Sans code existant, [PinDialog.showAuth] enchaîne directement sur la création.
     */
    fun showCodeManager(context: Context, onDone: () -> Unit) {
        if (!ProviderLockStore.hasPin(context)) {
            PinDialog.showAuth(
                context = context,
                title = context.getString(R.string.settings_parental_code_title),
                onSuccess = {
                    Toast.makeText(
                        context,
                        context.getString(R.string.settings_parental_code_created),
                        Toast.LENGTH_SHORT,
                    ).show()
                    onDone()
                },
            )
            return
        }
        showChangeCode(context, onDone)
    }

    private fun pinInput(context: Context, hint: String): EditText =
        EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            this.hint = hint
        }

    /**
     * 2026-08-10 (user « on dirait que le code parental ne s'enregistre pas ») :
     * la version précédente affichait deux champs sans étiquette — impossible de
     * savoir lequel était l'ancien code — et fermait la boîte même quand rien
     * n'avait été enregistré. Ici chaque champ est nommé, la saisie est vérifiée
     * AVANT d'enregistrer, et la boîte reste ouverte tant que ce n'est pas bon.
     */
    private fun showChangeCode(context: Context, onDone: () -> Unit) {
        val ancien = pinInput(context, context.getString(R.string.settings_parental_code_current_hint))
        val nouveau = pinInput(context, context.getString(R.string.settings_parental_code_new_hint))
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 24, 48, 0)
            addView(ancien)
            addView(nouveau)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.settings_parental_code_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.settings_parental_code_remove) { _, _ ->
                confirmRemoveCode(context, onDone)
            }
            .create()

        // Le bouton est câblé après coup : sinon Android referme la boîte à chaque
        // appui, y compris quand la saisie est refusée.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val ancienCode = ancien.text.toString().trim()
                val nouveauCode = nouveau.text.toString().trim()
                when {
                    !ProviderLockStore.verifyPin(context, ancienCode) ->
                        toast(context, R.string.settings_parental_code_wrong_old)
                    !ProviderLockStore.isPinFormatValid(nouveauCode) ->
                        toast(context, R.string.settings_parental_pin_too_short)
                    else -> {
                        ProviderLockStore.changePin(context, ancienCode, nouveauCode)
                        toast(context, R.string.settings_parental_code_changed)
                        dialog.dismiss()
                        onDone()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun confirmRemoveCode(context: Context, onDone: () -> Unit) {
        val saisie = pinInput(context, context.getString(R.string.settings_parental_code_current_hint))
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.settings_parental_code_remove)
            .setMessage(R.string.settings_parental_code_remove_message)
            .setView(saisie)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (ProviderLockStore.verifyPin(context, saisie.text.toString().trim())) {
                    ProviderLockStore.clearPin(context)
                    toast(context, R.string.settings_parental_code_removed)
                    dialog.dismiss()
                    onDone()
                } else {
                    toast(context, R.string.settings_parental_code_wrong_old)
                }
            }
        }
        dialog.show()
    }

    private fun toast(context: Context, messageRes: Int) {
        Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_SHORT).show()
    }

    /**
     * Liste à cocher des providers à verrouiller. Le code est demandé avant —
     * sinon n'importe qui pourrait déverrouiller ce que le code protège.
     */
    fun showLockedProviders(context: Context, onDone: () -> Unit) {
        PinDialog.showAuth(
            context = context,
            title = context.getString(R.string.settings_parental_locked_providers_title),
            onSuccess = { showProviderCheckList(context, onDone) },
        )
    }

    private fun showProviderCheckList(context: Context, onDone: () -> Unit) {
        val noms = allProviderNames()
        if (noms.isEmpty()) return
        val verrouilles = ProviderLockStore.getLockedProviders(context)
        val etat = noms.map { it in verrouilles }.toBooleanArray()
        AlertDialog.Builder(context)
            .setTitle(R.string.settings_parental_locked_providers_dialog)
            .setMultiChoiceItems(noms.toTypedArray(), etat) { _, index, coche ->
                etat[index] = coche
            }
            .setPositiveButton(android.R.string.ok) { d, _ ->
                noms.forEachIndexed { index, nom ->
                    if (etat[index]) ProviderLockStore.lockProvider(context, nom)
                    else ProviderLockStore.unlockProvider(context, nom)
                }
                d.dismiss()
                onDone()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Demande le code avant une modification sensible (l'âge maximum). */
    fun requireCode(context: Context, onGranted: () -> Unit) {
        if (!ProviderLockStore.hasPin(context)) {
            Toast.makeText(
                context,
                context.getString(R.string.settings_parental_set_code_first),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        PinDialog.showAuth(
            context = context,
            title = context.getString(R.string.settings_parental_code_title),
            onSuccess = onGranted,
        )
    }
}
