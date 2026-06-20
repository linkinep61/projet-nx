package com.streamflixreborn.streamflix.activities

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.streamflixreborn.streamflix.utils.TF1Auth
import com.streamflixreborn.streamflix.utils.TF1GigyaAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Form natif "Connexion TF1+". Saisie email + password → pipeline Gigya
 * complet via TF1GigyaAuth → JWT persistant + email/password persistants
 * pour re-login auto si le JWT expire.
 */
object TF1LoginDialog {

    /** 2026-06-19 (user "Email+mdp n'a pas l'air de fonctionner, on la retire
     *  car il y a déjà Google etc.") : ouvre directement la WebView TF1+ qui
     *  propose Google/Facebook/Apple + email/mdp natif (= sur TV, les boutons
     *  OAuth sont masqués via CSS injection pour forcer le login D-pad). */
    fun show(ctx: Context, onSuccess: (() -> Unit)? = null) {
        LoginWebViewActivity.start(ctx, LoginWebViewActivity.SERVICE_TF1)
    }

    private fun showEmailForm(ctx: Context, onSuccess: (() -> Unit)? = null) {
        val padding = (16 * ctx.resources.displayMetrics.density).toInt()

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, 0)
        }

        val info = TextView(ctx).apply {
            text = "Saisis ton compte TF1+ (= identique à tf1.fr/compte). " +
                   "Tes identifiants sont stockés localement et utilisés pour " +
                   "lancer les replays/live."
            setPadding(0, 0, 0, padding)
        }
        val emailField = EditText(ctx).apply {
            hint = "Email"
            inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or InputType.TYPE_CLASS_TEXT
            setText(TF1GigyaAuth.savedEmail(ctx) ?: "")
        }
        val passField = EditText(ctx).apply {
            hint = "Mot de passe"
            inputType = InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_CLASS_TEXT
        }
        container.addView(info)
        container.addView(emailField)
        container.addView(passField)

        AlertDialog.Builder(ctx)
            .setTitle("Connexion TF1+")
            .setView(container)
            .setPositiveButton("Connexion") { _, _ ->
                val email = emailField.text.toString().trim()
                val pass = passField.text.toString()
                if (email.isEmpty() || pass.isEmpty()) {
                    Toast.makeText(ctx, "Email et mot de passe requis", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                Toast.makeText(ctx, "Connexion à TF1+…", Toast.LENGTH_SHORT).show()
                CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                    val token = try { TF1GigyaAuth.login(email, pass) }
                                catch (_: Throwable) { null }
                    withContext(Dispatchers.Main) {
                        if (token != null) {
                            TF1Auth.saveToken(ctx, token, refresh = null, exp = null)
                            TF1GigyaAuth.saveCredentials(ctx, email, pass)
                            Toast.makeText(ctx, "✓ Connecté à TF1+", Toast.LENGTH_SHORT).show()
                            // Notifie le home pour rafraîchir l'affichage (=
                            //   masquer la card "🔓 Connexion" remplacée par
                            //   "✓ Connecté").
                            try {
                                com.streamflixreborn.streamflix.utils.UserPreferences
                                    .currentProvider?.let { p ->
                                    com.streamflixreborn.streamflix.utils.HomeCacheStore.clear(ctx, p)
                                }
                                com.streamflixreborn.streamflix.utils.ProviderChangeNotifier
                                    .notifyProviderChanged()
                            } catch (_: Throwable) {}
                            onSuccess?.invoke()
                        } else {
                            Toast.makeText(ctx,
                                "Échec connexion TF1+ — vérifie email/mdp",
                                Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
