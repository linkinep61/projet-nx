package com.streamflixreborn.streamflix.utils

import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Tunnel Shadowsocks gratuit via les serveurs free VYPN pour débloquer Vavoo en
 * France métropolitaine (sans VPN externe).
 *
 * Architecture :
 *   App → SOCKS5 local (127.0.0.1:1080) → Shadowsocks AEAD AES-256-GCM →
 *     → VYPN free server (DE/CZ/DK/FI/NO) → Vavoo CDN → vrai contenu
 *
 * Validation 2026-06-17 (curl sandbox) :
 *   - tunnel vers 141.95.102.126:17000 (Frankfurt)
 *   - GET catalog France → 300+ chaînes
 *   - resolve TF1 → m3u8 sunshine CDN
 *   - fetch segment .ts = 3.16 Mbps de vrai TF1 live HD (= pas la pub VYPN)
 *
 * Le payload de notre VavooProvider envoie déjà `package=net.vypn.app` et
 * obtient un addonSig valide (même status:guest), suffisant pour que le sunshine
 * CDN serve le vrai contenu quand l'IP source est hors France.
 *
 * Protocole Shadowsocks AEAD (RFC-style, pas SS2022) :
 *   - Salt 32 bytes au début de chaque connexion (TCP)
 *   - Master key = MD5_KDF(password)
 *   - Session key = HKDF-SHA1(master_key, salt, "ss-subkey")
 *   - Chunks : [encrypted_length 2B][tag 16B] [encrypted_payload N] [tag 16B]
 *   - Nonce = 12 bytes little-endian counter (start 0, +1 par chunk)
 *
 * Note : tous les ciphers AEAD du protocole SS sont supportés par javax.crypto
 * via `AES_256_GCM`. Pas besoin de lib native.
 */
object VavooTunnel {
    private const val TAG = "VavooTunnel"
    private const val LOCAL_PORT = 1080  // SOCKS5 local port
    private const val SS_PORT_DEFAULT = 17000  // VYPN SS port

    // Config Shadowsocks récupérée du ping VYPN
    @Volatile private var ssServerIp: String? = null
    @Volatile private var ssServerPort: Int = SS_PORT_DEFAULT
    @Volatile private var ssPassword: String? = null
    @Volatile private var ssMethod: String = "aes-256-gcm"

    // État du tunnel
    private val running = AtomicBoolean(false)
    @Volatile private var lastSkipBestN: Int = 0
    @Volatile private var lastServerIp: String? = null

    /** Métadonnées d'un serveur free du pool VYPN (= pour le picker manuel). */
    data class ServerInfo(
        val ip: String,
        val port: Int,
        val country: String,
        val city: String,
        val load: Double,
        val friendly: Boolean
    ) {
        /** Texte court à afficher dans le picker UI. */
        fun displayLabel(): String {
            val pct = (load * 100).coerceIn(0.0, 100.0).toInt()
            val star = if (friendly) "★ " else ""
            return "$star$country / $city  (load $pct%)"
        }
    }
    private var localServerSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var acceptJob: Job? = null

    /** Indique si le tunnel SOCKS5 local est actuellement actif. */
    fun isRunning(): Boolean = running.get()

    /** Adresse du proxy SOCKS5 local à utiliser dans OkHttp. */
    fun localProxyAddress(): InetSocketAddress = InetSocketAddress("127.0.0.1", LOCAL_PORT)

    /** Override manuel — quand non-null, fetchVypnConfig ignore le tri auto et
     *  utilise cette IP. Set par `startWithServer()`, cleared par `start(skipBestN)`. */
    @Volatile private var forcedServerIp: String? = null
    @Volatile private var forcedServerPort: Int? = null

    /** IP du serveur actuellement utilisé (= forcé ou auto-sélectionné). */
    fun currentServerIp(): String? = lastServerIp

    /** Cache de la dernière liste parsée pour exposer au picker manuel. */
    @Volatile private var lastParsedPool: List<ServerInfo> = emptyList()

