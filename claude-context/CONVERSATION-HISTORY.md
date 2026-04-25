# Conversation History — Streamflix Netu Fix

## Session 1 (compacted)

### User Request
Fix Netu video playback which fails because cfglobalcdn.com CDN is unreachable from French ISPs.

### Investigation Steps
1. User asked to inspect the Frembed web player page to understand how the web version handles the blocked CDN
2. Sent reference page: `https://frembed.cyou/movies/fierce-town/584855`
3. Provided full m3u8 URL: `https://d65f47.cfglobalcdn.com/silverlight/secip/25789/9471/yWginGTQHCkG7SIuzZ4MtA/MjAyLjkwLjcyLjEwMQ==/1776784598/hls-vod-s0012/flv/api/files/videos/2026/04/19/1776620589jljsc.mp4.m3u8`

### Discoveries
- cfglobalcdn.com subdomains use CNAME chains to different CDN servers
- d65f47.cfglobalcdn.com → c28.netu.tv → 50.7.230.18 (REACHABLE)
- 4fw4gd.cfglobalcdn.com → c6.netu.tv → 84.16.243.199 (BLOCKED by French ISPs)
- OkHttp's wire-format DoH doesn't follow CNAME chains properly
- Frembed Service Worker is for domain failover (cyou ↔ one), NOT video proxying

### Fixes Applied (TV Player)
1. Created JSON DoH DNS resolver using Cloudflare API → follows CNAME chains
2. Added domain-based DataSource selection (Cronet for vidzy, DoH for cfglobalcdn, Default for rest)
3. Added DataSource mismatch detection in displayVideo()
4. Fixed build error: "Unresolved reference 'Request'" → used fully qualified okhttp3.Request.Builder()

### Errors Encountered
- CNAME rewrite (d65f47 → c28.netu.tv) caused SSL cert mismatch → reverted to keep original hostname
- Wire-format DoH resolved to blocked IP 84.16.243.199 → switched to JSON DoH API
- JSON DoH also returns 84.16.243.199 for some subdomains (genuinely resolves there) → IP is blocked at TCP level

### Testing
- User tested with video on blocked CDN (4fw4gd → 84.16.243.199) → timeout, auto-switch to VOE worked
- Need to test with Fierce Town (d65f47 → 50.7.230.18) to verify DoH fix for unblocked CDNs

---

## Session 2 (current — 2026-04-21)

### Frembed Web Player Investigation
- Navigated to frembed.cyou/movies/fierce-town/584855 via Chrome MCP
- Found /api/dns endpoint → just ISP status monitor (Orange/Free/SFR/Bouygues all "Working")
- Frembed doesn't offer Netu for Fierce Town — only Voe and Dood
- Couldn't see iframe network requests (cross-origin restriction)
- Conclusion: Frembed simply doesn't use Netu when it's unreliable

### TCP Pre-check Implementation
Added fast ISP block detection in both TV and Mobile players:
- After DoH DNS resolution, test TCP connect to resolved IP on port 443 (3s timeout)
- If blocked → throw SocketTimeoutException immediately → auto-fallback in ~3s instead of 20s
- Reduced OkHttp DoH client timeouts from 20s/30s to 8s/15s

### Server Priority Change
In FrembedExtractor.kt, set Netu reliability to 99 (dead last):
```
Vidara=1, Vidsonic=2, Rpmvid=3, StreamWish=4, Streamix=4, Voe=5, Dood=6, Filemoon=10, Netu=99
```

### Mobile Player Parity
Applied ALL fixes from TV player to PlayerMobileFragment.kt:
- usingCronet / usingDoH flags
- needsCronet() / needsDoH() detection
- createHttpDataSourceFactory() with domain-based routing
- createDoHOkHttpDataSourceFactory() with JSON DoH + TCP pre-check
- createDefaultHttpDataSourceFactory()
- displayVideo() DataSource mismatch detection
- onPlayerError() Cronet fallback + auto-server-switch

### Layout Switch
Changed `local.properties` APP_LAYOUT from `tv` to `mobile` for emulator testing.

---

## User Communication Style
- Writes in French (often informal/caps)
- "ET" = "and? / what next?"
- "PAS DE VIDEO" = "no video"
- "TOUJOURE PAS" = "still not working"
- "YA CA" = "that's all"
- "LES CODE POUR LES LOGE" = log filter keywords
- Sends logcat screenshots and raw log pastes
- Expects direct action, minimal explanation

---

## Session 3 (2026-04-23) — Multi-provider UI fixes + Wiflix + LuluVdo

