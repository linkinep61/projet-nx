package com.streamflixreborn.streamflix.utils

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * 2026-10-29 (user : « SendVid, on peut quasiment pas lire un épisode sans coupure ») :
 * le lien SendVid porte `rate=250k` (signé, non modifiable) → ~250 Ko/s PAR CONNEXION, alors que
 * l'épisode en consomme ~625 Ko/s. Mesuré dans Chrome : 4 connexions en parallèle = ~400 Ko/s
 * CHACUNE. On télécharge donc les morceaux suivants du fichier sur [PARALLELE] connexions à la
 * fois (requêtes Range), et on les rend au lecteur dans l'ordre.
 */
@UnstableApi
class ParallelRangeDataSource(
    private val client: OkHttpClient,
    private val entetes: Map<String, String>,
) : BaseDataSource(true), HttpDataSource {

    companion object {
        private const val TAG = "ParallelRange"
        private const val PREMIER = 256 * 1024L      // petit premier morceau : démarrage rapide
        private const val MORCEAU = 1024 * 1024L
        private const val PARALLELE = 4
    }

    // Recréé à chaque open() : le lecteur réutilise le même objet après close().
    private var pool: ExecutorService? = null
    private var transfertDemarre = false
    private val enCours = ArrayDeque<Future<ByteArray>>()
    private var uri: Uri? = null
    private var url = ""
    private var prochain = 0L          // début du prochain morceau à demander
    private var fin = C.LENGTH_UNSET.toLong()   // position de fin (exclue), connue après le 1er morceau
    private var courant: ByteArray = ByteArray(0)
    private var lu = 0
    private var restant = C.LENGTH_UNSET.toLong()
    private val proprietes = HashMap<String, String>()
    private var codeReponse = -1
    private var entetesReponse: Map<String, List<String>> = emptyMap()

    private fun telecharger(debut: Long, taille: Long): ByteArray {
        val finMorceau = debut + taille - 1
        val req = Request.Builder().url(url).apply {
            header("User-Agent", NetworkClient.USER_AGENT)
            (entetes + proprietes).forEach { (k, v) -> header(k, v) }
            header("Range", "bytes=$debut-$finMorceau")
        }.build()
        client.newCall(req).execute().use { r ->
            if (r.code != 206 && r.code != 200) throw HttpDataSource.InvalidResponseCodeException(
                r.code, r.message, null, r.headers.toMultimap(), DataSpec(Uri.parse(url)), ByteArray(0))
            if (fin == C.LENGTH_UNSET.toLong()) {
                r.header("Content-Range")?.substringAfter('/')?.toLongOrNull()?.let { fin = it }
            }
            codeReponse = r.code
            entetesReponse = r.headers.toMultimap()
            return r.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun planifier() {
        while (enCours.size < PARALLELE && (fin == C.LENGTH_UNSET.toLong() || prochain < fin)) {
            val debut = prochain
            val taille = if (fin == C.LENGTH_UNSET.toLong()) MORCEAU else minOf(MORCEAU, fin - debut)
            prochain += taille
            enCours.addLast(pool!!.submit(Callable { telecharger(debut, taille) }))
        }
    }

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        url = dataSpec.uri.toString()
        fin = C.LENGTH_UNSET.toLong()
        enCours.clear()
        pool = Executors.newFixedThreadPool(PARALLELE)
        transferInitializing(dataSpec)
        try {
            // 1er morceau seul et petit : il donne la taille du fichier et démarre vite.
            courant = telecharger(dataSpec.position, PREMIER)
            lu = 0
            prochain = dataSpec.position + courant.size
        } catch (e: Exception) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e as? IOException ?: IOException(e), dataSpec, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
        }
        val total = if (fin != C.LENGTH_UNSET.toLong()) fin - dataSpec.position else C.LENGTH_UNSET.toLong()
        restant = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else total
        if (dataSpec.length != C.LENGTH_UNSET.toLong()) fin = minOf(
            if (fin == C.LENGTH_UNSET.toLong()) Long.MAX_VALUE else fin, dataSpec.position + dataSpec.length)
        planifier()
        Log.d(TAG, "ouvert à ${dataSpec.position} (fichier ${fin} o) — $PARALLELE connexions")
        transferStarted(dataSpec)
        transfertDemarre = true
        return restant
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (restant == 0L) return C.RESULT_END_OF_INPUT
        if (lu >= courant.size) {
            val f = enCours.removeFirstOrNull() ?: return C.RESULT_END_OF_INPUT
            courant = try { f.get() } catch (e: Exception) {
                throw HttpDataSource.HttpDataSourceException.createForIOException(
                    (e.cause as? IOException) ?: IOException(e), DataSpec(uri ?: Uri.EMPTY),
                    HttpDataSource.HttpDataSourceException.TYPE_READ)
            }
            lu = 0
            planifier()
            if (courant.isEmpty()) return C.RESULT_END_OF_INPUT
        }
        var n = minOf(length, courant.size - lu)
        if (restant != C.LENGTH_UNSET.toLong()) n = minOf(n.toLong(), restant).toInt()
        System.arraycopy(courant, lu, buffer, offset, n)
        lu += n
        if (restant != C.LENGTH_UNSET.toLong()) restant -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        enCours.forEach { it.cancel(true) }
        enCours.clear()
        pool?.shutdownNow()
        pool = null
        uri = null
        courant = ByteArray(0); lu = 0
        // N'annonce la fin que d'un transfert réellement démarré (sinon NPE dans BaseDataSource).
        if (transfertDemarre) { transfertDemarre = false; transferEnded() }
    }

    override fun setRequestProperty(name: String, value: String) { proprietes[name] = value }
    override fun clearRequestProperty(name: String) { proprietes.remove(name) }
    override fun clearAllRequestProperties() { proprietes.clear() }
    override fun getResponseCode(): Int = codeReponse
    override fun getResponseHeaders(): Map<String, List<String>> = entetesReponse

    class Factory(private val client: OkHttpClient) : HttpDataSource.Factory {
        private val defauts = HashMap<String, String>()
        override fun setDefaultRequestProperties(props: Map<String, String>): HttpDataSource.Factory {
            defauts.clear(); defauts.putAll(props); return this
        }
        override fun createDataSource(): HttpDataSource = ParallelRangeDataSource(client, HashMap(defauts))
    }
}
