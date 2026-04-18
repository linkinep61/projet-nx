# StreamFlix - Notes de session

## Base
- Repo: https://github.com/streamflix-reborn/streamflix.git
- Branche: main, commit 06ea3af8 (v1.7.114)
- APK original: streamflix-v1.7.114-070e8bbf.apk (070e8bbf = hash build GitHub Actions)
- Compatible TOUS appareils (APP_LAYOUT=null, comme l'APK original)

## Clés (local.properties)
- TMDB_API_KEY=adc5047f27e588c9347087931a696cf4
- SUBDL_API_KEY=null
- RABBITSTREAM_SOURCE_API= (vide)
- APP_LAYOUT=null (compatible tous appareils, comme l'APK original)

## Providers custom (non trackés par git, À PRÉSERVER lors de git pull)
- WiflixProvider.kt (bypass Cloudflare via WebViewResolver, init() requis dans Activities)
- UnJourUnFilm2Provider.kt (version custom de UnJourUnFilmProvider)
- aploufProvider.kt (movies only, pas de tvShows)
- AnimeSamaProvider.kt (anime-sama.to, FR, films + séries)

## Modifications par rapport au git original (À RÉAPPLIQUER après chaque git pull)
1. **MainTvActivity.kt** + **MainMobileActivity.kt**: import + WiflixProvider.init(this)
2. **WebViewResolver.kt**: content markers Wiflix (block-main, mov-t, mov-list, posterimg)
3. **BypassWebViewActivity.kt**: domaines flemmix.farm, flemmix.upns.pro, wiflix.zone, wiflix.dev, wiflix.fun dans isAllowedBypassHost()
4. **Provider.kt**: UnJourUnFilm2Provider, aploufProvider et AnimeSamaProvider dans la map providers
5. **TmdbProvider.kt**: FR servers - ajout serveurs globaux en complément des serveurs FR

## URLs extraites de l'APK original (providers)
- AfterDark: https://afterdark.best/
- Cine24h: https://cine24h.online
- Frembed: https://frembed.bond/ (fallback: https://frembed.work)
- Wiflix/Flemmix: https://flemmix.upns.pro (portal: https://ww1.wiflix-adresses.fun/)
- VoirFilm: https://api.voirfilm.cam
- StreamingIta: https://streamingita.homes
- Moflix: https://moflix-stream.xyz (mirrors: .click, .fans, .link, https://moflix.upns.xyz, https://moflix.rpmplay.xyz)
- VidLink: https://vidlink.pro
- VixSrc: (uses vidsrc.to, vidsrc.ru, vidsrc-embed.ru)
- Videasy: https://api.videasy.net (player: https://player.videasy.net/, decrypt: https://enc-dec.app/api/dec-videasy)
- Vidzee: https://player.vidzee.wtf (core: https://core.vidzee.wtf)
- Vidrock: https://vidrock.net
- Vidflix: https://vidflix.club
- PrimeSrc: https://primesrc.me
- MoviesAPI: https://moviesapi.club/
- 2Embed: https://www.2embed.cc
- Keys: https://keys4.fun, https://raw.githubusercontent.com/Ciarands/vidsrc-keys/main/keys.json, https://raw.githubusercontent.com/Rowdy-Avocado/multi-keys/keys/index.html
- AfterDark proxies: https://proxy.afterdark.baby/alejandro, /boom-clap, /elizabeth-taylor, /rolly
- Rabbitstream: https://rabbitstream.net
- StreamWish domains: .biz, .cc, .club, .com, .fun, .info, .live, .me, .net, .org, .site, .to
- Zilla: https://player.zilla-networks.com
- GxPlayer: https://watch.gxplayer.xyz
- FluxCDN: https://sv1.fluxcedene.net/api/
- FkPlayer: https://fkplayer.xyz/api/decoding

## Hex Keys trouvées dans l'APK (crypto/extractors)
- adc5047f27e588c9347087931a696cf4 (TMDB)
- c8032935fa94ff8ccc97265b88043695 (extractor related)
- de06549424a3b59673ca7f9023042e52 (extractor related)
- fab377736647fa77cfe9a15b380c0869 (extractor related)
- 4200356cf564f1245fa49295ad6bd2a4 (extractor related)
- 24758778992d2473ae2618adf856f8902a675718eef18169c854d07d1fcad298

## Providers dans l'APK original (tous)
AfterDark, Altadefinizione01, AniWorld, AnimeAv1, AnimeBum, AnimeFlv, AnimeSaturn, AnimeUnity, AnimeWorld, Animefenix, AnyMovie, CB01, CableVisionHD, Cine24h, CineCalidad, CuevanaEu, Doramasflix, Einschalten, FilmPalast, FilmyOnlineCc, FlixLatam, Frembed, FrenchAnime, FrenchManga, FrenchStream, GuardaFlix, GuardaSerie, HDFilme, HiAnime, Kidraz, LaCartoons, Latanime, MEGAKino, MStream, Otakufr, Pelisplusto, PoseidonHD2, Ridomovies, SerienStream, Sflix, SoloLatino, StreamingCommunity, StreamingIta, SuperStream, Tmdb, TvporinternetHD, UnJourUnFilm, Wiflix, Zeriun

## Extractors dans l'APK original (tous)
AfterDark, AmazonDrive, ApiVoirFilm, BigWarp, Chillx, Closeload, Dailymotion, DoodLa, Dropload, Einschalten, Filemoon, Frembed, Fsvid, Goodstream, GoogleDrive, Gupload, GxPlayer, Hxfile, Lamovie, LoadX, LuluVdo, MStreamClick, MStreamDay, MagaSavor, MailRu, MixDrop, Moflix, Moviesapi, MyFileStorage, Okru, OnRegardeOu, Oneupload, PDrain, Pcloud, PlusPomla, PrimeSrc, Rabbitstream, Ridoo, Rpmvid, SaveFiles, ShareCloudy, StreamUp, StreamWish, Streamhub, Streamix, Streamruby, Streamtape, Supervideo, TwoEmbed, USTR, UpZur, Upzone, Uqload, Veev, VidGuard, VidHide, VidLink, VidMoLy, VidPly, Vidara, Videasy, VideoSibNet, Vidflix, Vidnest, Vidora, Vidoza, Vidplay, Vidrock, Vidsonic, VidsrcNet, VidsrcRu, VidsrcTo, Vidzee, Vidzy, VixSrc, Vixcloud, Voe, Vtube, YourUpload, Zilla

## PROCÉDURE MISE À JOUR (à suivre à chaque git pull)
1. `git pull origin main` (depuis le terminal Windows)
2. Vérifier que les providers custom sont toujours là (non trackés, normalement préservés) :
   - `app/src/main/java/.../providers/WiflixProvider.kt`
   - `app/src/main/java/.../providers/UnJourUnFilm2Provider.kt`
   - `app/src/main/java/.../providers/aploufProvider.kt`
3. Réappliquer les modifications suivantes :
   a. **MainTvActivity.kt** : ajouter `import ...WiflixProvider` + `WiflixProvider.init(this)` après `Cine24hProvider.init(this)`
   b. **MainMobileActivity.kt** : idem
   c. **WebViewResolver.kt** : ajouter dans hasContent check : `|| cleanHtml.contains("block-main") || cleanHtml.contains("mov-t") || cleanHtml.contains("mov-list") || cleanHtml.contains("posterimg")`
   d. **BypassWebViewActivity.kt** : ajouter dans isAllowedBypassHost() : `|| host.endsWith("flemmix.farm") || host.endsWith("flemmix.upns.pro") || host.endsWith("wiflix.zone") || host.endsWith("wiflix.dev") || host.endsWith("wiflix.fun") || host.endsWith("flemmix.golf") || host.endsWith("flemmix.irish") || host.endsWith("flemmix.town") || host.endsWith("flemmix.vip") || host.endsWith("wiflix.red") || host.endsWith("wiflix.re")`
   e. **Provider.kt** : ajouter dans la map providers : `UnJourUnFilm2Provider to ProviderSupport(movies = true, tvShows = true)`, `aploufProvider to ProviderSupport(movies = true, tvShows = false)` et `AnimeSamaProvider to ProviderSupport(movies = true, tvShows = true)`
   f. **TmdbProvider.kt** bloc "fr" : ajouter les serveurs globaux (VixSrc, TwoEmbed, VidsrcNet, VidLink, VidsrcRu, Vidflix, Vidrock, Vidzee, PrimeSrc) en complément des serveurs FR, et utiliser `FrembedProvider.defaultBaseUrl` / `AfterDarkProvider.defaultBaseUrl` en fallback quand le cache est vide
4. Vérifier local.properties (pas touché par git pull) :
   - TMDB_API_KEY=adc5047f27e588c9347087931a696cf4
   - APP_LAYOUT=null
5. Build : `.\gradlew assembleDebug` ou commande FULL via agent_bridge.ps1

## Build
- agent_bridge.ps1 v3 avec logcat auto-géré
- Commande: FULL (build + install + launch + logcat)
- Package debug: com.streamflixreborn.streamflix.debug

## Build Release
- APK: `app/build/outputs/apk/release/app-release.apk` (~18,4 Mo)
- Package: com.streamflixreborn.streamflix (sans suffixe .debug)
- Keystore: `streamflix-release.jks` (à la racine du projet)
- Alias: streamflix / Password: streamflix2024
- Mises à jour auto: DÉSACTIVÉES (return@launch dans MainViewModel.checkUpdate())
- Compatible TV + Smartphone (APP_LAYOUT=null)
- Via Android Studio: Build > Generate Signed App Bundle / APK > APK > release
- Script alternatif: `build-release.bat` (nécessite JAVA_HOME)

## Modifications supplémentaires (par rapport au git original)
6. **VoeExtractor.kt**: ajout de `"https://jefferycontrolmodel.com"` dans aliasUrls (domaine alias Voe utilisé par Wiflix Lecteur 3)
7. **StreamWishExtractor.kt**: ajout de `"https://smoothpre.com"` dans aliasUrls (serveur Smoothpre utilisé par anime-sama)
8. **OneuploadExtractor.kt**: ajout de `"https://oneupload.to"` dans aliasUrls (domaine oneupload.to utilisé par anime-sama, en plus de oneupload.net)
9. **MainViewModel.kt**: `return@launch` ajouté en haut de `checkUpdate()` pour désactiver les mises à jour auto
10. **BypassWebViewActivity.kt**: ajout des domaines flemmix.golf, flemmix.irish, flemmix.town, flemmix.vip, wiflix.red, wiflix.re dans isAllowedBypassHost()
11. **build.gradle (app)**: ajout signingConfigs.release avec keystore streamflix-release.jks

## Problèmes connus
- Debug build (.debug) = app séparée, SharedPreferences vides, WebView data isolé
- FrembedProvider/AfterDarkProvider: URLs en cache vides sur debug → fallback defaultBaseUrl
- TmdbProvider FR: Frembed/AfterDark retournent souvent 0 serveurs → serveurs globaux ajoutés en complément
- VideasyExtractor: API enc-dec.app peut retourner vide → JSONException
- Turnstile bypass: ne résout pas sur debug build (hypothèse: isolation package)

## AnimeSamaProvider (anime-sama.to)
- Provider complet: search, home, movies, tvshows, episodes, servers, video
- Search: POST vers `/template-php/defaut/fetch.php` avec paramètre `query`
- Images: `https://raw.githubusercontent.com/Anime-Sama/IMG/img/contenu/{slug}.jpg`
- Episodes: parsing de `episodes.js` (regex `var epsN = [...]`) pour chaque lecteur
- Serveurs: extraction multi-lecteur + multi-langue (vostfr, vf, vastfr)
- Logo: `https://anime-sama.to/img/icon.png`
- Video: direct MP4 pour anime-sama.fr, Extractor.extract() pour hosts connus, fallback embed
- Serveurs triés: Minochinos en premier (le plus fiable), puis les autres
- getHome(): parse `.grabScroll` containers avec h2 section headers
  - Sections: Sorties du [Jour], Derniers épisodes ajoutés, Derniers contenus sortis
  - "Derniers épisodes ajoutés" et "Derniers contenus sortis" en priorité (haut de page)
  - Filtré: "Scans" et "Reprenez votre visionnage" (nécessite cookies)
  - Titres: img[alt] > h2.card-title > slug humanisé
  - Type: badge "Film" → Movie, sinon TvShow
- OkHttp avec `withContext(Dispatchers.IO)` pour éviter ANR

## Serveurs AnimeSama - État
- **Minochinos** (minochinos.com): ✅ fonctionne via Extractor fallback, priorisé en 1er
- **Sibnet** (video.sibnet.ru): ✅ extraction custom MP4 dans getVideo()
- **SendVid** (sendvid.com): ✅ extraction custom via JSoup dans getVideo()
- **VK/VKVideo** (vk.com, vkvideo.ru): ✅ passthrough avec Referer
- **Smoothpre** (smoothpre.com): ✅ ajouté aux alias StreamWish → extraction JWPlayer
- **OneUpload** (oneupload.to): ✅ ajouté aux alias OneUpload → extraction JWPlayer
- **VidMoLy** (vidmoly.to): ❌ JWT + JS anti-bot, non réparable sans WebView
- **Lpayer** (lpayer.embed4me.com): ❌ API chiffrée côté client, complexe à reverse-engineer

## Résolu
- Wiflix Lecteur 3 (Voe): jefferycontrolmodel.com ajouté aux aliasUrls → extraction m3u8 OK, lecture OK
- Swish (hgcloud.to): ajouté aux alias StreamWish → extraction OK
- AnimeSamaProvider: homepage complète avec toutes les sections, build OK, déployé
- AnimeSamaProvider: logo ajouté (anime-sama.to/img/icon.png)
- AnimeSamaProvider: Minochinos priorisé en premier dans la liste des serveurs
- AnimeSamaProvider: fix image flickering (data-src fallback, filtrage data: URIs)
- Smoothpre: ajouté aux alias StreamWish → extraction OK
- OneUpload (oneupload.to): ajouté aux alias → extraction OK

## Vérification directe sur les sites (Chrome MCP)
- **anime-sama.to**: vérifié episodes.js de Chainsaw Man → tous les serveurs confirmés (Smoothpre, Sibnet, OneUpload, SendVid, VidMoLy, anime-sama.fr)
- **Wiflix portal** (ww1.wiflix-adresses.fun): en ligne, nouveaux domaines découverts → flemmix.golf, flemmix.irish, flemmix.town, flemmix.vip, wiflix.red, wiflix.re (ajoutés au bypass)
- **flemmix.farm**: en ligne ✅
- **frembed**: accessible ✅
- **afterdark**: accessible ✅
- **vidlink.pro**: accessible ✅
- **cine24h.online**: DOWN ❌ (site hors ligne)

## Non réparable (limitation structurelle)
- VidMoLy (vidmoly.biz/to): le site utilise JWT + JS rendering anti-bot, Retrofit/JSoup ne peut pas charger le player. Nécessiterait WebView. Échoue aussi sur l'APK original.
- Lpayer (lpayer.embed4me.com): API `/api/v1/info?id=X` retourne des données chiffrées, déchiffrement client-side via vidstack.js. Nécessiterait reverse-engineering du JS.
