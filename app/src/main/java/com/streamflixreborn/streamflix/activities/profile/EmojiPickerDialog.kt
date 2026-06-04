package com.streamflixreborn.streamflix.activities.profile

import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.R
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * 2026-05-12 : dialog de sélection d'emoji pour l'avatar de profil. Affiche
 * une grille focusable (D-pad friendly TV + tap mobile) des emojis de
 * [ProfileEmojis.list]. Au clic, callback avec l'emoji sélectionné.
 *
 * 2026-05-29 : ajout bouton "Coller une URL d'image" en haut du picker.
 * Permet de mettre une image perso (URL ou GIF) comme avatar de profil,
 * comme Nuvio. L'URL est stockée directement dans Profile.emoji et
 * ProfileEmojiArt.urlForValue() la détecte automatiquement (startsWith http).
 *
 * Utilisé par [ProfilePickerActivity] et [ProfilePickerTvActivity] dans
 * les flows "créer profil" et "changer emoji".
 */
object EmojiPickerDialog {

    fun show(context: Context, currentEmoji: String?, onPick: (String) -> Unit) {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.dialog_emoji_picker, null, false)
        val rv = view.findViewById<RecyclerView>(R.id.rv_emoji_picker)
        // 2026-06-03 (user "réduire une ligne pour faire que 5 colonnes, ça
        //   fait pas beau et ça tasse") : 6 → 5 colonnes pour respirer.
        rv.layoutManager = GridLayoutManager(context, 5)

        var dialogRef: AlertDialog? = null
        // 2026-05-20 : set COMPLET Fluent (~1581) au lieu des 67 curés.
        rv.adapter = Adapter(FluentAvatars.list(context), currentEmoji) { picked ->
            onPick(picked)
            dialogRef?.dismiss()
        }

        // 2026-05-29 : bouton URL personnalisée
        val btnUrl = view.findViewById<Button>(R.id.btn_custom_url)
        btnUrl.setOnClickListener {
            showCustomUrlDialog(context, currentEmoji) { url ->
                onPick(url)
                dialogRef?.dismiss()
            }
        }

        dialogRef = AlertDialog.Builder(context)
            .setTitle("Choisir un avatar")
            .setView(view)
            .setNegativeButton("Annuler", null)
            .create()
        dialogRef.show()

        // Demande le focus initial sur le bouton URL (D-pad ready).
        btnUrl.post { btnUrl.requestFocus() }
    }

    /**
     * Dialog pour saisir une URL d'image personnalisée.
     * Accepte http/https, PNG/JPG/WEBP/GIF.
     */
    private fun showCustomUrlDialog(
        context: Context,
        currentUrl: String?,
        onConfirm: (String) -> Unit,
    ) {
        val input = EditText(context).apply {
            hint = "https://exemple.com/avatar.png"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            // Pré-remplir si l'avatar actuel est déjà une URL
            if (currentUrl?.startsWith("http") == true) {
                setText(currentUrl)
                selectAll()
            }
            setPadding(48, 24, 48, 0)
        }
        AlertDialog.Builder(context)
            .setTitle("URL d'avatar personnalisée")
            .setMessage("Colle un lien d'image (PNG, JPG, WEBP ou GIF animé)")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val url = input.text.toString().trim()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    // 2026-06-03 : URLs Tenor/Giphy/Imgur sont des pages HTML —
                    //   résoudre vers le fichier image direct (og:image) sinon
                    //   Glide ne peut pas afficher.
                    val pendingToast = if (url.contains("tenor.com") || url.contains("giphy.com") ||
                                           url.contains("imgur.com")) {
                        Toast.makeText(context, "Résolution du lien…", Toast.LENGTH_SHORT).also { it.show() }
                    } else null
                    com.streamflixreborn.streamflix.utils.AvatarUrlResolver.resolveAsync(url) { resolved ->
                        pendingToast?.cancel()
                        // 2026-06-03 (user "y a plus d'image" sans internet) :
                        //   Confirme l'emoji direct (UI réactive) ET en parallèle
                        //   force le download local AVEC feedback Toast. Si OK →
                        //   "Avatar enregistré ✓". Si KO → "Échec : besoin
                        //   d'internet pour télécharger". L'user sait si offline-ok.
                        onConfirm(resolved)
                        val dlToast = Toast.makeText(context, "Téléchargement avatar…", Toast.LENGTH_SHORT).also { it.show() }
                        MainScope().launch {
                            try {
                                com.streamflixreborn.streamflix.activities.profile.ProfileEmojiArt
                                    .cacheLocally(context, resolved)
                                val cached = com.streamflixreborn.streamflix.activities.profile.ProfileEmojiArt
                                    .hasLocalCache(context, resolved)
                                dlToast.cancel()
                                Toast.makeText(
                                    context,
                                    if (cached) "Avatar enregistré ✓ (visible hors-ligne)"
                                    else "Avatar enregistré (Note : besoin d'internet — téléchargement local échoué)",
                                    if (cached) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
                                ).show()
                            } catch (e: Exception) {
                                dlToast.cancel()
                                Toast.makeText(context, "Échec téléchargement : ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                } else if (url.isNotEmpty()) {
                    Toast.makeText(
                        context,
                        "L'URL doit commencer par http:// ou https://",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private class Adapter(
        private val emojis: List<String>,
        private val currentEmoji: String?,
        private val onClick: (String) -> Unit,
    ) : RecyclerView.Adapter<Adapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_emoji_picker, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val emoji = emojis[position]
            // 2026-05-20 : rendu Fluent 3D (image) avec fallback emoji systeme.
            ProfileEmojiArt.bind(emoji, holder.image, holder.text)
            holder.itemView.isSelected = emoji == currentEmoji
            holder.itemView.setOnClickListener { onClick(emoji) }
        }

        override fun getItemCount(): Int = emojis.size

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val text: TextView = itemView.findViewById(R.id.tv_emoji)
            val image: ImageView = itemView.findViewById(R.id.iv_emoji)
        }
    }
}
