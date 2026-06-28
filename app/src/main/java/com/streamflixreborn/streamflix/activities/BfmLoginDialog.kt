package com.streamflixreborn.streamflix.activities

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.streamflixreborn.streamflix.utils.BfmSsoAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dialog natif "Connexion RMC BFM Play". Saisie email + password → pipeline
 * SSO REST via BfmSsoAuth → token + credentials persistants pour re-login
 * auto quand le token expire (~24h).
 *
 * Modèle identique à TF1LoginDialog : on capture les credentials côté Android
 * (pas via JS hook dans la WebView) → fiable à 100%.
 */
object BfmLoginDialog {

    /**
     * Affiche le dialog de connexion BFM.
     * Si le REST SSO échoue → fallback vers la WebView OIDC classique
     * (les credentials sont déjà sauvegardés à ce stade).
     */
    fun show(ctx: Context, onSuccess: (() -> Unit)? = null) {
        val padding = (16 * ctx.resources.displayMetrics.density).toInt()

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, 0)
        }

        // 2026-06-23 (user "mettre un message quand on doit se reconnecter
        //   au lieu d'attendre sans savoir") : affiche un AVERTISSEMENT
        //   rouge en haut du dialog si le compte est bloqué localement
        //   (= 3 fails consécutifs sur 24h). L'user comprend qu'il doit
        //   reset son password BFM AVANT de retaper, sinon le serveur va
        //   le rejeter encore.
        val isBlocked = BfmSsoAuth.isLoginBlocked(ctx)
        val info = TextView(ctx).apply {
            text = if (isBlocked) {
                "⚠️ CONNEXION BLOQUÉE\n\n" +
                "3 tentatives ont échoué récemment. Pour éviter que BFM bloque " +
                "complètement ton compte, va sur https://rmcbfmplay.com et " +
                "RÉINITIALISE TON MOT DE PASSE.\n\n" +
                "Ensuite reviens ici et saisis ton NOUVEAU mot de passe (le " +
                "compteur sera reset automatiquement)."
            } else {
                "Saisis ton compte RMC BFM Play. " +
                "Tes identifiants sont stockés localement et utilisés " +
                "pour lancer les replays et les lives BFM/RMC."
            }
            if (isBlocked) {
                setTextColor(android.graphics.Color.parseColor("#FFFF6B6B"))
                textSize = 14f
            }
            setPadding(0, 0, 0, padding)
        }
        val emailField = EditText(ctx).apply {
            hint = "Email"
            inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or InputType.TYPE_CLASS_TEXT
            setText(BfmSsoAuth.savedEmail(ctx) ?: "")
        }
        val passField = EditText(ctx).apply {
            hint = "Mot de passe"
            inputType = InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_CLASS_TEXT
            // 2026-06-22 (user "je dois remettre mon mot de passe à chaque fois") :
            //   pré-remplir le mot de passe sauvegardé, comme on fait déjà pour l'email.
            //   L'user n'a plus qu'à cliquer Connexion sans rien retaper.
            BfmSsoAuth.savedPassword(ctx)?.takeIf { it.isNotBlank() }?.let { setText(it) }
        }
        container.addView(info)
        container.addView(emailField)
        container.addView(passField)

        AlertDialog.Builder(ctx)
            .setTitle("Connexion RMC BFM Play")
            .setView(container)
            .setPositiveButton("Connexion") { _, _ ->
                val email = emailField.text.toString().trim()
                val pass = passField.text.toString()
                if (email.isEmpty() || pass.isEmpty()) {
                    Toast.makeText(ctx, "Email et mot de passe requis", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // Sauvegarder les credentials IMMÉDIATEMENT (même si le login
                // REST échoue, on les a pour le fallback WebView + relogin)
                BfmSsoAuth.saveCredentials(ctx, email, pass)
                Toast.makeText(ctx, "Connexion à BFM Play…", Toast.LENGTH_SHORT).show()

                CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                    val token = try { BfmSsoAuth.login(ctx, email, pass) }
                               catch (_: Throwable) { null }
                    withContext(Dispatchers.Main) {
                        if (token != null) {
                            Toast.makeText(ctx, "✓ Connecté à BFM Play", Toast.LENGTH_SHORT).show()
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
                            // REST SSO a échoué → fallback WebView OIDC
                            // (les credentials sont déjà sauvegardés)
                            Toast.makeText(ctx,
                                "Login REST échoué — ouverture du site BFM…",
                                Toast.LENGTH_SHORT).show()
                            LoginWebViewActivity.start(
                                ctx, LoginWebViewActivity.SERVICE_BFM
                            )
                        }
                    }
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
