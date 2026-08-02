package com.streamflixreborn.streamflix.extractors

import android.annotation.SuppressLint
import android.os.Message
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject

/**
 * OnRegardeOuExtractor — extrait le flux des pages onregardeou.site.
 *
 * 2026-07-01 : l'hébergeur est passé en résolution DYNAMIQUE + gate anti-bot.
 *   - La page /video/<slug>/ = player video.js avec `const videoData={servers:[...]}`.
 *   - Un overlay « Confirmez que vous êtes humain pour lancer la vidéo » exige un
 *     clic ; les serveurs pointent vers des hôtes qui changent souvent
 *     (bysezoxexe/uns.bio/chuckle-tube/upbolt…) + pop-unders pub.
 *   → L'ancien parsing statique (`videoData` → hôtes → extracteurs in-app) ne joue
 *     plus (hôtes inconnus/gatés).
 *
 * Nouvelle stratégie (modèle MoiflixExtractor) : WebView headless qui charge la
 *   page, auto-clique le bouton play (passe le gate humain), BLOQUE les pop-unders
 *   (onCreateWindow=false) et INTERCEPTE le m3u8/mp4 réel via shouldInterceptRequest.
 *   Robuste aux changements de domaine des hôtes (on capte le flux, peu importe
 *   d'où il vient).
 */
class OnRegardeOuExtractor : Extractor() {

    override val name = "OnRegardeOu"
    override val mainUrl = "https://onregardeou.site"
    // 2026-07-01 : les mirrors internes d'onregardeou qui n'ont PAS d'extracteur
    //   dédié (bysezoxexe→player q8y5z, upbolt, uns.bio) sont routés ICI (WebView
    //   intercept générique). PAS chuckle-tube → c'est du VOE, géré par VoeExtractor
    //   (rapide, sans WebView) via son rotatingDomain. OnRegardeOu est enregistré
    //   AVANT Filemoon dans la liste → il gagne bysezoxexe (sinon Filemoon+PoW).
    override val aliasUrls = listOf(
        "https://bysezoxexe.com", "https://upbolt.to", "https://dismoiceline.uns.bio",
    )

    private val context = StreamFlixApp.instance.applicationContext

    private val ANDROID_CHROME_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    /** Le provider appelle expand() avec l'URL embed onregardeou. La page contient
     *  `videoData.servers` = plusieurs MIRRORS (bysezoxexe/uns.bio/chuckle-tube/upbolt…).
     *  On les expose TOUS comme serveurs distincts (le user voyait « SERVEUR 1/2/3/4 »
     *  sur le site). Chaque mirror route ensuite vers le bon extracteur via
     *  Extractor.extract (VOE pour chuckle-tube, WebView OnRegardeOu pour les autres). */
    // 2026-07-02 (user "ce genre de serveur qui affiche une page à cliquer, on les désactive
    //   maintenant qu'on a plein de backups") : mirrors OnRegardeOu à NE PAS exposer.
    //   uns.bio = player vidstack (PoW "Verifying human…" à cliquer, ne joue pas headless).
    //   2026-07-27 : upbolt RÉ-ACTIVÉ — le m3u8 (edge0X.upbolt.to) se lit sans en-tête (vérifié) ;
    //   le seul blocage était le tap « play » qui redirigeait vers /dl (téléchargement) et tuait le
    //   player. On bloque désormais cette navigation /dl (cf shouldOverrideUrlLoading) → le player
    //   reste et le m3u8 part en XHR → intercepté. On garde juste la lecture NATIVE du m3u8 capté.
    // ── 2026-08-05 : uns.bio RÉ-ACTIVÉ, et son bannissement de juillet était infondé ──────
    //   Ce qu'on a établi (ne pas refaire le tour) :
    //     · Son lecteur est le MÊME que celui d'EmbedSeek — bundle de 883 029 octets contre
    //       883 091, mêmes chaînes `Adblock Detected` / `Headless Detected` / `ima3.js` /
    //       `/api/v1/video` / `cf-master`. Il est donc résolu par `OnlyFlixResolver`, comme
    //       son jumeau, depuis le correctif du SDK publicitaire du même jour.
    //     · Le motif « PoW Verifying human…, ne joue pas headless » est FAUX. Vérifié par un
    //       VRAI clic humain dans Chrome sur `dismoiceline.uns.bio/#69ftr8` : le lecteur
    //       affiche « Désolé, cette vidéo n'est pas disponible. » C'est le CONTENU qui est
    //       mort sur ce miroir, pas le lecteur qui résiste. Les endpoints répondent pourtant
    //       200 (la fiche et la clé existent), d'où `/api/v1/video` qui part sans qu'aucun
    //       manifeste ne suive : il n'y a rien à servir.
    //   ⚠ Ne PAS re-bannir ce miroir sur la base d'un seul film : il faut le tester sur un
    //     titre dont le média est vivant. La liste reste en place pour un usage futur.
    private val DISABLED_MIRROR_HOSTS = emptyList<String>()

    suspend fun expand(link: String, referer: String = mainUrl, suffix: String = ""): List<Video.Server> {
        if (link.isBlank()) return emptyList()
        // ── 2026-08-05 — LE LIEN D'ENTRÉE PEUT DÉJÀ ÊTRE UN MIROIR ───────────────────────
        //   1Jour1Film livre maintenant les miroirs EN DIRECT (`bysezoxexe.com`,
        //   `dismoiceline.uns.bio`) au lieu de la page `onregardeou.site`. Dans ce cas
        //   `parseMirrors` ne trouve aucun `videoData.servers` et on tombait sur le repli
        //   ci-dessous, qui nommait TOUT « OnRegardeOu » — d'où les deux serveurs au libellé
        //   identique signalés par le user, dont un mort. Et le filtre des miroirs désactivés
        //   ne s'appliquait qu'aux résultats de `parseMirrors`, jamais au lien d'entrée :
        //   `uns.bio` passait donc malgré son bannissement de juillet.
        val hoteEntree = try {
            android.net.Uri.parse(link).host?.lowercase().orEmpty()
        } catch (_: Exception) { "" }
        if (DISABLED_MIRROR_HOSTS.any { hoteEntree.contains(it) }) {
            android.util.Log.d("OnRegardeOu", "expand: miroir désactivé écarté ($hoteEntree)")
            return emptyList()
        }
        val mirrors = parseMirrors(link).filterNot { (_, mUrl) ->
            val h = try { android.net.Uri.parse(mUrl).host?.lowercase().orEmpty() } catch (_: Exception) { "" }
            DISABLED_MIRROR_HOSTS.any { h.contains(it) }
        }
        if (mirrors.isEmpty()) {
            // Repli : un seul serveur. On le nomme par son HÔTE RÉEL plutôt que « OnRegardeOu »,
            //   sinon deux miroirs directs différents portent le même libellé.
            //   ⚠ On GARDE « OnRegardeOu » dans le libellé (user : « y'a plus OnRegardeOu »
            //     après un premier essai qui n'affichait que l'hôte) : c'est sous ce nom
            //     qu'il identifie le serveur. L'hôte est ajouté ENTRE PARENTHÈSES, ce qui
            //     distingue les miroirs sans faire disparaître le nom du service.
            val etiquette = if (hoteEntree.isNotBlank() && !hoteEntree.contains("onregardeou")) {
                val parts = hoteEntree.removePrefix("www.").split(".")
                val court = if (parts.size >= 3) parts.takeLast(2).joinToString(".") else hoteEntree
                "OnRegardeOu ($court)"
            } else "OnRegardeOu"
            // ⚠⚠ IDENTIFIANT UNIQUE PAR MIROIR — ne pas revenir à « ${name}_0 ».
            //   Le provider appelle `expand()` UNE FOIS PAR MIROIR (bysezoxexe, puis uns.bio).
            //   Avec un index 0 codé en dur, les deux serveurs recevaient LE MÊME identifiant
            //   `OnRegardeOu_0` → `BackupRegistry` dédoublonne par identifiant et jetait le
            //   second. Symptôme user : « et il est où le deuxième ? », un seul serveur affiché
            //   alors que le site en propose deux. On dérive donc l'identifiant de l'hôte.
            val cle = hoteEntree.ifBlank { link }.filter { it.isLetterOrDigit() }.takeLast(16)
            return listOf(Video.Server(id = "${name}_$cle", name = "$suffix$etiquette", src = link))
        }
        return mirrors.mapIndexed { i, (mName, mUrl) ->
            val svc = Extractor.identifyServiceName(mUrl)
            // Nom DISTINCT. Les mirrors bysezoxexe/upbolt/uns.bio routent tous vers
            //   CET extracteur (aliasUrls) → identifyServiceName renvoie "OnRegardeOu"
            //   pour les 3 → « 3 fois le même » dans le picker. On préfère : le vrai
            //   service tiers (VOE…) sinon le nom videoData ("Serveur N", = le site)
            //   sinon le host.
            // Host "propre" pour l'affichage : full host sans www. Pour un
            //   sous-domaine rotatif (dismoiceline.uns.bio) on garde le domaine
            //   enregistrable (2 derniers labels : uns.bio) = stable entre rotations.
            val host = try {
                val h = android.net.Uri.parse(mUrl).host?.removePrefix("www.").orEmpty()
                val parts = h.split(".")
                if (parts.size >= 3) parts.takeLast(2).joinToString(".") else h
            } catch (_: Exception) { "" }
            // User (2026-07-01) : « il devrait être nommé par leur nom d'origine
            //   pour qu'on sache ». → priorité : service tiers reconnu (VOE/Vidara…)
            //   sinon le HOST réel (bysezoxexe.com / uns.bio / upbolt.to) — PLUS le
            //   « Serveur N » générique qui masquait l'identité du mirror.
            val label = when {
                svc != null && !svc.equals(name, ignoreCase = true) -> svc
                host.isNotBlank() -> host
                mName.isNotBlank() -> mName
                else -> "Serveur ${i + 1}"
            }
            Video.Server(id = "${name}_$i", name = "$suffix$label", src = mUrl)
        }
    }

