package com.streamflixreborn.streamflix.utils

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * 2026-08-27 (user : « sur le provider Vavoo on a intégré un VPN, j'aimerais
 *   utiliser ce VPN pour toute l'application, pour les VOD. Dans Paramètres ›
 *   Connexion et services, une nouvelle option qui fera un VPN entier pour
 *   l'application. Je parle bien des 2 variantes de VPN ») :
 *
 * Jusqu'ici le tunnel Shadowsocks VYPN (VavooTunnel) n'était branché QUE sur le
 * client OkHttp de VavooProvider. Ici on l'étend à TOUT le trafic HTTP de
 * l'app — providers VOD, extracteurs, lecture ExoPlayer — sans toucher aux 121
 * OkHttpClient.Builder() du projet.
 *
 * Comment : on installe un ProxySelector par défaut au niveau JVM.
 *   - OkHttp : quand aucun `proxy()` explicite n'est posé sur le builder, il
 *     utilise ProxySelector.getDefault() (capturé à la construction du client —
 *     d'où l'installation TRÈS tôt dans StreamFlixApp.onCreate).
 *   - HttpURLConnection (= DefaultHttpDataSource de Media3) : idem.
 *   - Cronet : possède sa PROPRE pile réseau et IGNORE ce ProxySelector. Le
 *     player bascule donc sur DefaultHttpDataSource quand `actif()` est vrai
 *     (voir createHttpDataSourceFactory dans les 2 PlayerFragment).
 *
 * Le sélecteur est DYNAMIQUE : il relit la préférence et l'état du tunnel à
 * chaque requête. Activer / désactiver l'option ne demande donc aucun
 * redémarrage de l'app, seulement le (re)démarrage du tunnel.
 *
 * Les 2 variantes de VPN partagent le même VavooTunnel ; elles ne diffèrent
 * que par le serveur ciblé dans le pool VYPN (skipBestN 0 ou 1).
 */
object VpnGlobal {

    private const val TAG = "VpnGlobal"
    private val AUCUN_PROXY = listOf(Proxy.NO_PROXY)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Domaines qui ne doivent JAMAIS transiter par le tunnel :
     *   - vypn.net = l'API de contrôle du tunnel lui-même. La faire passer par
     *     le tunnel créerait une boucle (et un blocage si le tunnel est mort).
     */
    private val DOMAINES_EXCLUS = listOf("vypn.net")

    @Volatile private var installe = false

    /** Installe le ProxySelector global. Idempotent, à appeler une seule fois
     *  au tout début de StreamFlixApp.onCreate(). */
    fun installer() {
        if (installe) return
        try {
            val precedent = ProxySelector.getDefault()
            ProxySelector.setDefault(SelecteurGlobal(precedent))
            installe = true
            Log.d(TAG, "ProxySelector global installé (précédent=${precedent?.javaClass?.simpleName})")
        } catch (e: Throwable) {
            Log.e(TAG, "installation du ProxySelector impossible : ${e.message}", e)
        }
    }

    /** true si le VPN global doit réellement router du trafic maintenant :
     *  option activée ET tunnel effectivement monté. */
    fun actif(): Boolean = try {
        UserPreferences.vpnGlobalActif && VavooTunnel.isRunning()
    } catch (_: Throwable) {
        false
    }

    /** Libellé court pour l'UI (résumé de la préférence, toast, etc.). */
    fun etatLisible(): String = when {
        !UserPreferences.vpnGlobalActif -> "Désactivé"
        VavooTunnel.isRunning() -> {
            val ip = VavooTunnel.currentServerIp() ?: "?"
            "Actif — $ip"
        }
        else -> "Activé (tunnel en cours de connexion…)"
    }

    /** Plage privee 172.16.0.0/12 (172.16.x a 172.31.x). */
    private val PRIVE_172 = Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\..*")

    private fun estAdresseLocale(hote: String?): Boolean {
        val h = hote?.lowercase() ?: return true
        return h == "localhost" ||
                h == "127.0.0.1" ||
                h == "::1" ||
                h.startsWith("127.") ||
                h.startsWith("10.") ||
                h.startsWith("192.168.") ||
                PRIVE_172.matches(h) ||
                h.endsWith(".local")
    }

    private class SelecteurGlobal(
        private val precedent: ProxySelector?
    ) : ProxySelector() {

        override fun select(uri: URI?): List<Proxy> {
            try {
                if (uri == null) return AUCUN_PROXY
                if (!actif()) return precedent?.select(uri) ?: AUCUN_PROXY

                val hote = uri.host?.lowercase()
                // Serveur local (proxy HLS interne, WebSocket bypass, Cast, IPTV
                // sur le réseau domestique) : jamais par le tunnel.
                if (estAdresseLocale(hote)) return AUCUN_PROXY
                if (hote != null && DOMAINES_EXCLUS.any { hote == it || hote.endsWith(".$it") }) {
                    return AUCUN_PROXY
                }
                return listOf(Proxy(Proxy.Type.SOCKS, VavooTunnel.localProxyAddress()))
            } catch (e: Throwable) {
                Log.e(TAG, "select(${uri?.host}) : ${e.message}")
                return AUCUN_PROXY
            }
        }

        override fun connectFailed(uri: URI?, adresse: SocketAddress?, erreur: IOException?) {
            Log.w(TAG, "connectFailed ${uri?.host} via $adresse : ${erreur?.message}")
            try { precedent?.connectFailed(uri, adresse, erreur) } catch (_: Throwable) {}
        }
    }

    /**
     * Applique le réglage choisi dans Paramètres › Connexion et services :
     * (re)démarre le tunnel sur la bonne variante, ou l'arrête si plus personne
     * n'en a besoin (ni le VPN global, ni le réglage Vavoo).
     *
     * @param onFini rappelé sur le thread IO avec true si le tunnel est monté
     *               (ou n'avait pas à l'être), false si le démarrage a échoué.
     */
    fun appliquer(onFini: ((Boolean) -> Unit)? = null) {
        scope.launch {
            try {
                val besoinGlobal = UserPreferences.vpnGlobalActif
                val besoinVavoo = UserPreferences.vavooUseTunnel

                if (!besoinGlobal && !besoinVavoo) {
                    try { VavooTunnel.stop() } catch (_: Throwable) {}
                    rafraichirClients()
                    Log.d(TAG, "VPN global OFF et Vavoo OFF → tunnel arrêté")
                    onFini?.invoke(true)
                    return@launch
                }

                // Un seul tunnel pour toute l'app : le VPN global décide du
                // serveur quand il est actif, sinon on garde le choix Vavoo.
                val skip = UserPreferences.tunnelSkipBestNEffectif()

                // Stop → pause → start : même séquence que le cold start, elle
                // évite les sockets zombies du pool OkHttp vers l'ancien serveur.
                try { VavooTunnel.stop() } catch (_: Throwable) {}
                delay(500)
                val ok = VavooTunnel.start(skipBestN = skip)
                Log.d(TAG, "tunnel (re)démarré skip=$skip global=$besoinGlobal → $ok")
                if (ok) rafraichirClients()
                onFini?.invoke(ok)
            } catch (e: Throwable) {
                Log.e(TAG, "appliquer : ${e.message}", e)
                onFini?.invoke(false)
            }
        }
    }

    /** Invalide les clients HTTP qui gardent un pool de connexions vers
     *  l'ancienne route. */
    private fun rafraichirClients() {
        try {
            com.streamflixreborn.streamflix.providers.VavooProvider.invalidateClientCache()
        } catch (_: Throwable) {}
    }
}