    /**
     * Récupère la liste de TOUS les serveurs free du pool VYPN, triés par
     * pertinence (= friendly d'abord puis load croissant). Cache au passage
     * password/method/port pour qu'un `startWithServer()` derrière n'ait pas
     * besoin de refaire le ping.
     *
     * Retourne une liste vide si le ping échoue. À appeler en IO dispatcher.
     */
    suspend fun listFreeServers(): List<ServerInfo> = withContext(Dispatchers.IO) {
        try {
            // 2026-06-18 : on lance un fetchVypnConfig (= effet de bord : cache
            //   password/method + sélectionne serveur courant), puis on
            //   retourne le pool parsé qu'il a stocké dans lastParsedPool.
            //   Si fetch échoue mais qu'on a un pool précédent en cache, on
            //   le sert (= meilleur que rien).
            val prevForcedIp = forcedServerIp
            val prevForcedPort = forcedServerPort
            // On éteint temporairement le forced pour que fetchVypnConfig
            //   re-parse le pool sans short-circuit. Restaure après.
            forcedServerIp = null
            forcedServerPort = null
            try {
                fetchVypnConfig(skipBestN = lastSkipBestN)
            } finally {
                forcedServerIp = prevForcedIp
                forcedServerPort = prevForcedPort
            }
            lastParsedPool
        } catch (e: Throwable) {
            Log.e(TAG, "listFreeServers failed: ${e.message}")
            lastParsedPool  // = empty list si jamais fetché
        }
    }

