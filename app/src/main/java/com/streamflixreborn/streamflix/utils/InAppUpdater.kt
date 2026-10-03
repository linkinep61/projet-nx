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
     * - 2026-09-20 : MESURÉ — le jeton embarqué dans GitHub.kt était RÉVOQUÉ
     *   (401 sur /user comme sur le dépôt). La source privée
     *   Xx-nanico-xX/mobile-client-v2, qui exigeait ce jeton, ne pouvait donc
     *   plus aboutir : c'est la source PUBLIQUE qui servait déjà réellement
     *   toutes les mises à jour. Sur décision du user, on ne garde que celle-ci
     *   et le jeton mort a été retiré de l'app.
     *
     * - Source unique : linkinep61/mobile-client-v2-backup — accédée SANS
     *   authentification. Ce dépôt DOIT rester PUBLIC : s'il passe en privé,
     *   plus aucune mise à jour n'atteint le parc, et il n'y a plus de repli.
     */
    private data class UpdateSource(
        val owner: String,
        val repo: String,
        val useAuth: Boolean,
    )

    private val UPDATE_SOURCES = listOf(
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

    /**
     * 2026-10-03 (user : « il y a pas un moyen de signaler qu'il y a eu un écrasement ») :
     *   l'APK porte le commit compilé (BuildConfig.BUILD_SHA, fourni par GitHub Actions). Un
     *   écrasement déplace le tag de la release sur un autre commit : même numéro de version,
     *   commit différent → une version corrigée est en ligne. On attend que les APK de ce nouveau
     *   commit soient publiés (fichiers plus récents que le commit), sinon on ne signale rien.
     */
    data class VersionEnLigne(val release: GitHub.Release, val asset: GitHub.Release.Asset)

    suspend fun ecrasementDisponible(): VersionEnLigne? = withContext(Dispatchers.IO) {
        val notreSha = BuildConfig.BUILD_SHA
        if (BuildConfig.DEBUG || notreSha.isBlank()) return@withContext null
        val release = fetchLatestFromAnySource() ?: return@withContext null
        if (Version(release.tagName.substringAfter("v")).compareTo(Version(BuildConfig.VERSION_NAME)) != 0) {
            return@withContext null   // numéro différent : c'est la mise à jour classique qui s'en charge
        }
        val (sha, dateCommit) = commitDuTag(release.tagName) ?: return@withContext null
        if (sha.equals(notreSha, ignoreCase = true)) return@withContext null
        val asset = choisirApk(release.assets) ?: return@withContext null
        val dateApk = lireDate(asset.updatedAt) ?: return@withContext null
        if (dateCommit != null && dateApk < dateCommit) return@withContext null   // compilation en cours
        android.util.Log.i("InAppUpdater", "écrasement en ligne : ${release.tagName} ${sha.take(8)} (installé ${notreSha.take(8)})")
        VersionEnLigne(release, asset)
    }

    /** Dernière release publiée + l'APK adapté à l'appareil (bouton « Télécharger la dernière version »). */
    suspend fun derniereVersion(): VersionEnLigne? = withContext(Dispatchers.IO) {
        val release = fetchLatestFromAnySource() ?: return@withContext null
        val asset = choisirApk(release.assets) ?: return@withContext null
        VersionEnLigne(release, asset)
    }

    /** Même choix d'APK que la mise à jour classique (MainViewModel) : x86, TV ou universel. */
    fun choisirApk(assets: List<GitHub.Release.Asset>): GitHub.Release.Asset? {
        val apks = assets.filter {
            it.contentType == "application/vnd.android.package-archive" || it.name.endsWith(".apk", ignoreCase = true)
        }
        val x86 = android.os.Build.SUPPORTED_ABIS.firstOrNull()?.let {
            it.equals("x86", ignoreCase = true) || it.equals("x86_64", ignoreCase = true)
        } == true
        return when {
            x86 -> apks.firstOrNull { it.name.endsWith("-x86.apk", ignoreCase = true) }
            BuildConfig.APP_LAYOUT == "tv" -> apks.firstOrNull { it.name.endsWith("-tv.apk", ignoreCase = true) }
            else -> apks.firstOrNull { it.name.endsWith("-universal.apk", ignoreCase = true) }
        } ?: apks.firstOrNull { !it.name.endsWith("-x86.apk", ignoreCase = true) }
    }

    /** Commit (sha, date) sur lequel pointe le tag, via l'API GitHub publique. */
    private fun commitDuTag(tag: String): Pair<String, Long?>? {
        for (source in UPDATE_SOURCES) {
            try {
                val req = Request.Builder()
                    .url("https://api.github.com/repos/${source.owner}/${source.repo}/commits/$tag")
                    .header("Accept", "application/vnd.github+json")
                    .build()
                downloadClient.newCall(req).execute().use { r ->
                    if (r.isSuccessful) {
                        val o = org.json.JSONObject(r.body?.string().orEmpty())
                        val sha = o.optString("sha")
                        if (sha.isNotBlank()) {
                            val date = lireDate(o.optJSONObject("commit")?.optJSONObject("committer")?.optString("date"))
                            return sha to date
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("InAppUpdater", "commit du tag $tag illisible : ${e.message}")
            }
        }
        return null
    }

    private fun lireDate(s: String?): Long? = try {
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.parse(s.orEmpty())?.time
    } catch (_: Exception) { null }

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.MINUTES)
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
