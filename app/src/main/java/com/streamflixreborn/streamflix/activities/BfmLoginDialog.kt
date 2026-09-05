package com.streamflixreborn.streamflix.activities

import android.app.AlertDialog
import android.content.Context
import com.streamflixreborn.streamflix.utils.RmcPlusAuth

/**
 * Dialog « Connexion RMC+ » (ex-RMC BFM Play).
 *
 * 2026-09-05 (user : « il faut réparer la totalité ») : l'ancien formulaire natif
 * email + mot de passe → SSO REST n'a plus de serveur en face (RMC BFM Play est devenu RMC+,
 * le SSO CAS répond 504, voir [RmcPlusAuth]). La connexion se fait maintenant dans la
 * WebView, sur le vrai site, comme pour TF1+ et M6+ ; ce dialog ne fait qu'expliquer et
 * ouvrir la WebView. Ancienne version : `BfmLoginDialog.kt.bak-rmcplus`.
 *
 * [onSuccess] : gardé pour compatibilité, appelé quand l'utilisateur lance la connexion
 * (le résultat réel arrive par LoginWebViewActivity → RmcPlusAuth ; le TV Hub se
 * rafraîchit tout seul via ProviderChangeNotifier au retour).
 */
object BfmLoginDialog {

    fun show(ctx: Context, onSuccess: (() -> Unit)? = null) {
        val deja = RmcPlusAuth.estConnecte(ctx)
        AlertDialog.Builder(ctx)
            .setTitle("Connexion RMC+")
            .setMessage(
                (if (deja) "Ta session RMC+ n'est plus acceptée par le site. " else "") +
                    "RMC BFM Play est devenu RMC+ : la connexion se fait sur le site, avec ton " +
                    "compte RMC BFM habituel (gratuit). Une fois connecté, appuie sur " +
                    "« Terminer connexion » en bas de la page — la session reste valable " +
                    "plusieurs mois.",
            )
            .setPositiveButton("Se connecter") { _, _ ->
                LoginWebViewActivity.start(ctx, LoginWebViewActivity.SERVICE_BFM)
                onSuccess?.invoke()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
