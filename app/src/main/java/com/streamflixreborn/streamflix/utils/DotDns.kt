package com.streamflixreborn.streamflix.utils

import android.util.Log
import okhttp3.Dns
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.util.Random
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * DNS-over-TLS (DoT, RFC 7858) — 2026-07-29.
 *
 * POURQUOI : le DNS privé d'Android utilise **DoT** (port 853) avec un simple
 * hostname (ex. `ns0.fdn.fr`). Beaucoup de resolvers non-filtrants (FDN, Mullvad,
 * dns.sb, DNSForge clean…) n'ont **PAS** de endpoint DoH → notre ancienne liste
 * "https://ns0.fdn.fr/dns-query" échouait silencieusement (aucun serveur DoH là-bas),
 * l'app retombait sur le DNS système du FAI (donc filtré). Cette classe parle DoT
 * directement, comme le réglage "DNS privé" du téléphone.
 *
 * @param serverHost hostname du serveur DoT (SNI + vérification du certificat)
 * @param bootstrapIps IPs littérales du serveur → on se connecte SANS résoudre
 *   serverHost via le DNS système (sinon un DNS FAI cassé ferait tout tomber).
 */
class DotDns(
    private val serverHost: String,
    private val bootstrapIps: List<String>
) : Dns {

    private val port = 853
    private val rnd = Random()

    override fun lookup(hostname: String): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        // A (IPv4) d'abord — suffisant pour ExoPlayer/OkHttp dans 99% des cas.
        runCatching { out.addAll(query(hostname, TYPE_A)) }
            .onFailure { Log.d(TAG, "DoT A($hostname) via $serverHost KO: ${it.message}") }
        // AAAA seulement si aucune IPv4 (évite un 2e handshake TLS quand inutile).
        if (out.isEmpty()) {
            runCatching { out.addAll(query(hostname, TYPE_AAAA)) }
                .onFailure { Log.d(TAG, "DoT AAAA($hostname) via $serverHost KO: ${it.message}") }
        }
        if (out.isEmpty()) throw UnknownHostException("DoT: aucune adresse pour $hostname via $serverHost")
        return out
    }

    /** InetAddress du serveur DoT : littéral bootstrap + hostname attaché (pour le cert). */
    private fun serverAddress(): InetAddress {
        for (ip in bootstrapIps) {
            val bytes = runCatching { InetAddress.getByName(ip).address }.getOrNull() ?: continue
            runCatching { return InetAddress.getByAddress(serverHost, bytes) }
        }
        // Dernier recours : résoudre le host via le système (peut être bloqué, mais on tente).
        return InetAddress.getByName(serverHost)
    }

    private fun query(hostname: String, type: Int): List<InetAddress> {
        val id = rnd.nextInt(0xFFFF)
        val queryBytes = buildQuery(id, hostname, type)
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val serverAddr = serverAddress()
        (factory.createSocket() as SSLSocket).use { socket ->
            socket.soTimeout = 7000
            socket.connect(InetSocketAddress(serverAddr, port), 7000)
            // SNI + vérification stricte du certificat contre serverHost.
            val params = socket.sslParameters
            params.serverNames = listOf(SNIHostName(serverHost))
            params.endpointIdentificationAlgorithm = "HTTPS"
            socket.sslParameters = params
            socket.startHandshake()

            val dos = DataOutputStream(socket.outputStream)
            dos.writeShort(queryBytes.size) // préfixe longueur 2 octets (DoT = DNS/TCP)
            dos.write(queryBytes)
            dos.flush()

            val dis = DataInputStream(socket.inputStream)
            val len = dis.readUnsignedShort()
            if (len <= 0 || len > 8192) throw UnknownHostException("DoT: réponse invalide ($len)")
            val resp = ByteArray(len)
            dis.readFully(resp)
            return parseAnswers(resp, type)
        }
    }

    /** Construit un message DNS wire-format (1 question, RD=1). */
    private fun buildQuery(id: Int, hostname: String, type: Int): ByteArray {
        val header = ByteArray(12)
        header[0] = (id ushr 8).toByte(); header[1] = id.toByte()
        header[2] = 0x01; header[3] = 0x00           // flags: RD=1
        header[4] = 0x00; header[5] = 0x01           // QDCOUNT=1
        // ANCOUNT/NSCOUNT/ARCOUNT = 0
        val qname = ArrayList<Byte>()
        for (label in hostname.trim('.').split('.')) {
            val b = label.toByteArray(Charsets.UTF_8)
            qname.add(b.size.toByte())
            qname.addAll(b.toList())
        }
        qname.add(0) // fin du nom
        val tail = ByteArray(4)
        tail[0] = (type ushr 8).toByte(); tail[1] = type.toByte() // QTYPE
        tail[2] = 0x00; tail[3] = 0x01                             // QCLASS=IN
        return header + qname.toByteArray() + tail
    }

    /** Parse les réponses ; renvoie les A ou AAAA selon `wantType`. */
    private fun parseAnswers(msg: ByteArray, wantType: Int): List<InetAddress> {
        val result = ArrayList<InetAddress>()
        if (msg.size < 12) return result
        val qd = ((msg[4].toInt() and 0xFF) shl 8) or (msg[5].toInt() and 0xFF)
        val an = ((msg[6].toInt() and 0xFF) shl 8) or (msg[7].toInt() and 0xFF)
        var pos = 12
        // Sauter les questions
        repeat(qd) {
            pos = skipName(msg, pos)
            pos += 4 // QTYPE + QCLASS
        }
        // Lire les réponses
        repeat(an) {
            if (pos >= msg.size) return@repeat
            pos = skipName(msg, pos)
            if (pos + 10 > msg.size) return@repeat
            val type = ((msg[pos].toInt() and 0xFF) shl 8) or (msg[pos + 1].toInt() and 0xFF)
            val rdLen = ((msg[pos + 8].toInt() and 0xFF) shl 8) or (msg[pos + 9].toInt() and 0xFF)
            val rdStart = pos + 10
            if (rdStart + rdLen > msg.size) return@repeat
            if (type == wantType && (rdLen == 4 || rdLen == 16)) {
                val addr = msg.copyOfRange(rdStart, rdStart + rdLen)
                runCatching { result.add(InetAddress.getByAddress(addr)) }
            }
            pos = rdStart + rdLen
        }
        return result
    }

    /** Avance après un nom DNS (gère la compression 0xC0). */
    private fun skipName(msg: ByteArray, start: Int): Int {
        var pos = start
        while (pos < msg.size) {
            val len = msg[pos].toInt() and 0xFF
            when {
                len == 0 -> return pos + 1
                len and 0xC0 == 0xC0 -> return pos + 2 // pointeur = 2 octets, fin du nom
                else -> pos += 1 + len
            }
        }
        return pos
    }

    companion object {
        private const val TAG = "DnsResolver"
        private const val TYPE_A = 1
        private const val TYPE_AAAA = 28

        /** IPs bootstrap connues des serveurs DoT non-filtrants proposés dans l'app
         *  (source : sebsauvage.net/wiki dns-alternatifs). Évite de résoudre le host
         *  du resolver via le DNS FAI (qui peut être justement celui qu'on fuit). */
        fun bootstrapFor(host: String): List<String> = when {
            host.equals("ns0.fdn.fr", true) -> listOf("80.67.169.12", "2001:910:800::12")
            host.equals("ns1.fdn.fr", true) -> listOf("80.67.169.40", "2001:910:800::40")
            host.contains("adblock.dns.mullvad", true) -> listOf("194.242.2.3", "2a07:e340::3")
            host.contains("mullvad", true) -> listOf("194.242.2.2", "2a07:e340::2")
            host.equals("dot.sb", true) || host.contains("dns.sb", true) ->
                listOf("185.222.222.222", "45.11.45.11", "2a09::", "2a11::")
            host.contains("dns4all", true) ->
                listOf("194.0.5.3", "194.0.5.64", "2001:678:8::3", "2001:678:8::64")
            host.contains("libredns", true) ->
                listOf("88.198.92.222", "192.71.166.92", "2a01:4f8:1c0c:82c0::1")
            host.contains("controld", true) ->
                listOf("76.76.2.0", "76.76.10.0", "2606:1a40::", "2606:1a40:1::")
            host.contains("unfiltered.adguard", true) ->
                listOf("94.140.14.140", "94.140.14.141", "2a10:50c0::1:ff", "2a10:50c0::2:ff")
            host.contains("adguard", true) ->
                listOf("94.140.14.14", "94.140.15.15", "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff")
            host.contains("dnsforge", true) ->
                listOf("176.9.93.198", "176.9.1.117", "2a01:4f8:151:34aa::198", "2a01:4f8:141:316d::117")
            else -> emptyList()
        }
    }
}
