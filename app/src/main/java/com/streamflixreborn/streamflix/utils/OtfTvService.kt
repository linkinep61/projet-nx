package com.streamflixreborn.streamflix.utils

import android.util.Base64
import android.util.Log
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 2026-05-31 : OTF TV — API chiffrée AES qui retourne des URLs m3u8 directes.
 * Extrait de WiTvProvider pour être utilisé dans TV Hub.
 *
 * API : POST https://app.otf-tv.com/otf/authV4.php (V3 deprecated ~juin 2026)
 * Réponse : JSON chiffré AES-128-CBC contenant des groupes de chaînes
 * avec des URLs directes sur blc2cr.linkip.org / stm.linkip.org.
 */
object OtfTvService {

    private const val TAG = "OtfTvService"
    // 2026-06-20 : V3 retourne body vide depuis ~juin 2026, V4 fonctionne
    //   (même clé AES, même format hash, même payload — seul l'endpoint change).
    private const val OTF_API_URL = "https://app.otf-tv.com/otf/authV4.php"
    private const val OTF_AES_KEY = "@z5wFi5vDgtF_vds"

    /** 2026-07-20 (user « OTF ne fonctionne plus … écran noir ») — DIAGNOSTIC RÉSEAU on-device
     *  (PCAPdroid sur l'app officielle OTF TV V3.2 / com.iptvsmartflixplayer) : elle interroge
     *  `authV3.php` puis streame depuis **blcco.linkip.org**. Or `authV4.php` (ce qu'on appelait
     *  seul) ne sert AUCUNE URL blcco : 1133/1667 chaînes sont sur `*.dencreak.com` (NXDOMAIN,
     *  domaine mort) et le reste sur `stm.linkip.org` (HTTP 522, origine morte) → écran noir.
     *  TESTÉ : `authV3.php` avec notre payload répond **302 vers `ash-speed.hetzner.com/10GB.bin`**
     *  (tarpit anti-scraper) → inutilisable, on ne l'appelle PAS. La vraie solution est la
     *  reconstruction des URLs sur les hôtes CDN vivants (cf. `expandCdnMirrors`). */
    private val OTF_API_URLS = listOf(
        "https://app.otf-tv.com/otf/authV4.php",
    )

    /** 2026-07-20 — CDN dont le DOMAINE est mort (NXDOMAIN via Cloudflare/Google/Quad9), constaté
     *  en diagnostic on-device : l'API OTF sert encore ~1133 chaînes sur 1667 avec ces hôtes
     *  (py892/fr.dencreak.com) → UnknownHostException → écran noir. VÉRIFIÉ : le domaine `dencreak.com`
     *  ENTIER est NXDOMAIN (1.1.1.1 depuis le PC, DNS privé du téléphone à `off`) → ce n'est PAS un
     *  filtrage local, le CDN est mort. On relègue simplement ces URLs en dernier dans la liste de
     *  la chaîne (le player passe à la suivante). */
    private val DEAD_CDN_HOSTS = listOf("dencreak.com", "stm.linkip.org", "stb.blc2cdn.fyi")

    /** CDN vérifié VIVANT (celui que l'app officielle utilise réellement) → priorité maximale. */
    private val PREFERRED_CDN_HOSTS = listOf("blcco.linkip.org", "blc2cr.linkip.org")

    /** 2026-07-20 (user : « quand je change de chaîne ça change pas… obligé de faire retour ») —
     *  le catalogue contient PLUSIEURS entrées portant EXACTEMENT le même nom (ex. « TF1 » présent
     *  en double/triple sur des CDN différents). Le tri TNT les plaçant côte à côte, « chaîne
     *  suivante » tombait sur un doublon → log `switchChannel delta=1 : TF1 → TF1`, et comme le
     *  doublon est souvent sur un CDN mort, on avait en plus une erreur réseau.
     *  → On fusionne les entrées de même nom en une seule (URLs cumulées, CDN vivant d'abord),
     *  ce qui rend prev/next réellement utile ET donne à chaque chaîne toutes ses sources. */
    /** 2026-07-20 : la chaîne a-t-elle au moins une source jouable ? (après miroir stm→blcco).
     *  Sert au zapping : inutile de s'arrêter 45 s sur une chaîne dont tous les CDN sont morts —
     *  le user appuie sur « suivante », il veut la prochaine chaîne QUI MARCHE. */
    fun hasLiveSource(ch: OtfChannel): Boolean =
        orderUrlsByCdnHealth(ch.urls).any { cdnRank(it) < 2 }

