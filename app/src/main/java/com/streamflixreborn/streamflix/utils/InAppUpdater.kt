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
     * - 2026-10-26 : source principale = dépôt de DISTRIBUTION public warbix-prog/ONYX
     *   (le workflow y copie chaque release, mêmes noms d'APK, APK remplacés à chaque
     *   écrasement). Le dépôt de code reste en REPLI (choix du user) : si le dépôt de
     *   distribution tombe, repasser le dépôt de code en public le temps de publier une
     *   version qui pointe vers la nouvelle page de distribution.
     */
    private data class UpdateSource(
        val owner: String,
        val repo: String,
        val useAuth: Boolean,
        /** true : le tag ne bouge pas à l'écrasement, on se fie à la date des APK. */
        val distribution: Boolean = false,
    )

    private val UPDATE_SOURCES = listOf(
        UpdateSource(owner = "warbix-prog", repo = "ONYX", useAuth = false, distribution = true),
        UpdateSource(owner = "linkinep61", repo = "mobile-client-v2-backup", useAuth = false),
    )

    /** Marge entre notre compilation et la mise en ligne de nos propres APK (build ~15 min). */
    private const val MARGE_PUBLICATION_MS = 45 * 60 * 1000L

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

    private fun versionDe(r: GitHub.Release) = Version(r.tagName.substringAfter("v"))

    /** Releases d'une source, de la plus récente à la plus ancienne PAR NUMÉRO (les dates de
     *  création du dépôt de distribution sont toutes identiques, l'ordre de l'API n'est pas fiable). */
    private suspend fun releasesDe(source: UpdateSource): List<GitHub.Release> {
        val list = if (source.useAuth) {
            GitHub.Releases.getReleases(source.owner, source.repo)
        } else {
            GitHub.Releases.getReleasesPublic(source.owner, source.repo)
        }
        return list.sortedWith { a, b -> versionDe(b).compareTo(versionDe(a)) }
    }

    /** Tente chaque source dans l'ordre ; une source vide ou en erreur passe la main à la suivante. */
    private suspend fun fetchAllWithSource(): Pair<UpdateSource, List<GitHub.Release>>? {
        for (source in UPDATE_SOURCES) {
            try {
                val list = releasesDe(source)
                if (list.isNotEmpty()) {
                    android.util.Log.d("InAppUpdater", "Releases fetched from ${source.owner}/${source.repo}")
                    return source to list
                }
            } catch (e: Exception) {
                android.util.Log.w("InAppUpdater", "Source ${source.owner}/${source.repo} failed: ${e.message}, trying next source...")
            }
        }
        return null
    }

    private suspend fun fetchLatestWithSource(): Pair<UpdateSource, GitHub.Release>? =
        fetchAllWithSource()?.let { (source, list) -> source to list.first() }

    private suspend fun fetchLatestFromAnySource(): GitHub.Release? = fetchLatestWithSource()?.second

    private suspend fun fetchAllReleasesFromAnySource(): List<GitHub.Release> =
        fetchAllWithSource()?.second ?: emptyList()

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
        val (source, release) = fetchLatestWithSource() ?: return@withContext null
        if (Version(release.tagName.substringAfter("v")).compareTo(Version(BuildConfig.VERSION_NAME)) != 0) {
            return@withContext null   // numéro différent : c'est la mise à jour classique qui s'en charge
        }
        if (source.distribution) {
            // 2026-10-26 : dépôt de distribution → le tag ne bouge pas, seuls les APK sont
            //   remplacés. APK mis en ligne bien après notre propre compilation = écrasement.
            val notreHeure = BuildConfig.BUILD_TIME
            if (notreHeure <= 0L) return@withContext null
            val asset = choisirApk(release.assets) ?: return@withContext null
            val dateApk = lireDate(asset.updatedAt) ?: return@withContext null
            if (dateApk <= notreHeure + MARGE_PUBLICATION_MS) return@withContext null
            android.util.Log.i("InAppUpdater", "écrasement en ligne : ${release.tagName} (APK du ${asset.updatedAt})")
            return@withContext VersionEnLigne(release, asset)
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
        // Le tag du dépôt de distribution ne pointe pas sur le commit compilé : ignoré ici.
        for (source in UPDATE_SOURCES.filter { !it.distribution }) {
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
        // 2026-10-19 : sur Android 6, l'installateur lit l'APK par une adresse file:// ; le
        //   cache INTERNE de l'appli lui est fermé → APK dans le cache externe (lisible).
        val dossier = if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N)
            (context.externalCacheDir ?: context.cacheDir) else context.cacheDir
        dossier.listFiles()
            ?.filter { it.extension == "apk" }
            ?.forEach { it.deleteOnExit() }

        val apk = withContext(Dispatchers.IO) {
            File.createTempFile(
                "${File(asset.name).nameWithoutExtension}-",
                ".${File(asset.name).extension}",
                dossier
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

    /**
     * 2026-10-19 (repris du correctif StreamFlix Reborn v1.7.237) :
     *   • Android 6 : son installateur ne sait pas lire une adresse content:// (FileProvider)
     *     → adresse file:// sur un fichier rendu lisible ;
     *   • box / TV : sans le type « APK » explicite, certaines répondent « aucune application
     *     pour ouvrir ce fichier » → setDataAndType + NEW_TASK, et repli sur INSTALL_PACKAGE.
     */
    fun installApk(context: Context, uri: Uri) {
        val type = "application/vnd.android.package-archive"
        val apkFile = File(uri.path!!)
        val apkUri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".provider", apkFile)
        } else {
            apkFile.setReadable(true, false)
            Uri.fromFile(apkFile)
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, type)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
        }
        try {
            context.startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            @Suppress("DEPRECATION")
            context.startActivity(Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(apkUri, type)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            })
        }
    }
}
