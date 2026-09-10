package com.streamflixreborn.streamflix.activities

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.streamflixreborn.streamflix.utils.BowdAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 2026-09-10 — Form natif « Connexion Bowd », calqué sur [TF1LoginDialog].
 *
 * Saisie UNE fois : identifiants stockés localement, puis reconnexion silencieuse
 * à chaque expiration de session (cf. [BowdAuth.jeton]). L'utilisateur ne revoit
 * jamais cet écran tant qu'il ne se déconnecte pas.
 *
 * La CRÉATION de compte reste chez eux, dans leur formulaire : leur inscription est
 * protégée par un captcha, et l'app ne le contourne pas. D'où le bouton « Créer un
 * compte » qui ouvre simplement leur page dans le navigateur du téléphone.
 */
object BowdLoginDialog {

    /** Mémorise l'identifiant SEUL (jamais le mot de passe) pour le pré-remplir au retour
     *  de l'inscription. Le couple complet n'est enregistré qu'après une connexion
     *  réussie, par [BowdAuth.saveCredentials]. */
    private fun memoriserIdentifiant(ctx: Context, identifiant: String, motDePasse: String = "") {
        runCatching {
            val e = ctx.applicationContext
                .getSharedPreferences("bowd_creds", Context.MODE_PRIVATE)
                .edit().putString("username", identifiant)
            // Le mot de passe n'est garde QUE s'il a ete saisi ici par l'utilisateur, pour
            //   lui eviter de le retaper au retour de l'inscription. La connexion reussie
            //   le confirmera de toute facon via BowdAuth.saveCredentials.
            if (motDePasse.isNotEmpty()) e.putString("password", motDePasse)
            e.apply()
        }
    }

    fun show(ctx: Context, onSuccess: (() -> Unit)? = null) {
        val padding = (16 * ctx.resources.displayMetrics.density).toInt()

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, 0)
        }
        val info = TextView(ctx).apply {
            text = "Saisis ton compte Bowd (= celui de bowdtv.com). Tes identifiants " +
                   "restent sur cet appareil et servent à te reconnecter tout seul " +
                   "quand la session expire.\n\n" +
                   "Pas encore de compte ? Crée-le sur bowdtv.com, puis reviens ici."
            setPadding(0, 0, 0, padding)
        }
        // 2026-09-10 (user : « le truc qu'il faut pas oublier c'est pour la version TV,
        //   pour remplir les différentes catégories sur la page de connexion ») :
        //   réglages indispensables à la télécommande. Le piège principal est
        //   `setSingleLine` : un EditText multi-ligne AVALE la touche Bas, et l'utilisateur
        //   reste bloqué sur le premier champ sans jamais atteindre le second. Le reste
        //   (focusableInTouchMode + focus initial + IME NEXT/DONE) fait que le D-pad entre
        //   dans le formulaire et en ressort proprement vers les boutons du dialogue.
        val surTv = ctx.packageManager.hasSystemFeature(
            android.content.pm.PackageManager.FEATURE_LEANBACK
        )
        val userField = EditText(ctx).apply {
            hint = "Identifiant"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
            isFocusable = true
            isFocusableInTouchMode = true
            setSelectAllOnFocus(true)
            setText(BowdAuth.savedUsername(ctx) ?: "")
            if (surTv) textSize = 18f
        }
        val passField = EditText(ctx).apply {
            hint = "Mot de passe"
            inputType = InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            isFocusable = true
            isFocusableInTouchMode = true
            if (surTv) textSize = 18f
        }
        container.addView(info)
        container.addView(userField)
        container.addView(passField)

        AlertDialog.Builder(ctx)
            .setTitle("Connexion Bowd")
            .setView(container)
            .setPositiveButton("Connexion") { _, _ ->
                val u = userField.text.toString().trim()
                val p = passField.text.toString()
                if (u.isEmpty() || p.isEmpty()) {
                    Toast.makeText(ctx, "Identifiant et mot de passe requis", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                Toast.makeText(ctx, "Connexion à Bowd…", Toast.LENGTH_SHORT).show()
                CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                    val jwt = try { BowdAuth.login(u, p) } catch (_: Throwable) { null }
                    withContext(Dispatchers.Main) {
                        if (jwt != null) {
                            BowdAuth.saveCredentials(ctx, u, p)
                            Toast.makeText(ctx, "✓ Connecté à Bowd", Toast.LENGTH_SHORT).show()
                            onSuccess?.invoke()
                        } else {
                            Toast.makeText(
                                ctx,
                                "Échec connexion Bowd — vérifie identifiant/mot de passe",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            }
            // 2026-09-10 (user : « ça évite de tout réécrire ») : l'inscription reste DANS
            //   l'app, dans la WebView déjà utilisée ailleurs, au lieu de rejeter
            //   l'utilisateur vers son navigateur. Il remplit LEUR formulaire et coche
            //   LEUR contrôle lui-même : l'app ne crée aucun compte et ne remplit rien à
            //   sa place. L'identifiant déjà saisi ici est mémorisé avant le détour, pour
            //   être pré-rempli au retour.
            .setNeutralButton("Créer un compte") { _, _ ->
                // 2026-09-10 (user : « le mieux c'est que l'utilisateur mette un mot de passe
                //   et un pseudo directement, et quand il arrive sur la page ça le met au bon
                //   endroit ») : on emporte CE QU'IL A SAISI vers leur formulaire. L'app
                //   n'invente aucun identifiant, ne valide rien et ne touche pas au controle
                //   anti-robot — elle recopie, comme un gestionnaire de mots de passe.
                //   Les valeurs sont aussi gardees ici, pour que le retour soit immediat.
                val dejaSaisi = userField.text.toString().trim()
                val mdpSaisi = passField.text.toString()
                if (dejaSaisi.isNotEmpty()) memoriserIdentifiant(ctx, dejaSaisi, mdpSaisi)
                if (dejaSaisi.isEmpty() || mdpSaisi.isEmpty()) {
                    Toast.makeText(
                        ctx,
                        "Choisis d'abord un identifiant et un mot de passe : ils seront " +
                            "recopiés dans leur formulaire et conservés ici.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                val interne = runCatching {
                    ctx.startActivity(
                        android.content.Intent(
                            ctx,
                            com.streamflixreborn.streamflix.activities.tools.BypassWebViewActivity::class.java,
                        ).putExtra(
                            com.streamflixreborn.streamflix.activities.tools.BypassWebViewActivity.EXTRA_URL,
                            "https://bowdtv.com/signup",
                        ).putExtra(
                            com.streamflixreborn.streamflix.activities.tools.BypassWebViewActivity.EXTRA_PREREMPLIR_USER,
                            dejaSaisi,
                        ).putExtra(
                            com.streamflixreborn.streamflix.activities.tools.BypassWebViewActivity.EXTRA_PREREMPLIR_PASS,
                            mdpSaisi,
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.isSuccess
                if (!interne) runCatching {
                    ctx.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://bowdtv.com/signup"),
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
            .also {
                // Le D-pad doit atterrir sur un champ, pas dans le vide.
                userField.requestFocus()
            }
    }
}
