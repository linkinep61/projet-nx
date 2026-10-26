package com.streamflixreborn.streamflix.utils

import android.util.Base64
import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * 2026-10-27 (user : chaînes oxax dans Zone 18@) : même modèle qu'[AdultTvResolver].
 *   La playlist contient `oxax://<slug>` ; le vrai lien est fabriqué à la lecture, depuis
 *   l'appareil, parce qu'il est signé pour l'IP du visiteur (le `k` contient l'IP) et change
 *   à chaque chargement de la page.
 *
 * Page http://oxax.tv/<slug>.html :
 *   var kodk="1/index.m3u8?k=…S"; var kos="<hexa>";
 *   new Playerjs("#<BLOB>")
 * BLOB = base64 d'un JSON {"id":"pl_ok","file":"https://s.oxax.tv/{v1}<hexa>{v2}<hexa> or …"}
 *   pollué par des morceaux parasites (« FNTU2… »), parfois emboîtés, qui varient.
 *   {v1} = kodk, {v2} = kos. On prend la 1re source avant « or ».
 *
 * Déparasitage : voir [decoder] — aucune liste figée (2026-10-27 : de nouveaux morceaux
 *   apparaissaient sur d'autres chaînes, « brouillage non reconnu » sur sl-hot1).
 */
object OxaxResolver {
    private const val TAG = "OxaxResolver"
    private const val SCHEME = "oxax://"
    private const val BASE = "http://oxax.tv"
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val RE_KODK = Regex("""var\s+kodk\s*=\s*"([^"]+)"""")
    private val RE_KOS = Regex("""var\s+kos\s*=\s*"([^"]+)"""")
    private val RE_BLOB = Regex("""new\s+Playerjs\(\s*["']([^"']+)["']""")

    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val TETE = "{\"id\":\""
    private const val MILIEU = "\",\"file\":\"https://s.oxax.tv/{v1}"
    private fun estHex(c: Char) = c in '0'..'9' || c in 'a'..'f'

    fun isOxaxUrl(src: String): Boolean = src.startsWith(SCHEME)

    /** Où en est le texte décodé par rapport au début attendu
     *  {"id":"…","file":"https://s.oxax.tv/{v1}<hexa>{v2}<hexa>(espace ou ")
     *  → null = impossible, "" = encore incomplet, sinon l'adresse de la 1re source.
     *  La signature (hexa1 + kos + hexa2) fait toujours 32 caractères (vérifié). */
    private fun etat(t: String, lkos: Int): String? {
        val n = t.length
        if (n <= TETE.length) return if (TETE.startsWith(t)) "" else null
        if (!t.startsWith(TETE)) return null
        var i = TETE.length
        while (i < n && (t[i].isLetterOrDigit() || t[i] == '_')) i++
        if (i == n) return ""
        var reste = t.substring(i)
        if (reste.length <= MILIEU.length) return if (MILIEU.startsWith(reste)) "" else null
        if (!reste.startsWith(MILIEU)) return null
        i += MILIEU.length
        val d1 = i
        while (i < n && estHex(t[i])) i++
        if (i == n) return ""
        val h1 = t.substring(d1, i)
        reste = t.substring(i)
        if (reste.length <= 4) return if ("{v2}".startsWith(reste)) "" else null
        if (!reste.startsWith("{v2}")) return null
        i += 4
        val d2 = i
        while (i < n && estHex(t[i])) i++
        if (i == n) return ""
        if (t[i] != ' ' && t[i] != '"') return null
        val h2 = t.substring(d2, i)
        if (h1.length + lkos + h2.length != 32) return null
        return "https://s.oxax.tv/{v1}$h1{v2}$h2"
    }

    /** Déparasitage guidé : on lit le BLOB caractère par caractère ; à chaque « F » on essaie
     *  aussi de sauter un morceau parasite (2 à 20 caractères). Chaque groupe de 4 caractères
     *  gardés est décodé tout de suite et doit coller au début attendu, sinon on revient en
     *  arrière. Les parasites n'ont donc pas besoin d'être connus à l'avance, et ceux placés
     *  après la 1re source (inutile) ne gênent pas. */
    fun decoder(blob: String, lkos: Int): String? {
        var s = blob.trim().removePrefix("#")
        val debut = s.indexOf("eyJ")            // base64 de `{"` : ce qui précède est parasite
        if (debut > 0) s = s.substring(debut)
        val budget = intArrayOf(200_000)
        fun dfs(i: Int, garde: String, texte: String): String? {
            if (--budget[0] < 0) return null
            val e = etat(texte, lkos) ?: return null
            if (e.isNotEmpty()) return e
            if (i >= s.length) return null
            val c = s[i]
            if (c in B64) {
                val g = garde + c
                if (g.length == 4) {
                    val octets = try { Base64.decode(g, Base64.NO_WRAP) } catch (_: Exception) { null }
                    if (octets != null && octets.all { it in 32..126 }) {
                        dfs(i + 1, "", texte + String(octets, Charsets.US_ASCII))?.let { return it }
                    }
                } else {
                    dfs(i + 1, g, texte)?.let { return it }
                }
            }
            if (c == 'F') {
                for (l in 2..20) {
                    if (i + l > s.length) break
                    dfs(i + l, garde, texte)?.let { return it }
                }
            }
            return null
        }
        return dfs(0, "", "")
    }

    suspend fun resolve(src: String): Video? = withContext(Dispatchers.IO) {
        val slug = src.removePrefix(SCHEME).trim('/').removeSuffix(".html")
        try {
            val page = "$BASE/$slug.html"
            val html = NetworkClient.default.newCall(
                Request.Builder().url(page)
                    .header("User-Agent", UA)
                    .header("Referer", "$BASE/")
                    .build()
            ).execute().use { it.body?.string().orEmpty() }

            val kodk = RE_KODK.find(html)?.groupValues?.get(1)
            val kos = RE_KOS.find(html)?.groupValues?.get(1)
            val blob = RE_BLOB.find(html)?.groupValues?.get(1)
            if (kodk == null || kos == null || blob == null) {
                Log.w(TAG, "$slug : page incomplète (kodk=${kodk != null}, kos=${kos != null}, lecteur=${blob != null})")
                return@withContext null
            }
            val fichier = decoder(blob, kos.length) ?: run {
                Log.w(TAG, "$slug : brouillage non reconnu (kos=${kos.length}) $blob")
                return@withContext null
            }
            val lien = fichier.replace("{v1}", kodk).replace("{v2}", kos)
            Log.d(TAG, "$slug : lien résolu")
            Video(
                source = lien,
                type = "application/vnd.apple.mpegurl",
                headers = mapOf("User-Agent" to UA, "Referer" to "$BASE/", "Origin" to BASE),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "résolution échouée pour $src : ${t.javaClass.simpleName} ${t.message}")
            null
        }
    }
}
