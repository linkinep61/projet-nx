package com.streamflixreborn.streamflix.download

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.databinding.ItemDownloadTvBinding

class DownloadAdapterTv(
    private val onDelete: (DownloadEntity) -> Unit,
    private val onClick: (DownloadEntity) -> Unit,
    private val onPause: (DownloadEntity) -> Unit = {},
    private val onResume: (DownloadEntity) -> Unit = {},
    private val onRetry: (DownloadEntity) -> Unit = {},
) : ListAdapter<DownloadEntity, DownloadAdapterTv.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDownloadTvBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemDownloadTvBinding,
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

            // Action button (primary: pause/resume/retry/play)
            when {
                item.isDownloading -> {
                    binding.btnAction.setImageResource(R.drawable.ic_pause)
                    binding.btnAction.setOnClickListener { onPause(item) }
                    binding.btnAction.visibility = View.VISIBLE
                }
                item.isPaused -> {
                    binding.btnAction.setImageResource(R.drawable.ic_resume)
                    binding.btnAction.setOnClickListener { onResume(item) }
                    binding.btnAction.visibility = View.VISIBLE
                }
                item.isFailed -> {
                    binding.btnAction.setImageResource(R.drawable.ic_refresh)
                    binding.btnAction.setOnClickListener { onRetry(item) }
                    binding.btnAction.visibility = View.VISIBLE
                }
                item.isCompleted -> {
                    binding.btnAction.setImageResource(R.drawable.ic_resume)
                    binding.btnAction.setOnClickListener { onClick(item) }
                    binding.btnAction.visibility = View.VISIBLE
                }
                item.isPending -> {
                    binding.btnAction.visibility = View.GONE
                }
            }

            // Delete/cancel button
            val showDelete = item.isDownloading || item.isPaused || item.isPending || item.isCompleted || item.isFailed
            binding.btnDelete.visibility = if (showDelete) View.VISIBLE else View.GONE
            binding.btnDelete.setImageResource(R.drawable.ic_delete)
            binding.btnDelete.setOnClickListener { onDelete(item) }

            // Row click — play if completed
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
