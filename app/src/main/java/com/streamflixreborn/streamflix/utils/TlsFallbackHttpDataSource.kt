package com.streamflixreborn.streamflix.utils

import android.net.Uri
import android.util.Log
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.util.concurrent.ConcurrentHashMap

/**
 * 2026-09-18 — REPLI TLS AUTOMATIQUE POUR LE LECTEUR.
 *
 * Constat (TCL Smart TV Pro, Android 14 armeabi-v7a) : les serveurs Cloudstream
 * étaient rouges sur la TV alors qu'ils fonctionnaient sur l'Oppo ET sur le Honor,
 * sur le MÊME réseau et avec le MÊME APK. Le journal donnait :
 *
 *     javax.net.ssl.SSLHandshakeException
 *     SSLV3_ALERT_HANDSHAKE_FAILURE
 *     HANDSHAKE_FAILURE_ON_CLIENT_HELLO
 *
 * Autrement dit le CDN REFUSE le ClientHello : aucune version de protocole ni
 * aucune suite de chiffrement en commun. L'API, elle, répondait très bien — les
 * serveurs étaient listés. La différence : l'API passe par OkHttp (donc par le
 * Conscrypt que l'app installe en provider 1 au démarrage), alors que la LECTURE
 * passe par DefaultHttpDataSource → HttpURLConnection → la pile TLS DU SYSTÈME,
 * celle du téléviseur, trop vieille pour ce CDN.
 *
 * Ce wrapper ne change aucun aiguillage existant : il laisse la voie normale
 * tenter sa chance, et ne fait quelque chose QUE là où il y avait déjà un échec
 * franc. Sur poignée de main refusée, il rejoue la requête via OkHttp/Conscrypt
 * et retient l'hôte, pour que les segments suivants n'aient pas à repayer le
 * handshake perdu. Volontairement général : le problème n'est pas propre à
 * Cloudstream, il touchera tout CDN qui durcit sa configuration TLS, sur tous
 * les appareils anciens.
 */
class TlsFallbackHttpDataSource(
    private val primary: HttpDataSource,
    private val fallbackFactory: HttpDataSource.Factory,
) : HttpDataSource by primary {

    /** Source réellement utilisée : `primary`, ou le repli OkHttp après échec TLS. */
    private var active: HttpDataSource = primary

    /** Rejoués sur le repli : il est créé après coup, il n'a rien vu passer. */
    private val listeners = mutableListOf<TransferListener>()
    private val proprietes = linkedMapOf<String, String>()

    override fun open(dataSpec: DataSpec): Long {
        val hote = dataSpec.uri.host

        // Hôte déjà connu pour refuser la pile système : on n'essaie même pas.
        if (hote != null && hotesSansTlsSysteme.containsKey(hote)) {
            active = creerRepli()
            return active.open(dataSpec)
        }

        active = primary
        return try {
            primary.open(dataSpec)
        } catch (e: Throwable) {
            if (!estEchecHandshake(e)) throw e
            if (hote != null) hotesSansTlsSysteme[hote] = true
            Log.w(
                TAG,
                "Poignée de main TLS refusée par $hote via la pile système → repli OkHttp/Conscrypt (${e.message})",
            )
            runCatching { primary.close() }
            active = creerRepli()
            active.open(dataSpec)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active.read(buffer, offset, length)

    override fun close() {
        runCatching { active.close() }
        if (active !== primary) runCatching { primary.close() }
    }

    override fun getUri(): Uri? = active.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active.responseHeaders

    override fun addTransferListener(transferListener: TransferListener) {
        listeners.add(transferListener)
        primary.addTransferListener(transferListener)
    }

    override fun setRequestProperty(name: String, value: String) {
        proprietes[name] = value
        active.setRequestProperty(name, value)
    }

    override fun clearRequestProperty(name: String) {
        proprietes.remove(name)
        active.clearRequestProperty(name)
    }

    override fun clearAllRequestProperties() {
        proprietes.clear()
        active.clearAllRequestProperties()
    }

    /** Crée le repli et lui rejoue écouteurs + en-têtes déjà posés sur la voie normale. */
    private fun creerRepli(): HttpDataSource {
        val repli = fallbackFactory.createDataSource()
        listeners.forEach { repli.addTransferListener(it) }
        proprietes.forEach { (nom, valeur) -> repli.setRequestProperty(nom, valeur) }
        return repli
    }

    companion object {
        private const val TAG = "PlayerNetwork"

        /**
         * Hôtes dont on a constaté qu'ils refusent la pile TLS du système sur CET
         * appareil. Mémoire de session : un flux DASH/HLS tire des dizaines de
         * segments, inutile de repayer un handshake perdu à chaque fois.
         */
        private val hotesSansTlsSysteme = ConcurrentHashMap<String, Boolean>()

        /** Vrai si la chaîne de causes contient un échec TLS (handshake ou protocole). */
        private fun estEchecHandshake(e: Throwable): Boolean {
            var cause: Throwable? = e
            var garde = 0
            while (cause != null && garde++ < 12) {
                if (cause is javax.net.ssl.SSLException) return true
                val m = cause.message
                if (m != null && (
                        m.contains("HANDSHAKE_FAILURE", ignoreCase = true) ||
                            m.contains("SSLV3_ALERT_HANDSHAKE_FAILURE", ignoreCase = true) ||
                            m.contains("Failure in SSL library", ignoreCase = true)
                        )
                ) return true
                cause = cause.cause
            }
            return false
        }
    }

    /** Fabrique : enveloppe la fabrique normale, avec la fabrique de repli sous le coude. */
    class Factory(
        private val primaryFactory: HttpDataSource.Factory,
        private val fallbackFactory: HttpDataSource.Factory,
    ) : HttpDataSource.Factory {

        override fun setDefaultRequestProperties(
            defaultRequestProperties: Map<String, String>,
        ): HttpDataSource.Factory {
            primaryFactory.setDefaultRequestProperties(defaultRequestProperties)
            fallbackFactory.setDefaultRequestProperties(defaultRequestProperties)
            return this
        }

        override fun createDataSource(): HttpDataSource =
            TlsFallbackHttpDataSource(primaryFactory.createDataSource(), fallbackFactory)
    }
}
