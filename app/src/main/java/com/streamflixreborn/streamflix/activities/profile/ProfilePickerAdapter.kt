package com.streamflixreborn.streamflix.activities.profile

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
            holder.emoji.text = profile.emoji
            holder.name.text = profile.name
            holder.lock.visibility = if (profile.pinHash != null) View.VISIBLE else View.GONE
            holder.itemView.setOnClickListener { onProfileClick(profile) }
        } else {
            holder.emoji.text = "+"
            holder.name.text = "Ajouter"
            holder.lock.visibility = View.GONE
            holder.itemView.setOnClickListener { onAddProfileClick() }
        }
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val emoji: TextView = itemView.findViewById(R.id.tv_profile_emoji)
        val name: TextView = itemView.findViewById(R.id.tv_profile_name)
        val lock: ImageView = itemView.findViewById(R.id.iv_profile_lock)
    }
}
