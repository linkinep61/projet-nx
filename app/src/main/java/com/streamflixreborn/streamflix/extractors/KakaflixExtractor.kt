package com.streamflixreborn.streamflix.extractors

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

// Gestionnaire generique de proxy de redirection (kokoflix/kakaflix/newPlayer).
// Suit le redirect HTTP puis delegue a l'extracteur du domaine resolu.
// Calque sur upstream streamflix-reborn FrenchStreamProvider L814.
class KakaflixExtractor : Extractor() {

    override val name = "RedirectProxy"
    override val mainUrl = "https://kokoflix.lol"

    override val aliasUrls = listOf(
        "https://kakaflix.lol",
        // 2026-11-02 : nouveaux noms du même relais.
        "https://flixeo.xyz",
        "https://trakx.lol",
    )

    /** Domaines du relais (on continue de suivre tant qu'on y est). */
    private val RELAIS = listOf("kokoflix.lol", "kakaflix.lol", "flixeo.xyz", "trakx.lol")

    override val rotatingDomain: List<Regex> = listOf(
        // 2026-11-02 (banc de test : ~20 serveurs CoflixWiki / LoiFlix / FrenchStream « No extractors
        //   found » sur flixeo.xyz/…/newPlayer.php et trakx.lol) - l'adresse comparée est mise en
        //   MINUSCULES par Extractor.extract : « newPlayer » avec un P majuscule ne pouvait plus jamais
        //   correspondre. Insensible à la casse désormais.
        Regex("""newplayer\.php""", RegexOption.IGNORE_CASE)
    )

    override suspend fun extract(link: String): Video {
        Log.d("RedirectProxy", "Resolving proxy: $link")

        // ── 2026-08-06 : ON NE SUIT PLUS LA CHAÎNE DE REDIRECTIONS ───────────────────────
        //   User : « VOE VF HD échoue à chaque fois ». Journal :
        //     RedirectProxy: Resolving proxy: https://kakaflix.lol/voe3/newPlayer.php?id=…
        //     QualityProbe: Too many follow-up requests: 21
        //   Ce relais renvoie vers un domaine VOE (`pamelachangemission.com/e/<id>`) qui
        //   REBOUCLE sur lui-même. Vérifié dans un vrai navigateur : Chrome affiche « vous a
        //   redirigé à de trop nombreuses reprises ». La boucle est donc côté VOE, elle
        //   n'est pas de notre fait et on ne peut pas la casser en la suivant.
        //   Or on n'a AUCUN besoin d'aller au bout : le PREMIER saut donne déjà tout ce qui
        //   compte — le domaine VOE et l'identifiant de la vidéo. On s'arrête donc là et on
        //   passe la main à l'extracteur VOE, qui sait traiter ce lien.
        //   ⚠ NE PAS remettre `followRedirects(true)` : c'est ce qui faisait tomber
        //     l'extraction au bout de 21 sauts au lieu d'aboutir au premier.
        val client = OkHttpClient.Builder()
            .connectionPool(com.streamflixreborn.streamflix.utils.NetworkClient.sharedConnectionPool)
            .dns(DnsResolver.doh)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        // 2026-11-02 (banc Superman : 9 serveurs CoflixWiki/LoiFlix « renvoie vers une publicité »,
        //   PC en 429 « Too many requests ») : flixeo.xyz limite/monétise, alors que trakx.lol sert
        //   le MÊME identifiant proprement (303 direct vers l'hébergeur, vérifié depuis le PC sur
        //   tokyo/ et moon2/). On interroge donc d'abord trakx.lol, puis l'hôte d'origine, puis les
        //   autres noms du relais ; le premier qui sort vers un vrai hébergeur gagne.
        val hoteOrigine = link.toHttpUrlOrNull()?.host?.lowercase()
        //   (kakaflix.lol n'a plus d'adresse DNS et kokoflix.lol répond 404 — vérifié au banc et au
        //   PC le 2026-11-02 — on ne les interroge plus.)
        val candidats = (listOf("trakx.lol", hoteOrigine, "flixeo.xyz"))
            .filterNotNull().distinct()
            .map { h -> if (h == hoteOrigine) link else link.replace(Regex("""^(https?://)[^/]+"""), "$1$h") }

        var resolvedUrl = link
        var derniereErreur: String? = null
        var delais = 0
        val videoIntrouvable = java.util.concurrent.atomic.AtomicBoolean(false)
        for (depart in candidats) {
            val r = try {
                resoudre(client, depart, videoIntrouvable)
            } catch (e: java.io.IOException) {
                // Relais injoignable / qui ne répond pas (ex. trakx.lol/viper/ pend indéfiniment,
                //   mesuré au curl le 2026-11-02). On passe au nom suivant, mais pas plus de deux
                //   fois : au-delà, c'est le relais entier qui est en panne, inutile d'attendre.
                derniereErreur = "pas de sortie ($depart : ${e.javaClass.simpleName} ${e.message})"
                Log.d("RedirectProxy", "relais $depart : $derniereErreur")
                if (++delais >= 2) break
                continue
            }
            val pub = Regex("""(?i)//click-v\d+\.|clck\.com""").containsMatchIn(r)
            val encoreRelais = r == depart || RELAIS.any { r.contains(it, ignoreCase = true) }
            if (!pub && !encoreRelais) { resolvedUrl = r; derniereErreur = null; break }
            derniereErreur = if (pub) "publicité ($r)" else "pas de sortie ($r)"
            Log.d("RedirectProxy", "relais $depart : $derniereErreur")
        }

        Log.d("RedirectProxy", "Resolved: $link -> $resolvedUrl")

        if (derniereErreur != null) {
            if (videoIntrouvable.get()) {
                // Message calé sur Extractor.classifyError (« video not found ») → lien mort signalé.
                throw Exception("Redirect proxy: video not found - le relais ne connaît plus cette vidéo ($link)")
            }
            if (derniereErreur.startsWith("publicité")) {
                throw Exception("Redirect proxy : renvoie vers une publicité au lieu de la vidéo ${derniereErreur.removePrefix("publicité ")}")
            }
            throw Exception("Redirect proxy failed: $link -> ${derniereErreur.removePrefix("pas de sortie (").removeSuffix(")")}")
        }

        return Extractor.extract(resolvedUrl)
    }

