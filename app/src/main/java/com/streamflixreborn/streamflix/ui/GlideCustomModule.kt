package com.streamflixreborn.streamflix.ui

import android.app.ActivityManager
import android.content.Context
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory
import com.bumptech.glide.load.engine.cache.LruResourceCache
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.module.AppGlideModule
import com.bumptech.glide.request.RequestOptions
import com.streamflixreborn.streamflix.utils.DnsResolver
import okhttp3.Cache
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.OkHttpClient.Builder
import okhttp3.Protocol
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.net.ssl.SSLContext
import java.security.SecureRandom

@GlideModule
class GlideCustomModule : AppGlideModule() {
    companion object {
        private const val DISK_CACHE_SIZE = 150L * 1024 * 1024 // 150 MB
    }

    override fun applyOptions(context: Context, builder: GlideBuilder) {
        // Disk cache: 150MB max
        builder.setDiskCache(InternalCacheDiskCacheFactory(context, "glide_cache", DISK_CACHE_SIZE))

        // 2026-05-18 : memory cache plus agressif sur low-heap (Chromecast 384MB
        //   tombait en OOM avec 64MB de cache Glide + posters accumulés).
        //   TV / leanback : cap à 24MB. Mobile : cap à 48MB.
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val appMemory = activityManager.memoryClass * 1024L * 1024L
        val isTv = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
        val maxCache = if (isTv) 24L * 1024 * 1024 else 48L * 1024 * 1024
        val memoryCacheSize = (appMemory / 12).coerceIn(8L * 1024 * 1024, maxCache)
        builder.setMemoryCache(LruResourceCache(memoryCacheSize))
        android.util.Log.d("GlideCustom", "Memory cache=${memoryCacheSize / 1024 / 1024}MB (isTv=$isTv, heap=${appMemory / 1024 / 1024}MB)")

        // Default to RGB_565 for lower memory usage
        builder.setDefaultRequestOptions(
            RequestOptions().format(DecodeFormat.PREFER_RGB_565)
        )
    }

    private fun getOkHttpClient(context: Context): OkHttpClient {
        // 2026-05-07 : SUPPRIMÉ le Cache OkHttp ici. Glide a déjà son propre
        // InternalCacheDiskCacheFactory (150 MB) qui cache les bitmaps décodés.
        // Le cache OkHttp dupliquait les images encodées et déclenchait un
        // SIGABRT natif dans conscrypt-android 2.5.3 (X509_NAME_print) lors
        // de Cache$Entry.writeTo → Handshake.peerCertificates →
        // OpenSSLX509Certificate.toString. Crash visible dans tombstone_00
        // sur thread image.tmdb.org. Sans .cache(...), CacheInterceptor n'écrit
        // plus, donc plus de sérialisation de cert, donc plus de crash.
        val logging = HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC)

        // Always trust-all for image loading AND use DoH for resolution
        val trustAllCerts = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            }
        )
        val sslContext = SSLContext.getInstance("TLS").apply { init(null, trustAllCerts, SecureRandom()) }
        val trustManager = trustAllCerts[0] as X509TrustManager

        // Build a base client (trust-all) to bootstrap DoH
        // Includes User-Agent, Referer, and shared CookieJar for Cloudflare compatibility
        return Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            // Force HTTP/1.1 — LiteSpeed servers (witv.team) reject OkHttp's HTTP/2 ALPN
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectionSpecs(listOf(ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
            .addInterceptor(logging)
            .addInterceptor { chain ->
                val request = chain.request()
                val newRequest = request.newBuilder()
                    .header("User-Agent", com.streamflixreborn.streamflix.utils.NetworkClient.USER_AGENT)
                    .header("Referer", "${request.url.scheme}://${request.url.host}/")
                    .build()
                chain.proceed(newRequest)
            }
            .cookieJar(com.streamflixreborn.streamflix.utils.NetworkClient.cookieJar)
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .dns(DnsResolver.doh)
            .build()
    }

    override fun registerComponents(
        context: Context, glide: Glide, registry: com.bumptech.glide.Registry
    ) {
        val okHttpClient = getOkHttpClient(context)
        registry.replace(
            GlideUrl::class.java, InputStream::class.java, OkHttpUrlLoader.Factory(okHttpClient)
        )
    }
}