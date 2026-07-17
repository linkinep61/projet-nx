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

    override fun lookup(hostname: String): List<InetAddress> {
        // Check cache d'abord
        val now = System.currentTimeMillis()
        cache[hostname]?.let { cached ->
            if (cached.expiresAt > now) {
                return cached.addresses
            }
            cache.remove(hostname)
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

    /** IPs connues des serveurs DoH courants (littéraux → aucune résolution DNS). */
    private fun bootstrapHostsFor(host: String): List<InetAddress> {
        val ips = when {
            host.contains("cloudflare", true) ->
                listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001")
            host.contains("google", true) ->
                listOf("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844")
            host.contains("quad9", true) ->
                listOf("9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9")
            host.contains("adguard", true) ->
                listOf("94.140.14.14", "94.140.15.15")
            else -> emptyList()
        }
        return ips.mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
    }
}
