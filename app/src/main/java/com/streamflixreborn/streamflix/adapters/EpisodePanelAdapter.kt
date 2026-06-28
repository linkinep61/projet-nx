package com.streamflixreborn.streamflix.adapters

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.models.Video

/**
 * 2026-06-20 (user "comme sur l'image, panel épisodes droite avec cards riches") :
 * Adapter du panneau épisodes (side-right). Affiche des cards riches (poster +
 * titre + meta + extrait synopsis), avec l'épisode courant surligné en rouge.
 *
 * @param episodes  liste à afficher (= filtrée par saison courante)
 * @param currentId id de l'épisode en lecture (= highlight rouge)
 * @param onClick   callback au clic sur un épisode
 */
class EpisodePanelAdapter(
    private var episodes: List<Video.Type.Episode>,
    private var currentId: String?,
    private val onClick: (Video.Type.Episode) -> Unit,
) : RecyclerView.Adapter<EpisodePanelAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val card: CardView = view.findViewById(R.id.cv_episode_card)
        val poster: ImageView = view.findViewById(R.id.iv_episode_poster)
        val title: TextView = view.findViewById(R.id.tv_episode_title)
        val meta: TextView = view.findViewById(R.id.tv_episode_meta)
        val overview: TextView = view.findViewById(R.id.tv_episode_overview)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_episode_panel, parent, false)
        return VH(view)
    }

    override fun getItemCount() = episodes.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ep = episodes[position]
        val ctx = holder.itemView.context

        // Title
        val displayTitle = ep.title?.takeIf { it.isNotBlank() }
            ?: "Épisode ${ep.number}"
        holder.title.text = displayTitle

        // Meta : "S{X}E{Y}" (+ titre saison si dispo et différent)
        val seasonNum = ep.season.number
        val sStr = seasonNum.toString().padStart(2, '0')
        val eStr = ep.number.toString().padStart(2, '0')
        val metaText = buildString {
            append("S")
            append(sStr)
            append("E")
            append(eStr)
            val seasonTitle = ep.season.title?.takeIf { it.isNotBlank() }
            if (!seasonTitle.isNullOrBlank() && seasonTitle != "Saison $seasonNum") {
                append(" · ")
                append(seasonTitle)
            }
        }
        holder.meta.text = metaText

        // Overview
        val overviewText = ep.overview?.takeIf { it.isNotBlank() }
        if (overviewText != null) {
            holder.overview.visibility = View.VISIBLE
            holder.overview.text = overviewText
        } else {
            holder.overview.visibility = View.GONE
        }

        // Poster
        val posterUrl = ep.poster ?: ep.tvShow.poster
        if (!posterUrl.isNullOrBlank()) {
            Glide.with(ctx)
                .load(posterUrl)
                .placeholder(android.R.drawable.ic_menu_gallery)
                .error(android.R.drawable.ic_menu_gallery)
                .centerCrop()
                .into(holder.poster)
        } else {
            holder.poster.setImageResource(android.R.drawable.ic_menu_gallery)
        }

        // Highlight rouge si épisode courant
        val isCurrent = currentId != null && ep.id == currentId
        val normalColor = if (isCurrent) Color.parseColor("#CC8B0000") // rouge sombre
            else Color.parseColor("#22FFFFFF")             // gris foncé semi-transparent
        val focusedColor = if (isCurrent) Color.parseColor("#FFB71C1C") // rouge vif (current + focus)
            else Color.parseColor("#66FFFFFF")             // gris clair (focus seul)
        holder.card.setCardBackgroundColor(normalColor)
        // 2026-06-21 (user "on voit pas le focus bouger sur la TV") :
        //   highlight visuel quand la carte reçoit le focus D-pad. Indispensable
        //   sur grand écran TV où sans contour le user pense que la nav D-pad
        //   ne marche pas alors qu'elle marche.
        holder.card.setOnFocusChangeListener { _, hasFocus ->
            holder.card.setCardBackgroundColor(if (hasFocus) focusedColor else normalColor)
            holder.card.cardElevation = if (hasFocus) 12f else 0f
            // Léger scale pour effet "lift" comme les tiles Netflix-like
            val scale = if (hasFocus) 1.04f else 1.0f
            holder.card.animate()
                .scaleX(scale).scaleY(scale)
                .setDuration(120L)
                .start()
        }
        // Indicateur ▶ devant le titre si courant
        holder.title.text = if (isCurrent) "▶ $displayTitle" else displayTitle

        holder.card.setOnClickListener { onClick(ep) }
    }

    fun updateEpisodes(newEpisodes: List<Video.Type.Episode>, newCurrentId: String?) {
        episodes = newEpisodes
        currentId = newCurrentId
        notifyDataSetChanged()
    }

    fun updateCurrentId(newCurrentId: String?) {
        currentId = newCurrentId
        notifyDataSetChanged()
    }
}