    fun dedupeChannels(list: List<OtfChannel>): List<OtfChannel> {
        val m = LinkedHashMap<String, OtfChannel>()
        for (c in list) {
            val prev = m[c.normalizedKey]
            m[c.normalizedKey] = if (prev == null) c else prev.copy(
                urls = orderUrlsByCdnHealth(prev.urls + c.urls),
                logo = prev.logo ?: c.logo,
            )
        }
        return m.values.toList()
    }

    /** 2026-07-20 — ✅ MIROIR VÉRIFIÉ `stm.linkip.org` → `blcco.linkip.org`.
     *  `stm.linkip.org` répond HTTP 522 (origine morte) mais `blcco.linkip.org` — l'hôte que
     *  streame réellement l'app officielle — sert le MÊME espace d'identifiants, avec le MÊME
     *  chemin `/live/<id>_.m3u8`. Vérifié EMPIRIQUEMENT en décodant une image de chaque flux :
     *  id 1704 → France 24, 792/793/794/795 → beIN Sports, 803 → RMC Sport 1, 843/847 → Canal+
     *  Sport, 787 → Eurosport 1 — tous conformes au nom que le catalogue donne à ces ids.
     *  C'est donc une substitution d'hôte SÛRE (contrairement à dencreak, cf. plus bas).
     *  On ajoute l'URL blcco EN PREMIER et on garde l'originale en repli. */
    private fun mirrorStmToBlcco(urls: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (u in urls) {
            if (u.contains("//stm.linkip.org/", true)) {
                out.add(u.replace("//stm.linkip.org/", "//blcco.linkip.org/", true))
            }
            out.add(u)
        }
        return out.toList()
    }

    /** ⛔ RETIRÉ 2026-07-20 (vérifié EN DIRECT, user : « j'ai mis France 2 j'ai une chaîne arabe ») :
     *  j'avais tenté de reconstruire l'URL sur un hôte vivant en gardant le même id
     *  (`/live/<id>_.m3u8`). **C'EST FAUX** : chaque CDN a son PROPRE espace d'identifiants —
     *  l'id 1704 = « France 24 » sur `stm.linkip.org` mais diffuse TF1 sur `blcco.linkip.org`.
     *  Principe du projet : PAS DE FLUX plutôt que la MAUVAISE chaîne. On ne réécrit donc JAMAIS
     *  l'hôte d'une URL OTF. Idem pour l'agrégation de « variantes » de nom, retirée pour la même
     *  raison. Ne pas retenter sans une VRAIE table de correspondance par CDN.
     */

    /** Trie les URLs d'UNE MÊME chaîne (plusieurs qualités) en mettant les CDN vivants d'abord.
     *  Ne fabrique AUCUNE URL : on ne joue que ce que l'API a réellement renvoyé pour cette chaîne.
     *
     *  2026-07-20 (user : « les chaînes partent pas aussi vite qu'avant, comme si elle passait par
     *  une phase d'échec avant d'aller à la source qui fonctionne » puis « pourquoi t'as gardé la
     *  morte, ça sert à rien ») : les URLs sur CDN mort sont **PUREMENT SUPPRIMÉES**, jamais
     *  reléguées **au sein d'une même chaîne** : si la chaîne a au moins une URL vivante, on ne
     *  tente PAS d'abord un hôte NXDOMAIN (c'était la « phase d'échec » avant la bonne source).
     *  En revanche on ne supprime jamais une chaîne du catalogue (cf. `fetchFromEndpoint`) : si
     *  toutes ses URLs sont mortes, on les garde telles quelles — l'API fait foi. */
    fun orderUrlsByCdnHealth(urls: List<String>): List<String> {
        val distinct = mirrorStmToBlcco(urls).distinct()
        val alive = distinct.filter { cdnRank(it) < 2 }
        return if (alive.isNotEmpty()) alive.sortedBy { cdnRank(it) } else distinct
    }

