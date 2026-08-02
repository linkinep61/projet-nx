package com.streamflixreborn.streamflix.utils

import android.util.Log
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.logging.HttpLoggingInterceptor
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

object DnsResolver : Dns {
    private const val TAG = "DnsResolver"


    /** 2026-05-14 (user "chargements infinis sur la liste IPTV") : Glide
     *  faisait un appel DoH HTTPS PAR logo (~50/s) sur le même host
     *  logoipro2.com → saturation réseau, list visible mais logos arrivent
     *  en pelote sur 30s. Cache mémoire 5min par host → 1er logo paye le DoH
     *  (~500ms), tous les suivants instantanés. Bénéfice général :
     *  l'app entière (tous providers + images TMDB) résout chaque host UNE
     *  fois par 5 min. */
    private const val CACHE_TTL_MS = 5 * 60 * 1000L
    private data class CachedAddr(val addresses: List<InetAddress>, val expiresAt: Long)
    private val cache = ConcurrentHashMap<String, CachedAddr>()

    /**
     * 2026-08-08 — adresse déjà résolue pour [hostname], ou null.
     *
     * Sert à imposer l'adresse à Cronet sur les hôtes bloqués au DNS par le FAI (cas
     * `strm7.uqload.is`) : Cronet apporte la bonne signature TLS mais ne sait pas résoudre le
     * nom, le DoT sait résoudre mais change de pile et se fait refuser en 403. En lisant ici
     * l'adresse que le DoT a DÉJÀ trouvée pendant l'extraction, le lecteur n'a plus besoin
     * d'une seconde tentative pour démarrer.
     */
    fun adresseEnCache(hostname: String): String? =
        cache[hostname]?.takeIf { it.expiresAt > System.currentTimeMillis() }
            ?.addresses?.firstOrNull()?.hostAddress
    private val logging = HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC)

    private val trustAllCerts = arrayOf<TrustManager>(
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        }
    )
    private val sslContext = SSLContext.getInstance("TLS").apply { init(null, trustAllCerts, SecureRandom()) }
    private val trustManager = trustAllCerts[0] as X509TrustManager

    // 2026-07-06 (blocage démarrage ~2,5s) : construire ce client OkHttp SSL au CLASS-LOAD
    //   de DnsResolver déclenchait le chargement de la classe okhttp3 `Platform`
    //   (findAndroidPlatform → CertificateChainCleaner) sur le MAIN THREAD — via
    //   StreamFlixApp.onCreate:307 → DnsResolver.<clinit> → OkHttpClient.Builder.sslSocketFactory.
    //   Sur Chromecast ce chargement de classe prend ~2,5s → fige au démarrage.
    //   FIX : LAZY. Le client se construit à la 1re résolution DNS (lookup), qui a lieu
    //   pendant les appels réseau = HORS main thread. Le <clinit> redevient trivial.
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .addInterceptor(logging)
            .build()
    }

    private var _url: String = UserPreferences.dohProviderUrl
    // 2026-07-06 : lazy aussi — buildDoh() touche `client` (OkHttp). S'il était construit
    //   au <clinit>, il re-déclencherait le chargement Platform sur le main. Construit à la
    //   1re résolution via currentDoh().
    private var _internalDoh: Dns? = null
    private fun currentDoh(): Dns = _internalDoh ?: buildDoh(_url).also { _internalDoh = it }

    /**
     * 2026-08-08 (user : « ça peut sûrement être contourné ») — CDN hakunaymatata :
     * ON FORCE LA BRANCHE CLOUDFRONT.
     *
     * Symptôme : tous les serveurs Cloudstream/MovieBox+ tombaient en `Response code: 428
     * Precondition Required`, sur la Chromecast comme dans le navigateur du user, avec ou
     * sans VPN (testé en IP résidentielle Orange 128.79.17.77 ET en IP NordVPN Allemagne
     * 5.253.115.33 — même refus). Ce n'était donc ni le jeton, ni les en-têtes, ni l'IP.
     *
     * Cause : `hcdn3.hakunaymatata.com` est servi par DEUX CDN selon la région, et les
     * en-têtes de réponse le disent noir sur blanc —
     *   côté user   : `server: Google-Edge-Cache`, `cdn-cache-status: par;bypassed`
     *                 → 428 sur TOUT, y compris un chemin inexistant et /robots.txt ;
     *   ailleurs    : `Server: CloudFront`, `X-Amz-Cf-Pop: JFK50-P13`
     *                 → 403 sans signature (normal), 206 video/mp4 avec.
     * La chaîne complète est :
     *   hcdn3.hakunaymatata.com → …bplslb.com → …cdn48.com → d2gvsznh03y059.cloudfront.net
     * Vérifié en imposant l'IP CloudFront avec l'en-tête Host : 403, plus jamais 428.
     *
     * Correctif : quand on nous demande un hôte hakunaymatata, on résout à la place le
     * nom CloudFront de la distribution. On reste donc sur le CDN d'origine du service,
     * simplement pas sur la branche Google Edge qui nous ferme la porte.
     *
     * Filet : si cette résolution échoue, on retombe sur la résolution normale de l'hôte
     * demandé — jamais de régression, au pire on revient au comportement d'avant.
     */
    private const val CLOUDFRONT_HAKUNA = "d2gvsznh03y059.cloudfront.net"
    private fun cibleContournement(hostname: String): String? =
        if (hostname.endsWith(".hakunaymatata.com", ignoreCase = true) &&
            !hostname.equals(CLOUDFRONT_HAKUNA, ignoreCase = true)
        ) CLOUDFRONT_HAKUNA else null

    override fun lookup(hostname: String): List<InetAddress> {
        // Check cache d'abord
        val now = System.currentTimeMillis()
        cache[hostname]?.let { cached ->
            if (cached.expiresAt > now) {
                return cached.addresses
            }
            cache.remove(hostname)
        }
        // Renvoi vers la branche CloudFront (voir le commentaire de cibleContournement).
        cibleContournement(hostname)?.let { cible ->
            try {
                val addrs = currentDoh().lookup(cible)
                if (addrs.isNotEmpty()) {
                    Log.i(TAG, "hakunaymatata : $hostname → branche CloudFront ($cible) = " +
                        addrs.joinToString { it.hostAddress ?: "" })
                    cache[hostname] = CachedAddr(addrs, now + CACHE_TTL_MS)
                    return addrs
                }
            } catch (e: Exception) {
                Log.w(TAG, "hakunaymatata : bascule CloudFront impossible pour $hostname " +
                    "(${e.message}) → résolution normale")
            }
        }
        val providerName = if (_url.isEmpty()) "SYSTEM" else _url
        Log.d(TAG, "Resolving host: $hostname using provider: $providerName")
        return try {
            val addresses = currentDoh().lookup(hostname)
            Log.d(TAG, "Resolved $hostname to: ${addresses.joinToString { it.hostAddress ?: "" }}")
            cache[hostname] = CachedAddr(addresses, now + CACHE_TTL_MS)
            addresses
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve $hostname with $providerName: ${e.message}")
            // 2026-07-29 : AVANT le DNS système (filtré par le FAI), tenter un DoH
            //   non-filtrant de SECOURS (dns.sb sur le port 443). Utile quand le DoT
            //   (port 853) est bloqué sur le réseau (certains Wifi/opérateurs) : le
            //   443 passe presque toujours → on garde un résolveur NON filtré au lieu
            //   de retomber sur le DNS FAI qui bloque justement les domaines visés.
            try {
                val backup = backupDoh().lookup(hostname)
                if (backup.isNotEmpty()) {
                    Log.i(TAG, "Backup DoH (dns.sb) resolved $hostname to: ${backup.joinToString { it.hostAddress ?: "" }}")
                    cache[hostname] = CachedAddr(backup, now + CACHE_TTL_MS)
                    return backup
                }
            } catch (eb: Exception) {
                Log.w(TAG, "Backup DoH also failed for $hostname: ${eb.message}")
            }
            // 2026-07-16 : filet de sécurité — si le DoH échoue (serveur DoH injoignable,
            //   ex. cloudflare-dns.com non résolvable via un DNS privé/cassé), on tente le
            //   DNS SYSTÈME pour NE PAS faire tomber tout le provider (AnimeSama échouait
            //   entièrement : catalogue, genres, épisodes, recherche). Si le domaine est
            //   bloqué par le DNS FAI, le système échouera aussi → on relance l'erreur DoH.
            return try {
                val sys = Dns.SYSTEM.lookup(hostname)
                Log.i(TAG, "Fallback SYSTEM DNS resolved $hostname to: ${sys.joinToString { it.hostAddress ?: "" }}")
                cache[hostname] = CachedAddr(sys, now + CACHE_TTL_MS)
                sys
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback SYSTEM DNS also failed for $hostname: ${e2.message}")
                throw e
            }
        }
    }

    /** Invalide le cache DNS (utile quand l'user change de provider DoH). */
    fun clearCache() {
        cache.clear()
        Log.d(TAG, "DNS cache cleared")
    }

    val doh: Dns get() = this

    @Synchronized
    fun setDnsUrl(newUrl: String) {
        Log.i(TAG, "DNS Change Requested: New URL = '$newUrl' (Current = '$_url')")
        if (newUrl != _url) {
            _url = newUrl
            _internalDoh = buildDoh(_url)
            // Invalide cache : les résolutions précédentes étaient via l'ancien provider.
            cache.clear()
            Log.i(TAG, "DNS Engine updated successfully to: ${if (newUrl.isEmpty()) "SYSTEM" else newUrl}, cache invalidated")
        } else {
            Log.d(TAG, "DNS URL is the same as current, skipping update.")
        }
    }

    @Synchronized
    private fun buildDoh(url: String): Dns {
        // 2026-07-29 : DNS-over-TLS (DoT). Les serveurs sans DoH (FDN, Mullvad,
        //   dns.sb, DNSForge clean…) sont stockés sous la forme "dot://<host>".
        //   C'est le même mécanisme que le "DNS privé" d'Android (port 853).
        if (url.startsWith("dot://", true)) {
            val host = url.removePrefix("dot://").removePrefix("DOT://").trim('/').trim()
            return try {
                Log.i(TAG, "Building DoT resolver for host=$host")
                DotDns(host, DotDns.bootstrapFor(host))
            } catch (e: Exception) {
                Log.e(TAG, "Error building DoT for $host, falling back to SYSTEM: ${e.message}")
                Dns.SYSTEM
            }
        }
        return if (url.isNotEmpty()) {
            try {
                val httpUrl = url.toHttpUrl()
                val builder = DnsOverHttps.Builder()
                    .client(client)
                    .url(httpUrl)
                // 2026-07-16 : IPs de bootstrap du serveur DoH → OkHttp se connecte
                //   DIRECTEMENT à l'IP sans devoir résoudre le host DoH (cloudflare-dns.com…)
                //   via le DNS système. Sans ça, quand le DNS système ne résout pas le host
                //   DoH (réseau/DNS privé), TOUT le DoH tombe (bug AnimeSama).
                bootstrapHostsFor(httpUrl.host).takeIf { it.isNotEmpty() }?.let {
                    builder.bootstrapDnsHosts(it)
                }
                builder.build()
            } catch (e: Exception) {
                Log.e(TAG, "Error building DoH for $url, falling back to SYSTEM: ${e.message}")
                Dns.SYSTEM
            }
        } else {
            Log.d(TAG, "No DoH URL provided, using SYSTEM DNS")
            Dns.SYSTEM
        }
    }

    /** DoH non-filtrant de secours (dns.sb, port 443) — construit à la 1re utilisation.
     *  Sert quand le resolver principal (souvent DoT/853) est injoignable réseau. */
    private var _backupDoh: Dns? = null
    @Synchronized
    private fun backupDoh(): Dns {
        _backupDoh?.let { return it }
        val built = try {
            val url = "https://doh.dns.sb/dns-query".toHttpUrl()
            DnsOverHttps.Builder()
                .client(client)
                .url(url)
                .bootstrapDnsHosts(
                    listOf("185.222.222.222", "45.11.45.11")
                        .mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
                )
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Backup DoH build failed: ${e.message}")
            Dns.SYSTEM
        }
        _backupDoh = built
        return built
    }

    /** IPs connues des serveurs DoH courants (littéraux → aucune résolution DNS). */
    private fun bootstrapHostsFor(host: String): List<InetAddress> {
        val ips = when {
            host.contains("cloudflare", true) ->
                listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001")
            host.contains("google", true) ->
                listOf("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844")
            host.contains("quad9", true) ->
                listOf("9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9")
            // 2026-07-30 : DoH non-filtrants 443 (utiles derrière Bouygues qui bloque le DoT/853
            //   et détourne le DNS 53). Bootstrap indispensable : sinon l'hôte DoH serait résolu
            //   par le DNS FAI détourné → échec.
            host.contains("dns.sb", true) ->
                listOf("185.222.222.222", "45.11.45.11", "2a09::", "2a11::")
            host.contains("mullvad", true) ->
                listOf("194.242.2.2", "2a07:e340::2")
            host.contains("adguard", true) ->
                listOf("94.140.14.14", "94.140.15.15")
            else -> emptyList()
        }
        return ips.mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
    }
}
