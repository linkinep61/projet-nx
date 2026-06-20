package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * "VPN 2" — passerelle vers PlanetVPN (com.freevpnplanet) que l'utilisateur
 * installe à part. On NE reverse PAS leur protocole maison "StarGuard" : on
 * exploite le proxy SOCKS5 que l'app PlanetVPN expose **localement** sur
 * 127.0.0.1:10808 quand elle est connectée.
 *
 * Vérifié le 2026-06-18 sur OPPO CPH2211 :
 *   - PlanetVPN connecté Frankfurt am Main #058 (= "Germany - Free")
 *   - Test handshake SOCKS5 :  printf '\x05\x01\x00' | nc 127.0.0.1 10808
 *     → réponse  05 00  (= SOCKS5 v5, NO_AUTH accepté)
 *   - Test CONNECT api.ipify.org:80 + GET / :
 *     → IP retournée = 162.19.234.202 (= serveur PlanetVPN DE)
 *     → CF-RAY: ...-FRA  (= Cloudflare datacenter Frankfurt)
 *
 * Architecture côté ONYX (= mêmes hooks que VavooTunnel.localProxyAddress) :
 *   App OkHttp/ExoPlayer → SOCKS5 127.0.0.1:10808 (= PlanetVPN local) →
 *     → StarGuard tunnel → serveur DE → Vavoo CDN → vrai contenu
 *
 * Avantages vs VYPN intégré :
 *   - Aucun reverse de protocole nécessaire
 *   - PlanetVPN gère la rotation des serveurs DE et le keep-alive
 *   - On ne crée PAS de VpnService → pas de conflit avec autres VPN du device
 *
 * Inconvénients à signaler à l'utilisateur :
 *   - PlanetVPN doit être installé manuellement (Play Store)
 *   - L'utilisateur doit cliquer "Connecter" dans PlanetVPN au moins 1 fois
 *     par session (ils ont un timer auto-disconnect)
 *   - Au démarrage de l'app PlanetVPN il y a une pub interstitielle
 *
 * Mutex : ne JAMAIS activer en même temps que VavooTunnel (mode VYPN). Le
 * picker top-bar bascule entre VYPN / PlanetVPN / OFF mais jamais 2 actifs.
 */
object PlanetVpnGate {

    private const val TAG = "PlanetVpnGate"
    const val PACKAGE_NAME = "com.freevpnplanet"
    private const val SOCKS5_PORT = 10808
    private const val SOCKS5_HOST = "127.0.0.1"
    private const val PROBE_TIMEOUT_MS = 1500

    /** Adresse du proxy SOCKS5 local exposé par PlanetVPN quand il est connecté. */
    fun localProxyAddress(): InetSocketAddress = InetSocketAddress(SOCKS5_HOST, SOCKS5_PORT)

    /** True si l'APK PlanetVPN est présente sur l'appareil. */
    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE_NAME, 0)
        true
    } catch (_: Throwable) {
        false
    }

    /**
     * Probe rapide : TCP connect + SOCKS5 v5 greeting + lecture de la réponse.
     * Retourne true si on a bien `05 00` (= NO_AUTH accepté). Aucune exception
     * ne remonte au caller.
     *
     * Doit être appelé en background (= IO dispatcher). ~50 ms typique en LAN
     * local, max PROBE_TIMEOUT_MS si PlanetVPN n'est pas démarré.
     */
    fun isReady(): Boolean {
        return try {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(SOCKS5_HOST, SOCKS5_PORT), PROBE_TIMEOUT_MS)
                sock.soTimeout = PROBE_TIMEOUT_MS
                // SOCKS5 v5 client greeting : version, n_methods=1, NO_AUTH
                sock.getOutputStream().apply {
                    write(byteArrayOf(0x05, 0x01, 0x00))
                    flush()
                }
                val resp = ByteArray(2)
                val read = sock.getInputStream().read(resp)
                read == 2 && resp[0] == 0x05.toByte() && resp[1] == 0x00.toByte()
            }
        } catch (e: Throwable) {
            Log.d(TAG, "isReady: SOCKS5 probe failed (${e.javaClass.simpleName}: ${e.message})")
            false
        }
    }

    /**
     * Lance l'app PlanetVPN. L'utilisateur doit ensuite cliquer "Connecter"
     * lui-même (= le pref serveur "Germany - Free" est par défaut sur les
     * comptes gratuits). À utiliser quand isReady() == false mais isInstalled() == true.
     */
    fun launchPlanetVpn(context: Context): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE_NAME)
                ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            if (intent != null) {
                context.startActivity(intent)
                true
            } else false
        } catch (e: Throwable) {
            Log.w(TAG, "launchPlanetVpn failed: ${e.message}")
            false
        }
    }

    /**
     * Ouvre la fiche PlanetVPN sur le Play Store (ou navigateur si Play Store
     * absent — cas Chromecast). À utiliser quand isInstalled() == false.
     */
    fun openInstallPage(context: Context): Boolean {
        return try {
            val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PACKAGE_NAME"))
                .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            try {
                context.startActivity(market)
                true
            } catch (_: Throwable) {
                val web = Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$PACKAGE_NAME"))
                    .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                context.startActivity(web)
                true
            }
        } catch (e: Throwable) {
            Log.w(TAG, "openInstallPage failed: ${e.message}")
            false
        }
    }

    /**
     * Polling : attend jusqu'à `timeoutMs` que le SOCKS5 soit ready. Test
     * chaque 500 ms. Retourne true dès qu'on a un OK, false si timeout.
     * À appeler en IO dispatcher. Utile après launchPlanetVpn pour bloquer
     * jusqu'à ce que l'utilisateur ait cliqué "Connecter".
     */
    fun waitUntilReady(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isReady()) return true
            try { Thread.sleep(500) } catch (_: InterruptedException) { return false }
        }
        return false
    }
}
