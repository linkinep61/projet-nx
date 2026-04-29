package com.streamflixreborn.streamflix.download

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.streamflixreborn.streamflix.R
import kotlinx.coroutines.launch
import java.io.File

/**
 * Bottom sheet overlay showing downloads list.
 * Can be displayed on top of the player without interrupting playback.
 */
class DownloadsBottomSheet : BottomSheetDialogFragment() {

    private val adapter = DownloadAdapter(
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
        return inflater.inflate(R.layout.bottom_sheet_downloads, container, false)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                // Take up ~60% of screen height
                val displayHeight = resources.displayMetrics.heightPixels
                behavior.peekHeight = (displayHeight * 0.6).toInt()
                it.setBackgroundColor(0x00000000) // transparent, layout has its own bg
            }
        }
        return dialog
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rvDownloads = view.findViewById<RecyclerView>(R.id.rv_downloads)
        val tvEmpty = view.findViewById<View>(R.id.tv_empty)

        rvDownloads.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            DownloadManager.getAllDownloads()
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect { downloads ->
                    adapter.submitList(downloads)
                    if (downloads.isEmpty()) {
                        tvEmpty.visibility = View.VISIBLE
                        rvDownloads.visibility = View.GONE
                    } else {
                        tvEmpty.visibility = View.GONE
                        rvDownloads.visibility = View.VISIBLE
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
        val file = File(download.filePath)
        if (!file.exists()) {
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
        dismiss()
    }

    companion object {
        const val TAG = "DownloadsBottomSheet"
    }
}
