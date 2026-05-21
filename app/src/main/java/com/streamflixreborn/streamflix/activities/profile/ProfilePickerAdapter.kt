package com.streamflixreborn.streamflix.activities.profile

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.models.Profile

/**
 * 2026-05-12 : adapter pour le grid de l'écran "Qui regarde ?". Affiche les
 * profils existants + 1 carte "+ Ajouter" en fin de liste.
 */
class ProfilePickerAdapter(
    private val profiles: List<Profile>,
    private val onProfileClick: (Profile) -> Unit,
    private val onAddProfileClick: () -> Unit,
    /** Layout d'item à inflater. Permet de réutiliser le même adapter pour
     *  mobile (item_profile_mobile.xml) et TV (item_profile_tv.xml). */
    private val itemLayoutRes: Int = R.layout.item_profile_mobile,
) : RecyclerView.Adapter<ProfilePickerAdapter.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_PROFILE = 0
        private const val VIEW_TYPE_ADD = 1
        /** Limite arbitraire pour ne pas dépasser un écran (Netflix en autorise 5). */
        const val MAX_PROFILES = 5

        /** 2026-05-13 (user "ça serait plus joli") : palette d'avatars cyclée
         *  par index de profil. 5 gradients vifs distincts. */
        private val AVATAR_BACKGROUNDS = intArrayOf(
            R.drawable.bg_profile_avatar_c1, // rose/rouge
            R.drawable.bg_profile_avatar_c2, // violet/bleu
            R.drawable.bg_profile_avatar_c3, // cyan/teal
            R.drawable.bg_profile_avatar_c4, // vert
            R.drawable.bg_profile_avatar_c5, // ambre/orange
        )
    }

    override fun getItemViewType(position: Int): Int {
        return if (position < profiles.size) VIEW_TYPE_PROFILE else VIEW_TYPE_ADD
    }

    override fun getItemCount(): Int {
        // Affiche carte "+" seulement si on n'a pas atteint le max.
        return profiles.size + if (profiles.size < MAX_PROFILES) 1 else 0
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(itemLayoutRes, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        if (position < profiles.size) {
            val profile = profiles[position]
            // 2026-05-20 : avatar Fluent 3D (image) avec fallback emoji systeme.
            ProfileEmojiArt.bind(profile.emoji, holder.emojiImage, holder.emoji)
            holder.name.text = profile.name
            holder.lock.visibility = if (profile.pinHash != null) View.VISIBLE else View.GONE
            holder.itemView.setOnClickListener { onProfileClick(profile) }
            // 2026-05-13 (user "voir à travers pour voir mon fond d'écran") :
            // tuile transparente, le wallpaper du home reste visible derrière.
            holder.avatar?.setBackgroundResource(android.R.color.transparent)
        } else {
            // Carte "+ Ajouter" : pas d'image Fluent, juste le "+" en TextView.
            holder.emojiImage?.visibility = View.GONE
            holder.emoji.visibility = View.VISIBLE
            holder.emoji.text = "+"
            holder.name.text = "Ajouter"
            holder.lock.visibility = View.GONE
            holder.itemView.setOnClickListener { onAddProfileClick() }
            // Carte "+ Ajouter" : juste la bordure pointillée pour la situer.
            holder.avatar?.setBackgroundResource(R.drawable.bg_profile_avatar_add)
        }
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val emoji: TextView = itemView.findViewById(R.id.tv_profile_emoji)
        // 2026-05-20 : image Fluent 3D (nullable pour compat anciens layouts).
        val emojiImage: ImageView? = itemView.findViewById(R.id.iv_profile_emoji)
        val name: TextView = itemView.findViewById(R.id.tv_profile_name)
        val lock: ImageView = itemView.findViewById(R.id.iv_profile_lock)
        // Nullable car l'ID a été ajouté côté layout 2026-05-13 ; safety pour
        // les anciens layouts qui ne l'auraient pas (au cas où).
        val avatar: FrameLayout? = itemView.findViewById(R.id.fl_profile_avatar)
    }
}
