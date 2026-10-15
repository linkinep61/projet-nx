package com.streamflixreborn.streamflix.activities.sync

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.utils.AppLanguageManager
import com.streamflixreborn.streamflix.utils.DeviceSyncManager
import com.streamflixreborn.streamflix.utils.ThemeManager
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.launch

/**
 * 2026-05-29 : Écran de synchronisation entre appareils via code Firebase.
 *
 * Deux modes :
 * - ENVOI : sérialise toutes les données, les upload sur Firebase, affiche un
 *   code 6 chars que l'autre appareil saisira.
 * - RÉCEPTION : l'user saisit le code → télécharge + applique les données.
 *
 * Compatible mobile (touch) + TV (D-pad : tous les éléments sont focusables).
 */
class DeviceSyncActivity : AppCompatActivity() {

    // ── Vues ──
    private lateinit var btnClose: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView

    // Choix
    private lateinit var layoutChoice: LinearLayout
    private lateinit var btnSend: Button
    private lateinit var btnReceive: Button

    // Envoi
    private lateinit var layoutSend: LinearLayout
    private lateinit var pbSend: ProgressBar
    private lateinit var tvSendLabel: TextView
    private lateinit var tvSendCode: TextView
    private lateinit var tvSendExpires: TextView
    private lateinit var tvSendStatus: TextView

