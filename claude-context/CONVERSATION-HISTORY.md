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
