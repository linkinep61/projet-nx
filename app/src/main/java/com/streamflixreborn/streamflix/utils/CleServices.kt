package com.streamflixreborn.streamflix.utils

import com.streamflixreborn.streamflix.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response

// 2026-11-07 : en-tete d'acces aux services en ligne de l'appli (exige par le pare-feu du domaine).
//   La valeur vient de la compilation (secret CI / local.properties), masquee dans BuildConfig.CV.
object CleServices : Interceptor {
    private val valeur: String by lazy {
        val m = "q7Zk2LpW9xRt4NbE"
        val h = BuildConfig.CV
        if (h.isEmpty()) "" else String(ByteArray(h.length / 2) { i ->
            (h.substring(i * 2, i * 2 + 2).toInt(16) xor m[i % m.length].code).toByte()
        }, Charsets.UTF_8)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val r = chain.request()
        return if (valeur.isNotEmpty() && r.url.host.endsWith("velkara.net"))
            chain.proceed(r.newBuilder().header("X-Cv", valeur).build())
        else chain.proceed(r)
    }
}
