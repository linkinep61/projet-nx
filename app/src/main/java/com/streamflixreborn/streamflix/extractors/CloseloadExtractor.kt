package com.streamflixreborn.streamflix.extractors

import android.util.Base64
import androidx.media3.common.MimeTypes
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.JsUnpacker
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url

class CloseloadExtractor : Extractor() {

    override val name = "Closeload"
    override val mainUrl = "https://closeload.top/"

    // 2026-09-19 (repris de streamflix-reborn2, commit 840c5b9) : miroir servi par Ridomovies.
    override val aliasUrls = listOf("https://ridorapid.closeload.top/")

    override suspend fun extract(link: String): Video {
        val retrofit = Retrofit.Builder()
            .baseUrl(mainUrl)
            .client(Extractor.sharedClient)
            .addConverterFactory(JsoupConverterFactory.create())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        val service = retrofit.create(Service::class.java)
        val document = service.get(link, "https://ridomovies.tv/")
        val html = document.toString()
        
        val unpacker = JsUnpacker(html)
        val unpacked = if (unpacker.detect()) unpacker.unpack() ?: html else html
        
        // --- 1. DYNAMIC PARAMETER DETECTION ---
        var magicNum = 399756995L
        var offset = 5
        val matchConst = Regex("""(\d+)\s*%\s*\(\s*i\s*\+\s*(\d+)\s*\)""").find(unpacked)
        if (matchConst != null) {
            magicNum = matchConst.groupValues[1].toLong()
            offset = matchConst.groupValues[2].toInt()
        }

        // --- 2. CANDIDATE COLLECTION ---
        val inputs = mutableListOf<String>()

        // A. DC Hello Pattern
        val varNameMatch = Regex("""myPlayer\.src\(\{\s*src:\s*(\w+)\s*,""").find(unpacked)
        if (varNameMatch != null) {
            val varName = varNameMatch.groupValues[1]
            val dcHelloMatch = Regex("""var\s+$varName\s*=\s*dc_hello\("([^"]+)"\)""").find(unpacked)
            if (dcHelloMatch != null) {
                inputs.add(dcHelloMatch.groupValues[1])
            }
        }

        // B. Arrays of strings
        Regex("""\[\s*((?:"[^"]+",?\s*)+)\]""").findAll(unpacked).forEach { match ->
            val parts = Regex("\"([^\"]+)\"").findAll(match.groupValues[1]).map { it.groupValues[1] }.toList()
            if (parts.size > 5) {
                inputs.add(parts.joinToString(""))
            }
        }

        // C. Long strings in function calls
        Regex("""\(\s*"([a-zA-Z0-9+/=]{30,})"\s*\)""").findAll(unpacked).forEach { match ->
            inputs.add(match.groupValues[1])
        }

        // --- 3. EXECUTE BRUTE FORCE ---
        // Try to find the URL in gathered inputs using smart brute force
        var source = inputs.firstNotNullOfOrNull { smartBruteForce(it, magicNum, offset) }

        // D. Fallback: Search for Pure Base64 strings if nothing else worked
        if (source == null) {
             source = Regex("[\"'](aHR0[a-zA-Z0-9+/=]{20,})[\"']").findAll(unpacked)
                .mapNotNull { safeBase64Decode(it.groupValues[1]) }
                .map { String(it, Charsets.UTF_8) }
                .firstOrNull { it.startsWith("http") }
        }

        // E. 2026-09-19 — NOUVELLE FABRICATION DE CLOSELOAD (repris de streamflix-reborn2,
        //    commit 840c5b9). Le site ne cache plus l'URL derrière `dc_hello` et un simple
        //    décalage : il publie une fonction `dc_<aléatoire>(value_parts)` dont la suite
        //    d'opérations (atob / reverse / rotation) CHANGE d'une page à l'autre, puis
        //    mélange les octets avec un accumulateur. La force brute ci-dessus ne peut donc
        //    plus tomber juste. On lit ici la fonction elle-même et on rejoue ses opérations
        //    dans l'ordre. Ajouté EN DERNIER : l'ancien chemin reste prioritaire, rien de ce
        //    qui marche aujourd'hui ne change.
        if (source == null) {
            val parFonction = extraireParFonctionDc(html)
            if (parFonction != null) {
                // Le CDN vérifie le référent de la page qui l'a servi (closeload OU ridorapid).
                val u = android.net.Uri.parse(link)
                val referer = "${u.scheme}://${u.host}/"
                return Video(
                    parFonction,
                    headers = mapOf("Referer" to referer),
                    type = MimeTypes.APPLICATION_M3U8,
                )
            }
        }

        if (source == null) throw Exception("No video found")

        return Video(source, headers = mapOf("Referer" to mainUrl), type = MimeTypes.APPLICATION_M3U8)
    }

