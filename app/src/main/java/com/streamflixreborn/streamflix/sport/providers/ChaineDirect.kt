package com.streamflixreborn.streamflix.sport.providers

import android.util.Log
import com.streamflixreborn.streamflix.sport.SportEvent
import com.streamflixreborn.streamflix.sport.StreamSource
import com.streamflixreborn.streamflix.sport.TtlCache
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

/**
 * chainedirect.com (liste du user ; « tu prends tout, il y a plein de choses », son propre petit
 * dossier dans Sport).
 *
 * Vérifié le 25/10/2026 dans le Chrome du user : `/chaines-live/<rubrique>/` en HTML simple,
 * une carte par chaîne (`.ann-short_item` : `.ann-short_price` = nom, `img.poster` = logo,
 * `a[href$=.html]` = page). 163 chaînes : sport 52, cinéma 27, documentaire 22, enfants 22,
 * généraliste 19, info 13, musique 6, Belgique 1, Suisse 1. La page d'une chaîne charge
 * `/player/source2/?id=<n>` puis `wideiptv.top/daddy.php` (relais thedaddy : Canal+ Foot = 463,
 * comme chez damitv) → lue par la WebView cachée, flux capté puis lu par le lecteur ONYX.
 * Chaque chaîne est rendue comme un « événement » en direct (même chemin de lecture que damitv).
 */
object ChaineDirect {

    const val ID = "chainedirect"
    private const val TAG = "SportChaineDirect"
    // 2026-10-28 (user, capture : « Ce site copie le catalogue de WiTV.eu », « on ne prend pas sur
    //   le bon catalogue ») : chainedirect.com n'est qu'une COPIE de witv.eu ; le lecteur de WiTV
    //   (/player/source2/) remplace le flux par cet écran quand il est appelé depuis la copie.
    //   Vérifié dans le Chrome du user : witv.eu = mêmes rubriques, mêmes pages, même lecteur.
    private const val BASE = "https://witv.eu"

    /** Rubrique du site → nom de la rubrique dans le dossier ONYX. */
    val RUBRIQUES = linkedMapOf(
        "sport" to "⚽ Sport",
        "generaliste" to "📺 Généraliste",
        "info" to "📰 Info",
        "cinema" to "🎬 Cinéma",
        "documentaire" to "🌍 Documentaire",
        "enfants" to "🧸 Enfants",
        "musique" to "🎵 Musique",
        "belgique" to "🇧🇪 Belgique et Suisse",
        "suisse" to "🇧🇪 Belgique et Suisse",
    )

    private val cache = TtlCache<List<SportEvent>>(30 * 60_000L)
    // 2026-10-28 : SANS cookies. Le client par défaut partage ceux de la WebView ; après un passage
    //   de la WebView sur witv.eu, ses cookies (détection de robot) font servir le LEURRE même avec
    //   le bon jeton. Mesuré dans Chrome : tout le parcours marche sans aucun cookie.
    private val client by lazy {
        NetworkClient.default.newBuilder().callTimeout(12, TimeUnit.SECONDS)
            .cookieJar(okhttp3.CookieJar.NO_COOKIES).build()
    }

    /** Efface les cookies witv.eu laissés par la WebView (le lecteur vidéo les enverrait sinon). */
    fun oublierCookiesWitv() {
        runCatching {
            val cm = android.webkit.CookieManager.getInstance()
            for (hote in listOf("https://witv.eu", "https://www.witv.eu", "https://chainedirect.com")) {
                cm.getCookie(hote)?.split(';')?.map { it.substringBefore('=').trim() }?.filter { it.isNotEmpty() }
                    ?.forEach { nom -> cm.setCookie(hote, "$nom=; Max-Age=0; Path=/") }
            }
            cm.flush()
        }
    }

    suspend fun getStreams(): List<SportEvent> = cache.get { charger() }
    fun derniereListe(): List<SportEvent> = cache.derniere() ?: emptyList()

    /** Rubrique ONYX d'une chaîne (portée dans [SportEvent.category]). */
    fun rubrique(ev: SportEvent): String = RUBRIQUES[ev.category] ?: "📺 Généraliste"

