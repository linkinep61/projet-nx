package com.streamflixreborn.streamflix.activities.profile

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.activities.main.MainMobileActivity
import com.streamflixreborn.streamflix.models.Profile
import com.streamflixreborn.streamflix.utils.ProfileManager
import com.streamflixreborn.streamflix.utils.ProfileStore

/**
 * 2026-05-12 : écran "Qui regarde ?" lancé avant [MainMobileActivity] quand
 * aucun profil n'est actif. Affiche les profils existants + carte "+" pour
 * créer. Bouton "Gérer les profils" pour CRUD complet.
 *
 * Pourquoi pas un Fragment dans MainMobileActivity ? L'idée Netflix-style est
 * que le picker soit le point d'entrée stable de l'app — pas une étape de
 * navigation interne. Activity séparée = back button quitte l'app proprement,
 * pas de retour à la home.
 */
class ProfilePickerActivity : FragmentActivity() {

    private lateinit var rvProfiles: RecyclerView
    private lateinit var btnManage: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile_picker)
        rvProfiles = findViewById(R.id.rv_profiles)
        btnManage = findViewById(R.id.btn_manage_profiles)
        btnManage.setOnClickListener { openManagementDialog() }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        val profiles = ProfileStore.getAll()
        rvProfiles.adapter = ProfilePickerAdapter(
            profiles = profiles,
            onProfileClick = ::onProfileClicked,
            onAddProfileClick = ::openAddProfileDialog,
        )
    }

    private fun onProfileClicked(profile: Profile) {
        if (profile.pinHash != null) {
            promptPin(profile) { ok ->
                if (ok) enterProfile(profile)
            }
        } else {
            enterProfile(profile)
        }
    }

    private fun enterProfile(profile: Profile) {
        ProfileManager.setCurrentProfile(profile)
        Log.d(TAG, "Entering profile: ${profile.name} (${profile.id})")
        // 2026-05-12 (user "on ne peut pas faire retour pour retourner ce
        // nouveau menu") : NE PAS finish() la picker — on veut que le bouton
        // retour depuis la home retourne sur "Qui regarde ?". FLAG_CLEAR_TOP
        // évite d'empiler plusieurs MainMobileActivity si l'user reclique le
        // même profil rapidement.
        startActivity(
            Intent(this, MainMobileActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("FORCE_PROVIDERS_SCREEN", true)
        )
    }

    private fun promptPin(profile: Profile, onResult: (Boolean) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN à 4 chiffres"
        }
        AlertDialog.Builder(this)
            .setTitle("PIN requis pour ${profile.name}")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                if (ProfileStore.verifyPin(profile, input.text.toString())) {
                    onResult(true)
                } else {
                    Toast.makeText(this, "PIN incorrect", Toast.LENGTH_SHORT).show()
                    onResult(false)
                }
            }
            .setNegativeButton("Annuler") { _, _ -> onResult(false) }
            .show()
    }

    /** Dialog très simple pour créer un profil (nom + emoji picker visuel). */
    private fun openAddProfileDialog() {
        val nameInput = EditText(this).apply {
            hint = "Nom"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        // Bouton qui ouvre EmojiPickerDialog (cohérent mobile + TV).
        var pickedEmoji = "🎬"
        val emojiButton = android.widget.Button(this).apply {
            text = "Avatar : $pickedEmoji"
            setOnClickListener {
                EmojiPickerDialog.show(this@ProfilePickerActivity, pickedEmoji) { e ->
                    pickedEmoji = e
                    text = "Avatar : $e"
                }
            }
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(nameInput)
            addView(TextView(this@ProfilePickerActivity).apply { setPadding(0, 24, 0, 0) })
            addView(emojiButton)
        }
        AlertDialog.Builder(this)
            .setTitle("Nouveau profil")
            .setView(container)
            .setPositiveButton("Créer") { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isBlank()) {
                    Toast.makeText(this, "Le nom est requis", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val newProfile = Profile(
                    id = ProfileStore.generateId(),
                    name = name,
                    emoji = pickedEmoji,
                    isAdmin = false,
                )
                ProfileStore.upsert(newProfile)
                refreshList()
                Toast.makeText(this, "Profil '${name}' créé", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** Mini-management : liste les profils avec bouton Supprimer. Renommage
     *  par clic long. Une vraie UI riche viendra en Phase 2. */
    private fun openManagementDialog() {
        val profiles = ProfileStore.getAll()
        val items = profiles.map { "${it.emoji} ${it.name}${if (it.isAdmin) " (admin)" else ""}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Gérer les profils")
            .setItems(items) { _, idx ->
                openEditProfileDialog(profiles[idx])
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun openEditProfileDialog(profile: Profile) {
        val options = mutableListOf("Renommer", "Changer emoji")
        if (profile.pinHash != null) options += "Supprimer PIN" else options += "Définir un PIN"
        options += "Supprimer ce profil"
        AlertDialog.Builder(this)
            .setTitle("${profile.emoji} ${profile.name}")
            .setItems(options.toTypedArray()) { _, idx ->
                when (options[idx]) {
                    "Renommer" -> editText(profile, "Renommer", profile.name) { value ->
                        ProfileStore.upsert(profile.copy(name = value))
                        refreshList()
                    }
                    "Changer emoji" -> EmojiPickerDialog.show(this, profile.emoji) { picked ->
                        ProfileStore.upsert(profile.copy(emoji = picked))
                        refreshList()
                    }
                    "Définir un PIN" -> editText(
                        profile,
                        "PIN à 4 chiffres",
                        "",
                        InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
                    ) { value ->
                        if (value.length in 3..6) {
                            ProfileStore.upsert(profile.copy(pinHash = ProfileStore.hashPin(value)))
                            refreshList()
                        } else {
                            Toast.makeText(this, "PIN entre 3 et 6 chiffres", Toast.LENGTH_SHORT).show()
                        }
                    }
                    "Supprimer PIN" -> {
                        ProfileStore.upsert(profile.copy(pinHash = null))
                        refreshList()
                    }
                    "Supprimer ce profil" -> confirmDelete(profile)
                }
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun confirmDelete(profile: Profile) {
        AlertDialog.Builder(this)
            .setTitle("Supprimer ${profile.name} ?")
            .setMessage("Les favoris et l'historique de ce profil seront perdus. Cette action est irréversible.")
            .setPositiveButton("Supprimer") { _, _ ->
                val ok = ProfileStore.delete(profile.id)
                if (!ok) {
                    Toast.makeText(
                        this,
                        "Impossible : c'est le dernier profil admin",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    refreshList()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun editText(
        profile: Profile,
        title: String,
        initial: String,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        onConfirm: (String) -> Unit,
    ) {
        val input = EditText(this).apply {
            setText(initial)
            this.inputType = inputType
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                onConfirm(input.text.toString().trim())
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    companion object {
        private const val TAG = "ProfilePicker"
    }
}
