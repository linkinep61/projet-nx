package com.streamflixreborn.streamflix.extractors

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.utils.WebViewResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 2026-07-16 — Résolveur headless pour les players « OnlyFlix » de Movix
 *   (SeekStreaming = seekplayer.vip/.me, EmbedSeek = *.embedseek.com).
 *
 * Contexte (décortiqué en direct dans Chrome sur L'Odyssée) :
 *   `/api/v1/info?id=<id>` renvoie une config chiffrée AES-CBC (WebCrypto), déchiffrée CÔTÉ
 *   CLIENT. Le résultat = l'URL d'un **master.m3u8 proxifié par le player lui-même** :
 *     `https://<host>/hlsmod/<tiktokcdn-host>/<path>/tt/master.m3u8?v=…`
 *   Les SEGMENTS de ce HLS sont du **MPEG-TS caché dans des images PNG** hébergées sur le CDN
 *   de TikTok (`*.tiktokcdn.com/…​.image`) : chaque segment = ~120 octets d'en-tête PNG (jusqu'à
 *   IEND) PUIS le TS pur (sync 0x47 tous les 188 octets, longueur exactement multiple de 188).
 *   hls.js strippe le PNG côté client. → cf. SeekStreamPngDataSource pour rejouer ça dans ExoPlayer.
 *
 * Le déchiffrement AES étant 100% JS (clé rotative, non exfiltrable), on ne le reproduit pas en
 *   Kotlin : on charge la page dans une WebView HEADLESS (invisible), on laisse le JS déchiffrer,
 *   et on capte le master.m3u8 par DEUX voies : (a) interception réseau de la requête
 *   `…/hlsmod/…master.m3u8`, (b) polling de l'attribut `src` du `<media-player>` (posé dès le
 *   déchiffrement, sans lecture). ExoPlayer joue ensuite le m3u8 en natif via le DataSource
 *   qui strippe les en-têtes PNG. Aucune WebView VISIBLE (contrairement à l'ancien overlay).
 */
object OnlyFlixResolver {

    private const val TAG = "OnlyFlixResolver"

    /** Force le player à démarrer pour déclencher le chargement du master.m3u8 par hls.js. */
    private const val PLAY_JS = """
        (function(){try{
            // ⚠⚠ 2026-08-04 — NE PAS REVENIR À `window.open = () => null`.
            //   Renvoyer `null` signifie « popup BLOQUÉE » pour la page. Or ces lecteurs
            //   comptent les ouvertures RÉUSSIES avant de libérer la lecture : c'est la
            //   raison des « trois ou quatre clics » que l'utilisateur doit faire à la main.
            //   Tant qu'on répondait null, le compteur ne bougeait pas, la porte restait
            //   fermée, et le vrai master.m3u8 n'était jamais réclamé — seul le leurre
            //   `preload.m3u8` (404) se chargeait. C'était le vrai blocage.
            //   On renvoie donc une FAUSSE fenêtre : la page croit la popup ouverte, rien
            //   ne s'ouvre réellement, et aucune publicité n'est affichée.
            try{
              if(!window.__faussesFenetres){
                window.__faussesFenetres=0;
                var faire=function(){
                  window.__faussesFenetres++;
                  var faux={closed:false,focus:function(){},blur:function(){},close:function(){this.closed=true;},
                            postMessage:function(){},moveTo:function(){},resizeTo:function(){},
                            document:{write:function(){},writeln:function(){},close:function(){},body:{}},
                            location:{href:'',replace:function(){},assign:function(){}}};
                  try{faux.opener=window;}catch(e){}
                  return faux;
                };
                window.open=faire;
                // certaines gates passent par un <a target="_blank"> synthétique
                try{
                  var clicOrigine=HTMLAnchorElement.prototype.click;
                  HTMLAnchorElement.prototype.click=function(){
                    if(this.target==='_blank'){window.__faussesFenetres++;return;}
                    return clicOrigine.apply(this,arguments);
                  };
                }catch(e){}
              }
            }catch(e){}
            var mp=document.querySelector('media-player');
            if(mp){try{mp.muted=true;}catch(e){}
                   try{mp.setAttribute&&mp.setAttribute('load','eager');mp.setAttribute&&mp.setAttribute('posterLoad','eager');}catch(e){}
                   try{mp.load='eager';}catch(e){}
                   try{if(mp.startLoading)mp.startLoading();}catch(e){}
                   try{if(mp.startLoadingPoster)mp.startLoadingPoster();}catch(e){}}
            // clic réel sur le gros bouton play (déclenche la lecture P2P sans play() direct)
            var b=document.querySelector('.vds-play-button')||document.querySelector('media-play-button')
                 ||document.querySelector('button[aria-label*="lay"]')||document.querySelector('[class*="play"]');
            if(b){try{var r=b.getBoundingClientRect();var x=(r.left+r.width/2)||5,y=(r.top+r.height/2)||5;
                var o={bubbles:true,cancelable:true,composed:true,clientX:x,clientY:y,view:window,button:0};
                b.dispatchEvent(new PointerEvent('pointerdown',o));b.dispatchEvent(new MouseEvent('mousedown',o));
                b.dispatchEvent(new PointerEvent('pointerup',o));b.dispatchEvent(new MouseEvent('mouseup',o));
                b.dispatchEvent(new MouseEvent('click',o));b.click&&b.click();}catch(e){}}
        }catch(e){console.log('PLAY_JS err '+e);}})();
    """

    /** Cherche le master.m3u8 (…/hlsmod/…master.m3u8) via plusieurs sources DOM + regex. */
    private const val PROBE_JS = """
        (function(){try{
            var cands=[];
            var mp=document.querySelector('media-player');
            if(mp){cands.push(mp.src,mp.currentSrc,(mp.getAttribute&&mp.getAttribute('src')));
                   try{if(mp.state){cands.push(mp.state.currentSrc,(mp.state.source&&mp.state.source.src));}}catch(e){}}
            var v=document.querySelector('video'); if(v){cands.push(v.currentSrc,v.src);}
            document.querySelectorAll('source').forEach(function(s){cands.push(s.src||(s.getAttribute&&s.getAttribute('src')));});
            // 2026-08-04 : deux livraisons coexistent — l'ancienne `/tt/master.m3u8`
            //   (seekplayer) et la nouvelle, un manifeste HLS déguisé en `.txt`.
            var bon=function(c){return typeof c==='string' &&
                (c.indexOf('/tt/master.m3u8')>=0 || /\/[a-z0-9-]*master[a-z0-9._-]*\.txt/i.test(c));};
            for(var i=0;i<cands.length;i++){var c=cands[i];
                if(c&&typeof c==='object')c=c.src||c.url;
                if(bon(c))return c;}
            // Dernier recours : la ressource a pu passer sans laisser de trace dans le DOM.
            try{var pe=performance.getEntriesByType('resource');
                for(var k=pe.length-1;k>=0;k--){if(bon(pe[k].name))return pe[k].name;}}catch(e){}
            var h=document.documentElement.innerHTML;
            var m=h.match(/https?:\/\/[^"'\s\\]*\/tt\/master\.m3u8[^"'\s\\]*/)
               || h.match(/https?:\/\/[^"'\s\\]*\/[a-z0-9-]*master[a-z0-9._-]*\.txt[^"'\s\\]*/i);
            if(m)return m[0];
            return '';
        }catch(e){return '';}})();
    """

    /**
     * 2026-08-02 (user : « Movix me renvoie des serveurs VOSTFR qui ne sont pas énumérés — par
     * exemple SeekStreaming joue du VOSTFR alors qu'il joue d'habitude du VF »).
     *
     * Ces lecteurs affichent le NOM DE FICHIER réel dans le `<title>` de la page — par exemple
     * « …S01E01 VOSTFR 1080p WEB x264… » ou « …MULTi.TRUEFRENCH… ». Or le nom de fichier ne ment
     * pas, contrairement à l'étiquette « (VF) » que Movix colle sans vérifier. On le remonte donc
     * jusqu'au `Video.fileName`, que `PlayerViewModel` sait déjà exploiter pour corriger la langue
     * affichée du serveur (mécanisme en place depuis le 1er août pour Rpmvid, mais qu'aucun autre
     * extracteur n'alimentait).
     *
     * Dernier titre observé pendant la résolution. Volatile : écrit depuis le thread principal
     * (WebView), lu juste après par l'extracteur appelant.
     */
    @Volatile
    var dernierTitre: String? = null
        private set

    // ⚠⚠⚠ 2026-08-05 — `imasdk.googleapis` A ÉTÉ RETIRÉ DE CETTE LISTE. NE PAS LE REMETTRE.
    //
    //   Le player embedseek vérifie désormais que le SDK publicitaire Google (ima3.js) se
    //   charge. Son propre code contient les messages « Adblock Detected » et « Please
    //   disable adblock to download this video », ainsi que l'événement `ADS_MANAGER_LOADED`
    //   (constaté en direct dans le bundle du site le 5 août).
    //
    //   Tant qu'on bloquait cet hôte, le site concluait à un bloqueur de publicités et
    //   n'appelait JAMAIS `/api/v1/video` — l'endpoint qui livre la configuration chiffrée du
    //   vrai flux. Le lecteur restait sur le leurre `preload.m3u8`, et l'extraction tombait en
    //   repli overlay après quinze secondes de silence total (aucune requête réseau).
    //   Séquence observée dans un vrai navigateur : `/api/v1/info` → `ima3.js` → `/api/v1/video`.
    //
    //   Charger le SDK est sans danger ici : `window.open` est déjà neutralisé par PLAY_JS
    //   (fausse fenêtre), la WebView est hors écran et détruite dès la résolution. Aucune
    //   publicité n'est affichée à l'utilisateur. Les régies agressives (popads, exoclick…)
    //   restent bloquées.
    private val BLOCKED_HOSTS = listOf(
        "googlesyndication", "doubleclick", "googleads",
        "popads", "popunder", "popcash", "propellerads", "exoclick", "juicyads",
        "trafficjunky", "googletagmanager", "google-analytics", "mc.yandex.ru",
        "yandex.ru", "a-ads.com", "translate.googleapis", "cloudflareinsights",
    )

    /** Vrai geste tactile (MotionEvent down+up) → déclenche la lecture P2P que le player OnlyFlix
     *  exige (isTrusted). Sans ça, seul le leurre preload.m3u8 se charge, jamais le vrai master. */
    private fun realTap(view: WebView, x: Float, y: Float) {
        val now = android.os.SystemClock.uptimeMillis()
        val down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
        val up = android.view.MotionEvent.obtain(now, now + 60, android.view.MotionEvent.ACTION_UP, x, y, 0)
        try { view.dispatchTouchEvent(down); view.dispatchTouchEvent(up) } catch (_: Exception) {}
        down.recycle(); up.recycle()
    }

    /**
     * 2026-08-04 — DEUXIÈME LIVRAISON, découverte en direct dans Chrome.
     *
     * Le player a changé de mode de distribution. Ce qu'on voit désormais sur embedseek :
     *   · manifeste HLS hébergé sur un domaine tiers (`*.velvetstreammedia.store`),
     *     nommé `cf-master.<id>.txt` — l'extension est un LEURRE, le contenu commence bien
     *     par `#EXTM3U` et le serveur répond `application/vnd.apple.mpegurl` ;
     *   · sous-playlists `index-f1-v1-a1.txt`, en chemin RELATIF (donc résolues seules) ;
     *   · segments `init-….woff` / `seg-N-….woff` = **fMP4 BRUT** (`ftypisom`, `moof`),
     *     servis en `video/mp4`. Là encore l'extension ment, mais AUCUN en-tête parasite
     *     n'est ajouté — contrairement aux anciens segments TS-dans-PNG. ExoPlayer les lit
     *     donc nativement, sans DataSource spécial.
     *
     * ⚠ Il faut impérativement forcer `MimeTypes.APPLICATION_M3U8` sur le Video : l'extension
     *   `.txt` empêcherait ExoPlayer de reconnaître un HLS s'il devait deviner tout seul.
     *
     * L'ancienne signature `/tt/master.m3u8` est CONSERVÉE : seekplayer l'utilise encore.
     */
    private fun isMasterM3u8(url: String): Boolean {
        val l = url.lowercase()
        // Ancienne livraison (TikTok CDN, segments TS-dans-PNG) — toujours en service.
        if (l.contains("/tt/master.m3u8")) return true
        if (l.contains("/hlsmod/") && l.contains("master.m3u8")) return true
        // Nouvelle livraison : manifeste maître déguisé en .txt.
        if (Regex("""/[a-z0-9-]*master[a-z0-9._-]*\.txt""").containsMatchIn(l)) return true
        // ── 2026-08-06 : TROISIÈME LIVRAISON — LE VRAI FLUX, SERVI PAR ADRESSE IP ────────
        //   Découvert sur `bll.embedseek.com` une fois le leurre écarté. La page demande
        //   alors ceci :
        //     https://185.237.107.24/v4/<jeton>/<horodatage>/bgm/clow5/master.m3u8?v=…&k=…
        //   Un `master.m3u8` ORDINAIRE, sur un hôte sans nom de domaine, avec un chemin qui
        //   n'a rien à voir avec les deux formes connues. Aucune des règles ci-dessus ne le
        //   reconnaissait : on capturait donc le manifeste-leurre `cf-master…txt` (hôte mort,
        //   sans jeton) et on laissait filer le seul qui marche.
        //   ⚠ Le motif exige « master » dans le nom : `preload.m3u8`, le leurre chargé au
        //     démarrage, ne doit surtout pas correspondre.
        if (Regex("""/[a-z0-9-]*master[a-z0-9._-]*\.m3u8""").containsMatchIn(l)) return true
        return false
    }

    /** Charge la page OnlyFlix en headless et renvoie l'URL du master.m3u8 (ou null si échec). */
    @SuppressLint("SetJavaScriptEnabled")
    // ── DÉLAI ADAPTATIF (2026-08-06) ────────────────────────────────────────────────────
    //   Historique, pour ne pas refaire le tour :
    //     · 5 août — allonger le délai à 28 s ne changeait RIEN : la page restait inerte
    //       après `preload.m3u8`, sans une seule requête pendant 21 s. Le blocage était la
    //       détection de bloqueur de publicités (cf. BLOCKED_HOSTS), pas le temps.
    //     · 6 août — cas INVERSE sur `bll.embedseek.com` : la page vit, mais lentement.
    //       `/api/v1/info` n'est parti qu'au bout de **8,5 s** (contre ~3 s la veille), il ne
    //       restait donc que six secondes et le manifeste n'a pas eu le temps d'arriver.
    //       L'utilisateur retombait sur l'overlay manuel, qu'il ne veut plus voir.
    //   Un délai fixe ne peut pas satisfaire les deux : trop court ici, trop long là-bas
    //   (trente secondes d'attente pour un serveur mort, c'est pire que l'échec).
    //   → On abandonne VITE si la page ne donne aucun signe de vie, et on patiente LONGTEMPS
    //     dès qu'elle a réclamé sa configuration (`/api/v1/`), preuve qu'elle progresse.
    /**
     * ── 2026-08-06 : LE MOTEUR DE RENDU SE FAISAIT TUER, ET ON ATTENDAIT DANS LE VIDE ──────
     *   Diagnostic sur `bll.embedseek.com` (user : « le serveur fonctionne, pourquoi il est
     *   mal extrait ? » — l'overlay manuel lisait le film juste après notre échec) :
     *     · 09:46:11 — `Uncaught ReferenceError: $ is not defined` venant de
     *       `french-anime.com` : UNE AUTRE WebView tournait en même temps, celle d'un autre
     *       provider en pleine recherche.
     *     · 09:46:16 — notre page met **8,5 s** rien qu'à demander `/api/v1/info` (contre ~3 s
     *       la veille) : c'est la contention entre deux moteurs Chromium.
     *     · 09:46:23 — `ActivityManager: Killing …:sandboxed_process0 (adj 0): isolated`.
     *       Le système a SUPPRIMÉ notre moteur de rendu. On n'a pas expiré : on a été tué.
     *   Et comme rien n'écoutait cet événement, la coroutine attendait son délai complet pour
     *   rien, puis repliait sur l'overlay — celui que l'utilisateur ne veut plus voir.
     *
     *   Deux garde-fous, ici même :
     *     1. `onRenderProcessGone` est traité. Il rend la main IMMÉDIATEMENT au lieu d'attendre.
     *        ⚠ Il DOIT renvoyer `true` : sans ça, Android tue l'application entière quand le
     *          moteur de rendu meurt.
     *     2. Une SECONDE tentative est lancée si la première a été tuée. Le champ est
     *        généralement libre à ce moment-là (l'autre WebView a fini), et ça rattrape
     *        exactement le cas observé.
     */
    suspend fun resolveMasterM3u8(pageUrl: String, timeoutMs: Long = 30_000L): String? {
        val premier = tenterResolution(pageUrl, timeoutMs)
        if (premier != null) return premier
        // Seule raison de retenter : le moteur de rendu a été tué avant d'aboutir.
        //   (Les manifestes aux hôtes morts sont désormais écartés à la source, dans
        //   l'intercepteur — relancer toute la résolution n'y changeait rien, la page
        //   reproposant le même hôte.)
        if (!dernierRenduTue) return null
        Log.d(TAG, "moteur de rendu tué → seconde tentative")
        return tenterResolution(pageUrl, timeoutMs)
    }

    /**
     * ── 2026-08-06 : LE MANIFESTE PEUT POINTER VERS UN HÔTE MORT ─────────────────────────
     *   Constaté sur `bll.embedseek.com` : l'extraction réussissait et produisait une URL
     *   PARFAITEMENT signée — `saw.technicalcatalog.site/v4/bgm/clow5/cf-master…txt?k=…&kx=…` —
     *   mais la lecture échouait aussitôt en `UnknownHostException (no network)`.
     *   Vérification faite sur trois résolveurs publics (Cloudflare, Google, Quad9) : ce nom
     *   n'a **ni enregistrement A ni AAAA**, il n'existe tout simplement pas. Ses frères de la
     *   même famille (`slt.velvetstreammedia.store`, `sbi.organicgoods.cfd`) répondent, eux,
     *   sans problème : ces hôtes CDN tournent en permanence et celui-là est tombé.
     *   Une nouvelle résolution obtient généralement un hôte différent, donc vivant.
     *   ⚠ On passe par `DnsResolver` (DoH + replis) et NON par le DNS système : ce dernier est
     *     filtré par certains fournisseurs et confondrait « bloqué chez moi » avec « mort ».
     */
    //   (Le contrôle lui-même vit maintenant dans `shouldInterceptRequest`, au plus près de
    //    la capture : c'est le seul endroit où l'on peut REFUSER un manifeste et laisser la
    //    page en proposer un autre.)

    /** Vrai si la tentative précédente s'est terminée parce que Chromium a été tué. */
    @Volatile
    private var dernierRenduTue = false

    /**
     * Hôtes déjà éprouvés : `true` = répond, `false` = mort, absent = pas encore vérifié.
     *   Alimenté en arrière-plan, consultable sans bloquer depuis le fil principal.
     */
    private val etatsHotes = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** Consultation NON bloquante. Lance la vérification en tâche de fond si nécessaire. */
    private fun etatHote(hote: String): Boolean? {
        etatsHotes[hote]?.let { return it }
        if (verificationsEnCours.add(hote)) {
            Thread {
                val ok = try {
                    com.streamflixreborn.streamflix.utils.DnsResolver.lookup(hote).isNotEmpty()
                } catch (_: Exception) { false }
                etatsHotes[hote] = ok
                verificationsEnCours.remove(hote)
            }.start()
        }
        return null
    }

    private val verificationsEnCours = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun tenterResolution(pageUrl: String, timeoutMs: Long): String? =
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    val context = StreamFlixApp.instance.applicationContext
                    dernierTitre = null   // ⚠ sinon on hériterait du nom de la vidéo précédente
                    dernierRenduTue = false
                    var resolved = false
                    // Vrai dès que la page a réclamé sa configuration : elle est vivante, on
                    //   lui accorde alors le délai long (cf. la note sur le délai adaptatif).
                    var pageVivante = false
                    var destroyHook: (() -> Unit)? = null
                    var attachedRoot: android.view.ViewGroup? = null
                    fun resolve(value: String?) {
                        if (!resolved && cont.isActive) {
                            resolved = true
                            cont.resume(value)
                        }
                        destroyHook?.invoke()
                        destroyHook = null
                    }

                    val webView = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.userAgentString = WebViewResolver.STEALTH_UA
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.mediaPlaybackRequiresUserGesture = false
                    }
                    // Attache la WebView DERRIÈRE l'UI (index 0, alpha ~0) sur l'activité courante :
                    //   elle a alors une VRAIE fenêtre/surface, sinon le player vidstack reste
                    //   « media not ready » et ne déclenche jamais le chargement du master.m3u8.
                    //   Invisible pour l'utilisateur. Fallback measure/layout si pas d'activité.
                    try {
                        val act = StreamFlixApp.currentActivity
                        val root = act?.findViewById<android.view.ViewGroup>(android.R.id.content)
                        if (root != null) {
                            // ⚠ 2026-08-02 (user : « un rectangle gris clair derrière, qui
                            //   disparaît ensuite ») : à 0.02 d'opacité sur toute la surface, la
                            //   page de l'hébergeur — fond clair — formait un voile gris visible
                            //   par-dessus la vidéo pendant l'extraction. On descend à 0.004 (1/255,
                            //   le minimum qui reste « visible » pour Chromium, donc qui continue de
                            //   faire tourner rendu et timers) et on rend la vue non interactive.
                            //   Ne PAS passer à 0f ni à INVISIBLE : le rendu serait suspendu et le
                            //   player ne chargerait jamais son flux.
                            // ════════════════════════════════════════════════════════
                            //  INVISIBLE PAR LA TAILLE, PAS PAR LA TRANSPARENCE
                            // ════════════════════════════════════════════════════════
                            // ⚠⚠⚠ 2026-08-04 — NE PAS REVENIR À UNE ASTUCE D'OPACITÉ.
                            //   Historique, pour ne pas refaire le tour :
                            //     • 0.02 → fonctionnait, mais un voile gris était visible à
                            //       l'œil nu par-dessus la vidéo (signalé par le user).
                            //     • 0.004 (2 août) → voile réglé, mais sous ce seuil Chromium
                            //       SUSPEND le rendu : le player vidstack reste « media not
                            //       ready » et ne réclame jamais le vrai master.m3u8. Il ne
                            //       charge que le leurre `preload.m3u8`, lequel répond 404
                            //       (vérifié en direct dans Chrome). DEUX extracteurs cassés
                            //       pendant deux jours — EmbedSeek et SeekStreaming — tous deux
                            //       repliés sur l'overlay. C'était l'UNIQUE différence
                            //       fonctionnelle avec la version du 25 juillet.
                            //
                            //   Solution (idée du user : « pourquoi ne pas les réduire à une
                            //   taille invisible ? ») — on sépare le GABARIT du DESSIN :
                            //     · les LayoutParams restent MATCH_PARENT, donc la page garde un
                            //       vrai viewport et le player s'initialise normalement ;
                            //     · seule la TRANSFORMATION de dessin est réduite (échelle 1/100
                            //       depuis le coin haut-gauche), avec une opacité PLEINE.
                            //   Chromium considère la vue comme entièrement visible et continue
                            //   de rendre ; à l'écran il ne reste qu'une dizaine de pixels dans
                            //   un coin, derrière l'interface. Ni voile, ni rendu suspendu.
                            //
                            //   ⚠ Ne pas remplacer par des LayoutParams minuscules : le viewport
                            //     deviendrait réellement minuscule et le player refuserait de
                            //     s'initialiser. C'est bien l'échelle de DESSIN qu'il faut,
                            //     jamais la taille de mise en page.
                            webView.alpha = 1f
                            webView.pivotX = 0f
                            webView.pivotY = 0f
                            webView.scaleX = 0.01f
                            webView.scaleY = 0.01f
                            // ⚠⚠ 2026-08-04 — NE JAMAIS REMETTRE `isEnabled = false` ICI.
                            //   C'est ce qui a cassé EmbedSeek (user : « le serveur EmbedSeek,
                            //   vous n'avez pas fait en sorte qu'il ait une extraction
                            //   normale ? »). Le 2 août, en corrigeant le voile gris, la vue
                            //   avait été rendue non interactive « par précaution ».
                            //   Or une vue DÉSACTIVÉE ne traite plus les événements tactiles :
                            //   `dispatchTouchEvent` n'aboutit pas, donc le `realTap` partait
                            //   dans le vide. Sans ce geste, le player OnlyFlix ne charge que
                            //   le leurre `preload.m3u8` et jamais le vrai master.m3u8 — le
                            //   symptôme exact relevé dans les logs (config déchiffrée, puis
                            //   `preload.m3u8`, puis quinze secondes de silence).
                            //   La vue est de toute façon inoffensive : elle est ajoutée à
                            //   l'index 0, donc DERRIÈRE toute l'interface, et détruite dès la
                            //   résolution. `isFocusable = false` suffit à l'empêcher de voler
                            //   le focus D-pad sur TV.
                            webView.isFocusable = false
                            webView.isFocusableInTouchMode = false
                            root.addView(webView, 0, android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT))
                            attachedRoot = root
                        } else {
                            webView.layout(0, 0, 1280, 720)
                        }
                    } catch (_: Exception) {
                        try { webView.layout(0, 0, 1280, 720) } catch (_: Exception) {}
                    }
                    webView.webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onConsoleMessage(m: android.webkit.ConsoleMessage?): Boolean {
                            Log.d(TAG, "JS: ${m?.message()?.take(120)}")
                            return true
                        }
                        override fun onCreateWindow(v: WebView?, d: Boolean, u: Boolean, msg: android.os.Message?): Boolean = false
                    }
                    android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?, request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString() ?: return null
                            val host = request.url?.host?.lowercase() ?: ""
                            val low = reqUrl.lowercase()
                            // Journalisation ciblée. (La trace TOTALE du 6 août a répondu : sur
                            //   `totocoutouno.rpmlive.online`, la page n'émet **aucune** requête
                            //   après `/api/v1/video` — elle ne cherche pas de manifeste. Vérifié
                            //   ensuite dans un vrai navigateur, à la main : le site affiche
                            //   « Désolé, cette vidéo n'est pas disponible. » Le contenu est mort
                            //   sur ce lien, l'extracteur n'y est pour rien. Preuve à l'appui :
                            //   sur un autre film, Rpmvid extrait en 504 ms par la voie native.)
                            if (low.contains("/api/v1/") || low.contains("m3u8") || low.contains("/tt/") || low.contains(".txt")) {
                                Log.d(TAG, "REQ: ${reqUrl.take(100)}")
                            }
                            // Signe de vie : la page a demandé sa configuration ou son leurre.
                            if (low.contains("/api/v1/") || low.contains("preload")) pageVivante = true
                            // (a) interception réseau du master.m3u8
                            if (isMasterM3u8(reqUrl)) {
                                // ── 2026-08-06 : ON REFUSE UN MANIFESTE DONT L'HÔTE EST MORT ──
                                //   Sur `bll.embedseek.com`, le premier manifeste proposé pointe
                                //   vers `saw.technicalcatalog.site`, qui n'a **ni A ni AAAA** sur
                                //   Cloudflare, Google et Quad9 — l'hôte n'existe plus. On le
                                //   capturait quand même et ExoPlayer mourait en
                                //   `UnknownHostException`. Relancer toute la résolution ne servait
                                //   à rien : la page repropose LE MÊME hôte, seul le jeton change.
                                //   Or l'overlay manuel, lui, finit par lire : c'est que la PAGE
                                //   encaisse l'échec et bascule sur un autre hôte. On la laisse
                                //   donc faire — on ne coupe plus au premier manifeste venu, on
                                //   attend celui qui répond vraiment.
                                //   ⚠ `DnsResolver` (DoH) et non le DNS système : sinon on
                                //     confondrait « bloqué par le FAI » avec « mort ».
                                //   Ici on est sur un fil d'arrière-plan : la résolution peut être
                                //   bloquante. On mémorise le résultat pour le sondage DOM, qui,
                                //   lui, tourne sur le fil principal et ne peut pas attendre.
                                val hote = request.url?.host.orEmpty()
                                val hoteOk = try {
                                    com.streamflixreborn.streamflix.utils.DnsResolver
                                        .lookup(hote).isNotEmpty()
                                } catch (_: Exception) { false }
                                etatsHotes[hote] = hoteOk
                                if (!hoteOk) {
                                    Log.w(TAG, "manifeste ignoré, hôte mort: $hote")
                                    return null   // on laisse passer : la page constatera l'échec
                                }
                                Log.d(TAG, "master.m3u8 CAPTURED (intercept): ${reqUrl.take(90)}")
                                resolve(reqUrl)
                                // on bloque : pas besoin de streamer dans la WebView
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }
                            if (BLOCKED_HOSTS.any { host.contains(it) }) {
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }
                            // on coupe le chargement des segments PNG (lourds) dans la WebView
                            if (host.contains("tiktokcdn") || reqUrl.lowercase().contains(".image")) {
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }
                            return null
                        }

                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            if (resolved || view == null) return
                            // Force le player à démarrer (→ hls.js fetch le master.m3u8 qu'on intercepte)
                            //   + poll multi-stratégie (src du media-player / video / regex DOM).
                            val startMs = System.currentTimeMillis()
                            // Page inerte : on rend la main au bout de 12 s pour ne pas faire
                            //   patienter devant un serveur mort. Page vivante : jusqu'à 28 s,
                            //   le temps que le manifeste arrive (mesuré à 8,5 s rien que pour
                            //   la configuration sur un hôte lent).
                            fun budget(): Long = if (pageVivante) 28_000L else 12_000L
                            fun poll() {
                                if (resolved) return
                                // Nom de fichier réel : ces lecteurs le mettent dans le <title>.
                                //   Capturé à chaque tour car le titre n'est renseigné qu'une fois
                                //   la configuration chargée (au départ « Chargement… »).
                                view.evaluateJavascript("document.title") { t ->
                                    val titre = t?.trim('"')?.replace("\\u0026", "&")?.trim()
                                    if (!titre.isNullOrBlank() &&
                                        !titre.startsWith("Chargement", ignoreCase = true)
                                    ) dernierTitre = titre
                                }
                                // relance le déclenchement (le player vidstack s'hydrate progressivement)
                                view.evaluateJavascript(PLAY_JS, null)
                                // VRAI geste tactile au centre = démarre la lecture P2P (→ vrai master.m3u8)
                                val w = if (view.width > 0) view.width else 720
                                val h = if (view.height > 0) view.height else 1280
                                realTap(view, w / 2f, h / 2f)
                                view.evaluateJavascript(PROBE_JS) { result ->
                                    val src = result?.trim('"')?.replace("\\/", "/")?.replace("\\u0026", "&") ?: ""
                                    // ⚠⚠ 2026-08-06 — LE MÊME CONTRÔLE QUE DANS L'INTERCEPTEUR.
                                    //   Il y a DEUX voies de capture : le réseau et ce sondage du
                                    //   DOM. Avoir protégé la première ne servait à rien — sur
                                    //   `bll.embedseek.com`, c'est CELLE-CI qui gagnait, trois
                                    //   secondes plus tôt, et elle renvoyait en plus la version
                                    //   SANS jeton du manifeste, vers un hôte sans aucun
                                    //   enregistrement DNS. D'où `UnknownHostException` à la
                                    //   lecture malgré le garde-fou réseau.
                                    //   ⚠ Ce callback s'exécute sur le FIL PRINCIPAL : pas de
                                    //     résolution DNS bloquante ici, elle gèlerait l'interface.
                                    //     On consulte un cache alimenté en arrière-plan ; tant que
                                    //     la réponse n'est pas connue, on continue simplement de
                                    //     sonder (une passe toutes les 800 ms).
                                    val srcUtilisable = isMasterM3u8(src) && run {
                                        val h = try { java.net.URL(src).host.orEmpty() } catch (_: Exception) { "" }
                                        if (h.isBlank()) true else when (etatHote(h)) {
                                            true -> true
                                            false -> { Log.w(TAG, "DOM probe ignoré, hôte mort: $h"); false }
                                            null -> false   // pas encore vérifié : on repasse plus tard
                                        }
                                    }
                                    if (srcUtilisable) {
                                        Log.d(TAG, "master.m3u8 via DOM probe: ${src.take(90)}")
                                        resolve(src)
                                    } else if (System.currentTimeMillis() - startMs < budget()) {
                                        // ⚠⚠ 2026-08-06 — INUTILE DE CLIQUER PLUS VITE, TESTÉ.
                                        //   La porte du site est le plus gros poste restant :
                                        //   11,9 s entre `api/v1/info` et `preload.m3u8`. On a
                                        //   donc essayé 350 ms au lieu de 800 — trois fois plus
                                        //   de clics sur le même intervalle, sans aucun risque
                                        //   puisque `window.open` est neutralisé et les
                                        //   navigations publicitaires refusées.
                                        //   RÉSULTAT : 13,6 s, soit légèrement PIRE (charge
                                        //   supplémentaire sur la box). La porte est donc
                                        //   TEMPORISÉE, pas comptée en clics. Ce délai-là est
                                        //   imposé par le site, on ne peut pas le raccourcir.
                                        //   (Un premier essai à 500 ms le 5 août n'avait rien
                                        //   prouvé : la porte était alors verrouillée par la
                                        //   détection de bloqueur de publicités.)
                                        view.postDelayed({ poll() }, 800L)
                                    }
                                }
                            }
                            view.postDelayed({ poll() }, 700L)
                        }

                        /**
                         * ── 2026-08-06 : ON NE SE LAISSE PLUS REDIRIGER PAR LES PUBS ────────
                         *   Ces lecteurs ouvrent trois ou quatre publicités par clic. `window.open`
                         *   est déjà neutralisé (fausse fenêtre, cf. PLAY_JS), mais RIEN
                         *   n'empêchait une régie de remplacer carrément la page — un
                         *   `location.href`, un `<a target="_blank">` transformé en navigation,
                         *   une redirection HTTP. Dans ce cas le lecteur disparaissait et la
                         *   résolution attendait dans le vide jusqu'au délai.
                         *   On n'autorise donc QUE le domaine du lecteur lui-même ; toute autre
                         *   navigation de la fenêtre principale est refusée, la page reste en place.
                         */
                        override fun shouldOverrideUrlLoading(
                            view: WebView?, request: WebResourceRequest?
                        ): Boolean {
                            val dest = request?.url ?: return false
                            if (request.isForMainFrame != true) return false
                            val hoteDest = dest.host?.lowercase().orEmpty()
                            val hoteLecteur = try {
                                android.net.Uri.parse(pageUrl).host?.lowercase().orEmpty()
                            } catch (_: Exception) { "" }
                            // Même domaine enregistrable = navigation légitime du lecteur.
                            val racine = { h: String ->
                                h.split(".").let { if (it.size >= 2) it.takeLast(2).joinToString(".") else h }
                            }
                            if (hoteDest.isBlank() || racine(hoteDest) == racine(hoteLecteur)) return false
                            Log.d(TAG, "navigation publicitaire bloquée → $hoteDest")
                            return true
                        }

                        override fun onReceivedError(
                            view: WebView?, request: WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) { /* ignore */ }

                        /**
                         * Le moteur de rendu Chromium a été tué (mémoire, ou trop de WebViews
                         *   en parallèle sur une box modeste). On rend la main tout de suite
                         *   au lieu d'attendre le délai complet dans le vide.
                         * ⚠ DOIT renvoyer `true`, sinon Android tue toute l'application.
                         */
                        override fun onRenderProcessGone(
                            view: WebView?,
                            detail: android.webkit.RenderProcessGoneDetail?
                        ): Boolean {
                            dernierRenduTue = true
                            Log.w(TAG, "moteur de rendu supprimé par le système (crash=${detail?.didCrash()})")
                            resolve(null)
                            return true
                        }
                    }

                    // ⚠ Le nettoyage doit être armé AVANT le chargement : une interception très
                    //   rapide du flux appellerait `resolve()` alors que `destroyHook` est encore
                    //   nul, et la WebView resterait accrochée à l'écran.
                    destroyHook = {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            try {
                                webView.stopLoading()
                                attachedRoot?.removeView(webView)
                                attachedRoot = null
                                webView.destroy()
                            } catch (_: Exception) {}
                        }
                    }
                    cont.invokeOnCancellation {
                        resolved = true
                        destroyHook?.invoke()
                        destroyHook = null
                    }

                    Log.d(TAG, "Loading OnlyFlix page headless: ${pageUrl.take(90)}")
                    webView.loadUrl(pageUrl)
                }
            }
        }
}
