package com.streamflixreborn.streamflix.fragments.downloads

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.streamflixreborn.streamflix.databinding.FragmentDownloadsTvBinding
import com.streamflixreborn.streamflix.download.DownloadAdapterTv
import com.streamflixreborn.streamflix.download.DownloadEntity
import com.streamflixreborn.streamflix.download.DownloadManager
import kotlinx.coroutines.launch
import java.io.File

class DownloadsTvFragment : Fragment() {

    private var _binding: FragmentDownloadsTvBinding? = null
    private val binding get() = _binding!!

    private val adapter = DownloadAdapterTv(
        onDelete = { download -> deleteDownload(download) },
        onClick = { download -> playDownload(download) },
        onPause = { download -> pauseDownload(download) },
        onResume = { download -> resumeDownload(download) },
        onRetry = { download -> retryDownload(download) },
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentDownloadsTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rvDownloads.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            DownloadManager.getAllDownloads()
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect { downloads ->
                    adapter.submitList(downloads)

                    if (downloads.isEmpty()) {
                        binding.layoutEmpty.visibility = View.VISIBLE
                        binding.rvDownloads.visibility = View.GONE
                    } else {
                        binding.layoutEmpty.visibility = View.GONE
                        binding.rvDownloads.visibility = View.VISIBLE
                    }
                }
        }
    }

    private fun deleteDownload(download: DownloadEntity) {
        viewLifecycleOwner.lifecycleScope.launch {
            if (download.isCompleted) {
                DownloadManager.deleteCompleted(download.id)
            } else {
                DownloadManager.cancel(download.id)
            }
        }
    }

    private fun pauseDownload(download: DownloadEntity) {
        viewLifecycleOwner.lifecycleScope.launch {
            DownloadManager.pause(download.id)
        }
    }

    private fun resumeDownload(download: DownloadEntity) {
        viewLifecycleOwner.lifecycleScope.launch {
            DownloadManager.resume(download.id)
        }
    }

    private fun retryDownload(download: DownloadEntity) {
        viewLifecycleOwner.lifecycleScope.launch {
            DownloadManager.retry(download.id)
        }
    }

    private fun playDownload(download: DownloadEntity) {
        val available = if (download.filePath.startsWith("content://")) {
            runCatching {
                requireContext().contentResolver.openInputStream(android.net.Uri.parse(download.filePath))?.use { true } ?: false
            }.getOrDefault(false)
        } else {
            File(download.filePath).exists()
        }

        if (!available) {
            Toast.makeText(requireContext(), "Fichier introuvable", Toast.LENGTH_SHORT).show()
            viewLifecycleOwner.lifecycleScope.launch {
                DownloadManager.deleteCompleted(download.id)
            }
            return
        }

        com.streamflixreborn.streamflix.activities.tools.LocalPlayerActivity.start(
            context = requireContext(),
            filePath = download.filePath,
            title = download.title,
            subtitle = download.subtitle,
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
