package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.streamflixreborn.streamflix.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max

object InAppUpdater {

    /**
     * 2026-05-03 : sources updates avec fallback automatique.
     * L'app tente chaque source dans l'ordre. Si la 1ère échoue (404 = repo
     * supprimé, ou erreur réseau), elle passe à la suivante sans bloquer
     * l'utilisateur.
     *
     * - Source #0 (primaire) : Xx-nanico-xX/mobile-client-v2 — repo public
     *   officiel, accédé AVEC le token read-only embarqué dans l'app
     *   (rate limit 5000 req/h, scope verrouillé sur ce repo).
     *
     * - Source #1 (backup) : Logami61/mobile-client-v2-backup — accédé SANS
     *   token (le token de l'app n'a pas le scope sur ce repo). Pour que ce
     *   fallback marche en production, ce repo backup doit être PUBLIC
     *   (il peut rester privé tant que la source #0 fonctionne).
     */
    private data class UpdateSource(
        val owner: String,
        val repo: String,
        val useAuth: Boolean,
    )

    private val UPDATE_SOURCES = listOf(
        UpdateSource(owner = "Xx-nanico-xX", repo = "mobile-client-v2", useAuth = true),
        UpdateSource(owner = "linkinep61", repo = "mobile-client-v2-backup", useAuth = false),
    )

    private data class Version(val name: String) : Comparable<Version> {
        override operator fun compareTo(other: Version): Int {
            val thisParts = this.name.split(".").toTypedArray()
            val thatParts = other.name.split(".").toTypedArray()
            for (i in 0 until max(thisParts.size, thatParts.size)) {
                val thisPart = thisParts.getOrNull(i)?.toIntOrNull() ?: 0
                val thatPart = thatParts.getOrNull(i)?.toIntOrNull() ?: 0
                if (thisPart < thatPart) return -1
                if (thisPart > thatPart) return 1
            }
            return 0
        }
    }

    /** Tente chaque source dans l'ordre, retourne la 1ère qui répond OK. */
    private suspend fun fetchLatestFromAnySource(): GitHub.Release? {
        for (source in UPDATE_SOURCES) {
            try {
                val release = if (source.useAuth) {
                    GitHub.Releases.getLatestRelease(source.owner, source.repo)
                } else {
                    GitHub.Releases.getLatestReleasePublic(source.owner, source.repo)
                }
                android.util.Log.d("InAppUpdater", "Latest release fetched from ${source.owner}/${source.repo}")
                return release
            } catch (e: Exception) {
                android.util.Log.w("InAppUpdater", "Source ${source.owner}/${source.repo} failed: ${e.message}, trying next source...")
            }
        }
        return null
    }

    private suspend fun fetchAllReleasesFromAnySource(): List<GitHub.Release> {
        for (source in UPDATE_SOURCES) {
            try {
                val list = if (source.useAuth) {
                    GitHub.Releases.getReleases(source.owner, source.repo)
                } else {
                    GitHub.Releases.getReleasesPublic(source.owner, source.repo)
                }
                android.util.Log.d("InAppUpdater", "Releases fetched from ${source.owner}/${source.repo}")
                return list
            } catch (e: Exception) {
                android.util.Log.w("InAppUpdater", "Source ${source.owner}/${source.repo} failed: ${e.message}, trying next source...")
            }
        }
        return emptyList()
    }

    suspend fun getReleaseUpdate(): GitHub.Release? {
        if (BuildConfig.DEBUG) return null

        val latestRelease = fetchLatestFromAnySource() ?: return null
        val currentVersion = BuildConfig.VERSION_NAME

        if (Version(latestRelease.tagName.substringAfter("v")) > Version(currentVersion)) {
            return latestRelease
        }
        return null
    }

    suspend fun getNewReleases(): List<GitHub.Release> {
        if (BuildConfig.DEBUG) return emptyList()

        val releases = fetchAllReleasesFromAnySource()
        val currentVersion = BuildConfig.VERSION_NAME

        val newReleases = releases
            .filter { Version(it.tagName.substringAfter("v")) > Version(currentVersion) }

        return newReleases
    }

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun downloadApk(context: Context, asset: GitHub.Release.Asset): File {
        context.cacheDir.listFiles()
            ?.filter { it.extension == "apk" }
            ?.forEach { it.deleteOnExit() }

        val apk = withContext(Dispatchers.IO) {
            File.createTempFile(
                "${File(asset.name).nameWithoutExtension}-",
                ".${File(asset.name).extension}",
                context.cacheDir
            )
        }

        withContext(Dispatchers.IO) {
            // 2026-05-03 : utilise browser_download_url (URL publique directe)
            // au lieu de asset.url (API GitHub avec Accept octet-stream + Bearer
            // token). Avantage : marche pour le repo principal Xx-nanico-xX ET
            // pour le fallback Logami61 sans dépendre du scope du token. Tant
            // que le repo source est public, le download passe.
            val request = Request.Builder()
                .url(asset.browserDownloadUrl)
                .build()

            val response = downloadClient.newCall(request).execute()
            if (!response.isSuccessful) {
                throw Exception("Download failed: HTTP ${response.code}")
            }

            response.body?.byteStream()?.use { input ->
                FileOutputStream(apk).use { output -> input.copyTo(output) }
            } ?: throw Exception("Download failed: empty response body")
        }

        return apk
    }

    fun installApk(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).also { intent ->
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            intent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            intent.data = FileProvider.getUriForFile(
                context,
                BuildConfig.APPLICATION_ID + ".provider",
                File(uri.path!!)
            )
        }
        context.startActivity(intent)
    }
}
