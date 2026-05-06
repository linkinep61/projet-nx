package com.streamflixreborn.streamflix.ui

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.streamflixreborn.streamflix.utils.ProviderLockStore

/**
 * 2026-05-05 : Dialog universel pour saisir / définir un PIN à 4 chiffres
 * pour le contrôle parental des providers.
 *
 * Trois modes :
 *   - SETUP   : premier setup → demande PIN + confirmation
 *   - VERIFY  : PIN existant → vérifie qu'il correspond
 *   - CHANGE  : ancien + nouveau PIN
 *
 * UI minimaliste avec AlertDialog standard pour fonctionner Mobile ET TV
 * (D-pad navigation native sur TextInput).
 */
object PinDialog {

    /** Affiche le dialog approprié selon que le PIN est configuré ou non.
     *  Si configuré → VERIFY. Sinon → SETUP. Callback `onSuccess` invoqué
     *  quand l'utilisateur a entré un PIN valide. */
    fun showAuth(
        context: Context,
        title: String = "Code parental",
        onSuccess: () -> Unit,
        onCancel: (() -> Unit)? = null,
    ) {
        if (ProviderLockStore.hasPin(context)) {
            showVerify(context, title, onSuccess, onCancel)
        } else {
            showSetup(context, onSuccess, onCancel)
        }
    }

    private fun showSetup(
        context: Context,
        onSuccess: () -> Unit,
        onCancel: (() -> Unit)?,
    ) {
        val pinInput = makePinInput(context)
        val confirmInput = makePinInput(context)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
            addView(TextView(context).apply {
                text = "Définissez un code à 4 chiffres pour verrouiller des providers."
                gravity = Gravity.START
            })
            addView(pinInput)
            addView(TextView(context).apply { text = "Confirmer :"; gravity = Gravity.START })
            addView(confirmInput)
        }
        AlertDialog.Builder(context)
            .setTitle("Créer un code parental")
            .setView(container)
            .setPositiveButton("Valider") { d, _ ->
                val pin = pinInput.text.toString().trim()
                val confirm = confirmInput.text.toString().trim()
                when {
                    pin.length !in 4..8 || !pin.all { it.isDigit() } ->
                        toast(context, "Le code doit faire 4 à 8 chiffres")
                    pin != confirm ->
                        toast(context, "Les codes ne correspondent pas")
                    else -> {
                        ProviderLockStore.setupPin(context, pin)
                        toast(context, "Code parental créé ✓")
                        d.dismiss()
                        onSuccess()
                    }
                }
            }
            .setNegativeButton("Annuler") { d, _ -> d.dismiss(); onCancel?.invoke() }
            .setCancelable(false)
            .show()
    }

    private fun showVerify(
        context: Context,
        title: String,
        onSuccess: () -> Unit,
        onCancel: (() -> Unit)?,
    ) {
        val pinInput = makePinInput(context)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
            addView(TextView(context).apply { text = "Saisissez le code parental :" })
            addView(pinInput)
        }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(container)
            .setPositiveButton("Valider") { d, _ ->
                val pin = pinInput.text.toString().trim()
                if (ProviderLockStore.verifyPin(context, pin)) {
                    d.dismiss()
                    onSuccess()
                } else {
                    toast(context, "Code incorrect")
                }
            }
            .setNegativeButton("Annuler") { d, _ -> d.dismiss(); onCancel?.invoke() }
            .show()
    }

    private fun makePinInput(context: Context): EditText {
        return EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            hint = "•••• "
            setPadding(24, 24, 24, 24)
        }
    }

    private fun toast(context: Context, msg: String) {
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