### Série FR / VOSTFR Navigation Fix
- Movie objects in Série FR/VOSTFR tabs redirectaient vers MovieDetail au lieu de TvShowDetail
- Ajouté `action_movies_to_tv_show` dans nav_main_graph_mobile.xml
- Modifié MovieViewHolder: `isDramaOrAnimeProvider()` helper (VoirDrama/VoirAnime seulement, PAS AnimeSama)
- Navigation redirect dans displayMobileItem(), displayGridMobileItem(), displaySwiperMobileItem()
- Ajouté `android:defaultValue="@null"` pour les args nullable (poster, banner) du tv_show fragment

### Film Detection
- TvShowViewHolder: "Regarder maintenant" au lieu de "Regarder S1 E1" quand seasons≤1 && episodes≤1
- TvShowMobileFragment: hide section Saisons pour les films (1 season avec ≤1 episode)

### Episode Thumbnails
- VoirDramaProvider + VoirAnimeProvider: extraction du poster de la série via `.summary_image img`
- Passé comme `poster = showPoster` dans le constructeur Episode → plus de placeholders gris

### Empty Content Filter
- VoirDrama/VoirAnime: `parseHomeItem()` retourne null si `.list-chapter` existe mais `.chapter-item` est vide
- AnimeSama: supprimé le "ultimate fallback" qui créait une fake Season 1
- AnimeSama: filtre badge "Scan" dans getHome()
- AnimeSama: probe episodes.js pour items featured (20 candidats → garde 15 avec contenu)

### Tab Renaming
- strings.xml: "Série FR" → "FR", "Série VOSTFR" → "VOSTFR"
- MainMobileActivity: customisation tabs pour VoirDrama/VoirAnime (AnimeSama revert après erreur)

### Wiflix Provider Improvements
- Added FEATURED category from TOP Séries (banner = poster)
- Parsing "Derniers Episodes" via div.base/div.base-hd/ul.last
- getServers(): `mapIndexed` → `mapIndexedNotNull`, filtre src vide
- Vrais noms de serveurs via `Extractor.identifyServiceName(src)` → "Rpmvid — Lecteur 1" au lieu de "Lecteur 1"
- `deduplicateServers()` pour éliminer doublons même service sur domaines différents
- Serveurs triés: VF first → service reliability → VOSTFR last

### RpmvidExtractor Updates
- Nouveaux alias: flemmix.farm, flemmix.rpmlive.online, flemmix.upns.xyz, etc.
- `rotatingDomain` patterns: `flemmix\.[a-z]+(?:\.[a-z]+)?/embed` et `/e/`
- `extractId()` amélioré: supporte 3 formats (#ID, /e/ID, ?id=ID)

### LuluVdo Extractor Fix (principal)
**Problème**: LuluVdo fonctionnait sur le site web mais pas dans l'app
**Diagnostic**:
1. Premier problème: OkHttpClient custom sans cookies/headers → Cloudflare bloquait (timeout 45s)
2. Solution: utiliser `NetworkClient.default` (cookies partagés WebView, headers navigateur, TLS configuré)
3. Requête passe (200 OK en ~470ms) mais vidéo ne jouait toujours pas
4. Deuxième problème: `JsoupConverterFactory` modifiait le HTML JavaScript lors du parsing
5. Solution: changé pour `ScalarsConverterFactory` (HTML brut en String)
6. Ajouté logs debug à chaque étape (tag: LuluVdoExtractor)
**Résultat**: Extraction réussie — page 53KB, unpack JS 3922 chars, source HLS trouvée

### Domaines vérifiés sur Wiflix (flemmix.wales)
- luluvdo.com → LuluVdoExtractor ✓
- charlestoughrace.com → VoeExtractor ✓ (alias)
- playmogo.com → DoodLaExtractor ✓ (alias)
- flemmix.upns.pro → RpmvidExtractor ✓ (alias)
- vidara.to → VidaraExtractor ✓
- vidsonic.net → VidsonicExtractor ✓

### Erreurs et corrections
- AnimeSama ajouté par erreur à isDramaOrAnimeProvider() et MainMobileActivity → revert
- LuluVdo "ignoreServerUrl()" créé pour filtrer → supprimé car LuluVdo fonctionne, remplacé par deduplicateServers()
- LuluVdo User-Agent Chrome/124 → Chrome/131
- Referer double slash "$mainUrl/" → mainUrl (sans double //)

### User Communication Notes
- "Holà holà j'ai pas parlé de animsama" = ne touche pas à ce que j'ai pas demandé
- "Et tu peux réparer ça Allez Go tout seul Tu peux te débrouiller" = autonomie attendue
- "Prends la main et va sur le site principal" = utilise Chrome MCP pour tester
- Envoie screenshots du site + logs Logcat
