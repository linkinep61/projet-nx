package com.streamflixreborn.streamflix.utils

import android.os.Build
import android.util.Log
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.StreamFlixApp
import java.security.KeyStore
import java.security.Provider
import java.security.cert.CertificateFactory
import javax.net.ssl.ManagerFactoryParameters
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.TrustManagerFactorySpi
import javax.net.ssl.X509TrustManager

/**
 * Security Provider that wraps the default TrustManagerFactory to include
 * the ISRG Root X1 + X2 certificates (Let's Encrypt) so that ALL HTTPS calls
 * trust them, indépendamment de l'OEM/version Android.
 *
 *  - ISRG Root X1 (RSA) : présent dans Android ≥ 7.1.1 (API 25+), absent
 *    avant → l'install était précédemment skip si API ≥ P.
 *  - ISRG Root X2 (ECDSA) : ajouté plus tard à AOSP, présence inconsistante
 *    selon OEM (vu absent sur OPPO ColorOS Android 13 → coflix.cymru tombait
 *    en `Unacceptable certificate: CN=ISRG Root X2` 2026-06-02). Donc on
 *    l'injecte systématiquement, quelle que soit l'API.
 *
 * Registered BEFORE Conscrypt so que tous les OkHttpClient en bénéficient
 * sans code par provider.
 */
class IsrgRootTrustProvider : Provider("IsrgRootTrust", 1.0, "Adds ISRG Root X1 + X2 to TrustManagerFactory") {

    companion object {
        private const val TAG = "IsrgRootTrust"

        fun install() {
            // 2026-06-02 : on n'a plus de skip par API.
            //   X1 reste utile pour les anciens devices (< 7.1.1 = API 25).
            //   X2 manque toujours sur certains OEMs même en API 33+ (OPPO,
            //   Xiaomi, …) → l'install systématique est ce qui débloque
            //   Coflix (cert ECDSA Let's Encrypt) et tout autre site qui
            //   bascule sur X2 à l'avenir.
            try {
                java.security.Security.insertProviderAt(IsrgRootTrustProvider(), 1)
                Log.i(TAG, "ISRG Root X1+X2 TrustProvider installed for API ${Build.VERSION.SDK_INT}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to install ISRG Root TrustProvider: ${e.message}")
            }
        }

        /** Merged KeyStore: system CAs + ISRG Root X1. Built once and cached. */
        internal val mergedKeyStore: KeyStore by lazy {
            val algorithm = TrustManagerFactory.getDefaultAlgorithm()

            // Load system certificates via default factory (skips our provider by using explicit provider name)
            val systemTmf = getSystemTrustManagerFactory(algorithm)
            systemTmf.init(null as KeyStore?)
            val systemTm = systemTmf.trustManagers.first { it is X509TrustManager } as X509TrustManager

            // Create merged keystore
            val ks = KeyStore.getInstance(KeyStore.getDefaultType())
            ks.load(null, null)

            systemTm.acceptedIssuers.forEachIndexed { i, cert ->
                ks.setCertificateEntry("system_$i", cert)
            }

            // Add ISRG Root X1 (RSA — Let's Encrypt classique)
            val cf = CertificateFactory.getInstance("X.509")
            val isrgX1 = StreamFlixApp.instance.resources.openRawResource(R.raw.isrg_root_x1).use {
                cf.generateCertificate(it)
            }
            ks.setCertificateEntry("isrg_root_x1", isrgX1)

            // Add ISRG Root X2 (ECDSA — Let's Encrypt nouvelle racine, manque
            // sur certains OEMs même en API 33+ → indispensable pour Coflix et
            // d'autres sites qui basculent sur cette CA).
            val isrgX2 = StreamFlixApp.instance.resources.openRawResource(R.raw.isrg_root_x2).use {
                cf.generateCertificate(it)
            }
            ks.setCertificateEntry("isrg_root_x2", isrgX2)

            Log.d(TAG, "Merged KeyStore built: ${ks.size()} certificates (incl. ISRG X1+X2)")
            ks
        }

        internal fun getSystemTrustManagerFactory(algorithm: String): TrustManagerFactory {
            val providers = java.security.Security.getProviders()
            val provider = providers.firstOrNull {
                it.name != "IsrgRootTrust" && it.getService("TrustManagerFactory", algorithm) != null
            } ?: throw java.security.NoSuchAlgorithmException("No system provider found for TrustManagerFactory.$algorithm")
            
            return TrustManagerFactory.getInstance(algorithm, provider)
        }
    }

    init {
        val factoryClass = IsrgTrustManagerFactorySpi::class.java.name
        put("TrustManagerFactory.${TrustManagerFactory.getDefaultAlgorithm()}", factoryClass)
        put("TrustManagerFactory.PKIX", factoryClass)
    }

    /**
     * TrustManagerFactory implementation that returns trust managers
     * from Conscrypt but with ISRG Root X1 injected into the trust store.
     */
    class IsrgTrustManagerFactorySpi : TrustManagerFactorySpi() {
        private lateinit var delegate: TrustManagerFactory

        override fun engineInit(ks: KeyStore?) {
            val algorithm = TrustManagerFactory.getDefaultAlgorithm()
            delegate = getSystemTrustManagerFactory(algorithm)

            if (ks == null) {
                // OkHttp calls init(null) to get system defaults — inject ISRG Root X1
                delegate.init(mergedKeyStore)
            } else {
                // Explicit KeyStore provided — use as-is
                delegate.init(ks)
            }
        }

        override fun engineInit(spec: ManagerFactoryParameters?) {
            delegate = getSystemTrustManagerFactory(TrustManagerFactory.getDefaultAlgorithm())
            delegate.init(spec)
        }

        override fun engineGetTrustManagers(): Array<TrustManager> = delegate.trustManagers
    }
}