    private fun page(chemin: String): String {
        val req = okhttp3.Request.Builder().url(BASE + chemin).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw Exception("$chemin : HTTP ${r.code}")
            return r.body?.string().orEmpty()
        }
    }

    /** UNE seule identité de navigateur pour la page, le lecteur ET le flux : le jeton `tk` du
     *  flux est lié à l'UA qui l'a obtenu (mesuré sur l'Oppo : flux lu sans UA → écran leurre). */
    private val UA = com.streamflixreborn.streamflix.utils.WebViewResolver.STEALTH_UA

    private fun lire(url: String, referer: String, etape: String, cadre: Boolean = false): String {
        val req = okhttp3.Request.Builder().url(url)
            .header("Referer", referer)
            .header("User-Agent", UA)
            .apply {
                // 2026-10-28 : la page du lecteur répond 403 aux en-têtes par défaut du client
                //   (Sec-Fetch « navigation directe ») ; un navigateur la charge comme un CADRE du
                //   même site → mêmes en-têtes.
                if (cadre) {
                    header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    header("Sec-Fetch-Dest", "iframe")
                    header("Sec-Fetch-Mode", "navigate")
                    header("Sec-Fetch-Site", "same-origin")
                }
            }
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw Exception("$etape : HTTP ${r.code}")
            return r.body?.string().orEmpty()
        }
    }

    /** En-têtes du flux WiTV : un Referer witv.eu suffit (mesuré : sans lui, la playlist de
     *  l'écran d'avertissement ; cookies inutiles). */
    //   2026-10-28 (Oppo : leurre malgré le jeton) : le client réseau pose par défaut
    //   « Sec-Fetch-Dest: document / navigate / none » = lien ouvert dans un onglet → WiTV sert
    //   l'écran d'avertissement. Le vrai lecteur (hls.js) envoie empty / cors / same-origin.
    val ENTETES_FLUX: Map<String, String> get() = mapOf(
        "User-Agent" to UA,
        "Referer" to "$BASE/",
        "Origin" to BASE,
        "Accept" to "*/*",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "same-origin",
    )

    /**
     * 2026-10-28 (user, capture de l'Oppo : « Ce site copie le catalogue de WiTV.eu » même depuis
     * witv.eu) — lecture DIRECTE, sans WebView. Mesuré dans le Chrome du user :
     *  • la page de la chaîne porte `#player1[data-v="wt1_…"]` et un script `var _a=[…],_m=N` :
     *    chaque nombre XOR N → « clé|…|<base64> » ; le base64 = JSON {data-v → adresse du lecteur
     *    /player/source2/?id=…}. Le site ne remplit cette table que s'il ne croit pas parler à un
     *    robot ; sinon il passe par /ajax_player.php qui rend un lecteur LEURRE (d'où l'écran
     *    d'avertissement dans la WebView) ;
     *  • le lecteur (`&_t=<ms>` ajouté comme le site) contient `var m3u8Url` = leurre
     *    (witv_notice.m3u8) et `var _wtPayload` = vraie adresse, base64 À L'ENVERS ;
     *  • la vraie playlist (/player/source2/?vp&tk&vc) est en direct, avec Referer witv.eu.
     * Null si une étape manque (le chemin WebView prend alors le relais).
     */
    /** Préfixe d'un résultat de [fluxDirect] qui n'est pas un flux mais un lecteur externe à ouvrir. */
    const val PREFIXE_CADRE = "cadre::"

    suspend fun fluxDirect(pageUrl: String): String? = withContext(Dispatchers.IO) {
        oublierCookiesWitv()
        runCatching {
            val page = lire(pageUrl, "$BASE/", "page de la chaîne")
            val dv = (Regex("id=\"player1\"[^>]*data-v=\"([^\"]+)\"").find(page)
                ?: Regex("data-v=\"(wt1_[^\"]+)\"").find(page))?.groupValues?.get(1)
                ?: return@runCatching null
            val tableau = Regex("var _a\\s*=\\s*\\[([0-9,\\s]+)]\\s*,\\s*_m\\s*=\\s*(\\d+)").find(page)
                ?: return@runCatching null
            val masque = tableau.groupValues[2].toInt()
            val texte = tableau.groupValues[1].split(',')
                .mapNotNull { it.trim().toIntOrNull() }
                .map { (it xor masque).toChar() }.joinToString("")
            val table = texte.split('|').getOrNull(2)?.ifBlank { null } ?: return@runCatching null
            val vmap = org.json.JSONObject(String(android.util.Base64.decode(table, android.util.Base64.DEFAULT), Charsets.UTF_8))
            var base = vmap.optString(dv).ifBlank { return@runCatching null }
                .replace("\\/", "/")
                .replace(Regex("^https?://(www\\.)?witv\\.[a-z]+", RegexOption.IGNORE_CASE), "")
            base = base.replace(Regex("([?&])_t=[0-9]+(&|\$)"), "\$1").trimEnd('?', '&')
            val lecteur = BASE + (if (base.startsWith("/")) base else "/$base") +
                (if (base.contains('?')) "&" else "?") + "_t=" + System.currentTimeMillis()
            val source2 = lire(lecteur, pageUrl, "page du lecteur", cadre = true)
            val charge = Regex("var _wtPayload\\s*=\\s*\"([^\"]+)\"").find(source2)?.groupValues?.get(1)
                ?: run {
                    // 2026-10-28 (user : « DAZN 1 France ne marche plus ») : certaines chaînes n'ont pas
                    //   le lecteur maison mais un CADRE vers un autre site (thedaddy.pk/stream/…). On
                    //   le rend tel quel : Index l'ouvrira directement dans la WebView (sans WiTV).
                    val cadreExterne = Regex("<iframe[^>]+src=[\"']([^\"']+)", RegexOption.IGNORE_CASE)
                        .findAll(source2).map { it.groupValues[1].replace("\\/", "/") }
                        .map { if (it.startsWith("//")) "https:$it" else it }
                        .firstOrNull { it.startsWith("http") && !it.contains("witv.", true) }
                    if (cadreExterne != null) Log.i(TAG, "lecteur externe : ${cadreExterne.substringBefore('?')}")
                    return@runCatching cadreExterne?.let { PREFIXE_CADRE + it }
                }
            val reel = String(android.util.Base64.decode(charge.reversed(), android.util.Base64.DEFAULT), Charsets.UTF_8)
                .replace("\\/", "/")
            val absolue = when {
                reel.startsWith("http") -> reel
                reel.startsWith("//") -> "https:$reel"
                reel.startsWith("/") -> BASE + reel
                else -> "$BASE/$reel"
            }
            // Le leurre peut se cacher dans `vp` (base64 de « witv_notice.m3u8 ») : on le décode.
            val vp = Regex("[?&]vp=([^&]+)").find(absolue)?.groupValues?.get(1)
                ?.let { runCatching { String(android.util.Base64.decode(java.net.URLDecoder.decode(it, "UTF-8"), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP), Charsets.UTF_8) }.getOrNull() }
                .orEmpty()
            Log.i(TAG, "lecture directe : vp=« ${vp.takeLast(40)} » jeton=${Regex("[?&]tk=").containsMatchIn(absolue)}")
            absolue.takeIf { !it.contains("notice", ignoreCase = true) && !vp.contains("notice", ignoreCase = true) }
        }.onFailure { Log.w(TAG, "lecture directe KO : ${it.message}") }.getOrNull()
    }

    private suspend fun charger(): List<SportEvent> = withContext(Dispatchers.IO) {
        val debut = System.currentTimeMillis()
        val parRubrique = coroutineScope {
            RUBRIQUES.keys.map { r ->
                async {
                    r to runCatching { Jsoup.parse(page("/chaines-live/$r/"), BASE) }
                        .onFailure { Log.w(TAG, "$r KO : ${it.message}") }.getOrNull()
                }
            }.awaitAll()
        }
        val vus = HashSet<String>()
        val out = ArrayList<SportEvent>()
        for ((r, doc) in parRubrique) {
            doc ?: continue
            for (carte in doc.select(".ann-short_item")) {
                val lien = carte.selectFirst("a[href$=.html]")?.absUrl("href")?.ifBlank { null } ?: continue
                if (!vus.add(lien)) continue
                val nom = carte.selectFirst(".ann-short_price")?.text()?.trim()?.ifBlank { null } ?: continue
                val logo = carte.selectFirst("img")?.absUrl("src").orEmpty()
                val id = lien.substringAfterLast('/').substringBefore(".html")
                out += SportEvent(
                    id = id, provider = ID, title = nom, league = "", category = r, poster = logo,
                    startsAt = null, endsAt = null, status = "live", viewers = 0,
                    sources = listOf(StreamSource(name = "Direct", embedUrl = lien)),
                    embedUrl = lien, francais = true,
                )
            }
        }
        if (out.isEmpty()) throw Exception("aucune chaîne lue")
        Log.i(TAG, "${out.size} chaînes (${System.currentTimeMillis() - debut} ms) : " +
            out.groupBy { it.category }.entries.joinToString { "${it.key}=${it.value.size}" })
        out
    }
}