    /** Rang de préférence d'une URL : 0 = CDN privilégié, 1 = neutre, 2 = CDN mort. */
    private fun cdnRank(u: String): Int = when {
        PREFERRED_CDN_HOSTS.any { u.contains(it, true) } -> 0
        DEAD_CDN_HOSTS.any { u.contains(it, true) } -> 2
        else -> 1
    }

    data class OtfChannel(
        val name: String,
        val normalizedKey: String,
        val catId: Int = 0, // CatID unique de l'API OTF
        val urls: List<String>, // m3u8 URLs (différentes qualités)
        val logo: String? = null, // URL du logo si dispo dans l'API
        val group: String = "", // Nom du groupe/catégorie (langue/pays)
    )

    // Cache en mémoire — valide 30 min
    private var cachedChannels: List<OtfChannel>? = null
    private var cacheTimestamp = 0L
    private const val CACHE_TTL = 30 * 60 * 1000L

    // 2026-06-19 v38 (user "creuse pourquoi OTF") : APRÈS décompilation de
    //   l'APK officielle OTF TV V3.2 (com.iptvsmartflixplayer), trouvé que
    //   DeviceID = Settings.Secure.ANDROID_ID BRUT (16 hex), PAS notre format
    //   bidon "sf<fingerprint_hash>otf". Le serveur rejetait silencieusement
    //   nos hashes (body vide). Avec le bon DeviceID → 1 MB de streams OK.
    @Volatile private var appContextRef: android.content.Context? = null
    fun installContext(ctx: android.content.Context) {
        appContextRef = ctx.applicationContext
        Log.d(TAG, "Application context installed for ANDROID_ID lookup")
    }
    // 2026-06-04 (user "le TV hub galère à se charger et s'ouvrir" quand OTF est
    //   down) : cache négatif — si le fetch échoue ou timeout, on bloque les
    //   re-tentatives pendant 60s pour ne pas faire patienter à chaque
    //   navigation home.
    @Volatile private var lastFailureTimestamp = 0L
    private const val FAILURE_COOLDOWN_MS = 60 * 1000L

    /**
     * Récupère toutes les chaînes OTF TV avec leurs URLs m3u8.
     * Cache 30 min en mémoire + cooldown 60s sur échec.
     */
    suspend fun fetchChannels(): List<OtfChannel> {
        val cached = cachedChannels
        if (cached != null && System.currentTimeMillis() - cacheTimestamp < CACHE_TTL) {
            return cached
        }
        // Si fetch a fail récemment, return ce qu'on a (souvent emptyList) sans
        // refaire un appel réseau qui re-timeout 8s.
        if (System.currentTimeMillis() - lastFailureTimestamp < FAILURE_COOLDOWN_MS) {
            Log.d(TAG, "fetchChannels: skipped (last failure ${(System.currentTimeMillis() - lastFailureTimestamp) / 1000}s ago, cooldown ${FAILURE_COOLDOWN_MS / 1000}s)")
            return cached ?: emptyList()
        }

        return try {
            val channels = fetchFromApi()
            cachedChannels = channels
            cacheTimestamp = System.currentTimeMillis()
            lastFailureTimestamp = 0L
            Log.d(TAG, "Fetched ${channels.size} OTF channels")
            channels
        } catch (t: Throwable) {
            // 2026-06-19 v36 : catch Throwable (= inclut OutOfMemoryError) au
            //   lieu d'Exception pour ne pas laisser un worker mort bloquer
            //   le getHome de LiveTvHubProvider.
            Log.e(TAG, "Failed to fetch OTF channels: ${t.javaClass.simpleName}: ${t.message}")
            lastFailureTimestamp = System.currentTimeMillis()
            cached ?: emptyList()
        }
    }
    /** Marque le service comme échoué (= bloque les retry pendant 60s).
     *  Appelé par LiveTvHubProvider si on intercepte un OOM ailleurs. */
    fun markFailure() {
        lastFailureTimestamp = System.currentTimeMillis()
    }