    // Réception
    private lateinit var layoutReceive: LinearLayout
    private lateinit var tvReceiveLabel: TextView
    private lateinit var etReceiveCode: EditText
    private lateinit var btnReceiveConfirm: Button
    private lateinit var pbReceive: ProgressBar
    private lateinit var tvReceiveStatus: TextView

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguageManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeManager.mobileThemeRes(UserPreferences.selectedTheme))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_sync)
        applyThemeWindowChrome()

        bindViews()
        setupListeners()
    }

    private fun bindViews() {
        btnClose = findViewById(R.id.btn_sync_close)
        tvTitle = findViewById(R.id.tv_sync_title)
        tvSubtitle = findViewById(R.id.tv_sync_subtitle)

        layoutChoice = findViewById(R.id.layout_choice)
        btnSend = findViewById(R.id.btn_send)
        btnReceive = findViewById(R.id.btn_receive)

        layoutSend = findViewById(R.id.layout_send)
        pbSend = findViewById(R.id.pb_send)
        tvSendLabel = findViewById(R.id.tv_send_label)
        tvSendCode = findViewById(R.id.tv_send_code)
        tvSendExpires = findViewById(R.id.tv_send_expires)
        tvSendStatus = findViewById(R.id.tv_send_status)

        layoutReceive = findViewById(R.id.layout_receive)
        tvReceiveLabel = findViewById(R.id.tv_receive_label)
        etReceiveCode = findViewById(R.id.et_receive_code)
        btnReceiveConfirm = findViewById(R.id.btn_receive_confirm)
        pbReceive = findViewById(R.id.pb_receive)
        tvReceiveStatus = findViewById(R.id.tv_receive_status)
    }

    private fun setupListeners() {
        btnClose.setOnClickListener { finish() }

        btnSend.setOnClickListener { startSend() }
        btnReceive.setOnClickListener { showReceiveScreen() }
        btnReceiveConfirm.setOnClickListener { startReceive() }

        findViewById<Button>(R.id.btn_drive_save).setOnClickListener {
            ouvrirSelecteur { choisirFichierSauvegarde.launch(DeviceSyncManager.nomFichierSauvegarde()) }
        }
        findViewById<Button>(R.id.btn_drive_load).setOnClickListener {
            ouvrirSelecteur { choisirFichierChargement.launch(arrayOf("*/*")) }
        }

        etReceiveCode.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                startReceive()
                true
            } else false
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  SAUVEGARDE / CHARGEMENT PAR FICHIER (Google Drive…)
    // ══════════════════════════════════════════════════════════════════
    // 2026-10-15 : le sélecteur d'Android propose Google Drive (compte de l'utilisateur),
    //   la mémoire du téléphone, une clé USB… Rien à configurer côté Google.

    private val choisirFichierSauvegarde = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> if (uri != null) sauvegarderDans(uri) }

    private val choisirFichierChargement = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) confirmerChargement(uri) }

    /** Beaucoup de box TV n'ont pas le sélecteur de fichiers (DocumentsUI absent,
     *  constaté pour les vidéos locales) : on le dit au lieu de ne rien faire. */
    private fun ouvrirSelecteur(lancer: () -> Unit) {
        try {
            lancer()
        } catch (e: android.content.ActivityNotFoundException) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Indisponible sur cet appareil")
                .setMessage("Cet appareil n'a pas de sélecteur de fichiers. " +
                    "Utilise plutôt « Envoyer / Recevoir des données » avec un code.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun afficherAttente(message: String) {
        layoutChoice.visibility = View.GONE
        layoutReceive.visibility = View.GONE
        layoutSend.visibility = View.VISIBLE
        pbSend.visibility = View.VISIBLE
        tvSendLabel.visibility = View.GONE
        tvSendCode.visibility = View.GONE
        tvSendExpires.visibility = View.GONE
        tvSendStatus.visibility = View.GONE
        tvSubtitle.text = message
    }

    private fun afficherResultat(ok: Boolean, titre: String, detail: String) {
        pbSend.visibility = View.GONE
        tvSubtitle.text = titre
        tvSendStatus.visibility = View.VISIBLE
        tvSendStatus.text = detail
        tvSendStatus.setTextColor(if (ok) 0xFF4CAF50.toInt() else 0xFFFF4444.toInt())
    }

    private fun sauvegarderDans(uri: android.net.Uri) {
        afficherAttente("Sauvegarde en cours…")
        lifecycleScope.launch {
            DeviceSyncManager.exporterVersFichier(this@DeviceSyncActivity, uri).fold(
                onSuccess = {
                    afficherResultat(true, "Sauvegarde terminée.",
                        "✓ Données sauvegardées.\n\nSur un autre appareil : Synchroniser → " +
                        "« Charger depuis Google Drive », puis choisis ce fichier.\n\n" +
                        "⚠ Le fichier contient aussi tes connexions (TF1+, M6+, RMC+) : " +
                        "ne le partage pas.")
                },
                onFailure = { e ->
                    afficherResultat(false, "La sauvegarde a échoué.", "✗ ${e.message}")
                },
            )
        }
    }

    private fun confirmerChargement(uri: android.net.Uri) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Charger cette sauvegarde ?")
            .setMessage("Les favoris, profils, sources et connexions de la sauvegarde " +
                "seront ajoutés à cet appareil.")
            .setPositiveButton("Charger") { _, _ -> chargerDepuis(uri) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun chargerDepuis(uri: android.net.Uri) {
        afficherAttente("Chargement de la sauvegarde…")
        lifecycleScope.launch {
            DeviceSyncManager.importerDepuisFichier(this@DeviceSyncActivity, uri).fold(
                onSuccess = {
                    // Plusieurs magasins (favoris, réglages) gardent une copie en mémoire : on
                    //   relance l'appli pour que tout soit visible, sans demander à l'utilisateur
                    //   de le faire lui-même.
                    afficherResultat(true, "Chargement terminé.",
                        "✓ Données restaurées avec succès !\n\nONYX redémarre…")
                    tvSendStatus.postDelayed({ redemarrerAppli() }, 2500)
                },
                onFailure = { e ->
                    afficherResultat(false, "Le chargement a échoué.", "✗ ${e.message}")
                },
            )
        }
    }

    private fun redemarrerAppli() {
        val i = packageManager.getLaunchIntentForPackage(packageName) ?: return
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(i)
        finishAffinity()
        Runtime.getRuntime().exit(0)
    }

    // ══════════════════════════════════════════════════════════════════
    //  ENVOI
    // ══════════════════════════════════════════════════════════════════

    private fun startSend() {
        layoutChoice.visibility = View.GONE
        layoutSend.visibility = View.VISIBLE

        pbSend.visibility = View.VISIBLE
        tvSendLabel.visibility = View.GONE
        tvSendCode.visibility = View.GONE
        tvSendExpires.visibility = View.GONE
        tvSendStatus.visibility = View.GONE

        tvSubtitle.text = "Préparation des données…"

        lifecycleScope.launch {
            val result = DeviceSyncManager.sendData(this@DeviceSyncActivity)
            pbSend.visibility = View.GONE

            result.fold(
                onSuccess = { code ->
                    // 2026-09-11 (user : « tu pouvais pas laisser ça comme avant avec le
                    //   code simple ») : retour au code 6 caractères, affiché 3 + 3.
                    val formatted = code.take(3) + " " + code.drop(3)
                    tvSendLabel.visibility = View.VISIBLE
                    tvSendCode.visibility = View.VISIBLE
                    tvSendCode.text = formatted
                    tvSendExpires.visibility = View.VISIBLE
                    // 2026-09-10 (user : « tu devrais mettre un avertissement si les gens ne
                    //   souhaitent pas partager le mot de passe ») : le transfert emporte
                    //   TOUT, comptes compris — c'est voulu, pour n'avoir rien à refaire en
                    //   face. Mais quelqu'un qui passe sa configuration à un proche ne s'en
                    //   doute pas. Il n'y a pas de demi-mesure possible : le bloc part
                    //   entier ou pas du tout, d'où la consigne de se déconnecter avant.
                    tvSendExpires.text = "Ce code expire dans 5 minutes.\n\n" +
                        "⚠ Il contient aussi tes connexions (TF1+, M6+, BFM). " +
                        "Ne le transmets qu'à tes propres appareils — pour le partager " +
                        "avec quelqu'un d'autre, déconnecte-toi de ces services avant de " +
                        "générer le code."
                    tvSubtitle.text = "Données prêtes !"

                    tvSendStatus.visibility = View.VISIBLE
                    tvSendStatus.text = "✓ Données envoyées"
                    tvSendStatus.setTextColor(0xFF4CAF50.toInt())
                },
                onFailure = { error ->
                    tvSendStatus.visibility = View.VISIBLE
                    tvSendStatus.text = "✗ Erreur : ${error.message}"
                    tvSendStatus.setTextColor(0xFFFF4444.toInt())
                    tvSubtitle.text = "L'envoi a échoué."
                }
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  RÉCEPTION
    // ══════════════════════════════════════════════════════════════════

    private fun showReceiveScreen() {
        layoutChoice.visibility = View.GONE
        layoutReceive.visibility = View.VISIBLE
        tvSubtitle.text = "Saisis le code affiché sur l'autre appareil."

        etReceiveCode.requestFocus()
        // Ouvrir le clavier (mobile)
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(etReceiveCode, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun startReceive() {
        val code = etReceiveCode.text.toString().trim()
        if (code.length < 6) {
            Toast.makeText(this, "Code incomplet", Toast.LENGTH_SHORT).show()
            return
        }

        // Cacher le clavier
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(etReceiveCode.windowToken, 0)

        btnReceiveConfirm.isEnabled = false
        etReceiveCode.isEnabled = false
        pbReceive.visibility = View.VISIBLE
        tvReceiveStatus.visibility = View.GONE
        tvSubtitle.text = "Synchronisation en cours…"

        lifecycleScope.launch {
            val result = DeviceSyncManager.receiveData(this@DeviceSyncActivity, code)
            pbReceive.visibility = View.GONE

            result.fold(
                onSuccess = {
                    tvReceiveStatus.visibility = View.VISIBLE
                    tvReceiveStatus.text = "✓ Données synchronisées avec succès !"
                    tvReceiveStatus.setTextColor(0xFF4CAF50.toInt())
                    tvSubtitle.text = "Synchronisation terminée."
                    btnReceiveConfirm.text = "Fermer"
                    btnReceiveConfirm.isEnabled = true
                    btnReceiveConfirm.setOnClickListener { finish() }
                },
                onFailure = { error ->
                    tvReceiveStatus.visibility = View.VISIBLE
                    tvReceiveStatus.text = "✗ ${error.message}"
                    tvReceiveStatus.setTextColor(0xFFFF4444.toInt())
                    tvSubtitle.text = "La synchronisation a échoué."
                    btnReceiveConfirm.isEnabled = true
                    etReceiveCode.isEnabled = true
                }
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════

    private fun applyThemeWindowChrome() {
        val palette = ThemeManager.palette(UserPreferences.selectedTheme)
        window.statusBarColor = palette.systemBar
        window.navigationBarColor = palette.systemBar
    }
}
