package com.streamflixreborn.streamflix.activities.profile

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.activities.main.MainTvActivity
import com.streamflixreborn.streamflix.models.Profile
import com.streamflixreborn.streamflix.utils.ProfileManager
import com.streamflixreborn.streamflix.utils.ProfileStore

/**
 * 2026-05-12 : équivalent TV de [ProfilePickerActivity]. Affiche un grid
 * horizontal de profils en Leanback, avec focus visible D-pad et items
 * plus grands (200×200) pour visibilité à distance.
 *
 * Pour les dialogs CRUD (créer/éditer/supprimer profil), on réutilise les
 * AlertDialogs standards — ça marche sur TV (navigable D-pad) même si c'est
 * moins joli qu'une UI Leanback pure. V1 acceptable, on peut polir plus tard.
 */
class ProfilePickerTvActivity : FragmentActivity() {

    private lateinit var rvProfiles: RecyclerView
    private lateinit var btnManage: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile_picker_tv)
        rvProfiles = findViewById(R.id.rv_profiles)
        rvProfiles.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
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
            itemLayoutRes = R.layout.item_profile_tv,
        )
        // Demande le focus initial sur le 1er profil (D-pad-friendly cold start).
        rvProfiles.post {
            rvProfiles.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    private fun onProfileClicked(profile: Profile) {
        if (profile.pinHash != null) {
            promptPin(profile) { ok -> if (ok) enterProfile(profile) }
        } else {
            enterProfile(profile)
        }
    }

    private fun enterProfile(profile: Profile) {
        ProfileManager.setCurrentProfile(profile)
        Log.d(TAG, "Entering profile: ${profile.name} (${profile.id})")
        startActivity(
            Intent(this, MainTvActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    private fun promptPin(profile: Profile, onResult: (Boolean) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN"
        }
        AlertDialog.Builder(this)
            .setTitle("PIN requis pour ${profile.name}")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                if (ProfileStore.verifyPin(profile, input.text.toString())) onResult(true)
                else {
                    Toast.makeText(this, "PIN incorrect", Toast.LENGTH_SHORT).show()
                    onResult(false)
                }
            }
            .setNegativeButton("Annuler") { _, _ -> onResult(false) }
            .show()
    }

    private fun openAddProfileDialog() {
        val nameInput = EditText(this).apply {
            hint = "Nom"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        // 2026-05-12 (user "la télé on n'a pas d'emoticônes") : bouton qui
        // ouvre EmojiPickerDialog au lieu d'un EditText (clavier TV ne donne
        // pas accès aux emojis).
        var pickedEmoji = "🎬"
        val emojiButton = android.widget.Button(this).apply {
            text = "Avatar : $pickedEmoji"
            isFocusable = true
            setOnClickListener {
                EmojiPickerDialog.show(this@ProfilePickerTvActivity, pickedEmoji) { e ->
                    pickedEmoji = e
                    text = "Avatar : $e"
                }
            }
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(nameInput)
            addView(TextView(this@ProfilePickerTvActivity).apply { setPadding(0, 24, 0, 0) })
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
                ProfileStore.upsert(
                    Profile(
                        id = ProfileStore.generateId(),
                        name = name,
                        emoji = pickedEmoji,
                        isAdmin = false,
                    )
                )
                refreshList()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun openManagementDialog() {
        val profiles = ProfileStore.getAll()
        val items = profiles.map {
            "${it.emoji} ${it.name}${if (it.isAdmin) " (admin)" else ""}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Gérer les profils")
            .setItems(items) { _, idx -> openEditProfileDialog(profiles[idx]) }
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
            .setMessage("Les favoris et l'historique de ce profil seront perdus.")
            .setPositiveButton("Supprimer") { _, _ ->
                val ok = ProfileStore.delete(profile.id)
                if (!ok) {
                    Toast.makeText(
                        this,
                        "Impossible : c'est le dernier profil admin",
                        Toast.LENGTH_LONG,
                    ).show()
                } else refreshList()
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
            .setPositiveButton("OK") { _, _ -> onConfirm(input.text.toString().trim()) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    companion object {
        private const val TAG = "ProfilePickerTv"
    }
}