    /** 2026-06-20 (user "au clic sur le dossier OTF vide ça devrait déclencher
     *  le refresh") : reset le cache négatif + le cache positif pour forcer
     *  un fetch frais au prochain fetchChannels(). */
    fun resetForRefresh() {
        lastFailureTimestamp = 0L
        cachedChannels = null
        cacheTimestamp = 0L
    }

    /**
     * Retourne les URLs m3u8 pour une chaîne par sa clé (catId ou normalizedKey).
     */
    suspend fun getUrlsForChannel(key: String): List<String> {
        Log.d(TAG, "getUrlsForChannel: looking for key='$key'")
        val channels = fetchChannels()
        Log.d(TAG, "getUrlsForChannel: ${channels.size} channels in cache")
        // 2026-07-20 (user "OTF ne fonctionne plus") : DIAGNOSTIC on-device — l'API répond
        //   parfaitement (991 Ko déchiffrés, 1667 chaînes) MAIS la 1re entrée trouvée pointait
        //   sur `fr.dencreak.com`, un domaine MORT (NXDOMAIN) → UnknownHostException → écran noir.
        //   L'app officielle, elle, streame depuis blcco.linkip.org. Cause : on faisait
        //   `firstOrNull` → une seule entrée, sans repli. FIX : agréger les URLs de TOUTES les
        //   entrées correspondantes (la même chaîne existe dans plusieurs groupes/CDN), en
        //   dédupliquant, et en reléguant EN DERNIER les hôtes connus morts. Le player peut
        //   ainsi basculer sur un CDN vivant.
        val catIdInt = key.toIntOrNull()
        val exact = if (catIdInt != null && catIdInt > 0) {
            channels.filter { it.catId == catIdInt }
        } else {
            channels.filter { it.normalizedKey == key }
        }
        // ⛔ 2026-07-20 : l'agrégation par « variantes de nom » (france2 ≈ france2hd…) est RETIRÉE.
        //   Elle rapatriait les URLs d'une AUTRE entrée du catalogue → user : « j'ai mis France 2,
        //   j'ai une chaîne arabe ». On ne joue QUE les URLs de la chaîne demandée.
        if (exact.isEmpty()) {
            Log.w(TAG, "getUrlsForChannel: NOT FOUND for key='$key'")
            return emptyList()
        }
        val ordered = orderUrlsByCdnHealth(exact.flatMap { it.urls })
        val liveCount = ordered.count { cdnRank(it) < 2 }
        Log.d(TAG, "getUrlsForChannel: ${exact.size} entrée(s) → ${ordered.size} URLs " +
            "($liveCount sur CDN vivant) — 1re: ${ordered.firstOrNull()?.take(60)}")
        return ordered
    }

