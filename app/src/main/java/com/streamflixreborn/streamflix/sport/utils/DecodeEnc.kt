package com.streamflixreborn.streamflix.sport.utils

/**
 * Décodage des paramètres `url=enc%3A…` de damitv (brief § 4) :
 * URL-decode → base64-decode → inverser la chaîne.
 *
 * Base64 écrit à la main : `java.util.Base64` exige Android 8 (l'appli tourne dès Android 6)
 * et `android.util.Base64` n'existe pas hors Android — cette version marche partout.
 * Ne lève jamais : en cas d'échec, rend null et l'appelant garde l'URL brute.
 */
object DecodeEnc {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun decodeEnc(param: String): String? = try {
        val raw = java.net.URLDecoder.decode(param, "UTF-8").removePrefix("enc:")
        val octets = base64(raw) ?: throw IllegalArgumentException("base64 invalide")
        String(octets, Charsets.UTF_8).reversed()
    } catch (_: Exception) {
        null
    }

    /** Base64 standard ou « URL-safe », padding facultatif. Null si caractère invalide. */
    private fun base64(s: String): ByteArray? {
        // URLDecoder change un « + » non encodé en espace : on le remet.
        val propre = s.trim().replace(' ', '+').replace('-', '+').replace('_', '/').trimEnd('=')
        val out = java.io.ByteArrayOutputStream(propre.length * 3 / 4)
        var tampon = 0
        var bits = 0
        for (c in propre) {
            if (c == '\n' || c == '\r' || c == ' ') continue
            val v = ALPHABET.indexOf(c)
            if (v < 0) return null
            tampon = (tampon shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((tampon shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    /** Encodage inverse, pour le test aller-retour seulement. */
    fun encodePourTest(url: String): String {
        val octets = url.reversed().toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        var i = 0
        while (i < octets.size) {
            val b0 = octets[i].toInt() and 0xFF
            val b1 = if (i + 1 < octets.size) octets[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < octets.size) octets[i + 2].toInt() and 0xFF else -1
            sb.append(ALPHABET[b0 shr 2])
            sb.append(ALPHABET[((b0 and 3) shl 4) or (if (b1 >= 0) b1 shr 4 else 0)])
            sb.append(if (b1 >= 0) ALPHABET[((b1 and 15) shl 2) or (if (b2 >= 0) b2 shr 6 else 0)] else '=')
            sb.append(if (b2 >= 0) ALPHABET[b2 and 63] else '=')
            i += 3
        }
        return "enc:$sb"
    }
}
