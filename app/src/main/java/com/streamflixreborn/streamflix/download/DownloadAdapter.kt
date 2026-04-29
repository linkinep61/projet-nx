package com.streamflixreborn.streamflix.download

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.databinding.ItemDownloadBinding

class DownloadAdapter(
    private val onDelete: (DownloadEntity) -> Unit,
    private val onClick: (DownloadEntity) -> Unit,
    private val onPause: (DownloadEntity) -> Unit = {},
    private val onResume: (DownloadEntity) -> Unit = {},
    private val onRetry: (DownloadEntity) -> Unit = {},
) : ListAdapter<DownloadEntity, DownloadAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDownloadBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemDownloadBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DownloadEntity) {
            binding.tvTitle.text = item.title
            binding.tvSubtitle.text = item.subtitle ?: item.providerName
            binding.tvSubtitle.visibility = if (item.subtitle != null) View.VISIBLE else View.GONE

            // Poster
            if (!item.poster.isNullOrEmpty()) {
                Glide.with(binding.ivPoster)
                    .load(item.poster)
                    .placeholder(R.drawable.glide_fallback_cover)
                    .error(R.drawable.glide_fallback_cover)
                    .centerCrop()
                    .into(binding.ivPoster)
            }

            // Status & progress
            when {
                item.isCompleted -> {
                    binding.tvStatus.text = "Téléchargé"
                    binding.tvStatus.setTextColor(0xFF4CAF50.toInt())
                    binding.progressBar.visibility = View.GONE
                }
                item.isDownloading -> {
                    binding.tvStatus.text = "Téléchargement… ${item.progress}%"
                    binding.tvStatus.setTextColor(0xFFBB86FC.toInt())
                    binding.progressBar.visibility = View.VISIBLE
                    binding.progressBar.progress = item.progress
                }
                item.isPending -> {
                    binding.tvStatus.text = "En attente…"
                    binding.tvStatus.setTextColor(0x99FFFFFF.toInt())
                    binding.progressBar.visibility = View.GONE
                }
                item.isPaused -> {
                    binding.tvStatus.text = "En pause — ${item.progress}%"
                    binding.tvStatus.setTextColor(0xFFFFAB40.toInt())
                    binding.progressBar.visibility = View.VISIBLE
                    binding.progressBar.progress = item.progress
                }
                item.isFailed -> {
                    binding.tvStatus.text = "Échec : ${item.errorMessage ?: "Erreur inconnue"}"
                    binding.tvStatus.setTextColor(0xFFFF5252.toInt())
                    binding.progressBar.visibility = View.GONE
                }
            }

            // Contextual action buttons
            val showCancel = item.isDownloading || item.isPaused || item.isPending
            binding.ivCancel.visibility = if (showCancel) View.VISIBLE else View.GONE
            if (showCancel) {
                binding.ivCancel.setImageResource(R.drawable.ic_delete)
                binding.ivCancel.setOnClickListener { onDelete(item) }
            }

            when {
                item.isDownloading -> {
                    binding.ivAction.setImageResource(R.drawable.ic_pause)
                    binding.ivAction.setOnClickListener { onPause(item) }
                }
                item.isPaused -> {
                    binding.ivAction.setImageResource(R.drawable.ic_resume)
                    binding.ivAction.setOnClickListener { onResume(item) }
                }
                item.isFailed -> {
                    binding.ivAction.setImageResource(R.drawable.ic_refresh)
                    binding.ivAction.setOnClickListener { onRetry(item) }
                }
                item.isCompleted -> {
                    binding.ivAction.setImageResource(R.drawable.ic_delete)
                    binding.ivAction.setOnClickListener { onDelete(item) }
                }
                item.isPending -> {
                    binding.ivAction.setImageResource(R.drawable.ic_delete)
                    binding.ivAction.setOnClickListener { onDelete(item) }
                    // Already have cancel visible, hide the duplicate action button
                    binding.ivAction.visibility = View.GONE
                }
            }

            // Make sure action button is visible for non-pending states
            if (!item.isPending) {
                binding.ivAction.visibility = View.VISIBLE
            }

            // Click on row — play if completed
            binding.root.setOnClickListener {
                if (item.isCompleted) onClick(item)
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<DownloadEntity>() {
            override fun areItemsTheSame(old: DownloadEntity, new: DownloadEntity) = old.id == new.id
            override fun areContentsTheSame(old: DownloadEntity, new: DownloadEntity) =
                old.status == new.status &&
                        old.downloadedBytes == new.downloadedBytes &&
                        old.totalBytes == new.totalBytes
        }
    }
}