    /** Parse TOUS les mirrors de `videoData.servers` → [(nom, url)]. */
    private suspend fun parseMirrors(onregardeouUrl: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(onregardeouUrl)
                .header("User-Agent", ANDROID_CHROME_UA)
                .header("Referer", "$mainUrl/").build()
            val html = NetworkClient.default.newCall(req).execute().use { it.body?.string() }
                ?: return@withContext emptyList()
            val m = Regex("videoData\\s*=\\s*(\\{[\\s\\S]*?\\});").find(html) ?: return@withContext emptyList()
            val servers = JSONObject(m.groupValues[1]).optJSONArray("servers") ?: return@withContext emptyList()
            (0 until servers.length()).mapNotNull { idx ->
                val o = servers.getJSONObject(idx)
                val url = o.optString("url").replace("\\/", "/").takeIf { it.startsWith("http") }
                    ?: return@mapNotNull null
                val nm = o.optString("name").ifBlank { o.optString("label") }
                nm to url
            }
        } catch (_: Exception) { emptyList() }
    }

    /** Récupère l'URL de l'hôte réel (bysezoxexe/uns.bio…) depuis le `videoData`
     *  de la page onregardeou. Ces hôtes s'auto-lancent → chargés directement dans
     *  la WebView, ils déclenchent la lecture sans le gate « humain » d'onregardeou. */
    private suspend fun resolveHostUrl(onregardeouUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(onregardeouUrl)
                .header("User-Agent", ANDROID_CHROME_UA)
                .header("Referer", "$mainUrl/").build()
            val html = NetworkClient.default.newCall(req).execute().use { it.body?.string() } ?: return@withContext null
            val m = Regex("const videoData\\s*=\\s*(\\{[\\s\\S]*?\\});").find(html) ?: return@withContext null
            val servers = JSONObject(m.groupValues[1]).optJSONArray("servers") ?: return@withContext null
            if (servers.length() == 0) return@withContext null
            servers.getJSONObject(0).optString("url").replace("\\/", "/").takeIf { it.startsWith("http") }
        } catch (_: Exception) { null }
    }

    /** upbolt/KVS : transforme le master multi-variantes
     *  `.../00014/,id_l,id_n,id_h,.urlset/master.m3u8?t=...` en la playlist de la MEILLEURE
     *  variante `.../00014/id_h/index-v1-a1.m3u8?t=...` (même token, préfixe couvert). Le master
     *  est DDoS-gated (403) ; la variante joue au token seul. No-op si le format ne matche pas. */
    /** Interrupteur des sondes de diagnostic upbolt (voir le bloc commenté dans `extraireUpbolt`). */
    private val SONDES_UPBOLT = false

    /** En-têtes du CDN upbolt — strictement ceux qui ont été mesurés à 200 sur l'appareil. */
    private fun requeteCdnUpbolt(url: String, hote: String, cookies: String): Request =
        Request.Builder()
            .url(url)
            .header("User-Agent", ANDROID_CHROME_UA)
            .header("Referer", "https://$hote/")
            .header("Origin", "https://$hote")
            .header("Accept", "*/*")
            .header("Accept-Language", "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7")
            .apply { if (cookies.isNotBlank()) header("Cookie", cookies) }
            .build()

    /** Re-soumet le formulaire `/dl` pour obtenir un jeton NEUF (le précédent peut être grillé). */
    private fun nouveauMasterUpbolt(hote: String, code: String, link: String, cookies: String): String? =
        runCatching {
            val corps = FormBody.Builder()
                .add("op", "embed").add("file_code", code).add("auto", "1").add("referer", "")
                .build()
            val req = Request.Builder()
                .url("https://$hote/dl")
                .post(corps)
                .header("User-Agent", ANDROID_CHROME_UA)
                .header("Referer", link)
                .header("Origin", "https://$hote")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7")
                .apply { if (cookies.isNotBlank()) header("Cookie", cookies) }
                .build()
            NetworkClient.default.newCall(req).execute().use { rep ->
                if (!rep.isSuccessful) return@runCatching null
                Regex("""https?://[^"'\s\\<>]+\.m3u8[^"'\s\\<>]*""").find(rep.body?.string().orEmpty())?.value
            }
        }.getOrNull()

    /**
     * Renvoie la playlist de la MEILLEURE qualité que le CDN accepte réellement de servir.
     *
     * Pourquoi ce n'est pas juste « prendre `_h` » : mesuré le 2026-08-08 sur la Chromecast,
     * `_l` répond 200 et `_n`/`_h` répondent 403 — alors que depuis le PC les trois passent.
     * Et un 403 empoisonne le jeton pour la suite. D'où : un essai = un jeton neuf.
     */
    private fun choisirMeilleureVarianteUpbolt(
        master: String,
        hote: String,
        code: String,
        link: String,
        cookies: String,
    ): String {
        val ordre = listOf("_h", "_n", "_l")   // de la meilleure à la moins bonne
        var masterCourant = master
        var repliPartiel: String? = null       // meilleure qualité qui démarre, sans tenir la distance
        for ((rang, suffixe) in ordre.withIndex()) {
            if (rang > 0) {
                masterCourant = nouveauMasterUpbolt(hote, code, link, cookies) ?: masterCourant
            }
            val candidat = rewriteUpboltMasterToVariant(masterCourant, suffixe)
            if (candidat == masterCourant) continue   // cette qualité n'existe pas sur ce fichier
            var corpsPlaylist = ""
            val http = runCatching {
                NetworkClient.default.newCall(requeteCdnUpbolt(candidat, hote, cookies))
                    .execute().use { rep ->
                        if (rep.isSuccessful) corpsPlaylist = rep.peekBody(200_000).string()
                        rep.code
                    }
            }.getOrElse { -1 }
            if (http != 200) {
                android.util.Log.d("OnRegardeOu", "upbolt qualité $suffixe → playlist HTTP $http")
                continue
            }
            // 2026-08-08 ter : la playlist qui répond 200 ne garantit RIEN — mesuré, `_h` a
            //   répondu 200 sur la playlist puis 403 sur son premier segment. On valide donc
            //   la qualité sur le SEGMENT, seul verdict qui compte pour la lecture.
            val segments = Regex("""https?://[^\s"']+\.ts[^\s"']*""").findAll(corpsPlaylist)
                .map { it.value }.toList()
                .ifEmpty {
                    corpsPlaylist.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }
                        .map { rel -> candidat.substringBeforeLast('/') + "/" + rel }.toList()
                }
            if (segments.isEmpty()) {
                android.util.Log.d("OnRegardeOu", "upbolt qualité $suffixe → playlist 200 mais vide")
                continue
            }
            // ⚠ DEUX SEGMENTS CONSÉCUTIFS, CORPS ENTIÈREMENT LU. C'est le SEUL test qui
            //   discrimine, et il a fallu se planter deux fois pour le comprendre :
            //     • valider la playlist seule → les 3 qualités passent, `_h` meurt en lecture ;
            //     • valider avec `Range: bytes=0-1` → les 3 passent encore, `_h` meurt pareil ;
            //     • mesuré en revanche, en GET complets depuis la Chromecast :
            //         `_l` : seg-1 = 200 ET seg-31 = 200   → deux segments entiers servis
            //         `_h` : seg-1 = 200 PUIS seg-2 = 403  → un seul, puis la porte se ferme
            //   Le CDN ne coupe donc pas « au deuxième segment » : il coupe quand le DÉBIT
            //   demandé dépasse ce que le jeton autorise (`sp=1000` dans l'URL). La basse
            //   définition tient sous le plafond, la haute non. On teste donc exactement ce que
            //   le lecteur va faire : enchaîner deux segments entiers.
            //   2026-08-08, DERNIER MOT : le plafond est un DÉBIT (`sp=1000` kbit/s), pas un
            //   nombre de segments — cf. LimiteurDebitUpbolt, qui bride désormais la lecture
            //   sous cette valeur. Toutes les qualités sont donc jouables ; ce qui compte ici,
            //   c'est uniquement de vérifier l'ACCÈS, sans manger le budget du lecteur.
            //   D'où le `Range: bytes=0-1` : deux octets, zéro impact sur le plafond.
            val teste = fun(u: String): Int = runCatching {
                val req = requeteCdnUpbolt(u, hote, cookies).newBuilder()
                    .header("Range", "bytes=0-1")
                    .build()
                NetworkClient.default.newCall(req).execute().use { it.code }
            }.getOrElse { -1 }
            val premier = teste(segments.first())
            val second = if (segments.size > 1) teste(segments[1]) else premier
            android.util.Log.d(
                "OnRegardeOu",
                "upbolt qualité $suffixe → playlist 200, segments consécutifs = $premier puis $second (${segments.size} segments)",
            )
            // 206 = Partial Content, la réponse normale à un `Range`. 200 accepté aussi.
            val ok = fun(c: Int) = c == 200 || c == 206
            if (ok(premier) && ok(second)) return candidat
            if (ok(premier) && repliPartiel == null) repliPartiel = candidat
        }
        repliPartiel?.let {
            android.util.Log.w("OnRegardeOu", "upbolt : aucune qualité ne tient la distance, repli sur celle qui démarre")
            return it
        }
        android.util.Log.w("OnRegardeOu", "upbolt : aucune qualité servie, repli sur la meilleure en aveugle")
        return rewriteUpboltMasterToVariant(master)
    }

    /**
     * 2026-08-08 — LA CAUSE, enfin. Relevé au journal :
     *
     *   16:56:01  DEUX extractions démarrent en parallèle sur le même lien (fils 8267 et 8269)
     *   16:56:04  la première obtient son jeton, variante `_h` retenue
     *   16:56:05  le lecteur demande la playlist → 403 immédiat
     *
     * Deux extractions simultanées = deux POST `/dl` = deux jetons, et **le second invalide le
     * premier**. Le lecteur part donc avec un jeton déjà mort, refusé avant la première image.
     *
     * Ça explique tout ce qui m'a égaré aujourd'hui : le point de rupture qui se déplaçait sans
     * logique (4, 14, 25, 27, 28 — il dépendait de l'instant où une seconde extraction survenait),
     * mes sondes qui réussissaient toujours (elles demandaient leur jeton en dernier), le
     * navigateur qui ne rate jamais (il n'en demande qu'un), et le fait que ma « rotation de
     * jeton » aggravait la situation au lieu de la sauver — elle tuait le jeton du lecteur.
     *
     * On sérialise donc : une seule extraction upbolt à la fois, et le résultat obtenu il y a
     * moins de [FRAICHEUR_MS] est réutilisé tel quel plutôt que d'en redemander un.
     */
    /**
     * 2026-08-08 — UA DESKTOP POUR UPBOLT, aligné sur la règle établie pour Uqload.
     *
     * Uqload tourne sur le MÊME logiciel qu'upbolt : même forme d'URL signée
     * `…/hls2/NN/NNNNN/,id_l,id_n,id_h,.urlset/master.m3u8?t=…&s=…&e=…&sp=…`. Et il fonctionne.
     * La règle notée dans `UqloadExtractor` est explicite : « le serveur filtre sur la
     * COMBINAISON UA + Sec-Fetch », et c'est l'UA Chrome desktop qui passe.
     *
     * Or upbolt faisait exactement l'inverse : UA mobile Pixel 8, et j'avais retiré les Sec-Fetch
     * ce matin en les croyant coupables — sur la foi d'un journal que j'avais mal lu. On aligne.
     * Ça recoupe aussi le seul fait indiscutable de la journée : le navigateur du user, un Chrome
     * desktop, lit ce film de bout en bout à tous les coups.
     */
    private val UPBOLT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/148.0.0.0 Safari/537.36"

    private val verrouUpbolt = kotlinx.coroutines.sync.Mutex()
    private var dernierFluxUpbolt: Pair<String, Video>? = null
    private var dernierFluxUpboltMs = 0L
    private val FRAICHEUR_MS = 60_000L

    /** Point d'entrée unique pour upbolt : sérialise, et réutilise un jeton encore frais. */
    private suspend fun extraireUpboltSerialise(link: String): Video {
        verrouUpbolt.lock()
        try {
            val cache = dernierFluxUpbolt
            if (cache != null && cache.first == link &&
                android.os.SystemClock.elapsedRealtime() - dernierFluxUpboltMs < FRAICHEUR_MS
            ) {
                android.util.Log.d(
                    "OnRegardeOu",
                    "upbolt : jeton obtenu il y a moins d'une minute → on le RÉUTILISE " +
                        "(un second POST /dl invaliderait celui du lecteur)",
                )
                return cache.second
            }
            val flux = extraireUpbolt(link)
            dernierFluxUpbolt = link to flux
            dernierFluxUpboltMs = android.os.SystemClock.elapsedRealtime()
            return flux
        } finally {
            verrouUpbolt.unlock()
        }
    }

    private fun rewriteUpboltMasterToVariant(url: String, suffixeVoulu: String? = null): String {
        try {
            if (!url.contains("upbolt", ignoreCase = true) || !url.contains(".urlset/master.m3u8")) return url
            val q = url.indexOf('?')
            val query = if (q >= 0) url.substring(q) else ""
            val path = if (q >= 0) url.substring(0, q) else url
            val mi = path.indexOf(".urlset/master.m3u8")
            if (mi < 0) return url
            val beforeUrlset = path.substring(0, mi)            // https://.../00014/,id_l,id_n,id_h,
            val lastSlash = beforeUrlset.lastIndexOf('/')
            if (lastSlash < 0) return url
            val prefix = beforeUrlset.substring(0, lastSlash + 1)   // https://.../00014/
            val names = beforeUrlset.substring(lastSlash + 1).trim(',').split(',').filter { it.isNotBlank() }
            if (names.isEmpty()) return url
            val hi = if (suffixeVoulu != null) {
                names.lastOrNull { it.endsWith(suffixeVoulu) } ?: return url
            } else {
                names.lastOrNull { it.endsWith("_h") } ?: names.last()  // meilleure qualité
            }
            val variant = "$prefix$hi/index-v1-a1.m3u8$query"
            android.util.Log.d("OnRegardeOu", "upbolt master→variante: $hi")
            return variant
        } catch (_: Exception) { return url }
    }

    /**
     * upbolt — EXTRACTION NATIVE, sans WebView (2026-08-02, vérifié en direct dans le navigateur).
     *
     * Historique : ce serveur était joué en MIROIR (page de l'hébergeur affichée dans une WebView),
     * au motif que « le CDN DDoS-Guard renvoie 403, le jeton n'est pas rejouable hors navigateur ».
     * L'observation du lecteur a montré autre chose :
     *
     *  • la page `/e/<code>` ne contient AUCUN flux — le bouton « play » n'est pas un lien mais un
     *    FORMULAIRE (`op=embed`, `file_code`, `auto=1`) qui se soumet en POST vers `/dl` ;
     *  • ce POST renvoie la page jwplayer complète, qui contient l'URL `…/master.m3u8` ;
     *  • master ET playlist de variante répondent **200 SANS aucun cookie** (testé sans session).
     *
     * Autrement dit le 403 venait de la requête directe sur le CDN sans passer par ce POST, pas
     * d'une protection infranchissable. On fait donc une simple requête HTTP : pas de WebView, pas
     * d'attente de rendu, et surtout un flux lu NATIVEMENT par ExoPlayer (contrôles, seek, qualité).
     */
    private suspend fun extraireUpbolt(link: String): Video = withContext(Dispatchers.IO) {
        // Code du fichier : …/e/<code>, …/d/<code> ou …/<code>.
        val code = Regex("""/(?:e|d|v|f)/([A-Za-z0-9_-]+)""").find(link)?.groupValues?.get(1)
            ?: link.trimEnd('/').substringAfterLast('/').substringBefore('.')
        if (code.isBlank()) throw Exception("upbolt : code de fichier introuvable dans l'URL")

        val hote = try {
            android.net.Uri.parse(link).host ?: "upbolt.to"
        } catch (_: Exception) { "upbolt.to" }

        // ── Étape 1 : ouvrir la page d'embed avant de poster ────────────────────────────────
        //   Reproduit la navigation réelle (la page pose la session, le POST suit). Testé : un POST
        //   « à froid » renvoie lui aussi un jeton valide, cette étape n'est donc pas strictement
        //   nécessaire — on la garde parce qu'elle colle au comportement d'un navigateur et ne
        //   coûte qu'une requête, ce qui évite de dépendre d'un détail susceptible de changer.
        val reqPage = Request.Builder()
            .url(link)
            .header("User-Agent", UPBOLT_UA)
            .header("Referer", "$mainUrl/")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7")
            .build()
        val cookiesSession = NetworkClient.default.newCall(reqPage).execute().use { rep ->
            rep.headers("Set-Cookie")
                .mapNotNull { it.substringBefore(';').trim().takeIf { c -> c.contains('=') } }
                .joinToString("; ")
        }

        // ── Étape 2 : soumettre le formulaire du bouton « play » (op=embed) ──────────────────
        val corps = FormBody.Builder()
            .add("op", "embed")
            .add("file_code", code)
            .add("auto", "1")
            .add("referer", "")
            .build()
        val req = Request.Builder()
            .url("https://$hote/dl")
            .post(corps)
            .header("User-Agent", UPBOLT_UA)
            .header("Referer", link)
            .header("Origin", "https://$hote")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7")
            .apply { if (cookiesSession.isNotBlank()) header("Cookie", cookiesSession) }
            .build()

        // ⚠ Les cookies posés par ce POST sont INDISPENSABLES à la lecture : c'est là que
        //   DDoS-Guard délivre la session (`__ddg1_`, `__ddg8_`, `__ddg9_`, `__ddg10_`), et le CDN
        //   `edge0X.upbolt.to` refuse le flux (403) sans elle. On ne peut pas compter sur le
        //   partage automatique : la lecture passe par CronetDataSource, dont le magasin de cookies
        //   est SÉPARÉ de celui d'OkHttp utilisé ici. On les récupère donc pour les transmettre à
        //   la main dans l'en-tête `Cookie` du flux.
        var cookies = ""
        val html = NetworkClient.default.newCall(req).execute().use { rep ->
            if (!rep.isSuccessful) throw Exception("upbolt : POST /dl a répondu ${rep.code}")
            // Cookies finaux = ceux de la page, mis à jour par ceux du POST.
            val duPost = rep.headers("Set-Cookie")
                .mapNotNull { it.substringBefore(';').trim().takeIf { c -> c.contains('=') } }
            val fusion = LinkedHashMap<String, String>()
            cookiesSession.split("; ").filter { it.contains('=') }
                .forEach { fusion[it.substringBefore('=')] = it }
            duPost.forEach { fusion[it.substringBefore('=')] = it }
            cookies = fusion.values.joinToString("; ")
            rep.body?.string()
        } ?: throw Exception("upbolt : réponse vide sur /dl")

        val m3u8 = Regex("""https?://[^"'\s\\<>]+\.m3u8[^"'\s\\<>]*""").find(html)?.value
            ?: throw Exception("upbolt : aucun m3u8 dans la réponse (page ou format modifié)")

        // ── Étape 3 : OUVRIR LA SESSION SUR LE CDN ────────────────────────────────────────────
        // 2026-08-08 (user : « lui il fonctionne bien, alors qu'est-ce qui va pas ») — MESURÉ,
        //   six requêtes sur la MÊME URL, à la même seconde, depuis le PC du user :
        //     MASTER   nu / Referer seul / +Accept / +cookies de l'app  → 200 (1757 o) à chaque fois
        //     VARIANTE nu / +Referer +Accept                            → 403
        //   Donc le master N'EST PAS gaté (contrairement à ce que suppose
        //   `rewriteUpboltMasterToVariant`, qui l'aggraverait) : ce sont les variantes et les
        //   segments qui le sont.
        //   Et surtout : ce même master répondait 403 dix minutes plus tôt depuis le même PC.
        //   Entre-temps j'avais lu le film dans le navigateur — DDoS-Guard laisse passer l'IP
        //   après un challenge réussi. La Chromecast, elle, ne le passe jamais.
        //
        //   Le hic : la session obtenue ci-dessus vient de `upbolt.to`, l'hôte de la page
        //   d'embed. Le flux, lui, sort de `edge0X.upbolt.to` — un AUTRE hôte — et DDoS-Guard
        //   délivre ses sessions PAR HÔTE. On envoyait donc au CDN des cookies qui ne valent
        //   pas pour lui.
        //
        //   On demande donc le master ici même, avec le client qui détient déjà la session, et
        //   on récupère les cookies que le CDN pose au passage pour les transmettre au lecteur.
        //   Le résultat est journalisé tel quel : si le CDN répond 200 à l'application, le
        //   problème n'est qu'un passage de témoin ; s'il répond 403, le blocage est réseau et
        //   il faudra chercher ailleurs. Aucune supposition n'est faite ici.
        runCatching {
            val reqCdn = Request.Builder()
                .url(m3u8)
                .header("User-Agent", UPBOLT_UA)
                .header("Referer", "https://$hote/")
                .header("Origin", "https://$hote")
                .header("Accept", "*/*")
                .header("Accept-Language", "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7")
                .apply { if (cookies.isNotBlank()) header("Cookie", cookies) }
                .build()
            NetworkClient.default.newCall(reqCdn).execute().use { rep ->
                val duCdn = rep.headers("Set-Cookie")
                    .mapNotNull { it.substringBefore(';').trim().takeIf { c -> c.contains('=') } }
                android.util.Log.d(
                    "OnRegardeOu",
                    "upbolt CDN master → HTTP ${rep.code}, ${duCdn.size} cookie(s) posés par le CDN",
                )
                // ── ARCHIVE DES SONDES 2026-08-08 (retirées : le coupable est trouvé) ──────
                //   Elles ont servi à éliminer, une par une, toutes les fausses pistes :
                //     master 200 · variante 200 · seg-1 200 (avec ET sans cookies) · seg-31 200
                //     seg-31 avec Sec-Fetch/sec-ch-ua = 200, sans = 200
                //     seg-31 avec Accept-Encoding:identity / Range / autre UA / sans Referer = 200
                //   Autrement dit : en séquentiel, sur UNE seule qualité, tout passe.
                //   Le vrai coupable est visible dans la trace OkHttp du lecteur :
                //     _l/index-v1-a1.m3u8 → 200      (1re qualité, OK)
                //     _n/index-v1-a1.m3u8 → 403      ← 2e qualité demandée
                //     _l/seg-39.ts        → 403      ← et TOUT tombe après, même la 1re qualité
                //   Le CDN grille le jeton dès qu'on demande une SECONDE variante : c'est une
                //   protection anti-téléchargement (un lecteur regarde une qualité, un aspirateur
                //   les récupère toutes). ExoPlayer, lui, sonde les autres qualités pour son
                //   débit adaptatif — il se fait donc bannir tout seul, en deux secondes.
                //   Correctif : on ne lui donne plus le master multi-qualités mais la playlist
                //   d'UNE variante (cf. `rewriteUpboltMasterToVariant`, écrite pour ça). Plus de
                //   qualité adaptative sur upbolt, mais le flux joue.
                // ── ANCIENNE SONDE (désactivée) : le master passe (200) mais la VARIANTE que le lecteur
                //   demande ensuite se prend un 403. Mesuré depuis le PC du user ET depuis Chrome :
                //     • jeton frais minté PAR CHROME  → master 200, variante 200
                //     • MÊME jeton, rejoué 3 min plus tard depuis Chrome → variante 403
                //     • jeton de l'app rejoué depuis Chrome → master 200, variante 403
                //   Donc ni les cookies (un fetch `credentials:'omit'` passe), ni les en-têtes,
                //   ni l'IP : la variante est gatée par quelque chose que le client OkHttp de
                //   l'app ne présente pas. Reste à savoir si CE client-ci (celui qui obtient le
                //   jeton et lit le master) y arrive, ou si seul le lecteur échoue. On demande
                //   donc la variante ICI, avec le client qui vient de réussir le master.
                // Sondes coupées : elles consommaient elles-mêmes des requêtes sur le jeton.
                @Suppress("KotlinConstantConditions")
                if (SONDES_UPBOLT) runCatching {
                    val corpsMaster = rep.peekBody(200_000).string()
                    val variante = Regex("""https?://[^\s"']+index-[^\s"']+\.m3u8[^\s"']*""")
                        .find(corpsMaster)?.value
                    if (variante == null) {
                        android.util.Log.d("OnRegardeOu", "SONDE variante : aucune variante dans le master")
                    } else {
                        val reqVar = reqCdn.newBuilder().url(variante).build()
                        val corpsVar = NetworkClient.default.newCall(reqVar).execute().use { rv ->
                            android.util.Log.d(
                                "OnRegardeOu",
                                "SONDE variante (même client OkHttp) → HTTP ${rv.code} — ${variante.substringBefore('?').takeLast(50)}",
                            )
                            rv.peekBody(200_000).string()
                        }
                        // SONDE 2 : le SEGMENT. C'est lui qui répond 403 au lecteur alors que le
                        //   master et la variante passent. Depuis Chrome, seg-1 ET seg-31 répondent
                        //   200 — donc pas de garde anti-seek. On teste ici les deux seules
                        //   différences qui restent entre l'app et le navigateur : les cookies
                        //   `.upbolt.to` qu'on envoie au CDN, et les en-têtes Sec-Fetch/sec-ch-ua.
                        val segment = Regex("""https?://[^\s"']+\.ts[^\s"']*""").find(corpsVar)?.value
                            ?: corpsVar.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                                ?.let { rel -> variante.substringBeforeLast('/') + "/" + rel }
                        if (segment == null) {
                            android.util.Log.d("OnRegardeOu", "SONDE segment : aucun segment dans la variante")
                        } else {
                            val avecCookies = reqCdn.newBuilder().url(segment).build()
                            val sansCookies = reqCdn.newBuilder().url(segment).removeHeader("Cookie").build()
                            val cAvec = runCatching {
                                NetworkClient.default.newCall(avecCookies).execute().use { it.code }
                            }.getOrElse { -1 }
                            val cSans = runCatching {
                                NetworkClient.default.newCall(sansCookies).execute().use { it.code }
                            }.getOrElse { -1 }
                            android.util.Log.d(
                                "OnRegardeOu",
                                "SONDE segment → avec cookies=$cAvec, sans cookies=$cSans — ${segment.substringBefore('?').takeLast(40)}",
                            )
                            // Segment LOINTAIN (celui que réclame une reprise en cours de film),
                            //   avec puis sans les en-têtes Sec-Fetch/sec-ch-ua : c'est la
                            //   dernière variable qui distingue la sonde du lecteur.
                            val loin = segment.replace(Regex("""seg-\d+-"""), "seg-31-")
                            val nu = reqCdn.newBuilder().url(loin).build()
                            val hinte = reqCdn.newBuilder().url(loin)
                                .header("Sec-Fetch-Dest", "empty")
                                .header("Sec-Fetch-Mode", "cors")
                                .header("Sec-Fetch-Site", "same-site")
                                .header("sec-ch-ua", "\"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\", \"Google Chrome\";v=\"131\"")
                                .header("sec-ch-ua-mobile", "?1")
                                .header("sec-ch-ua-platform", "\"Android\"")
                                .build()
                            val cNu = runCatching {
                                NetworkClient.default.newCall(nu).execute().use { it.code }
                            }.getOrElse { -1 }
                            val cHinte = runCatching {
                                NetworkClient.default.newCall(hinte).execute().use { it.code }
                            }.getOrElse { -1 }
                            android.util.Log.d(
                                "OnRegardeOu",
                                "SONDE seg-31 → sans Sec-Fetch=$cNu, avec Sec-Fetch=$cHinte",
                            )
                            // Les en-têtes ne changent rien (200 dans les deux cas). Ce que la
                            //   sonde n'imite PAS encore, c'est ce que `OkHttpDataSource` ajoute
                            //   d'office par-dessus nos en-têtes. On les teste un par un.
                            val variantes = listOf(
                                "Accept-Encoding=identity" to reqCdn.newBuilder().url(loin)
                                    .header("Accept-Encoding", "identity").build(),
                                "Range=bytes=0-" to reqCdn.newBuilder().url(loin)
                                    .header("Range", "bytes=0-").build(),
                                "UA=NetworkClient" to reqCdn.newBuilder().url(loin)
                                    .header("User-Agent", NetworkClient.USER_AGENT).build(),
                                "sans Referer" to reqCdn.newBuilder().url(loin)
                                    .removeHeader("Referer").build(),
                            )
                            val resultats = variantes.joinToString(", ") { (nom, r) ->
                                val c = runCatching {
                                    NetworkClient.default.newCall(r).execute().use { it.code }
                                }.getOrElse { -1 }
                                "$nom=$c"
                            }
                            android.util.Log.d("OnRegardeOu", "SONDE seg-31 en-têtes lecteur → $resultats")
                        }
                    }
                }.onFailure {
                    android.util.Log.d("OnRegardeOu", "SONDE variante KO : ${it.message}")
                }
                if (duCdn.isNotEmpty()) {
                    val fusion = LinkedHashMap<String, String>()
                    cookies.split("; ").filter { it.contains('=') }
                        .forEach { fusion[it.substringBefore('=')] = it }
                    duCdn.forEach { fusion[it.substringBefore('=')] = it }
                    cookies = fusion.values.joinToString("; ")
                }
            }
        }.onFailure {
            android.util.Log.w("OnRegardeOu", "upbolt CDN master injoignable : ${it.message}")
        }

        android.util.Log.d(
            "OnRegardeOu",
            "upbolt extraction native OK (code=$code, cookies=${cookies.count { it == '=' }})",
        )
        // Le master est une playlist multi-qualités : on le garde tel quel pour laisser ExoPlayer
        //   adapter la définition au débit. `rewriteUpboltMasterToVariant` reste disponible si le
        //   master venait à être gaté un jour (elle fige alors la meilleure qualité).
        // ⚠ DDoS-Guard ne se contente pas du jeton : il vérifie que la requête RESSEMBLE à celle
        //   d'un navigateur. Vérifié en direct : depuis Chrome le master répond 200, alors que la
        //   même URL avec seulement UA + Referer + Cookie répondait 403 depuis l'application.
        //   La différence tient aux en-têtes que Chrome ajoute d'office (Accept, Accept-Language,
        //   Sec-Fetch-*, sec-ch-ua). On les reproduit donc à l'identique.
        // 2026-08-08 — ON NE SERT PLUS LE MASTER, mais UNE variante.
        //   Le CDN grille le jeton dès qu'une SECONDE qualité est demandée (mesuré : `_l` 200,
        //   puis `_n` 403, puis TOUT en 403 y compris `_l` qui marchait). ExoPlayer sonde les
        //   autres qualités pour son débit adaptatif → il se bannit tout seul en deux secondes.
        //   En lui donnant directement la playlist d'une seule qualité, il n'a plus rien d'autre
        //   à demander. On y perd le changement de qualité automatique sur cet hébergeur ;
        //   c'est le prix pour qu'il joue.
        //   2026-08-08 bis — QUELLE variante ? Mesuré sur la Chromecast : `_l` répond 200,
        //   `_n` et `_h` répondent 403 — alors que depuis le PC les trois répondent 200, dans
        //   n'importe quel ordre. On ne peut donc pas figer `_h` en aveugle (log : `_h` = 403
        //   dès la 1re requête), ni se résigner au `_l` (le user veut la meilleure qualité).
        //   On DEMANDE donc, de la meilleure à la moins bonne, et on garde la première servie.
        //   ⚠ Un 403 empoisonne le jeton (constaté : après un `_n` refusé, même `_l` tombe en
        //   403). Chaque essai repart donc d'un jeton NEUF — un simple re-POST sur `/dl`.
        val fluxUneQualite = rewriteUpboltMasterToVariant(m3u8)
        // 2026-08-08 — MISE EN ROUTE DE LA PLAYLIST, ET RIEN D'AUTRE.
        //   Fait mesuré : quand l'extracteur allait lui-même chercher cette playlist avant de
        //   rendre la main, la lecture démarrait et tenait jusqu'au segment 25. Quand il ne le
        //   fait pas, le tout premier appel du lecteur sur cette même URL est refusé (403), alors
        //   que le master vient d'être servi en 200 une seconde plus tôt.
        //   C'est le seul élément du gros bloc de négociation supprimé ce soir qui avait un effet
        //   démontrable ; je le remets seul, sans la sélection de qualité ni les jetons multiples
        //   qui, eux, aggravaient les choses.
        runCatching {
            val reqPlaylist = Request.Builder()
                .url(fluxUneQualite)
                .header("User-Agent", UPBOLT_UA)
                .header("Referer", "https://$hote/")
                .header("Origin", "https://$hote")
                .header("Accept", "*/*")
                .header("Accept-Language", "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7")
                .apply { if (cookies.isNotBlank()) header("Cookie", cookies) }
                .build()
            NetworkClient.default.newCall(reqPlaylist).execute().use { rep ->
                android.util.Log.d("OnRegardeOu", "upbolt : mise en route de la playlist → HTTP ${rep.code}")
            }
        }
        Video(
            source = fluxUneQualite,
            headers = buildMap {
                put("User-Agent", UPBOLT_UA)
                put("Referer", "https://$hote/")
                put("Origin", "https://$hote")
                put("Accept", "*/*")
                put("Accept-Language", "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7")
                // Sec-Fetch/Sec-Ch-Ua REMIS, et cohérents avec l'UA desktop ci-dessus.
                //   Je les avais retirés ce matin en les croyant coupables ; c'était une
                //   erreur de lecture du journal. La règle établie sur Uqload — même logiciel,
                //   même forme d'URL `,l,n,h,.urlset` — dit l'inverse : ce serveur filtre sur
                //   la COMBINAISON UA + Sec-Fetch, et c'est l'UA desktop qui passe.
                put("Sec-Fetch-Dest", "empty")
                put("Sec-Fetch-Mode", "cors")
                put("Sec-Fetch-Site", "same-site")
                put("Sec-Ch-Ua", "\"Chromium\";v=\"148\", \"Google Chrome\";v=\"148\", \"Not_A Brand\";v=\"99\"")
                put("Sec-Ch-Ua-Mobile", "?0")
                put("Sec-Ch-Ua-Platform", "\"Windows\"")
                // 2026-08-08 : Sec-Fetch-* et sec-ch-ua RETIRÉS. Mesuré sur l'appareil, même
                //   jeton, à 8 secondes d'écart :
                //     sonde OkHttp SANS ces en-têtes  → seg-1  = 200 (avec ET sans cookies)
                //     lecteur AVEC ces en-têtes       → seg-31 = 403
                //   Et depuis Chrome, avec un jeton neuf, seg-31 demandé EN PREMIER répond 200 :
                //   il n'y a donc ni garde anti-reprise, ni segment manquant. La seule variable
                //   qui restait entre la requête qui passe et celle qui échoue, ce sont ces
                //   en-têtes-là. On envoie désormais au CDN exactement ce que la sonde envoie.
                if (cookies.isNotBlank()) put("Cookie", cookies)
            },
        )
    }

    override suspend fun extract(link: String): Video {
        // ── upbolt : EXTRACTION NATIVE (2026-08-02, décision user : « soit il marche en extraction
        //   normale, pas de WebView ») ────────────────────────────────────────────────────────
        //   Le miroir précédent affichait la page de l'hébergeur (avec ses pubs) et privait des
        //   contrôles natifs. On revient donc à une extraction classique, rendue possible par un
        //   constat déjà établi ici même : le MASTER `.urlset/master.m3u8` est bien gaté par
        //   DDoS-Guard (403), mais la playlist de VARIANTE (`<id>_h/index-v1-a1.m3u8`) se lit avec
        //   le SEUL token de l'URL — c'est précisément ce que fait `rewriteUpboltMasterToVariant`,
        //   écrite pour ça mais jamais atteinte car ce bloc renvoyait le miroir avant elle.
        //
        //   Le m3u8 n'est pas dans le HTML : le player l'émet en XHR. On le capte donc avec le
        //   résolveur HEADLESS (WebView de résolution, jamais affichée à l'écran — rien à voir
        //   avec le lecteur WebView), puis on joue le flux nativement dans ExoPlayer.
        if (link.contains("upbolt", ignoreCase = true)) {
            return extraireUpbolt(link)
        }
        // ── uns.bio : EXTRACTION NATIVE VIA LE RÉSOLVEUR OnlyFlix (2026-08-05) ───────────
        //   Ce miroir était banni depuis juillet (« player vidstack, PoW “Verifying human…”
        //   à cliquer, ne joue pas headless »). C'ÉTAIT UNE FAUSSE PISTE, démontrée en direct
        //   dans le navigateur : son bundle JS pèse 883 029 octets contre 883 091 pour
        //   `neocine.embedseek.com` — c'est LE MÊME lecteur, aux mêmes chaînes près
        //   (`Adblock Detected`, `Headless Detected`, `ima3.js`, `/api/v1/video`, `cf-master`).
        //
        //   Le vrai blocage était donc la détection de bloqueur de publicités, corrigée le
        //   même jour pour EmbedSeek : le site exige que le SDK `imasdk.googleapis` se charge
        //   avant de réclamer `/api/v1/video`, et nous le bloquions. Depuis ce correctif,
        //   `OnlyFlixResolver` obtient le manifeste signé sur cette famille de lecteurs.
        //
        //   ⚠ 2026-08-05, second constat : quand ce miroir échoue, ce n'est pas forcément le
        //   lecteur. Sur « Astérix — L'Empire du Milieu », un VRAI clic humain dans Chrome
        //   affiche « Désolé, cette vidéo n'est pas disponible. » — le média est mort sur ce
        //   miroir alors que les endpoints répondent encore 200. On lève donc une erreur de
        //   contenu mort plutôt que de retomber sur le chemin générique, qui finissait par
        //   capter n'importe quoi (un mouchard Yandex) et par proposer un serveur illisible.
        if (link.contains("uns.bio", ignoreCase = true)) {
            val master = try {
                OnlyFlixResolver.resolveMasterM3u8(link)
            } catch (e: Exception) {
                android.util.Log.w("OnRegardeOu", "uns.bio : résolution headless KO (${e.message})")
                null
            }
            if (!master.isNullOrBlank()) {
                val origine = try {
                    val u = java.net.URL(link); "${u.protocol}://${u.host}"
                } catch (_: Exception) { "https://uns.bio" }
                android.util.Log.d("OnRegardeOu", "uns.bio : master obtenu → lecture native")
                return Video(
                    source = master,
                    type = androidx.media3.common.MimeTypes.APPLICATION_M3U8,
                    headers = mapOf(
                        "User-Agent" to com.streamflixreborn.streamflix.utils.WebViewResolver.STEALTH_UA,
                        "Referer" to "$origine/",
                        "Origin" to origine,
                    ),
                    fileName = OnlyFlixResolver.dernierTitre,
                )
            }
            // Pas de manifeste par la voie rapide → on laisse le chemin générique tenter sa
            //   chance. C'est sans danger depuis que l'intercepteur écarte les mouchards
            //   (cf. le garde-fou Yandex dans shouldInterceptRequest).
            //   ⚠ NE PAS transformer cet échec en erreur « contenu mort » : essai fait le
            //     5 août puis annulé. Le message « cette vidéo n'est pas disponible » que
            //     j'avais relevé venait de MES propres manipulations JavaScript sur la page
            //     (div supprimés, window.open neutralisé, play() appelé en boucle), pas du
            //     site. Sur une page rechargée proprement, ce miroir LIT. Un simple échec du
            //     résolveur ne prouve donc rien sur la vitalité du contenu.
            android.util.Log.d("OnRegardeOu", "uns.bio : repli sur le chemin générique")
        }
        // onregardeou → URL de l'HÔTE réel (bysezoxexe = player Filemoon).
        val hostUrl = resolveHostUrl(link) ?: link
        // On charge l'HÔTE directement (bysezoxexe.com/e/<id>). C'EST LUI qui crée
        //   l'iframe du player (q8y5z) avec le bon parent + handshake postMessage :
        //   le player démarre SEUL, résout son propre anti-bot (pow.js), et le m3u8
        //   part dans les frames imbriquées → intercepté par la WebView (qui, sur
        //   device, voit TOUTES les frames, contrairement à Chrome).
        //   ⚠ NE PAS descendre jusqu'à q8y5z : chargé seul c'est une page de
        //   redirection sans parent → le player ne s'initialise jamais (écran noir).
        val (rawStreamUrl, streamReferer) = extractByIntercepting(hostUrl, "$mainUrl/")
            ?: throw Exception("OnRegardeOu: aucun flux capté pour $hostUrl")
        // upbolt (KVS) : le MASTER `.../,a,b,c,.urlset/master.m3u8` est gated par DDoS-Guard
        //   → 403 même avec cookie/Cronet/DefaultHttp (prouvé). Mais le token `t=` couvre tout
        //   le préfixe de chemin, et la playlist de VARIANTE `<hi>/index-v1-a1.m3u8` n'est PAS
        //   gatée (elle joue au token seul). On vise donc directement la meilleure variante.
        val streamUrl = rewriteUpboltMasterToVariant(rawStreamUrl)
        // HLS si .m3u8 OU master .txt/.urlset OU chemin /hls (uns.bio, streamwish…).
        //   Sinon (mp4 direct) → type null (auto-détecté par ExoPlayer).
        val isHls = streamUrl.contains(".m3u8") || streamUrl.contains(".txt") ||
            streamUrl.contains("master") || streamUrl.contains(".urlset") || streamUrl.contains("/hls")
        val originHost = Regex("(https?://[^/]+)").find(streamReferer)?.groupValues?.get(1) ?: mainUrl
        // Cookie de session posé par la WebView pendant la lecture (datadome / cf_clearance
        //   sur .upbolt.to). SANS lui, ExoPlayer démarre puis SE FAIT COUPER au bout de
        //   quelques segments (le CDN edge0X.upbolt.to redemande le cookie). On le capte
        //   sur le domaine du flux ET sur upbolt.to (le datadome est souvent posé domaine-wide).
        val streamCookie = buildString {
            try { android.webkit.CookieManager.getInstance().getCookie(streamUrl)?.let { append(it) } } catch (_: Exception) {}
            try {
                val extra = android.webkit.CookieManager.getInstance().getCookie(originHost)
                if (!extra.isNullOrBlank() && !this.contains(extra)) { if (isNotEmpty()) append("; "); append(extra) }
            } catch (_: Exception) {}
        }
        val headers = mutableMapOf(
            // Referer = la frame qui a réellement demandé le m3u8 (q8y5z/bysezoxexe),
            //   sinon le CDN (sprintcdn) refuse la lecture dans ExoPlayer.
            "Referer" to streamReferer,
            "Origin" to originHost,
            "User-Agent" to ANDROID_CHROME_UA,
        )
        if (streamCookie.isNotBlank()) headers["Cookie"] = streamCookie
        // upbolt (DDoS-Guard sur edge0X.upbolt.to) : le CDN sert le NAVIGATEUR (200) mais
        //   403 un client « nu ». Il vérifie la cohérence des en-têtes fetch que Chrome
        //   ajoute automatiquement pour un XHR de manifeste HLS. On les rejoue à l'identique.
        if (streamUrl.contains("upbolt", ignoreCase = true)) {
            headers["Accept"] = "*/*"
            headers["Accept-Language"] = "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7"
            headers["Sec-Fetch-Dest"] = "empty"
            headers["Sec-Fetch-Mode"] = "cors"
            headers["Sec-Fetch-Site"] = "same-site"
        }
        return Video(
            source = streamUrl,
            type = if (isHls) androidx.media3.common.MimeTypes.APPLICATION_M3U8 else null,
            headers = headers,
        )
    }

    /** Vrai geste tactile (MotionEvent down+up) au point donné → démarre le player
     *  q8y5z (qui exige une interaction). La whitelist de navigation empêche les
     *  popunders déclenchés par ce geste de détourner la frame. */
    private fun realTap(view: WebView, x: Float, y: Float) {
        val now = android.os.SystemClock.uptimeMillis()
        val down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
        val up = android.view.MotionEvent.obtain(now, now + 60, android.view.MotionEvent.ACTION_UP, x, y, 0)
        try { view.dispatchTouchEvent(down); view.dispatchTouchEvent(up) } catch (_: Exception) {}
        down.recycle(); up.recycle()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractByIntercepting(url: String, referer: String = "$mainUrl/"): Pair<String, String>? =
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(28_000L) {
                suspendCancellableCoroutine { cont ->
                    var resolved = false
                    fun resolve(value: Pair<String, String>?) {
                        if (!resolved && cont.isActive) { resolved = true; cont.resume(value) {} }
                    }

                    // Whitelist de navigation DYNAMIQUE : la chaîne fixe (onregardeou/
                    //   bysezoxexe/q8y5z) + le domaine du mirror réellement chargé
                    //   (upbolt.to, uns.bio…) → le player du mirror peut naviguer chez
                    //   lui, mais les popunders pub (autres domaines) restent bloqués.
                    val targetDomain = try {
                        val h = android.net.Uri.parse(url).host ?: ""
                        val parts = h.split(".")
                        if (parts.size >= 2) parts.takeLast(2).joinToString(".") else h
                    } catch (_: Exception) { "" }
                    val navAllowed = ALLOWED_NAV_HOSTS + listOfNotNull(targetDomain.takeIf { it.isNotBlank() })

                    val webView = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.userAgentString = ANDROID_CHROME_UA
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.mediaPlaybackRequiresUserGesture = false
                        // CLÉ (2026-07-01) : force le WebView à RASTER même hors écran /
                        //   caché → le player JW considère la page "visible" et AUTOPLAY
                        //   muet (comme dans Chrome), sans aucun geste. Le m3u8 part alors
                        //   à l'init du player → intercepté. Sans ça, un WebView caché ne
                        //   raster pas → le player attend un geste et ne joue jamais.
                        try { settings.offscreenPreRaster = true } catch (_: Exception) {}
                    }
                    // Attache la WebView DERRIÈRE l'UI (index 0, quasi invisible) sur
                    //   l'activité courante → elle a une vraie fenêtre, donc les
                    //   dispatchTouchEvent sont de VRAIS gestes que le player accepte
                    //   (le PoW pow.js ne démarre qu'après une interaction humaine).
                    //   Invisible pour l'user (alpha ~0 + derrière tout).
                    var attachedRoot: android.view.ViewGroup? = null
                    try {
                        val act = StreamFlixApp.currentActivity
                        val root = act?.findViewById<android.view.ViewGroup>(android.R.id.content)
                        if (root != null) {
                            webView.alpha = 0.004f   // 2026-08-02 : voile gris visible a 0.02 (WebView d'extraction)
                            root.addView(webView, 0, android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT))
                            attachedRoot = root
                        } else {
                            webView.layout(0, 0, 1280, 720)
                        }
                    } catch (_: Exception) { webView.layout(0, 0, 1280, 720) }

                    // Bloque toutes les fenêtres (pop-unders pub) + log console (debug).
                    webView.webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
                        ): Boolean = false
                        override fun onConsoleMessage(cm: android.webkit.ConsoleMessage?): Boolean {
                            android.util.Log.d("OnRegardeOuJS", "${cm?.message()}".take(180))
                            return true
                        }
                    }

                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val host = request?.url?.host?.lowercase() ?: return true
                            val path = request.url?.path?.lowercase().orEmpty()
                            // upbolt : le tap « play » tente de NAVIGUER la page vers /dl (page de
                            //   téléchargement) → ça tue le player avant que le m3u8 ne parte. Comme
                            //   upbolt.to est dans la whitelist (même domaine que l'embed), il faut
                            //   bloquer explicitement cette redirection. Le flux HLS passe par XHR/
                            //   hls.js (shouldInterceptRequest), PAS par ce hook → le bloquer est sûr.
                            if (host.contains("upbolt") && (path.endsWith("/dl") || path.contains("/download") || path.contains("/d/"))) {
                                android.util.Log.d("OnRegardeOu", "NAV upbolt /dl bloquée (garde le player vivant)")
                                return true
                            }
                            // WHITELIST STRICTE de navigation : on n'autorise QUE la chaîne
                            //   du player (onregardeou → bysezoxexe → q8y5z). TOUTE autre
                            //   navigation = popunder pub (torontocasbahs, pieshopweedish…)
                            //   qui détourne la frame principale et éjecte le player.
                            //   (Le m3u8/les assets passent par XHR/hls.js → shouldInterceptRequest,
                            //    PAS par ce hook navigation, donc les bloquer ici est sans risque.)
                            val allowed = navAllowed.any { host.contains(it) }
                            if (!allowed) android.util.Log.d("OnRegardeOu", "NAV bloquée (pub): $host")
                            return !allowed  // true = on bloque la navigation
                        }

                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val reqUrl = request?.url?.toString() ?: return null
                            val host = request.url?.host?.lowercase() ?: ""
                            // DEBUG : log les requêtes média/API/CDN pour voir ce que la
                            //   WebView reçoit réellement (frames profondes comprises).
                            if (reqUrl.contains(".m3u8") || reqUrl.contains(".mp4") || reqUrl.contains(".ts") ||
                                reqUrl.contains("master") || reqUrl.contains("/api/") || reqUrl.contains("sprintcdn") ||
                                reqUrl.contains("hls") || reqUrl.contains("stream") || reqUrl.contains("q8y5z") ||
                                reqUrl.contains(".m3u") || reqUrl.contains("playlist") || reqUrl.contains("cdn")) {
                                android.util.Log.d("OnRegardeOuNet", reqUrl.take(160))
                            }
                            // ⚠⚠ 2026-08-05 — LES PUBS/TRACKERS SONT ÉCARTÉS *AVANT* LA CAPTURE.
                            //   Bug constaté : le serveur `uns.bio` s'affichait en ROUGE avec, pour
                            //   « flux », `https://mc.yandex.com/watch/98086865?wmode=7&page-url=…`.
                            //   Cause : les tests ci-dessous scrutaient TOUTE l'URL, paramètres
                            //   compris. Or Yandex Metrika transmet le titre de la page dans un
                            //   paramètre — ici `Asterix…(2023).mkv.mp4` — donc `contains(".mp4")`
                            //   se déclenchait sur du tracking pur. Un serveur bidon était émis.
                            //   Double garde : (1) hôtes bloqués testés en premier, (2) les tests
                            //   d'extension ne portent plus que sur le CHEMIN, jamais la requête.
                            if (BLOCKED_HOSTS.any { host.contains(it) } ||
                                host.contains("yandex") || host.contains("metrika")) {
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }
                            val chemin = (request.url?.path ?: "").lowercase()
                            // Capture le flux : m3u8 (master) ou mp4 direct.
                            val isM3u8 = chemin.endsWith(".m3u8") || chemin.contains(".m3u8")
                            val isMp4 = chemin.endsWith(".mp4") && !chemin.contains("thumb")
                            // Certains mirrors servent le MASTER HLS en .txt (uns.bio →
                            //   vinturastudios `/v4/epu/<id>/cf-master.<ts>.txt`). On le
                            //   capte aussi (ExoPlayer le lira en HLS via le MimeType forcé).
                            val isTxtMaster = chemin.contains(".txt") &&
                                (chemin.contains("master") || chemin.contains("/epu/") || chemin.contains("/hls"))
                            if (isM3u8 || isMp4 || isTxtMaster) {
                                // Referer réel de la frame qui demande le flux (q8y5z/
                                //   bysezoxexe) → indispensable pour que le CDN accepte
                                //   la lecture ExoPlayer. Fallback : origin de l'URL.
                                val reqReferer = request.requestHeaders?.get("Referer")
                                    ?: request.requestHeaders?.get("referer")
                                    ?: (Regex("(https?://[^/]+)").find(reqUrl)?.groupValues?.get(1)?.plus("/") ?: referer)
                                android.util.Log.d("OnRegardeOu", "flux INTERCEPTÉ: ${reqUrl.take(120)} | ref=$reqReferer")
                                resolve(Pair(reqUrl, reqReferer))
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }
                            // Bloque les requêtes pub/tracker.
                            if (BLOCKED_HOSTS.any { host.contains(it) }) {
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }
                            return null
                        }

                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            if (view == null || resolved) return
                            android.util.Log.d("OnRegardeOu", "onPageFinished: $finishedUrl")
                            // GESTE nécessaire pour démarrer le player q8y5z (il n'autoplay
                            //   pas). Les popunders déclenchés par ce geste sont neutralisés
                            //   par la whitelist de navigation (shouldOverrideUrlLoading) →
                            //   ils ne peuvent plus détourner la frame. On tape au centre +
                            //   video.play() en secours.
                            val kick = Runnable {
                                if (resolved) return@Runnable
                                view.evaluateJavascript(AUTO_PLAY_JS, null)
                                val cx = (if (view.width > 0) view.width else 720) / 2f
                                val cy = (if (view.height > 0) view.height else 1280) / 2f
                                realTap(view, cx, cy)
                            }
                            view.postDelayed(kick, 2_000L)
                            view.postDelayed(kick, 5_000L)
                            view.postDelayed(kick, 9_000L)
                            view.postDelayed(kick, 14_000L)
                        }
                    }

                    android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                    // Garde le WebView "actif" même caché → la lecture média n'est pas
                    //   mise en pause par le système (sinon autoplay bloqué).
                    try { webView.onResume(); webView.resumeTimers() } catch (_: Exception) {}
                    webView.loadUrl(url, mapOf("Referer" to referer))

                    cont.invokeOnCancellation {
                        resolved = true
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            try { attachedRoot?.removeView(webView) } catch (_: Exception) {}
                            try { webView.stopLoading(); webView.destroy() } catch (_: Exception) {}
                        }
                    }
                }
            }
        }

    companion object {
        // UNIQUEMENT des régies pub / trackers. NE PAS mettre les hôtes vidéo
        //   (bysezoxexe, chuckle-tube, uns.bio, upbolt…) : ce sont les serveurs réels.
        private val BLOCKED_HOSTS = listOf(
            "googlesyndication", "doubleclick", "adservice", "popads", "popunder",
            "popcash", "propellerads", "exoclick", "juicyads", "trafficjunky",
            "googletagmanager", "google-analytics", "sewarsremeets", "frs2c", "jnbhi",
        )

        /** WHITELIST de navigation : SEULS ces hôtes (la chaîne du player) peuvent
         *  charger une page/frame. Tout le reste = popunder pub → bloqué. Le flux
         *  vidéo (m3u8 sprintcdn) passe par XHR/hls.js → shouldInterceptRequest, donc
         *  il n'est PAS concerné par ce filtre de navigation. */
        private val ALLOWED_NAV_HOSTS = listOf(
            "onregardeou", "bysezoxexe", "q8y5z",
        )

        /** Lance la lecture SANS cliquer d'overlay (les clics d'overlay q8y5z
         *  déclenchent les popunders pub). On appelle juste video.play() sur la
         *  balise <video> — le player autoplay de toute façon (gesture désactivé).
         *  Le m3u8 part à l'init du player → intercepté. */
        private const val AUTO_PLAY_JS = """
            (function(){
                function drive(doc){
                    try {
                        var v = doc.querySelector('video');
                        if (v){ try{ v.muted=true; v.play(); }catch(e){} }
                        // upbolt : bouton #vid_play qui injecte l'iframe player (le vrai
                        //   player n'existe qu'APRÈS ce clic ; la redirection /dl est
                        //   bloquée côté navigation → seul l'iframe reste).
                        var b = doc.getElementById('vid_play'); if (b){ try{ b.click(); }catch(e){} }
                        // boutons play génériques (jwplayer/video.js/overlays)
                        var pl = doc.querySelectorAll('.jw-icon-display,.vjs-big-play-button,[class*=play-button],[id*=play]');
                        for (var k=0;k<pl.length;k++){ try{ pl[k].click(); }catch(e){} }
                    } catch(e){}
                }
                try {
                    var ifr = document.querySelectorAll('iframe');
                    // 1er passage : pas encore d'iframe → on pilote le top (clic #vid_play
                    //   → injecte l'iframe player). Passages suivants : on pilote l'iframe
                    //   same-origin (son #vid_play / <video> → le m3u8 part → intercepté).
                    if (ifr.length === 0){ drive(document); }
                    else {
                        for (var i=0;i<ifr.length;i++){
                            try{ var d = ifr[i].contentDocument; if (d){ drive(d); } }catch(e){}
                        }
                    }
                } catch(e){}
            })();
        """
    }
}