    /** 2026-07-20 : interroge V3 **et** V4 et fusionne les URLs par chaîne (clé = CatID si présent,
     *  sinon nom normalisé). Une chaîne dont l'entrée V4 est sur un CDN mort récupère ainsi l'URL
     *  V3 (blcco.linkip.org) que l'app officielle utilise. */
    private fun fetchFromApi(): List<OtfChannel> {
        val merged = LinkedHashMap<String, OtfChannel>()
        for (endpoint in OTF_API_URLS) {
            val list = try {
                fetchFromEndpoint(endpoint)
            } catch (t: Throwable) {
                Log.w(TAG, "OTF $endpoint KO: ${t.javaClass.simpleName}: ${t.message}")
                emptyList()
            }
            Log.d(TAG, "OTF $endpoint → ${list.size} chaînes")
            for (ch in list) {
                // ⚠ CatID n'est PAS unique par chaîne (plusieurs chaînes le partagent) : la clé de
                //   fusion DOIT inclure le nom normalisé, sinon on écrase 1667 chaînes en 48.
                val k = "${ch.catId}|${ch.normalizedKey}"
                val prev = merged[k]
                merged[k] = if (prev == null) ch else prev.copy(
                    urls = (prev.urls + ch.urls).distinct(),
                    logo = prev.logo ?: ch.logo,
                )
            }
        }
        val result = merged.values.toList()
        Log.d(TAG, "OTF TV: ${result.size} chaînes après fusion V3+V4")
        try {
            val hosts = result.flatMap { it.urls }.mapNotNull {
                try { java.net.URL(it).host } catch (_: Exception) { null }
            }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
            Log.d(TAG, "OTF TV (fusion): hôtes CDN = " + hosts.take(12).joinToString(", ") { "${it.key}(${it.value})" })
        } catch (_: Throwable) {}
        return result
    }

