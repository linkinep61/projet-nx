package com.streamflixreborn.streamflix.activities.profile

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.R

/**
 * 2026-05-12 : dialog de sélection d'emoji pour l'avatar de profil. Affiche
 * une grille focusable (D-pad friendly TV + tap mobile) des emojis de
 * [ProfileEmojis.list]. Au clic, callback avec l'emoji sélectionné.
 *
 * Utilisé par [ProfilePickerActivity] et [ProfilePickerTvActivity] dans
 * les flows "créer profil" et "changer emoji".
 */
object EmojiPickerDialog {

    fun show(context: Context, currentEmoji: String?, onPick: (String) -> Unit) {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.dialog_emoji_picker, null, false)
        val rv = view.findViewById<RecyclerView>(R.id.rv_emoji_picker)
        rv.layoutManager = GridLayoutManager(context, 6)

        var dialogRef: AlertDialog? = null
        rv.adapter = Adapter(ProfileEmojis.list, currentEmoji) { picked ->
            onPick(picked)
            dialogRef?.dismiss()
        }

        dialogRef = AlertDialog.Builder(context)
            .setTitle("Choisir un avatar")
            .setView(view)
            .setNegativeButton("Annuler", null)
            .create()
        dialogRef.show()

        // Demande le focus initial sur le 1er emoji (D-pad ready).
        rv.post {
            rv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    private class Adapter(
        private val emojis: List<String>,
        private val currentEmoji: String?,
        private val onClick: (String) -> Unit,
    ) : RecyclerView.Adapter<Adapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_emoji_picker, parent, false) as TextView
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val emoji = emojis[position]
            holder.text.text = emoji
            holder.text.isSelected = emoji == currentEmoji
            holder.text.setOnClickListener { onClick(emoji) }
        }

        override fun getItemCount(): Int = emojis.size

        class VH(val text: TextView) : RecyclerView.ViewHolder(text)
    }
}
