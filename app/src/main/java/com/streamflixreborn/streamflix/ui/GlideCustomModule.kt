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

        // Memory cache: 1/6 of available app memory (capped, proportional to device RAM)
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val appMemory = activityManager.memoryClass * 1024L * 1024L
        val memoryCacheSize = (appMemory / 6).coerceIn(8L * 1024 * 1024, 64L * 1024 * 1024)
        builder.setMemoryCache(LruResourceCache(memoryCacheSize))

        // Default to RGB_565 for lower memory usage
        builder.setDefaultRequestOptions(
            RequestOptions().format(DecodeFormat.PREFER_RGB_565)
        )
    }

    private fun getOkHttpClient(context: Context): OkHttpClient {
        val appCache = Cache(File(context.cacheDir, "okhttpcache"), 10 * 1024 * 1024)

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
            .cache(appCache)
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