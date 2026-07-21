package com.streamflixreborn.streamflix.fragments.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.util.Rational
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.SubtitleView
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.activities.tools.BypassWebViewActivity
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.ContentExoControllerMobileBinding
import com.streamflixreborn.streamflix.databinding.FragmentPlayerMobileBinding
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.models.WatchItem
import com.streamflixreborn.streamflix.ui.PlayerMobileView
import com.streamflixreborn.streamflix.utils.MediaServer
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.UserDataCache
import com.streamflixreborn.streamflix.utils.dp
import com.streamflixreborn.streamflix.utils.getFileName
import com.streamflixreborn.streamflix.utils.next
import com.streamflixreborn.streamflix.utils.plus
import com.streamflixreborn.streamflix.utils.setMediaServerId
import com.streamflixreborn.streamflix.utils.setMediaServers
import com.streamflixreborn.streamflix.utils.toSubtitleMimeType
import com.streamflixreborn.streamflix.providers.IptvProvider
import com.streamflixreborn.streamflix.utils.viewModelsFactory
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import androidx.core.net.toUri
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cronet.CronetDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import org.chromium.net.CronetEngine
import com.google.android.gms.net.CronetProviderInstaller
import com.streamflixreborn.streamflix.fragments.player.settings.IptvFavorites
import com.streamflixreborn.streamflix.fragments.player.settings.IptvBannedServers
import com.streamflixreborn.streamflix.fragments.player.settings.PlayerSettingsView
import java.util.Base64 
import java.io.File
import java.io.FileOutputStream
import android.graphics.Color
import android.view.Gravity
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.navigation.NavOptions
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.EpisodeManager
import com.streamflixreborn.streamflix.utils.PlayerGestureHelper
import com.streamflixreborn.streamflix.utils.UserDataCache.toEpisode
import com.streamflixreborn.streamflix.utils.UserDataCache.toMovie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
// Removed: import okhttp3.internal.userAgent — it resolves to "okhttp/4.12.0"
import java.util.Locale

class PlayerMobileFragment : Fragment() {
    companion object {
        /** 2026-06-21 (user "panel reste ouvert quand on change d'épisode") :
         *  Set à true par le click handler du panel AVANT switchToEpisode().
         *  Lu par onViewCreated du nouveau fragment instance → réouvre le
         *  panel automatiquement (~200ms après création). */
        @Volatile var pendingReopenPanelMobile: Boolean = false

        private const val NEXT_EPISODE_PREFETCH_THRESHOLD_MS = 60_000L
        private const val NEXT_EPISODE_OVERLAY_MIN_THRESHOLD_MS = 30_000L

        private const val PIP_ACTION_PLAY = "com.streamfr.app.PIP_PLAY"
        private const val PIP_ACTION_PAUSE = "com.streamfr.app.PIP_PAUSE"
        private const val PIP_ACTION_REWIND = "com.streamfr.app.PIP_REWIND"
        private const val PIP_ACTION_FORWARD = "com.streamfr.app.PIP_FORWARD"

        /** Ad / tracking / popup domains blocked in DaddyLive WebView embeds */
        private val AD_BLOCK_PATTERNS = listOf(
            "doubleclick", "googlesyndication", "googleadservices",
            "adservice.google", "pagead2.googlesyndication",
            "trafficjunky", "exoclick", "juicyads", "clickadu",
            "popads", "popcash", "propellerads", "adsterra",
            "hilltopads", "richads", "pushground", "a-ads",
            "ad-maven", "admaven", "revcontent", "mgid",
            "taboola", "outbrain", "criteo", "amazon-adsystem",
            "bidswitch", "openx", "pubmatic", "rubiconproject",
            "spotxchange", "smartadserver",
            "betrad", "bluekai", "bongacams", "chaturbate",
            "livejasmin", "stripchat", "cam4",
            "pushwoosh", "onesignal", "pushengage",
            "notify", "notix", "gravitec",
            "acdn.adnxs", "adnxs.com", "adsrvr.org",
            "serving-sys.com", "zedo.com", "yieldmanager",
            "disqusads", "revdeepak", "pushance",
            // 2026-05-21 (capture user : pub AliExpress plein écran sur Player4me mobile) :
            //   hôtes d'interstitiels vus sur les players anime.
            "aliexpress", "decafeligibly", "morphify",
        )

        /** JS injected into DaddyLive embeds to kill popup ads and overlays */
        private const val DADDYLIVE_AD_KILL_JS = """
            (function(){
                // 1. Kill window.open (popup ads)
                window.open = function(){ return null; };

                // 2. Kill alert/confirm/prompt (annoying dialogs)
                window.alert = function(){};
                window.confirm = function(){ return false; };
                window.prompt = function(){ return null; };

                // 3. Periodic DOM cleanup — remove popup/overlay elements
                function killAds() {
                    // Remove elements with high z-index that overlay the video
                    document.querySelectorAll('div,iframe,section,aside').forEach(function(el){
                        var s = getComputedStyle(el);
                        var z = parseInt(s.zIndex) || 0;
                        var pos = s.position;
                        // Skip the video container / player
                        if (el.querySelector('video') || el.closest('video')) return;
                        if (el.id && (el.id.includes('player') || el.id.includes('video'))) return;
                        if (el.className && typeof el.className === 'string'
                            && (el.className.includes('player') || el.className.includes('video')
                                || el.className.includes('hls'))) return;
                        // Kill fixed/absolute overlays with high z-index
                        if ((pos === 'fixed' || pos === 'absolute') && z > 100) {
                            el.remove();
                        }
                    });
                    // Remove iframes that are NOT the player
                    document.querySelectorAll('iframe').forEach(function(f){
                        var src = f.src || '';
                        if (!src.includes('bolaloca') && !src.includes('player')
                            && !src.includes('embed') && src.length > 0) {
                            f.remove();
                        }
                    });
                }
                killAds();
                setInterval(killAds, 2000);

                // 4. Inject CSS to hide common ad patterns
                var css = document.createElement('style');
                css.textContent = [
                    '[id*="ad-"],[id*="ad_"],[class*="ad-overlay"]',
                    ',[class*="popup"],[class*="modal"],[class*="interstitial"]',
                    ',[id*="popup"],[id*="modal"]',
                    '{ display:none !important; }'
                ].join('');
                document.head.appendChild(css);
            })();
        """

        /** JS injecté dans Player4me pour tuer les pubs (interstitiels / overlays
         *  injectés dans le DOM) SANS supprimer le conteneur du player.
         *  2026-05-22 (user "des pubs passent encore" sur mobile) : le tueur
         *  générique DaddyLive enlevait le player Player4me (écran noir). Version
         *  CONSERVATRICE ici : on ne retire QUE
         *    1) les iframes dont le src est un hôte pub connu (jamais l'iframe
         *       du player, dont le src est un CDN),
         *    2) les overlays plein écran cliquables target=_blank cross-origin
         *       (interstitiels), jamais ceux qui contiennent la <video>,
         *    3) les éléments nommés pub (id/class) via CSS display:none.
         *  On ne touche JAMAIS aux fixed/absolute génériques ni aux iframes du
         *  player → pas d'écran noir. */
        private const val PLAYER4ME_AD_KILL_JS = """
            (function(){
                try {
                    window.open = function(){ return null; };
                    window.alert = function(){};
                    window.confirm = function(){ return false; };
                    window.prompt = function(){ return null; };
                } catch(e){}
                var AD = ['aliexpress','decafeligibly','morphify','doubleclick',
                    'googlesyndication','popads','popunder','popcash','propellerads',
                    'exoclick','juicyads','trafficjunky','clickadu','adsterra',
                    'hilltopads','adnxs','adsrvr','adserver','/ads/','/vast','vpaid',
                    'syndication','onclckmn','onclicka'];
                function isAd(s){ s=(s||'').toLowerCase();
                    for(var i=0;i<AD.length;i++){ if(s.indexOf(AD[i])>=0) return true; }
                    return false; }
                function kill(){
                    try {
                        document.querySelectorAll('iframe').forEach(function(f){
                            if(isAd(f.src)) f.remove();
                        });
                        document.querySelectorAll('a[target="_blank"]').forEach(function(a){
                            var h=a.href||''; if(h.indexOf('http')!==0) return;
                            var r=a.getBoundingClientRect();
                            var big=r.width>=window.innerWidth*0.5 && r.height>=window.innerHeight*0.5;
                            if((big || isAd(h)) && !a.querySelector('video')) a.remove();
                        });
                    } catch(e){}
                }
                kill();
                setInterval(kill, 1500);
                try {
                    var css=document.createElement('style');
                    css.textContent='[id*="ad-"],[id*="ad_"],[id*="popup"],[id*="interstitial"],'
                        +'[class*="ad-overlay"],[class*="popup"],[class*="interstitial"],'
                        +'[class*="sponsor"]{display:none!important;}';
                    (document.head||document.documentElement).appendChild(css);
                } catch(e){}
            })();
        """

        // 2026-07-09 : SeekStreaming (seekplayer) — plein écran du player vidstack, blocage
        //   popups/pubs, et FORCE le chargement du flux (vidstack fait du lazy-load + un leurre
        //   preload.m3u8 ; startLoading() + play() lancent la vraie lecture).
        private const val SEEKPLAYER_PLAY_JS = """
            (function(){
                try { window.open=function(){return null;}; }catch(e){}
                try {
                    var css=document.createElement('style');
                    css.textContent='html,body{margin:0!important;padding:0!important;background:#000!important;'
                        +'width:100vw!important;height:100vh!important;overflow:hidden!important;}'
                        +'media-player,media-provider,video{width:100vw!important;height:100vh!important;'
                        +'position:fixed!important;top:0!important;left:0!important;object-fit:contain!important;'
                        +'z-index:2147483000!important;background:#000!important;}'
                        +'a[target="_blank"],[class*="popup"],[id*="popup"],[class*="interstitial"]{display:none!important;pointer-events:none!important;}';
                    (document.head||document.documentElement).appendChild(css);
                } catch(e){}
                function go(){
                    try {
                        var mp=document.querySelector('media-player');
                        if(mp){ try{ mp.load='eager'; mp.setAttribute('load','eager'); }catch(e){}
                                try{ if(mp.startLoading) mp.startLoading(); }catch(e){}
                                try{ if(mp.play) mp.play(); }catch(e){} }
                        var v=document.querySelector('video');
                        if(v){ try{ v.play(); }catch(e){} }
                        // tue les liens pub plein écran (onclick ads)
                        document.querySelectorAll('a[target="_blank"]').forEach(function(a){ try{a.remove();}catch(e){} });
                        // auto-clique le dialogue « Vous regardiez cette vidéo… Reprendre ? » (non
                        //   cliquable sous notre overlay / à la télécommande) → on reprend tout seul.
                        document.querySelectorAll('button,a,[role="button"]').forEach(function(b){
                          var t=((b.textContent||b.innerText||'')+'').trim();
                          if(t==='Reprendre'||t.indexOf('Reprendre')===0){ try{b.click();}catch(e){} }
                        });
                    } catch(e){}
                }
                go(); var n=0; var t=setInterval(function(){ n++; go(); if(n>20)clearInterval(t); }, 1000);
            })();
        """

        // 2026-07-09 (user « un 2e bouton dédié qui NE FAIT QUE la rafale de clics ») : rafale de
        //   ~15 clics synthétiques sur le vrai bouton du player web pour DÉCLENCHER/reprendre la
        //   lecture (un geste est requis ; un seul clic « prend » rarement dans la WebView).
        private const val SEEK_BURST_JS = """
            (function(){try{
              var v=document.querySelector('video');var mp=document.querySelector('media-player');
              function clickAt(el,x,y){if(!el)return;var base={bubbles:true,cancelable:true,composed:true,clientX:x,clientY:y,screenX:x,screenY:y,view:window,button:0,pointerId:1,pointerType:'mouse',isPrimary:true,width:1,height:1};function pe(t,b){var o={};for(var k in base)o[k]=base[k];o.buttons=b;return new PointerEvent(t,o);}function me(t,b){var o={};for(var k in base)o[k]=base[k];o.buttons=b;return new MouseEvent(t,o);}try{el.dispatchEvent(pe('pointerdown',1));el.dispatchEvent(me('mousedown',1));el.dispatchEvent(pe('pointerup',0));el.dispatchEvent(me('mouseup',0));el.dispatchEvent(me('click',0));}catch(e){try{el.click&&el.click();}catch(e2){}}}
              try{if(mp){mp.load='eager';if(mp.startLoading)mp.startLoading();}}catch(e){}
              var b=document.querySelector('.vds-play-button')||document.querySelector('media-play-button');
              if(b){var r=b.getBoundingClientRect();var bx=Math.round(r.left+r.width/2)||1,by=Math.round(r.top+r.height/2)||1;for(var i=0;i<15;i++)clickAt(b,bx,by);}
              else{var box=(mp||v||document.body).getBoundingClientRect();var cx=Math.round(box.left+box.width/2),cy=Math.round(box.top+box.height/2);for(var j=0;j<15;j++){var t=document.elementFromPoint(cx,cy)||mp||v;clickAt(t,cx,cy);}}
              try{if(v)v.play();}catch(e){}
              console.log('SEEKPP activate paused='+(v?v.paused:'?'));
            }catch(e){console.log('SEEKPP err '+e);}})();
        """

        // 2026-07-16 : SwiftFlow (swiftflow.lol) — player Plyr à ad-gate. Le bouton « Regarder
        //   maintenant » ouvre une pub via window.open PUIS débloque le player. On neutralise
        //   window.open (renvoie une fausse fenêtre → le handler croit la pub ouverte et débloque
        //   SANS ouvrir de vraie pub), on auto-clique l'ad-gate, plein écran + play. Le mp4
        //   citron-edge signé se charge alors dans le <video> natif (piloté par nos contrôles miroir).
        private const val SWIFTFLOW_PLAY_JS = """
            (function(){
                try { window.open=function(){return {closed:false,focus:function(){},blur:function(){},close:function(){},location:{href:''}};}; }catch(e){}
                try {
                    var css=document.createElement('style');
                    css.textContent='html,body{margin:0!important;padding:0!important;background:#000!important;'
                        +'width:100vw!important;height:100vh!important;overflow:hidden!important;}'
                        +'video,.plyr,.plyr__video-wrapper,.plyr--video{width:100vw!important;height:100vh!important;'
                        +'position:fixed!important;top:0!important;left:0!important;object-fit:contain!important;'
                        +'z-index:2147483000!important;background:#000!important;}'
                        +'a[target="_blank"],[class*="popup"],[id*="popup"],[class*="interstitial"]{display:none!important;pointer-events:none!important;}';
                    (document.head||document.documentElement).appendChild(css);
                } catch(e){}
                function gate(){
                    var hit=false;
                    try {
                        document.querySelectorAll('button,a,[role="button"],div,span').forEach(function(b){
                            if(b.querySelector && b.querySelector('button,a')) return; // que les feuilles
                            var t=((b.textContent||b.innerText||'')+'').trim().toLowerCase();
                            if(t.indexOf('regarder maintenant')>=0 && t.length<40){ try{b.click();hit=true;}catch(e){} }
                        });
                    } catch(e){}
                    return hit;
                }
                function go(){
                    try {
                        gate();
                        var v=document.querySelector('video');
                        if(v){ try{ v.muted=false; v.play(); }catch(e){} }
                        document.querySelectorAll('a[target="_blank"]').forEach(function(a){ try{a.remove();}catch(e){} });
                    } catch(e){}
                }
                go(); var n=0; var t=setInterval(function(){ n++; go(); if(n>25)clearInterval(t); }, 800);
            })();
        """
    }

    /** Flag : a-t-on déjà auto-sélectionné un sous-titre OpenSubtitles ?
     *  Évite de re-déclencher le download à chaque emit du subtitleState. */
    private var autoSubtitleApplied = false
    private var _binding: FragmentPlayerMobileBinding? = null
    private val binding get() = _binding!!
    private var isSetupDone = false

    private val PlayerControlView.binding
        get() = ContentExoControllerMobileBinding.bind(this.findViewById(R.id.cl_exo_controller))

    private val args by navArgs<PlayerMobileFragmentArgs>()
    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val viewModel by viewModelsFactory { PlayerViewModel(args.videoType, args.id) }

    /** Visual channel key (e.g. "France 4") — used for IPTV favorites persistence. */
    private val currentChannelKey: String by lazy {
        args.id
            .removePrefix("ch::")
            .removePrefix("sport::")
            .removePrefix("ola_ep::")
            .removePrefix("ola::")
    }

    private lateinit var player: ExoPlayer
    private lateinit var httpDataSource: HttpDataSource.Factory
    private lateinit var dataSourceFactory: DataSource.Factory
    private lateinit var mediaSession: MediaSession
    private lateinit var progressHandler: android.os.Handler
    private lateinit var progressRunnable: Runnable
    private lateinit var gestureHelper: PlayerGestureHelper

    private var servers = listOf<Video.Server>()
    private var zoomToast: Toast? = null

    /** 2026-06-12 — Verrou enfant. True quand l'écran est en mode lock.
     *  Lu par la touche back (OnBackPressedCallback) pour ignorer la sortie
     *  fullscreen tant que pas déverrouillé. */
    private var isScreenLocked: Boolean = false
    private var screenLockBackCallback: androidx.activity.OnBackPressedCallback? = null

    // IPTV: when all initial servers fail but progressive (OLA) servers may still arrive,
    // keep the player open and wait for additionalServer emissions instead of navigating up.
    private var awaitingMoreServers = false
    // 2026-07-05 (ANR DessinAnime, pile watchdog : main bloqué dans le collecteur
    //   SuccessLoadingServers à PlayerMobileFragment.kt:1096) : le ViewModel ré-émet
    //   SuccessLoadingServers à CHAQUE lot de serveurs progressifs (DessinAnime en émet
    //   des dizaines via ses backups). Le collecteur re-lançait alors l'auto-pick + la
    //   boucle d'attente 3s + viewModel.getVideo() sur le thread principal à chaque vague
    //   → émissions ré-entrantes (flowWithLifecycle + StateFlow) → main saturé → ANR.
    //   Ce flag garantit que l'auto-pick/lecture ne démarre qu'UNE fois par cycle de
    //   chargement (remis à false sur LoadingServers, càd aussi sur reloadServersAfterBypass).
    //   Le picker de serveurs, lui, continue de se mettre à jour à chaque émission.
    private var initialServerPicked = false
    // 2026-07-05 (user "C STAR épuise ses variantes visibles et abandonne, alors que le
    //   pool complet a 100+ streams") : quand le switch a vidé la liste visible, on tire
    //   des remplaçants depuis le POOL COMPLET du provider (Phase 3 non émise). Borné par
    //   MAX_POOL_REPLACEMENTS par ouverture de chaîne pour éviter une boucle infinie.
    private var poolReplacementCount = 0
    private var poolReplacementChannelId: String? = null
    private val MAX_POOL_REPLACEMENTS = 20
    private var awaitTimeoutHandler: android.os.Handler? = null
    private var awaitTimeoutRunnable: Runnable? = null

    // 2026-05-04 : message d'attente affiché pendant l'extraction quand
    // ça traîne (typiquement Cloudflare challenge sur vidmoly). 3 paliers :
    //  - 5s : "Chargement..."
    //  - 12s : "Vérification CF en cours, peut prendre 30s..."
    //  - 25s : "Toujours en cours, patience..."
    private var patienceHandler: android.os.Handler? = null
    private val patienceRunnables = mutableListOf<Runnable>()

    private var currentVideo: Video? = null
    private var currentServer: Video.Server? = null
    // 2026-05-05 : watchdog buffering — fallback automatique au serveur suivant
    // si le player reste bloqué en STATE_BUFFERING > N secondes (Darkibox & co
    // qui renvoient une URL valide mais ne délivrent pas de données).
    private var bufferingWatchdog: kotlinx.coroutines.Job? = null
    // 2026-05-21 (user "baisse auto seulement si ça bufferise" + "remonte tout seul
    //   quand c'est stable") : plafond de résolution adaptatif piloté par les
    //   rebufferings (mode Auto/VOD uniquement). Cf AdaptiveQualityGovernor.
    private var adaptiveQualityGovernor: com.streamflixreborn.streamflix.utils.AdaptiveQualityGovernor? = null
    private var adaptiveQualityTicker: kotlinx.coroutines.Job? = null
    // 2026-05-09 v2 : 10s → 20s → 45s.
    // Règle user : "il faut que la source aille jusqu'à l'échec avant de
    // changer". Le watchdog ne doit PAS kill prématurément une source qui
    // charge lentement — ExoPlayer fire ses propres erreurs (TCP timeout,
    // 404, 403, format unsupported) en 15-30s typiquement. 45s = filet de
    // sécurité absolu pour les vraies pannes silencieuses (genre le CDN
    // accepte la connexion mais ne renvoie jamais de bytes).
    private val BUFFERING_TIMEOUT_MS = 45_000L
    private var usingCronet = false
    private var usingDoH = false
    private var usingBrowserOkHttp = false
    private var usingWebView = false

    // Track the active Player.Listener so we can remove it before attaching a new one.
    // Without this, every retry/displayVideo() call piled a new listener on top of the
    // previous, causing onPlayerError to fire N times per error and accumulating
    // ExoPlayer/MediaSession resources until the OS killed the process.
    private var activePlayerListener: androidx.media3.common.Player.Listener? = null

    /** IPTV sticky-server: once the current stream has reached STATE_READY, we never
     *  auto-switch on transient errors — we just re-prepare. The user can always
     *  switch manually. Reset when the user changes server or channel. */
    private var iptvRetryCount = 0
    private val IPTV_MAX_RETRIES_SAME_STREAM = 3
    private var iptvCurrentStreamHasWorked = false
    // 2026-05-11 : pour VOD aussi — true dès STATE_READY pour différencier
    // "petite coupure pendant lecture" (swap rapide) vs "n'a jamais démarré"
    // (sticky, l'user veut choisir manuellement).
    private var vodCurrentStreamHasWorked = false
    // 2026-07-11 : freeze detection → proposition lecteur externe.
    private var didProposeExternalThisSession = false

    // 2026-05-28 : compteur de retries STICKY sur erreurs "transitoires" (non-dead).
    // Si on re-prepare 3× de suite sans jamais atteindre STATE_READY, le serveur
    // est mort en pratique — swap au suivant au lieu de boucler indéfiniment.
    private var vodStickyRetryCount = 0

    // ── WebView overlay (Netu anti-bot bypass — touch-friendly for mobile) ──
    private var webViewOverlay: FrameLayout? = null
    private var overlayWebView: WebView? = null
    // 2026-07-09 : lecteur miroir branché sur la PlayerView pour les WebView-lecteurs (seekplayer/
    //   abyss/player4me) → nos contrôles natifs pilotent la <video> de la WebView.
    private var webMirrorPlayer: WebPlayerMirror? = null
    private var webMirrorPoll: Runnable? = null
    private var pendingWebViewVideo: Video? = null
    private var pendingWebViewServer: Video.Server? = null
    @Volatile private var m3u8Intercepted = false
    /** CDN iframe page URL captured from shouldInterceptRequest — needed to establish the CDN session */
    @Volatile private var daddyLiveCdnPageUrl: String? = null

    /** Hidden WebView on file:/// for DaddyLive WebViewDataSource (Chrome TLS + no CORS) */
    private var daddyLiveProxyWebView: WebView? = null

    /** Cached CronetEngine using Play Services' Chrome TLS stack */
    private var cronetEngine: CronetEngine? = null
    /** Shared bounded executor for Cronet — avoids unbounded newCachedThreadPool */
    private val cronetExecutor = java.util.concurrent.Executors.newFixedThreadPool(4)
    private var isIgnoringPip = false

    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!::player.isInitialized) return
            when (intent?.action) {
                PIP_ACTION_PLAY -> player.play()
                PIP_ACTION_PAUSE -> player.pause()
                PIP_ACTION_REWIND -> player.seekTo(maxOf(0, player.currentPosition - 10_000))
                PIP_ACTION_FORWARD -> player.seekTo(minOf(player.duration, player.currentPosition + 10_000))
            }
            // Update PiP actions to reflect new play/pause state
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                updatePipParams()
            }
        }
    }
    private var waitingForBypass = false
    private var bypassDone = false
    private var nextEpisodePrefetchTargetId: String? = null
    private var nextEpisodePrefetchJob: Job? = null
    private var nextEpisodeOverlayDismissed = false

    private val bypassWebViewLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val cookies =
                result.data?.getStringExtra(BypassWebViewActivity.EXTRA_COOKIE_HEADER)?.trim()

            if (result.resultCode != android.app.Activity.RESULT_OK || cookies.isNullOrBlank()) {
                waitingForBypass = false
                return@registerForActivityResult
            }

            val bypassUrl = servers.firstOrNull { isSerienStreamBypassUrl(it.id) }?.id
            if (bypassUrl.isNullOrBlank()) {
                waitingForBypass = false
                return@registerForActivityResult
            }

            applyBypassCookies(bypassUrl, cookies)
            waitingForBypass = false
            bypassDone = true

            lifecycleScope.launch {
                delay(300)
                viewModel.reloadServersAfterBypass()
            }
        }

    // 2026-06-30 : true si la case "toujours utiliser ce lecteur" était cochée
    //   au moment de lancer le sélecteur → on mémorise le lecteur choisi + on
    //   active le mode "toujours externe".
    private var pendingExternalDefault = false

    private val chooserReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val clickedComponent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent?.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT, android.content.ComponentName::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT)
                }
                val pkg = clickedComponent?.packageName
                Log.i("ExternalPlayer", "Mobile - App selezionata: ${pkg ?: "Sconosciuta"}")
                // Mémorise toujours le dernier lecteur externe choisi.
                if (!pkg.isNullOrBlank()) {
                    UserPreferences.externalPlayerPackage = pkg
                    if (pendingExternalDefault) {
                        UserPreferences.alwaysUseExternalPlayer = true
                        try {
                            Toast.makeText(requireContext(),
                                "Ce lecteur sera utilisé par défaut", Toast.LENGTH_SHORT).show()
                        } catch (_: Exception) {}
                    }
                }
                pendingExternalDefault = false
            }
        }
    }

    // 2026-06-30 : empêche le re-lancement auto du lecteur externe à chaque
    //   displayVideo() (switch de serveur) → une seule fois par ouverture.
    private var didAutoLaunchExternal = false

    private fun resolveExternalSourceUri(video: Video): Uri {
        val initialSource = video.source
        if (initialSource.startsWith("data:application/vnd.apple.mpegurl;base64,")) {
            val playlistContent = decodeBase64Uri(initialSource)
            val extractedUrl = if (playlistContent != null) extractUrlFromPlaylist(playlistContent) else null
            if (extractedUrl != null) return extractedUrl.toUri()
            return try {
                val file = File(requireContext().cacheDir, "stream.m3u8")
                FileOutputStream(file).use { it.write(playlistContent?.toByteArray() ?: ByteArray(0)) }
                FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.provider", file)
            } catch (ignored: Exception) { initialSource.toUri() }
        }
        return initialSource.toUri()
    }

    /** Lance le lecteur externe. directPackage != null → lance DIRECTEMENT ce
     *  lecteur mémorisé (pas de sélecteur). null → ouvre le sélecteur système. */
    private fun doExternalLaunch(video: Video, directPackage: String?) {
        isIgnoringPip = true
        val videoTitle = when (val type = args.videoType) {
            is Video.Type.Movie -> type.title
            is Video.Type.Episode -> "${type.tvShow.title} • S${type.season.number} E${type.number}"
        }
        val sourceUri = resolveExternalSourceUri(video)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(sourceUri, "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra("title", videoTitle)
            putExtra("position", player.currentPosition.toInt())
            putExtra("return_result", true)
            putExtra("extra_headers", video.headers?.map { "${it.key}: ${it.value}" }?.toTypedArray())
            if (video.headers != null) {
                putExtra("headers", video.headers.flatMap { listOf(it.key, it.value) }.toTypedArray())
            }
        }
        if (!directPackage.isNullOrBlank()) {
            try {
                intent.setPackage(directPackage)
                try { player.pause() } catch (_: Exception) {}
                startActivity(intent)
                return
            } catch (e: Exception) {
                Log.w("ExternalPlayer", "Lecteur direct $directPackage indispo (${e.message}) — sélecteur")
                intent.setPackage(null)
            }
        }
        try {
            val receiverIntent = Intent("ACTION_PLAYER_CHOSEN").apply { setPackage(requireContext().packageName) }
            val pendingIntent = PendingIntent.getBroadcast(
                requireContext(), 0, receiverIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                startActivity(Intent.createChooser(intent, getString(R.string.player_external_player_title), pendingIntent.intentSender))
            } else {
                startActivity(Intent.createChooser(intent, getString(R.string.player_external_player_title)))
            }
        } catch (e: Exception) {
            Log.e("ExternalPlayer", "Errore selettore app", e)
            startActivity(Intent.createChooser(intent, getString(R.string.player_external_player_title)))
        }
    }

    /** 2026-07-11 : dialog CUSTOM qui liste les lecteurs externes installés +
     *  case à cocher « Définir par défaut » EN BAS du même dialog (pas de
     *  chooser système séparé). Au clic sur un lecteur → lance-le, et si la
     *  case est cochée → mémorise le package + active le mode toujours externe. */
    private fun promptExternalThenLaunch(video: Video) {
        val ctx = requireContext()
        val pm = ctx.packageManager
        val probeIntent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(android.net.Uri.parse("content://video"), "video/*") }
        val resolvedApps = pm.queryIntentActivities(probeIntent, 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
        if (resolvedApps.isEmpty()) {
            Toast.makeText(ctx, "Aucun lecteur externe installé", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = resolvedApps.map { it.loadLabel(pm).toString() }.toTypedArray()
        val icons = resolvedApps.map { it.loadIcon(pm) }

        // Adapter custom avec icônes
        val adapter = object : android.widget.ArrayAdapter<String>(ctx, android.R.layout.select_dialog_item, android.R.id.text1, labels) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val v = super.getView(position, convertView, parent)
                val tv = v.findViewById<android.widget.TextView>(android.R.id.text1)
                val iconSize = (40 * resources.displayMetrics.density).toInt()
                val icon = icons[position]
                icon.setBounds(0, 0, iconSize, iconSize)
                tv.setCompoundDrawables(icon, null, null, null)
                tv.compoundDrawablePadding = (12 * resources.displayMetrics.density).toInt()
                tv.setPadding((16 * resources.displayMetrics.density).toInt(), (12 * resources.displayMetrics.density).toInt(),
                    (16 * resources.displayMetrics.density).toInt(), (12 * resources.displayMetrics.density).toInt())
                return v
            }
        }

        // Layout : CheckBox EN HAUT (toujours visible) + ListView en dessous
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        val cb = android.widget.CheckBox(ctx).apply {
            text = "Définir par défaut"
            isChecked = UserPreferences.alwaysUseExternalPlayer
            isFocusable = true
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val listView = android.widget.ListView(ctx).apply {
            this.adapter = adapter
            dividerHeight = 0
        }
        container.addView(cb)
        container.addView(listView)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Lecteur externe")
            .setView(container)
            .setNegativeButton("Annuler", null)
            .create()

        listView.setOnItemClickListener { _, _, position, _ ->
            val pkg = resolvedApps[position].activityInfo.packageName
            UserPreferences.externalPlayerPackage = pkg
            if (cb.isChecked) {
                UserPreferences.alwaysUseExternalPlayer = true
                try { Toast.makeText(ctx, "${labels[position]} défini par défaut", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
            } else {
                UserPreferences.alwaysUseExternalPlayer = false
            }
            dialog.dismiss()
            doExternalLaunch(video, directPackage = pkg)
        }
        dialog.show()
    }

    /** 2026-07-11 : quand ExoPlayer fige et qu'il n'y a plus de serveur, propose
     *  à l'utilisateur de lancer la vidéo dans un lecteur externe (VLC/MX Player).
     *  @param reason texte court décrivant la cause. */
    private fun proposeExternalPlayer(reason: String) {
        if (didProposeExternalThisSession) return
        didProposeExternalThisSession = true
        val video = currentVideo ?: return
        try {
            val ctx = requireContext()
            try { player.pause() } catch (_: Throwable) {}
            val items = arrayOf("Cette fois", "Toujours", "Non")
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle("La vidéo semble figée")
                .setMessage("$reason\n\nLancer dans un lecteur externe ?")
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> { // Cette fois
                            doExternalLaunch(video, directPackage = UserPreferences.externalPlayerPackage)
                        }
                        1 -> { // Toujours — ouvre le dialog custom avec la liste des lecteurs
                            promptExternalThenLaunch(video)
                        }
                        2 -> { // Non
                            try { player.play() } catch (_: Throwable) {}
                        }
                    }
                }
                .setCancelable(true)
                .setOnCancelListener {
                    try { player.play() } catch (_: Throwable) {}
                }
                .show()
        } catch (e: Exception) {
            Log.w("PlayerMobileFragment", "proposeExternalPlayer dialog failed: ${e.message}")
        }
    }

    private val pickLocalSubtitle = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        requireContext().contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )

        val fileName = uri.getFileName(requireContext()) ?: uri.toString()

        val currentPosition = player.currentPosition
        val currentSubtitleConfigurations =
            player.currentMediaItem?.localConfiguration?.subtitleConfigurations?.map {
                MediaItem.SubtitleConfiguration.Builder(it.uri)
                    .setMimeType(it.mimeType)
                    .setLabel(it.label)
                    .setLanguage(it.language)
                    .setSelectionFlags(0)
                    .build()
            } ?: listOf()
        player.setMediaItem(
            MediaItem.Builder()
                .setUri(player.currentMediaItem?.localConfiguration?.uri)
                .setMimeType(player.currentMediaItem?.localConfiguration?.mimeType)
                .setSubtitleConfigurations(
                    currentSubtitleConfigurations
                            + MediaItem.SubtitleConfiguration.Builder(uri)
                        .setMimeType(fileName.toSubtitleMimeType())
                        .setLabel(fileName)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
                .setMediaMetadata(player.mediaMetadata)
                .build()
        )
        player.seekTo(currentPosition)
        player.play()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlayerMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        if (!isSetupDone) {
            requireActivity().requestedOrientation =
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            
            val window = requireActivity().window
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            
            val insetsController = WindowInsetsControllerCompat(window, window.decorView)
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            isSetupDone = true
        }
        isIgnoringPip = false
        // 2026-06-21 (user "j'ai activé le PiP dans les paramètres mais ça
        //   fonctionne pas") : si l'user a flippé la pref pendant qu'il était
        //   ailleurs (Settings), Android n'a pas été mis à jour sur
        //   setAutoEnterEnabled. On rafraîchit les params à chaque onResume.
        if (::player.isInitialized
            && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        ) {
            try { updatePipParams() } catch (_: Throwable) {}
        }
        if (::player.isInitialized) {
            binding.pvPlayer.useController = true
            // Resume playback after returning from bypass or any pause
            // 2026-05-16 : wrap try-catch — AbstractMethodError sur l'ancien
            // Player.Listener.onPlayerStateChanged(bool,int) d'une lib qui
            // n'a pas été recompilée avec Media3 1.8. Le crash bloque l'app
            // au resume sinon. Le play() lui-même réussit, seul le listener
            // crash → on swallow l'erreur.
            try {
                if (!player.isPlaying) {
                    player.play()
                }
            } catch (e: AbstractMethodError) {
                Log.w("PlayerMobile", "AbstractMethodError on player.play (lib mismatch): ${e.message}")
            } catch (e: Exception) {
                Log.w("PlayerMobile", "Error on player.play: ${e.message}")
            }
        }
        
        try {
            val filter = IntentFilter("ACTION_PLAYER_CHOSEN")
            ContextCompat.registerReceiver(
                requireContext(),
                chooserReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (ignored: Exception) {}
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 2026-07-12 (user, cf. PlayerTvFragment) : au play, on met en pause le chargement des
        //   jaquettes (niveau activité) → le CPU/réseau va à la recherche de serveurs. Repris en
        //   onDestroyView. La fiche synopsis a déjà chargé ses images avant le play.
        try { com.bumptech.glide.Glide.with(requireActivity()).pauseAllRequestsRecursive() } catch (_: Throwable) {}

        // 2026-06-21 (user "panel reste ouvert quand on change d'épisode") :
        //   Si le flag statique est set, on réouvre le panel après ~600ms
        //   pour laisser le temps au player de se setup. Le flag est
        //   consommé immédiatement pour éviter une boucle.
        if (pendingReopenPanelMobile) {
            pendingReopenPanelMobile = false
            view.postDelayed({
                if (_binding != null && !episodePanelMobileVisible) {
                    try { showEpisodePanelMobile() } catch (_: Throwable) {}
                }
            }, 600)
        }

        // 2026-06-12 — Verrou enfant : intercepter le back hardware tant que
        // l'écran est locked → l'enfant ne peut pas quitter le fullscreen.
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : androidx.activity.OnBackPressedCallback(false) {
                override fun handleOnBackPressed() {
                    // No-op : on consomme l'event sans rien faire.
                    Toast.makeText(
                        requireContext(),
                        "Écran verrouillé — appuie sur le cadenas pour déverrouiller",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }.also { cb ->
                // Le callback est piloté par isScreenLocked : on l'enable
                // quand on verrouille, disable quand on déverrouille.
                screenLockBackCallback = cb
            },
        )

        // 2026-06-04 (user "garde le pattern OTF comme l'application originelle
        //   jusqu'à l'ExoPlayer de la vidéo") : pour les flux OTF on bascule
        //   immédiatement vers OtfPlayerActivity qui utilise ExoPlayer 2.19.1
        //   (laxiste discontinuities) au lieu de Media3 1.8.0 (strict, crash).
        if (args.id.startsWith("livehub::otf::")) {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val key = args.id.removePrefix("livehub::otf::").substringBefore("::")
                val urls = try {
                    com.streamflixreborn.streamflix.utils.OtfTvService.getUrlsForChannel(key)
                } catch (_: Exception) { emptyList() }
                val url = urls.firstOrNull()
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    if (url != null && context != null) {
                        Log.d("PlayerMobileFragment", "OTF stream → redirect to OtfPlayerActivity (ExoPlayer 2.19.1)")
                        val intent = android.content.Intent(
                            requireContext(),
                            com.streamflixreborn.streamflix.activities.player.OtfPlayerActivity::class.java,
                        ).apply {
                            putExtra(com.streamflixreborn.streamflix.activities.player.OtfPlayerActivity.EXTRA_URL, url)
                            // 2026-07-20 : on passe TOUTES les URLs (CDN multiples) pour que le
                            //   player bascule au lieu de rester bloqué sur un CDN mort.
                            putStringArrayListExtra(
                                com.streamflixreborn.streamflix.activities.player.OtfPlayerActivity.EXTRA_URLS,
                                ArrayList(urls),
                            )
                            putExtra(com.streamflixreborn.streamflix.activities.player.OtfPlayerActivity.EXTRA_KEY, key)
                            putExtra(com.streamflixreborn.streamflix.activities.player.OtfPlayerActivity.EXTRA_TITLE, args.title)
                        }
                        startActivity(intent)
                        findNavController().popBackStack()
                    } else {
                        Log.w("PlayerMobileFragment", "OTF: pas d'URL pour key=$key, fallback Media3")
                    }
                }
            }
            return
        }

        // 2026-06-03 (user "Miracast / AirPlay 2 → il faut qu'on ajoute ça") :
        //   Le bouton Cast actuel ne détecte QUE les Chromecast. On étend le
        //   MediaRouteSelector pour inclure aussi CATEGORY_LIVE_VIDEO et
        //   CATEGORY_REMOTE_PLAYBACK → MediaRouter listera AUSSI les wireless
        //   displays (Miracast) comme la LG webOS UP75006LF. Un seul bouton
        //   qui montre Chromecast + Miracast dans le même picker.
        view.post {
            try {
                // 2026-06-03 v3 (user "il trouve rien, autres users pareil") :
                //   Hook le MediaRouteButton au Cast SDK pour que le picker liste
                //   les Chromecast. Confirmé : sans ce call, picker vide.
                val castBtn = binding.pvPlayer.findViewById<androidx.mediarouter.app.MediaRouteButton>(R.id.btn_exo_cast)
                if (castBtn != null) {
                    try {
                        com.google.android.gms.cast.framework.CastButtonFactory
                            .setUpMediaRouteButton(requireContext().applicationContext, castBtn)
                        Log.d("PlayerMobile", "CastButtonFactory.setUpMediaRouteButton OK")
                    } catch (e: Throwable) {
                        Log.w("PlayerMobile", "CastButtonFactory failed: ${e.message}")
                    }
                }

                // 2026-06-03 : bouton "Diffuser TV externe" → lance Settings
                //   ACTION_CAST_SETTINGS Android natif qui détecte les Miracast
                //   wireless displays (LG webOS, Samsung etc) que le Cast SDK
                //   ne voit pas. Fallback : ouvrir wifi display settings.
                val wirelessBtn = binding.pvPlayer.findViewById<android.widget.ImageView>(R.id.btn_exo_wireless)
                wirelessBtn?.setOnClickListener {
                    try {
                        val intent = android.content.Intent("android.settings.CAST_SETTINGS")
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                    } catch (_: Throwable) {
                        try {
                            startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
                        } catch (_: Throwable) {
                            android.widget.Toast.makeText(
                                requireContext(),
                                "Impossible d'ouvrir les paramètres Cast",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("PlayerMobile", "Failed to setup cast buttons: ${e.message}")
            }
        }

        // 2026-05-15 (user "vire cette foutue roulette pour Mon IPTV") : force
        // GONE de tout overlay loading dès l'init pour les contenus IPTV. Belt-
        // and-suspenders : showLoadingOverlay() est déjà no-op pour ces ids
        // (isIptvChannelContext), mais si l'overlay a été laissé VISIBLE par
        // un précédent passage (transition mini→full, reconfig, etc.), force
        // l'invisible pour éviter le spinner fantôme.
        if (isIptvChannelContext()) {
            try {
                binding.loadingOverlay.visibility = View.GONE
                binding.pvPlayer.setShowBuffering(androidx.media3.ui.PlayerView.SHOW_BUFFERING_NEVER)
            } catch (_: Exception) {}
        }

        // 2026-05-09 v17 : pose tout de suite la channelKey IPTV partagée pour
        // que le picker (favoris/coche) marche dès la 1re ouverture.
        run {
            // Détection contexte IPTV pour activer favoris/picker etc.
            val isIptvCtx = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
                args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
                args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
                args.id.startsWith("movixlivetv::") ||
                args.id.startsWith("livehub::") ||
                args.id.startsWith("sportlive::") ||
                args.id.startsWith("match::") ||
                args.id.startsWith("vavoo::") ||
                args.id.startsWith("myiptv-live::") ||
                args.id.startsWith("myiptv::")
            com.streamflixreborn.streamflix.fragments.player.settings.PlayerSettingsView
                .Settings.Server.currentIptvChannelKey =
                if (isIptvCtx) args.id else null
        }

        // Pre-install Play Services Cronet provider asynchronously so it's ready
        // by the time a LuluVdo/vidzy video needs Chrome's TLS stack
        initCronetEngine()

        // 2026-07-05 : transfert seamless mini→fullscreen (même logique que TV).
        // Si le mini-player est en transition, on récupère son ExoPlayer VIVANT
        // au lieu d'en créer un nouveau → zéro coupure, zéro re-chargement.
        val isIptvChannelId = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
            args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
            args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
            args.id.startsWith("livehub::") || args.id.startsWith("sportlive::") ||
            args.id.startsWith("match::") || args.id.startsWith("vavoo::") ||
            args.id.startsWith("myiptv-live::") || args.id.startsWith("myiptv::")
        val isBypassMiniPlayerId = args.id.startsWith("myiptv-movie::") ||
            args.id.startsWith("myiptv-ep-stk::") ||
            !isIptvChannelId
        if (!isBypassMiniPlayerId) {
            try {
                if (com.streamflixreborn.streamflix.utils.MiniPlayerController.isPlaying() ||
                    com.streamflixreborn.streamflix.utils.MiniPlayerController.currentChannelId != null) {
                    if (!com.streamflixreborn.streamflix.utils.MiniPlayerController.transitioningToFullscreen) {
                        // VOD-like dans un provider IPTV → kill le mini
                        Log.d("PlayerMobileFragment", "IPTV launch, mini actif mais pas en transition → stopAsync")
                        com.streamflixreborn.streamflix.utils.MiniPlayerController.stopAsync()
                    }
                }
            } catch (_: Exception) {}
        } else {
            // VOD = pas de handoff
            try {
                if (com.streamflixreborn.streamflix.utils.MiniPlayerController.isPlaying() ||
                    com.streamflixreborn.streamflix.utils.MiniPlayerController.currentChannelId != null) {
                    Log.d("PlayerMobileFragment", "VOD launch, mini actif → stopAsync (kill)")
                    com.streamflixreborn.streamflix.utils.MiniPlayerController.stopAsync()
                }
            } catch (_: Exception) {}
        }

        val transferred = if (isBypassMiniPlayerId) null
            else com.streamflixreborn.streamflix.utils.MiniPlayerController.transferPlayer()
        if (transferred != null) {
            attachTransferredPlayer(transferred)
        } else {
            initializePlayer(false)
        }
        initializeVideo()

        // For IPTV (WiTv / OlaTv) extraction can take 5-15s; the PlayerView
        // controller doesn't auto-show until a MediaItem is set, so the user
        // is left staring at a black screen with no buttons. Force the
        // controller visible immediately and disable auto-hide; once playback
        // actually starts (STATE_READY) we restore the normal 2s timeout.
        run {
            val provider = UserPreferences.currentProvider
            val isIptv = provider is com.streamflixreborn.streamflix.providers.IptvProvider
            if (isIptv) {
                binding.pvPlayer.useController = true
                binding.pvPlayer.controllerShowTimeoutMs = 0
                binding.pvPlayer.showController()
            }
        }
        gestureHelper = PlayerGestureHelper(
            requireContext(),
            binding.pvPlayer,
            binding.llBrightness,
            binding.pbBrightness,
            binding.tvBrightnessPercentage,
            binding.llVolume,
            binding.pbVolume,
            binding.tvVolumePercentage,
            onDoubleTapSeek = { side ->
                // 2026-06-21 (user "double-clic à gauche = -10s, à droite =
                //   +10s, comme sur l'image") : seek ±10s + overlay visuel
                //   bref (~600ms).
                if (::player.isInitialized) {
                    try {
                        val delta = if (side == "left") -10_000L else 10_000L
                        val newPos = (player.currentPosition + delta).coerceIn(0L, player.duration.coerceAtLeast(0L))
                        player.seekTo(newPos)
                        showDoubleTapSeekOverlay(side)
                    } catch (_: Throwable) {}
                }
            }
        )

        // Stato Video
        viewLifecycleOwner.lifecycleScope.launch { 
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.CREATED).collect { state ->
                when (state) {
                    PlayerViewModel.State.LoadingServers -> {
                        // 2026-05-09 : afficher l'overlay de chargement dès le
                        // début pour pas laisser un écran noir vide. La barre
                        // de progression fictive simule un chargement de 5s.
                        showLoadingOverlay()
                        // 2026-07-05 : nouveau cycle de chargement → ré-autorise l'auto-pick
                        //   une fois (cf initialServerPicked, anti-ANR DessinAnime).
                        initialServerPicked = false
                    }
                    is PlayerViewModel.State.SuccessLoadingServers -> {
                        servers = state.servers
                        val sToServer = servers.firstOrNull {
                            isSerienStreamBypassUrl(it.id)
                        }

                        if (sToServer != null && !waitingForBypass && !bypassDone) {
                            val bypassUrl = buildSerienStreamBypassUrl()
                            if (bypassUrl.isNullOrBlank()) {
                                waitingForBypass = false
                                Toast.makeText(requireContext(), "Unable to open s.to bypass page.", Toast.LENGTH_SHORT).show()
                                return@collect
                            }

                            waitingForBypass = true
                            bypassWebViewLauncher.launch(
                                Intent(requireContext(), BypassWebViewActivity::class.java)
                                    .putExtra(BypassWebViewActivity.EXTRA_URL, bypassUrl)
                            )
                        } else {
                            val providerName = UserPreferences.currentProvider?.name ?: ""
                            val isTmdb = providerName.contains("TMDb", ignoreCase = true)

                            if (servers.isEmpty()) {
                                val message = if (isTmdb) {
                                    val langCode = providerName.substringAfter("(").substringBefore(")")
                                    val locale = Locale.forLanguageTag(langCode)
                                    val langDisplayName = locale.getDisplayLanguage(Locale.getDefault())
                                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

                                    getString(R.string.player_not_available_lang_message, langDisplayName)
                                } else {
                                    "No servers found for this content."
                                }
                                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                                findNavController().navigateUp()
                                return@collect
                            }

                            // 2026-05-08 : pose la channelKey IPTV partagée pour
                            // que Settings.Server.isIptv puisse calculer son channelKey
                            // (utilisé par les boutons croix/cœur du picker).
                            val isIptvCtx = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
                                args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
                                args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
                                args.id.startsWith("movixlivetv::") ||
                                args.id.startsWith("livehub::") ||
                                args.id.startsWith("sportlive::") ||
                                args.id.startsWith("match::")
                            PlayerSettingsView.Settings.Server.currentIptvChannelKey =
                                if (isIptvCtx) args.id else null

                            // 2026-05-08 : tri par priorité IPTV : favoris (par ordre user)
                            // → non-favoris non-bannis → bannis. Garantit que le fallback
                            // onPlayerError essaie fav#1, puis fav#2, etc., avant les autres.
                            val orderedServers = if (isIptvCtx) {
                                val favIds = IptvFavorites.getFavoritesForChannel(args.id)
                                val favIdSet = favIds.toSet()
                                val favRank: (String) -> Int = { id ->
                                    val r = favIds.indexOf(id)
                                    if (r >= 0) r else Int.MAX_VALUE
                                }
                                state.servers.sortedWith(compareBy(
                                    { IptvBannedServers.isBanned(args.id, it.id) },  // false avant true → non-bannis d'abord
                                    { !favIdSet.contains(it.id) },                    // false avant true → favoris d'abord
                                    { favRank(it.id) }                                // ordre user dans favoris
                                ))
                            } else state.servers

                            player.playlistMetadata = MediaMetadata.Builder()
                                .setTitle(state.toString())
                                .setMediaServers(orderedServers.map {
                                    MediaServer(
                                        id = it.id,
                                        name = it.name,
                                    )
                                })
                                .build()
                            binding.settings.setOnServerSelectedListener { server ->
                                // 2026-05-10 : pas de !! qui crashait sur serveurs ajoutés
                                // dynamiquement (kick + replacement).
                                val target = state.servers.find { server.id == it.id }
                                    ?: Video.Server(id = server.id, name = server.name)
                                // 2026-05-16 : stop player avant nouvelle extraction
                                // pour pas qu'il continue à jouer l'ancien stream.
                                runCatching {
                                    if (::player.isInitialized) {
                                        player.pause(); player.stop()
                                    }
                                }
                                // 2026-05-16 : marque le serveur sélectionné comme
                                // "en cours de chargement" pour qu'il s'affiche
                                // avec le suffixe " ⟳" dans le picker.
                                runCatching {
                                    PlayerSettingsView.Settings.Server.list.forEach {
                                        it.isLoading = (it.id == server.id)
                                    }
                                    binding.settings.refreshServerList()
                                }
                                viewModel.getVideo(target)
                            }

                            // IPTV ban callback: remove source and replace from pool
                            binding.settings.onChannelVariantBanned = { bannedVariant ->
                                val provider = UserPreferences.currentProvider
                                if (provider is com.streamflixreborn.streamflix.providers.OlaTvProvider) {
                                    val replacement = provider.requestSingleReplacement(bannedVariant.id)
                                    if (replacement != null) {
                                        val closeBracket = replacement.name.indexOf(']')
                                        val label = if (closeBracket >= 0) replacement.name.substring(closeBracket + 2) else replacement.name
                                        val newVariant = PlayerSettingsView.Settings.ChannelVariant(
                                            id = replacement.id,
                                            name = label,
                                            channelKey = currentChannelKey,
                                        )
                                        PlayerSettingsView.Settings.ChannelVariant.addReplacement(newVariant)
                                        // Also add to servers list so getVideo can find it
                                        servers = servers + replacement
                                    }
                                    binding.settings.refreshChannelVariantList()
                                }
                            }

                            // IPTV favorite callback: just refresh UI (persistence handled by IptvFavorites)
                            binding.settings.onChannelVariantFavoriteToggled = { _ ->
                                binding.settings.refreshChannelVariantList()
                            }

                            // 2026-05-08 : ban d'un Settings.Server IPTV → trigger
                            // reload des servers pour que le backfill compense
                            // (provider voit nonBannedCount < 5 → scanne nouveaux).
                            binding.settings.onServerBanned = {
                                viewModel.reloadServersAfterBypass()
                            }
                            binding.settings.onServerFavoriteToggled = {
                                // 2026-07-08 : re-trier les serveurs pour que les cœurs
                                // remontent immédiatement en tête (VOD + IPTV).
                                viewModel.resortServers()
                            }

                            // 2026-07-16 : appui LONG sur un serveur → le signaler comme « mauvais »
                            //   (mauvaise saison/épisode/langue). Envoi GitHub (onyx-crash-reports),
                            //   dédup 1×/serveur. Pour les bêtatesteurs.
                            binding.settings.onServerReported = { server ->
                                val vt = args.videoType
                                val title = when (vt) {
                                    is com.streamflixreborn.streamflix.models.Video.Type.Episode -> vt.tvShow.title
                                    is com.streamflixreborn.streamflix.models.Video.Type.Movie -> vt.title
                                } ?: "?"
                                val episode = (vt as? com.streamflixreborn.streamflix.models.Video.Type.Episode)?.number ?: 0
                                // Saison RÉELLE depuis l'id (« …/saisonN/… ») car AnimeSama met la langue
                                //   dans season.number ; fallback = season.number.
                                val season = Regex("(?i)saison\\s*(\\d+)").find(args.id)?.groupValues?.get(1)?.toIntOrNull()
                                    ?: (vt as? com.streamflixreborn.streamflix.models.Video.Type.Episode)?.season?.number ?: 0
                                val source = when {
                                    server.id.startsWith("bkreg::") -> server.id.removePrefix("bkreg::").substringBefore("::")
                                    server.name.contains(" · ") -> server.name.substringBefore(" · ")
                                    else -> server.name
                                }
                                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                    .setTitle("Signaler ce serveur")
                                    .setMessage("Signaler « ${server.name} » comme MAUVAIS (mauvaise saison/épisode/langue) pour « $title » ?")
                                    .setNegativeButton("Annuler", null)
                                    .setPositiveButton("Signaler") { _, _ ->
                                        com.streamflixreborn.streamflix.utils.BrokenSourceReporter.reportBadMatch(
                                            serverName = server.name,
                                            sourceLabel = source,
                                            resolvedUrl = args.id,
                                            contentTitle = title,
                                            season = season,
                                            episode = episode,
                                        ) { ok ->
                                            view?.post {
                                                android.widget.Toast.makeText(
                                                    requireContext(),
                                                    if (ok) "Serveur signalé, merci !" else "Échec du signalement",
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    }
                                    .show()
                            }

                            // 2026-06-03 v2 (user "Elles disparaissent") : ne CLEAR que sur
                            //   changement de chaîne. Avant : clear inconditionnel à chaque
                            //   émission SuccessLoadingServers wipait les variantes ajoutées
                            //   via additionalServer. channelKey format peut être "tf1" ou
                            //   "ola::tf1" → normaliser les 2 côtés.
                            fun normKey(k: String): String =
                                k.removePrefix("ola_ep::").removePrefix("ola::").removePrefix("vegeta_ep::").removePrefix("vegeta::")
                            val curList = PlayerSettingsView.Settings.ChannelVariant.list
                            val curKeyNorm = normKey(args.id)
                            val needsClear = curList.isNotEmpty() &&
                                curList.any { it.channelKey.isNotEmpty() && normKey(it.channelKey) != curKeyNorm }
                            if (needsClear || (curList.isNotEmpty() && curKeyNorm.isEmpty())) {
                                Log.d("PlayerMobileFragment", "ChannelVariant.list CLEAR (channel change: ${curList.firstOrNull()?.channelKey} → ${args.id})")
                                curList.clear()
                            } else {
                                Log.d("PlayerMobileFragment", "ChannelVariant.list NOT cleared (same channel '$curKeyNorm', ${curList.size} entries kept)")
                            }
                            binding.settings.refreshChannelVariantList()

                            // 2026-05-08 : continuité mini→fullscreen + favoris multi.
                            //  1. Si on vient du mini, reprendre le SAME server.
                            //  2. Sinon, prendre le 1er favori marqué par l'user
                            //     (par ordre de marquage = priorité utilisateur).
                            //  3. Sinon servers.first() par défaut.
                            // 2026-07-07 (user « le transfert plein écran OLA coupe à 2s et recharge
                            //   tout ») : les ids OLA sont `ola_stream::cid::label::url` avec l'URL
                            //   ÉPHÉMÈRE. Une comparaison par id EXACT échoue entre le serveur joué
                            //   par le mini et celui de la liste fraîche/du favori → l'app croit être
                            //   sur un « mauvais serveur » → getVideo() → rechargement complet à ~2s
                            //   (quand le getServers OLA finit). On compare l'id CANONIQUE (sans URL),
                            //   comme le fragment TV le fait déjà pour les favoris.
                            fun canonicalServerId(rawId: String): String {
                                val parts = rawId.split("::")
                                return if (parts.size >= 4 && parts[0].endsWith("_stream"))
                                    "${parts[0]}::${parts[1]}::${parts[2]}" else rawId
                            }
                            val miniServer = if (com.streamflixreborn.streamflix.utils.MiniPlayerController.transitioningToFullscreen) {
                                com.streamflixreborn.streamflix.utils.MiniPlayerController.currentMiniServer()
                            } else null
                            val matchedMiniServer = miniServer?.let { mini ->
                                state.servers.firstOrNull { canonicalServerId(it.id) == canonicalServerId(mini.id) }
                            }
                            // Favoris multi-server (max 5, ordre = priorité)
                            val orderedFavIds = IptvFavorites.getFavoritesForChannel(currentChannelKey)
                            val favServer = orderedFavIds.firstNotNullOfOrNull { favId ->
                                state.servers.firstOrNull { it.id == favId }
                            }
                            // 2026-05-08 : skip les bannis pour le démarrage auto.
                            // L'user ne veut pas qu'un server grisé soit joué.
                            val firstNonBanned = state.servers.firstOrNull { srv ->
                                !IptvBannedServers.isBanned(currentChannelKey, srv.id)
                            }

                            // 2026-07-06 (user "il doit PAS y avoir de blocage, les serveurs
                            //   doivent arriver et s'afficher ; on enlève le blocage") :
                            //   SUPPRIMÉ l'attente artificielle de 3s (« attendre que le HEAD scan
                            //   flague les morts ») qui tournait DANS le collecteur viewModel.state
                            //   sur le thread principal. Couplée au flowWithLifecycle + StateFlow,
                            //   cette attente tenait le collecteur ouvert pendant que les émissions
                            //   (vagues de backups DessinAnime) s'empilaient en ré-entrance → le main
                            //   thread ne rendait plus la main au looper → ANR ("ONYX ne répond pas").
                            //   On pique désormais le serveur immédiatement ; si le 1er est mort, le
                            //   fallback onPlayerError + la course de vivacité s'en chargent.

                            // 2026-07-07 (user « le tri se fait QUE avec la langue, les favoris et la
                            //   qualité, tout le reste on s'en fout ; si un tri peut interférer et
                            //   bouffer de la mémoire on vire ») : SUPPRIMÉ le skip « broken »
                            //   (brokenServerNames() itérait la map serverHealth sur le MAIN THREAD =
                            //   l'ANR). La liste arrive déjà triée langue/qualité/favoris par le
                            //   ViewModel. Pick = mini → favori → 1er non-banni → 1er. Un serveur qui
                            //   foire est géré par l'auto-switch onPlayerError, pas par un pré-tri.
                            val initialServer = matchedMiniServer
                                ?: favServer
                                ?: firstNonBanned
                                ?: state.servers.first()
                            if (matchedMiniServer != null) {
                                Log.d("PlayerMobileFragment", "Mini→fullscreen : reprise sur ${matchedMiniServer.name}")
                            } else if (favServer != null) {
                                Log.d("PlayerMobileFragment", "Favori prioritaire : ${favServer.name}")
                            } else {
                                Log.d("PlayerMobileFragment", "Initial-pick: ${initialServer.name}")
                            }

                            // 2026-07-05 : guard transfert seamless mini→fullscreen
                            //   (même logique que PlayerTvFragment L1191).
                            //   Si le player a été transféré depuis le mini (déjà en lecture),
                            //   on SKIP viewModel.getVideo() pour ne PAS déclencher displayVideo()
                            //   qui recrée le player → restart de zéro. Le player transféré joue
                            //   déjà le bon flux. On pose juste currentServer pour que le picker
                            //   serveurs / fallback marchent.
                            val miniPlayingId = com.streamflixreborn.streamflix.utils.MiniPlayerController.getCurrentPlayingServerId()
                            val miniIsOnWrongServer = attachedFromMiniPlayer &&
                                favServer != null &&
                                miniPlayingId != null &&
                                canonicalServerId(miniPlayingId) != canonicalServerId(favServer.id)
                            // 2026-07-05 : démarrage de lecture UNE seule fois par cycle
                            //   (anti-ANR DessinAnime). Les émissions SuccessLoadingServers
                            //   suivantes (lots de backups) mettent à jour le picker plus haut
                            //   mais ne relancent PAS getVideo (= plus de rafale ré-entrante
                            //   sur le thread principal).
                            if (!initialServerPicked) {
                                if (attachedFromMiniPlayer && !miniIsOnWrongServer) {
                                    initialServerPicked = true
                                    currentServer = initialServer
                                    Log.d("PlayerMobileFragment", "Skip viewModel.getVideo() — player already running (transferred from mini), currentServer=${initialServer.name}")
                                } else if (state.autoPlay) {
                                    initialServerPicked = true
                                    if (miniIsOnWrongServer) {
                                        Log.w("PlayerMobileFragment", "Mini joue '$miniPlayingId' mais favori = '${favServer?.id}' → force switch sur favori (${favServer?.name})")
                                    }
                                    viewModel.getVideo(initialServer)
                                } else {
                                    // 2026-07-07 : serveurs AFFICHÉS, mais auto-play EN ATTENTE d'un serveur
                                    //   non-VOSTFR/VO. On ne lance rien ; le ViewModel ré-émet
                                    //   SuccessLoadingServers(autoPlay=true) dès qu'un VF arrive (ou 12s).
                                    Log.d("PlayerMobileFragment", "Serveurs affichés — auto-play en attente (hors VOSTFR/VO)")
                                }
                            } else {
                                Log.d("PlayerMobileFragment", "SuccessLoadingServers (lot suivant) — picker mis à jour, pas de re-getVideo (initialServerPicked)")
                            }
                        }

                    }

                    is PlayerViewModel.State.FailedLoadingServers -> {
                        // 2026-06-06 : auto-skip anime — si on est sur un épisode
                        // d'un provider anime et que le provider ne renvoie AUCUN
                        // serveur, on tente l'épisode suivant plutôt que de fermer.
                        if (tryAutoSkipBrokenAnimeEpisode()) {
                            return@collect
                        }
                        Toast.makeText(
                            requireContext(),
                            state.error.message ?: "",
                            Toast.LENGTH_LONG
                        ).show()
                        findNavController().navigateUp()
                    }

                    is PlayerViewModel.State.LoadingVideo -> {
                        // Don't set a MediaItem with empty URI — it causes
                        // FileNotFoundException and puts the player in ERROR state
                        // before extraction finishes. displayVideo() will set the
                        // real MediaItem when SuccessLoadingVideo arrives.
                        schedulePatienceMessages()
                        // 2026-05-16 : marque le serveur en cours d'extraction
                        // pour qu'il affiche " ⟳" dans le picker auto-ouvert.
                        runCatching {
                            val activeId = state.server.id
                            PlayerSettingsView.Settings.Server.list.forEach {
                                it.isLoading = (it.id == activeId)
                            }
                            binding.settings.refreshServerList()
                        }
                    }

                    is PlayerViewModel.State.SuccessLoadingVideo -> {
                        // Channel works — unmark as failed if it was, cancel any pending wait
                        UserPreferences.unmarkChannelFailed(args.id)
                        // 2026-06-06 : un épisode a chargé OK → reset le compteur de
                        // sauts consécutifs de l'auto-skip anime (sinon un user qui
                        // a "consommé" 4 sauts d'affilée hier resterait à 4 pour
                        // toujours).
                        com.streamflixreborn.streamflix.utils.AnimeAutoSkipState.onSuccess()
                        cancelAwaitMoreServers()
                        cancelPatienceMessages()
                        // Cache l'overlay de chargement — on a un Video prêt,
                        // ExoPlayer va prendre le relais visuellement.
                        hideLoadingOverlay()
                        PlayerSettingsView.Settings.ExtraBuffering.init(state.video.extraBuffering)
                        PlayerSettingsView.Settings.SoftwareDecoder.init(false)
                        displayVideo(state.video, state.server)
                    }

                    is PlayerViewModel.State.FailedLoadingVideo -> {
                        cancelPatienceMessages()
                        // Re-afficher l'overlay : on tente le serveur suivant,
                        // donc on revient en mode chargement (chargement à 0).
                        showLoadingOverlay()
                        // Drop this broken variant from the visible Chaîne page so the user
                        // doesn't see piling up dead entries. Re-emitted next session.
                        pruneBrokenVariant(state.server)
                        // 2026-05-24 : marque DEAD par titre — le fallback et le picker
                        //   voient ce serveur en rouge pour CE titre (pas de bave).
                        com.streamflixreborn.streamflix.utils.TitleServerStatus.record(
                            state.server.id,
                            com.streamflixreborn.streamflix.utils.ExtractorRanker.ServerStatus.DEAD,
                        )
                        // IPTV (live channels): NEVER auto-advance to a different server on
                        // extractor failure. Same sticky policy as onPlayerError. Without
                        // this, OLA/Vegeta jumped from variant to variant during initial
                        // loading which churned the buffer and broke playback. The
                        // tryNextChannelVariant call below still handles same-channel
                        // variants, and the IPTV ingestion keeps running in the background
                        // so additional sources show up in the Chaîne page.
                        val isLiveIptv = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
                            args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
                            args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
                            args.id.startsWith("livehub::") || args.id.startsWith("sportlive::") ||
                            args.id.startsWith("match::") || args.id.startsWith("vavoo::") || args.id.startsWith("myiptv-live::")
                        // 2026-05-09 : nextAutoFallbackServer skip VOSTFR/VO si on
                        // était en VF — évite de jouer du sub contre la volonté user.
                        val nextServer = if (isLiveIptv) null
                            else nextAutoFallbackServer(servers, state.server)
                        if (nextServer != null) {
                            viewModel.getVideo(nextServer)
                        } else if (tryNextChannelVariant(state.server)) {
                            // OLA channel variant fallback succeeded — playing next variant
                        } else {
                            // 2026-05-09 : tous les serveurs ont été tentés et tous
                            // ont fail. Demande au ViewModel de marquer ce film comme
                            // "présumé sans source" SI le pattern d'échecs le justifie
                            // (≥2 fails dont la majorité dead-content). Ça pousse le
                            // film en bas du home au prochain refresh.
                            viewModel.markFilmEmptyIfAllDeadContent()
                            val provider = UserPreferences.currentProvider
                            // For IPTV providers (WiTv / OlaTv) keep the player open and wait for
                            // additional progressive servers instead of closing immediately.
                            val isIptv = provider is com.streamflixreborn.streamflix.providers.IptvProvider
                            // 2026-06-12 (user "si le serveur est mort au lancement,
                            //   ça s'arrête direct au lieu d'attendre les autres
                            //   serveurs — ça doit être sur tous les providers") :
                            //   pour les providers progressifs (Cloudstream, Movix,
                            //   FS, Frembed, Wiflix, Papa…), on attend les backups
                            //   asynchrones même quand le flow .collect a fini, car
                            //   des serveurs additionnels peuvent encore arriver via
                            //   additionalServer. Timeout court (20 s) pour VOD,
                            //   timeout long (90 s) pour IPTV.
                            val isProgressive = provider is com.streamflixreborn.streamflix.providers.ProgressiveServersProvider
                            if (isIptv || viewModel.progressiveStillCollecting || isProgressive) {
                                if (!awaitingMoreServers) {
                                    Log.d("PlayerMobileFragment", "All initial servers failed — awaiting progressive backups…")
                                    Toast.makeText(
                                        requireContext(),
                                        "Recherche d'autres sources…",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                val timeoutMs = if (isIptv) 90_000L else 20_000L
                                startAwaitMoreServers(timeoutMs)
                            } else {
                                // 2026-06-06 : auto-skip anime (dernier recours) — si
                                // épisode anime et tous les serveurs sont morts, on
                                // saute à l'épisode suivant au lieu de fermer le player.
                                if (tryAutoSkipBrokenAnimeEpisode()) {
                                    return@collect
                                }
                                val providerName = provider?.name ?: ""
                                val isTmdb = providerName.contains("TMDb", ignoreCase = true)

                                val message = if (isTmdb) {
                                    val langCode = providerName.substringAfter("(").substringBefore(")")
                                    val locale = Locale.forLanguageTag(langCode)
                                    val langDisplayName = locale.getDisplayLanguage(Locale.getDefault())
                                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                                    getString(R.string.player_not_available_lang_message, langDisplayName)
                                } else {
                                    "All servers failed to load the video."
                                }

                                Toast.makeText(
                                    requireContext(),
                                    message,
                                    Toast.LENGTH_LONG
                                ).show()
                                findNavController().navigateUp()
                            }
                        }
                    }
                }
            }
        }

        // Progressive additional servers (OLA TV streams go to Chaîne, others to Serveurs)

        // Coalesce many rapid emissions into a single UI refresh — without this,
        // 50+ progressive emissions in a few hundred ms saturate the main thread
        // (sort + notifyDataSetChanged per emit) and trigger an ANR.
        val refreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var refreshChannelPending = false
        val refreshChannelRunnable = Runnable {
            refreshChannelPending = false
            if (_binding != null) binding.settings.refreshChannelVariantList()
        }
        fun scheduleChannelRefresh() {
            if (refreshChannelPending) return
            refreshChannelPending = true
            refreshHandler.postDelayed(refreshChannelRunnable, 200)
        }
        var refreshServerPending = false
        val refreshServerRunnable = Runnable {
            refreshServerPending = false
            if (_binding != null) binding.settings.refreshServerList()
        }
        fun scheduleServerRefresh() {
            if (refreshServerPending) return
            refreshServerPending = true
            refreshHandler.postDelayed(refreshServerRunnable, 200)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.additionalServer.collect { server ->
                servers = servers + server

                if (server.name.startsWith("OLA[")) {
                    // Parse "OLA[key] label" format
                    val closeBracket = server.name.indexOf(']')
                    if (closeBracket < 0) return@collect
                    val olaKey = server.name.substring(4, closeBracket)
                    val label = server.name.substring(closeBracket + 2) // skip "] "

                    // Filter: ignore streams from a different channel (stale emissions)
                    if (olaKey != currentChannelKey) {
                        Log.d("PlayerMobileFragment", "Ignoring stale OLA stream: ${server.name} (expected key=$currentChannelKey)")
                        return@collect
                    }

                    // OLA TV stream → add to Chaîne page (max 3 per same name)
                    val sameNameCount = PlayerSettingsView.Settings.ChannelVariant.list.count { it.name == label }
                    var addedVariant: PlayerSettingsView.Settings.ChannelVariant? = null
                    if (sameNameCount < 3) {
                        addedVariant = PlayerSettingsView.Settings.ChannelVariant(
                            id = server.id, name = label, channelKey = currentChannelKey,
                        )
                        // Restore favorite state from persistence
                        if (IptvFavorites.isFavorite(currentChannelKey, server.id)) {
                            addedVariant.isFavorite = true
                        }
                        PlayerSettingsView.Settings.ChannelVariant.list.add(addedVariant)
                        if (PlayerSettingsView.Settings.ChannelVariant.list.size == 1) {
                            PlayerSettingsView.Settings.ChannelVariant.list.first().isSelected = true
                        }
                        scheduleChannelRefresh()
                    }

                    // Set up variant click handler (idempotent — same listener every time)
                    binding.settings.setOnChannelVariantSelectedListener { variant ->
                        viewModel.getVideo(Video.Server(variant.id, variant.name))
                    }

                    // Auto-play logic:
                    // 1. If this stream is the favorite → play it immediately (switch to it)
                    // 2. If no favorite exists → play the first OLA stream that arrives
                    // 3. If a favorite exists but this isn't it → don't auto-play (wait for fav)
                    val isFav = IptvFavorites.isFavorite(currentChannelKey, server.id)
                    val hasNonOla = servers.any { !it.name.startsWith("OLA[") }
                    val firstOla = !hasNonOla && PlayerSettingsView.Settings.ChannelVariant.list.size == 1
                    val hasFavoriteForChannel = server.id.startsWith("ola_stream::") &&
                        IptvFavorites.getFavoriteForChannel(currentChannelKey) != null

                    if (isFav) {
                        // This is the favorite → play it NOW, even if something else is already playing
                        Log.d("PlayerMobileFragment", "★ Favorite OLA stream arrived — switching to: $label")
                        PlayerSettingsView.Settings.ChannelVariant.list.forEach { it.isSelected = false }
                        // Mark the variant in the list (may be the one just added, or an existing one)
                        val favVariant = addedVariant
                            ?: PlayerSettingsView.Settings.ChannelVariant.list.find { it.id == server.id }
                        favVariant?.isSelected = true
                        viewModel.getVideo(server)
                    } else if (firstOla && !hasFavoriteForChannel) {
                        // No favorite set for this channel → play first available
                        Log.d("PlayerMobileFragment", "No non-OLA servers, no favorite — auto-playing first OLA stream")
                        viewModel.getVideo(server)
                    } else if (awaitingMoreServers) {
                        Log.d("PlayerMobileFragment", "Awaiting more servers — trying newly arrived OLA stream: $label")
                        cancelAwaitMoreServers()
                        triedChannelVariantIds.add(server.id)
                        viewModel.getVideo(server)
                    } else if (firstOla && hasFavoriteForChannel) {
                        // First stream but a favorite exists — play this temporarily,
                        // the favorite will auto-switch when it arrives
                        Log.d("PlayerMobileFragment", "Playing first OLA stream while waiting for favorite")
                        viewModel.getVideo(server)
                    }

                    Log.d("PlayerMobileFragment", "OLA stream added to Chaîne: $label")
                } else {
                    // Regular server → add to Serveurs page
                    player.playlistMetadata = MediaMetadata.Builder()
                        .setTitle(player.playlistMetadata?.title?.toString() ?: "")
                        .setMediaServers(servers.filter { !it.name.startsWith("OLA[") }.map {
                            MediaServer(id = it.id, name = it.name)
                        })
                        .build()
                    PlayerSettingsView.Settings.Server.addUnique(
                        PlayerSettingsView.Settings.Server(id = server.id, name = server.name)
                    )
                    scheduleServerRefresh()
                    Log.d("PlayerMobileFragment", "Additional server added: ${server.name}")

                    // If we were waiting for more servers after a failure, try this one now.
                    if (awaitingMoreServers) {
                        Log.d("PlayerMobileFragment", "Awaiting more servers — trying newly arrived server: ${server.name}")
                        cancelAwaitMoreServers()
                        viewModel.getVideo(server)
                    }
                }

                // Update server selection listener (regular servers only)
                binding.settings.setOnServerSelectedListener { sel ->
                    servers.find { sel.id == it.id }?.let { viewModel.getVideo(it) }
                }
            }
        }

        // 2026-05-21 : mise à jour PROGRESSIVE de la liste (providers progressifs).
        //   Reçoit la liste COMPLÈTE ré-ordonnée par bucket de langue à chaque
        //   nouveau lot. On remplace le picker SANS relancer la lecture (la
        //   sélection/loading en cours est préservée par id).
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.serversReordered.collect { reordered ->
                servers = reordered
                val nonOla = reordered.filter { !it.name.startsWith("OLA[") }
                val prevSelectedId = PlayerSettingsView.Settings.Server.list.firstOrNull { it.isSelected }?.id
                val prevLoadingId = PlayerSettingsView.Settings.Server.list.firstOrNull { it.isLoading }?.id
                val prevQualities = PlayerSettingsView.Settings.Server.list.associate { it.id to it.quality }
                PlayerSettingsView.Settings.Server.list.clear()
                PlayerSettingsView.Settings.Server.addAllUnique(nonOla.map {
                    PlayerSettingsView.Settings.Server(id = it.id, name = it.name).apply {
                        isSelected = (it.id == prevSelectedId)
                        isLoading = (it.id == prevLoadingId)
                        quality = it.quality ?: prevQualities[it.id]
                    }
                })
                if (::player.isInitialized) {
                    player.playlistMetadata = MediaMetadata.Builder()
                        .setTitle(player.playlistMetadata?.title?.toString() ?: "")
                        .setMediaServers(nonOla.map { MediaServer(id = it.id, name = it.name) })
                        .build()
                }
                binding.settings.setOnServerSelectedListener { sel ->
                    servers.find { sel.id == it.id }?.let { viewModel.getVideo(it) }
                }
                scheduleServerRefresh()
                Log.d("PlayerMobileFragment", "Servers reordered (progressive): ${reordered.size}")
            }
        }

        // 2026-06-30 : rafraîchir la qualité affichée quand le probe background termine
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.qualityUpdated.collect {
                val knownServers = servers
                for (settingSrv in PlayerSettingsView.Settings.Server.list) {
                    if (settingSrv.quality != null) continue
                    val match = knownServers.find { it.id == settingSrv.id }
                    if (match?.quality != null) {
                        settingSrv.quality = match.quality
                    }
                }
                scheduleServerRefresh()
            }
        }

        // Stato Sottotitoli
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.subtitleState.flowWithLifecycle(lifecycle, Lifecycle.State.CREATED).collect { state ->
                when (state) {
                    PlayerViewModel.SubtitleState.Loading -> {}
                    is PlayerViewModel.SubtitleState.SuccessOpenSubtitles -> {
                        // 2026-05-07 : auto-download OpenSubtitles DÉSACTIVÉ.
                        // Le user active manuellement dans le menu Sous-titres s'il en
                        // veut un. Trop de cas où ça forçait un sub sur de l'audio FR.
                        binding.settings.openSubtitles = state.subtitles
                    }
                    is PlayerViewModel.SubtitleState.FailedOpenSubtitles -> {}

                    PlayerViewModel.SubtitleState.DownloadingOpenSubtitle -> {}
                    is PlayerViewModel.SubtitleState.SuccessDownloadingOpenSubtitle -> {
                        val fileName = state.uri.getFileName(requireContext()) ?: state.uri.toString()
                        val currentPosition = player.currentPosition
                        val currentSubtitleConfigurations = player.currentMediaItem?.localConfiguration?.subtitleConfigurations?.map {
                            MediaItem.SubtitleConfiguration.Builder(it.uri)
                                .setMimeType(it.mimeType)
                                .setLabel(it.label)
                                .setLanguage(it.language)
                                .setSelectionFlags(0)
                                .build()
                        } ?: listOf()
                        player.setMediaItem(
                            MediaItem.Builder()
                                .setUri(player.currentMediaItem?.localConfiguration?.uri)
                                .setMimeType(player.currentMediaItem?.localConfiguration?.mimeType)
                                .setSubtitleConfigurations(
                                    currentSubtitleConfigurations + MediaItem.SubtitleConfiguration.Builder(state.uri)
                                        .setMimeType(fileName.toSubtitleMimeType())
                                        .setLabel(fileName)
                                        .setLanguage(state.subtitle.languageName)
                                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                        .build()
                                )
                                .setMediaMetadata(player.mediaMetadata)
                                .build()
                        )
                        UserPreferences.subtitleName = (state.subtitle.languageName ?: fileName).substringBefore(" ")
                        player.seekTo(currentPosition)
                        player.play()
                    }
                    is PlayerViewModel.SubtitleState.FailedDownloadingOpenSubtitle -> {
                        Toast.makeText(requireContext(), "${state.subtitle.subFileName}: ${state.error.message}", Toast.LENGTH_LONG).show()
                    }

                    is PlayerViewModel.SubtitleState.SuccessSubDLSubtitles -> {
                        binding.settings.subDLSubtitles = state.subtitles
                    }
                    is PlayerViewModel.SubtitleState.FailedSubDLSubtitles -> {}

                    PlayerViewModel.SubtitleState.DownloadingSubDLSubtitle -> {}
                    is PlayerViewModel.SubtitleState.SuccessDownloadingSubDLSubtitle -> {
                        val fileName = state.uri.getFileName(requireContext()) ?: state.uri.toString()
                        val currentPosition = player.currentPosition
                        val currentSubtitleConfigurations = player.currentMediaItem?.localConfiguration?.subtitleConfigurations?.map {
                            MediaItem.SubtitleConfiguration.Builder(it.uri)
                                .setMimeType(it.mimeType)
                                .setLabel(it.label)
                                .setLanguage(it.language)
                                .setSelectionFlags(0)
                                .build()
                        } ?: listOf()
                        player.setMediaItem(
                            MediaItem.Builder()
                                .setUri(player.currentMediaItem?.localConfiguration?.uri)
                                .setMimeType(player.currentMediaItem?.localConfiguration?.mimeType)
                                .setSubtitleConfigurations(
                                    currentSubtitleConfigurations + MediaItem.SubtitleConfiguration.Builder(state.uri)
                                        .setMimeType(fileName.toSubtitleMimeType())
                                        .setLabel(state.subtitle.releaseName ?: state.subtitle.name ?: fileName)
                                        .setLanguage(state.subtitle.lang ?: state.subtitle.language ?: "Unknown")
                                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                        .build()
                                )
                                .setMediaMetadata(player.mediaMetadata)
                                .build()
                        )
                        UserPreferences.subtitleName = (state.subtitle.releaseName ?: state.subtitle.name ?: fileName).substringBefore(" ")
                        player.seekTo(currentPosition)
                        player.play()
                    }
                    is PlayerViewModel.SubtitleState.FailedDownloadingSubDLSubtitle -> {
                        Toast.makeText(requireContext(), "${state.subtitle.name}: ${state.error.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.playPreviousOrNextEpisode.collect { nextEpisode ->
                    releasePlayer()
                    isSetupDone = false
                    val action = PlayerMobileFragmentDirections
                        .actionPlayerMobileFragmentSelf(
                            id = nextEpisode.id,
                            videoType = nextEpisode,
                            title = nextEpisode.tvShow.title,
                            subtitle = "S${nextEpisode.season.number} E${nextEpisode.number}  •  ${nextEpisode.title}"
                        )

                    hideNextEpisodeOverlay()
                    findNavController().navigate(
                        action,
                        NavOptions.Builder()
                            .setPopUpTo(
                                findNavController().currentDestination?.id ?: return@collect, true
                            )
                            .setLaunchSingleTop(false) 
                            .build()
                    )
                }
            }
        }


    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        binding.pvPlayer.useController = !isInPictureInPictureMode
        if (!isInPictureInPictureMode) {
            // Exiting PiP — unregister the broadcast receiver
            try { requireContext().unregisterReceiver(pipActionReceiver) } catch (_: Exception) {}
        }
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
    }

    fun onUserLeaveHint() {
        // 2026-06-21 (user "quand on quitte l'application, souvent elle se met
        //   en tout petit comme ça [PiP]. Il faudrait mettre une option dans
        //   Apparence pour désactiver. Beaucoup de personnes veulent quitter
        //   tout simplement") :
        //   Gate sur UserPreferences.pipOnExit (default false = off).
        if (!UserPreferences.pipOnExit) return
        if (!isIgnoringPip && ::player.isInitialized && player.isPlaying) {
            enterPIPMode()
        }
    }

    override fun onStop() {
        super.onStop()
        val inPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                requireActivity().isInPictureInPictureMode
        if (::player.isInitialized && !inPip) {
            player.pause()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 2026-07-12 : retour au home/fiche → reprise du chargement des jaquettes (pause au play).
        try { com.bumptech.glide.Glide.with(requireActivity()).resumeRequestsRecursive() } catch (_: Throwable) {}
        hideWebViewOverlay()

        // 2026-06-02 : clear le picker quand on quitte le player. Sinon
        //   les serveurs du film précédent restent dans Settings.Server.list
        //   (singleton companion object) et pollue le suivant.
        try {
            com.streamflixreborn.streamflix.fragments.player.settings.PlayerSettingsView
                .Settings.Server.list.clear()
        } catch (_: Exception) { }

        // Cleanup Handler leaks
        if (::progressHandler.isInitialized && ::progressRunnable.isInitialized) {
            progressHandler.removeCallbacks(progressRunnable)
        }
        cancelAwaitMoreServers()
        cancelPatienceMessages()

        // Cleanup DaddyLive proxy WebView
        daddyLiveProxyWebView?.let {
            try { it.stopLoading(); it.destroy() } catch (_: Exception) {}
        }
        daddyLiveProxyWebView = null

        // Shutdown Cronet executor
        cronetExecutor.shutdownNow()

        nextEpisodePrefetchJob?.cancel()
        val window = requireActivity().window
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
        }
        WindowCompat.getInsetsController(
            window,
            window.decorView
        ).run {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            show(WindowInsetsCompat.Type.systemBars())
        }
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        releasePlayer()
        try {
            requireContext().unregisterReceiver(chooserReceiver)
        } catch (ignored: Exception) {}
        _binding = null
        isSetupDone = false
    }

    fun onBackPressed(): Boolean = when {
        // 2026-06-20 (user "comme sur l'image, panel épisodes droite") :
        //   BACK quand le panel épisodes est ouvert → ferme le panel (au
        //   lieu de quitter le player).
        episodePanelMobileVisible -> {
            hideEpisodePanelMobile()
            true
        }
        webViewOverlay != null -> {
            hideWebViewOverlay()
            true
        }
        binding.pvPlayer.isManualZoomEnabled -> {
            binding.pvPlayer.exitManualZoomMode()
            true
        }
        binding.settings.isVisible -> {
            binding.settings.onBackPressed()
        }
        else -> false
    }


    private fun initializeVideo() {
        WindowCompat.getInsetsController(
            requireActivity().window,
            requireActivity().window.decorView
        ).run {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        when (val type = args.videoType) {
            is Video.Type.Episode -> {
                nextEpisodeOverlayDismissed = false
                nextEpisodePrefetchTargetId = null
                if (EpisodeManager.listIsEmpty(type)) {
                    EpisodeManager.clearEpisodes()
                    lifecycleScope.launch(Dispatchers.IO) {
                        EpisodeManager.addEpisodesFromDb(type, database)
                        withContext(Dispatchers.Main) {
                            EpisodeManager.setCurrentEpisode(type)
                            updatePlayerHeader(type)
                            setupEpisodeNavigationButtons()
                            refreshEpisodeNavigation(type)
                        }
                    }
                } else {
                    EpisodeManager.setCurrentEpisode(type)
                    setupEpisodeNavigationButtons()
                    refreshEpisodeNavigation(type)
                }
            }
            is Video.Type.Movie -> {
                nextEpisodeOverlayDismissed = false
                nextEpisodePrefetchTargetId = null
                EpisodeManager.clearEpisodes()
                hideNextEpisodeOverlay()
            }
        }


        binding.settings.onSubtitlesClicked = {
            viewModel.getSubtitles(args.videoType)
        }
        binding.settings.setOnExtraBufferingSelectedListener {
            displayVideo(
                currentVideo ?: return@setOnExtraBufferingSelectedListener,
                currentServer ?: return@setOnExtraBufferingSelectedListener
            )
        }
        binding.settings.setOnSoftwareDecoderSelectedListener { useSoftware ->
            currentSoftwareDecoder = useSoftware
            displayVideo(
                currentVideo ?: return@setOnSoftwareDecoderSelectedListener,
                currentServer ?: return@setOnSoftwareDecoderSelectedListener
            )
        }
        binding.settings.setOnServerDownloadClickedListener { serverSetting ->
            val server = servers.find { it.id == serverSetting.id } ?: return@setOnServerDownloadClickedListener
            downloadFromServer(server)
        }
        binding.settings.onDownloadsClicked = {
            com.streamflixreborn.streamflix.download.DownloadsBottomSheet()
                .show(childFragmentManager, com.streamflixreborn.streamflix.download.DownloadsBottomSheet.TAG)
        }
        binding.pvPlayer.resizeMode = UserPreferences.playerResize.resizeMode
        binding.pvPlayer.subtitleView?.apply {
            setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * UserPreferences.captionTextSize)
            setStyle(UserPreferences.captionStyle)
            // 2026-06-21 (user "regarde les sous-titres ils sont encore dans
            //   la barre") : padding bas user + 32dp pour dégager la zone
            //   du progress bar + time labels (qui sont collés à 0dp en bas).
            //   Avant : sub overlappait avec "00:07 · 22:52".
            val extraBottom = (32 * context.resources.displayMetrics.density).toInt()
            setPadding(0, 0, 0, UserPreferences.captionMargin.dp(context) + extraBottom)
        }
        setupEpisodeNavigationButtons()

        binding.pvPlayer.controller.binding.btnExoBack.setOnClickListener {
            findNavController().navigateUp()
        }

        updatePlayerHeader()

        binding.pvPlayer.controller.binding.btnExoExternalPlayer.setOnClickListener {
            Toast.makeText(
                requireContext(),
                getString(R.string.player_external_player_error_video),
                Toast.LENGTH_SHORT
            ).show()
        }

        // 2026-06-20 : exo_replay retiré du centre → plus de click listener.
        // Le bouton "replay from start" n'est plus dans le layout mobile.

        // 2026-06-20 : bouton Serveur en haut → ouvre direct la liste des serveurs.
        binding.pvPlayer.controller.binding.btnExoServer.setOnClickListener {
            binding.pvPlayer.controllerShowTimeoutMs = binding.pvPlayer.controllerShowTimeoutMs
            binding.settings.showServers()
        }

        // 2026-06-12 (user "crée une page transparente qui verrouille
        //   carrément le truc, seul le cadenas le déverrouille") :
        //   approche overlay-fullscreen.
        //
        //   layout_screen_lock = FrameLayout invisible par défaut, mais
        //   quand visible il :
        //   - intercepte TOUS les taps (clickable=true + focusable=true)
        //     → impossible de toucher la PlayerView ni les contrôles dessous
        //   - contient SEULEMENT btn_screen_unlock qui est le seul
        //     élément cliquable au-dessus de l'overlay
        //
        //   Tap sur le cadenas (controller) → overlay VISIBLE + controller
        //   caché + gestures bloqués + back hardware bloqué.
        //   Tap sur btn_screen_unlock → overlay GONE + tout revient.
        binding.pvPlayer.controller.binding.btnExoLock.setOnClickListener {
            engageScreenLock()
        }

        binding.layoutScreenLock.setOnClickListener {
            // Tap N'IMPORTE OÙ sur l'overlay → on flashe le cadenas
            // brièvement (auto-hide 3s) pour signaler à l'user où taper
            // pour déverrouiller. C'est la "lampe torche" pour trouver
            // le cadenas dans le noir.
            flashUnlockButton()
        }

        binding.btnScreenUnlock.setOnClickListener {
            disengageScreenLock()
        }

        // 2026-06-12 (user "ajoute un bouton liste des chaines a cote du
        //   cadenas en bas, pour TOUS les providers IPTV - PAS pour les
        //   films et series") : équivalent du LEFT D-pad sur TV. Visible
        //   seulement si on est sur une chaîne IPTV. Tap → BottomSheet avec
        //   toute la liste, tap sur une chaîne = switch rapide.
        // 2026-06-20 (user "et j'ai dit de faire ça aussi sur mobile en met
        //   le même icône que pour IPTV pour afficher ce menu là") : le bouton
        //   liste est visible en IPTV ET en VOD série.
        // 2026-06-21 (user "sur Vavoo j'ai fait gauche j'ai plus que TF1.
        //   T'aurais pas dû appliquer ça pour les Provider IPTV — c'est censé
        //   afficher toutes les chaînes") :
        //   Bug — IPTV stocke la chaîne courante comme Video.Type.Episode →
        //   isVodEpisode était true → on routait vers le panel épisodes (=
        //   1 chaîne courante seule) au lieu de la liste IPTV (= toutes les
        //   chaînes). Fix : si IPTV, route TOUJOURS vers le panel IPTV
        //   peu importe le type.
        val isIptvCtx = isIptvChannelContext()
        val isVodEpisode = !isIptvCtx && args.videoType is com.streamflixreborn.streamflix.models.Video.Type.Episode
        binding.pvPlayer.controller.binding.btnExoChannelList.isVisible = isIptvCtx || isVodEpisode
        binding.pvPlayer.controller.binding.btnExoChannelList.setOnClickListener {
            if (isVodEpisode) {
                showEpisodeListBottomSheet()
            } else {
                showIptvChannelListPanelMobile()
            }
        }

        binding.pvPlayer.controller.binding.btnExoPictureInPicture.setOnClickListener {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.player_picture_in_picture_not_supported),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                enterPIPMode()
            }
        }

        binding.pvPlayer.controller.binding.btnExoAspectRatio.setOnClickListener {
            val newResize = UserPreferences.playerResize.next()
            zoomToast?.cancel()
            zoomToast = Toast.makeText(requireContext(), newResize.stringRes, Toast.LENGTH_SHORT)
            zoomToast?.show()

            UserPreferences.playerResize = newResize
            binding.pvPlayer.controllerShowTimeoutMs = binding.pvPlayer.controllerShowTimeoutMs
            updatePlayerScale()
        }

        // 2026-06-20 : 3-dots → popup menu avec toutes les options
        binding.pvPlayer.controller.binding.exoSettings.setOnClickListener { anchor ->
            binding.pvPlayer.controllerShowTimeoutMs = binding.pvPlayer.controllerShowTimeoutMs
            showPlayerOverflowMenu(anchor)
        }

        // 2026-05-16 (user "ça charge à l'infini sans savoir si serveur OK") :
        // tap sur le loading overlay → cancel extraction en cours + open Settings
        // (server picker) pour que l'user puisse changer de serveur sans attendre.
        binding.loadingOverlay.setOnClickListener {
            viewModel.cancelGetVideo()
            // 2026-05-16 : cancel aussi l'extraction FRAnime en cours (WebView)
            // pour pas qu'elle continue à charger inutilement en background.
            runCatching {
                com.streamflixreborn.streamflix.extractors.FranimeSession.cancelCurrent()
            }
            cancelPatienceMessages()
            hideLoadingOverlay()
            // 2026-05-16 : ouvre direct la liste de serveurs (pas le menu Main)
            // pour que l'user puisse cliquer un autre serveur sans navigation.
            binding.settings.showServers()
        }

        binding.settings.setOnLocalSubtitlesClickedListener {
            isIgnoringPip = true
            pickLocalSubtitle.launch(
                arrayOf(
                    "text/plain",
                    "text/str",
                    "application/octet-stream",
                    MimeTypes.TEXT_UNKNOWN,
                    MimeTypes.TEXT_VTT,
                    MimeTypes.TEXT_SSA,
                    MimeTypes.APPLICATION_TTML,
                    MimeTypes.APPLICATION_MP4VTT,
                    MimeTypes.APPLICATION_SUBRIP,
                )
            )
        }

        binding.settings.setOnOpenSubtitleSelectedListener { subtitle ->
            viewModel.downloadSubtitle(subtitle.openSubtitle)
        }

        binding.settings.setOnSubDLSubtitleSelectedListener { subtitle ->
            viewModel.downloadSubDLSubtitle(subtitle.subDLSubtitle)
        }

        binding.settings.setOnExtraBufferingSelectedListener {
            displayVideo(
                currentVideo ?: return@setOnExtraBufferingSelectedListener,
                currentServer ?: return@setOnExtraBufferingSelectedListener
            )
        }

        binding.pvPlayer.controller.binding.btnSkipIntro.setOnClickListener {
            player.seekTo(player.currentPosition + 85000)
            it.isGone = true
        }

        binding.btnNextEpisodeAction.setOnClickListener {
            hideNextEpisodeOverlay()
            playNextEpisodeAcrossSeasons()
        }
        binding.btnNextEpisodeDismiss.setOnClickListener {
            nextEpisodeOverlayDismissed = true
            hideNextEpisodeOverlay()
        }

        binding.settings.onManualZoomClicked = {
            binding.settings.hide()
            binding.pvPlayer.hideController()
            binding.pvPlayer.enterManualZoomMode()
        }
        // 2026-07-06 : persistance du zoom manuel par serveur (Sibnet, etc.)
        // 2026-07-10 : + zoom manuel GLOBAL « collant » (LAST_KEY) → suit sur toutes les vidéos
        //   suivantes jusqu'à modif/reset (reset = 1f,1f → save() supprime la clé).
        (binding.pvPlayer as? PlayerMobileView)?.onZoomChanged = { sx, sy ->
            com.streamflixreborn.streamflix.utils.ZoomPrefsStore.save(
                com.streamflixreborn.streamflix.utils.ZoomPrefsStore.LAST_KEY, sx, sy
            )
            val key = com.streamflixreborn.streamflix.utils.ZoomPrefsStore.extractKey(
                currentServer, currentVideo?.source
            )
            if (key != null) {
                com.streamflixreborn.streamflix.utils.ZoomPrefsStore.save(key, sx, sy)
            }
        }
    }

 private fun updatePlayerScale() {
        val videoSurfaceView = binding.pvPlayer.videoSurfaceView
        val playerResize = UserPreferences.playerResize 

        binding.pvPlayer.resizeMode = playerResize.resizeMode 

        when (playerResize) { 
            UserPreferences.PlayerResize.Stretch43 -> {
                val scale = 1.33f 
                videoSurfaceView?.scaleX = scale
                videoSurfaceView?.scaleY = 1f
            }
            UserPreferences.PlayerResize.StretchVertical -> {
                videoSurfaceView?.scaleX = 1f
                videoSurfaceView?.scaleY = 1.25f
            }
            UserPreferences.PlayerResize.SuperZoom -> {
                videoSurfaceView?.scaleX = 1.5f
                videoSurfaceView?.scaleY = 1.5f
            }
            else -> {
                // 2026-07-10 (user « garder le même zoom d'une vidéo à l'autre ») : au lieu de forcer
                //   1f (ce qui écraserait le zoom manuel collant), on applique le dernier zoom manuel
                //   GLOBAL s'il existe.
                val z = com.streamflixreborn.streamflix.utils.ZoomPrefsStore.load(
                    com.streamflixreborn.streamflix.utils.ZoomPrefsStore.LAST_KEY)
                videoSurfaceView?.scaleX = z?.first ?: 1f
                videoSurfaceView?.scaleY = z?.second ?: 1f
            }
        }
    }

    fun setupEpisodeNavigationButtons() {
        val btnPrevious = binding.pvPlayer.controller.binding.btnCustomPrev
        val btnNext = binding.pvPlayer.controller.binding.btnCustomNext

        // IPTV channel navigation: prev/next channel buttons
        // 2026-05-08 : ajout vegeta::/vegeta_ep:: oubliés (le code ola:: était
        // là, mais vegeta absent → boutons prev/next ne marchaient pas pour VegetaTV).
        // 2026-05-08 : ajout livehub:: pour TV Hub (zap entre favoris).
        val isIptvChannel = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
            args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
            args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
            args.id.startsWith("livehub::") || args.id.startsWith("vavoo::")
        if (isIptvChannel) {
            setupChannelNavigationButtons(btnPrevious, btnNext)
            return
        }

        fun handleNavigationButton(
            button: ImageView,
            hasEpisode: () -> Boolean,
            playEpisode: () -> Unit
        ) {
            if (!hasEpisode()) {
                button.isGone = true
                return
            }

            button.isGone = false
            button.setOnClickListener listener@{
                if (!hasEpisode()) return@listener

                val videoType = args.videoType
                val hasFinished = player.hasFinished()
                val hasReallyFinished = player.hasReallyFinished()
                val ctx = requireContext()
                val provider = UserPreferences.currentProvider ?: return@listener

                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    val watchItem: WatchItem? = when (videoType) {
                        is Video.Type.Movie -> database.movieDao().getById(videoType.id)
                        is Video.Type.Episode -> database.episodeDao().getById(videoType.id)
                    }

                    when (videoType) {
                        is Video.Type.Movie -> {
                            val movie = watchItem as? Movie
                            movie?.let { database.movieDao().update(it) }
                            movie?.let { UserDataCache.addMovieToContinueWatching(ctx, provider, it) }
                        }

                        is Video.Type.Episode -> {
                            val episode = watchItem as? Episode
                            episode?.let {
                                if (hasFinished) {
                                    database.episodeDao().resetProgressionFromEpisode(videoType.id)
                                    UserDataCache.removeEpisodeFromContinueWatching(ctx, provider, it.id)
                                }
                                database.episodeDao().update(it)

                                if (!hasFinished) {
                                    UserDataCache.addEpisodeToContinueWatching(ctx, provider, it)
                                }

                                it.tvShow?.let { tvShow ->
                                    database.tvShowDao().getById(tvShow.id)
                                }?.let { tvShow ->
                                    val episodeDao = database.episodeDao()
                                    val isStillWatching = episodeDao.hasAnyWatchHistoryForTvShow(tvShow.id)

                                    database.tvShowDao().save(tvShow.copy().apply {
                                        merge(tvShow)
                                        isWatching = !hasReallyFinished || isStillWatching
                                    })
                                }
                            }
                        }
                    }
                }

                playEpisode()
            }
        }

        handleNavigationButton(
            btnPrevious,
            EpisodeManager::hasPreviousEpisode,
            viewModel::playPreviousEpisode
        )
        handleNavigationButton(btnNext, EpisodeManager::hasNextEpisode, ::playNextEpisodeAcrossSeasons)
    }

    private fun setupChannelNavigationButtons(btnPrevious: ImageView, btnNext: ImageView) {
        val provider = UserPreferences.currentProvider

        // Resolve prev/next IDs depending on provider type
        val prevId: String?
        val nextId: String?
        val resolveDisplayName: (String) -> String?
        val resolvePoster: (String) -> String?

        when (provider) {
            is com.streamflixreborn.streamflix.providers.OlaTvProvider -> {
                prevId = provider.getPreviousChannelId(args.id)
                nextId = provider.getNextChannelId(args.id)
                resolveDisplayName = { provider.getChannelDisplayName(it) }
                resolvePoster = { provider.getChannelPoster(it) }
            }
            is com.streamflixreborn.streamflix.providers.VegetaTvProvider -> {
                prevId = provider.getPreviousChannelId(args.id)
                nextId = provider.getNextChannelId(args.id)
                resolveDisplayName = { provider.getChannelDisplayName(it) }
                resolvePoster = { provider.getChannelPoster(it) }
            }
            is com.streamflixreborn.streamflix.providers.LiveTvHubProvider -> {
                prevId = provider.getPreviousChannelId(args.id)
                nextId = provider.getNextChannelId(args.id)
                resolveDisplayName = { provider.getChannelDisplayName(it) }
                resolvePoster = { provider.getChannelPoster(it) }
            }
            is com.streamflixreborn.streamflix.providers.VavooProvider -> {
                prevId = provider.getPreviousChannelId(args.id)
                nextId = provider.getNextChannelId(args.id)
                resolveDisplayName = { provider.getChannelDisplayName(it) }
                resolvePoster = { provider.getChannelPoster(it) }
            }
            else -> {
                btnPrevious.isGone = true
                btnNext.isGone = true
                return
            }
        }

        btnPrevious.isGone = prevId == null
        btnNext.isGone = nextId == null

        fun navigateToChannel(channelId: String) {
            val channelName = resolveDisplayName(channelId) ?: channelId
            val channelPoster = resolvePoster(channelId)

            val videoType = Video.Type.Episode(
                id = channelId,
                number = 1,
                title = channelName,
                poster = channelPoster,
                overview = null,
                tvShow = Video.Type.Episode.TvShow(
                    id = channelId,
                    title = channelName,
                    poster = channelPoster,
                    banner = null,
                    releaseDate = null,
                    imdbId = null,
                ),
                season = Video.Type.Episode.Season(
                    number = 1,
                    title = "Live",
                ),
            )
            val navArgs = android.os.Bundle().apply {
                putString("id", channelId)
                putString("title", channelName)
                putString("subtitle", channelName)
                putSerializable("videoType", videoType)
            }
            findNavController().navigate(
                R.id.player,
                navArgs,
                androidx.navigation.NavOptions.Builder()
                    .setPopUpTo(R.id.player, true)
                    .build()
            )
        }

        if (prevId != null) {
            btnPrevious.setOnClickListener { navigateToChannel(prevId) }
        }
        if (nextId != null) {
            btnNext.setOnClickListener { navigateToChannel(nextId) }
        }
    }

    /** Try the next untried OLA channel variant. Returns true if a variant was found and is being tried. */
    private var triedChannelVariantIds = mutableSetOf<String>()

    /**
     * Keep the player open while progressive (OLA) servers are still being fetched.
     * Default timeout: 90s — after that, give up and navigate up.
     */
    private fun startAwaitMoreServers(timeoutMs: Long = 90_000L) {
        awaitingMoreServers = true
        cancelAwaitTimeoutOnly()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        awaitTimeoutHandler = handler
        // 2026-07-06 (règle implacable — user) : on ne "coupe" (Aucune source) QUE si :
        //   (1) la recherche de serveurs est FINIE d'elle-même (progressiveStillCollecting=false),
        //   ET (2) TOUS les serveurs trouvés ont été essayés (aucun non-DEAD restant).
        //   Tant que la recherche tourne → on ré-attend (poll 4s), jamais de coupure prématurée.
        //   Borné naturellement : la recherche progressive se termine (plafond de collecte ~45s).
        val poll = object : Runnable {
            override fun run() {
                if (!awaitingMoreServers) return
                // Recherche encore en cours → on attend, on ne coupe pas.
                if (viewModel.progressiveStillCollecting) {
                    handler.postDelayed(this, 4_000L)
                    return
                }
                // Recherche terminée : reste-t-il un serveur JAMAIS essayé ? → on le tente.
                val untried = nextAutoFallbackServer(servers, currentServer)
                if (untried != null) {
                    Log.d("PlayerMobileFragment", "Await: recherche finie, serveur non essayé restant → ${untried.name}")
                    awaitingMoreServers = false
                    viewModel.getVideo(untried)
                    return
                }
                // Recherche finie ET tous les serveurs essayés → SEULEMENT là on abandonne.
                Log.d("PlayerMobileFragment", "Await: recherche finie + tous serveurs essayés → Aucune source")
                awaitingMoreServers = false
                if (isAdded) {
                    Toast.makeText(
                        requireContext(),
                        "Aucune source disponible.",
                        Toast.LENGTH_LONG
                    ).show()
                    try { findNavController().navigateUp() } catch (_: Exception) { }
                }
            }
        }
        awaitTimeoutRunnable = poll
        handler.postDelayed(poll, 3_000L)
    }

    /** Cancel the timeout AND clear the awaiting flag (call when a server succeeds or user leaves). */
    private fun cancelAwaitMoreServers() {
        awaitingMoreServers = false
        cancelAwaitTimeoutOnly()
    }

    private fun cancelAwaitTimeoutOnly() {
        awaitTimeoutRunnable?.let { awaitTimeoutHandler?.removeCallbacks(it) }
        awaitTimeoutHandler = null
        awaitTimeoutRunnable = null
    }

    /** 2026-05-04 : messages "patience" désactivés à la demande de l'utilisateur.
     *  Ils apparaissaient pour tous les extracteurs et étaient trompeurs (mention
     *  Cloudflare hardcodée + apparition aléatoire). La fonction est conservée
     *  mais ne fait rien — pour pouvoir les réactiver facilement plus tard. */
    private fun schedulePatienceMessages() {
        cancelPatienceMessages()
        // no-op : tous les toasts désactivés
    }

    private fun cancelPatienceMessages() {
        val h = patienceHandler ?: return
        patienceRunnables.forEach { h.removeCallbacks(it) }
        patienceRunnables.clear()
        patienceHandler = null
    }

    /**
     * Retourne le prochain serveur à essayer en failover auto, avec
     * dégradation de langue contrôlée.
     *
     *  Règles :
     *  1. Cherche d'abord le prochain serveur de la MÊME langue que le current
     *     (si current=VF → cherche le prochain VF dispo dans la liste)
     *  2. Si aucun de la même langue → DÉGRADE :
     *     - VF épuisés → essaie le 1er VOSTFR de la liste (sub français = OK)
     *     - VOSTFR épuisés → essaie le 1er VO de la liste (mieux que rien)
     *  3. VO épuisés → STOP (vraiment plus rien à essayer)
     *
     *  Cette dégradation contrôlée évite le piège qu'on avait observé :
     *  "stop dès que les VF foirent" laissait l'user devant un message
     *  d'erreur alors qu'il y avait peut-être un VOSTFR/VO qui marchait.
     *
     *  L'user peut toujours cliquer manuellement n'importe quel server
     *  dans le picker (qui montre la liste complète).
     */
    private fun nextAutoFallbackServer(allServers: List<Video.Server>, current: Video.Server?): Video.Server? {
        // 2026-07-06 (logique "implacable" — user) : on essaie CHAQUE serveur UNE seule fois,
        //   dans l'ORDRE de la liste (déjà triée VF-d'abord), et on saute UNIQUEMENT ceux déjà
        //   essayés (marqués DEAD pour CE titre = ils ont réellement échoué à l'extraction ou à
        //   la lecture). Retourne null SEULEMENT quand TOUS les serveurs ont été essayés.
        //   SUPPRIMÉ : les "passes" qui effaçaient les DEAD et rebouclaient sur des serveurs déjà
        //   morts (cause du re-pick d'un serveur mort, ex Videasy VOSTFR), et le skip basé sur les
        //   drapeaux HEAD peu fiables. On ne se fie qu'à l'échec RÉEL sur ce titre.
        return allServers.firstOrNull { srv ->
            (current == null || srv.id != current.id) &&
                com.streamflixreborn.streamflix.utils.TitleServerStatus.statusOf(srv.id) !=
                    com.streamflixreborn.streamflix.utils.ExtractorRanker.ServerStatus.DEAD
        }
    }

    /** 2026-07-04 : compteur de passes complètes (reprise depuis le haut, DEAD effacés).
     *  Phase 1 (passes 0-3) : serveurs NON-VOSTFR/VO = FR probable (marqués ou non).
     *  Phase 2 (passes 4-7) : serveurs VOSTFR/VO (le reste).
     *  Remis à 0 dès qu'un flux atteint READY → chaque lecture a ses propres passes. */
    private var autoPassCount = 0
    private val AUTO_VF_PASSES_M = 4
    private val AUTO_REST_PASSES_M = 4

    /** Vrai si le serveur N'EST PAS explicitement marqué VOSTFR ou VO.
     *  = tout ce qui pourrait être FR (y compris non marqué). */
    private fun isLikelyFrSrv(s: com.streamflixreborn.streamflix.models.Video.Server): Boolean {
        val n = s.name.lowercase()
        if (n.contains("vostfr") || n.contains("sous-titr")) return false
        if (Regex("""(^|[^a-z])vo([^a-z]|$)""").containsMatchIn(n)) return false
        if (n.contains(Regex("\\b(raw|eng|english|spa|ita|german|deu|jap)\\b"))) return false
        return true
    }

    /** Détection langue cohérente avec ExtractorRanker. Retourne "vf", "vostfr", "vo". */
    private fun detectServerLanguage(name: String): String {
        val lower = name.lowercase()
        // VOSTFR check FIRST car contient "FR" qui matcherait VF.
        if (lower.contains(Regex("\\b(vostfr|vost|sub|subbed)\\b"))) return "vostfr"
        if (lower.contains(Regex("\\b(vff|vfq|vfi|vf|french|francais|français)\\b"))) return "vf"
        if (lower.contains(Regex("\\b(vo|raw|multi|eng|english|spa|ita|german|deu|jap)\\b"))) return "vo"
        // Pas de marker → assume VF (cas IPTV / chaînes sport)
        return "vf"
    }

    /** Animateur de la barre de progression fictive (0 → 95% sur 5s, stagne à 95%). */
    private var loadingBarAnimator: android.animation.ObjectAnimator? = null
    /** Handler pour le delay de 250ms avant affichage — évite de flasher
     *  l'overlay sur les chargements rapides (cache HIT du pre-extract). */
    private val loadingShowHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var loadingShowRunnable: Runnable? = null

    /** Délai avant affichage de l'overlay. Sous ce délai, l'overlay ne flash
     *  pas — utile quand le pre-extract a déjà caché et que le chargement
     *  est instantané. Au-dessus, l'user voit le spinner et la barre. */
    private val LOADING_OVERLAY_SHOW_DELAY_MS = 250L

    /**
     * Affiche l'overlay de chargement : spinner centré + barre de progression
     * fictive. Appelé sur LoadingServers + LoadingVideo (échec d'un serveur,
     * tentative du suivant).
     *
     *  Délai 250ms avant affichage : si le chargement complète AVANT
     *  (cas du cache HIT du pre-extract), l'overlay ne s'affiche jamais.
     *  C'est le pattern "delayed display" recommandé pour les loaders qui
     *  évite de flasher pour rien.
     */
    /** True si on est sur une chaîne IPTV — pour skip le loading overlay
     *  (le buffer ExoPlayer gère déjà le chargement) qui bloque l'accès
     *  au menu Settings sur ces providers.
     *  2026-05-15 (user "la roulette ne devrait pas y être pour les séries IPTV") :
     *  ajout des prefixes `myiptv-*` (Mon IPTV / Stalker / Xtream custom) qui
     *  étaient oubliés → le 1er clic sur série/film bloquait l'overlay pendant
     *  la résolution Stalker handshake+create_link (3-5s). */
    private fun isIptvChannelContext(): Boolean {
        val id = args.id
        return id.startsWith("ch::") || id.startsWith("sport::") ||
            id.startsWith("ola::") || id.startsWith("ola_ep::") ||
            id.startsWith("vegeta::") || id.startsWith("vegeta_ep::") ||
            id.startsWith("livehub::") || id.startsWith("movixlivetv::") ||
            id.startsWith("sportlive::") || id.startsWith("match::") ||
            id.startsWith("vavoo::") ||
            id.startsWith("myiptv::") || id.startsWith("myiptv-live::") ||
            id.startsWith("myiptv-movie::") || id.startsWith("myiptv-ep::") ||
            id.startsWith("myiptv-show::") || id.startsWith("myiptv-season::") ||
            id.startsWith("myiptv-stalkerep::")
    }

    // ═══════════════════════════════════════════════════════════════
    //  2026-06-12 — Verrou enfant style "overlay plein écran".
    //  Voir setupListeners() pour le wiring des boutons.
    // ═══════════════════════════════════════════════════════════════

    private var unlockFlashJob: kotlinx.coroutines.Job? = null

    // ──────────────────────────────────────────────────────────────────
    // 2026-06-20 : popup menu 3-dots — regroupe lock, cast, wireless,
    // PiP, record, ratio, external, paramètres.
    // ──────────────────────────────────────────────────────────────────
    private fun showPlayerOverflowMenu(anchor: android.view.View) {
        val popup = android.widget.PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.menu_player_overflow_mobile, popup.menu)

        // Forcer l'affichage des icônes dans le PopupMenu (API privée)
        try {
            val method = popup.javaClass.getDeclaredMethod("setForceShowIcon", Boolean::class.javaPrimitiveType)
            method.invoke(popup, true)
        } catch (_: Throwable) {
            // Fallback : on essaie via MenuPopupHelper
            try {
                val field = popup.javaClass.getDeclaredField("mPopup")
                field.isAccessible = true
                val menuPopupHelper = field.get(popup)
                menuPopupHelper.javaClass.getDeclaredMethod("setForceShowIcon", Boolean::class.javaPrimitiveType)
                    .invoke(menuPopupHelper, true)
            } catch (_: Throwable) { /* icônes invisibles = fallback OK, titres restent */ }
        }

        // Adapter le titre "Enregistrer" si un enregistrement est en cours
        if (com.streamflixreborn.streamflix.download.LiveRecorder.isRecording) {
            popup.menu.findItem(R.id.menu_player_record)?.title = "Arrêter l'enregistrement"
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_player_lock -> {
                    engageScreenLock()
                    true
                }
                R.id.menu_player_cast -> {
                    // Déclenche le picker Chromecast via le MediaRouteButton caché
                    binding.pvPlayer.findViewById<androidx.mediarouter.app.MediaRouteButton>(
                        R.id.btn_exo_cast
                    )?.performClick()
                    true
                }
                R.id.menu_player_wireless -> {
                    try {
                        val intent = android.content.Intent("android.settings.CAST_SETTINGS")
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                    } catch (_: Throwable) {
                        try {
                            startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
                        } catch (_: Throwable) {
                            Toast.makeText(requireContext(), "Impossible d'ouvrir les paramètres Cast", Toast.LENGTH_SHORT).show()
                        }
                    }
                    true
                }
                R.id.menu_player_pip -> {
                    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
                        Toast.makeText(requireContext(), getString(R.string.player_picture_in_picture_not_supported), Toast.LENGTH_SHORT).show()
                    } else {
                        enterPIPMode()
                    }
                    true
                }
                R.id.menu_player_record -> {
                    val server = currentServer
                    if (server != null) {
                        handleLiveRecord(server)
                        updateLiveRecordButton()
                    }
                    true
                }
                R.id.menu_player_aspect_ratio -> {
                    val newResize = UserPreferences.playerResize.next()
                    zoomToast?.cancel()
                    zoomToast = Toast.makeText(requireContext(), newResize.stringRes, Toast.LENGTH_SHORT)
                    zoomToast?.show()
                    UserPreferences.playerResize = newResize
                    updatePlayerScale()
                    true
                }
                R.id.menu_player_settings -> {
                    binding.settings.show()
                    true
                }
                R.id.menu_player_external -> {
                    // 2026-06-29 : délègue au lancement fonctionnel (chooser) déjà câblé
                    // sur btnExoExternalPlayer — l'utilisateur choisit son lecteur (VLC, MX…).
                    binding.pvPlayer.controller.binding.btnExoExternalPlayer.performClick()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    /** Active l'overlay verrou : intercepte tous les taps, bloque les
     *  gestures + le back hardware, cache le controller ExoPlayer. */
    private fun engageScreenLock() {
        if (_binding == null) return
        isScreenLocked = true
        screenLockBackCallback?.isEnabled = true
        gestureHelper.isLocked = true
        binding.pvPlayer.hideController()
        // L'overlay couvre tout l'écran et bloque tous les taps.
        binding.layoutScreenLock.visibility = View.VISIBLE
        // Le cadenas est affiché au tap initial (= 3s flash) puis
        // s'auto-cache. L'user retape n'importe où pour le retrouver.
        flashUnlockButton()
        Toast.makeText(
            requireContext(),
            "Écran verrouillé — tape le cadenas pour déverrouiller",
            Toast.LENGTH_SHORT,
        ).show()
    }

    /** Désactive l'overlay verrou : restaure les gestures, le back, le
     *  controller ExoPlayer. */
    private fun disengageScreenLock() {
        if (_binding == null) return
        isScreenLocked = false
        screenLockBackCallback?.isEnabled = false
        gestureHelper.isLocked = false
        unlockFlashJob?.cancel()
        binding.layoutScreenLock.visibility = View.GONE
        binding.btnScreenUnlock.visibility = View.VISIBLE
    }

    /** Affiche le cadenas 3 secondes puis le fade-out. */
    private fun flashUnlockButton() {
        if (_binding == null) return
        unlockFlashJob?.cancel()
        binding.btnScreenUnlock.apply {
            alpha = 1f
            visibility = View.VISIBLE
        }
        unlockFlashJob = viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.delay(3_000)
            if (_binding == null) return@launch
            binding.btnScreenUnlock.animate()
                .alpha(0f)
                .setDuration(400)
                .withEndAction {
                    if (_binding != null) binding.btnScreenUnlock.visibility = View.INVISIBLE
                }
                .start()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  2026-06-12 (user "ajoute un bouton liste des chaines a cote du
    //   cadenas en bas") : équivalent mobile du LEFT D-pad TV.
    //  Tap sur le bouton btnExoChannelList → BottomSheet plein hauteur
    //  avec toutes les chaînes du provider IPTV courant. Tap sur une
    //  chaîne → navigate(R.id.player) avec popUpTo (= replace le player
    //  par la nouvelle chaîne sans empiler).
    // ═══════════════════════════════════════════════════════════════

    private fun showIptvChannelListBottomSheet() {
        val provider = UserPreferences.currentProvider ?: return
        val ctx = context ?: return

        // Layout container du BottomSheet
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, 32, 0, 0)
            setBackgroundColor(0xFF1A1A1A.toInt())
        }

        // 2026-06-13 : on declare le sheet TÔT pour pouvoir l'utiliser dans
        //   le closure du bouton "Fermer".
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(ctx)

        // 2026-06-13 (user "on a oublié de mettre un bouton retour pour
        //   quitter") : ligne titre + bouton retour à droite.
        val headerRow = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(40, 24, 24, 8)
        }
        val titleView = android.widget.TextView(ctx).apply {
            text = "Chaînes — chargement…"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        headerRow.addView(titleView, android.widget.LinearLayout.LayoutParams(
            0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
        ))
        val closeBtn = android.widget.Button(ctx).apply {
            text = "Fermer"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            setBackgroundColor(0x33FFFFFF)
            setPadding(24, 12, 24, 12)
            minWidth = 0
            minimumWidth = 0
            setOnClickListener { sheet.dismiss() }
        }
        headerRow.addView(closeBtn, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        container.addView(headerRow)

        // 2026-06-13 (user "ajoute une barre de recherche dans l'overlay des
        //   chaines IPTV, sur mobile et TV") : EditText filtre la liste
        //   dynamiquement par nom (case-insensitive).
        val searchInput = android.widget.EditText(ctx).apply {
            hint = "Rechercher une chaîne…"
            setHintTextColor(0x88FFFFFF.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setBackgroundColor(0x22FFFFFF)
            setPadding(32, 24, 32, 24)
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        val searchParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(40, 0, 40, 16) }
        container.addView(searchInput, searchParams)

        val rv = androidx.recyclerview.widget.RecyclerView(ctx).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(ctx)
            setPadding(0, 0, 0, 32)
            clipToPadding = false
        }
        container.addView(rv, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            1f,
        ))

        // 2026-06-15 v4 (user "toujours pareil pour la liste des chaînes ça
        //   prend tout l'écran") : utiliser setOnShowListener pour appliquer
        //   la modif APRÈS que la vue soit complètement créée et attachée.
        //   En paysage : window.attributes.width = 60% + gravity START.
        //   C'est l'approche window-level qui contourne le comportement
        //   par défaut de BottomSheetDialog (= match_parent en width).
        val isLandscape = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (isLandscape) {
            sheet.setOnShowListener {
                try {
                    val w = sheet.window
                    if (w != null) {
                        val newWidth = (resources.displayMetrics.widthPixels * 0.2f).toInt()
                        w.setLayout(newWidth, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                        val lp = w.attributes
                        lp.gravity = android.view.Gravity.START or android.view.Gravity.BOTTOM
                        w.attributes = lp
                        Log.d("PlayerMobileFragment", "ChannelList sheet landscape: window width=${newWidth}px (60%) gravity=START|BOTTOM")
                    }
                    // En complément : forcer la largeur de la design_bottom_sheet
                    val designBottomSheet = sheet.findViewById<android.view.View>(
                        com.google.android.material.R.id.design_bottom_sheet
                    )
                    designBottomSheet?.let { view ->
                        val newWidth = (resources.displayMetrics.widthPixels * 0.2f).toInt()
                        val params = view.layoutParams
                        params.width = newWidth
                        view.layoutParams = params
                        (view.layoutParams as? android.widget.FrameLayout.LayoutParams)?.gravity =
                            android.view.Gravity.START or android.view.Gravity.BOTTOM
                        view.requestLayout()
                    }
                } catch (e: Throwable) {
                    Log.w("PlayerMobileFragment", "ChannelList landscape resize failed: ${e.message}")
                }
            }
        }
        // sheet déclaré plus haut, on le configure et l'affiche ici.
        sheet.setContentView(container)
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.peekHeight = (resources.displayMetrics.heightPixels * 0.75f).toInt()
        sheet.show()

        // Charger les chaînes en background (= même logique que PlayerTvFragment)
        viewLifecycleOwner.lifecycleScope.launch {
            val items = withContext(kotlinx.coroutines.Dispatchers.Default) {
                val channelIds: List<String> = when (provider) {
                    is com.streamflixreborn.streamflix.providers.OlaTvProvider -> provider.getOrderedChannelIds()
                    is com.streamflixreborn.streamflix.providers.VegetaTvProvider -> provider.getOrderedChannelIds()
                    is com.streamflixreborn.streamflix.providers.LiveTvHubPlusProvider -> provider.getOrderedChannelIds()
                    is com.streamflixreborn.streamflix.providers.LiveTvHubProvider -> provider.getOrderedChannelIds(args.id)
                    is com.streamflixreborn.streamflix.providers.VavooProvider -> provider.getOrderedChannelIds()
                    is com.streamflixreborn.streamflix.providers.MyIptvProvider -> provider.getOrderedLiveChannelIds()
                    else -> emptyList()
                }
                channelIds.mapNotNull { id ->
                    val name = when (provider) {
                        is com.streamflixreborn.streamflix.providers.OlaTvProvider -> provider.getChannelDisplayName(id)
                        is com.streamflixreborn.streamflix.providers.VegetaTvProvider -> provider.getChannelDisplayName(id)
                        is com.streamflixreborn.streamflix.providers.LiveTvHubPlusProvider -> provider.getChannelDisplayName(id)
                        is com.streamflixreborn.streamflix.providers.LiveTvHubProvider -> provider.getChannelDisplayName(id)
                        is com.streamflixreborn.streamflix.providers.VavooProvider -> provider.getChannelDisplayName(id)
                        is com.streamflixreborn.streamflix.providers.MyIptvProvider -> provider.getChannelDisplayName(id)
                        else -> null
                    } ?: return@mapNotNull null
                    val logo = when (provider) {
                        is com.streamflixreborn.streamflix.providers.OlaTvProvider -> provider.getChannelPoster(id)
                        is com.streamflixreborn.streamflix.providers.VegetaTvProvider -> provider.getChannelPoster(id)
                        is com.streamflixreborn.streamflix.providers.LiveTvHubPlusProvider -> provider.getChannelPoster(id)
                        is com.streamflixreborn.streamflix.providers.LiveTvHubProvider -> provider.getChannelPoster(id)
                        is com.streamflixreborn.streamflix.providers.VavooProvider -> provider.getChannelPoster(id)
                        is com.streamflixreborn.streamflix.providers.MyIptvProvider -> provider.getChannelPoster(id)
                        else -> null
                    }
                    Triple(id, name, logo)
                }
            }

            if (!isAdded || _binding == null) return@launch
            titleView.text = "Chaînes (${items.size})"
            val mutableItems = items.toMutableList()
            val adapter = IptvChannelListMobileAdapter(mutableItems, args.id) { selectedId ->
                sheet.dismiss()
                switchToIptvChannel(selectedId, provider)
            }
            rv.adapter = adapter
            // Scroll vers la chaîne actuelle
            val currentIdx = items.indexOfFirst { it.first == args.id }
            if (currentIdx >= 0) rv.scrollToPosition(currentIdx)

            // 2026-06-13 : filtre live sur EditText
            searchInput.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val q = s?.toString()?.trim()?.lowercase().orEmpty()
                    val filtered = if (q.isBlank()) items
                        else items.filter { it.second.lowercase().contains(q) }
                    adapter.replaceItems(filtered)
                    titleView.text = if (q.isBlank()) "Chaînes (${filtered.size})"
                        else "Chaînes (${filtered.size}/${items.size})"
                    if (filtered.isNotEmpty()) rv.scrollToPosition(0)
                }
            })
        }
    }

    /**
     * 2026-06-20 (user "j'ai dit de faire ça aussi sur mobile en met le même
     * icône que pour IPTV pour afficher ce menu là") : bottom-sheet avec la
     * liste des épisodes de la série en cours. Réutilise EpisodeManager
     * (= déjà rempli par le détail de la série), peu importe le provider VOD.
     */
    /**
     * 2026-06-20 (user "comme sur l'image, panel épisodes droite avec saisons +
     *   cartes riches") : remplace l'ancien AlertDialog par un VRAI panel
     *   side-right intégré, calqué sur PlayerTvFragment.showEpisodePanel.
     *   Slide depuis la droite, onglets saisons en haut, cards riches en bas
     *   (poster + titre + meta + extrait synopsis), épisode courant rouge.
     *   Background = thème user.
     */
    private var episodePanelMobileVisible: Boolean = false
    /** 2026-06-21 (drill-down) : stack pour navigation dans les sous-dossiers. */
    private data class EpisodePanelLevelMobile(
        val episodes: List<com.streamflixreborn.streamflix.models.Video.Type.Episode>,
        val currentId: String?,
        val title: String,
    )
    private val episodePanelMobileStack: MutableList<EpisodePanelLevelMobile> = mutableListOf()
    private var episodePanelMobileAdapter: com.streamflixreborn.streamflix.adapters.EpisodePanelAdapter? = null
    private var episodePanelMobileCurrentSeason: Int = -1

    private fun showEpisodeListBottomSheet() {
        showEpisodePanelMobile()
    }

    private fun showEpisodePanelMobile() {
        if (episodePanelMobileVisible) return
        val ctx = context ?: return
        if (_binding == null) return
        val allEpisodes = com.streamflixreborn.streamflix.utils.EpisodeManager.getAllEpisodes()
        if (allEpisodes.isEmpty()) {
            android.widget.Toast.makeText(ctx, "Aucun épisode disponible",
                android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val panel = binding.layoutEpisodePanelMobile

        // Background = couleur du thème user
        try {
            val theme = com.streamflixreborn.streamflix.utils.UserPreferences.selectedTheme
            val palette = com.streamflixreborn.streamflix.utils.ThemeManager.palette(theme)
            val bg = palette.mobileNavBackground
            val r = android.graphics.Color.red(bg)
            val g = android.graphics.Color.green(bg)
            val b = android.graphics.Color.blue(bg)
            panel.setBackgroundColor(android.graphics.Color.argb(0xEE, r, g, b))
        } catch (_: Throwable) {
            panel.setBackgroundColor(0xEE000000.toInt())
        }

        panel.visibility = View.VISIBLE
        panel.bringToFront()
        panel.animate().translationX(0f).setDuration(200).start()
        episodePanelMobileVisible = true

        val currentEpId = args.id
        val groupedInMemory = allEpisodes.groupBy { it.season.number }
            .toSortedMap().toMutableMap()
        val currentSeasonNum = allEpisodes.firstOrNull { it.id == currentEpId }
            ?.season?.number ?: groupedInMemory.keys.firstOrNull() ?: 1
        episodePanelMobileCurrentSeason = currentSeasonNum

        // 2026-06-21 (user "pour les saisons elle n'apparaissent pas toutes") :
        //   query provider pour récupérer TOUTES les saisons de la série.
        val seasonTabsContainer = binding.llEpisodeSeasonTabsMobile
        val rv = binding.rvEpisodePanelListMobile
        val dp = resources.displayMetrics.density
        val episodesBySeasonCache = mutableMapOf<Int, List<com.streamflixreborn.streamflix.models.Video.Type.Episode>>()
        episodesBySeasonCache.putAll(groupedInMemory)
        val seasonIdCache = mutableMapOf<Int, String>()

        // Adapter initialisé avec la saison courante (= déjà chargée)
        val initialEps = groupedInMemory[currentSeasonNum].orEmpty()
        binding.tvEpisodePanelTitleMobile.text = "Épisodes (${initialEps.size})"
        rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(ctx)
        val adapter = com.streamflixreborn.streamflix.adapters.EpisodePanelAdapter(
            initialEps, currentEpId
        ) { ep ->
            // 2026-06-21 v2 (user "il prend pas en charge les double dossiers
            //   — VOSTFR/FR. Si je clique sur le dossier il m'affiche pas
            //   les saisons dedans") : si ep est un wrapper langue (id
            //   commence par "@subfolder:" ou overview == "@subfolder"), on
            //   ferme le panel + navigue vers Season fragment qui sait
            //   afficher les sous-saisons. Pour les vrais épisodes, on
            //   charge in-place comme avant.
            // 2026-06-21 v3 (user "pareil sur mobile quand on clique dessus
            //   ça fait un chargement mais ça ne change pas d'épisode") :
            //   navigate(R.id.season) ne marchait pas. Refactor : DRILL-DOWN
            //   dans le panel. Fetch les sous-épisodes et remplace la liste.
            //   BACK pop vers le parent.
            val isSubfolder = ep.id.startsWith("@subfolder:") || ep.overview == "@subfolder"
            if (isSubfolder) {
                val realSeasonId = ep.id.removePrefix("@subfolder:")
                android.util.Log.d("PlayerMobileFragment", "subfolder drill-down → seasonId=$realSeasonId")
                binding.tvEpisodePanelTitleMobile.text = "Épisodes — chargement…"
                viewLifecycleOwner.lifecycleScope.launch {
                    val sub = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            fetchEpisodesForSeasonMobile(realSeasonId, ep.number)
                        } catch (t: Throwable) {
                            android.util.Log.e("PlayerMobileFragment", "drill-down fetch failed: ${t.message}", t)
                            emptyList()
                        }
                    }
                    if (!episodePanelMobileVisible || _binding == null) return@launch
                    if (sub.isEmpty()) {
                        android.widget.Toast.makeText(ctx,
                            "Aucun épisode dans ce dossier",
                            android.widget.Toast.LENGTH_SHORT).show()
                        binding.tvEpisodePanelTitleMobile.text = "Épisodes (${initialEps.size})"
                        return@launch
                    }
                    // Snapshot parent pour BACK
                    val parentEps = episodesBySeasonCache[episodePanelMobileCurrentSeason] ?: initialEps
                    episodePanelMobileStack.add(EpisodePanelLevelMobile(
                        episodes = parentEps,
                        currentId = currentEpId,
                        title = "Épisodes (${parentEps.size})",
                    ))
                    binding.tvEpisodePanelTitleMobile.text = "${ep.title ?: "Dossier"} (${sub.size})"
                    episodePanelMobileAdapter?.updateEpisodes(sub, currentEpId)
                    binding.rvEpisodePanelListMobile.scrollToPosition(0)
                }
                return@EpisodePanelAdapter
            }
            // 2026-06-21 v3 (user "clic sur épisode, ça fait un chargement
            //   mais ne change pas d'épisode") :
            //   viewModel.playEpisode(ep) seul ne switch PAS le MediaItem du
            //   player (le state collector ne re-trigger pas comme attendu).
            //   On utilise switchToEpisode(ep) qui emit _playPreviousOrNext
            //   Episode → déclenche la recréation du fragment avec les
            //   nouveaux args (= même chemin que Next/Previous qui marche).
            //   Un flag statique réouvre le panel automatiquement après la
            //   recréation pour préserver l'UX panel-toujours-ouvert.
            try {
                com.streamflixreborn.streamflix.utils.EpisodeManager.setCurrentEpisode(ep)
                pendingReopenPanelMobile = true
                viewModel.switchToEpisode(ep)
            } catch (t: Throwable) {
                android.util.Log.e("PlayerMobileFragment",
                    "switchToEpisode failed: ${t.message}", t)
            }
        }
        episodePanelMobileAdapter = adapter
        rv.adapter = adapter
        val currentIdx = initialEps.indexOfFirst { it.id == currentEpId }
        if (currentIdx >= 0) rv.scrollToPosition(currentIdx)

        // 2026-06-21 v2 (user "il confond Saison 1 et Saison 2 avec FR et
        //   VOSTFR") : map seasonNumber → vrai titre (= "VOSTFR" / "VF" /
        //   "Saison 1" / etc.) pour afficher le bon libellé d'onglet. On
        //   tape dans EpisodeManager qui a la liste des Season avec titles.
        val seasonTitlesByNumMobile: Map<Int, String?> = try {
            com.streamflixreborn.streamflix.utils.EpisodeManager.getAllEpisodes()
                .distinctBy { it.season.number }
                .associate { it.season.number to it.season.title }
        } catch (_: Throwable) { emptyMap() }
        // Helper : construit les boutons saisons à partir d'une liste de Nº
        fun buildSeasonTabsMobile(allSeasonNums: List<Int>) {
            seasonTabsContainer.removeAllViews()
            allSeasonNums.forEach { seasonNum ->
                val realTitle = seasonTitlesByNumMobile[seasonNum]?.takeIf { it.isNotBlank() }
                val btn = android.widget.Button(ctx).apply {
                    // Si le provider a fourni un titre (= "VOSTFR", "VF",
                    //   "Spécial...", etc.), on l'utilise. Sinon "Saison N".
                    text = realTitle ?: "Saison $seasonNum"
                    textSize = 12f
                    setPadding((14 * dp).toInt(), (8 * dp).toInt(),
                        (14 * dp).toInt(), (8 * dp).toInt())
                    minWidth = 0
                    minHeight = 0
                    val isSelected = seasonNum == episodePanelMobileCurrentSeason
                    setBackgroundColor(if (isSelected)
                        android.graphics.Color.parseColor("#CC8B0000")
                        else android.graphics.Color.parseColor("#22FFFFFF"))
                    setTextColor(android.graphics.Color.WHITE)
                    val lp = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
                    lp.marginEnd = (8 * dp).toInt()
                    layoutParams = lp
                    setOnClickListener {
                        episodePanelMobileCurrentSeason = seasonNum
                        for (i in 0 until seasonTabsContainer.childCount) {
                            val tab = seasonTabsContainer.getChildAt(i)
                            val tabSeason = allSeasonNums[i]
                            tab.setBackgroundColor(if (tabSeason == seasonNum)
                                android.graphics.Color.parseColor("#CC8B0000")
                                else android.graphics.Color.parseColor("#22FFFFFF"))
                        }
                        // Cache HIT ou lazy fetch. 2026-06-21 v4 (user "elle
                        //   devrait déjà tout afficher") : auto-flatten si la
                        //   liste est all-subfolder.
                        val cached = episodesBySeasonCache[seasonNum]
                        if (cached != null) {
                            viewLifecycleOwner.lifecycleScope.launch {
                                val displayList = if (cached.all { it.id.startsWith("@subfolder:") || it.overview == "@subfolder" } && cached.isNotEmpty()) {
                                    binding.tvEpisodePanelTitleMobile.text = "Épisodes — chargement…"
                                    flattenSubfoldersMobile(cached, seasonNum)
                                } else cached
                                if (!episodePanelMobileVisible || _binding == null) return@launch
                                episodesBySeasonCache[seasonNum] = displayList
                                binding.tvEpisodePanelTitleMobile.text = "Épisodes (${displayList.size})"
                                episodePanelMobileAdapter?.updateEpisodes(displayList, currentEpId)
                            }
                        } else {
                            binding.tvEpisodePanelTitleMobile.text = "Épisodes — chargement…"
                            viewLifecycleOwner.lifecycleScope.launch {
                                val fetched = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    try {
                                        val seasonId = seasonIdCache[seasonNum] ?: ""
                                        if (seasonId.isBlank()) emptyList()
                                        else fetchEpisodesForSeasonMobile(seasonId, seasonNum)
                                    } catch (_: Throwable) { emptyList() }
                                }
                                if (!episodePanelMobileVisible || _binding == null) return@launch
                                val displayList = if (fetched.all { it.id.startsWith("@subfolder:") || it.overview == "@subfolder" } && fetched.isNotEmpty()) {
                                    flattenSubfoldersMobile(fetched, seasonNum)
                                } else fetched
                                if (!episodePanelMobileVisible || _binding == null) return@launch
                                episodesBySeasonCache[seasonNum] = displayList
                                binding.tvEpisodePanelTitleMobile.text = "Épisodes (${displayList.size})"
                                episodePanelMobileAdapter?.updateEpisodes(displayList, currentEpId)
                            }
                        }
                    }
                }
                seasonTabsContainer.addView(btn)
            }
        }

        // Tabs initiales avec ce qu'on a en mémoire
        buildSeasonTabsMobile(groupedInMemory.keys.toList())

        // 2026-06-24 (user "il manque pas VF et VOSTFR en haut") : onglets
        //   LANGUE au-dessus des saisons (= AnimeSama uniquement). Refetch
        //   tvShow avec slug@vf/slug@vostfr au clic → rebuild season tabs.
        run buildLangTabs@{
            val langScroll = binding.hsvEpisodeLangTabsMobile
            val langContainer = binding.llEpisodeLangTabsMobile
            val provider = com.streamflixreborn.streamflix.utils.UserPreferences.currentProvider
            val isAnimeSama = com.streamflixreborn.streamflix.utils.MultiLangDetector.supportsTvShowIdRefetch(provider)
            if (!isAnimeSama) {
                langScroll.visibility = View.GONE
                return@buildLangTabs
            }
            val rawTvShowId = (args.videoType as? com.streamflixreborn.streamflix.models.Video.Type.Episode)
                ?.tvShow?.id ?: return@buildLangTabs
            // 2026-06-24 v11 mobile : currentLang MUTABLE + détection depuis
            //   args.id en priorité (= langue réelle de l'épisode joué).
            var currentLang = com.streamflixreborn.streamflix.utils.MultiLangDetector.langOf(args.id)
                ?: com.streamflixreborn.streamflix.utils.MultiLangDetector.langOfTvShowId(rawTvShowId)
                ?: "vostfr"
            langScroll.visibility = View.VISIBLE
            langContainer.removeAllViews()
            val langsOrderedMobile = listOf("vostfr" to "VOSTFR", "vf" to "VF")
            val repaintLangTabsMobile: () -> Unit = {
                for (i in 0 until langContainer.childCount) {
                    val tab = langContainer.getChildAt(i)
                    val tabLang = langsOrderedMobile.getOrNull(i)?.first ?: continue
                    val sel = tabLang == currentLang
                    tab.setBackgroundColor(if (sel)
                        android.graphics.Color.parseColor("#CC1565C0")
                        else android.graphics.Color.parseColor("#22FFFFFF"))
                }
            }
            langsOrderedMobile.forEach { (langCode, label) ->
                val isSelected = langCode == currentLang
                val btn = android.widget.Button(ctx).apply {
                    text = label
                    textSize = 12f
                    setPadding((14 * dp).toInt(), (8 * dp).toInt(),
                        (14 * dp).toInt(), (8 * dp).toInt())
                    minWidth = 0; minHeight = 0
                    setBackgroundColor(if (isSelected)
                        android.graphics.Color.parseColor("#CC1565C0")
                        else android.graphics.Color.parseColor("#22FFFFFF"))
                    setTextColor(android.graphics.Color.WHITE)
                    val lp = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
                    lp.marginEnd = (8 * dp).toInt()
                    layoutParams = lp
                    setOnClickListener {
                        if (langCode == currentLang) return@setOnClickListener
                        val slug = rawTvShowId.substringBefore("@")
                        val newTvShowId = "$slug@$langCode"
                        binding.tvEpisodePanelTitleMobile.text = "Épisodes — chargement ${label}…"
                        viewLifecycleOwner.lifecycleScope.launch {
                            val newSeasons = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                try { provider?.getTvShow(newTvShowId)?.seasons.orEmpty() } catch (_: Throwable) { emptyList() }
                            }
                            if (!episodePanelMobileVisible || _binding == null) return@launch
                            if (newSeasons.isEmpty()) {
                                android.widget.Toast.makeText(ctx,
                                    "Aucune saison en $label",
                                    android.widget.Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            episodesBySeasonCache.clear()
                            seasonIdCache.clear()
                            newSeasons.forEach { s -> seasonIdCache[s.number] = s.id }
                            val newNums = newSeasons.map { it.number }.distinct().sorted()
                            episodePanelMobileCurrentSeason = newNums.firstOrNull() ?: 1
                            buildSeasonTabsMobile(newNums)
                            val firstSeasonId = seasonIdCache[episodePanelMobileCurrentSeason] ?: ""
                            val firstEps = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                if (firstSeasonId.isBlank()) emptyList()
                                else try { fetchEpisodesForSeasonMobile(firstSeasonId, episodePanelMobileCurrentSeason) } catch (_: Throwable) { emptyList() }
                            }
                            if (!episodePanelMobileVisible || _binding == null) return@launch
                            val displayList = if (firstEps.isNotEmpty() && firstEps.all { it.id.startsWith("@subfolder:") || it.overview == "@subfolder" })
                                flattenSubfoldersMobile(firstEps, episodePanelMobileCurrentSeason)
                            else firstEps
                            if (!episodePanelMobileVisible || _binding == null) return@launch
                            episodesBySeasonCache[episodePanelMobileCurrentSeason] = displayList
                            binding.tvEpisodePanelTitleMobile.text = "Épisodes — $label (${displayList.size})"
                            episodePanelMobileAdapter?.updateEpisodes(displayList, currentEpId)
                            rv.scrollToPosition(0)
                            // ⭐ FIX bug refresh : UPDATE currentLang AVANT repaint
                            currentLang = langCode
                            repaintLangTabsMobile()
                        }
                    }
                }
                langContainer.addView(btn)
            }
        }

        // 2026-06-21 (user "pourquoi ton code se fie pas directement à
        //   l'endroit où on passe par la synopsis pour récupérer les saisons") :
        //   lire les saisons depuis la BASE DE DONNÉES Room (= synopsis).
        //   Fallback provider.getTvShow si DB vide.
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val tvShowId = (args.videoType as? com.streamflixreborn.streamflix.models.Video.Type.Episode)
                    ?.tvShow?.id ?: return@launch
                val database = com.streamflixreborn.streamflix.database.AppDatabase
                    .getInstance(ctx.applicationContext)
                val provider = com.streamflixreborn.streamflix.utils.UserPreferences.currentProvider
                // 2026-06-24 v10 : @lang force fetch vraies saisons.
                val tvShowIdForPanel = if (com.streamflixreborn.streamflix.utils.MultiLangDetector.supportsTvShowIdRefetch(provider) && !tvShowId.contains("@")) {
                    val lang = when {
                        args.id.contains("/vostfr") -> "vostfr"
                        args.id.contains("/vf") -> "vf"
                        else -> "vostfr"
                    }
                    "$tvShowId@$lang"
                } else tvShowId
                // 2026-06-24 v12 mobile : skip DB cache pour AnimeSama (= il
                //   contient les wrappers VOSTFR/VF qui pollueraient le merge).
                val skipDbForAnimeSama = com.streamflixreborn.streamflix.utils.MultiLangDetector.supportsTvShowIdRefetch(provider)
                val dbSeasons = if (skipDbForAnimeSama) emptyList()
                    else withContext(kotlinx.coroutines.Dispatchers.IO) {
                        try { database.seasonDao().getByTvShowId(tvShowId) } catch (_: Throwable) { emptyList() }
                    }
                val providerSeasons = if (provider != null) {
                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                        try { provider.getTvShow(tvShowIdForPanel).seasons } catch (_: Throwable) { emptyList() }
                    }
                } else emptyList()
                if (!episodePanelMobileVisible || _binding == null) return@launch
                val byNum = mutableMapOf<Int, com.streamflixreborn.streamflix.models.Season>()
                // Merge DB + provider seasons. Wiflix retourne parfois
                // 2 seasons same number avec ids "blocvostfr"/"blocfr"
                // → priorité à la lang du current épisode.
                dbSeasons.forEach { byNum[it.number] = it }
                val userLangIsVf = args.id.contains("blocfr") || args.id.contains("/vf")
                val userLangIsVostfr = args.id.contains("blocvostfr") || args.id.contains("/vostfr")
                providerSeasons.forEach { s ->
                    val existing = byNum[s.number]
                    if (existing == null) {
                        byNum[s.number] = s
                        return@forEach
                    }
                    val newIsVf = s.id.contains("blocfr") || s.id.contains("/vf")
                    val newIsVostfr = s.id.contains("blocvostfr") || s.id.contains("/vostfr")
                    val existingIsVf = existing.id.contains("blocfr") || existing.id.contains("/vf")
                    val existingIsVostfr = existing.id.contains("blocvostfr") || existing.id.contains("/vostfr")
                    val takeNew = when {
                        userLangIsVf && newIsVf && !existingIsVf -> true
                        userLangIsVostfr && newIsVostfr && !existingIsVostfr -> true
                        else -> false
                    }
                    if (takeNew) byNum[s.number] = s
                }
                val sortedSeasons = byNum.values.sortedBy { it.number }
                sortedSeasons.forEach { seasonIdCache[it.number] = it.id }
                val allSeasonNums = sortedSeasons.map { it.number }.distinct()
                // 2026-06-24 v13 mobile : pour AnimeSama, force rebuild tabs
                //   + patch labels "Saison N" + refetch épisodes saison
                //   courante avec le seasonId réel (= clean, pas @subfolder).
                if (com.streamflixreborn.streamflix.utils.MultiLangDetector.supportsTvShowIdRefetch(provider)) {
                    if (allSeasonNums.isNotEmpty()) buildSeasonTabsMobile(allSeasonNums)
                    for (i in 0 until seasonTabsContainer.childCount) {
                        val btn = seasonTabsContainer.getChildAt(i) as? android.widget.Button ?: continue
                        val seasonNum = allSeasonNums.getOrNull(i) ?: continue
                        btn.text = "Saison $seasonNum"
                    }
                    val curSeasonNum = episodePanelMobileCurrentSeason
                    val curSeasonId = seasonIdCache[curSeasonNum] ?: ""
                    if (curSeasonId.isNotBlank()) {
                        launch {
                            val freshEps = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                try { fetchEpisodesForSeasonMobile(curSeasonId, curSeasonNum) }
                                catch (_: Throwable) { emptyList() }
                            }
                            if (!episodePanelMobileVisible || _binding == null) return@launch
                            val displayList = if (freshEps.isNotEmpty() && freshEps.all { it.id.startsWith("@subfolder:") || it.overview == "@subfolder" })
                                flattenSubfoldersMobile(freshEps, curSeasonNum)
                            else freshEps
                            if (!episodePanelMobileVisible || _binding == null) return@launch
                            val cleanList = displayList.filter {
                                !it.id.startsWith("@subfolder:") && it.overview != "@subfolder"
                            }
                            if (cleanList.isEmpty()) return@launch
                            episodesBySeasonCache[curSeasonNum] = cleanList
                            binding.tvEpisodePanelTitleMobile.text = "Épisodes (${cleanList.size})"
                            episodePanelMobileAdapter?.updateEpisodes(cleanList, currentEpId)
                        }
                    }
                } else if (allSeasonNums.isNotEmpty() && allSeasonNums != groupedInMemory.keys.toList()) {
                    buildSeasonTabsMobile(allSeasonNums)
                }
            } catch (_: Throwable) {}
        }

        binding.btnEpisodePanelCloseMobile.setOnClickListener { hideEpisodePanelMobile() }
    }

    /** 2026-06-21 v4 (user "elle devrait déjà tout afficher") :
     *  Aplatit les sous-dossiers en concaténant leurs contenus. 1 niveau
     *  de profondeur. Si nested → garde le folder pour drill-down manuel. */
    private suspend fun flattenSubfoldersMobile(
        subfolders: List<com.streamflixreborn.streamflix.models.Video.Type.Episode>,
        parentSeasonNumber: Int,
    ): List<com.streamflixreborn.streamflix.models.Video.Type.Episode> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val out = mutableListOf<com.streamflixreborn.streamflix.models.Video.Type.Episode>()
        for (folder in subfolders) {
            val realSeasonId = folder.id.removePrefix("@subfolder:")
            val sub = try {
                fetchEpisodesForSeasonMobile(realSeasonId, parentSeasonNumber)
            } catch (_: Throwable) { emptyList() }
            val isStillNested = sub.isNotEmpty() && sub.all {
                it.id.startsWith("@subfolder:") || it.overview == "@subfolder"
            }
            if (isStillNested || sub.isEmpty()) {
                out.add(folder)
            } else {
                val prefix = folder.title?.takeIf { it.isNotBlank() }
                out.addAll(sub.map { ep ->
                    if (prefix != null && ep.title != null && !ep.title.startsWith(prefix)) {
                        ep.copy(title = "[$prefix] ${ep.title}")
                    } else ep
                })
            }
        }
        out
    }

    /** 2026-06-21 : fetch async les épisodes d'une saison via provider. */
    private suspend fun fetchEpisodesForSeasonMobile(
        seasonId: String,
        seasonNumber: Int,
    ): List<com.streamflixreborn.streamflix.models.Video.Type.Episode> {
        val provider = com.streamflixreborn.streamflix.utils.UserPreferences.currentProvider
            ?: return emptyList()
        val tvShowId = (args.videoType as? com.streamflixreborn.streamflix.models.Video.Type.Episode)
            ?.tvShow?.id ?: return emptyList()
        val episodes = try {
            provider.getEpisodesBySeason(seasonId)
        } catch (_: Throwable) { return emptyList() }
        // 2026-06-24 v14 mobile (user "S02E01 · VF alors que c'est VOSTFR") :
        //   append @lang détecté depuis seasonId pour que getTvShow renvoie
        //   les vraies saisons (= pas les wrappers VOSTFR/VF).
        val tvShowIdForMeta = if (com.streamflixreborn.streamflix.utils.MultiLangDetector.supportsTvShowIdRefetch(provider) && !tvShowId.contains("@")) {
            val lang = when {
                seasonId.contains("/vf") -> "vf"
                seasonId.contains("/vostfr") -> "vostfr"
                else -> "vostfr"
            }
            "$tvShowId@$lang"
        } else tvShowId
        val tvShowMeta = try { provider.getTvShow(tvShowIdForMeta) } catch (_: Throwable) { null }
        val seasonMeta = tvShowMeta?.seasons?.firstOrNull { it.number == seasonNumber }
        val cleanSeasonTitle = seasonMeta?.title?.takeIf {
            it.isNotBlank() && it != "VOSTFR" && it != "VF"
        }
        return episodes.map { ep ->
            com.streamflixreborn.streamflix.models.Video.Type.Episode(
                id = ep.id,
                number = ep.number,
                title = ep.title,
                poster = ep.poster,
                overview = ep.overview,
                tvShow = com.streamflixreborn.streamflix.models.Video.Type.Episode.TvShow(
                    id = tvShowId,
                    title = tvShowMeta?.title ?: "",
                    poster = tvShowMeta?.poster,
                    banner = tvShowMeta?.banner,
                    releaseDate = null,
                    imdbId = tvShowMeta?.imdbId,
                ),
                season = com.streamflixreborn.streamflix.models.Video.Type.Episode.Season(
                    number = seasonNumber,
                    title = cleanSeasonTitle,
                ),
            )
        }
    }

    private fun hideEpisodePanelMobile() {
        if (!episodePanelMobileVisible || _binding == null) return
        // 2026-06-21 (drill-down) : si on est en sous-niveau, BACK restaure
        //   le niveau parent au lieu de fermer le panel.
        if (episodePanelMobileStack.isNotEmpty()) {
            val parent = episodePanelMobileStack.removeAt(episodePanelMobileStack.size - 1)
            binding.tvEpisodePanelTitleMobile.text = parent.title
            episodePanelMobileAdapter?.updateEpisodes(parent.episodes, parent.currentId)
            binding.rvEpisodePanelListMobile.scrollToPosition(0)
            return
        }
        val panel = binding.layoutEpisodePanelMobile
        val widthPx = panel.width.toFloat().takeIf { it > 0 }
            ?: (360 * resources.displayMetrics.density)
        panel.animate().translationX(widthPx)
            .setDuration(200)
            .withEndAction { if (_binding != null) panel.visibility = View.GONE }
            .start()
        episodePanelMobileVisible = false
    }

    /** 2026-06-21 : fermeture complète du panel (croix UI / leave-fullscreen).
     *  Vide le drill-down stack avant de hider. */
    private fun forceCloseEpisodePanelMobile() {
        episodePanelMobileStack.clear()
        if (!episodePanelMobileVisible || _binding == null) return
        val panel = binding.layoutEpisodePanelMobile
        val widthPx = panel.width.toFloat().takeIf { it > 0 }
            ?: (360 * resources.displayMetrics.density)
        panel.animate().translationX(widthPx)
            .setDuration(200)
            .withEndAction { if (_binding != null) panel.visibility = View.GONE }
            .start()
        episodePanelMobileVisible = false
    }

    /**
     * 2026-06-15 (user "tu n'arrives pas à prendre le même pattern que TV") :
     * Version PANEL INTÉGRÉ (= pattern TV qui marche) du channel list mobile.
     * Utilise layout_channel_list_mobile du fragment_player_mobile.xml au lieu
     * d'un BottomSheetDialog. Largeur = layout_constraintWidth_percent ajusté
     * selon orientation (1.0 portrait, 0.2 paysage).
     */
    private fun showIptvChannelListPanelMobile() {
        val provider = UserPreferences.currentProvider ?: return
        if (_binding == null) return

        val panel = binding.layoutChannelListMobile
        val titleView = binding.tvChannelListTitleMobile
        val searchInput = binding.etChannelSearchMobile
        val rv = binding.rvChannelListMobile
        val closeBtn = binding.btnChannelListCloseMobile

        // Largeur selon orientation via ConstraintSet
        val root = binding.root
        val cs = androidx.constraintlayout.widget.ConstraintSet()
        cs.clone(root)
        val isLandscape = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        // 2026-06-15 v12 (user "l'overlay prend tout le téléphone, on avait
        //   réglé") : ConstraintSet.constrainPercentWidth() ne fait RIEN si
        //   `widthDefault` n'est pas explicitement set à MATCH_CONSTRAINT_PERCENT.
        //   Sans ce setter, le percent est ignoré → width reste 0dp → conteneur
        //   prend tout l'espace dispo. Le set ci-dessous active le mode percent.
        cs.constrainDefaultWidth(
            R.id.layout_channel_list_mobile,
            androidx.constraintlayout.widget.ConstraintSet.MATCH_CONSTRAINT_PERCENT
        )
        // 2026-06-15 v17 (user "20 %") : landscape strict 20%, portrait 40%.
        cs.constrainPercentWidth(
            R.id.layout_channel_list_mobile,
            if (isLandscape) 0.2f else 0.4f
        )
        cs.applyTo(root)

        titleView.text = "Chaînes — chargement…"
        rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        panel.visibility = View.VISIBLE
        panel.bringToFront()

        closeBtn.setOnClickListener {
            panel.visibility = View.GONE
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val items = withContext(kotlinx.coroutines.Dispatchers.Default) {
                val channelIds: List<String> = when (provider) {
                    is com.streamflixreborn.streamflix.providers.OlaTvProvider -> provider.getOrderedChannelIds()
                    is com.streamflixreborn.streamflix.providers.VegetaTvProvider -> provider.getOrderedChannelIds()
                    is com.streamflixreborn.streamflix.providers.LiveTvHubPlusProvider -> provider.getOrderedChannelIds()
                    is com.streamflixreborn.streamflix.providers.LiveTvHubProvider -> provider.getOrderedChannelIds(args.id)
                    is com.streamflixreborn.streamflix.providers.VavooProvider -> provider.getOrderedChannelIds()
                    is com.streamflixreborn.streamflix.providers.MyIptvProvider -> provider.getOrderedLiveChannelIds()
                    else -> emptyList()
                }
                channelIds.mapNotNull { id ->
                    val name = when (provider) {
                        is com.streamflixreborn.streamflix.providers.OlaTvProvider -> provider.getChannelDisplayName(id)
                        is com.streamflixreborn.streamflix.providers.VegetaTvProvider -> provider.getChannelDisplayName(id)
                        is com.streamflixreborn.streamflix.providers.LiveTvHubPlusProvider -> provider.getChannelDisplayName(id)
                        is com.streamflixreborn.streamflix.providers.LiveTvHubProvider -> provider.getChannelDisplayName(id)
                        is com.streamflixreborn.streamflix.providers.VavooProvider -> provider.getChannelDisplayName(id)
                        is com.streamflixreborn.streamflix.providers.MyIptvProvider -> provider.getChannelDisplayName(id)
                        else -> null
                    } ?: return@mapNotNull null
                    val logo = when (provider) {
                        is com.streamflixreborn.streamflix.providers.OlaTvProvider -> provider.getChannelPoster(id)
                        is com.streamflixreborn.streamflix.providers.VegetaTvProvider -> provider.getChannelPoster(id)
                        is com.streamflixreborn.streamflix.providers.LiveTvHubPlusProvider -> provider.getChannelPoster(id)
                        is com.streamflixreborn.streamflix.providers.LiveTvHubProvider -> provider.getChannelPoster(id)
                        is com.streamflixreborn.streamflix.providers.VavooProvider -> provider.getChannelPoster(id)
                        is com.streamflixreborn.streamflix.providers.MyIptvProvider -> provider.getChannelPoster(id)
                        else -> null
                    }
                    Triple(id, name, logo)
                }
            }

            if (!isAdded || _binding == null) return@launch
            titleView.text = "Chaînes (${items.size})"
            val mutableItems = items.toMutableList()
            val adapter = IptvChannelListMobileAdapter(mutableItems, args.id) { selectedId ->
                panel.visibility = View.GONE
                switchToIptvChannel(selectedId, provider)
            }
            rv.adapter = adapter
            val currentIdx = items.indexOfFirst { it.first == args.id }
            if (currentIdx >= 0) rv.scrollToPosition(currentIdx)

            searchInput.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val q = s?.toString()?.trim()?.lowercase().orEmpty()
                    val filtered = if (q.isBlank()) items
                        else items.filter { it.second.lowercase().contains(q) }
                    adapter.replaceItems(filtered)
                    titleView.text = if (q.isBlank()) "Chaînes (${filtered.size})"
                        else "Chaînes (${filtered.size}/${items.size})"
                    if (filtered.isNotEmpty()) rv.scrollToPosition(0)
                }
            })
        }
    }

    private fun switchToIptvChannel(channelId: String, provider: com.streamflixreborn.streamflix.providers.Provider) {
        val channelName = when (provider) {
            is com.streamflixreborn.streamflix.providers.OlaTvProvider -> provider.getChannelDisplayName(channelId)
            is com.streamflixreborn.streamflix.providers.VegetaTvProvider -> provider.getChannelDisplayName(channelId)
            is com.streamflixreborn.streamflix.providers.LiveTvHubPlusProvider -> provider.getChannelDisplayName(channelId)
            is com.streamflixreborn.streamflix.providers.LiveTvHubProvider -> provider.getChannelDisplayName(channelId)
            is com.streamflixreborn.streamflix.providers.VavooProvider -> provider.getChannelDisplayName(channelId)
            is com.streamflixreborn.streamflix.providers.MyIptvProvider -> provider.getChannelDisplayName(channelId)
            else -> null
        } ?: channelId
        val channelPoster = when (provider) {
            is com.streamflixreborn.streamflix.providers.OlaTvProvider -> provider.getChannelPoster(channelId)
            is com.streamflixreborn.streamflix.providers.VegetaTvProvider -> provider.getChannelPoster(channelId)
            is com.streamflixreborn.streamflix.providers.LiveTvHubPlusProvider -> provider.getChannelPoster(channelId)
            is com.streamflixreborn.streamflix.providers.LiveTvHubProvider -> provider.getChannelPoster(channelId)
            is com.streamflixreborn.streamflix.providers.VavooProvider -> provider.getChannelPoster(channelId)
            is com.streamflixreborn.streamflix.providers.MyIptvProvider -> provider.getChannelPoster(channelId)
            else -> null
        }

        val videoType = Video.Type.Episode(
            id = channelId,
            number = 1,
            title = channelName,
            poster = channelPoster,
            overview = null,
            tvShow = Video.Type.Episode.TvShow(
                id = channelId,
                title = channelName,
                poster = channelPoster,
                banner = null,
                releaseDate = null,
                imdbId = null,
            ),
            season = Video.Type.Episode.Season(
                number = 1,
                title = "Live",
            ),
        )
        val navArgs = android.os.Bundle().apply {
            putString("id", channelId)
            putString("title", channelName)
            putString("subtitle", channelName)
            putSerializable("videoType", videoType)
        }
        findNavController().navigate(
            R.id.player,
            navArgs,
            androidx.navigation.NavOptions.Builder()
                .setPopUpTo(R.id.player, true)
                .build(),
        )
    }

    /** Adapter simple pour la BottomSheet liste chaînes IPTV mobile.
     *  2026-06-13 : items en MutableList pour pouvoir filtrer dynamiquement. */
    private class IptvChannelListMobileAdapter(
        private val items: MutableList<Triple<String, String, String?>>,
        private val currentId: String,
        private val onClick: (String) -> Unit,
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<IptvChannelListMobileAdapter.VH>() {
        class VH(val root: android.widget.LinearLayout) : androidx.recyclerview.widget.RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val row = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(40, 24, 40, 24)
                gravity = android.view.Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                background = android.graphics.drawable.ColorDrawable(0x00000000)
            }
            val logo = android.widget.ImageView(ctx).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(72, 72)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            }
            row.addView(logo)
            val name = android.widget.TextView(ctx).apply {
                textSize = 16f
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(32, 0, 0, 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            row.addView(name, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            return VH(row)
        }

        override fun getItemCount() = items.size

        /** 2026-06-13 : filtre par la recherche EditText. */
        @SuppressWarnings("NotifyDataSetChanged")
        fun replaceItems(newItems: List<Triple<String, String, String?>>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (id, displayName, logoUrl) = items[position]
            val logo = holder.root.getChildAt(0) as android.widget.ImageView
            val name = holder.root.getChildAt(1) as android.widget.TextView
            name.text = displayName
            val isCurrent = id == currentId
            holder.root.setBackgroundColor(if (isCurrent) 0x33FFFFFF else 0x00000000)
            name.setTypeface(name.typeface,
                if (isCurrent) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            if (!logoUrl.isNullOrBlank()) {
                com.bumptech.glide.Glide.with(logo).load(logoUrl).into(logo)
            } else {
                logo.setImageDrawable(com.streamflixreborn.streamflix.utils
                    .ChannelPlaceholderDrawable(displayName, bgAlpha = 100))
            }
            holder.root.setOnClickListener { onClick(id) }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Mobile port v52 + v54 + v58 : anti-cut live IPTV
    //  (cross-fade audio + freeze frame + backup post-swap)
    //  Aligné avec PlayerTvFragment.
    // ═══════════════════════════════════════════════════════════════

    private fun buildLiveBackupItem(player: androidx.media3.exoplayer.ExoPlayer): MediaItem? {
        return try {
            val curUri = player.currentMediaItem?.localConfiguration?.uri ?: return null
            val curMime = player.currentMediaItem?.localConfiguration?.mimeType
            MediaItem.Builder()
                .setUri(curUri)
                .apply { curMime?.let { setMimeType(it) } }
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(55_000)
                        .setMaxOffsetMs(120_000)
                        .setMinOffsetMs(30_000)
                        .setMinPlaybackSpeed(0.95f)
                        .setMaxPlaybackSpeed(1.0f)
                        .build()
                )
                .build()
        } catch (_: Exception) { null }
    }

    // Mobile utilise TextureView (vs SurfaceView TV). TextureView.getBitmap()
    //   est plus simple et synchrone (pas besoin de PixelCopy async).
    private fun captureLastFrameToOverlay() {
        try {
            if (_binding == null) return
            val pv = binding.pvPlayer
            val overlay = try { binding.root.findViewById<android.widget.ImageView>(R.id.swap_freeze_overlay) } catch (_: Exception) { null } ?: return
            val textureView = findTextureView(pv) ?: return
            val bmp = textureView.bitmap ?: return
            overlay.setImageBitmap(bmp)
            overlay.visibility = View.VISIBLE
            overlay.alpha = 1f
        } catch (e: Exception) {
            android.util.Log.w("PlayerMobileFragment", "captureLastFrameToOverlay: ${e.message}")
        }
    }

    private fun findTextureView(root: View): android.view.TextureView? {
        if (root is android.view.TextureView) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findTextureView(root.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun hideFreezeOverlay() {
        try {
            if (_binding == null) return
            val overlay = binding.root.findViewById<android.widget.ImageView>(R.id.swap_freeze_overlay) ?: return
            overlay.visibility = View.GONE
            overlay.setImageBitmap(null)
        } catch (_: Exception) {}
    }

    private suspend fun swapToNextWithAudioFade(player: androidx.media3.exoplayer.ExoPlayer) {
        val savedVolume = try { player.volume } catch (_: Exception) { 1f }
        try {
            withContext(Dispatchers.Main) { captureLastFrameToOverlay() }
            for (i in 9 downTo 0) {
                player.volume = savedVolume * (i / 10f)
                kotlinx.coroutines.delay(15L)
            }
            player.seekToNextMediaItem()
            // 2026-05-20 (user "retours sur écran avec son et image qui se
            //   répètent") : après swap, le backup est à une position PLUS
            //   ANCIENNE → replay de contenu déjà vu. seekToDefaultPosition
            //   force le saut au live edge (targetOffset derrière).
            player.seekToDefaultPosition()
            android.util.Log.d("PlayerMobileFragment", "seekToDefaultPosition après swap (anti-replay)")
            try {
                val nextBackup = buildLiveBackupItem(player)
                if (nextBackup != null) {
                    player.addMediaItem(nextBackup)
                    android.util.Log.d("PlayerMobileFragment", "v58: backup re-ajouté post-swap")
                }
            } catch (e: Exception) {
                android.util.Log.w("PlayerMobileFragment", "v58 re-add backup failed: ${e.message}")
            }
            kotlinx.coroutines.delay(80L)
            for (i in 1..12) {
                player.volume = savedVolume * (i / 12f)
                kotlinx.coroutines.delay(20L)
            }
            player.volume = savedVolume
            val deadline = System.currentTimeMillis() + 2_000L
            while (System.currentTimeMillis() < deadline) {
                try {
                    if (player.playbackState == androidx.media3.common.Player.STATE_READY &&
                        player.isPlaying) break
                } catch (_: Exception) {}
                kotlinx.coroutines.delay(40L)
            }
            try {
                val overlay = binding.root.findViewById<android.widget.ImageView>(R.id.swap_freeze_overlay)
                if (overlay != null && overlay.visibility == View.VISIBLE) {
                    for (a in 10 downTo 0) {
                        overlay.alpha = a / 10f
                        kotlinx.coroutines.delay(20L)
                    }
                }
            } catch (_: Exception) {}
            hideFreezeOverlay()
        } catch (e: Exception) {
            try { player.volume = savedVolume } catch (_: Exception) {}
            hideFreezeOverlay()
            android.util.Log.w("PlayerMobileFragment", "swapToNextWithAudioFade: ${e.message}")
        }
    }

    private fun showLoadingOverlay() {
        // 2026-05-08 : skip sur IPTV — l'overlay plein écran bloque l'accès
        // au menu Serveurs/Settings, et le buffer ExoPlayer gère déjà la
        // sensation de chargement.
        if (isIptvChannelContext()) return
        if (_binding == null) return
        val overlay = binding.loadingOverlay
        if (overlay.isVisible) return  // déjà affiché, ne pas reset l'animation

        // Annule un éventuel show précédent en attente.
        loadingShowRunnable?.let { loadingShowHandler.removeCallbacks(it) }

        val runnable = Runnable {
            if (_binding == null) return@Runnable
            val ov = binding.loadingOverlay
            val bar = binding.loadingOverlayBar
            ov.visibility = View.VISIBLE
            bar.progress = 0
            loadingBarAnimator?.cancel()
            // Animate from 0 to 95 over 5s. On stagne à 95% pour ne pas mentir
            // (l'attente réelle peut dépasser 5s, surtout quand un extracteur
            // foire et qu'on en essaye un autre). Le hideLoadingOverlay() snap
            // à 100% quand le chargement vrai est terminé.
            loadingBarAnimator = android.animation.ObjectAnimator
                .ofInt(bar, "progress", 0, 95)
                .apply {
                    duration = 5_000L
                    interpolator = android.view.animation.DecelerateInterpolator()
                    start()
                }
            // 2026-05-16 : pendant le chargement, on affiche aussi le picker
            // de serveurs DIRECTEMENT (pas le menu Main) — comme ça l'user
            // voit l'état (chargement du serveur courant) ET peut cliquer un
            // autre serveur sans étape intermédiaire.
            runCatching { binding.settings.showServers() }
        }
        loadingShowRunnable = runnable
        loadingShowHandler.postDelayed(runnable, LOADING_OVERLAY_SHOW_DELAY_MS)
    }

    /** Cache l'overlay, annule le delay et stoppe l'animation. Appelé sur
     *  SuccessLoadingVideo. Si l'overlay n'a jamais été affiché (chargement
     *  plus rapide que LOADING_OVERLAY_SHOW_DELAY_MS), c'est un no-op visuel. */
    private fun hideLoadingOverlay() {
        if (_binding == null) return
        // Annule le show en attente si pas encore exécuté.
        loadingShowRunnable?.let { loadingShowHandler.removeCallbacks(it) }
        loadingShowRunnable = null
        loadingBarAnimator?.cancel()
        loadingBarAnimator = null
        if (binding.loadingOverlay.isVisible) {
            binding.loadingOverlayBar.progress = 100
            binding.loadingOverlay.visibility = View.GONE
        }
        // 2026-05-16 : ferme aussi le picker serveurs auto-ouvert pendant
        // le chargement — l'épisode a démarré, on revient au player propre.
        runCatching {
            PlayerSettingsView.Settings.Server.list.forEach { it.isLoading = false }
            binding.settings.refreshServerList()
            binding.settings.hide()
        }
    }

    /** Mark a server as tried and remove it from the Chaîne page so broken variants
     *  don't pollute the visible list. They'll re-emit at next session if Phase 3
     *  finds them again and they happen to work that time. Also reports the resolved
     *  upstream URL to OlaTvProvider so other variants pointing to the same dead URL
     *  fail fast. */
    /**
     * 2026-05-25 : prochain serveur non-DEAD per-titre après [current].
     * Remplace les `servers.getOrNull(indexOf+1)` bruts qui ré-essayaient
     * les serveurs rouges en boucle.
     */
    private fun nextNonDeadServer(current: Video.Server?): Video.Server? {
        if (current == null) return null
        val idx = servers.indexOfFirst { it.id == current.id }
        if (idx < 0) return null
        return servers.drop(idx + 1).firstOrNull {
            com.streamflixreborn.streamflix.utils.TitleServerStatus.statusOf(it.id) !=
                com.streamflixreborn.streamflix.utils.ExtractorRanker.ServerStatus.DEAD
        }
    }

    private fun pruneBrokenVariant(server: Video.Server?) {
        if (server == null) return
        triedChannelVariantIds.add(server.id)
        // Report the upstream URL we were just playing — multiple variants may resolve
        // to the same dead URL (different cmd, same upstream).
        val playingUri = player.currentMediaItem?.localConfiguration?.uri?.toString()
        if (!playingUri.isNullOrBlank()) {
            // 2026-06-15 (user "OlaTvProvider Domain blacklisted 185.160 alors qu'on
            //   est sur TF1 World Live TV") : même bug que celui fixé dans
            //   MiniPlayerController. OlaTvProvider blacklist le HOST entier
            //   inconditionnellement → quand on est sur un autre provider (World
            //   Live TV, prime-tv 185.160), les fail sur 185.160 cassent toutes
            //   les chaînes du host. Gate par préfixe d'ID.
            val isOlaChannel = args.id.startsWith("ola::") ||
                args.id.startsWith("ola_ep::") ||
                args.id.startsWith("ola_stream::") ||
                args.id.startsWith("ola_fasttrack::")
            if (isOlaChannel) {
                try {
                    com.streamflixreborn.streamflix.providers.OlaTvProvider.reportBrokenStreamUrl(playingUri)
                } catch (_: Throwable) { /* WiTv lacks this method, ignore */ }
            }
        }
        val variants = PlayerSettingsView.Settings.ChannelVariant.list
        val removed = variants.removeAll { it.id == server.id }
        if (removed && _binding != null) {
            Log.d("PlayerMobileFragment", "Pruned broken variant: ${server.name}")
            binding.settings.refreshChannelVariantList()
        }
    }

    private fun tryNextChannelVariant(failedServer: Video.Server?): Boolean {
        val variants = PlayerSettingsView.Settings.ChannelVariant.list
        if (variants.isEmpty()) return false

        // Mark the failed server's variant as tried AND remove it from the visible list
        // so the user doesn't see broken entries piling up. Re-add happens automatically
        // on the next session (Phase 3 will re-emit working variants).
        if (failedServer != null) {
            triedChannelVariantIds.add(failedServer.id)
            val removed = variants.removeAll { it.id == failedServer.id }
            if (removed && _binding != null) {
                Log.d("PlayerMobileFragment", "Removed broken variant from Chaîne page: ${failedServer.name}")
                binding.settings.refreshChannelVariantList()
            }
        }

        // Find the first untried variant
        val nextVariant = variants.firstOrNull { it.id !in triedChannelVariantIds }
        if (nextVariant != null) {
            triedChannelVariantIds.add(nextVariant.id)
            Log.d("PlayerMobileFragment", "Fallback → trying channel variant: ${nextVariant.name}")
            viewModel.getVideo(Video.Server(nextVariant.id, nextVariant.name))
            return true
        }

        Log.d("PlayerMobileFragment", "No more channel variants to try (tried ${triedChannelVariantIds.size})")
        return false
    }

    /**
     * Download from a specific server: resolve the video URL, then enqueue it.
     * Blocks WebView-only sources (LuluVdo, Netu/cfglobalcdn) that need a
     * browser network stack and would 403 via direct OkHttp download.
     */
    private fun downloadFromServer(server: Video.Server) {
        // Ensure notification permission is granted (Android 13+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val perm = android.Manifest.permission.POST_NOTIFICATIONS
            if (requireContext().checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(perm), 1001)
            }
        }

        // Live IPTV: tap into the player's DataSource to record while playing
        // (avoids segment-auth issues from separate OkHttp fetches).
        val isLiveIptv = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
            args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
            args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
            args.id.startsWith("livehub::") || args.id.startsWith("sportlive::") ||
            args.id.startsWith("match::") || args.id.startsWith("vavoo::") || args.id.startsWith("myiptv-live::")
        if (isLiveIptv) {
            handleLiveRecord(server)
            return
        }

        val providerName = UserPreferences.currentProvider?.name ?: "unknown"
        val videoType = currentVideoTypeForUi()

        Toast.makeText(requireContext(), "Résolution du lien…", Toast.LENGTH_SHORT).show()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val provider = UserPreferences.currentProvider ?: return@launch
                val video = provider.getVideo(server)
                if (video.source.isEmpty()) throw Exception("No source found")

                // ── Block WebView-only sources ──
                if (isVideoWebViewOnly(video)) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            requireContext(),
                            "Ce serveur nécessite un navigateur, téléchargement impossible (${server.name})",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }

                Log.d("PlayerMobileFragment", "Enqueuing download: server=${server.name} source=${video.source.take(100)} headers=${video.headers?.keys}")

                com.streamflixreborn.streamflix.download.DownloadManager.enqueue(
                    video = video,
                    videoType = videoType,
                    providerName = providerName,
                    serverName = server.name,
                )
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(),
                        "Téléchargement ajouté : ${server.name}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.e("PlayerMobileFragment", "Download failed for server ${server.name}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(),
                        "Erreur : ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /**
     * Returns true if this video requires a WebView network stack and cannot
     * be downloaded directly with OkHttp.
     * Covers: LuluVdo/LuluStream CDN (TLS fingerprint), Netu/cfglobalcdn (ISP block),
     * and any source that sets needsWebViewClick.
     */
    private fun isVideoWebViewOnly(video: Video): Boolean {
        // Needs user interaction in WebView (anti-bot click)
        if (video.needsWebViewClick && !video.webViewUrl.isNullOrBlank()) return true

        // LuluVdo CDN — 403s without Chromium TLS fingerprint
        if (isLuluVdoCdn(video)) return true

        // data: URI sources can't be downloaded directly (base64-encoded m3u8 with
        // segments behind WebView-protected CDN)
        if (video.source.startsWith("data:")) return true

        // cfglobalcdn — ISP blocks, needs WebView Chromium stack
        if (video.source.contains("cfglobalcdn.com", ignoreCase = true)) return true

        return false
    }

    /** Show the inline REC button on the player controls. Visible on ANY video
     *  (live IPTV + VOD): the user can capture a portion of any stream by
     *  toggling start/stop. The icon goes RED when recording so the state is
     *  visible at a glance. Called whenever the playing video changes. */
    private fun updateLiveRecordButton() {
        val btn = binding.pvPlayer.findViewById<android.widget.ImageView>(R.id.btn_live_record)
            ?: return
        btn.visibility = View.VISIBLE
        val recording = com.streamflixreborn.streamflix.download.LiveRecorder.isRecording
        val tint = if (recording) {
            android.graphics.Color.parseColor("#FF3B30")
        } else {
            android.graphics.Color.WHITE
        }
        androidx.core.widget.ImageViewCompat.setImageTintList(
            btn, android.content.res.ColorStateList.valueOf(tint)
        )
        btn.setOnClickListener {
            val server = currentServer ?: return@setOnClickListener
            handleLiveRecord(server)
            // Refresh icon tint after toggling.
            updateLiveRecordButton()
        }
    }

    /** Live IPTV recording: tap into ExoPlayer's DataSource (no separate auth-failing
     *  fetch). Toggle: 1st click starts recording, 2nd click stops. The user MUST
     *  keep the channel playing — closing the player ends the recording. */
    private fun handleLiveRecord(server: Video.Server) {
        if (com.streamflixreborn.streamflix.download.LiveRecorder.isRecording) {
            // Stop and finalize
            val channel = currentChannelKey.ifBlank { server.name }
            val savedPath = com.streamflixreborn.streamflix.download.LiveRecorder.stopRecording(requireContext().applicationContext)
            if (savedPath != null) {
                Toast.makeText(requireContext(),
                    "✅ Enregistrement sauvegardé dans Movies/StreamFlix",
                    Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(requireContext(),
                    "Aucun segment capturé — restez sur la chaîne pendant l'enregistrement",
                    Toast.LENGTH_LONG).show()
            }
            return
        }
        // Start recording
        val channelDisplayName = when (val type = args.videoType) {
            is Video.Type.Episode -> type.tvShow.title
            is Video.Type.Movie -> type.title
        }.ifBlank { server.name }
        val outFile = com.streamflixreborn.streamflix.download.LiveRecorder.startRecording(
            context = requireContext().applicationContext,
            channelDisplayName = channelDisplayName,
        )
        if (outFile == null) {
            Toast.makeText(requireContext(),
                "Un enregistrement est déjà en cours",
                Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(requireContext(),
            "🔴 Enregistrement démarré — RESTEZ sur la chaîne. Re-tapez le bouton pour arrêter.",
            Toast.LENGTH_LONG).show()
        // Force the buffer to drain to live edge so new segments are fetched and
        // captured right away (with our big 30s/120s buffer, otherwise we could
        // wait minutes before any new fetch happens).
        try {
            if (::player.isInitialized) {
                player.seekToDefaultPosition()
            }
        } catch (_: Exception) {}
    }

    private fun refreshEpisodeNavigation(type: Video.Type.Episode) {
        lifecycleScope.launch(Dispatchers.IO) {
            EpisodeManager.ensureNextEpisodeAvailable(type, database)
            withContext(Dispatchers.Main) {
                setupEpisodeNavigationButtons()
            }
        }
    }

    // 2026-06-13 (user "Cloudstream Silo S1 — l'épisode next en automatique
    //   a tendance à sauter des épisodes") : EpisodeManager.getNextEpisode()
    //   a un effet de bord (incrémente currentIndex à chaque appel). Quand
    //   l'épisode finit, STATE_ENDED + onIsPlayingChanged peuvent déclencher
    //   2 appels à playNextEpisodeAcrossSeasons rapprochés → 2 incréments
    //   → 1 épisode sauté. On garde l'ID de l'épisode pour lequel l'autoplay
    //   a déjà été consommé, et on ignore tout appel redondant.
    private var autoplayConsumedForEpisodeId: String? = null

    private fun playNextEpisodeAcrossSeasons(autoplay: Boolean = false) {
        val type = args.videoType as? Video.Type.Episode ?: return

        Log.w("AutoplayDiag", "→ playNextEpisodeAcrossSeasons(autoplay=$autoplay) ep=${type.id} consumedForId=$autoplayConsumedForEpisodeId")

        // Anti double-call : SYNCHRONE, AVANT le launch{} (sinon les 2 callers
        //   parallèles voient encore null et entrent tous les 2 dans le scope
        //   → 2 incréments de currentIndex → saut d'un épisode).
        if (autoplay && autoplayConsumedForEpisodeId == type.id) {
            Log.w("AutoplayDiag", "✕ BLOCKED double-autoplay sur ${type.id}")
            return
        }
        if (autoplay) autoplayConsumedForEpisodeId = type.id

        lifecycleScope.launch {
            val hasNextEpisode = withContext(Dispatchers.IO) {
                EpisodeManager.ensureNextEpisodeAvailable(type, database)
            }

            setupEpisodeNavigationButtons()

            if (!hasNextEpisode) {
                Log.w("AutoplayDiag", "  ↳ pas d'épisode suivant")
                return@launch
            }
            if (autoplay && !UserPreferences.autoplay) {
                Log.w("AutoplayDiag", "  ↳ autoplay pref off")
                return@launch
            }

            Log.w("AutoplayDiag", "  ↳ viewModel.playNextEpisode() — currentIndex avant=${com.streamflixreborn.streamflix.utils.EpisodeManager.currentIndex}")
            viewModel.playNextEpisode()
            Log.w("AutoplayDiag", "  ↳ APRÈS playNextEpisode — currentIndex=${com.streamflixreborn.streamflix.utils.EpisodeManager.currentIndex}")
        }
    }

    /**
     * 2026-06-06 (user) : « si aucun épisode n'est fonctionnel pour les
     * providers animés, passe automatiquement à l'épisode suivant — sinon
     * l'app retourne en plein écran et il ne se passe plus rien ». Dernier
     * recours UX pour la lecture en kids/anime, déclenché UNIQUEMENT quand :
     *  - on regarde un épisode (pas un film),
     *  - le provider courant est dans ProviderGroup.ANIME,
     *  - le compteur AnimeAutoSkipState autorise encore un saut
     *    (plafond = 5 sauts consécutifs, anti-boucle infinie si toute la
     *    saison est cassée).
     *
     * Renvoie `true` si l'auto-skip a été déclenché → le caller doit alors
     * NE PAS appeler navigateUp(). Renvoie `false` sinon → caller garde son
     * comportement standard (Toast + navigateUp).
     */
    private fun tryAutoSkipBrokenAnimeEpisode(): Boolean {
        if (args.videoType !is Video.Type.Episode) return false
        val provider = UserPreferences.currentProvider ?: return false
        if (com.streamflixreborn.streamflix.providers.Provider.getGroup(provider)
            != com.streamflixreborn.streamflix.providers.Provider.Companion.ProviderGroup.ANIME) {
            return false
        }
        // 2026-06-13 (user "l'épisode next en automatique a tendance à
        //   sauter les épisodes") : respecte le toggle UserPreferences.
        //   Par défaut désactivé → ne saute plus en cascade.
        if (!UserPreferences.animeAutoSkipBroken) {
            Log.d("PlayerMobileFragment", "Anime auto-skip désactivé par l'user — pas de saut")
            return false
        }
        if (!com.streamflixreborn.streamflix.utils.AnimeAutoSkipState.tryConsumeSkip()) {
            Log.w("PlayerMobileFragment", "Anime auto-skip plafond atteint (5 sauts) — arrêt")
            return false
        }
        Toast.makeText(
            requireContext(),
            "Épisode indisponible — passage au suivant",
            Toast.LENGTH_SHORT
        ).show()
        playNextEpisodeAcrossSeasons(autoplay = false)
        return true
    }

    private fun decodeBase64Uri(uri: String): String? {
        return try {
            val parts = uri.split(",")
            if (parts.size == 2 && parts[0].contains(";base64")) {
                val base64Data = parts[1]
                val decodedBytes = Base64.getDecoder().decode(base64Data)
                String(decodedBytes, Charsets.UTF_8)
            } else {
                null
            }
        } catch (ignored: Exception) {
            null
        }
    }

    private fun extractUrlFromPlaylist(playlist: String): String? {
        return try {
            val lines = playlist.lines().map { it.trim() }
            lines.firstOrNull { it.startsWith("http") }
                ?: lines.firstNotNullOfOrNull { line ->
                    val regex = """URI=["'](http[^"']+)["']""".toRegex()
                    regex.find(line)?.groupValues?.get(1)
                }
        } catch (ignored: Exception) {
            null
        }
    }


    /**
     * Returns true if this video source is a LuluVdo/LuluStream CDN URL
     * that will always 403 with any non-WebView HTTP client (including Cronet).
     */
    private fun isLuluVdoCdn(video: Video): Boolean {
        if (video.webViewUrl.isNullOrBlank()) return false
        val src = video.source.lowercase()
        val wvUrl = video.webViewUrl!!.lowercase()
        return src.contains("tnmr.org") || src.contains("luluvdo") || src.contains("lulustream")
                || src.contains("luluvid") || src.contains("luluvdoo") || src.contains("lulucdn")
                || wvUrl.contains("luluvdo") || wvUrl.contains("lulustream")
                || wvUrl.contains("luluvid") || wvUrl.contains("luluvdoo")
    }

    /** Netu cfglobalcdn — ISP blocks the IP, WebView bypasses it */
    private fun isNetuCfglobalcdn(video: Video): Boolean {
        return video.webViewUrl != null &&
               com.streamflixreborn.streamflix.extractors.NetuExtractor.sharedWebView != null &&
               (video.source.startsWith("data:") || video.source.contains("cfglobalcdn.com"))
    }

    /**
     * Sync cookies from Android's WebView CookieManager to Java's default CookieHandler.
     * This allows DefaultHttpDataSource (which uses HttpURLConnection) to send WebView cookies.
     */
    private fun syncWebViewCookies(sourceUrl: String) {
        try {
            val uri = java.net.URI(sourceUrl)
            val host = uri.host ?: return

            // Ensure Java's CookieHandler is set up
            if (java.net.CookieHandler.getDefault() == null) {
                java.net.CookieHandler.setDefault(java.net.CookieManager())
            }
            val cookieHandler = java.net.CookieHandler.getDefault() as? java.net.CookieManager ?: return

            // Get cookies from WebView's CookieManager for this domain
            val webViewCookies = android.webkit.CookieManager.getInstance().getCookie("https://$host")
            if (webViewCookies.isNullOrBlank()) {
                Log.d("PlayerNetwork", "No WebView cookies for $host")
                return
            }

            // Parse and add each cookie to Java's CookieManager
            webViewCookies.split(";").forEach { cookie ->
                val trimmed = cookie.trim()
                if (trimmed.isNotBlank()) {
                    try {
                        val httpCookie = java.net.HttpCookie.parse("Set-Cookie: $trimmed")
                        httpCookie.forEach { c ->
                            c.domain = host
                            c.path = "/"
                            cookieHandler.cookieStore.add(uri, c)
                        }
                    } catch (_: Exception) {}
                }
            }
            Log.d("PlayerNetwork", "Synced WebView cookies to CookieHandler for $host: ${webViewCookies.take(100)}")
        } catch (e: Exception) {
            Log.w("PlayerNetwork", "Cookie sync failed: ${e.message}")
        }
    }

    private fun displayVideo(video: Video, server: Video.Server) {
        currentVideo = video

        // 2026-07-11 : si le mode « toujours lecteur externe » est actif,
        //   on lance directement le lecteur externe sans passer par ExoPlayer.
        if (UserPreferences.alwaysUseExternalPlayer && !didAutoLaunchExternal) {
            didAutoLaunchExternal = true // 1 seule fois par session
            Log.d("PlayerMobileFragment", "alwaysUseExternalPlayer=true → launch external player")
            val pkg = UserPreferences.externalPlayerPackage
            doExternalLaunch(video, directPackage = pkg)
            return
        }

        // 2026-06-03 (user "ça déclenche pas la Chromecast") : pre-set la pending
        //   video dans CastHelper dès qu'on commence à jouer une vidéo. Si l'user
        //   clique le bouton Cast et sélectionne la Chromecast → le listener
        //   enverra automatiquement cette URL. Sans ce hook : Cast sélectionné =
        //   session ouverte mais aucune vidéo envoyée.
        try {
            val title = args.title ?: server.name
            val poster: String? = null  // args.poster n'existe pas, on laisse vide
            val contentType = when {
                video.source.contains(".m3u8", true) -> "application/x-mpegURL"
                video.source.contains(".mpd", true) -> "application/dash+xml"
                video.source.contains(".mp4", true) -> "video/mp4"
                else -> "application/x-mpegURL"
            }
            com.streamflixreborn.streamflix.utils.CastHelper.castVideo(
                requireContext().applicationContext,
                video.source,
                title,
                poster,
                contentType,
            )
        } catch (e: Throwable) {
            Log.w("PlayerMobile", "CastHelper pre-set failed: ${e.message}")
        }
        // 2026-05-21 : statut serveurs PAR TITRE (couleurs picker liées à ce contenu).
        com.streamflixreborn.streamflix.utils.TitleServerStatus.setCurrentTitle(args.id)
        // Reset IPTV stickiness when switching to a different server (manual or auto).
        if (currentServer?.id != server.id) {
            iptvRetryCount = 0
            iptvCurrentStreamHasWorked = false
            vodCurrentStreamHasWorked = false
            vodStickyRetryCount = 0
            // 2026-05-21 : nouveau serveur → on repart en pleine qualité.
            adaptiveQualityGovernor?.reset()
        }
        currentServer = server
        updatePlayerHeader()
        updateLiveRecordButton()

        // Clean up any existing WebView overlay (e.g. switching servers). 2026-07-09 : on ferme
        //   TOUJOURS l'ancien overlay avant d'afficher la nouvelle source (même webview→webview),
        //   sinon showWebViewOverlay sort tôt (webViewOverlay != null) et le nouveau serveur reste
        //   bloqué. Le picker serveurs (bottom-sheet) s'ouvre PAR-DESSUS sans passer par ici.
        if (webViewOverlay != null) {
            hideWebViewOverlay()
        }

        Log.d("PlayerDebug", "displayVideo: server=${server.name}, source=${video.source.take(100)}, type=${video.type}, headers=${video.headers}")

        // ── Netu anti-bot: show WebView overlay so user can tap the play button ──
        if (video.needsWebViewClick && !video.webViewUrl.isNullOrBlank()) {
            // Clean up any existing overlay first (prevents second player stacking)
            if (webViewOverlay != null) {
                Log.d("PlayerMobile", "Cleaning previous overlay before new one: ${video.webViewUrl?.take(60)}")
                hideWebViewOverlay()
            }
            // Clean up DaddyLive proxy if any
            daddyLiveProxyWebView?.let { old ->
                try { old.stopLoading(); old.destroy() } catch (_: Exception) {}
                (binding.root as? ViewGroup)?.removeView(old)
            }
            daddyLiveProxyWebView = null

            Log.d("PlayerMobile", "needsWebViewClick → showing WebView overlay for: ${video.webViewUrl}")
            // Stop current playback before showing overlay
            if (::player.isInitialized) {
                player.stop()
                player.clearMediaItems()
            }
            pendingWebViewVideo = video
            pendingWebViewServer = server
            showWebViewOverlay(video.webViewUrl!!)
            return
        }

        // WebView bypass: LuluVdo (TLS fingerprint) or Netu (ISP IP block)
        // If we have a shared WebView from extraction, use WebViewDataSource
        // to route ExoPlayer's HTTP requests through the WebView's network stack.
        val needsWebViewDs = (isLuluVdoCdn(video)
            && video.source.startsWith("data:")
            && com.streamflixreborn.streamflix.extractors.LuluVdoExtractor.sharedWebView != null)
            || isNetuCfglobalcdn(video)
        if (isLuluVdoCdn(video) || isNetuCfglobalcdn(video)) {
            Log.d("PlayerNetwork", "WebView bypass: source is ${if (video.source.startsWith("data:")) "data URI" else "CDN URL"}, webViewDs=$needsWebViewDs, lulu=${isLuluVdoCdn(video)}, netu=${isNetuCfglobalcdn(video)}")
        }

        val extraBuffering = PlayerSettingsView.Settings.ExtraBuffering.isEnabled
        val softwareDecoder = PlayerSettingsView.Settings.SoftwareDecoder.isEnabled

        // Switch DataSource if the video URL needs a different engine
        val urlNeedsCronet = needsCronet(video.source)
        val urlNeedsDoH = needsDoH(video.source)
        val urlNeedsBrowserOkHttp = needsBrowserOkHttp(video.source)
        val dataSourceMismatch = (urlNeedsCronet && !usingCronet) || (!urlNeedsCronet && usingCronet)
            || (urlNeedsDoH && !usingDoH) || (!urlNeedsDoH && usingDoH)
            || (urlNeedsBrowserOkHttp && !usingBrowserOkHttp) || (!urlNeedsBrowserOkHttp && usingBrowserOkHttp)
        val needsReinit =
            extraBuffering != currentExtraBuffering || softwareDecoder != currentSoftwareDecoder || dataSourceMismatch

        if (dataSourceMismatch) {
            Log.d("PlayerNetwork", "DataSource mismatch: needsCronet=$urlNeedsCronet(was=$usingCronet), needsDoH=$urlNeedsDoH(was=$usingDoH), needsBrowserOkHttp=$urlNeedsBrowserOkHttp(was=$usingBrowserOkHttp) → switching")
            httpDataSource = createHttpDataSourceFactory(video.source)
            Log.d("PlayerNetwork", "After factory creation: httpDataSource=${httpDataSource.javaClass.simpleName}, usingCronet=$usingCronet, usingDoH=$usingDoH, usingBrowserOkHttp=$usingBrowserOkHttp")
        }

        if (needsReinit) {
            initializePlayer(extraBuffering, softwareDecoder, video.source)
            Log.d("PlayerNetwork", "After initializePlayer: httpDataSource=${httpDataSource.javaClass.simpleName}, usingCronet=$usingCronet")
            player.playlistMetadata = MediaMetadata.Builder()
                .setTitle(resolvePlayerTitle())
                .setMediaServers(servers.map {
                    MediaServer(
                        id = it.id,
                        name = it.name,
                    )
                })
                .build()
        }

        val currentPosition = player.currentPosition

        if (!needsWebViewDs) {
            // 2026-05-19 v85g (user "il faut que tu repare les deux extracteurs") :
            //   DefaultHttpDataSource IGNORE "User-Agent" dans setDefaultRequestProperties
            //   et utilise toujours celui du factory (setUserAgent()). Quand un
            //   extracteur fournit un UA specifique (Uqload exige Chrome Mobile),
            //   il fallait que cet UA passe — sinon serveur 403. On recree donc
            //   le factory avec le bon setUserAgent() si video.headers en contient un.
            val videoUa = video.headers?.get("User-Agent")
            val sourceHost = try { java.net.URL(video.source).host.lowercase() } catch (_: Throwable) { "" }
            // 2026-05-19 v85h : hosts qui font du JA3/TLS fingerprinting strict
            //   (DefaultHttpDataSource utilise HttpURLConnection avec un TLS
            //   fingerprint Java identifiable -> 403). Route ces hosts via
            // 2026-05-19 v85k : uqload + abyssa passent par Cronet (TLS Chrome
            //   bypass JA3) — voir needsCronet(). Pas besoin de branche OkHttp ici.
            if (!videoUa.isNullOrBlank() && videoUa != NetworkClient.USER_AGENT &&
                !(sourceHost.contains("uqload") || sourceHost.contains("abyssa") || sourceHost.contains("abysscdn")
                    || sourceHost.contains("citron-edge"))) {
                try {
                    val customFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                        .setUserAgent(videoUa)
                        .setConnectTimeoutMs(15_000)
                        .setReadTimeoutMs(30_000)
                        .setAllowCrossProtocolRedirects(true)
                    httpDataSource = com.streamflixreborn.streamflix.utils.LiveReconnectingHttpDataSource.Factory(customFactory)
                    Log.d("PlayerNetwork", "Custom UA from extractor: $videoUa -> factory rebuilt")
                    dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(requireContext(), httpDataSource)
                } catch (e: Exception) {
                    Log.w("PlayerNetwork", "Failed to rebuild factory with custom UA: ${e.message}")
                }
            }
            httpDataSource.setDefaultRequestProperties(
                mapOf(
                    "User-Agent" to (videoUa ?: NetworkClient.USER_AGENT),
                ) + (video.headers ?: emptyMap())
            )
        }

        // 2026-05-09 : pour IPTV live HLS, on configure un offset cible de 60s
        // derrière le live edge. Sans ça l'user dit que "le download va jusqu'au
        // bout de la barre, ce qui crée des coupures intempestives". Avec un
        // targetOffset 60s, le playback head reste stable au milieu du buffer.
        val isLiveIptvChannel = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
            args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
            args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
            args.id.startsWith("livehub::") || args.id.startsWith("sportlive::") ||
            args.id.startsWith("match::") || args.id.startsWith("vavoo::") || args.id.startsWith("myiptv-live::")
        // 2026-05-09 : roue de chargement masquée pour IPTV uniquement.
        // 2026-05-15 (user "vire cette foutue roulette pour Mon IPTV") :
        // étendu à TOUS les contenus IPTV (live, VOD, séries, Mon IPTV Stalker)
        // via isIptvChannelContext() — myiptv-stalkerep:: inclus. TiviMate-style :
        // pas de spinner overlay, juste le buffer ExoPlayer interne sans UI.
        // keepContentOnPlayerReset reset à false — sera mis à true uniquement
        // juste avant les reloads auto-recovery (sinon casse mini→fullscreen).
        try {
            binding.pvPlayer.setShowBuffering(
                if (isIptvChannelContext()) androidx.media3.ui.PlayerView.SHOW_BUFFERING_NEVER
                else androidx.media3.ui.PlayerView.SHOW_BUFFERING_WHEN_PLAYING
            )
            binding.pvPlayer.setKeepContentOnPlayerReset(false)
        } catch (_: Exception) {}
        Log.d("PlayerMobileFragment", "displayVideo: video.subtitles.size=${video.subtitles.size}, defaults=${video.subtitles.count { it.default }}, labels=${video.subtitles.joinToString { "${it.label}(def=${it.default})" }}")
        val noDefaultSub = video.subtitles.isNotEmpty() && video.subtitles.none { it.default }
        val mediaItemBuilder = MediaItem.Builder()
            .setUri(video.source.toUri())
            .setMimeType(video.type)
            .setSubtitleConfigurations(video.subtitles.mapIndexed { idx, subtitle ->
                val isDefault = subtitle.default || (noDefaultSub && idx == 0)
                MediaItem.SubtitleConfiguration.Builder(subtitle.file.toUri())
                    .setMimeType(subtitle.file.toSubtitleMimeType())
                    .setLabel(subtitle.label)
                    .apply {
                        val lower = subtitle.label.lowercase()
                        if (lower.contains("fr") || lower.contains("français") ||
                            lower.contains("francais") || lower.contains("vostfr") ||
                            lower.contains("vf")) {
                            setLanguage("fr")
                        }
                    }
                    .setSelectionFlags(if (isDefault) C.SELECTION_FLAG_DEFAULT else 0)
                    .build()
            })
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setMediaServerId(server.id)
                    .build()
            )
        val isOtfBypass = args.id.startsWith("livehub::otf::") ||
            (currentServer?.id?.startsWith("livehub::otf::") == true)
        if (isLiveIptvChannel && !isOtfBypass) {
            // 2026-05-20 (parité PlayerTvFragment) : aligné sur TV — cible 45s
            //   derrière live edge (cushion plus large), et surtout JAMAIS de
            //   speedup (max 1.0 au lieu de 1.03). Le 1.03× faisait rattraper le
            //   live edge → vidait le buffer → coupures. min 20s / max 120s.
            // 2026-06-04 : OTF bypass — OTF TV V3.2 n'a pas de LiveConfiguration
            //   custom, le flux est traité comme du contenu standard. Reproduire.
            mediaItemBuilder.setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(45_000L)
                    .setMinOffsetMs(20_000L)
                    .setMaxOffsetMs(120_000L)
                    .setMinPlaybackSpeed(0.95f)
                    .setMaxPlaybackSpeed(1.0f)
                    .build()
            )
        }
        // 2026-06-08 (user "soustitres bizarres jaune sur noir -HUGO ! HUGO !
        //   HUGO ! ça crée du bazar") : désactive les CEA-608/708 closed
        //   captions embarqués dans les flux HLS live (France TV pousse des CC
        //   pour malentendants par défaut). UNIQUEMENT pour IPTV — les
        //   sous-titres VOD restent intacts. Aligné avec PlayerTvFragment.
        try {
            val p = player
            if (isLiveIptvChannel) {
                // 2026-06-10 (user "dessin animé sous-titré + désactiver
                //   via paramètres") : honore le toggle iptvShowSubtitlesFr.
                p.trackSelectionParameters = if (UserPreferences.iptvShowSubtitlesFr) {
                    p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .setPreferredTextLanguages("fr", "fre", "fra")
                        .setSelectUndeterminedTextLanguage(true)
                        .build()
                } else {
                    p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                }
            } else {
                // VOD : ré-activer text track au cas où on switch d'une IPTV
                //   vers un film. Sans ça, les sous-titres VOD ne s'afficheraient
                //   plus après avoir regardé une chaîne.
                // 2026-06-09 (user "peu importe les épisodes c'est désactivé") :
                //   setTrackTypeDisabled(false) seul ne relance pas l'auto-
                //   sélection sur les épisodes VOD (animes / séries). Ajout :
                //   - clearOverridesOfType : reset overrides hérités.
                //   - setPreferredTextLanguages("fr"/"fre"/"fra") : préférence FR.
                //   - setSelectUndeterminedTextLanguage(true) : sélectionne
                //     même si label vide (cas embedded subs anime).
                // 2026-06-27 (user "Plex films/séries tout en anglais, faut que
                //   ce soit en français") : le VOD Plex/Pluto (catalogue France)
                //   a souvent une piste audio FR (VF) MAIS ExoPlayer jouait la
                //   piste par défaut (= VO anglaise) car aucune langue audio
                //   préférée n'était définie. On force la préférence audio FR
                //   UNIQUEMENT pour Plex/Pluto VOD (pas les animes VF/VOSTFR qui
                //   gèrent la langue au niveau source). Si pas de piste FR,
                //   ExoPlayer retombe sur la piste par défaut (no-op).
                val isPlexPlutoVod = args.id.let {
                    it.contains("plexvod_") || it.contains("plexep::") ||
                    it.contains("plutomovie_") || it.contains("plutoep::")
                }
                p.trackSelectionParameters = p.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setPreferredTextLanguages("fr", "fre", "fra")
                    .setSelectUndeterminedTextLanguage(true)
                    .apply {
                        if (isPlexPlutoVod) {
                            setPreferredAudioLanguages("fr", "fre", "fra")
                        }
                    }
                    .build()
            }
        } catch (_: Exception) {}
        val mediaItem = mediaItemBuilder.build()

        if (needsWebViewDs) {
            // Route .ts segment requests through WebView's network stack
            val wv = com.streamflixreborn.streamflix.extractors.LuluVdoExtractor.sharedWebView
                ?: com.streamflixreborn.streamflix.extractors.NetuExtractor.sharedWebView
                ?: error("needsWebViewDs but no sharedWebView available")
            val webViewDsFactory = DefaultDataSource.Factory(
                requireContext(),
                com.streamflixreborn.streamflix.utils.WebViewDataSource.Factory(wv)
            )
            val hlsSource = androidx.media3.exoplayer.hls.HlsMediaSource.Factory(webViewDsFactory)
                .createMediaSource(mediaItem)
            player.setMediaSource(hlsSource)
            usingWebView = true
            Log.d("PlayerNetwork", "WebView bypass: using WebViewDataSource for HLS playback (${if (isLuluVdoCdn(video)) "LuluVdo" else "Netu"})")
        } else {
            // Route HLS sources (live IPTV AND VOD .m3u8) through TeeDataSource so
            // LiveRecorder can capture played segments on demand. This lets the
            // user tap REC during ANY HLS playback to capture a portion. For
            // non-HLS sources (mp4, etc.) the TeeDataSource still wraps fine.
            // 2026-05-04 : MoiflixExtractor sert un master HLS via xs1.php?data=XXX
            // (sans extension .m3u8 dans l'URL) avec type=APPLICATION_M3U8. On
            // teste donc le `type` en plus de la source URL pour router vers
            // HlsMediaSource au lieu de ProgressiveMediaSource.
            // 2026-05-10 : .ts force Progressive (MPEG-TS continu), même si l'URL
            // contient /live/. Sinon HlsPlaylistParser tente de parser le binaire
            // comme playlist HLS et crashe (#EXTM3U manquant).
            val urlEndsWithTs = video.source.substringBefore('?').endsWith(".ts", ignoreCase = true)
            val isHls = !urlEndsWithTs && (
                video.source.contains(".m3u8")
                || video.source.contains("/live/")
                || video.type == androidx.media3.common.MimeTypes.APPLICATION_M3U8
                // 2026-07-05 : IANA standard (défense en profondeur)
                || video.type?.lowercase() == "application/vnd.apple.mpegurl"
            )
            // 2026-05-11 : détection DASH (.mpd) — utilisé par 3BoxTV (LCI live etc.).
            // Sans cette branche, le DASH tombe dans ProgressiveMediaSource qui
            // ne sait pas lire un manifest XML → "UnrecognizedInputFormatException".
            val isDash = !isHls && !urlEndsWithTs && (
                video.source.substringBefore('?').endsWith(".mpd", ignoreCase = true)
                || video.type == androidx.media3.common.MimeTypes.APPLICATION_MPD
            )
            // 2026-07-16 : sources OnlyFlix (SeekStreaming/EmbedSeek) = HLS `…/hlsmod/…` dont les
            //   segments sont du TS caché dans des PNG. On enveloppe le DataSource pour stripper
            //   l'en-tête PNG à la volée (cf. SeekStreamPngDataSource). N'affecte que ces sources.
            val hlsBaseFactory: androidx.media3.datasource.DataSource.Factory =
                if (video.source.contains("/tt/master.m3u8", ignoreCase = true) || video.source.contains("/hlsmod/", ignoreCase = true))
                    com.streamflixreborn.streamflix.utils.SeekStreamPngDataSource.Factory(dataSourceFactory)
                else dataSourceFactory
            val teeFactory = com.streamflixreborn.streamflix.download.TeeDataSourceFactory(hlsBaseFactory)
            if (isHls) {
                // 2026-05-09 v23 : retry 403 sur HLS Live (cf PlayerTvFragment).
                val errorPolicy = object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
                    override fun getRetryDelayMsFor(info: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                        val ex = info.exception
                        if (ex is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException && ex.responseCode == 403) {
                            Log.d("PlayerMobileFragment", "HLS 403 → retry 1.5s (manifest refresh)")
                            return 1500L
                        }
                        return super.getRetryDelayMsFor(info)
                    }
                    override fun getMinimumLoadableRetryCount(dataType: Int): Int = 6
                }
                val hlsSource = androidx.media3.exoplayer.hls.HlsMediaSource.Factory(teeFactory)
                    .setAllowChunklessPreparation(true)
                    .setLoadErrorHandlingPolicy(errorPolicy)
                    .createMediaSource(mediaItem)
                player.setMediaSource(hlsSource)
                Log.d("PlayerDebug", "HLS v23: TeeDataSource + retry-403 policy")
            } else if (isDash) {
                // DASH (.mpd) — utilisé par les flux live français (TF1, France TV, etc.)
                // via le pipeline 3BoxTV (RSS feed → URL signée vers .mpd).
                val dashFactory = androidx.media3.exoplayer.dash.DashMediaSource.Factory(teeFactory)
                // v34 : Widevine DRM pour TF1+ VOD. Le mediainfo retourne une license URL
                //   dans delivery.drms[name=widevine].url qu'on récupère via TF1Resolver cache.
                // 2026-06-19 : étendu pour M6+. M6Resolver expose aussi
                //   getWidevineLicenseUrl + getWidevineHeaders (= x-dt-auth-token).
                // 2026-06-27 : Pluto DASH exclu du DRM TF1/M6/BFM (faux match → « connecte-toi à M6 »).
                val isPlutoDashFs = video.source.contains("pluto.tv")
                val widevineUrl = if (isPlutoDashFs)
                    com.streamflixreborn.streamflix.utils.PlutoTvResolver.getWidevineLicenseUrl(video.source)
                else
                    (com.streamflixreborn.streamflix.utils.TF1Resolver
                    .getWidevineLicenseUrl(video.source)
                    ?: com.streamflixreborn.streamflix.utils.M6Resolver
                        .getWidevineLicenseUrl(video.source)
                    ?: com.streamflixreborn.streamflix.utils.BfmResolver
                        .getWidevineLicenseUrl(video.source))
                val drmHeaders = if (isPlutoDashFs) null else
                    (com.streamflixreborn.streamflix.utils.M6Resolver
                    .getWidevineHeaders(video.source)
                    ?: com.streamflixreborn.streamflix.utils.BfmResolver
                        .getWidevineHeaders(video.source))
                if (widevineUrl != null) {
                    Log.d("PlayerDebug", "DASH+Widevine DRM: license=${widevineUrl.take(80)}... headers=${drmHeaders?.keys}")
                    // 2026-06-19 v17 : si M6+ (= drmHeaders contient x-dt-auth-token),
                    //   utilise M6DrmCallback custom qui parse le JSON wrapper
                    //   {"license": "base64..."} retourné par DRMtoday. Sinon
                    //   HttpMediaDrmCallback standard pour TF1+ et autres.
                    // 2026-06-20 : BFM Play (= drmHeaders contient customdata) →
                    //   BfmDrmCallback custom qui retourne les bytes Widevine bruts.
                    val isM6 = drmHeaders?.containsKey("x-dt-auth-token") == true
                    val isBfm = drmHeaders?.containsKey("customdata") == true
                    val drmCallback: androidx.media3.exoplayer.drm.MediaDrmCallback = if (isM6) {
                        Log.d("PlayerDebug", "Using M6DrmCallback (DRMtoday JSON wrapper)")
                        com.streamflixreborn.streamflix.utils.M6DrmCallback(widevineUrl, drmHeaders!!)
                    } else if (isBfm) {
                        Log.d("PlayerDebug", "Using BfmDrmCallback (raw Widevine)")
                        com.streamflixreborn.streamflix.utils.BfmDrmCallback(widevineUrl, drmHeaders!!)
                    } else {
                        val cb = androidx.media3.exoplayer.drm.HttpMediaDrmCallback(
                            widevineUrl,
                            androidx.media3.datasource.DefaultHttpDataSource.Factory()
                                .setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36")
                        )
                        drmHeaders?.forEach { (k, v) ->
                            try { cb.setKeyRequestProperty(k, v) } catch (_: Throwable) {}
                        }
                        cb
                    }
                    // v37 : custom provider qui force securityLevel=L3 (software path).
                    //   Sinon Widevine tente L1 (= HW secure) qui demande HDCP →
                    //   sur OPPO CPH2211 HDCP retourne `255` (= unsupported) →
                    //   check en boucle infinie = écran qui clignote.
                    val l3Provider = androidx.media3.exoplayer.drm.ExoMediaDrm.Provider { uuid ->
                        val drm = androidx.media3.exoplayer.drm.FrameworkMediaDrm.newInstance(uuid)
                        try {
                            drm.setPropertyString("securityLevel", "L3")
                        } catch (e: Exception) {
                            Log.w("PlayerDebug", "Set L3 failed: ${e.message}")
                        }
                        drm
                    }
                    val drmSessionManager = androidx.media3.exoplayer.drm.DefaultDrmSessionManager.Builder()
                        .setUuidAndExoMediaDrmProvider(
                            androidx.media3.common.C.WIDEVINE_UUID,
                            l3Provider
                        )
                        .setMultiSession(false)
                        .build(drmCallback)
                    dashFactory.setDrmSessionManagerProvider { drmSessionManager }
                }
                val dashSource = dashFactory.createMediaSource(mediaItem)
                player.setMediaSource(dashSource)
                Log.d("PlayerDebug", "DASH: DashMediaSource + TeeDataSource (DRM=${widevineUrl != null})")
            } else {
                // Non-HLS / Non-DASH (mp4 progressive). Wrap with TeeDataSource for REC.
                val progressiveSource = androidx.media3.exoplayer.source.ProgressiveMediaSource
                    .Factory(teeFactory)
                    .createMediaSource(mediaItem)
                player.setMediaSource(progressiveSource)
                Log.d("PlayerDebug", "Progressive: using TeeDataSource (REC button can tap)")
            }
            usingWebView = false
        }

        // 2026-07-11 : lecteur externe — si un par-défaut est déjà choisi, lance
        //   directement ; sinon affiche le dialog avec checkbox "Définir par défaut".
        binding.pvPlayer.controller.binding.btnExoExternalPlayer.setOnClickListener {
            val pkg = UserPreferences.externalPlayerPackage
            if (UserPreferences.alwaysUseExternalPlayer && !pkg.isNullOrBlank()) {
                doExternalLaunch(video, directPackage = pkg)
            } else {
                promptExternalThenLaunch(video)
            }
        }
        // Detach previous listener (if any) before adding the new one — otherwise each
        // displayVideo() call leaks a listener and onPlayerError fires multiple times.
        activePlayerListener?.let { try { player.removeListener(it) } catch (_: Exception) {} }
        val newListener = object : Player.Listener {
            // 2026-05-15 (user "Aventures croisées ne se lance pas, écran noir") :
            // détection des codecs vidéo non supportés via onTracksChanged. Cas
            // HEVC Main 10 / HDR10 (hvc1.2.4.L120.90) où onPlayerError n'est PAS
            // déclenché parce que l'audio joue OK et que le player atteint
            // STATE_READY. Sans ce check, l'utilisateur reste sur un spinner
            // éternel sans savoir pourquoi.
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                super.onTracksChanged(tracks)
                val videoGroups = tracks.groups.filter {
                    it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO
                }
                if (videoGroups.isEmpty()) return  // audio-only legit
                // Y a-t-il AU MOINS un track vidéo supporté par les décodeurs ?
                val anyVideoSupported = videoGroups.any { group ->
                    (0 until group.length).any { i -> group.isTrackSupported(i) }
                }
                if (!anyVideoSupported) {
                    val firstFormat = videoGroups.first().getTrackFormat(0)
                    val codecLabel = firstFormat.codecs ?: firstFormat.sampleMimeType ?: "?"
                    val isHdr10 = codecLabel.contains("hvc1.2.", ignoreCase = true) ||
                        codecLabel.contains("hev1.2.", ignoreCase = true) ||
                        firstFormat.colorInfo?.colorTransfer == androidx.media3.common.C.COLOR_TRANSFER_ST2084
                    // 2026-06-19 v13 (user "C'est un bug codec, le son passe mais
                    //   pas l'image") : distinguer DRM-locked vs codec impossible.
                    //   avc1.42C01E (h264 baseline) est supporté par tous les
                    //   Android. Si isTrackSupported false MAIS le format a un
                    //   drmInitData, c'est un cas DRM → message clair, audio
                    //   continue de jouer (= au lieu de player.stop() qui tue
                    //   tout). L'image reviendra dès que la license sera
                    //   obtenue (= via M6Resolver background fix).
                    val isDrmLocked = firstFormat.drmInitData != null
                    if (isDrmLocked) {
                        Log.w("PlayerNetwork", "Track vidéo DRM-protected sans license ($codecLabel) — audio only")
                        try {
                            android.widget.Toast.makeText(
                                requireContext(),
                                "Contenu protégé (DRM) — lecture audio seulement.",
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        } catch (_: Exception) {}
                        // PAS de player.stop() → l'audio AAC continue de jouer
                        return
                    }
                    val toastMsg = when {
                        isHdr10 -> "Vidéo HEVC HDR10 (10-bit) non supportée par ce device — essaie un autre serveur si dispo"
                        else -> "Codec vidéo non supporté ($codecLabel) par ce device — essaie un autre serveur si dispo"
                    }
                    Log.e("PlayerNetwork", "Aucun track vidéo supporté ($codecLabel) — Toast + auto-skip")
                    try {
                        android.widget.Toast.makeText(
                            requireContext(), toastMsg, android.widget.Toast.LENGTH_LONG,
                        ).show()
                    } catch (_: Exception) {}
                    val server = currentServer
                    if (server != null) {
                        pruneBrokenVariant(server)
                        val nextServer = nextAutoFallbackServer(servers, server)
                        if (nextServer != null) {
                            Log.d("PlayerNetwork", "Codec unsupported → next server: ${nextServer.name}")
                            viewModel.getVideo(nextServer)
                        } else if (!tryNextChannelVariant(server)) {
                            Log.w("PlayerNetwork", "Codec unsupported et aucune source alternative → propose external player")
                            try { player.stop() } catch (_: Exception) {}
                            proposeExternalPlayer("Codec vidéo ($codecLabel) non supporté par cet appareil")
                        }
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                super.onPlaybackStateChanged(playbackState)
                val stateName = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "UNKNOWN($playbackState)"
                }
                Log.d("PlayerDebug", "onPlaybackStateChanged: $stateName, uri=${player.currentMediaItem?.localConfiguration?.uri?.toString()?.take(80)}")

                // 2026-05-21 : alimente le governor de qualité adaptatif (rebuffers).
                adaptiveQualityGovernor?.onState(playbackState)

                // 2026-05-05 : watchdog buffering — fallback auto si bloqué > 10s
                if (playbackState != Player.STATE_BUFFERING) {
                    bufferingWatchdog?.cancel()
                    bufferingWatchdog = null
                } else {
                    val isLiveIptv = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
                        args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
                        args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
                        args.id.startsWith("livehub::") || args.id.startsWith("sportlive::") ||
                        args.id.startsWith("match::") || args.id.startsWith("vavoo::") || args.id.startsWith("myiptv-live::")
                    // 2026-05-11 (user) : 2 règles selon état du stream :
                    //
                    // (A) PRÉ-READY (épisode jamais lancé, extracteur silencieux) :
                    //     SKIP au server suivant après 30s. Sinon l'user reste planté
                    //     indéfiniment sur un extracteur qui rend une URL morte.
                    //
                    // (B) POST-READY (épisode déjà joué, juste une coupure réseau) :
                    //     ZÉRO swap. À 15s buffering, on auto-active ExtraBuffering
                    //     (maxBufferMs 120s→300s) + re-init MÊME serveur avec seekTo
                    //     pour reprendre où l'user était. L'épisode marchait donc
                    //     le server est OK, juste un blip réseau.
                    if (!isLiveIptv && bufferingWatchdog == null) {
                        val initialPosition = player.currentPosition
                        bufferingWatchdog = viewLifecycleOwner.lifecycleScope.launch {
                            if (!vodCurrentStreamHasWorked) {
                                // (A) Pré-READY : 35s puis skip server
                                // 2026-06-09 : 20s→35s (user "l'autoscip pour les animes,
                                //   les serveurs ont pas le temps d'arriver"). Sibnet/
                                //   VidMoLy/Vidsonic peuvent prendre 25-30s depuis Tahiti.
                                kotlinx.coroutines.delay(35_000L)
                                if (player.playbackState == Player.STATE_BUFFERING &&
                                    player.currentPosition == initialPosition &&
                                    !vodCurrentStreamHasWorked) {
                                    val server = currentServer
                                    val nextServer = nextAutoFallbackServer(servers, server)
                                    Log.w("PlayerNetwork",
                                        "Pre-READY 35s freeze on ${server?.name} → skip to ${nextServer?.name}")
                                    if (server != null) {
                                        pruneBrokenVariant(server)
                                        // 2026-05-12 : flag instantanément le serveur broken
                                        // → fallback suivant le skip → cascade rapide
                                        com.streamflixreborn.streamflix.extractors.Extractor
                                            .recordFailureExternal(server.name, "pre-ready-freeze")
                                    }
                                    if (nextServer != null) {
                                        viewModel.getVideo(nextServer)
                                    } else if (viewModel.progressiveStillCollecting) {
                                        // 2026-05-28 : backups en cours → attendre le prochain lot
                                        Log.w("PlayerNetwork", "Pre-READY freeze, no servers YET — waiting for progressive backups…")
                                        viewModel.serversReordered.first().let { newList ->
                                            val candidate = newList.firstOrNull { s -> s.id != server?.id }
                                            if (candidate != null) {
                                                Log.w("PlayerNetwork", "Progressive backup arrived → trying ${candidate.name}")
                                                servers = newList
                                                viewModel.getVideo(candidate)
                                            } else {
                                                // 2026-07-11 : backups arrivés mais aucun candidat → propose lecteur externe
                                                Log.w("PlayerNetwork", "Pre-READY freeze, progressive done but no candidate → propose external player")
                                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                    proposeExternalPlayer("Aucun serveur n'a pu lire cette vidéo")
                                                }
                                            }
                                        }
                                    } else {
                                        // 2026-07-11 : plus aucun serveur → propose lecteur externe
                                        Log.w("PlayerNetwork", "Pre-READY freeze, no more servers → propose external player")
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            proposeExternalPlayer("Aucun serveur n'a pu lire cette vidéo")
                                        }
                                    }
                                }
                            } else {
                                // (B) Post-READY : 15s puis super-buffer OU swap si déjà tenté
                                kotlinx.coroutines.delay(15_000L)
                                if (player.playbackState == Player.STATE_BUFFERING &&
                                    player.currentPosition == initialPosition) {
                                    if (!currentExtraBuffering) {
                                        // 1ʳᵉ tentative : activer ExtraBuffering + re-init
                                        val server = currentServer
                                        val video = currentVideo
                                        if (server != null && video != null) {
                                            val savedPos = player.currentPosition
                                            Log.w("PlayerNetwork",
                                                "Post-READY 15s freeze on ${server.name} → ExtraBuffering ON @${savedPos}ms")
                                            PlayerSettingsView.Settings.ExtraBuffering.init(true)
                                            initializePlayer(true, currentSoftwareDecoder, video.source)
                                            displayVideo(video, server)
                                            try { player.seekTo(savedPos) } catch (_: Exception) {}
                                        }
                                    } else {
                                        // 2ᵉ tentative : ExtraBuffering déjà actif et TOUJOURS
                                        // coincé → le serveur est réellement mort. Swap.
                                        val server = currentServer
                                        Log.w("PlayerNetwork",
                                            "Post-READY freeze AFTER ExtraBuffering on ${server?.name} → server dead, swapping")
                                        if (server != null) {
                                            pruneBrokenVariant(server)
                                            com.streamflixreborn.streamflix.utils.TitleServerStatus.record(
                                                server.id,
                                                com.streamflixreborn.streamflix.utils.ExtractorRanker.ServerStatus.DEAD,
                                            )
                                        }
                                        val nextServer = nextAutoFallbackServer(servers, server)
                                        if (nextServer != null) {
                                            Log.w("PlayerNetwork", "→ auto-switching to ${nextServer.name}")
                                            viewModel.getVideo(nextServer)
                                        } else if (viewModel.progressiveStillCollecting) {
                                            Log.w("PlayerNetwork", "→ waiting for progressive backups…")
                                            viewModel.serversReordered.first().let { newList ->
                                                val candidate = newList.firstOrNull { s -> s.id != server?.id }
                                                if (candidate != null) {
                                                    servers = newList
                                                    viewModel.getVideo(candidate)
                                                }
                                            }
                                        } else {
                                            Log.e("PlayerNetwork", "→ no more servers to try → propose external player")
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                proposeExternalPlayer("La vidéo a figé et il n'y a plus d'autre serveur")
                                            }
                                        }
                                    }
                                }
                            }
                            bufferingWatchdog = null
                        }
                    }
                    // 2026-05-10 : ancien BUFFERING watchdog IPTV (rebuild player) désactivé
                    //   — crash natif MediaCodec sur empilement de reloads.
                    // 2026-05-20 (parité PlayerTvFragment) : reprise LÉGÈRE d'un stall live
                    //   SILENCIEUX (BUFFERING sans erreur, ex Vavoo edge lent au démarrage) :
                    //   seek live edge + prepare() toutes les 12s tant que ça stalle, PAS de
                    //   rebuild player (donc pas de crash), bornée par anti-empilement +
                    //   anti-flap. Sans ça le flux restait figé indéfiniment.
                    if (isLiveIptv && !transferLiveRecoveryActive) {
                        transferLiveRecoveryActive = true
                        // 2026-07-05 (user "reste coincé sur le 1er serveur, peine perdue,
                        //   pas de switch sur flux muet") : pour une chaîne OLA dont le
                        //   serveur courant n'a JAMAIS atteint READY (tuning initial), un
                        //   flux qui connecte mais n'envoie aucune image ne doit PAS boucler
                        //   en re-prepare sur le MÊME serveur → on SAUTE activement au variant
                        //   suivant (vérifié-vivant par la course parallèle du provider). Une
                        //   fois READY (sticky), on garde le re-prepare same-server pour les
                        //   blips transitoires (ne pas churner un flux qui marchait).
                        val isOlaCh = args.id.startsWith("ola::") || args.id.startsWith("ola_ep::")
                        viewLifecycleOwner.lifecycleScope.launch {
                            try {
                                var initialSwitchTries = 0
                                while (_binding != null &&
                                    (try { player.playbackState == Player.STATE_BUFFERING } catch (_: Exception) { false })) {
                                    // Fenêtre courte (7s) tant que ça n'a jamais démarré sur OLA
                                    // (on veut sauter vite un serveur muet) ; 12s sinon.
                                    val neverWorked = !iptvCurrentStreamHasWorked
                                    kotlinx.coroutines.delay(if (neverWorked && isOlaCh) 7_000L else 12_000L)
                                    if (_binding == null) break
                                    val stillBuffering = try { player.playbackState == Player.STATE_BUFFERING } catch (_: Exception) { false }
                                    if (!stillBuffering) break
                                    if (preemptiveReloadInFlight) continue

                                    // CAS 1 — jamais démarré + OLA : le serveur courant est muet
                                    //   → hop au variant suivant (max 6 tentatives) au lieu de
                                    //   re-prepare le même. getVideo relance un cycle propre.
                                    if (!iptvCurrentStreamHasWorked && isOlaCh && initialSwitchTries < 6) {
                                        initialSwitchTries++
                                        Log.w("PlayerMobileFragment", "Live BUFFERING (jamais READY) → switch variant OLA #$initialSwitchTries")
                                        val switched = tryNextChannelVariant(currentServer)
                                        if (switched) break  // nouveau getVideo → nouveau listener/loop
                                        // plus de variant → on retombe sur le re-prepare ci-dessous
                                    }

                                    val nowFlapB = System.currentTimeMillis()
                                    recentReloadTimestamps.removeAll { (nowFlapB - it) > RELOAD_FLAP_WINDOW_MS }
                                    if (recentReloadTimestamps.size >= RELOAD_FLAP_THRESHOLD) {
                                        Log.e("PlayerMobileFragment", "Live BUFFERING: reload flap — STOP")
                                        break
                                    }
                                    recentReloadTimestamps.add(nowFlapB)
                                    Log.w("PlayerMobileFragment", "Live BUFFERING >12s → seek live edge + prepare()")
                                    try {
                                        player.seekToDefaultPosition()
                                        player.prepare()
                                        player.playWhenReady = true
                                    } catch (_: Exception) {}
                                }
                            } finally {
                                transferLiveRecoveryActive = false
                            }
                        }
                    }
                }

                // Once we actually reach READY, restore the normal 2s auto-hide
                // (we forced it to 0 during IPTV extraction so the controls
                // would be visible immediately). Also mark IPTV server as sticky.
                if (playbackState == Player.STATE_READY) {
                    if (binding.pvPlayer.controllerShowTimeoutMs == 0) {
                        binding.pvPlayer.controllerShowTimeoutMs = 2000
                    }
                    if (iptvRetryCount > 0) {
                        Log.d("PlayerMobileFragment", "Stream recovered, resetting IPTV retry counter")
                        iptvRetryCount = 0
                    }
                    if (!iptvCurrentStreamHasWorked) {
                        iptvCurrentStreamHasWorked = true
                        Log.d("PlayerMobileFragment", "IPTV stream marked as working — sticky server enabled")
                        // 2026-05-09 v13 : refresh proactif du token Stalker toutes les 3 min.
                        scheduleProactiveTokenRefresh()
                    }
                    // 2026-05-11 : VOD too — flag pour swap rapide sur "petite coupure"
                    if (!vodCurrentStreamHasWorked) {
                        vodCurrentStreamHasWorked = true
                        vodStickyRetryCount = 0
                        Log.d("PlayerMobileFragment", "VOD stream marked as working — fast-swap on glitch enabled")
                    }
                    // 2026-05-21 : ce serveur a RÉELLEMENT joué pour CE titre → VERIFIED (vert)
                    //   uniquement pour ce titre (args.id), pas de bave sur les autres.
                    currentServer?.let {
                        com.streamflixreborn.streamflix.utils.TitleServerStatus.record(
                            it.id,
                            com.streamflixreborn.streamflix.utils.ExtractorRanker.ServerStatus.VERIFIED,
                            args.id,
                        )
                    }
                    // 2026-07-04 : lecture réussie → ré-arme les 8 passes pour la prochaine fois.
                    autoPassCount = 0
                }

                // Live IPTV auto-resume: re-prepare on STATE_ENDED (playlist tail
                // exhausted) AND on STATE_IDLE (transient blip with no error).
                val isLiveIptvStream = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
                    args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
                    args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
                    args.id.startsWith("livehub::") || args.id.startsWith("sportlive::") ||
                    args.id.startsWith("match::") || args.id.startsWith("vavoo::") || args.id.startsWith("myiptv-live::")
                // 2026-05-16 : pour les chaînes IPTV live, certaines playlists HLS
                // contiennent `#EXT-X-ENDLIST` ou ont une durée fixe courte (ex: 5s)
                // → ExoPlayer pense que c'est une VOD courte → STATE_ENDED après 5s.
                // Le reload doit ÊTRE LÉGER (just player.prepare() = re-fetch la
                // playlist qui a de nouveaux segments). Surtout PAS displayVideo()
                // qui recrée le MediaItem (= reset complet = coupe le rendu).
                // STATE_IDLE = blip réseau transient (même traitement).
                // 2026-06-05 (user "série enfants 5min ne switch pas vers
                //   l'épisode suivant") : sur les vidéos courtes, ExoPlayer
                //   peut sauter le isPlaying=false et passer direct STATE_ENDED.
                //   Le handler autoplay dans onIsPlayingChanged ne se déclenche
                //   pas alors. On déclenche aussi sur STATE_ENDED pour VOD.
                if (!isLiveIptvStream && playbackState == Player.STATE_ENDED
                    && args.videoType is Video.Type.Episode
                    && UserPreferences.autoplay
                ) {
                    Log.w("AutoplayDiag", "[TRIGGER A] STATE_ENDED VOD ep=${(args.videoType as? Video.Type.Episode)?.id} → playNextEpisodeAcrossSeasons(autoplay=true)")
                    playNextEpisodeAcrossSeasons(autoplay = true)
                }

                if (isLiveIptvStream && (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE)) {
                    if (playbackState == Player.STATE_IDLE && !iptvCurrentStreamHasWorked) return
                    if (preemptiveReloadInFlight) return
                    // 2026-05-20 (parité TV) : anti-flap — stop si >=3 reloads en 15s
                    //   (flux trop instable) pour ne pas empiler les prepare().
                    val nowFlapE = System.currentTimeMillis()
                    recentReloadTimestamps.removeAll { (nowFlapE - it) > RELOAD_FLAP_WINDOW_MS }
                    if (recentReloadTimestamps.size >= RELOAD_FLAP_THRESHOLD) {
                        Log.e("PlayerMobileFragment", "Live ENDED/IDLE reload flap (${recentReloadTimestamps.size} en 15s) — STOP")
                        return
                    }
                    recentReloadTimestamps.add(nowFlapE)
                    Log.w("PlayerMobileFragment", "Live IPTV $playbackState — refresh playlist via player.prepare()")
                    preemptiveReloadInFlight = true
                    try {
                        viewLifecycleOwner.lifecycleScope.launch {
                            try {
                                // JUST prepare() — refresh la playlist HLS et reprend
                                // au bord du nouveau contenu disponible. PAS de seek,
                                // PAS de displayVideo, PAS de setMediaItem.
                                player.prepare()
                                player.playWhenReady = true
                            } catch (e: Exception) {
                                Log.w("PlayerMobileFragment", "prepare() refresh failed: ${e.message}")
                            }
                            // Cool-down court — si la playlist est vraiment cassée
                            // (ENDLIST fixe), on retentera vite. Pas de 25s d'avant.
                            kotlinx.coroutines.delay(3_000L)
                            preemptiveReloadInFlight = false
                        }
                    } catch (e: Exception) {
                        preemptiveReloadInFlight = false
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                super.onIsPlayingChanged(isPlaying)
                binding.pvPlayer.keepScreenOn = isPlaying || UserPreferences.keepScreenOnWhenPaused

                if (isPlaying) {
                    startProgressHandler()
                } else {
                    stopProgressHandler()
                }

                val hasUri = player.currentMediaItem?.localConfiguration?.uri
                    ?.toString()?.isNotEmpty()
                    ?: false

                if (!isPlaying && hasUri) {
                    val videoType = args.videoType
                    val hasStarted = player.hasStarted()
                    val hasFinished = player.hasFinished()
                    val hasReallyFinished = player.hasReallyFinished()
                    val currentPos = player.currentPosition
                    val duration = player.duration
                    val ctx = requireContext()
                    val provider = UserPreferences.currentProvider ?: return

                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        val watchItem: WatchItem? = when (videoType) {
                            is Video.Type.Movie -> database.movieDao().getById(videoType.id)
                            is Video.Type.Episode -> database.episodeDao().getById(videoType.id)
                        }

                        when {
                            hasStarted && !hasFinished -> {
                                watchItem?.isWatched = false
                                watchItem?.watchedDate = null
                                watchItem?.watchHistory = WatchItem.WatchHistory(
                                    lastEngagementTimeUtcMillis = System.currentTimeMillis(),
                                    lastPlaybackPositionMillis = currentPos,
                                    durationMillis = duration,
                                )
                            }

                            hasFinished -> {
                                watchItem?.isWatched = true
                                watchItem?.watchedDate = Calendar.getInstance()
                                watchItem?.watchHistory = null
                            }
                        }

                        when (videoType) {
                            is Video.Type.Movie -> {
                                val movie = watchItem as? Movie
                                movie?.let {
                                    database.movieDao().update(it)
                                    UserDataCache.syncMovieToCache(ctx, provider, it)
                                }
                            }

                            is Video.Type.Episode -> {
                                val episode = watchItem as? Episode
                                episode?.let {
                                    if (hasFinished) {
                                        database.episodeDao().resetProgressionFromEpisode(videoType.id)
                                        UserDataCache.removeEpisodeFromContinueWatching(ctx, provider, it.id)
                                        queueNextEpisodeForContinueWatching(provider)
                                    }
                                    database.episodeDao().update(it)
                                    if (!hasFinished) {
                                        UserDataCache.syncEpisodeToCache(ctx, provider, it)
                                    }

                                    it.tvShow?.let { tvShow ->
                                        database.tvShowDao().getById(tvShow.id)
                                    }?.let { tvShow ->
                                        val episodeDao = database.episodeDao()
                                        val isStillWatching = episodeDao.hasAnyWatchHistoryForTvShow(tvShow.id)

                                        database.tvShowDao().save(tvShow.copy().apply {
                                            merge(tvShow)
                                            isWatching = !hasReallyFinished || isStillWatching
                                        })
                                    }
                                }
                            }
                        }
                    }
                    // 2026-05-09 : IPTV ne déclenche jamais l'autoplay
                    // next-episode (bug BFMTV qui auto-skipait sur live).
                    val isLiveIptvNoAutoSkip = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
                        args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
                        args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
                        args.id.startsWith("livehub::") || args.id.startsWith("sportlive::") ||
                        args.id.startsWith("match::") || args.id.startsWith("vavoo::") || args.id.startsWith("myiptv-live::")
                    if (player.hasReallyFinished() && !isLiveIptvNoAutoSkip) {
                        if (UserPreferences.autoplay) {
                            Log.w("AutoplayDiag", "[TRIGGER B] hasReallyFinished ep=${(args.videoType as? Video.Type.Episode)?.id} pos=${player.currentPosition} dur=${player.duration} → playNextEpisodeAcrossSeasons(autoplay=true)")
                            playNextEpisodeAcrossSeasons(autoplay = true)
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                super.onPlayerError(error)
                // 2026-05-21 : ce serveur a ÉCHOUÉ pour CE titre → DEAD (rouge) uniquement
                //   pour ce titre (args.id). Un échec ici ne colore pas les autres épisodes.
                currentServer?.let {
                    com.streamflixreborn.streamflix.utils.TitleServerStatus.record(
                        it.id,
                        com.streamflixreborn.streamflix.utils.ExtractorRanker.ServerStatus.DEAD,
                        args.id,
                    )
                }
                Log.e("PlayerDebug", "onPlayerError: code=${error.errorCode}, msg=${error.message}")
                Log.e("PlayerDebug", "  cause: ${error.cause}")
                Log.e("PlayerDebug", "  cause.cause: ${error.cause?.cause}")
                Log.e("PlayerDebug", "  uri: ${player.currentMediaItem?.localConfiguration?.uri?.toString()?.take(100)}")
                Log.e("PlayerMobileFragment", "onPlayerError: ", error)

                val cause = error.cause?.cause
                val causeMsg = cause?.message ?: ""
                val errorCauseMsg = error.cause?.message ?: ""

                // 2026-05-15 (user "écran noir au lieu de jouer la vidéo") :
                // détection explicite des codecs propriétaires non supportables
                // (Dolby Vision dvhe.*, AV1 sur vieux device, HDR10+ sur device
                // SDR, etc.). Sans ce check, l'user voit BUFFERING infini sans
                // savoir pourquoi. Maintenant on affiche un Toast clair + on
                // stoppe le sticky-retry qui boucle sans espoir.
                val errMsgFull = error.message ?: ""
                val isUnsupportedCodec = errMsgFull.contains("NO_EXCEEDS_CAPABILITIES") ||
                    errMsgFull.contains("NO_UNSUPPORTED_TYPE") ||
                    errMsgFull.contains("NO_UNSUPPORTED_DRM")
                val isDolbyVision = errMsgFull.contains("dolby-vision", ignoreCase = true) ||
                    errMsgFull.contains("dvhe.", ignoreCase = true) ||
                    errMsgFull.contains("dvav.", ignoreCase = true) ||
                    errMsgFull.contains("dvh1.", ignoreCase = true)
                if (isUnsupportedCodec || isDolbyVision) {
                    val server = currentServer
                    val toastMsg = when {
                        isDolbyVision -> "Dolby Vision non supporté sur cet appareil — choisis un autre serveur si dispo"
                        else -> "Format/codec non supporté par ce device — choisis un autre serveur si dispo"
                    }
                    Log.e("PlayerNetwork", "Codec non supporté ($errMsgFull) — Toast + auto-skip")
                    try {
                        android.widget.Toast.makeText(
                            requireContext(),
                            toastMsg,
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    } catch (_: Exception) {}
                    if (server != null) {
                        pruneBrokenVariant(server)
                        val nextServer = nextAutoFallbackServer(servers, server)
                        if (nextServer != null) {
                            Log.d("PlayerNetwork", "Codec unsupported → next server: ${nextServer.name}")
                            viewModel.getVideo(nextServer)
                        } else if (!tryNextChannelVariant(server)) {
                            Log.w("PlayerNetwork", "Codec unsupported (onPlayerError) et aucune source alternative → propose external player")
                            try { player.stop() } catch (_: Exception) {}
                            proposeExternalPlayer("Format/codec non supporté par cet appareil")
                        }
                    }
                    return
                }

                // Fallback 0: Cronet network errors (NOT 403) → retry with DefaultHttp
                val is403 = errorCauseMsg.contains("403") || causeMsg.contains("403")
                val isCronetNetworkError = causeMsg.contains("ERR_CONNECTION_TIMED_OUT")
                        || causeMsg.contains("ERR_CONNECTION_REFUSED")
                        || causeMsg.contains("ERR_CONNECTION_RESET")
                        || causeMsg.contains("ERR_NAME_NOT_RESOLVED")
                        || causeMsg.contains("ERR_SSL")
                        || causeMsg.contains("ERR_NETWORK")
                if (usingCronet && isCronetNetworkError && !is403) {
                    Log.w("PlayerNetwork", "Cronet network error ($causeMsg), retrying with DefaultHttp fallback")
                    val video = currentVideo ?: return
                    val server = currentServer ?: return
                    httpDataSource = createDefaultHttpDataSourceFactory()
                    dataSourceFactory = DefaultDataSource.Factory(requireContext(), httpDataSource)
                    initializePlayer(currentExtraBuffering, currentSoftwareDecoder, video.source)
                    displayVideo(video, server)
                    return
                }

                // Fallback 1: if 403, try next server or channel variant
                if (is403) {
                    Log.e("PlayerNetwork", "403 error! usingCronet=$usingCronet, source=${player.currentMediaItem?.localConfiguration?.uri?.toString()?.take(80)}")
                    val server = currentServer ?: return
                    pruneBrokenVariant(server)
                    val nextServer = nextAutoFallbackServer(servers, server)
                    if (nextServer != null) {
                        Log.d("PlayerNetwork", "403 → trying next server: ${nextServer.name}")
                        viewModel.getVideo(nextServer)
                    } else if (!tryNextChannelVariant(server)) {
                        Log.e("PlayerNetwork", "No more servers or channel variants to try after 403")
                    }
                    return
                }

                // Fallback 2: if connection timed out, ISP-blocked, or WebView fetch failed,
                // automatically try the next server or channel variant
                val isConnectionTimeout = causeMsg.contains("SocketTimeoutException")
                        || causeMsg.contains("failed to connect")
                        || causeMsg.contains("Connection timed out")
                        || causeMsg.contains("ERR_CONNECTION_TIMED_OUT")
                        || causeMsg.contains("ISP blocked")
                        || causeMsg.contains("cfglobalcdn IP")
                        || cause is java.net.SocketTimeoutException
                        || error.cause is java.net.SocketTimeoutException
                        || errorCauseMsg.contains("WebView fetch failed")
                        || errorCauseMsg.contains("WebView fetch timed out")
                if (isConnectionTimeout) {
                    val server = currentServer ?: return
                    // 2026-05-08 : pour IPTV, sticky absolu APRÈS 1er READY.
                    // L'user a explicitement choisi (ou démarré sur un favori) —
                    // pas de switch auto. Avant 1er READY, on cherche encore une
                    // source utilisable comme avant.
                    val isLiveIptvNow = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
                        args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
                        args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
                        args.id.startsWith("livehub::") || args.id.startsWith("sportlive::") ||
                        args.id.startsWith("match::") || args.id.startsWith("vavoo::") || args.id.startsWith("myiptv-live::")
                    if (isLiveIptvNow && iptvCurrentStreamHasWorked) {
                        Log.w("PlayerNetwork", "Connection timeout IPTV sticky (already worked) → re-prepare same server")
                        try { player.prepare(); player.playWhenReady = true } catch (_: Exception) {}
                        return
                    }
                    pruneBrokenVariant(server)
                    val nextServer = nextAutoFallbackServer(servers, server)
                    if (nextServer != null) {
                        Log.w("PlayerNetwork", "Connection timeout on ${server.name}, auto-switching to ${nextServer.name}")
                        viewModel.getVideo(nextServer)
                    } else if (!tryNextChannelVariant(server)) {
                        Log.e("PlayerNetwork", "Connection timeout on ${server.name}, no more servers → propose external player")
                        proposeExternalPlayer("Connexion impossible — aucun serveur alternatif")
                    }
                    return
                }

                // Fallback 3 (IPTV): for live channels — sticky server pattern.
                // Switch quand :
                //   (a) le stream n'a JAMAIS fonctionné ET retry > MAX_RETRIES_BEFORE_SWITCH
                //   (b) erreur HTTP permanente (403/404/410/451/456 = blocked/dead)
                // Sinon : re-prepare le même server (sticky pour erreurs transitoires
                // après que le stream a déjà marché une fois).
                val isLiveIptv = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
                    args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
                    args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
                    args.id.startsWith("livehub::") || args.id.startsWith("sportlive::") ||
                    args.id.startsWith("match::") || args.id.startsWith("vavoo::") || args.id.startsWith("myiptv-live::")
                if (isLiveIptv) {
                    val server = currentServer ?: return
                    val errCodeName = error.errorCodeName
                    iptvRetryCount++

                    // 2026-05-09 v3 : STICKY ABSOLU si stream a marché.
                    val errMsg = (error.cause?.message ?: error.message ?: "").lowercase()
                    // 2026-05-17 : 500/502/503 ajoutés (parité TV) — Stalker/Xtream
                    // token expiré donne souvent 5xx → fresh handshake résout.
                    // 2026-07-05 (parité TV — user "reste coincé, retente le même serveur mort") :
                    //   ERROR_CODE_IO_BAD_HTTP_STATUS traité comme permanent via le CODE d'erreur
                    //   (le parsing du message ne matchait pas toujours le statut exact).
                    val isBadHttpStatusCode = error.errorCode ==
                        androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                    val isPermanentHttpError = isBadHttpStatusCode ||
                        errMsg.contains("response code: 403") ||
                        errMsg.contains("response code: 404") ||
                        errMsg.contains("response code: 410") ||
                        errMsg.contains("response code: 451") ||
                        errMsg.contains("response code: 456") ||
                        errMsg.contains("response code: 500") ||
                        errMsg.contains("response code: 502") ||
                        errMsg.contains("response code: 503")
                    val MAX_RETRIES_BEFORE_SWITCH = 3
                    // 2026-06-17 (user "écran noir + pas de son sur OLA") : 456 = MAC
                    //   blocked / rate-limit côté serveur — JAMAIS récupérable même
                    //   après retry. Force le switch même si stream a déjà brièvement
                    //   marché (sinon on retry sticky un server condamné).
                    val isMacBlocked456 = errMsg.contains("response code: 456")
                    val shouldSwitch = (!iptvCurrentStreamHasWorked || isMacBlocked456) &&
                        (isPermanentHttpError || iptvRetryCount >= MAX_RETRIES_BEFORE_SWITCH)

                    // 2026-05-10 : cooldown anti-cascade (cf PlayerTvFragment).
                    val nowMs = System.currentTimeMillis()
                    val sinceLastSwitch = nowMs - lastAutoSwitchTime
                    val switchCooldownMs = 10_000L
                    // 2026-05-10 : favori → JAMAIS auto-switch.
                    val isFavoriteServer = try {
                        com.streamflixreborn.streamflix.fragments.player.settings.IptvFavorites
                            .isFavorite(args.id, server.id)
                    } catch (_: Exception) { false }
                    // 2026-07-05 : démarrage + erreur HTTP permanente → pas de cooldown, switch direct.
                    val bypassCooldown = !iptvCurrentStreamHasWorked && isPermanentHttpError
                    if (shouldSwitch && isFavoriteServer) {
                        Log.d("PlayerMobileFragment", "IPTV switch demandé mais server est favori — on retry à la place")
                    } else if (shouldSwitch && sinceLastSwitch < switchCooldownMs && !bypassCooldown) {
                        Log.d("PlayerMobileFragment", "IPTV switch demandé mais cooldown ${(switchCooldownMs - sinceLastSwitch)/1000}s — on retry à la place")
                    } else if (shouldSwitch) {
                        Log.w("PlayerMobileFragment", "IPTV switch on ${server.name} ($errCodeName, retry=$iptvRetryCount, hasWorked=$iptvCurrentStreamHasWorked, permanentHttp=$isPermanentHttpError)")
                        pruneBrokenVariant(server)
                        val nextServer = nextNonDeadServer(server)
                        if (nextServer != null) {
                            iptvRetryCount = 0
                            iptvCurrentStreamHasWorked = false
                            lastAutoSwitchTime = nowMs
                            viewModel.getVideo(nextServer)
                            return
                        } else if (tryNextChannelVariant(server)) {
                            iptvRetryCount = 0
                            iptvCurrentStreamHasWorked = false
                            lastAutoSwitchTime = nowMs
                            return
                        }
                        // 2026-07-05 (user "souvent il n'y a plus de serveur") : la liste visible
                        //   est épuisée → on tire un remplaçant dans le POOL COMPLET du provider
                        //   (streams Phase 3 non émis, 100+ pour une chaîne populaire). Dédup +
                        //   skip domaines morts gérés par requestSingleReplacement (renvoie null
                        //   quand le pool est vraiment épuisé). Borné par MAX_POOL_REPLACEMENTS.
                        if (poolReplacementChannelId != args.id) {
                            poolReplacementChannelId = args.id
                            poolReplacementCount = 0
                        }
                        val poolProv = UserPreferences.currentProvider
                        if (poolProv is com.streamflixreborn.streamflix.providers.OlaTvProvider &&
                            poolReplacementCount < MAX_POOL_REPLACEMENTS) {
                            val repl = poolProv.requestSingleReplacement(server.id)
                            if (repl != null) {
                                poolReplacementCount++
                                Log.w("PlayerMobileFragment", "Pool replacement #$poolReplacementCount pour ${server.name} → ${repl.name}")
                                pruneBrokenVariant(server)
                                triedChannelVariantIds.add(repl.id)
                                iptvRetryCount = 0
                                iptvCurrentStreamHasWorked = false
                                lastAutoSwitchTime = nowMs
                                viewModel.getVideo(repl)
                                return
                            }
                        }
                        Log.e("PlayerMobileFragment", "IPTV switch demandé mais pas de server suivant disponible (pool épuisé)")
                    }

                    // 2026-05-12 (user) : HARD CAP pour éviter boucle infinie.
                    // Cf PlayerTvFragment pour le contexte (BoxXtemus HTML embed
                    // → ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED en boucle 33+ fois).
                    val HARD_RETRY_CAP = 5
                    val isParseError = errCodeName == "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED" ||
                        errCodeName == "ERROR_CODE_PARSING_CONTAINER_MALFORMED" ||
                        errCodeName == "ERROR_CODE_PARSING_MANIFEST_MALFORMED" ||
                        errCodeName == "ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED"
                    val noFallbackAvailable = nextNonDeadServer(server) == null
                    if (!iptvCurrentStreamHasWorked && iptvRetryCount >= HARD_RETRY_CAP && (isParseError || noFallbackAvailable)) {
                        Log.e("PlayerMobileFragment",
                            "IPTV hard cap atteint sur ${server.name} ($errCodeName, retry=$iptvRetryCount, hasWorked=false, noFallback=$noFallbackAvailable) — abandon")
                        try {
                            android.widget.Toast.makeText(
                                requireContext(),
                                "Lecture impossible (${errCodeName.removePrefix("ERROR_CODE_")})",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        } catch (_: Exception) {}
                        try { player.stop() } catch (_: Exception) {}
                        return
                    }

                    Log.w("PlayerMobileFragment", "IPTV retry on ${server.name} ($errCodeName) — retry #$iptvRetryCount, sticky (hasWorked=$iptvCurrentStreamHasWorked)")
                    try {
                        // 2026-05-09 v13 : Stalker + 403 → fresh handshake.
                        val isStalker = server.id.startsWith("vegeta_stream::") ||
                            server.id.startsWith("ola_stream::")
                        if (isStalker && isPermanentHttpError) {
                            Log.d("PlayerMobileFragment", "Stalker token expired → fresh handshake")
                            iptvRetryCount = 0
                            viewLifecycleOwner.lifecycleScope.launch {
                                val newServer = withContext(Dispatchers.IO) {
                                    when {
                                        server.id.startsWith("vegeta_stream::") ->
                                            com.streamflixreborn.streamflix.providers.VegetaTvProvider.refreshServerUrl(server)
                                        server.id.startsWith("ola_stream::") ->
                                            com.streamflixreborn.streamflix.providers.OlaTvProvider.refreshServerUrl(server)
                                        else -> null
                                    }
                                }
                                if (newServer != null && _binding != null) {
                                    viewModel.getVideo(newServer)
                                } else if (_binding != null) {
                                    // 2026-05-09 v19 : refresh impossible — prune + auto-switch
                                    // vers le server suivant au lieu de boucler en prepare().
                                    Log.w("PlayerMobileFragment",
                                        "Refresh impossible (no Stalker context) — pruning ${server.name} and auto-switching")
                                    pruneBrokenVariant(server)
                                    val nextServer = nextNonDeadServer(server)
                                        ?: servers.firstOrNull { it.id != server.id &&
                                            com.streamflixreborn.streamflix.utils.TitleServerStatus.statusOf(it.id) !=
                                                com.streamflixreborn.streamflix.utils.ExtractorRanker.ServerStatus.DEAD }
                                    if (nextServer != null) {
                                        viewModel.getVideo(nextServer)
                                    } else {
                                        player.prepare()
                                        player.playWhenReady = true
                                    }
                                }
                            }
                        } else if (server.id.startsWith("m3u8::")) {
                            // WiTV v2 m3u8 server failed → rotation auto vers le suivant
                            Log.w("PlayerMobileFragment", "WiTV m3u8 ${server.name} failed ($errCodeName) → rotation auto")
                            viewLifecycleOwner.lifecycleScope.launch {
                                val nextServer = nextNonDeadServer(server)
                                    ?: servers.firstOrNull { it.id != server.id &&
                                        com.streamflixreborn.streamflix.utils.TitleServerStatus.statusOf(it.id) !=
                                            com.streamflixreborn.streamflix.utils.ExtractorRanker.ServerStatus.DEAD }
                                if (nextServer != null && _binding != null) {
                                    Log.w("PlayerMobileFragment", "  → essai ${nextServer.name}")
                                    viewModel.getVideo(nextServer)
                                } else {
                                    player.prepare()
                                    player.playWhenReady = true
                                }
                            }
                        } else {
                            player.prepare()
                            player.playWhenReady = true
                        }
                    } catch (e: Exception) {
                        Log.w("PlayerMobileFragment", "Sticky retry failed: ${e.message}")
                    }
                    return
                }

                // Fallback 4 (catch-all VOD): any other error code we didn't match
                // explicitly above (404 Not Found, 410 Gone, manifest malformed,
                // decoding failure, generic IO, etc.) used to leave the screen frozen
                // black with no auto-advance — the user had to back out and pick
                // another server manually. For anime/film providers we now always
                // try the next server when an error fires, so a dead first lecteur
                // (e.g. vidmoly with an expired token) auto-fails over to the
                // following one.
                // 2026-05-11 (user) : RÈGLE 403/serveur HS uniquement.
                // Avant : tout error non-handled (incluant decoder error, glitch
                // transitoire) → auto-switch serveur. User : "si la vidéo bug au lieu
                // de reprendre sur le même serveur s'auto swap, c'est pas bon".
                // Nouveau : seulement swap si l'erreur indique vraiment un serveur
                // HS (404/410/451/456/500/502/503). Sinon STICKY = re-prepare le
                // même server (l'user a explicitement choisi, on respecte).
                run {
                    val server = currentServer ?: return
                    val errCodeName = error.errorCodeName
                    val msg = (error.message ?: "") + " " + (error.cause?.message ?: "")
                    // 2026-05-25 : PARSING_CONTAINER_UNSUPPORTED/MALFORMED = le serveur
                    // renvoie du contenu illisible (page HTML, fichier corrompu). C'est
                    // permanent, pas transitoire — re-prepare boucle à l'infini.
                    val isServerDead = Regex("\\b(404|410|451|456|500|502|503|504)\\b").containsMatchIn(msg) ||
                        errCodeName.contains("HTTP_DATA_SOURCE_FORBIDDEN") ||
                        errCodeName.contains("DECODING_FAILED") ||
                        errCodeName.contains("PARSING_CONTAINER_UNSUPPORTED") ||
                        errCodeName.contains("PARSING_CONTAINER_MALFORMED") ||
                        errCodeName.contains("PARSING_MANIFEST_MALFORMED") ||
                        errCodeName.contains("PARSING_MANIFEST_UNSUPPORTED") ||
                        errCodeName.contains("UnrecognizedInputFormatException")
                    if (isServerDead) {
                        pruneBrokenVariant(server)
                        val nextServer = nextAutoFallbackServer(servers, server)
                        if (nextServer != null) {
                            Log.w("PlayerNetwork",
                                "Server HS on ${server.name} ($errCodeName: ${error.message}) — auto-switching to ${nextServer.name}")
                            viewModel.getVideo(nextServer)
                        } else if (!tryNextChannelVariant(server)) {
                            if (viewModel.progressiveStillCollecting) {
                                // 2026-05-28 : le flow progressif est encore en cours
                                // → des backups Movix/Cloudstream arrivent bientôt.
                                // On attend le prochain lot puis on retente le meilleur.
                                Log.w("PlayerNetwork", "Server HS on ${server.name} ($errCodeName) — no servers YET, waiting for progressive backups…")
                                viewLifecycleOwner.lifecycleScope.launch {
                                    viewModel.serversReordered.first().let { newList ->
                                        val candidate = newList.firstOrNull { s -> s.id != server.id }
                                        if (candidate != null) {
                                            Log.w("PlayerNetwork", "Progressive backup arrived → trying ${candidate.name}")
                                            servers = newList
                                            viewModel.getVideo(candidate)
                                        } else {
                                            Log.e("PlayerNetwork", "Progressive backup arrived but no new server to try")
                                        }
                                    }
                                }
                            } else {
                                Log.e("PlayerNetwork", "Server HS on ${server.name} ($errCodeName) and no more servers")
                            }
                        }
                    } else {
                        // Erreur transitoire (glitch décodeur, network blip) — STICKY.
                        // 2026-05-28 : compteur anti-boucle infinie. Si 3 re-prepare
                        // échouent sans jamais atteindre READY → serveur mort en pratique.
                        vodStickyRetryCount++
                        if (vodStickyRetryCount >= 3) {
                            Log.w("PlayerNetwork",
                                "3 sticky retries failed on ${server.name} ($errCodeName) — treating as dead, swapping")
                            vodStickyRetryCount = 0
                            pruneBrokenVariant(server)
                            com.streamflixreborn.streamflix.utils.TitleServerStatus.record(
                                server.id,
                                com.streamflixreborn.streamflix.utils.ExtractorRanker.ServerStatus.DEAD,
                            )
                            val nextServer = nextAutoFallbackServer(servers, server)
                            if (nextServer != null) {
                                viewModel.getVideo(nextServer)
                            } else if (viewModel.progressiveStillCollecting) {
                                viewLifecycleOwner.lifecycleScope.launch {
                                    viewModel.serversReordered.first().let { newList ->
                                        val candidate = newList.firstOrNull { s -> s.id != server.id }
                                        if (candidate != null) {
                                            servers = newList
                                            viewModel.getVideo(candidate)
                                        }
                                    }
                                }
                            }
                        } else {
                            Log.w("PlayerNetwork",
                                "Transient error on ${server.name} ($errCodeName: ${error.message}) — STICKY retry $vodStickyRetryCount/3")
                            try { player.prepare(); player.playWhenReady = true } catch (_: Exception) {}
                        }
                    }
                }
            }
        }
        player.addListener(newListener)
        activePlayerListener = newListener

        // 2026-05-09 v12 : pour IPTV live, seekToDefaultPosition (= live edge
        // moins targetOffsetMs = mid-bar). Évite stagne en timeout après refresh.
        val isLiveIptvStream = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
            args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
            args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
            args.id.startsWith("livehub::") || args.id.startsWith("sportlive::") ||
            args.id.startsWith("match::") || args.id.startsWith("vavoo::") || args.id.startsWith("myiptv-live::")
        if (isLiveIptvStream) {
            player.seekToDefaultPosition()
        } else if (currentPosition == 0L) {
            val videoType = args.videoType
            val provider = UserPreferences.currentProvider
            val ctx = requireContext()

            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val watchItem: WatchItem? = when (videoType) {
                    is Video.Type.Movie -> {
                        val movie = if (provider != null) {
                            UserDataCache.read(ctx, provider)?.continueWatchingMovies
                                ?.find { it.id == videoType.id }?.toMovie()
                        } else null
                        movie ?: database.movieDao().getById(videoType.id)
                    }
                    is Video.Type.Episode -> {
                        val episode = if (provider != null) {
                            UserDataCache.read(ctx, provider)?.continueWatchingEpisodes
                                ?.find { it.id == videoType.id }?.toEpisode()
                        } else null
                        episode ?: database.episodeDao().getById(videoType.id)
                    }
                }

                val lastPlaybackPositionMillis = watchItem?.watchHistory
                    ?.let { it.lastPlaybackPositionMillis - 10.seconds.inWholeMilliseconds }

                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    player.seekTo(lastPlaybackPositionMillis ?: 0)
                }
            }
        } else {
            player.seekTo(currentPosition)
        }

        player.prepare()
        player.play()

        // 2026-07-06 : auto-appliquer le zoom persisté pour ce serveur (ex: Sibnet).
        //   Le zoom est purement visuel (scaleX/Y sur la SurfaceView), indépendant
        //   du pipeline media. On l'applique APRÈS prepare/play pour que la surface
        //   existe. disableAllClipping nécessaire pour que le scale>1 ne soit pas coupé.
        try {
            // 2026-07-10 : zoom « collant » — d'abord le dernier zoom manuel GLOBAL (suit d'une vidéo à
            //   l'autre), sinon le zoom par serveur (ex: Sibnet). Reste tant qu'il n'est pas modifié/reset.
            val saved = com.streamflixreborn.streamflix.utils.ZoomPrefsStore.load(
                com.streamflixreborn.streamflix.utils.ZoomPrefsStore.LAST_KEY
            ) ?: com.streamflixreborn.streamflix.utils.ZoomPrefsStore.extractKey(server, video.source)
                ?.let { com.streamflixreborn.streamflix.utils.ZoomPrefsStore.load(it) }
            if (saved != null) {
                binding.pvPlayer.videoSurfaceView?.let { vv ->
                    vv.scaleX = saved.first
                    vv.scaleY = saved.second
                    // Désactiver le clipping pour que le scale>1 soit visible
                    var current: View? = binding.pvPlayer
                    while (current != null) {
                        (current as? ViewGroup)?.let { it.clipChildren = false; it.clipToPadding = false }
                        val p = current.parent
                        current = if (p is View) p else null
                    }
                    Log.d("PlayerMobileFragment", "Zoom collant appliqué: scaleX=${saved.first}, scaleY=${saved.second}")
                }
            }
        } catch (e: Exception) {
            Log.w("PlayerMobileFragment", "Zoom auto-apply failed: ${e.message}")
        }

        // Enable auto-PiP on Android 12+ once playback starts
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            updatePipParams()
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(): PictureInPictureParams {
        val isPlaying = ::player.isInitialized && player.isPlaying
        val actions = mutableListOf<RemoteAction>()

        // 2026-06-22 (user "en lecteur réduit PiP, les icônes pause, avance,
        //   recule ne répondent pas") : sur Android 13+, registerReceiver avec
        //   RECEIVER_NOT_EXPORTED bloque la livraison d'Intents IMPLICITES
        //   (= sans setPackage). On rend les intents EXPLICITES en ajoutant
        //   le package name → Android livre le broadcast au receiver privé.
        val pkg = requireContext().packageName

        // Rewind 10s
        actions.add(
            RemoteAction(
                Icon.createWithResource(requireContext(), R.drawable.exo_styled_controls_rewind),
                getString(R.string.player_rewind),
                getString(R.string.player_rewind),
                PendingIntent.getBroadcast(
                    requireContext(), 3,
                    Intent(PIP_ACTION_REWIND).setPackage(pkg),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        )

        // Play or Pause
        if (isPlaying) {
            actions.add(
                RemoteAction(
                    Icon.createWithResource(requireContext(), R.drawable.exo_styled_controls_pause),
                    getString(R.string.player_pause),
                    getString(R.string.player_pause),
                    PendingIntent.getBroadcast(
                        requireContext(), 1,
                        Intent(PIP_ACTION_PAUSE).setPackage(pkg),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
            )
        } else {
            actions.add(
                RemoteAction(
                    Icon.createWithResource(requireContext(), R.drawable.exo_styled_controls_play),
                    getString(R.string.player_play),
                    getString(R.string.player_play),
                    PendingIntent.getBroadcast(
                        requireContext(), 2,
                        Intent(PIP_ACTION_PLAY).setPackage(pkg),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
            )
        }

        // Forward 10s
        actions.add(
            RemoteAction(
                Icon.createWithResource(requireContext(), R.drawable.exo_styled_controls_fastforward),
                getString(R.string.player_forward),
                getString(R.string.player_forward),
                PendingIntent.getBroadcast(
                    requireContext(), 4,
                    Intent(PIP_ACTION_FORWARD).setPackage(pkg),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        )

        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(actions)

        // 2026-06-21 (user "j'ai activé le paramètre PiP mais ça ne fonctionne
        //   pas") : sur Android 12+ (S), `setAutoEnterEnabled` contrôle si
        //   Android entre AUTOMATIQUEMENT en PiP quand l'user quitte l'activité.
        //   Sans cette gate, Android pouvait shortcircuit notre onUserLeaveHint
        //   et auto-enter PiP même quand UserPreferences.pipOnExit = false.
        //   On la lie maintenant à la pref user.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(UserPreferences.pipOnExit)
        }

        return builder.build()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun updatePipParams() {
        try {
            requireActivity().setPictureInPictureParams(buildPipParams())
        } catch (_: Exception) {}
    }

    // 2026-06-21 (user "double-clic à gauche = -10s, à droite = +10s") :
    //   Overlay flottant qui apparaît brièvement après un double-tap seek.
    //   Affiche un TextView semi-transparent rond avec "⏪ -10s" ou "+10s ⏩".
    //   Auto-hide après ~600ms.
    private var doubleTapSeekOverlay: TextView? = null
    private var doubleTapSeekHideJob: kotlinx.coroutines.Job? = null
    private fun showDoubleTapSeekOverlay(side: String) {
        if (_binding == null) return
        val ctx = context ?: return
        val dp = ctx.resources.displayMetrics.density
        if (doubleTapSeekOverlay == null) {
            val tv = TextView(ctx).apply {
                gravity = android.view.Gravity.CENTER
                textSize = 18f
                setTextColor(android.graphics.Color.WHITE)
                setPadding((20 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt(), (16 * dp).toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 48 * dp
                    setColor(0xCC000000.toInt())
                }
                elevation = 12 * dp
                isClickable = false
                isFocusable = false
            }
            // Attache l'overlay au parent du PlayerView (= FrameLayout/ConstraintLayout).
            val parent = binding.pvPlayer.parent as? android.view.ViewGroup
            if (parent is androidx.constraintlayout.widget.ConstraintLayout) {
                val lp = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                    androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.WRAP_CONTENT,
                    androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.WRAP_CONTENT,
                )
                lp.topToTop = binding.pvPlayer.id
                lp.bottomToBottom = binding.pvPlayer.id
                tv.layoutParams = lp
                tv.id = View.generateViewId()
                parent.addView(tv)
                // Positionnement horizontal défini après dans show (gauche/droite).
                doubleTapSeekOverlay = tv
            } else if (parent is android.widget.FrameLayout) {
                val lp = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                )
                lp.gravity = android.view.Gravity.CENTER_VERTICAL
                tv.layoutParams = lp
                parent.addView(tv)
                doubleTapSeekOverlay = tv
            } else return // parent non supporté → abandon silencieux
        }
        val tv = doubleTapSeekOverlay ?: return
        tv.text = if (side == "left") "⏪  -10s" else "+10s  ⏩"
        // Repositionne selon le côté
        val parent = tv.parent as? android.view.ViewGroup ?: return
        when (val lp = tv.layoutParams) {
            is androidx.constraintlayout.widget.ConstraintLayout.LayoutParams -> {
                if (side == "left") {
                    lp.startToStart = binding.pvPlayer.id
                    lp.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                    lp.marginStart = (32 * dp).toInt()
                } else {
                    lp.endToEnd = binding.pvPlayer.id
                    lp.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                    lp.marginEnd = (32 * dp).toInt()
                }
                tv.layoutParams = lp
            }
            is android.widget.FrameLayout.LayoutParams -> {
                lp.gravity = android.view.Gravity.CENTER_VERTICAL or
                    (if (side == "left") android.view.Gravity.START else android.view.Gravity.END)
                lp.marginStart = (32 * dp).toInt()
                lp.marginEnd = (32 * dp).toInt()
                tv.layoutParams = lp
            }
        }
        tv.alpha = 0f
        tv.visibility = View.VISIBLE
        tv.animate().alpha(1f).setDuration(120).start()
        doubleTapSeekHideJob?.cancel()
        doubleTapSeekHideJob = viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.delay(500)
            tv.animate().alpha(0f).setDuration(180).withEndAction {
                if (_binding != null) tv.visibility = View.GONE
            }.start()
        }
    }

    private fun enterPIPMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            binding.pvPlayer.useController = false

            // Register PiP action receiver
            val filter = IntentFilter().apply {
                addAction(PIP_ACTION_PLAY)
                addAction(PIP_ACTION_PAUSE)
                addAction(PIP_ACTION_REWIND)
                addAction(PIP_ACTION_FORWARD)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requireContext().registerReceiver(pipActionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    requireContext().registerReceiver(pipActionReceiver, filter)
                }
            } catch (_: Exception) {}

            requireActivity().enterPictureInPictureMode(buildPipParams())
        }
    }


    private fun ExoPlayer.hasStarted(): Boolean {
        return (this.currentPosition > (this.duration * 0.005) || this.currentPosition > 20.seconds.inWholeMilliseconds)
    }

    private fun ExoPlayer.hasFinished(): Boolean {
        return (this.currentPosition > (this.duration * 0.90))
    }

    private fun ExoPlayer.hasReallyFinished(): Boolean {
        return this.duration > 0 &&
                this.currentPosition >= (this.duration - UserPreferences.autoplayBuffer * 1000)
    }

    private fun currentVideoTypeForUi(): Video.Type = when (val type = args.videoType) {
        is Video.Type.Episode -> EpisodeManager.getCurrentEpisode()
            ?.takeIf { currentEpisode -> currentEpisode.id == type.id }
            ?: type
        is Video.Type.Movie -> type
    }

    private fun resolvePlayerTitle(videoType: Video.Type = currentVideoTypeForUi()): String {
        return when (videoType) {
            is Video.Type.Movie -> videoType.title
            is Video.Type.Episode -> videoType.tvShow.title.ifBlank { args.title }
        }
    }

    private fun resolvePlayerSubtitle(videoType: Video.Type = currentVideoTypeForUi()): String {
        return when (videoType) {
            is Video.Type.Movie -> args.subtitle
            is Video.Type.Episode -> {
                val episodeTitle = videoType.title?.takeUnless { it.isBlank() } ?: args.subtitle
                "S${videoType.season.number} E${videoType.number}  •  $episodeTitle"
            }
        }
    }

    private fun updatePlayerHeader(videoType: Video.Type = currentVideoTypeForUi()) {
        binding.pvPlayer.controller.binding.tvExoTitle.text = resolvePlayerTitle(videoType)
        binding.pvPlayer.controller.binding.tvExoSubtitle.text = resolvePlayerSubtitle(videoType)
    }

    private fun queueNextEpisodeForContinueWatching(provider: com.streamflixreborn.streamflix.providers.Provider) {
        val nextEpisode = EpisodeManager.peekNextEpisode() ?: return
        val episodeDao = database.episodeDao()
        val persistedNextEpisode = episodeDao.getById(nextEpisode.id)?.apply {
            isWatched = false
            watchedDate = null
            watchHistory = WatchItem.WatchHistory(
                lastEngagementTimeUtcMillis = System.currentTimeMillis(),
                lastPlaybackPositionMillis = 0L,
                durationMillis = 0L,
            )
        } ?: Episode(
            id = nextEpisode.id,
            number = nextEpisode.number,
            title = nextEpisode.title,
            poster = nextEpisode.poster,
            overview = nextEpisode.overview,
            tvShow = database.tvShowDao().getById(nextEpisode.tvShow.id) ?: TvShow(
                id = nextEpisode.tvShow.id,
                title = nextEpisode.tvShow.title,
                poster = nextEpisode.tvShow.poster,
                banner = nextEpisode.tvShow.banner,
            ),
            season = Season(
                number = nextEpisode.season.number,
                title = nextEpisode.season.title,
            ),
        ).apply {
            isWatched = false
            watchedDate = null
            watchHistory = WatchItem.WatchHistory(
                lastEngagementTimeUtcMillis = System.currentTimeMillis(),
                lastPlaybackPositionMillis = 0L,
                durationMillis = 0L,
            )
        }

        episodeDao.save(persistedNextEpisode)
        UserDataCache.syncEpisodeToCache(requireContext(), provider, persistedNextEpisode)
    }
    private fun startProgressHandler() {
        progressHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var liveCheckCounter = 0
        // 2026-05-09 PISTE A : tracking du drain buffer (cf PlayerTvFragment).
        var lastAheadSec = -1
        var consecutiveDrainTicks = 0
        // 2026-05-10 : préemptif requiert buffer sain (>=15s) au moins une fois.
        var bufferEverHealthy = false
        progressRunnable = Runnable {
            if (player.isPlaying) {
                val isLiveIptv = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
                    args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
                    args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
                    args.id.startsWith("livehub::") || args.id.startsWith("sportlive::") ||
                    args.id.startsWith("match::") || args.id.startsWith("vavoo::") || args.id.startsWith("myiptv-live::")
                if (!isLiveIptv) {
                    val show = player.currentPosition in 3000..120000
                    showSkipIntroButton(show)
                    updateNextEpisodeOverlay()
                } else {
                    liveCheckCounter++
                    val pos = player.currentPosition
                    val buf = player.bufferedPosition
                    val ahead = (buf - pos).coerceAtLeast(0)
                    val aheadSec = (ahead / 1000).toInt()
                    if (liveCheckCounter >= 5) {
                        liveCheckCounter = 0
                        Log.d("PlayerMobileFragment", "Live buffer: pos=${pos/1000}s buf=${buf/1000}s ahead=${aheadSec}s")
                    }
                    // Mobile port v51 : dual-bar visuel — barre 1 (lecture) + barre 2 (pré-chargé).
                    //   Cycle wall-clock 60s. visualBoost +60s quand backup en queue (bars pleines).
                    try {
                        val ctrl = binding.pvPlayer.controller.binding.root
                        val stdBar = ctrl.findViewById<View>(R.id.exo_progress)
                        val pri = ctrl.findViewById<androidx.media3.ui.DefaultTimeBar>(R.id.live_primary_progress)
                        val sec = ctrl.findViewById<androidx.media3.ui.DefaultTimeBar>(R.id.live_secondary_progress)
                        // 2026-06-19 (user "ma barre était purement visuelle. Tu remets
                        //   la même barre que pour les VOD") : on remet la barre standard
                        //   ExoPlayer (= exo_progress) sur live AUSSI, comme VOD. Les 2
                        //   barres custom (live_primary/secondary) deviennent permanent GONE.
                        //   Le swap réel se fait en interne via ExoPlayer.mediaItemCount,
                        //   pas via le visuel. Cohérent VOD/Live + 1 seule barre.
                        if (stdBar != null && stdBar.visibility != View.VISIBLE) stdBar.visibility = View.VISIBLE
                        if (pri != null && pri.visibility != View.GONE) pri.visibility = View.GONE
                        if (sec != null && sec.visibility != View.GONE) sec.visibility = View.GONE
                        if (pri != null && sec != null) {
                            liveCumulativePlaybackMs += 1000L
                            smoothedAheadMs = if (ahead > smoothedAheadMs) ahead
                                else (smoothedAheadMs - 1000L).coerceAtLeast(ahead).coerceAtLeast(0L)
                            val hasBackupQueued = try { player.mediaItemCount > 1 } catch (_: Exception) { false }
                            val visualBoost = if (hasBackupQueued) 60_000L else 0L
                            val visualAhead = smoothedAheadMs + visualBoost
                            val chunkMs = 60_000L
                            val pairMs = 2 * chunkMs
                            val cumPos = liveCumulativePlaybackMs
                            val pairStart = (cumPos / pairMs) * pairMs
                            val posInPair = cumPos - pairStart
                            val phaseA = posInPair < chunkMs
                            if (phaseA) {
                                pri.setDuration(chunkMs); pri.setPosition(posInPair)
                                pri.setBufferedPosition((posInPair + visualAhead).coerceAtMost(chunkMs))
                                pri.alpha = 1f
                                sec.setDuration(chunkMs); sec.setPosition(0)
                                val bar1Remaining = chunkMs - posInPair
                                sec.setBufferedPosition((visualAhead - bar1Remaining).coerceAtLeast(0L).coerceAtMost(chunkMs))
                                sec.alpha = 0.6f
                            } else {
                                val bar2Pos = posInPair - chunkMs
                                sec.setDuration(chunkMs); sec.setPosition(bar2Pos)
                                sec.setBufferedPosition((bar2Pos + visualAhead).coerceAtMost(chunkMs))
                                sec.alpha = 1f
                                val bar2Remaining = chunkMs - bar2Pos
                                pri.setDuration(chunkMs); pri.setPosition(0)
                                pri.setBufferedPosition((visualAhead - bar2Remaining).coerceAtLeast(0L).coerceAtMost(chunkMs))
                                pri.alpha = 0.6f
                            }
                        }
                    } catch (_: Exception) {}
                    // PISTE A : détection drain buffer (5 ticks consécutifs en baisse + ahead<25s)
                    if (lastAheadSec >= 0 && aheadSec < lastAheadSec) {
                        consecutiveDrainTicks++
                    } else {
                        consecutiveDrainTicks = 0
                    }
                    lastAheadSec = aheadSec
                    if (aheadSec >= 15) bufferEverHealthy = true
                    // v72c HOTFIX (user "lecture ne fonctionne plus sur mobile") :
                    //   LAZY BACKUP DÉSACTIVÉ — Mobile utilise ProgressiveMediaSource
                    //   pour les streams live IPTV (vs HlsMediaSource TV). Ajouter un
                    //   2e MediaItem = ouvrir 2e connexion HTTP → serveur Vegeta renvoie
                    //   HTTP 444 (kicked) → playback meurt.
                    //   Conséquence : pas de swap doux possible sur Mobile (pas de backup
                    //   en queue), PREEMPTIVE fallback sur displayVideo destructif.
                    //   Le visual dual-bar fonctionne quand même (boost +60s reste à 0).
                    if (consecutiveDrainTicks >= 5 && aheadSec < 12 && bufferEverHealthy &&
                        iptvCurrentStreamHasWorked && !preemptiveReloadInFlight) {
                        preemptiveReloadInFlight = true
                        consecutiveDrainTicks = 0
                        val cs = currentServer
                        val cv = currentVideo
                        val hasBackup = try { player.mediaItemCount > 1 && player.hasNextMediaItem() } catch (_: Exception) { false }
                        if (hasBackup) {
                            // Mobile port v52+v54+v58 : smooth swap au lieu de reload destructif
                            Log.w("PlayerMobileFragment",
                                "PREEMPTIVE swap JUMELAGE — buffer ${aheadSec}s → swapToNextWithAudioFade")
                            viewLifecycleOwner.lifecycleScope.launch {
                                try {
                                    swapToNextWithAudioFade(player)
                                } catch (e: Exception) {
                                    Log.w("PlayerMobileFragment", "swapToNextWithAudioFade failed: ${e.message}")
                                }
                                kotlinx.coroutines.delay(10_000L)
                                preemptiveReloadInFlight = false
                            }
                        } else if (cs != null && cv != null) {
                            // Fallback path destructif (pas de backup en queue — myiptv-live, ou démarrage)
                            Log.w("PlayerMobileFragment",
                                "PREEMPTIVE reload (CRITIQUE) — buffer ${aheadSec}s, pas de backup → displayVideo")
                            try {
                                binding.pvPlayer.controller.hide()
                            } catch (_: Exception) {}
                            viewLifecycleOwner.lifecycleScope.launch {
                                try {
                                    displayVideo(cv, cs)
                                } catch (e: Exception) {
                                    Log.w("PlayerMobileFragment", "Preemptive displayVideo failed: ${e.message}")
                                }
                                kotlinx.coroutines.delay(20_000L)
                                preemptiveReloadInFlight = false
                            }
                        } else {
                            preemptiveReloadInFlight = false
                        }
                    }
                }
            }
            progressHandler.postDelayed(progressRunnable, 1000)
        }
        progressHandler.post(progressRunnable)
    }

    /** 2026-05-09 v3 : best practices HLS live — speed subtil 0.95-1.05,
     *  cible 25s derrière live edge. */
    private fun adjustLivePlaybackSpeed() {
        val offset = player.currentLiveOffset
        if (offset == androidx.media3.common.C.TIME_UNSET) return
        val targetSpeed = when {
            offset < 10_000 -> 0.95f
            offset < 20_000 -> 0.98f
            offset > 50_000 -> 1.05f
            offset > 35_000 -> 1.02f
            else -> 1.0f
        }
        val current = player.playbackParameters.speed
        if (kotlin.math.abs(current - targetSpeed) > 0.005f) {
            player.setPlaybackSpeed(targetSpeed)
            Log.d("PlayerLiveOffset", "offset=${offset/1000}s → speed=${targetSpeed}x")
        }
    }

    private fun stopProgressHandler() {
        if (::progressHandler.isInitialized) {
            progressHandler.removeCallbacks(progressRunnable)
        }
        proactiveRefreshHandler?.removeCallbacksAndMessages(null)
        proactiveRefreshHandler = null
    }

    // 2026-05-09 v13 : refresh proactif du token Stalker (Vegeta/Ola) toutes
    // les 3 min, AVANT expiry → cut court et prévisible vs 25s subi.
    private var proactiveRefreshHandler: android.os.Handler? = null
    @Volatile private var emergencyRefreshInFlight = false
    // 2026-05-09 PISTE A : flag anti-reload-en-rafale pour le préemptif drain.
    @Volatile private var preemptiveReloadInFlight = false
    // 2026-05-20 (parité PlayerTvFragment) : reprise live BUFFERING silencieux +
    //   anti-flap partagé (stop si trop de reloads en peu de temps → évite le
    //   crash MediaCodec sur empilement de reloads).
    @Volatile private var transferLiveRecoveryActive: Boolean = false
    private val recentReloadTimestamps = ArrayDeque<Long>()
    private val RELOAD_FLAP_THRESHOLD = 3
    private val RELOAD_FLAP_WINDOW_MS = 15_000L
    // Mobile port v51 : state pour dual-bar visuel live IPTV (1:1 avec TV)
    @Volatile private var smoothedAheadMs: Long = 0L
    @Volatile private var liveCumulativePlaybackMs: Long = 0L
    @Volatile private var lastAutoSwitchTime = 0L

    private fun scheduleProactiveTokenRefresh() {
        val server = currentServer ?: return
        val isStalker = server.id.startsWith("vegeta_stream::") ||
            server.id.startsWith("ola_stream::")
        if (!isStalker) return

        proactiveRefreshHandler?.removeCallbacksAndMessages(null)
        proactiveRefreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
        proactiveRefreshHandler?.postDelayed({
            val current = currentServer
            if (current != null && _binding != null) {
                Log.d("PlayerMobileFragment", "Proactive token refresh (60s) — fresh handshake")
                viewLifecycleOwner.lifecycleScope.launch {
                    val newServer = withContext(Dispatchers.IO) {
                        when {
                            current.id.startsWith("vegeta_stream::") ->
                                com.streamflixreborn.streamflix.providers.VegetaTvProvider.refreshServerUrl(current)
                            current.id.startsWith("ola_stream::") ->
                                com.streamflixreborn.streamflix.providers.OlaTvProvider.refreshServerUrl(current)
                            else -> null
                        }
                    }
                    if (newServer != null && _binding != null) {
                        viewModel.getVideo(newServer)
                    }
                }
                scheduleProactiveTokenRefresh()
            }
        }, 60 * 1000L)
    }

    private fun updateNextEpisodeOverlay() {
        val currentEpisode = currentVideoTypeForUi() as? Video.Type.Episode ?: run {
            hideNextEpisodeOverlay()
            return
        }
        val duration = player.duration.takeIf { it > 0 } ?: run {
            hideNextEpisodeOverlay()
            return
        }
        val remainingMs = (duration - player.currentPosition).coerceAtLeast(0L)

        if (nextEpisodeOverlayDismissed) {
            hideNextEpisodeOverlay()
            return
        }

        // 2026-06-14 (user "ajoute option pour désactiver l'overlay
        //   épisode suivant, pour ceux qui veulent aller jusqu'à la fin") :
        //   respect du toggle Settings → Paramètres du lecteur.
        if (!UserPreferences.showNextEpisodeOverlay) {
            hideNextEpisodeOverlay()
            return
        }

        if (remainingMs <= NEXT_EPISODE_PREFETCH_THRESHOLD_MS) {
            ensureNextEpisodePrepared(currentEpisode)
        }

        val nextEpisode = EpisodeManager.peekNextEpisode()
        val overlayThresholdMs = maxOf(
            NEXT_EPISODE_OVERLAY_MIN_THRESHOLD_MS,
            UserPreferences.autoplayBuffer * 1000L
        )
        if (nextEpisode == null || remainingMs == 0L || remainingMs > overlayThresholdMs) {
            hideNextEpisodeOverlay()
            return
        }

        showNextEpisodeOverlay(nextEpisode, remainingMs)
    }

    private fun ensureNextEpisodePrepared(currentEpisode: Video.Type.Episode) {
        if (EpisodeManager.peekNextEpisode() != null) return
        if (nextEpisodePrefetchTargetId == currentEpisode.id && nextEpisodePrefetchJob?.isActive == true) {
            return
        }

        nextEpisodePrefetchTargetId = currentEpisode.id
        nextEpisodePrefetchJob?.cancel()
        nextEpisodePrefetchJob = lifecycleScope.launch(Dispatchers.IO) {
            val loaded = EpisodeManager.ensureNextEpisodeAvailable(currentEpisode, database)
            withContext(Dispatchers.Main) {
                if (!isAdded || _binding == null) return@withContext
                setupEpisodeNavigationButtons()
                if (loaded && player.isPlaying) {
                    updateNextEpisodeOverlay()
                }
            }
        }
    }

    private fun showNextEpisodeOverlay(nextEpisode: Video.Type.Episode, remainingMs: Long) {
        binding.tvNextEpisodeMeta.text = getString(
            R.string.tv_show_item_season_number_episode_number,
            nextEpisode.season.number,
            nextEpisode.number
        )
        binding.tvNextEpisodeTitle.text = nextEpisode.title
            ?: getString(R.string.episode_number, nextEpisode.number)
        binding.tvNextEpisodeCountdown.text = if (UserPreferences.autoplay) {
            getString(
                R.string.player_next_episode_autoplay_in,
                ((remainingMs + 999L) / 1000L).toInt()
            )
        } else {
            getString(R.string.player_next_episode_ready)
        }

        Glide.with(this)
            .load(com.streamflixreborn.streamflix.utils.optimizeArtworkUrl(nextEpisode.poster ?: nextEpisode.tvShow.poster, 400))
            .error(R.drawable.glide_fallback_cover)
            .fallback(R.drawable.glide_fallback_cover)
            .centerCrop()
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(binding.ivNextEpisodePoster)

        if (binding.layoutNextEpisodeOverlay.isGone) {
            val fadeIn = android.view.animation.AnimationUtils.loadAnimation(requireContext(), R.anim.fade_in)
            binding.layoutNextEpisodeOverlay.startAnimation(fadeIn)
            binding.layoutNextEpisodeOverlay.isVisible = true
        }
    }

    private fun hideNextEpisodeOverlay() {
        if (_binding == null) return
        if (binding.layoutNextEpisodeOverlay.isVisible) {
            val fadeOut = android.view.animation.AnimationUtils.loadAnimation(requireContext(), R.anim.fade_out)
            binding.layoutNextEpisodeOverlay.startAnimation(fadeOut)
            binding.layoutNextEpisodeOverlay.isGone = true
        }
    }

    private fun showSkipIntroButton(show: Boolean) {
        val btnSkipIntro = binding.pvPlayer.controller.binding.btnSkipIntro
        if (show && btnSkipIntro.isGone) {
            val fadeIn = android.view.animation.AnimationUtils.loadAnimation(requireContext(), R.anim.fade_in)
            btnSkipIntro.startAnimation(fadeIn)
            btnSkipIntro.isVisible = true
        } else if (!show && btnSkipIntro.isVisible) {
            val fadeOut = android.view.animation.AnimationUtils.loadAnimation(requireContext(), R.anim.fade_out)
            btnSkipIntro.startAnimation(fadeOut)
            btnSkipIntro.isGone = true
        }
    }



    override fun onPause() {
        super.onPause()
        stopProgressHandler()
        hideNextEpisodeOverlay()
    }

    private var currentExtraBuffering = false
    private var currentSoftwareDecoder = false

    private fun buildPlayer(extraBuffering: Boolean): ExoPlayer {
        // 2026-05-09 : ajout des préfixes IPTV oubliés (livehub::/sportlive::/match::)
        // 2026-05-11 : ajout vavoo:: — sans ça l'image figeait 4s sur Oppo car
        // buildPlayer utilisait le LoadControl VOD (bufferForPlayback 1.5s) au lieu
        // du LoadControl IPTV (10s) → quand le buffer vidéo se vidait brièvement,
        // décodeur c2.mtk.avc.decoder à 0 fps pendant 4s.
        val isLiveIptv = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
            args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
            args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
            args.id.startsWith("livehub::") || args.id.startsWith("sportlive::") ||
            args.id.startsWith("match::") || args.id.startsWith("vavoo::") || args.id.startsWith("myiptv-live::")
        // Per user request: precharge as much as possible so the live stream
        // never cuts. Bigger buffer windows + longer rebuffer threshold.
        // 2026-05-09 v11 : recovery ULTRA RAPIDE après cut (1s).
        // 2026-06-04 (capture APK OTF TV V3.2 décompilé) : OTF utilise les
        //   constructeurs PAR DÉFAUT — aucun setBufferDurationsMs, aucun
        //   setEnableDecoderFallback, aucun setExtensionRendererMode dans
        //   l'APK officiel. Donc pour reproduire EXACTEMENT son comportement
        //   on utilise DefaultLoadControl() sans config + DefaultRenderersFactory
        //   sans setter (plus bas).
        val isOtfForLoadCtrl = args.id.startsWith("livehub::otf::") ||
            (currentServer?.id?.startsWith("livehub::otf::") == true)
        val loadControl = if (isOtfForLoadCtrl) {
            DefaultLoadControl()  // Strictement comme OTF TV V3.2 : aucun setter
        } else if (isLiveIptv) {
            // 2026-05-20 (parité PlayerTvFragment) : aligné sur les valeurs TV
            //   tunées — min 30→60s (plus d'avance, moins de cuts), start 10→1s
            //   (démarrage 10× plus rapide), rebuffer 1000→500ms (reprise 2× plus
            //   vite). L'ancien 30s/10s était périmé côté mobile.
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    60_000,
                    300_000,
                    1_000,
                    500
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        } else {
            // v65 (aligned with PlayerTvFragment) : bump VOD buffer.
            //   min 30→60s, max 120→300s (600 extra), start 1.5→1s, rebuffer 3→1.5s.
            //   Films/séries n'arrivent plus en bout de buffer = moins de "rame".
            // 2026-06-09 (user "ça a rien changé tu peux remettre comme avant") :
            //   restauration après test ami.
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    60_000,
                    if (extraBuffering) 600_000 else 300_000,
                    1_000,
                    1_500
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        }

        val baseBuilder = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1 && !currentSoftwareDecoder) {
            ExoPlayer.Builder(requireContext())
        } else {
            // 2026-06-10 : respecte le toggle Settings "Forcer le décodeur logiciel".
            //   OFF par défaut = HW prioritaire. ON = software FFmpeg.
            val prefSw = com.streamflixreborn.streamflix.utils.UserPreferences.preferSoftwareDecoder
            val mode = if (currentSoftwareDecoder || prefSw)
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            else
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            Log.d("PlayerMobileFragment",
                "NextRenderersFactory mode=$mode (prefSw=$prefSw, swDec=$currentSoftwareDecoder, isLiveIptv=$isLiveIptv)")
            val renderersFactory = io.github.anilbeesetti.nextlib.media3ext.ffdecoder
                .NextRenderersFactory(requireContext()).apply {
                    setEnableDecoderFallback(true)
                    setExtensionRendererMode(mode)
                }
            ExoPlayer.Builder(requireContext(), renderersFactory)
        }

        return baseBuilder
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .build()
    }

    /**
     * Pre-install Cronet from Play Services so CronetEngine.Builder uses
     * Chrome's real BoringSSL stack (identical JA3 fingerprint to Chrome).
     * Called once in onViewCreated — by the time a video loads, it's ready.
     */
    private fun initCronetEngine() {
        CronetProviderInstaller.installProvider(requireContext())
            .addOnSuccessListener {
                try {
                    cronetEngine = CronetEngine.Builder(requireContext())
                        .enableQuic(true)
                        .enableHttp2(true)
                        .build()
                    Log.d("PlayerNetwork", "Cronet engine pre-initialized: ${cronetEngine?.javaClass?.name}")
                } catch (e: Exception) {
                    Log.e("PlayerNetwork", "Cronet engine build failed after provider install: ${e.message}")
                }
            }
            .addOnFailureListener { e ->
                Log.w("PlayerNetwork", "CronetProviderInstaller failed: ${e.message} — will try native Cronet on demand")
            }
    }

    private fun needsCronet(url: String): Boolean {
        // Cronet uses Chrome's TLS stack (JA3 fingerprint matches real browsers)
        // These CDNs reject non-Chromium TLS fingerprints (OkHttp → 404/403)
        // 2026-05-19 v85k : Uqload (strm*.uqload.is) + Hydrax (abyssa.cc/abysscdn)
        //   font du JA3 fingerprinting strict. Test bash curl = 200 OK depuis meme IP
        //   source que telephone (202.90.76.81). Mais OkHttp Android = 403. Donc TLS
        //   fingerprint Android Conscrypt detecte et blacklisted. Cronet (Chromium TLS)
        //   bypass car identique a un vrai Chrome.
        // 2026-07-06 : vidzy.cc/vidzy.to = CDN Vidmoly alternatif, même
        // JA3 que vidzy.live. vmwesa.online = autre CDN Vidmoly (rotation).
        // Sans Cronet → UnknownHostException sur Chromecast (DNS système KO).
        return url.contains("vidzy.", ignoreCase = true)
            || url.contains("vmwesa.online", ignoreCase = true)
            || url.contains("cfglobalcdn.com", ignoreCase = true)
            || url.contains("anime-sama.", ignoreCase = true)
            || url.contains("uqload.is", ignoreCase = true)
            || url.contains("uqload.cx", ignoreCase = true)
            || url.contains("uqload.co", ignoreCase = true)
            || url.contains("uqload.to", ignoreCase = true)
            || url.contains("uqload.net", ignoreCase = true)
            || url.contains("strm5.uqload", ignoreCase = true)
            || url.contains("strm.uqload", ignoreCase = true)
            // 2026-05-20 (parité TV) : substrings élargies (abyssa / abysscdn) pour
            //   couvrir tous les TLD Hydrax (.cc/.io/.net…), comme PlayerTvFragment.
            || url.contains("abyssa", ignoreCase = true)
            || url.contains("abysscdn", ignoreCase = true)
            // 2026-07-09 : Nakios CDN (sv.citron-edge.lol) fait du JA3 fingerprinting
            //   strict. DefaultHttpDataSource (TLS Java) → redirect Telegram (anti-hotlink).
            //   Cronet (TLS Chrome) + Referer nakios.store → 206 OK.
            || url.contains("citron-edge", ignoreCase = true)
    }

    private fun needsDoH(url: String): Boolean {
        // sprintcdn/r66nv9ed.com: Filemoon CDN — system DNS resolves to wrong edge,
        // token is edge-bound so we need DoH to hit the correct server
        // cloudatacdn.com: Dood final CDN — DefaultHttpDataSource breaks after
        // ~4s with "UnknownHostException (no network)" mid-stream because the
        // HttpURLConnection keepalive drops; OkHttp + DoH keeps the connection
        // alive and retries cleanly.
        // cdndirector.dailymotion.com: Dailymotion HLS — token `sec=` est signé
        // sur la route IP/DNS qui a fait l'appel JSON. Notre extracteur passe
        // par OkHttp+DoH mais le DefaultHttpDataSource (HttpURLConnection +
        // DNS système) résout sur un autre edge -> token rejeté avec 403
        // (vu sur OPPO + NordVPN actif, 4 mai 2026).
        // anime-sama.* : storage CDN (s5/s22.anime-sama.fr) DNS-bloqué chez
        // certains FAI (Tahiti satellite). DoH by-passe ça (vu en log Chromecast :
        // ERROR_CODE_IO_NETWORK_CONNECTION_FAILED + UnknownHostException).
        return url.contains("sprintcdn", ignoreCase = true)
            || url.contains("r66nv9ed.com", ignoreCase = true)
            || url.contains("cloudatacdn.com", ignoreCase = true)
            || url.contains("cdndirector.dailymotion.com", ignoreCase = true)
            || url.contains("dmcdn.net", ignoreCase = true)
            || url.contains("anime-sama.", ignoreCase = true)
    }

    // 2026-05-20 (parité PlayerTvFragment) : détection émulateur (BlueStacks inclus)
    //   pour basculer Cronet → OkHttp/DoH quand Cronet ne joint pas les CDN.
    private val isEmulator: Boolean by lazy {
        val buildCheck = (android.os.Build.FINGERPRINT.contains("generic", ignoreCase = true)
                || android.os.Build.MODEL.contains("Emulator", ignoreCase = true)
                || android.os.Build.MODEL.contains("Android SDK", ignoreCase = true)
                || android.os.Build.MANUFACTURER.contains("BlueStacks", ignoreCase = true)
                || android.os.Build.BOARD.contains("goldfish", ignoreCase = true)
                || android.os.Build.HARDWARE.contains("ranchu", ignoreCase = true)
                || android.os.Build.PRODUCT.contains("sdk", ignoreCase = true)
                || android.os.Build.PRODUCT.contains("vbox", ignoreCase = true))
        if (buildCheck) return@lazy true
        val blueStacksPackages = listOf(
            "com.bluestacks.BstCommandProcessor",
            "com.bluestacks.settings",
            "com.bluestacks.home",
            "com.bluestacks.appmart"
        )
        val pm = try { requireContext().packageManager } catch (_: Exception) { null }
        val isBlueStacks = pm != null && blueStacksPackages.any { pkg ->
            try { pm.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }
        }
        val bstVersion = try {
            @Suppress("PrivateApi")
            val clazz = Class.forName("android.os.SystemProperties")
            val get = clazz.getMethod("get", String::class.java, String::class.java)
            (get.invoke(null, "ro.bst.version", "") as? String)?.isNotEmpty() == true
        } catch (_: Exception) { false }
        isBlueStacks || bstVersion
    }

    private fun needsBrowserOkHttp(url: String): Boolean {
        // v85s 2026-05-20: TOUT passe par Cronet pour Uqload maintenant (extract + play
        // memes Chromium TLS = token strm5 issued + accepted)
        //   l'embed. UqloadExtractor utilise OkHttp ; donc le player DOIT aussi utiliser
        //   OkHttp (avec la MEME instance, partage via UqloadExtractor.sharedOkHttpClient)
        //   pour que les fingerprints matchent. Sinon 403 sur master.m3u8.
        return false
    }

    /**
     * Creates the right DataSource factory for [videoUrl].
     * - vidzy.live → Cronet (Chrome network stack, needed for JA3 bypass)
     * - cfglobalcdn.com → OkHttp + DoH (ISP DNS blocks, need CNAME chain resolution)
     * - tnmr.org/luluvdo → OkHttp with full browser headers (CDN requires browser-like requests)
     * - everything else → DefaultHttpDataSource (system DNS, most compatible)
     */
    private fun createHttpDataSourceFactory(videoUrl: String = ""): HttpDataSource.Factory {
        if (!needsCronet(videoUrl)) {
            if (needsDoH(videoUrl)) {
                Log.d("PlayerNetwork", "URL needs DoH for CNAME resolution ($videoUrl)")
                return createDoHOkHttpDataSourceFactory()
            }
            if (needsBrowserOkHttp(videoUrl)) {
                Log.d("PlayerNetwork", "URL needs browser OkHttp ($videoUrl)")
                return createBrowserOkHttpDataSourceFactory(videoUrl)
            }
            Log.d("PlayerNetwork", "URL does not need Cronet ($videoUrl), using DefaultHttp")
            return createDefaultHttpDataSourceFactory()
        }
        // 2026-05-20 (parité PlayerTvFragment) : sur émulateur (ex BlueStacks),
        //   Cronet n'atteint souvent pas les CDN → fallback OkHttp/DoH.
        if (isEmulator) {
            Log.d("PlayerNetwork", "Emulator detected (${android.os.Build.MANUFACTURER}/${android.os.Build.MODEL}), using fallback")
            return if (needsDoH(videoUrl)) createDoHOkHttpDataSourceFactory() else createDefaultHttpDataSourceFactory()
        }
        // Use pre-initialized engine from Play Services, or build one on-demand
        val engine = cronetEngine ?: try {
            Log.d("PlayerNetwork", "Cronet engine not pre-initialized, building on demand...")
            CronetEngine.Builder(requireContext())
                .enableQuic(true)
                .enableHttp2(true)
                .build()
        } catch (e: Exception) {
            Log.e("PlayerNetwork", "Cronet completely unavailable: ${e.message}", e)
            null
        }

        if (engine == null) {
            Log.w("PlayerNetwork", "No Cronet engine available, falling back to OkHttp")
            return createDefaultHttpDataSourceFactory()
        }

        Log.d("PlayerNetwork", "Using CronetDataSource (${engine.javaClass.simpleName}) for: ${videoUrl.take(80)}")
        usingCronet = true
        usingDoH = false
        usingBrowserOkHttp = false
        // 2026-05-19 v85k : Uqload + Hydrax exigent EXACTEMENT Chrome 131 Pixel 8.
        //   Le default Chrome 116 donne 403. Override pour ces hosts.
        val cronetUa = if (videoUrl.contains("uqload", ignoreCase = true) ||
                           videoUrl.contains("abyssa", ignoreCase = true) ||
                           videoUrl.contains("abysscdn", ignoreCase = true)) {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"  // v85x desktop UA match PC Chrome
        } else {
            NetworkClient.USER_AGENT
        }
        Log.d("PlayerNetwork", "v85k Cronet UA = $cronetUa")
        return CronetDataSource.Factory(engine, cronetExecutor)
            .setUserAgent(cronetUa)
            .setConnectionTimeoutMs(30_000)
            .setReadTimeoutMs(30_000)
    }

    /**
     * OkHttp DataSource with a custom DNS resolver that uses Cloudflare's JSON
     * DoH API to follow CNAME chains + TCP pre-check to detect ISP-blocked IPs fast.
     */
    private fun createDoHOkHttpDataSourceFactory(): HttpDataSource.Factory {
        usingCronet = false
        usingDoH = true
        usingBrowserOkHttp = false

        val jsonDohDns = object : okhttp3.Dns {
            private val fallback = DnsResolver.doh
            private val dnsClient = OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            /** Multiple DoH providers — different providers may return different IPs */
            private val dohProviders = listOf(
                "https://cloudflare-dns.com/dns-query",
                "https://dns.google/resolve",
                "https://dns.quad9.net:5053/dns-query"
            )

            /** Query a DoH provider and return all A record IPs + any CNAME targets found */
            private fun queryDoH(provider: String, name: String): Pair<List<String>, List<String>> {
                val request = okhttp3.Request.Builder()
                    .url("$provider?name=$name&type=A")
                    .header("Accept", "application/dns-json")
                    .build()
                val body = dnsClient.newCall(request).execute().use { it.body?.string() }
                    ?: return Pair(emptyList(), emptyList())

                val json = org.json.JSONObject(body)
                val answers = json.optJSONArray("Answer")
                    ?: return Pair(emptyList(), emptyList())

                val ips = mutableListOf<String>()
                val cnames = mutableListOf<String>()
                for (i in 0 until answers.length()) {
                    val answer = answers.getJSONObject(i)
                    when (answer.optInt("type")) {
                        1 -> ips.add(answer.optString("data"))   // A record
                        5 -> cnames.add(answer.optString("data").trimEnd('.')) // CNAME
                    }
                }
                return Pair(ips, cnames)
            }

            override fun lookup(hostname: String): List<java.net.InetAddress> {
                if (!hostname.contains("cfglobalcdn.com", ignoreCase = true)) {
                    return fallback.lookup(hostname)
                }
                Log.d("PlayerNetwork", "Multi-DoH lookup for: $hostname")
                try {
                    val allIps = linkedSetOf<String>() // preserve order, no duplicates
                    var cnameTarget: String? = null

                    // Phase 1: query all DoH providers for the cfglobalcdn hostname
                    for (provider in dohProviders) {
                        try {
                            val (ips, cnames) = queryDoH(provider, hostname)
                            Log.d("PlayerNetwork", "DoH ($provider): IPs=$ips, CNAMEs=$cnames")
                            allIps.addAll(ips)
                            if (cnames.isNotEmpty() && cnameTarget == null) {
                                cnameTarget = cnames.first()
                            }
                        } catch (e: Exception) {
                            Log.w("PlayerNetwork", "DoH provider $provider failed: ${e.message}")
                        }
                    }

                    // Phase 2: if CNAME found, also resolve CNAME target directly
                    // (might give different IPs than the flattened chain)
                    if (cnameTarget != null) {
                        Log.d("PlayerNetwork", "CNAME chain: $hostname → $cnameTarget, resolving target...")
                        for (provider in dohProviders) {
                            try {
                                val (ips, _) = queryDoH(provider, cnameTarget!!)
                                if (ips.isNotEmpty()) {
                                    Log.d("PlayerNetwork", "CNAME target $cnameTarget via $provider: $ips")
                                    allIps.addAll(ips)
                                }
                            } catch (_: Exception) {}
                        }
                    }

                    if (allIps.isEmpty()) throw Exception("No IPs from any DoH provider")
                    Log.d("PlayerNetwork", "All candidate IPs for $hostname: $allIps")

                    // Phase 3: TCP pre-check all unique IPs
                    val reachable = mutableListOf<java.net.InetAddress>()
                    val unreachable = mutableListOf<java.net.InetAddress>()
                    for (ipStr in allIps) {
                        val ip = java.net.InetAddress.getByName(ipStr)
                        try {
                            val socket = java.net.Socket()
                            socket.connect(java.net.InetSocketAddress(ip, 443), 3000)
                            socket.close()
                            Log.d("PlayerNetwork", "TCP OK: $hostname → ${ip.hostAddress}:443")
                            reachable.add(ip)
                            break // one reachable is enough
                        } catch (e: Exception) {
                            Log.w("PlayerNetwork", "TCP FAIL: $hostname → ${ip.hostAddress}:443")
                            unreachable.add(ip)
                        }
                    }

                    val ordered = reachable + unreachable
                    if (reachable.isEmpty()) {
                        Log.w("PlayerNetwork", "ALL ${allIps.size} IPs blocked for $hostname")
                    }
                    return ordered
                } catch (e: Exception) {
                    Log.w("PlayerNetwork", "Multi-DoH failed for $hostname: ${e.message}, trying wire DoH")
                    return fallback.lookup(hostname)
                }
            }
        }

        val dohClient = OkHttpClient.Builder()
            .dns(jsonDohDns)
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        Log.d("PlayerNetwork", "Using OkHttpDataSource with Multi-DoH (cfglobalcdn resolution)")
        return OkHttpDataSource.Factory(dohClient)
            .setUserAgent(NetworkClient.USER_AGENT)
    }

    /**
     * Clean OkHttp DataSource for CDNs like tnmr.org that reject inconsistent headers.
     * Uses DoH DNS + cookie jar from NetworkClient, but NO browser-navigation interceptor
     * (which would add Upgrade-Insecure-Requests and Sec-Fetch-Dest:document that conflict
     * with the media-fetch headers the player sets via setDefaultRequestProperties).
     */
    private fun createBrowserOkHttpDataSourceFactory(videoUrl: String = ""): HttpDataSource.Factory {
        usingCronet = false
        usingDoH = false
        usingBrowserOkHttp = true
        // v85r 2026-05-20 : Pour Uqload, reutiliser EXACTEMENT le sharedOkHttpClient de
        //   UqloadExtractor. Le token signe par strm5.uqload.is est bound au TLS
        //   fingerprint du client OkHttp qui a fetch l'embed. Si on cree un nouveau
        //   client (meme avec la meme config) la TLS handshake peut produire un
        //   ClientHello legerement different (cipher suite order, ALPN, GREASE) et
        //   le serveur renvoie 403. Donc on utilise la MEME instance.
        val isUqload = videoUrl.contains("uqload", ignoreCase = true)
        val client = if (isUqload) {
            Log.d("PlayerNetwork", "Using SHARED UqloadExtractor.sharedOkHttpClient (TLS fingerprint match)")
            com.streamflixreborn.streamflix.extractors.UqloadExtractor.sharedOkHttpClient
        } else {
            OkHttpClient.Builder()
                .dns(DnsResolver.doh)
                .cookieJar(NetworkClient.cookieJar)
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
        val ua = if (isUqload) {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"  // v85x desktop UA match PC Chrome
        } else {
            NetworkClient.USER_AGENT
        }
        Log.d("PlayerNetwork", "Using clean OkHttpDataSource (DoH + cookies, no interceptor), ua=${ua.take(50)}")
        return OkHttpDataSource.Factory(client)
            .setUserAgent(ua)
    }

    private fun createDefaultHttpDataSourceFactory(): HttpDataSource.Factory {
        usingCronet = false
        usingDoH = false
        usingBrowserOkHttp = false
        Log.d("PlayerNetwork", "Using DefaultHttpDataSource + LiveReconnecting wrapper")
        val base = DefaultHttpDataSource.Factory()
            .setUserAgent(NetworkClient.USER_AGENT)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
        // 2026-05-10 : wrapper auto-reconnect sur EOF pour live MPEG-TS (cf PlayerTvFragment).
        return com.streamflixreborn.streamflix.utils.LiveReconnectingHttpDataSource.Factory(base)
    }

    // 2026-07-05 : flag pour savoir si le player vient du mini (transfert seamless)
    private var attachedFromMiniPlayer = false

    /**
     * 2026-07-05 : Attache le player ExoPlayer transféré depuis le mini-player
     * SANS le stopper ni le re-créer → lecture continue, zéro coupure.
     * Même logique que PlayerTvFragment.attachTransferredPlayer().
     */
    private fun attachTransferredPlayer(transferred: ExoPlayer) {
        Log.d("PlayerMobileFragment", "Attaching transferred ExoPlayer from mini player")
        attachedFromMiniPlayer = true
        player = transferred
        if (!::httpDataSource.isInitialized) {
            httpDataSource = createHttpDataSourceFactory("")
        }
        dataSourceFactory = DefaultDataSource.Factory(requireContext(), httpDataSource)
        val isLiveIptvHere = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
            args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
            args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
            args.id.startsWith("livehub::") || args.id.startsWith("sportlive::") ||
            args.id.startsWith("match::") || args.id.startsWith("vavoo::") || args.id.startsWith("myiptv-live::")
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(), !isLiveIptvHere)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage("fr").build()
        mediaSession = MediaSession.Builder(requireContext(), player)
            .setId("player_mobile_${System.nanoTime()}")
            .build()
        binding.pvPlayer.player = player
        binding.settings.player = player
        binding.settings.subtitleView = binding.pvPlayer.subtitleView
        binding.settings.onSubtitlesClicked = { viewModel.getSubtitles(args.videoType) }
        com.streamflixreborn.streamflix.utils.MiniPlayerController.clearTransitionFlag()
        // REPEAT_MODE_OFF pour live (cf TV fragment)
        if (isLiveIptvHere) {
            player.repeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF
        }
        if (!player.playWhenReady) {
            player.playWhenReady = true
        }
        // Listener recovery minimal (même logique que TV) pour que le live
        // puisse reprendre après STATE_ENDED/erreur.
        attachTransferRecoveryListener()
        Log.d("PlayerMobileFragment", "Transferred player attached — seamless (repeat=${player.repeatMode} playWhenReady=${player.playWhenReady})")
    }

    /** Listener recovery minimal pour le path transfer-from-mini.
     *  Le listener complet de displayVideo() n'est pas attaché ici pour
     *  éviter un re-init du player. Quand le flux live s'épuise (STATE_ENDED)
     *  ou se déconnecte (STATE_IDLE), on re-prepare() pour re-fetch l'URL. */
    private fun attachTransferRecoveryListener() {
        val recoveryListener = object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    androidx.media3.common.Player.STATE_ENDED,
                    androidx.media3.common.Player.STATE_IDLE -> {
                        Log.d("PlayerMobileFragment", "Transfer recovery: STATE=$state → prepare()")
                        try {
                            player.seekToDefaultPosition()
                            player.prepare()
                            player.playWhenReady = true
                        } catch (_: Exception) {}
                    }
                    androidx.media3.common.Player.STATE_READY -> {
                        Log.d("PlayerMobileFragment", "Transfer recovery: STATE_READY")
                    }
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.e("PlayerMobileFragment", "Transfer recovery: error ${error.errorCodeName} → prepare()")
                try {
                    player.seekToDefaultPosition()
                    player.prepare()
                    player.playWhenReady = true
                } catch (_: Exception) {}
            }
        }
        try { player.addListener(recoveryListener) } catch (_: Exception) {}
    }

    private fun initializePlayer(extraBuffering: Boolean, softwareDecoder: Boolean = currentSoftwareDecoder, videoUrl: String = "") {
        releasePlayer()
        currentExtraBuffering = extraBuffering
        currentSoftwareDecoder = softwareDecoder

        httpDataSource = createHttpDataSourceFactory(videoUrl)

        dataSourceFactory = DefaultDataSource.Factory(requireContext(), httpDataSource)

        // 2026-05-09 : handleAudioFocus=false pour IPTV Live (cf PlayerTvFragment).
        // Empêche les pauses auto sur Bluetooth/notif/audio focus loss.
        val isLiveIptvHere = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
            args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
            args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
            args.id.startsWith("livehub::") || args.id.startsWith("sportlive::") ||
            args.id.startsWith("match::") || args.id.startsWith("vavoo::") || args.id.startsWith("myiptv-live::")
        player = buildPlayer(extraBuffering).also { player ->
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    !isLiveIptvHere,
                )

                val lang = UserPreferences.currentProvider?.language?.substringBefore("-")
                val tsBuilder = player.trackSelectionParameters.buildUpon()
                if (lang == "es") {
                    tsBuilder.setPreferredAudioLanguage("spa")
                } else {
                    // 2026-05-22 : toujours préférer l'audio français par défaut
                    // (user "quand on lance un film qui a du FR dedans, qu'il se
                    // mette en priorité — on est obligé d'aller dans Audio changer").
                    tsBuilder.setPreferredAudioLanguage("fr")
                }
                // 2026-05-09 : pour IPTV, prioriser AAC > AC3 > MP3 (anti EAC-3
                // qui n'est pas décodé par tous les hardware → "pas de son sur
                // Canal+ Live").
                val isLiveIptvCh = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
                    args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
                    args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
                    args.id.startsWith("livehub::") || args.id.startsWith("sportlive::") ||
                    args.id.startsWith("match::") || args.id.startsWith("vavoo::") || args.id.startsWith("myiptv-live::")
                if (isLiveIptvCh) {
                    tsBuilder.setPreferredAudioMimeTypes(
                        androidx.media3.common.MimeTypes.AUDIO_AAC,
                        androidx.media3.common.MimeTypes.AUDIO_AC3,
                        androidx.media3.common.MimeTypes.AUDIO_MPEG,
                    )
                }
                player.trackSelectionParameters = tsBuilder.build()

                mediaSession = MediaSession.Builder(requireContext(), player)
                    .setId("player_mobile_${System.nanoTime()}")
                    .build()
            }

        // 2026-05-21 : governor de qualité adaptatif — actif uniquement en VOD +
        //   qualité Auto (qualityHeight == null). Descend sur rebuffers répétés,
        //   remonte quand stable. Recréé à chaque rebuild de player.
        adaptiveQualityTicker?.cancel()
        adaptiveQualityGovernor = com.streamflixreborn.streamflix.utils.AdaptiveQualityGovernor(player) {
            !isLiveIptvHere && UserPreferences.qualityHeight == null
        }
        adaptiveQualityTicker = viewLifecycleOwner.lifecycleScope.launch {
            while (_binding != null) {
                kotlinx.coroutines.delay(10_000L)
                if (_binding == null) break
                adaptiveQualityGovernor?.onTick()
            }
        }

        binding.pvPlayer.player = player
        binding.settings.player = player
        binding.settings.subtitleView = binding.pvPlayer.subtitleView
        binding.settings.onSubtitlesClicked = {
            viewModel.getSubtitles(args.videoType)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // WebView overlay — Netu anti-bot bypass (touch-friendly for mobile)
    // ═══════════════════════════════════════════════════════════════════

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    /**
     * 2026-07-09 : superpose NOTRE barre de contrôle au-dessus d'un overlay WebView-player
     * (abyss / seekplayer / player4me) pour que l'app garde la main : retour, pause/play (piloté
     * en JS sur la balise <video>), seek, liste serveurs, paramètres. Tap = afficher/masquer,
     * auto-masquage après quelques secondes. « Comme notre player », même si le flux joue en WebView.
     */
    private fun addOverlayPlayerControls(ctx: Context, overlay: FrameLayout, wv: WebView) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        fun fmt(s: Int): String { val m = s / 60; val sec = s % 60; return "%d:%02d".format(m, sec) }

        // Layer NON cliquable : les taps au CENTRE passent au player web (indispensable pour le
        //   « tape play » d'abyss). Seules nos barres (haut/bas) captent leurs propres taps.
        val layer = FrameLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            elevation = 45f
        }

        // ── Barre du haut : retour + titre ──
        val topBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#B3000000"))
            setPadding(28, 20, 28, 20)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        }
        val backBtn = android.widget.ImageView(ctx).apply {
            setImageResource(android.R.drawable.ic_media_previous)
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(80, 80)
            isClickable = true; isFocusable = true
        }
        val titleTv = TextView(ctx).apply {
            text = resolvePlayerTitle()
            setTextColor(Color.WHITE); textSize = 15f
            setPadding(28, 0, 0, 0); maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(backBtn); topBar.addView(titleTv)

        // ── Barre du bas : seek + boutons ──
        val bottomBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#B3000000"))
            setPadding(28, 12, 28, 20)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        }
        val timeTv = TextView(ctx).apply {
            text = "0:00 / 0:00"; setTextColor(Color.WHITE); textSize = 12f
        }
        val seek = android.widget.SeekBar(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(0, 8, 0, 0)
        }
        fun mkBtn(icon: Int, label: String): LinearLayout {
            val b = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                isClickable = true; isFocusable = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            b.addView(android.widget.ImageView(ctx).apply {
                setImageResource(icon); setColorFilter(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(70, 70)
            })
            b.addView(TextView(ctx).apply {
                text = label; setTextColor(Color.WHITE); textSize = 11f; gravity = Gravity.CENTER
            })
            return b
        }
        val playPause = mkBtn(android.R.drawable.ic_media_pause, "Pause")
        val playIcon = playPause.getChildAt(0) as android.widget.ImageView
        val playLabel = playPause.getChildAt(1) as TextView
        val serversBtn = mkBtn(android.R.drawable.ic_menu_sort_by_size, "Serveurs")
        val settingsBtn = mkBtn(android.R.drawable.ic_menu_manage, "Paramètres")
        btnRow.addView(playPause); btnRow.addView(serversBtn); btnRow.addView(settingsBtn)
        bottomBar.addView(timeTv); bottomBar.addView(seek); bottomBar.addView(btnRow)

        // Barres TOUJOURS visibles (focusables → navigables à la télécommande sur TV, et
        //   l'utilisateur peut toujours mettre en pause / changer de serveur). Pas d'auto-masquage
        //   pour éviter d'avoir à re-capter les taps (qui doivent rester au player web au centre).
        fun scheduleHide() { /* no-op : barres persistantes */ }

        // ── Pause / play (JS) ──
        playPause.setOnClickListener {
            wv.evaluateJavascript(
                "(function(){var v=document.querySelector('video');if(!v)return 'x';if(v.paused){v.play();return 'playing';}v.pause();return 'paused';})()"
            ) { r ->
                val playing = r?.contains("playing") == true
                playIcon.setImageResource(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
                playLabel.text = if (playing) "Pause" else "Lecture"
            }
            scheduleHide()
        }
        // ── Serveurs : on OUVRE la liste PAR-DESSUS (bottom-sheet) SANS fermer la vidéo. Le swap
        //   d'overlay se fait seulement à la sélection d'un serveur (displayVideo → hideWebViewOverlay).
        serversBtn.setOnClickListener {
            try { binding.settings.showServers() } catch (_: Exception) {}
        }
        // ── Paramètres : menu overflow du player, ancré sur le bouton ──
        settingsBtn.setOnClickListener { anchor -> try { showPlayerOverflowMenu(anchor) } catch (_: Exception) {} }
        // ── Retour : ferme l'overlay + back normal du player ──
        backBtn.setOnClickListener {
            hideWebViewOverlay()
            try { binding.pvPlayer.controller.binding.btnExoBack.performClick() } catch (_: Exception) {}
        }

        // ── Seek : poll position/durée depuis la <video>, drag pour se déplacer ──
        var userSeeking = false
        val poll = object : Runnable {
            override fun run() {
                if (webViewOverlay == null) return
                if (!userSeeking) {
                    try {
                        wv.evaluateJavascript(
                            "(function(){var v=document.querySelector('video');return v&&v.duration?Math.floor(v.currentTime)+'/'+Math.floor(v.duration):'0/0';})()"
                        ) { r ->
                            val parts = r?.trim('"')?.split('/')
                            if (parts != null && parts.size == 2) {
                                val cur = parts[0].toIntOrNull() ?: 0
                                val dur = parts[1].toIntOrNull() ?: 0
                                if (dur > 0) { seek.max = dur; if (!userSeeking) seek.progress = cur; timeTv.text = "${fmt(cur)} / ${fmt(dur)}" }
                            }
                        }
                    } catch (_: Exception) {}
                }
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(poll, 1500)
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) timeTv.text = "${fmt(p)} / ${fmt(sb?.max ?: 0)}"
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) { userSeeking = true }
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                val target = sb?.progress ?: 0
                try { wv.evaluateJavascript("(function(){var v=document.querySelector('video');if(v)v.currentTime=$target;})()", null) } catch (_: Exception) {}
                userSeeking = false; scheduleHide()
            }
        })

        layer.addView(bottomBar); layer.addView(topBar)
        overlay.addView(layer)
        scheduleHide()
    }

    /**
     * 2026-07-09 : branche un [WebPlayerMirror] sur la PlayerView pour que NOS contrôles natifs
     * pilotent la <video> de la WebView (play/pause/seek), mobile ET télécommande TV. Ajoute aussi
     * un capteur de tap transparent au-dessus de la WebView pour convoquer/masquer le controller
     * (la WebView capterait sinon les taps et le controller ne s'afficherait jamais).
     */
    private fun attachWebMirrorPlayer(overlay: FrameLayout, wv: WebView) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val mirror = WebPlayerMirror(
            android.os.Looper.getMainLooper(),
            onPlayPause = {
                // VRAI MotionEvent Android sur la WebView, PILE là où est le gros bouton bleu du
                //   player web = centre HORIZONTAL, à la hauteur de la BARRE de contrôle (Y du bouton
                //   play natif). MotionEvent = touch RÉEL (isTrusted=true) → démarrage fiable.
                try {
                    if (wv.width > 0 && wv.height > 0) {
                        val x = wv.width / 2f
                        // Y = hauteur du bouton play natif (la barre), converti en coords WebView.
                        val playBtn = try { binding.pvPlayer.controller.binding.exoPlayPause } catch (_: Exception) { null }
                        val y: Float = if (playBtn != null && playBtn.height > 0) {
                            val loc = IntArray(2); playBtn.getLocationOnScreen(loc)
                            val wvLoc = IntArray(2); wv.getLocationOnScreen(wvLoc)
                            (loc[1] + playBtn.height / 2 - wvLoc[1]).toFloat().coerceIn(1f, (wv.height - 1).toFloat())
                        } else wv.height / 2f
                        val t = android.os.SystemClock.uptimeMillis()
                        val down = android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
                        val up = android.view.MotionEvent.obtain(t, t + 60, android.view.MotionEvent.ACTION_UP, x, y, 0)
                        wv.dispatchTouchEvent(down); wv.dispatchTouchEvent(up)
                        down.recycle(); up.recycle()
                        Log.d("PlayerMobile", "SEEK real-tap at $x,$y (wv ${wv.width}x${wv.height})")
                    }
                } catch (_: Exception) {}
            },
            onSeekTo = { ms ->
                try {
                    wv.evaluateJavascript(
                        "(function(){var v=document.querySelector('video');if(v)v.currentTime=${ms / 1000.0};})()", null)
                } catch (_: Exception) {}
            },
        )
        webMirrorPlayer = mirror
        try { binding.pvPlayer.player = mirror } catch (e: Exception) { Log.w("PlayerMobile", "attach mirror KO: ${e.message}") }
        try { binding.pvPlayer.showController() } catch (_: Exception) {}

        // Bouton du MILIEU (exoPlayPause, centré sur le gros bouton bleu) = UNIQUEMENT l'auto-clic :
        //   on override son clic pour envoyer un VRAI MotionEvent sur la WebView pile sur le bouton
        //   bleu (centre horizontal, hauteur de la barre). Il ne passe plus par la logique play/pause
        //   du miroir → plus d'interaction avec le bouton pause dédié.
        try {
            binding.pvPlayer.controller.binding.exoPlayPause.setOnClickListener {
                try {
                    if (wv.width > 0 && wv.height > 0) {
                        val x = wv.width / 2f
                        val pb = binding.pvPlayer.controller.binding.exoPlayPause
                        val loc = IntArray(2); pb.getLocationOnScreen(loc)
                        val wvLoc = IntArray(2); wv.getLocationOnScreen(wvLoc)
                        val y = (loc[1] + pb.height / 2 - wvLoc[1]).toFloat().coerceIn(1f, (wv.height - 1).toFloat())
                        val t = android.os.SystemClock.uptimeMillis()
                        val down = android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
                        val up = android.view.MotionEvent.obtain(t, t + 60, android.view.MotionEvent.ACTION_UP, x, y, 0)
                        wv.dispatchTouchEvent(down); wv.dispatchTouchEvent(up)
                        down.recycle(); up.recycle()
                        Log.d("PlayerMobile", "SEEK milieu real-tap at $x,$y")
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        // Bouton PAUSE dédié (dans la barre, câblé EN DIRECT) : v.pause()/v.play() FIABLE. La pause
        //   d'une vidéo qui joue ne nécessite aucun geste → v.pause() marche à coup sûr. Le play/
        //   lancement (qui exige un vrai geste) est géré par le bouton principal (MotionEvent).
        try {
            val ppBtn = binding.pvPlayer.controller.binding.root
                .findViewById<android.widget.ImageView>(R.id.btn_seek_playpause)
            ppBtn?.visibility = View.VISIBLE
            // Espaceur gauche pour garder le bouton play centré sur le bouton bleu.
            binding.pvPlayer.controller.binding.root
                .findViewById<View>(R.id.btn_seek_spacer)?.visibility = View.VISIBLE
            ppBtn?.setOnClickListener {
                try {
                    wv.evaluateJavascript(
                        "(function(){try{var v=document.querySelector('video');if(!v)return;var mp=document.querySelector('media-player');" +
                        "if(v.paused){try{v.play();}catch(e){}try{if(mp&&mp.play)mp.play();}catch(e){}}" +
                        "else{try{v.pause();}catch(e){}try{if(mp&&mp.pause)mp.pause();}catch(e){}}}catch(e){}})()", null)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        // Capteur de tap : affiche/masque le controller natif au tap (la WebView capterait sinon
        //   les taps → le controller ne s'afficherait jamais).
        val tapCatcher = View(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            isClickable = true
            setOnClickListener {
                try {
                    if (binding.pvPlayer.isControllerFullyVisible) binding.pvPlayer.hideController()
                    else binding.pvPlayer.showController()
                } catch (_: Exception) {}
            }
        }
        overlay.addView(tapCatcher)

        val poll = object : Runnable {
            override fun run() {
                if (webMirrorPlayer !== mirror || webViewOverlay == null) return
                try {
                    wv.evaluateJavascript(
                        "(function(){var v=document.querySelector('video');return v?(Math.round(v.currentTime*1000)+'/'+Math.round((v.duration||0)*1000)+'/'+(v.paused?0:1)):'0/0/0';})()"
                    ) { r ->
                        val p = r?.trim('"')?.split('/')
                        if (p != null && p.size == 3) {
                            mirror.update(p[0].toLongOrNull() ?: 0L, p[1].toLongOrNull() ?: 0L, p[2] == "1")
                        }
                    }
                } catch (_: Exception) {}
                handler.postDelayed(this, 500)
            }
        }
        webMirrorPoll = poll
        handler.postDelayed(poll, 800)
    }

    private fun detachWebMirrorPlayer() {
        webMirrorPoll?.let { android.os.Handler(android.os.Looper.getMainLooper()).removeCallbacks(it) }
        webMirrorPoll = null
        // Re-cache le bouton play/pause dédié seekplayer + son espaceur.
        try {
            binding.pvPlayer.controller.binding.root
                .findViewById<android.widget.ImageView>(R.id.btn_seek_playpause)?.visibility = View.GONE
            binding.pvPlayer.controller.binding.root
                .findViewById<View>(R.id.btn_seek_spacer)?.visibility = View.GONE
        } catch (_: Exception) {}
        // RESTAURE le play/pause NORMAL : mon override du bouton central pointait vers la WebView
        //   (détruite) → sans ça le player normal ne pouvait plus se mettre en pause.
        try {
            binding.pvPlayer.controller.binding.exoPlayPause.setOnClickListener {
                try { if (::player.isInitialized) { if (player.playWhenReady) player.pause() else player.play() } } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        if (webMirrorPlayer != null) {
            webMirrorPlayer = null
            // Restaure le vrai ExoPlayer sur la PlayerView.
            try { if (::player.isInitialized) binding.pvPlayer.player = player } catch (_: Exception) {}
        }
    }

    private fun showWebViewOverlay(embedUrl: String) {
        if (webViewOverlay != null) return
        val ctx = requireContext()
        // Embeds où la WebView EST le lecteur (abyss/seekplayer/player4me) : la WebView va DANS la
        //   zone vidéo du player natif (overlayFrameLayout = derrière les contrôles) et on branche
        //   un lecteur MIROIR (WebPlayerMirror) sur la PlayerView → NOS contrôles natifs pilotent la
        //   vidéo web (play/pause/seek), mobile ET télécommande TV. Les autres embeds gardent
        //   l'overlay plein écran sur la racine.
        // 2026-07-17 : abyss = mode MIROIR (contrôles natifs play/pause/seek qui pilotent la WebView,
        //   fonctionne à la télécommande sur TV). + interception → bascule native si abyss mint l'URL.
        val webViewIsPlayer = embedUrl.contains("seekplayer") || embedUrl.contains("embedseek") || embedUrl.contains("abyss") || embedUrl.contains("4meplayer") || embedUrl.contains("swiftflow")
        val nativeVideoOverlay = binding.pvPlayer.overlayFrameLayout
        val useNativeControls = webViewIsPlayer && nativeVideoOverlay != null
        val rootView: ViewGroup = if (useNativeControls) nativeVideoOverlay!! else binding.root as ViewGroup
        m3u8Intercepted = false
        daddyLiveCdnPageUrl = null

        // ── Overlay container ──
        val overlay = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            // Élévation 0 dans la zone vidéo native (rester DERRIÈRE les contrôles) ; 30f en overlay
            //   plein écran (DaddyLive/Netu).
            elevation = if (useNativeControls) 0f else 30f
        }

        // ── WebView (user can touch/tap directly) ──
        val wv = WebView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = NetworkClient.USER_AGENT
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                loadWithOverviewMode = true
                useWideViewPort = true
            }
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        // Detect if this is a DaddyLive/bolaloca embed
        val isDaddyLiveEmbed = embedUrl.contains("bolaloca")
        // v88 : abyss/Hydrax joue DANS la WebView overlay (pas d'interception m3u8,
        //   car le stream abyss a un binding TLS qui casse hors WebView). La WebView
        //   visible/interactive EST le player ; l'utilisateur tape play.
        val isAbyssEmbed = embedUrl.contains("abyss")
        // 2026-05-21 (user "sur mobile Player4me affiche des pubs, pas comme sur TV où il
        //   marche bien") : on porte le traitement Player4me du TV vers le mobile —
        //   chargement direct + Referer, blocage des pubs (ressources + navigation), SSL.
        val isPlayer4me = embedUrl.contains("4meplayer")
        // 2026-07-09 : SeekStreaming (seekplayer.vip/.me) de Movix. Player vidstack P2P/HLS
        //   inextractible headless (flux révélé seulement en lecture réelle) → on le JOUE dans
        //   l'overlay WebView comme abyss : nav top-level hors seekplayer bloquée (tue les redirects
        //   pub), pubs coupées, autoplay + plein écran injectés.
        // 2026-07-16 : embedseek = MÊME player OnlyFlix que seekplayer → même traitement.
        val isSeekPlayer = embedUrl.contains("seekplayer") || embedUrl.contains("embedseek")
        // 2026-07-16 : SwiftFlow (swiftflow.lol) — player Movix Plyr à ad-gide (« Regarder
        //   maintenant »). Même famille de traitement que seekplayer (nav-block, ad-block,
        //   overlay lecteur), mais avec son propre JS d'auto-clic sur l'ad-gate.
        val isSwiftFlow = embedUrl.contains("swiftflow")
        val seekAdHosts = listOf(
            "boredomcuff", "spleniidizzy", "gappedpeatmen", "popads", "popcash", "propeller",
            "onclick", "adsterra", "hilltopads", "monetag", "clickadu", "doubleclick",
            "googlesyndication", "syndication", "exoclick", "juicyads", "trafficjunky",
            // 2026-07-16 : pubs vues sur l'ad-gate swiftflow.lol
            "eminentpercentvandalism",
        )
        if (isSeekPlayer || isSwiftFlow) {
            wv.settings.userAgentString = com.streamflixreborn.streamflix.utils.WebViewResolver.STEALTH_UA
        }
        val abyssNavAllow = listOf(
            "abysscdn.com", "abyss.to", "abyssa.cc", "abyssplayer.com", "hydrax.net",
            "iamcdn.net", "googleapis.com", "jwpcdn.com", "jsdelivr.net",
            "cloudflare.com", "dessinanime.cc", "morphify.net"
        )
        val abyssAdHosts = listOf(
            "aliexpress", "decafeligibly", "googlesyndication", "doubleclick",
            "popads", "popunder", "popcash", "propellerads", "exoclick",
            "juicyads", "trafficjunky", "clickadu", "adsterra", "hilltopads",
            "histats", "googletagmanager", "google-analytics"
        )

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean {
                // Player4me redirige toute la page vers des interstitiels pub : on
                //   bloque la navigation top-level vers tout ce qui n'est pas 4meplayer
                //   + ses CDN connus. Le flux vidéo se charge en ressource → non impacté.
                if (isPlayer4me) {
                    val nh = request?.url?.host ?: return false
                    val ok = listOf(
                        "4meplayer", "dessinanime", "cloudflare", "cloudfront",
                        "akamaized", "googlevideo", "jsdelivr", "sendvid", "uqload",
                        "abysscdn", "embed4me", "hakunaymatata", "bcdn", "hcdn"
                    ).any { nh.contains(it) }
                    if (!ok) { Log.d("PlayerMobile", "Player4me NAV BLOCKED: $nh"); return true }
                    return false
                }
                // SeekStreaming : bloque toute NAVIGATION top-level qui n'est pas seekplayer
                //   (= les redirects pub au 1er clic). Le flux vidéo/CDN se charge en ressource
                //   (shouldInterceptRequest), pas en navigation → non impacté.
                if (isSeekPlayer) {
                    val nh = request?.url?.host ?: return false
                    if (!(nh.contains("seekplayer.") || nh.contains("embedseek."))) { Log.d("PlayerMobile", "Seek NAV BLOCKED: $nh"); return true }
                    return false
                }
                // SwiftFlow : seule la page player swiftflow.lol doit naviguer top-level.
                //   Le mp4 citron-edge + le signing cheksum se chargent en RESSOURCE (non impacté).
                //   Toute autre navigation = redirect pub de l'ad-gate → bloquée.
                if (isSwiftFlow) {
                    val nh = request?.url?.host ?: return false
                    if (!nh.contains("swiftflow.")) { Log.d("PlayerMobile", "SwiftFlow NAV BLOCKED: $nh"); return true }
                    return false
                }
                if (!isAbyssEmbed) return false
                val navHost = request?.url?.host ?: return false
                val allowed = abyssNavAllow.any { navHost == it || navHost.endsWith(".$it") }
                if (!allowed) { Log.d("PlayerMobile", "Abyss NAV BLOCKED: $navHost"); return true }
                return false
            }
            override fun shouldInterceptRequest(
                view: WebView?, request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null

                // SeekStreaming / SwiftFlow : coupe les hôtes de pub (le player charge sinon des onclick/pop).
                if (isSeekPlayer || isSwiftFlow) {
                    val sh = request?.url?.host ?: ""
                    if (seekAdHosts.any { sh.contains(it, ignoreCase = true) }) {
                        Log.d("PlayerMobile", "Seek/SwiftFlow AD BLOCKED: $sh")
                        return WebResourceResponse("text/plain", "UTF-8",
                            java.io.ByteArrayInputStream("".toByteArray()))
                    }
                }

                // Abyss/Hydrax: player dedie sans pub
                if (isAbyssEmbed) {
                    val ah = request?.url?.host ?: ""
                    // 2026-07-17 : au VRAI tap play (geste de confiance), abyss mint l'URL MP4
                    //   googleapis → on la CAPTE et on bascule en lecture NATIVE ExoPlayer (contrôle
                    //   télécommande complet sur TV, comme les autres serveurs). Le WebView ne sert
                    //   qu'à révéler l'URL via ton interaction.
                    if (!m3u8Intercepted && isAbyssMediaUrl(url)) {
                        Log.d("PlayerMobile", "Abyss media intercepted → native: ${url.take(90)}")
                        m3u8Intercepted = true
                        android.os.Handler(android.os.Looper.getMainLooper()).post { onAbyssMediaIntercepted(url) }
                        return WebResourceResponse("text/plain", "UTF-8",
                            java.io.ByteArrayInputStream("".toByteArray()))
                    }
                    if (abyssAdHosts.any { ah.contains(it, ignoreCase = true) }) {
                        Log.d("PlayerMobile", "Abyss AD BLOCKED: $ah")
                        return WebResourceResponse("text/plain", "UTF-8",
                            java.io.ByteArrayInputStream("".toByteArray()))
                    }
                }

                // ── Player4me : blocage des ressources pub (interstitiels plein écran) ──
                if (isPlayer4me) {
                    val host = request?.url?.host ?: ""
                    val isAd = AD_BLOCK_PATTERNS.any { host.contains(it, ignoreCase = true) }
                        || url.contains("/ads/") || url.contains("/ad.")
                        || url.contains("popunder") || url.contains("pop.js")
                        || url.contains("/vast") || url.contains("vpaid")
                        || url.contains("syndication") || url.contains("/banner")
                    if (isAd) {
                        Log.d("PlayerMobile", "Player4me AD BLOCKED: $host")
                        return WebResourceResponse("text/plain", "UTF-8",
                            java.io.ByteArrayInputStream("".toByteArray()))
                    }
                }

                // ── DaddyLive: block ads + popups ──
                if (isDaddyLiveEmbed) {
                    val host = request?.url?.host ?: ""
                    // Block known ad / tracking / popup domains
                    val isAd = AD_BLOCK_PATTERNS.any { host.contains(it, ignoreCase = true) }
                        || url.contains("/ads/") || url.contains("/ad.")
                        || url.contains("popunder") || url.contains("pop.js")
                        || url.contains("trafficjunky") || url.contains("exoclick")
                        || url.contains("juicyads") || url.contains("clickadu")
                        || url.contains("/prebid") || url.contains("adserver")
                        || url.contains("syndication") || url.contains("banner")
                        || url.contains("/vast") || url.contains("vpaid")
                    if (isAd) {
                        Log.d("PlayerMobile", "DaddyLive AD BLOCKED: ${host}/${url.takeLast(60)}")
                        return WebResourceResponse(
                            "text/plain", "UTF-8",
                            java.io.ByteArrayInputStream("".toByteArray())
                        )
                    }
                }

                // ── DaddyLive: LOG ALL non-ad requests for CDN iframe detection ──
                if (isDaddyLiveEmbed && !m3u8Intercepted) {
                    val reqHost = request?.url?.host ?: ""
                    val isBolaloca = reqHost.contains("bolaloca") || reqHost.contains("daddylive")
                    if (!isBolaloca) {
                        val accept = request?.requestHeaders?.get("Accept") ?: ""
                        Log.d("PlayerMobile", "DaddyLive REQ [${request?.method}] host=$reqHost accept=${accept.take(40)} url=${url.take(160)}")
                    }

                    // Capture the CDN iframe page URL (the HTML page, not assets)
                    if (daddyLiveCdnPageUrl == null) {
                        val isCdnDomain = reqHost.contains("58103793") || reqHost.contains("lulustream")
                            || reqHost.contains("luluvdo") || reqHost.contains("lulucdn")
                            || reqHost.contains("cdn-tnmr") || reqHost.contains("hlsbot")
                        val isStaticAsset = url.contains(".m3u8") || url.contains(".ts")
                            || url.contains(".js") || url.contains(".css")
                            || url.contains(".png") || url.contains(".jpg")
                            || url.contains(".svg") || url.contains(".ico")
                            || url.contains(".woff") || url.contains(".gif")
                            || url.contains(".woff2") || url.contains(".ttf")
                        val accept = request?.requestHeaders?.get("Accept") ?: ""
                        val looksLikeHtml = accept.contains("text/html") || (!isStaticAsset && request?.method == "GET")
                        if (isCdnDomain && looksLikeHtml && !isStaticAsset) {
                            daddyLiveCdnPageUrl = url
                            Log.d("PlayerMobile", "DaddyLive CDN iframe URL captured: ${url.take(160)}")
                        }
                    }
                }

                // ── DaddyLive: intercept m3u8, play via ExoPlayer + WebViewDataSource ──
                if (isDaddyLiveEmbed && url.contains(".m3u8")) {
                    if (!m3u8Intercepted) {
                        Log.d("PlayerMobile", "DaddyLive M3U8 intercepted: ${url.take(140)}")
                        m3u8Intercepted = true
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            onDaddyLiveM3u8Intercepted(m3u8Url = url, embedUrl = embedUrl)
                        }
                    }
                    return WebResourceResponse(
                        "application/vnd.apple.mpegurl", "UTF-8",
                        java.io.ByteArrayInputStream("#EXTM3U\n#EXT-X-ENDLIST\n".toByteArray())
                    ).apply { responseHeaders = mapOf("Access-Control-Allow-Origin" to "*") }
                }

                // ── Netu/cfglobalcdn interception (existing logic) ──
                if (url.contains("cfglobalcdn.com")) {
                    if (m3u8Intercepted) {
                        Log.d("PlayerMobile", "Blocking cfglobalcdn request: ${url.takeLast(60)}")
                        return WebResourceResponse(
                            "text/plain", "UTF-8",
                            java.io.ByteArrayInputStream("".toByteArray())
                        ).apply {
                            responseHeaders = mapOf(
                                "Access-Control-Allow-Origin" to "*"
                            )
                        }
                    }
                    if (url.contains("silverlight") || url.contains("hls-vod")
                        || url.contains(".m3u8")
                    ) {
                        Log.d("PlayerMobile", "M3U8 intercepted from WebView: ${url.take(120)}")
                        m3u8Intercepted = true
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            onM3u8Intercepted(url)
                        }
                        return WebResourceResponse(
                            "application/vnd.apple.mpegurl", "UTF-8",
                            java.io.ByteArrayInputStream(
                                "#EXTM3U\n#EXT-X-ENDLIST\n".toByteArray()
                            )
                        ).apply {
                            responseHeaders = mapOf(
                                "Access-Control-Allow-Origin" to "*"
                            )
                        }
                    }
                }

                return null // let WebView handle all other requests natively
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d("PlayerMobile", "Overlay WebView loaded: ${url?.take(80)}")

                // ── DaddyLive: inject anti-popup/ad JS ──
                if (isDaddyLiveEmbed) {
                    view?.evaluateJavascript(DADDYLIVE_AD_KILL_JS, null)
                }
                // ── Player4me: tueur de pub conservateur (préserve le player) ──
                if (isPlayer4me) {
                    view?.evaluateJavascript(PLAYER4ME_AD_KILL_JS, null)
                }
                // ── SeekStreaming : plein écran + autoplay du player vidstack ──
                if (isSeekPlayer) {
                    view?.evaluateJavascript(SEEKPLAYER_PLAY_JS, null)
                    view?.postDelayed({ view.evaluateJavascript(SEEKPLAYER_PLAY_JS, null) }, 2500)
                }
                // ── SwiftFlow : auto-clic ad-gate « Regarder maintenant » + plein écran + play ──
                if (isSwiftFlow) {
                    view?.evaluateJavascript(SWIFTFLOW_PLAY_JS, null)
                    view?.postDelayed({ view.evaluateJavascript(SWIFTFLOW_PLAY_JS, null) }, 1500)
                    view?.postDelayed({ view.evaluateJavascript(SWIFTFLOW_PLAY_JS, null) }, 3500)
                }
            }

            // 2026-05-21 : accepter les certs SSL invalides dans l'overlay player
            //   (Player4me + ses CDN ont parfois un cert douteux → handshake rejeté =
            //   écran noir). Cohérent avec le trust-all déjà en place ailleurs.
            override fun onReceivedSslError(
                view: WebView?,
                handler: android.webkit.SslErrorHandler?,
                error: android.net.http.SslError?
            ) {
                try { handler?.proceed() } catch (_: Throwable) { handler?.cancel() }
            }
        }

        wv.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                Log.d("PlayerMobile", "JS console [${consoleMessage?.messageLevel()}]: ${consoleMessage?.message()?.take(200)}")
                return true
            }
            // Block ALL popup windows (window.open)
            override fun onCreateWindow(
                view: WebView?, isDialog: Boolean,
                isUserGesture: Boolean, resultMsg: android.os.Message?
            ): Boolean {
                Log.d("PlayerMobile", "BLOCKED popup window (isDaddyLive=$isDaddyLiveEmbed)")
                return false
            }
        }

        // ── Hint text ──
        val hint = TextView(ctx).apply {
            text = if (isSeekPlayer || isSwiftFlow) "Astuce : appuyez plusieurs fois sur ▶ / ⏸"
                else if (isDaddyLiveEmbed) "Chargement de la vidéo…"
                else "Appuyez sur le bouton play pour lancer la vidéo"
            setTextColor(Color.WHITE)
            textSize = 14f
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            setBackgroundColor(Color.parseColor("#99000000"))
            setPadding(24, 12, 24, 12)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = 48 }
        }

        overlay.addView(wv)
        overlay.addView(hint)
        rootView.addView(overlay)
        // Branche le lecteur MIROIR sur la PlayerView → nos contrôles natifs pilotent la WebView.
        if (useNativeControls) {
            attachWebMirrorPlayer(overlay, wv)
        }

        webViewOverlay = overlay
        overlayWebView = wv

        if (isDaddyLiveEmbed) {
            // DaddyLive: load embed URL DIRECTLY (no iframe wrapper) so our
            // ad-kill JS runs in the same context as the popup-creating scripts.
            Log.d("PlayerMobile", "Loading DaddyLive embed directly: ${embedUrl.take(100)}")
            wv.loadUrl(embedUrl)
        } else if (isPlayer4me) {
            // Player4me doit être chargé DIRECTEMENT (le wrapper iframe provoque
            //   « document.domain mutation blocked » → écran noir) avec Referer
            //   dessinanime.cc (anti-hotlink). Identique au TV.
            Log.d("PlayerMobile", "Loading Player4me directly: ${embedUrl.take(100)}")
            wv.loadUrl(embedUrl, mapOf("Referer" to "https://dessinanime.cc/"))
        } else if (isSeekPlayer) {
            // SeekStreaming : chargement DIRECT de l'embed (le player vidstack joue dedans).
            Log.d("PlayerMobile", "Loading SeekStreaming directly: ${embedUrl.take(100)}")
            wv.loadUrl(embedUrl)
        } else if (isSwiftFlow) {
            // SwiftFlow : chargement DIRECT du player swiftflow.lol (le Plyr joue dedans après
            //   auto-clic de l'ad-gate). Referer swiftflow.lol pour le signing cheksum/citron.
            Log.d("PlayerMobile", "Loading SwiftFlow directly: ${embedUrl.take(100)}")
            wv.loadUrl(embedUrl, mapOf("Referer" to "https://swiftflow.lol/"))
        } else {
            // Other embeds: use iframe wrapper (page expects to be in an iframe)
            val baseHost = if (isAbyssEmbed) "https://dessinanime.cc/" else "https://frembed.cyou/"
            val iframeWrapper = """
                <!DOCTYPE html>
                <html><head>
                <meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
                <style>*{margin:0;padding:0}html,body{width:100%;height:100%;overflow:hidden;background:#000}
                iframe{width:100%;height:100%;border:none}</style>
                </head><body>
                <iframe src="$embedUrl" allow="autoplay;fullscreen;encrypted-media" allowfullscreen
                        referrerpolicy="origin"></iframe>
                </body></html>
            """.trimIndent()
            Log.d("PlayerMobile", "Loading iframe wrapper for: ${embedUrl.take(100)} (base=$baseHost)")
            wv.loadDataWithBaseURL(baseHost, iframeWrapper, "text/html", "UTF-8", null)
        }

        // Auto-fade hint after 5 seconds
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            hint.animate().alpha(0f).setDuration(600).start()
        }, 5000)
    }

    private fun hideWebViewOverlay() {
        val overlay = webViewOverlay ?: return
        val wv = overlayWebView
        webViewOverlay = null
        overlayWebView = null
        pendingWebViewVideo = null
        pendingWebViewServer = null

        // Restaure le vrai ExoPlayer AVANT de détruire la WebView (le miroir la référence).
        detachWebMirrorPlayer()
        wv?.let {
            try { it.stopLoading(); it.destroy() } catch (_: Exception) {}
        }
        // 2026-07-09 : l'overlay peut vivre dans binding.root OU dans overlayFrameLayout du player
        //   natif → on retire depuis son vrai parent.
        (overlay.parent as? ViewGroup)?.removeView(overlay)
        Log.d("PlayerMobile", "WebView overlay hidden")
    }

    /**
     * Called when shouldInterceptRequest detects a cfglobalcdn M3U8 URL.
     * Navigate the WebView to the M3U8 URL to extract content, then play via ExoPlayer.
     */
    private fun onM3u8Intercepted(m3u8Url: String) {
        val video = pendingWebViewVideo ?: return
        val server = pendingWebViewServer ?: return

        Log.d("PlayerMobile", "onM3u8Intercepted: ${m3u8Url.take(100)}")

        // The netu embed URL is the correct Referer for cfglobalcdn
        // (not frembed.cyou — cfglobalcdn validates Referer against the netu origin)
        val netuEmbedUrl = video.webViewUrl ?: "https://netu.frembed.bond/"
        val netuOrigin = try {
            val u = java.net.URL(netuEmbedUrl); "${u.protocol}://${u.host}"
        } catch (_: Exception) { "https://netu.frembed.bond" }

        // Extract cookies from the WebView session BEFORE destroying it.
        // The anti-bot click sets session cookies that cfglobalcdn validates.
        val cookieManager = CookieManager.getInstance()
        val cfgCookies = cookieManager.getCookie(m3u8Url) ?: ""
        val netuCookies = cookieManager.getCookie(netuEmbedUrl) ?: ""
        Log.d("PlayerMobile", "Cookies for cfglobalcdn: ${cfgCookies.take(80)}")
        Log.d("PlayerMobile", "Cookies for netu: ${netuCookies.take(80)}")

        // Destroy the overlay WebView — CronetDataSource handles TLS
        hideWebViewOverlay()
        com.streamflixreborn.streamflix.extractors.NetuExtractor.sharedWebView = null

        Log.d("PlayerMobile", "Using CronetDataSource for Netu/cfglobalcdn (referer=$netuEmbedUrl)")

        // Build headers with correct Referer, Origin, and cookies
        val headers = mutableMapOf(
            "Referer" to netuEmbedUrl,
            "Origin" to netuOrigin,
        )
        if (cfgCookies.isNotBlank()) {
            headers["Cookie"] = cfgCookies
        }

        val newVideo = Video(
            source = m3u8Url,
            type = MimeTypes.APPLICATION_M3U8,
            headers = headers,
            webViewUrl = null,
            subtitles = video.subtitles
        )
        displayVideo(newVideo, server)
    }

    /** URL média réelle d'abyss (MP4 signé sur googleapis) captée dans l'overlay. */
    private fun isAbyssMediaUrl(url: String): Boolean {
        val l = url.lowercase()
        if (!l.startsWith("http")) return false
        return l.contains("storage.googleapis.com")
            || l.contains("commondatastorage.googleapis.com")
            || (l.contains(".mp4") && !l.contains("abysscdn.com/?") && !l.contains("google-analytics"))
    }

    /** Abyss a miné l'URL MP4 (au vrai tap play) → on ferme l'overlay et on joue en NATIF. */
    private fun onAbyssMediaIntercepted(mediaUrl: String) {
        val video = pendingWebViewVideo ?: return
        val server = pendingWebViewServer ?: return
        val clean = mediaUrl.substringBefore("#")
        Log.d("PlayerMobile", "onAbyssMediaIntercepted → native ExoPlayer: ${clean.take(90)}")
        hideWebViewOverlay()
        val newVideo = Video(
            source = clean,
            type = MimeTypes.VIDEO_MP4,
            headers = mapOf(
                "User-Agent" to NetworkClient.USER_AGENT,
                "Referer" to "https://abysscdn.com/",
            ),
            webViewUrl = null,
            subtitles = video.subtitles,
        )
        displayVideo(newVideo, server)
        // Restaure explicitement l'interface native (contrôles, liste serveurs) — identique aux autres.
        binding.pvPlayer.postDelayed({
            try { binding.pvPlayer.useController = true; binding.pvPlayer.showController() } catch (_: Exception) {}
        }, 400)
    }

    /**
     * Called when a DaddyLive/bolaloca WebView intercepts an m3u8 URL.
     * Creates a hidden proxy WebView that navigates to the CDN's REAL URL
     * (not loadDataWithBaseURL — that creates a synthetic origin the CDN
     * rejects). By doing loadUrl("cdnOrigin/"), the WebView gets a genuine
     * same-origin context: XHR has correct Origin/Referer headers, Chrome
     * TLS fingerprint, and shared cookies via CookieManager.
     */
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun onDaddyLiveM3u8Intercepted(m3u8Url: String, embedUrl: String) {
        val video = pendingWebViewVideo ?: return
        val server = pendingWebViewServer ?: return

        // Extract CDN origin from the m3u8 URL INCLUDING port (e.g. https://xxx.58103793.net:8443)
        val cdnOrigin = try {
            val u = java.net.URL(m3u8Url)
            val port = if (u.port != -1 && u.port != u.defaultPort) ":${u.port}" else ""
            "${u.protocol}://${u.host}$port"
        } catch (_: Exception) { "" }

        Log.d("PlayerMobile", "DaddyLive M3U8 → ExoPlayer via WebViewDataSource (CDN origin=$cdnOrigin): ${m3u8Url.take(120)}")

        // ── 0. Clean up any previous DaddyLive proxy (prevents second player) ──
        daddyLiveProxyWebView?.let { old ->
            Log.d("PlayerMobile", "Cleaning up previous DaddyLive proxy WebView")
            try { old.stopLoading(); old.destroy() } catch (_: Exception) {}
            (binding.root as? ViewGroup)?.removeView(old)
        }
        daddyLiveProxyWebView = null

        // ── 1. Create a hidden proxy WebView ──
        val ctx = requireContext()
        val proxyWv = WebView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(1, 1) // invisible
            setBackgroundColor(Color.TRANSPARENT)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = NetworkClient.USER_AGENT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                // Allow XHR from loadDataWithBaseURL context
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = true
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = true
            }
        }

        // Ensure cookies are shared
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(proxyWv, true)

        // Add to view tree (required for WebView to work) but invisible
        (binding.root as ViewGroup).addView(proxyWv)
        daddyLiveProxyWebView = proxyWv

        val cdnPageUrl = daddyLiveCdnPageUrl
        Log.d("PlayerMobile", "DaddyLive proxy: cdnPageUrl=$cdnPageUrl, cdnOrigin=$cdnOrigin")

        proxyWv.webViewClient = object : WebViewClient() {
            private var started = false

            override fun shouldInterceptRequest(
                view: WebView?, request: WebResourceRequest?
            ): WebResourceResponse? {
                val reqUrl = request?.url?.toString() ?: return null
                // BEFORE page loaded: block scripts so hls.js doesn't start its own playback
                // AFTER page loaded (started=true): let EVERYTHING through for WebViewDataSource fetch()
                if (!started && (reqUrl.endsWith(".js") || reqUrl.contains("hls.min")
                            || reqUrl.contains("hls.js") || reqUrl.contains("/js/"))) {
                    Log.d("PlayerMobile", "DaddyLive proxy: blocking script: ${reqUrl.takeLast(60)}")
                    return WebResourceResponse(
                        "text/plain", "UTF-8",
                        java.io.ByteArrayInputStream("".toByteArray())
                    )
                }
                return null
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (started) return
                started = true
                Log.d("PlayerMobile", "DaddyLive proxy WebView loaded CDN page, url=$url")

                // ── 3. Hide the overlay WebView (stop its playback) ──
                hideWebViewOverlay()

                // ── 4. Build HLS source with WebViewDataSource ──
                val webViewDsFactory = DefaultDataSource.Factory(
                    ctx,
                    com.streamflixreborn.streamflix.utils.WebViewDataSource.Factory(proxyWv)
                )

                val mediaItem = MediaItem.Builder()
                    .setUri(m3u8Url.toUri())
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setMediaServerId(server.id)
                            .build()
                    )
                    .build()

                val hlsSource = androidx.media3.exoplayer.hls.HlsMediaSource.Factory(webViewDsFactory)
                    .createMediaSource(mediaItem)

                // ── 5. Play in ExoPlayer ──
                if (!::player.isInitialized) return
                player.setMediaSource(hlsSource)
                player.prepare()
                player.playWhenReady = true
                usingWebView = true

                Log.d("PlayerMobile", "DaddyLive → ExoPlayer playing via WebViewDataSource (cdnPage=$url)")
            }
        }

        // ── 2. Load the CDN iframe page to establish the player session ──
        // The CDN validates .ts segment requests against an active session
        // created when its iframe page loads. Without this, .ts gets 403.
        if (cdnPageUrl != null) {
            Log.d("PlayerMobile", "DaddyLive proxy: loading CDN iframe page: ${cdnPageUrl.take(120)}")
            proxyWv.loadUrl(cdnPageUrl)
        } else {
            // Fallback: use loadDataWithBaseURL (m3u8 will work but .ts may 403)
            Log.w("PlayerMobile", "DaddyLive proxy: no CDN page URL captured, using loadDataWithBaseURL origin=$cdnOrigin")
            proxyWv.loadDataWithBaseURL(
                "$cdnOrigin/",
                "<html><head></head><body></body></html>",
                "text/html", "UTF-8", null
            )
        }
    }

    private fun releasePlayer() {
        stopProgressHandler()
        binding.pvPlayer.player = null
        binding.settings.player = null
        binding.settings.subtitleView = null
        if (::player.isInitialized) {
            activePlayerListener?.let { try { player.removeListener(it) } catch (_: Exception) {} }
            player.release()
        }
        activePlayerListener = null
        if (::mediaSession.isInitialized) {
            mediaSession.release()
        }
        // Release shared WebView used by WebViewDataSource
        if (usingWebView) {
            com.streamflixreborn.streamflix.extractors.LuluVdoExtractor.releaseSharedWebView()
            com.streamflixreborn.streamflix.extractors.NetuExtractor.releaseSharedWebView()
            usingWebView = false
        }
        // Release DaddyLive proxy WebView
        daddyLiveProxyWebView?.let {
            try { it.stopLoading(); it.destroy() } catch (_: Exception) {}
            (binding.root as? ViewGroup)?.removeView(it)
        }
        daddyLiveProxyWebView = null
    }

    private fun isSerienStreamBypassUrl(url: String): Boolean {
        return runCatching {
            Uri.parse(url).host.equals("s.to", ignoreCase = true)
        }.getOrDefault(false)
    }

    private fun buildSerienStreamBypassUrl(): String? {
        return null
    }

    private fun applyBypassCookies(url: String, cookieHeader: String) {
        val host = runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")
        val targets = linkedSetOf<String>().apply {
            add(url)
            if (host.isNotBlank()) {
                add("https://$host/")
                add("http://$host/")
            }
        }

        val cookieManager = CookieManager.getInstance()
        cookieHeader.split(";")
            .map { it.trim() }
            .filter { it.contains("=") }
            .forEach { cookie ->
                targets.forEach { target ->
                    cookieManager.setCookie(target, cookie)
                }
            }
        cookieManager.flush()
    }
}