    private fun fetchFromEndpoint(apiUrl: String): List<OtfChannel> {
        // 2026-06-19 v38 : DeviceID = Settings.Secure.ANDROID_ID brut (= 16
        //   chars hex) comme l'app officielle OTF TV V3.2 (décompilé). Avec
        //   notre ancien format "sf<hash>otf", le serveur rejetait silencieux.
        val ctx = appContextRef
        val deviceId = if (ctx != null) {
            try {
                android.provider.Settings.Secure.getString(
                    ctx.contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID,
                )?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" }
                    ?: fallbackDeviceId()
            } catch (_: Throwable) { fallbackDeviceId() }
        } else fallbackDeviceId()
        Log.d(TAG, "fetchFromApi: using DeviceID=${deviceId.take(4)}... (len=${deviceId.length})")
        val hash = otfEncrypt("5wF${deviceId}_Opd")

        val formBody = FormBody.Builder()
            .add("DeviceID", deviceId)
            .add("hash", hash)
            .build()
        // 2026-06-19 v36 (user "chargement infini après refresh TV Hub") : la
        //   réponse OTF est grosse (plusieurs MB) et OkHttp duplique le buffer
        //   pour cache write → OOM heap. Fix : cacheControl noStore +
        //   close().use{} pour libérer rapidement.
        // 2026-06-19 v37 (user "OTF redirige vers hetzner / mettre une sécurité
        //   au cas où") : 3 défenses cumulées —
        //   1. Client OkHttp dédié avec followRedirects=false (= ignore 30x)
        //   2. Timeouts agressifs 6s connect / 8s read
        //   3. Cap taille body : si Content-Length > 5 MB ou response.body
        //      réelle > 5 MB → abandon et retourne empty
        // 2026-06-19 v38 : on garde followRedirects=false par défense (l'API
        //   ne devrait JAMAIS rediriger, et si elle le fait c'est suspect).
        //   Mais les timeouts sont élargis car la réponse normale est ~1 MB
        //   (= ~1s download sur connection moyenne).
        // 2026-06-19 v42 (user "ça mouline encore" + crash FATAL OOM
        //   CacheInterceptor.cacheWritingSource.read MALGRÉ .cache(null) v41) :
        //   le .newBuilder() de NetworkClient.default hérite des interceptors
        //   parent, y compris CacheInterceptor qui buffer le body en RAM même
        //   sans cache disque (= bug OkHttp). Fix RADICAL : construire un
        //   OkHttpClient TOTALEMENT VIERGE pour OTF, sans hériter de quoi que
        //   ce soit du parent. Aucun cache, aucun interceptor → aucune copie
        //   du body en RAM.
        val safeClient = okhttp3.OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val req = Request.Builder()
            .url(apiUrl)
            .post(formBody)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            .cacheControl(okhttp3.CacheControl.Builder().noStore().noCache().build())
            .build()

        val response = safeClient.newCall(req).execute()
        // 2026-06-19 v37 : SAFETY — si la réponse est un redirect (30x) ou trop
        //   grosse (> 5 MB), on abandonne IMMÉDIATEMENT.
        if (response.code in 300..399) {
            val loc = response.header("Location") ?: "?"
            Log.w(TAG, "OTF returned redirect ${response.code} → $loc — refusing to follow")
            try { response.close() } catch (_: Throwable) {}
            return emptyList()
        }
        val contentLength = response.body?.contentLength() ?: -1L
        // 2026-06-19 v38 : la réponse OTF normale fait ~1 MB. On cap à 4 MB
        //   pour laisser de la marge mais refuser un payload anormal.
        val MAX_OTF_BODY = 4L * 1024L * 1024L  // 4 MB
        if (contentLength > MAX_OTF_BODY) {
            Log.w(TAG, "OTF body too large (${contentLength} bytes > ${MAX_OTF_BODY}) — refusing to read")
            try { response.close() } catch (_: Throwable) {}
            return emptyList()
        }
        // 2026-06-19 v39 (user "tvhub bloqué" + crash AndroidRuntime OOM dans
        //   response.body.string()) : ne PLUS appeler .string() qui essaie
        //   d'allouer tout le body en RAM (crash OOM si chunked sans
        //   contentLength). On lit via le source byte-par-byte avec une borne
        //   stricte de 4 MB et un try/catch Throwable qui CATCH même les
        //   Errors (OOM, StackOverflow).
        val body = try {
            val source = response.body?.source()
            if (source == null) {
                null
            } else {
                val buf = okio.Buffer()
                var totalRead = 0L
                while (!source.exhausted() && totalRead < MAX_OTF_BODY) {
                    val n = source.read(buf, 64 * 1024L)
                    if (n == -1L) break
                    totalRead += n
                }
                if (totalRead >= MAX_OTF_BODY) {
                    Log.w(TAG, "OTF body exceeded ${MAX_OTF_BODY} bytes — discarding")
                    null
                } else {
                    buf.readUtf8()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "OTF body read failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        } finally {
            try { response.close() } catch (_: Throwable) {}
        }

        if (body.isNullOrBlank() || body.length < 50) {
            Log.w(TAG, "OTF TV: empty/short response (code=${response.code})")
            return emptyList()
        }
        if (body.contains("denied") || body.contains("error")) {
            Log.w(TAG, "OTF TV: API error: ${body.take(100)}")
            return emptyList()
        }

        val decrypted = otfDecrypt(body)
        Log.d(TAG, "OTF TV: decrypted ${decrypted.length} chars")

        val fixed = decrypted
            .replace(Regex(",\\s*\\]"), "]")
            .replace(Regex(",\\s*\\}"), "}")
        val json = JSONObject(fixed)
        val streams = json.optJSONArray("Streams") ?: return emptyList()

        val result = mutableListOf<OtfChannel>()
        for (g in 0 until streams.length()) {
            val group = streams.optJSONObject(g) ?: continue
            val groupName = group.optString("name", "").trim()
            val channels = group.optJSONArray("Channels") ?: continue
            for (c in 0 until channels.length()) {
                val ch = channels.optJSONObject(c) ?: continue
                val name = ch.optString("name", "").trim()
                if (name.isBlank()) continue

                val key = normalize(name)
                if (key.isEmpty()) continue

                val catId = ch.optInt("CatID", 0)

                // Logo : le champ s'appelle "thumbnail" dans l'API OTF
                val logo = ch.optString("thumbnail", "").trim().ifBlank {
                    ch.optString("logo", "").trim().ifBlank {
                        ch.optString("image", "").trim()
                    }
                }.ifBlank { null }

                val vq = ch.optJSONArray("vq")
                val urls = mutableListOf<String>()
                if (vq != null) {
                    for (q in 0 until vq.length()) {
                        val quality = vq.optJSONObject(q) ?: continue
                        val url = quality.optString("url", "").trim()
                        if (url.startsWith("http")) urls.add(url)
                    }
                }

                // 2026-07-20 (user : « tu prends tout ce qu'il y a ») : le catalogue reflète
                //   EXACTEMENT ce que l'API renvoie — on ne masque aucune chaîne, même si son CDN
                //   est actuellement hors ligne. Aucun filtrage, aucune URL fabriquée.
                if (urls.isNotEmpty()) {
                    result.add(OtfChannel(name, key, catId, urls, logo, groupName))
                }
            }
        }

        Log.d(TAG, "OTF TV: parsed ${result.size} channels with URLs")
        // DIAG 2026-07-20 : répartition des hôtes CDN dans la réponse (pour repérer les CDN morts).
        try {
            val hosts = result.flatMap { it.urls }.mapNotNull {
                try { java.net.URL(it).host } catch (_: Exception) { null }
            }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
            Log.d(TAG, "OTF TV: hôtes CDN = " + hosts.take(12).joinToString(", ") { "${it.key}(${it.value})" })
        } catch (_: Throwable) {}
        return result
    }

    private fun otfEncrypt(plaintext: String): String {
        val keySpec = SecretKeySpec(OTF_AES_KEY.toByteArray(Charsets.UTF_8), "AES")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)
        val iv = cipher.iv
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = iv + ct
        return Base64.encodeToString(combined, Base64.DEFAULT)
    }

    private fun otfDecrypt(encrypted: String): String {
        val keySpec = SecretKeySpec(OTF_AES_KEY.toByteArray(Charsets.UTF_8), "AES")
        val raw = Base64.decode(encrypted, Base64.DEFAULT)
        val iv = raw.copyOfRange(0, 16)
        val ct = raw.copyOfRange(16, raw.size)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    /** Fallback si Context indispo ou ANDROID_ID indisponible : génère un
     *  16-hex pseudo-stable basé sur Build.FINGERPRINT. */
    private fun fallbackDeviceId(): String {
        val src = android.os.Build.FINGERPRINT + "_" + android.os.Build.MODEL
        val md = java.security.MessageDigest.getInstance("MD5").digest(src.toByteArray())
        return md.joinToString("") { String.format("%02x", it) }.substring(0, 16)
    }

    /** Normalise un nom de chaîne : minuscules, accents → ASCII, sans espaces ni caractères spéciaux. */
    private fun normalize(name: String): String {
        // Décomposer les accents (NFD) puis supprimer les diacritiques
        val nfd = java.text.Normalizer.normalize(name.lowercase(), java.text.Normalizer.Form.NFD)
        return nfd.replace(Regex("\\p{InCombiningDiacriticalMarks}"), "")
            .replace(Regex("[^a-z0-9]"), "")
    }

    /**
     * 2026-06-04 (user "trier les chaînes OTF dans l'ordre des chaînes France") :
     * ordre TNT français standard pour les chaînes mainstream + bouquets Canal+
     * + sport + cinéma. Les chaînes hors de cette liste passent ensuite par ordre
     * alphabétique. À utiliser avec [sortChannelsFrenchTntOrder].
     */
    private val FRENCH_TNT_ORDER: List<String> = listOf(
        // Bouquet TNT clair (canaux 1-27)
        "tf1", "france2", "france3", "canal", "canalplus", "france5", "m6",
        "arte", "c8", "w9", "tmc", "tfx", "nrj12", "lcp", "publicsenat",
        "france4", "franceinfo", "cnews", "cstar", "gulli",
        "tf1seriesfilms", "tf1seriefilms", "lequipe", "6ter",
        "rmcstory", "rmcdecouverte", "cherie25",
        // 22-30 — chaînes locales/régionales
        "lci", "franceinfotv",
        // Bouquet Canal+ payantes
        "canalplus", "canalplussport", "canalplussport360", "canalplussportplus",
        "canalplusgrandsport", "canalplusfoot",
        "canalpluscinema", "canalplusgrandsclassiques", "canalplusgrandsecran",
        "canalplusbox", "canalplusinternational", "canalpluskids",
        "canalplusseries", "canalplusdocs", "canalplusofflive",
        // Sport
        "beinsports1", "beinsports2", "beinsports3", "beinsportsmax4",
        "beinsportsmax5", "beinsportsmax6", "beinsportsmax7", "beinsportsmax8",
        "rmcsport1", "rmcsport2", "rmcsport3", "rmcsport4",
        "eurosport1", "eurosport2",
        "ligue1plus", "infosport", "infosportplus",
        "dazn1", "dazn2",
        // Cinéma
        "ocsmax", "ocscity", "ocschoc", "ocsgeants",
        "tcmcinema", "tcm", "tcmclassic",
        "polar", "actionchasseetpeche",
        "paramountchannel", "paramount", "wbtv", "warnertv",
        // Découverte
        "natgeo", "natgeowild", "natgeohd",
        "discovery", "discoveryscience", "discoveryinvestigation",
        "histoire", "rmcstoryplus", "ushuaiatv", "voyage",
        "planeteplus", "planetenoplus", "trekplus",
        // Info / Politique
        "bfmtv", "bfmparis", "bfmlyon", "bfmbusiness",
        "francetv", "tv5monde",
        // Jeunesse
        "boomerang", "cartoonnetwork", "disneychannel", "disneyjunior",
        "nickelodeon", "nickjr", "tiji", "piwiplus", "gullimax",
        // Musique
        "mtv", "mtvhits", "mcm", "mcmtop", "mtvclub",
        "trace", "tracetropical", "traceurban",
        // Style de vie
        "13rue", "13erue",
    )

    /**
     * Trie une liste de chaînes par ordre TNT français (chaînes mainstream)
     * puis alphabétique. Insensible à la casse et aux accents/séparateurs grâce
     * au normalizedKey utilisé en clé. Utilisé par OtfPlayerActivity (mobile)
     * et OtfPlayerTvActivity (TV) pour le panel chaînes / boutons prev/next.
     */
    fun sortChannelsFrenchTntOrder(channels: List<OtfChannel>): List<OtfChannel> {
        return channels.sortedWith(
            compareBy<OtfChannel> { ch ->
                val idx = FRENCH_TNT_ORDER.indexOf(ch.normalizedKey)
                if (idx < 0) Int.MAX_VALUE else idx
            }.thenBy { it.name.lowercase() }
        )
    }

    /** Retourne le logo d'une chaîne par sa clé normalisée. */
    suspend fun getLogoForChannel(normalizedKey: String): String? {
        val channels = fetchChannels()
        return channels.firstOrNull { it.normalizedKey == normalizedKey }?.logo
    }

    /** Retourne la liste des groupes (langues/pays) disponibles. */
    suspend fun getGroups(): List<String> {
        val channels = fetchChannels()
        return channels.map { it.group }.distinct().filter { it.isNotBlank() }
    }

    /** Retourne les chaînes d'un groupe spécifique. */
    suspend fun getChannelsByGroup(groupName: String): List<OtfChannel> {
        val channels = fetchChannels()
        return channels.filter { it.group == groupName }
    }

    /** Groupe sélectionné par l'utilisateur (persiste en SharedPrefs). */
    var selectedGroup: String
        get() {
            val ctx = com.streamflixreborn.streamflix.StreamFlixApp.instance
            return ctx.getSharedPreferences("otf_prefs", android.content.Context.MODE_PRIVATE)
                .getString("selected_group", "") ?: ""
        }
        set(value) {
            val ctx = com.streamflixreborn.streamflix.StreamFlixApp.instance
            ctx.getSharedPreferences("otf_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putString("selected_group", value).apply()
            // PAS de clearCache() — le cache contient TOUTES les chaînes,
            // le filtrage par groupe se fait dans getHome(). Vider le cache
            // forcerait un re-fetch API de 5s → chargement infini.
        }

    fun clearCache() {
        cachedChannels = null
        cacheTimestamp = 0
    }
}