    /** Suit le relais depuis `link` (quelques sauts) et renvoie l'adresse de sortie, ou `link`. */
    private suspend fun resoudre(
        client: OkHttpClient,
        link: String,
        videoIntrouvable: java.util.concurrent.atomic.AtomicBoolean,
    ): String {
        return withContext(Dispatchers.IO) {
            var courant = link
            var url = link
            // Quelques sauts suffisent largement pour sortir du relais ; on s'arrête dès
            //   qu'on quitte kokoflix/kakaflix, ou faute de nouvelle redirection.
            repeat(4) {
                val request = Request.Builder()
                    .url(courant)
                    .header(
                        "User-Agent",
                        // 2026-11-02 : avec un navigateur d'ORDINATEUR, flixeo renvoie vers une pub ;
                        //   un Chrome de téléphone n'y a pas eu droit (mesuré au curl).
                        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                    )
                    .header("Referer", "https://www.frenchstream.re/")
                    .build()

                val suivant = client.newCall(request).execute().use { reponse ->
                    reponse.header("Location")?.let { entete ->
                        // `Location` peut être relatif : on le résout sur l'URL courante.
                        courant.toHttpUrlOrNull()?.resolve(entete)?.toString() ?: entete
                    } ?: run {
                        // 2026-11-02 : flixeo.xyz répond 200 avec une page « Loading… » qui redirige
                        //   en JavaScript (window.location.replace('…?ch=1&id=…&js=<jeton>')).
                        val corps = reponse.body?.string().orEmpty().take(4000)
                        // Diagnostic (2026-11-02) : ce que répond le relais quand il ne redirige pas.
                        Log.d("RedirectProxy", "pas de Location : HTTP ${reponse.code}, ${corps.length} car. « ${corps.replace(Regex("\\s+"), " ").take(220)} »")
                        // 2026-11-02 (banc Inception) : « No video found with that id » = le relais
                        //   ne connaît plus cette vidéo → lien mort, pas une panne.
                        //   (variante française vue le même jour : « Aucune vidéo avec cet ID ».)
                        if (Regex("""(?i)no video found|aucune vid[ée]o avec""").containsMatchIn(corps)) videoIntrouvable.set(true)
                        Regex("""location\.replace\(\s*['"]([^'"]+)['"]""").find(corps)?.groupValues?.get(1)
                            ?.let { courant.toHttpUrlOrNull()?.resolve(it)?.toString() ?: it }
                    }
                // 2026-11-02 : sans redirection ni script, inutile de redemander 4 fois la même
                //   page (on l'a vu sur un HTTP 500 du relais) : on rend la main tout de suite.
                } ?: return@withContext url

                url = suivant
                courant = suivant
                val horsRelais = RELAIS.none { suivant.contains(it, true) }
                if (horsRelais) return@withContext suivant
            }
            url
        }
    }
}