    /**
     * Lit la fonction de déchiffrement publiée dans la page et la rejoue.
     *
     * 1. chaque bloc `eval(function(p,a,c,k,e…))` est dépaqueté et ajouté au texte cherché ;
     * 2. on repère `function dc_xxx(value_parts) { … return unmix }` ;
     * 3. on relève, DANS L'ORDRE, ses opérations : `atob(`, `reverse()`, rotation `%26` ;
     * 4. on relève les deux constantes de la boucle de mélange (`acc`) ;
     * 5. pour chaque appel `dc_xxx([ "…", "…" ])`, on recolle les morceaux, on applique les
     *    opérations, puis on démêle : `acc = (acc+pas)%256`, `clair = octet XOR acc`,
     *    `acc = (acc+octet)%256`.
     *
     * Renvoie null (sans jamais lever) si la page n'est pas de cette génération.
     */
    private fun extraireParFonctionDc(html: String): String? = try {
        var texte = html
        Regex("""eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e""")
            .findAll(html).forEach { m ->
                val fin = (m.range.first + 5000).coerceAtMost(html.length)
                val morceau = html.substring(m.range.first, fin)
                val up = JsUnpacker(morceau)
                if (up.detect()) up.unpack()?.let { texte += "\n" + it }
            }

        val fonction = Regex(
            """function\s+(dc_[a-zA-Z0-9_]+)\(value_parts\)\s*\{(.*?return unmix;?)\s*\}""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(texte)

        if (fonction == null) null else {
            val nom = fonction.groupValues[1]
            val corps = fonction.groupValues[2]

            val operations = mutableListOf<Pair<String, Int?>>()
            Regex(
                """(atob\()|(reverse\(\))|(replace\(\/\[a-zA-Z\]\/g.*?o\s*-\s*base\s*\+\s*(\d+)\s*\)\s*%\s*26)""",
                RegexOption.DOT_MATCHES_ALL,
            ).findAll(corps).forEach { m ->
                when {
                    m.groupValues[1].isNotEmpty() -> operations.add("atob" to null)
                    m.groupValues[2].isNotEmpty() -> operations.add("reverse" to null)
                    m.groupValues[3].isNotEmpty() -> operations.add("rot" to m.groupValues[4].toInt())
                }
            }

            var accInit = 2
            var accPas = 9
            Regex("""var\s+acc\s*=\s*(\d+)""").find(corps)?.let { accInit = it.groupValues[1].toInt() }
            Regex("""acc\s*=\s*\(\s*acc\s*\+\s*(\d+)\s*\)\s*%\s*256""").find(corps)
                ?.let { accPas = it.groupValues[1].toInt() }

            fun decoder64(s: String): ByteArray {
                val propre = s.replace(Regex("""\s+"""), "")
                val reste = propre.length % 4
                val complet = if (reste > 0) propre + "=".repeat(4 - reste) else propre
                return Base64.decode(complet, Base64.DEFAULT)
            }

            var trouve: String? = null
            for (appel in Regex("""$nom\(\s*\[\s*((?:"[^"]+",?\s*)+)\s*\]\s*\)""").findAll(texte)) {
                val morceaux = Regex(""""([^"]+)"""").findAll(appel.groupValues[1])
                    .map { it.groupValues[1] }.toList()
                var chaine: String? = morceaux.joinToString("").replace("\\/", "/")
                var octets: ByteArray? = null
                var ok = true

                for ((op, param) in operations) {
                    when (op) {
                        "atob" -> try {
                            octets = if (chaine != null) decoder64(chaine!!)
                            else decoder64(String(octets!!, Charsets.ISO_8859_1))
                            chaine = String(octets!!, Charsets.ISO_8859_1)
                        } catch (_: Exception) { ok = false }
                        "reverse" -> {
                            chaine = (chaine ?: String(octets!!, Charsets.ISO_8859_1)).reversed()
                            octets = null
                        }
                        "rot" -> {
                            val d = param!!
                            val depart = chaine ?: String(octets!!, Charsets.ISO_8859_1)
                            val sb = StringBuilder()
                            for (c in depart) sb.append(
                                when (c) {
                                    in 'a'..'z' -> (((c - 'a') + d) % 26 + 'a'.code).toChar()
                                    in 'A'..'Z' -> (((c - 'A') + d) % 26 + 'A'.code).toChar()
                                    else -> c
                                }
                            )
                            chaine = sb.toString()
                            octets = null
                        }
                    }
                    if (!ok) break
                }
                if (!ok) continue

                val finaux = octets ?: chaine!!.toByteArray(Charsets.ISO_8859_1)
                var acc = accInit
                val demele = StringBuilder()
                for (b in finaux) {
                    val o = b.toInt() and 0xFF
                    acc = (acc + accPas) % 256
                    demele.append((o xor acc).toChar())
                    acc = (acc + o) % 256
                }
                val url = demele.toString().trim()
                if (url.startsWith("http")) { trouve = url; break }
            }
            trouve
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * Smart Brute Force: Tries all permutations of:
     * 1. String Transforms (Reverse, ROT13)
     * 2. Base64 Decode
     * 3. (Optional) Intermediate Transforms + Second Base64 Decode
     * 4. Byte Transforms (Reverse, ROT13)
     * 5. (Optional) Decryption Loop
     *
     * Returns the extracted URL string if found, otherwise null.
     */
    private fun smartBruteForce(inputData: String, magicNum: Long, offset: Int): String? {
        val stringTransforms = listOf<(String) -> String>(
            { it },                         // No change
            { it.reversed() },              // Reverse
            { rot13(it) },                  // ROT13
            { rot13(it.reversed()) },       // Reverse -> ROT13
            { rot13(it).reversed() }        // ROT13 -> Reverse
        )

        val byteTransforms = listOf<(ByteArray) -> ByteArray>(
            { it },                         // No change
            { it.reversedArray() },         // Reverse bytes
            { rot13Bytes(it) },             // ROT13 bytes
            { rot13Bytes(it.reversedArray()) }, // Reverse -> ROT13 bytes
            { rot13Bytes(it).reversedArray() }  // ROT13 -> Reverse bytes
        )

        for (sTrans in stringTransforms) {
            for (bTrans in byteTransforms) {
                try {
                    // Phase 1: String Transform -> Base64
                    val sRes = sTrans(inputData)
                    val b64Res = safeBase64Decode(sRes) ?: continue

                    // Collect candidates for Phase 2 (Byte Transform & Loop)
                    // We store: (bytes, description)
                    val candidates = mutableListOf<ByteArray>()
                    candidates.add(b64Res) // Standard Logic

                    // Phase 1.5: Double Base64 Logic
                    try {
                        val firstDecodeStr = String(b64Res, Charsets.ISO_8859_1) // Keep byte values
                        
                        // Variation A: Direct Double Decode
                        val b64Res2 = safeBase64Decode(firstDecodeStr)
                        if (b64Res2 != null) candidates.add(b64Res2)

                        // Variation B: Reverse before Double Decode (B64 -> Reverse -> B64)
                        val b64Res2Reversed = safeBase64Decode(firstDecodeStr.reversed())
                        if (b64Res2Reversed != null) candidates.add(b64Res2Reversed)

                    } catch (e: Exception) { }

                    // Phase 2: Byte Transform -> Decryption Loop -> Validate
                    for (candidateBytes in candidates) {
                         val finalBytes = bTrans(candidateBytes)
                         
                         // Try WITH decryption loop
                         try {
                             val adjusted = unmixLoop(finalBytes, magicNum, offset)
                             val url = String(adjusted, Charsets.UTF_8).trim()
                             if (url.startsWith("http") && url.contains(".mp4")) {
                                 return url
                             }
                         } catch (e: Exception) {}

                         // Try WITHOUT decryption loop
                         try {
                             val urlPlain = String(finalBytes, Charsets.UTF_8).trim()
                             if (urlPlain.startsWith("http") && urlPlain.contains(".mp4")) {
                                 return urlPlain
                             }
                         } catch (e: Exception) {}
                    }

                } catch (e: Exception) {
                    continue
                }
            }
        }
        return null
    }

    private fun safeBase64Decode(str: String): ByteArray? = try {
        Base64.decode(str, Base64.DEFAULT)
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun rot13(input: String): String = input.map {
        when (it) {
            in 'A'..'Z' -> 'A' + (it - 'A' + 13) % 26
            in 'a'..'z' -> 'a' + (it - 'a' + 13) % 26
            else -> it
        }
    }.joinToString("")

    private fun rot13Bytes(data: ByteArray): ByteArray {
        val res = ByteArray(data.size)
        for (i in data.indices) {
            val b = data[i].toInt()
            res[i] = when (b) {
                in 65..90 -> (65 + (b - 65 + 13) % 26).toByte()
                in 97..122 -> (97 + (b - 97 + 13) % 26).toByte()
                else -> b.toByte()
            }
        }
        return res
    }

    private fun unmixLoop(decodedBytes: ByteArray, magicNum: Long, offset: Int): ByteArray {
        val finalBytes = ByteArray(decodedBytes.size)
        for (i in decodedBytes.indices) {
            val b = decodedBytes[i].toInt() and 0xFF
            val adjustment = (magicNum % (i + offset)).toInt()
            finalBytes[i] = ((b - adjustment + 256) % 256).toByte()
        }
        return finalBytes
    }
    
    private interface Service {
        @GET
        suspend fun get(@Url url: String, @Header("referer") referer: String): Document
    }
}