    /** Démarre le tunnel sur un serveur PRÉCIS (= choisi manuellement par
     *  l'utilisateur via le picker). Set forcedServerIp + démarre. */
    suspend fun startWithServer(server: ServerInfo): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "startWithServer: ${server.country}/${server.city} ${server.ip}:${server.port}")
        if (running.get()) {
            try { stop() } catch (_: Throwable) {}
        }
        forcedServerIp = server.ip
        forcedServerPort = server.port
        // start lit forcedServerIp via fetchVypnConfig
        start(skipBestN = 0, clearForcedOverride = false)
    }

    /**
     * Démarre le tunnel. Fetch la config VYPN si pas déjà fait, sélectionne le
     * meilleur serveur free, ouvre le SOCKS5 local. Idempotent : appel multiple
     * = no-op si déjà actif.
     *
     * Bloque jusqu'à ce que le tunnel soit prêt à accepter des connexions
     * (ou échoue). À appeler depuis un coroutine IO.
     *
     * 2026-06-17 v2 : try-catch défensif partout, on log mais on ne throw jamais
     *   (= retour Boolean). Aucune exception ne remonte au caller.
     */
    /**
     * @param skipBestN combien de serveurs "meilleurs par load" sauter avant
     *   de choisir. 0 = serveur primaire (= "Tunnel VPN"), 1+ = "VPN 2/3/…",
     *   utile pour éviter qu'un serveur saturé bloque tous les utilisateurs.
     */
    suspend fun start(skipBestN: Int = 0, clearForcedOverride: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        // 2026-06-18 : `clearForcedOverride=false` permet à startWithServer()
        //   de garder son override IP actif. Les autres appels (= auto)
        //   wipent l'override pour repasser en sélection auto.
        if (clearForcedOverride) {
            forcedServerIp = null
            forcedServerPort = null
        }
        if (running.get() && skipBestN == lastSkipBestN && forcedServerIp == null) {
            Log.d(TAG, "start: tunnel déjà actif sur même index ($skipBestN)")
            return@withContext true
        }
        if (running.get()) {
            Log.d(TAG, "start: restart pour nouveau serveur (skipBestN=$skipBestN, forced=$forcedServerIp)")
            try { stop() } catch (_: Throwable) {}
        }
        try {
            // Force re-fetch sauf si déjà bon ET pas d'override
            if (ssServerIp == null || ssPassword == null || skipBestN != lastSkipBestN || forcedServerIp != null) {
                val configOk = try { fetchVypnConfig(skipBestN) } catch (e: Throwable) {
                    Log.e(TAG, "fetchVypnConfig threw: ${e.message}", e); false
                }
                if (!configOk) {
                    Log.e(TAG, "Cannot start tunnel: VYPN config fetch failed")
                    return@withContext false
                }
            }
            val server = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("127.0.0.1", LOCAL_PORT))
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Cannot bind SOCKS5 port $LOCAL_PORT: ${e.message}", e)
                return@withContext false
            }
            localServerSocket = server
            running.set(true)
            acceptJob = scope.launch {
                Log.d(TAG, "SOCKS5 tunnel listening on 127.0.0.1:$LOCAL_PORT → $ssServerIp:$ssServerPort")
                while (running.get() && !server.isClosed) {
                    try {
                        val client = server.accept()
                        launch {
                            try { handleClient(client) } catch (e: Throwable) { Log.w(TAG, "handleClient threw: ${e.message}") }
                        }
                    } catch (e: Throwable) {
                        if (running.get()) Log.w(TAG, "accept loop: ${e.message}")
                    }
                }
            }
            true
        } catch (e: Throwable) {
            Log.e(TAG, "start failed: ${e.message}", e)
            running.set(false)
            false
        }
    }

    /** Arrête le tunnel proprement et libère le port local. */
    fun stop() {
        if (!running.getAndSet(false)) return
        try { localServerSocket?.close() } catch (_: Exception) {}
        localServerSocket = null
        acceptJob?.cancel()
        acceptJob = null
        Log.d(TAG, "tunnel stopped")
    }

    /**
     * Fetch la config Shadowsocks depuis VYPN /api/app/ping.
     * Sélectionne le serveur free avec la load la plus basse.
     */
    private fun fetchVypnConfig(skipBestN: Int = 0): Boolean {
        return try {
            // Payload de ping (= identique au VavooProvider.getAddonSignature)
            val now = System.currentTimeMillis()
            val payload = JSONObject().apply {
                put("token", "")
                put("reason", "app-focus")
                put("locale", "fr")
                put("theme", "dark")
                put("metadata", JSONObject().apply {
                    put("device", JSONObject().apply {
                        put("type", "phone")
                        put("uniqueId", "vypn-${android.os.Build.FINGERPRINT.hashCode().toUInt().toString(16)}")
                    })
                    put("os", JSONObject().apply {
                        put("name", "android")
                        put("version", android.os.Build.VERSION.RELEASE)
                        put("abis", org.json.JSONArray(listOf("arm64-v8a")))
                        put("host", "android")
                    })
                    put("app", JSONObject().apply { put("platform", "android") })
                    put("version", JSONObject().apply {
                        put("package", "net.vypn.app")
                        put("binary", "1.4.1")
                        put("js", "1.4.1")
                    })
                })
                put("appFocusTime", 0)
                put("playerActive", false)
                put("playDuration", 0)
                put("devMode", false)
                put("hasAddon", true)
                put("castConnected", false)
                put("package", "net.vypn.app")
                put("version", "1.4.1")
                put("process", "app")
                put("firstAppStart", now)
                put("lastAppStart", now)
                put("ipLocation", JSONObject.NULL)
                put("adblockEnabled", true)
                put("migrationApplied", false)
                put("migrationTargetInstalled", false)
                put("proxy", JSONObject().apply {
                    put("supported", org.json.JSONArray(listOf("ss")))
                    put("engine", "Mu")
                    put("ssVersion", "2022")
                    put("enabled", false)
                    put("autoServer", true)
                    put("id", "")
                })
                put("iap", JSONObject().apply {
                    put("supported", false)
                    put("error", "")
                })
            }
            // POST direct (PAS via le tunnel — ici on est en initialisation)
            val url = java.net.URL("https://www.vypn.net/api/app/ping")
            val conn = url.openConnection() as javax.net.ssl.HttpsURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val resp = JSONObject(body)
            val proxyObj = resp.optJSONObject("proxy") ?: run {
                Log.e(TAG, "ping: no proxy object")
                return false
            }
            val ss = proxyObj.optJSONObject("shadowsocks") ?: run {
                Log.e(TAG, "ping: no shadowsocks block")
                return false
            }
            ssPassword = ss.optString("password", "").takeIf { it.isNotEmpty() } ?: run {
                Log.e(TAG, "ping: no password")
                return false
            }
            ssServerPort = ss.optInt("port", SS_PORT_DEFAULT)
            ssMethod = ss.optString("method", "aes-256-gcm")

            // 2026-06-18 : on ramasse TOUS les serveurs free puis on les trie
            //   par pertinence Vavoo.
            //   2026-06-18 (suite — test live : Copenhagen DK timed out sur
            //     vavoo.to bien que load minimal) : Vavoo ralentit/bloque les
            //     serveurs hors zone EU-centrale. PRIORITÉ aux pays "amis" =
            //     DE / CZ / NL — ensuite tout le reste.
            //   ⚠️ JAMAIS la France dans la liste : Vavoo geoblock FR, sortir
            //     du tunnel en France annulerait le bénéfice du VPN.
            //   `skipBestN` permet à "VPN 2" de piocher le 2e meilleur de la
            //   liste TRIÉE (= 2e meilleur "ami" si on en a au moins 2).
            val locations = proxyObj.optJSONArray("locations") ?: return false
            data class Candidate(val ip: String, val load: Double, val label: String, val country: String, val friendly: Boolean)
            // 2026-06-18 (logs live OPPO 09:15) : pool VYPN free actuel = 7
            //   serveurs, AUCUN DE/CZ/NL. NO/Oslo validé chez l'user
            //   ("j'ai basculé sur un bon serveur" = Oslo). DK/Copenhagen
            //   timed out chez lui → on garde DE/CZ/NL en friendly pour
            //   plus tard (= si VYPN les rajoute) + NO pour aujourd'hui.
            //   On EXCLUT : DK (testé KO), FR (geoblock Vavoo).
            // VYPN renvoie le pays comme code ISO 2 lettres ("DE", "CZ"...)
            //   et pas le nom complet. Donc on accepte les 2 formes.
            val FRIENDLY_COUNTRIES = setOf(
                "germany", "deutschland", "de",
                "czech republic", "czechia", "cz",
                "netherlands", "holland", "nl",
                "norway", "norge", "no"
            )
            val candidates = mutableListOf<Candidate>()
            for (i in 0 until locations.length()) {
                val country = locations.optJSONObject(i) ?: continue
                if (!country.optBoolean("free", false)) continue
                val countryName = country.optString("name")
                val isFriendly = FRIENDLY_COUNTRIES.contains(countryName.trim().lowercase())
                val cities = country.optJSONArray("children") ?: continue
                for (j in 0 until cities.length()) {
                    val city = cities.optJSONObject(j) ?: continue
                    val servers = city.optJSONArray("children") ?: continue
                    for (k in 0 until servers.length()) {
                        val srv = servers.optJSONObject(k) ?: continue
                        if (!srv.optBoolean("free", false)) continue
                        val ip = srv.optString("ip", "").takeIf { it.isNotEmpty() } ?: continue
                        val load = srv.optDouble("load", 1.0)
                        candidates += Candidate(ip, load,
                            "${countryName}/${city.optString("name")}",
                            countryName, isFriendly)
                    }
                }
            }
            if (candidates.isEmpty()) {
                Log.e(TAG, "no free server found")
                return false
            }
            // Tri 2-niveaux : friendly d'abord, puis load croissant
            candidates.sortWith(compareByDescending<Candidate> { it.friendly }.thenBy { it.load })
            // 2026-06-18 : cache pour listFreeServers() (= picker manuel).
            lastParsedPool = candidates.map { c ->
                val cityName = c.label.substringAfter("/")
                ServerInfo(
                    ip = c.ip,
                    port = ssServerPort,
                    country = c.country,
                    city = cityName,
                    load = c.load,
                    friendly = c.friendly
                )
            }
            val nFriendly = candidates.count { it.friendly }
            Log.d(TAG, "Pool VYPN : ${candidates.size} serveurs free dont $nFriendly amis (DE/CZ/NL/NO)")
            for ((idx, c) in candidates.withIndex()) {
                Log.d(TAG, "  [$idx] ${if (c.friendly) "★" else " "} ${c.label} ${c.ip} load=${c.load}")
            }
            // 2026-06-18 : si override manuel (= picker "VPN 2 → choisir
            //   serveur") on bypass la sélection auto.
            val forced = forcedServerIp
            if (forced != null) {
                val match = candidates.firstOrNull { it.ip == forced }
                ssServerIp = forced
                forcedServerPort?.let { ssServerPort = it }
                lastSkipBestN = skipBestN
                lastServerIp = forced
                Log.i(TAG, "✓ VYPN config OK (forcé manuel): ${match?.label ?: "??"} $forced:$ssServerPort")
                return true
            }
            val pickIdx = skipBestN.coerceIn(0, candidates.size - 1)
            val chosen = candidates[pickIdx]
            ssServerIp = chosen.ip
            lastSkipBestN = skipBestN
            lastServerIp = chosen.ip
            Log.i(TAG, "✓ VYPN config OK [skip=$skipBestN/${candidates.size}] ${if (chosen.friendly) "★" else "(non-friendly)"}: " +
                "${chosen.label} ${chosen.ip}:$ssServerPort (load=${chosen.load}, method=$ssMethod)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "fetchVypnConfig: ${e.message}", e)
            false
        }
    }

    // ───────── Handler client SOCKS5 ─────────

    /**
     * 2026-06-17 : helper compatible toutes versions Android (vs readNBytes qui
     * n'est dispo qu'à partir d'API 33 / Android 13). Lit EXACTEMENT n bytes
     * ou throw EOFException si EOF avant.
     */
    private fun java.io.InputStream.readExactly(n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = this.read(out, off, n - off)
            if (r < 0) throw java.io.EOFException("readExactly: EOF after $off/$n bytes")
            off += r
        }
        return out
    }

    private suspend fun handleClient(client: Socket) {
        try {
            client.soTimeout = 30_000
            val cin = client.getInputStream()
            val cout = client.getOutputStream()

            // SOCKS5 handshake step 1 : VER + NMETHODS + METHODS
            val greeting = cin.readExactly(2)
            if (greeting[0] != 0x05.toByte()) { client.close(); return }
            val nmethods = greeting[1].toInt() and 0xFF
            if (nmethods > 0) cin.readExactly(nmethods)  // discard
            cout.write(byteArrayOf(0x05, 0x00))  // NO AUTH
            cout.flush()

            // SOCKS5 request : VER CMD RSV ATYP DST.ADDR DST.PORT
            val header = cin.readExactly(4)
            if (header[0] != 0x05.toByte() || header[1] != 0x01.toByte()) {
                cout.write(byteArrayOf(0x05, 0x07, 0, 0x01, 0, 0, 0, 0, 0, 0)); client.close(); return
            }
            val atyp = header[3].toInt() and 0xFF
            val targetAddrBytes: ByteArray
            val targetHostStr: String
            when (atyp) {
                0x01 -> { // IPv4
                    val ip = cin.readExactly(4)
                    targetAddrBytes = byteArrayOf(0x01) + ip
                    targetHostStr = ip.joinToString(".") { (it.toInt() and 0xFF).toString() }
                }
                0x03 -> { // domain
                    val len = (cin.read() and 0xFF)
                    val name = cin.readExactly(len)
                    targetAddrBytes = byteArrayOf(0x03, len.toByte()) + name
                    targetHostStr = String(name, Charsets.US_ASCII)
                }
                0x04 -> { // IPv6
                    val ip = cin.readExactly(16)
                    targetAddrBytes = byteArrayOf(0x04) + ip
                    targetHostStr = "[ipv6]"
                }
                else -> { client.close(); return }
            }
            val portBytes = cin.readExactly(2)
            val targetAddrPayload = targetAddrBytes + portBytes
            val port = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
            Log.v(TAG, "SOCKS5 → $targetHostStr:$port")

            // Reply SUCCESS to client
            cout.write(byteArrayOf(0x05, 0x00, 0, 0x01, 0, 0, 0, 0, 0, 0))
            cout.flush()

            // Open Shadowsocks connection
            val ssIp = ssServerIp ?: run { client.close(); return }
            val pwd = ssPassword ?: run { client.close(); return }
            val ssSocket = Socket()
            ssSocket.soTimeout = 30_000
            ssSocket.connect(InetSocketAddress(ssIp, ssServerPort), 10_000)

            // Encrypt + send SS header (= ATYP+ADDR+PORT) on output stream
            val ssOut = ssSocket.getOutputStream()
            val ssIn = ssSocket.getInputStream()

            val outCipher = ShadowsocksAead(pwd, isEncrypt = true)
            // Send salt + 1st encrypted chunk (the target addr)
            outCipher.writeInitialSalt(ssOut)
            outCipher.encryptAndWriteChunk(ssOut, targetAddrPayload)
            ssOut.flush()

            val inCipher = ShadowsocksAead(pwd, isEncrypt = false)
            // 2026-06-17 FIX DEADLOCK : readInitialSalt DOIT être dans le launch
            //   parallèle, PAS séquentiel après writeInitialSalt. Sinon : on bloque
            //   en attendant le salt du serveur, mais le serveur ne renvoie son
            //   salt qu'APRÈS avoir reçu data du target (= vypn.net). Le target
            //   attend un TLS ClientHello d'OkHttp dans `cin`. Mais on ne lit
            //   pas `cin` car on est bloqué sur readInitialSalt → deadlock.

            // Pipe bidirectionnel — chaque sens en parallèle
            coroutineScope {
                launch {
                    // client → SS server (encrypt + forward)
                    try {
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = cin.read(buf)
                            if (n <= 0) break
                            outCipher.encryptAndWriteChunk(ssOut, buf.copyOf(n))
                            ssOut.flush()
                        }
                    } catch (_: Exception) {}
                }
                launch {
                    // SS server → client (read salt + decrypt + forward)
                    try {
                        inCipher.readInitialSalt(ssIn)  // 32B salt — peut bloquer mais en parallèle
                        while (true) {
                            val chunk = inCipher.readAndDecryptChunk(ssIn) ?: break
                            cout.write(chunk)
                            cout.flush()
                        }
                    } catch (_: Exception) {}
                }
            }
            try { ssSocket.close() } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.v(TAG, "handleClient: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }
}

/**
 * Mini Shadowsocks AEAD AES-256-GCM stream codec — 1 instance par sens
 * (encrypt OU decrypt). Pas thread-safe.
 *
 * Spec: https://shadowsocks.org/doc/aead.html
 *   - Key derivation : MD5_KDF(password) → 32B master key
 *   - Session : salt (32B random) + HKDF-SHA1(master, salt, "ss-subkey") → 32B session key
 *   - Chunks : [enc_length(2B)+tag(16B)] then [enc_payload(N)+tag(16B)]
 *   - Nonce : 12 bytes little-endian counter, +1 par chunk, séparé pour length et payload
 */
private class ShadowsocksAead(password: String, val isEncrypt: Boolean) {
    companion object {
        const val SALT_LEN = 32
        const val KEY_LEN = 32
        const val NONCE_LEN = 12
        const val TAG_LEN = 16
        const val MAX_CHUNK = 0x3FFF  // 14 bits — Shadowsocks AEAD chunk size limit
    }

    private val masterKey: ByteArray = deriveMasterKey(password, KEY_LEN)
    private lateinit var sessionKey: ByteArray
    private val nonce = ByteArray(NONCE_LEN)  // counter little-endian

    /** Encrypt mode : write salt (32B) on stream, derive session key. */
    fun writeInitialSalt(out: java.io.OutputStream) {
        val salt = ByteArray(SALT_LEN).also { java.security.SecureRandom().nextBytes(it) }
        out.write(salt)
        sessionKey = hkdfSha1(masterKey, salt, "ss-subkey".toByteArray(Charsets.US_ASCII), KEY_LEN)
    }

    /** Decrypt mode : read salt (32B) from stream, derive session key. */
    fun readInitialSalt(input: java.io.InputStream) {
        // 2026-06-17 : readExactly compatible toutes versions Android (≠ readNBytes API 33+)
        val salt = ByteArray(SALT_LEN)
        var off = 0
        while (off < SALT_LEN) {
            val r = input.read(salt, off, SALT_LEN - off)
            if (r < 0) throw java.io.IOException("salt read incomplete: $off/$SALT_LEN")
            off += r
        }
        sessionKey = hkdfSha1(masterKey, salt, "ss-subkey".toByteArray(Charsets.US_ASCII), KEY_LEN)
    }

    /** Encrypt + send a chunk (max 0x3FFF bytes per call — segment if larger). */
    fun encryptAndWriteChunk(out: java.io.OutputStream, payload: ByteArray) {
        var off = 0
        while (off < payload.size) {
            val len = minOf(MAX_CHUNK, payload.size - off)
            // Length (2B BE) → encrypt
            val lenBytes = byteArrayOf((len ushr 8).toByte(), (len and 0xFF).toByte())
            val encLen = aeadSeal(lenBytes, sessionKey, nonce)
            incrementNonce()
            // Payload → encrypt
            val encPayload = aeadSeal(payload.copyOfRange(off, off + len), sessionKey, nonce)
            incrementNonce()
            out.write(encLen)
            out.write(encPayload)
            off += len
        }
    }

    /** Read + decrypt next chunk. Returns null on EOF. */
    fun readAndDecryptChunk(input: java.io.InputStream): ByteArray? {
        // 2026-06-17 : helper readExactly inline (compat toutes versions Android).
        fun readExact(n: Int): ByteArray? {
            val out = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(out, off, n - off)
                if (r < 0) return null  // EOF
                off += r
            }
            return out
        }
        // length chunk : 2B+TAG
        val lenChunk = readExact(2 + TAG_LEN) ?: return null
        val lenBytes = aeadOpen(lenChunk, sessionKey, nonce) ?: throw java.io.IOException("length decrypt failed")
        incrementNonce()
        val len = ((lenBytes[0].toInt() and 0xFF) shl 8) or (lenBytes[1].toInt() and 0xFF)
        if (len <= 0 || len > MAX_CHUNK) throw java.io.IOException("invalid chunk len $len")
        // payload chunk
        val payloadChunk = readExact(len + TAG_LEN) ?: return null
        val payload = aeadOpen(payloadChunk, sessionKey, nonce) ?: throw java.io.IOException("payload decrypt failed")
        incrementNonce()
        return payload
    }

    private fun incrementNonce() {
        for (i in nonce.indices) {
            nonce[i] = ((nonce[i].toInt() and 0xFF) + 1).toByte()
            if (nonce[i].toInt() and 0xFF != 0) return
        }
    }

    /** AES-256-GCM seal : encrypted + tag (16B) concaténés. */
    private fun aeadSeal(plain: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LEN * 8, nonce))
        return cipher.doFinal(plain)
    }

    /** AES-256-GCM open : returns null on auth fail. */
    private fun aeadOpen(cipherWithTag: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray? {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LEN * 8, nonce))
            cipher.doFinal(cipherWithTag)
        } catch (_: Exception) { null }
    }

    /** Shadowsocks MD5-based KDF: concat MD5(password), MD5(prev+password), … until ≥ outLen. */
    private fun deriveMasterKey(password: String, outLen: Int): ByteArray {
        val pwdBytes = password.toByteArray(Charsets.UTF_8)
        val md5 = MessageDigest.getInstance("MD5")
        var prev = ByteArray(0)
        val result = ByteArray(outLen)
        var pos = 0
        while (pos < outLen) {
            md5.reset()
            md5.update(prev)
            md5.update(pwdBytes)
            prev = md5.digest()
            val take = minOf(prev.size, outLen - pos)
            System.arraycopy(prev, 0, result, pos, take)
            pos += take
        }
        return result
    }

    /** HKDF-SHA1 (RFC 5869) — 1-round suffisant pour outLen ≤ 20 bytes ; on étend ici à 32B. */
    private fun hkdfSha1(ikm: ByteArray, salt: ByteArray, info: ByteArray, outLen: Int): ByteArray {
        // PRK = HMAC-SHA1(salt, ikm)
        val prkMac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(salt, "HmacSHA1")) }
        val prk = prkMac.doFinal(ikm)
        // OKM = T(1) | T(2) | … ; T(i) = HMAC-SHA1(prk, T(i-1) | info | i)
        val out = ByteArray(outLen)
        var pos = 0
        var prev = ByteArray(0)
        var counter = 1
        while (pos < outLen) {
            val expandMac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(prk, "HmacSHA1")) }
            expandMac.update(prev)
            expandMac.update(info)
            expandMac.update(counter.toByte())
            prev = expandMac.doFinal()
            val take = minOf(prev.size, outLen - pos)
            System.arraycopy(prev, 0, out, pos, take)
            pos += take
            counter++
        }
        return out
    }
}
