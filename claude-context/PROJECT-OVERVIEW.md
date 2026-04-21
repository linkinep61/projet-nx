# Streamflix — Project Context for Claude

**Date**: 2026-04-21
**Owner**: Guillaume (linkinep61@gmail.com)
**Project**: Streamflix — Android streaming app (Kotlin, ExoPlayer, OkHttp)
**Build**: Gradle, Android SDK, minSdk ~21, targetSdk ~34
**Layout modes**: `APP_LAYOUT=tv` (Android TV) or `APP_LAYOUT=mobile` (phone/tablet) — set in `local.properties`
**Current setting**: `APP_LAYOUT=mobile` (for testing on emulator)

---

## Architecture Summary

Streamflix is a multi-provider streaming aggregator. It scrapes streaming sites (Frembed, FrenchStream, Wiflix, etc.) to find video links, then plays them via ExoPlayer.

### Key Flow
1. **Provider** (e.g. `FrembedProvider`) → gets movie/show metadata
2. **Extractor** (e.g. `FrembedExtractor`) → resolves server list (Voe, Dood, Netu, etc.)
3. **Extractor** (e.g. `NetuExtractor`, `VoeExtractor`) → extracts actual video URL (m3u8/mp4)
4. **Player** (`PlayerTvFragment` or `PlayerMobileFragment`) → plays video via ExoPlayer

### Key Directories
- `app/src/main/java/com/streamflixreborn/streamflix/`
  - `providers/` — site scrapers (FrembedProvider, WiflixProvider, etc.)
  - `extractors/` — video URL extractors (NetuExtractor, VoeExtractor, FrembedExtractor, etc.)
  - `fragments/player/` — ExoPlayer UI (PlayerTvFragment.kt, PlayerMobileFragment.kt)
  - `utils/` — DnsResolver, NetworkClient, UserPreferences, etc.
  - `models/` — Video, Movie, TvShow, Episode, etc.

---

## Recent Work — Netu/cfglobalcdn Fix (April 2026)

### The Problem
French ISPs block `cfglobalcdn.com` (Netu's CDN) at both DNS and TCP level:
- DNS: ISP DNS returns wrong/no IP for cfglobalcdn subdomains
- TCP: Some resolved IPs (e.g. 84.16.243.199) are blocked at network level

cfglobalcdn subdomains use CNAME chains:
- `d65f47.cfglobalcdn.com` → CNAME `c28.netu.tv` → A `50.7.230.18` (**REACHABLE**)
- `4fw4gd.cfglobalcdn.com` → CNAME `c6.netu.tv` → A `84.16.243.199` (**BLOCKED by ISP**)

### The Solution (implemented in both TV and Mobile players)

#### 1. Domain-based DataSource Selection
Three DataSource types based on video URL domain:
- **vidzy.live** → `CronetDataSource` (Chrome network stack for JA3/TLS fingerprint bypass)
- **cfglobalcdn.com** → `OkHttpDataSource` with custom DoH DNS resolver
- **everything else** → `DefaultHttpDataSource` (standard Java HttpURLConnection)

#### 2. JSON DoH DNS Resolver (for cfglobalcdn)
Custom `okhttp3.Dns` implementation that:
- Queries Cloudflare's JSON DoH API (`cloudflare-dns.com/dns-query?name=...&type=A`)
- Properly follows CNAME chains (unlike OkHttp's wire-format DoH)
- Returns the final A record IP while keeping original hostname for TLS/SNI

#### 3. TCP Pre-check (fast ISP block detection)
After DNS resolution, immediately tests TCP connectivity to port 443 with 3-second timeout.
If the IP is blocked by the ISP, throws `SocketTimeoutException` immediately instead of waiting 20+ seconds for ExoPlayer's timeout.

#### 4. Auto-fallback on Connection Timeout
`onPlayerError()` detects timeout/block errors and automatically switches to the next server in the list (e.g. Netu → Voe → Dood).

#### 5. Server Priority (Netu last)
In `FrembedExtractor.kt`, Netu has reliability score 99 (lowest priority), so other servers (Voe=5, Dood=6) are tried first.

---

## Modified Files

### `PlayerTvFragment.kt` (Android TV player)
**Path**: `app/src/main/java/com/streamflixreborn/streamflix/fragments/player/PlayerTvFragment.kt`
- Added `usingDoH` flag (line ~158)
- Added `needsDoH()` function (line ~1748)
- Updated `createHttpDataSourceFactory()` to route cfglobalcdn → DoH (line ~1758)
- Added `createDoHOkHttpDataSourceFactory()` with JSON DoH + TCP pre-check (line ~1794)
- Renamed old OkHttp factory to `createDefaultHttpDataSourceFactory()` (line ~1854)
- Updated `displayVideo()` with DoH dataSource mismatch detection (line ~1032)
- Updated `onPlayerError()` with ISP blocked detection + auto-fallback (line ~1328)

### `PlayerMobileFragment.kt` (Phone/tablet player)
**Path**: `app/src/main/java/com/streamflixreborn/streamflix/fragments/player/PlayerMobileFragment.kt`
- Added `usingCronet` and `usingDoH` flags
- Added `needsCronet()`, `needsDoH()` functions
- Added `createHttpDataSourceFactory()`, `createDoHOkHttpDataSourceFactory()`, `createDefaultHttpDataSourceFactory()`
- Updated `displayVideo()` with DataSource switching
- Updated `onPlayerError()` with Cronet fallback + auto-server-switch on timeout

### `NetuExtractor.kt`
**Path**: `app/src/main/java/com/streamflixreborn/streamflix/extractors/NetuExtractor.kt`
- Added `rewriteCfglobalcdn()` (currently no-op — returns URL unchanged)
- Applied wrapper to all 3 video URL return points
- Added `Log` import and `TAG` constant

### `FrembedExtractor.kt`
**Path**: `app/src/main/java/com/streamflixreborn/streamflix/extractors/FrembedExtractor.kt`
- Added Voe=5, Dood=6, Netu=99 to `reliabilityOrder` map
- Changed `defaultReliability` from 5 to 7

### `local.properties`
- `APP_LAYOUT=mobile` (was `tv`)

---

## VP2 match_parent Crash Fix
Fixed ViewPager2 crash ("Pages must fill the whole ViewPager2") in mobile swiper.
- **File**: `adapters/viewholders/CategoryViewHolder.kt`
- **Fix**: `disableViewPager2EnforceChildFill()` — uses reflection to access VP2's internal RecyclerView and clears the `OnChildAttachStateChangeListeners` that enforce the match_parent check.
- Called before setting the adapter in `displayMobileSwiper()`.

---

## Key Technical Details

### DataSource Selection Logic
```
URL contains "vidzy.live"     → CronetDataSource (JA3 bypass)
URL contains "cfglobalcdn.com" → OkHttpDataSource + JSON DoH DNS + TCP pre-check
Everything else               → DefaultHttpDataSource (system DNS)
```

### DNS Resolution Chain (cfglobalcdn)
```
1. Cloudflare JSON DoH API: GET https://cloudflare-dns.com/dns-query?name=HOSTNAME&type=A
2. Parse Answer array, extract type=1 (A records)
3. TCP pre-check: connect to IP:443 with 3s timeout
4. If blocked → throw SocketTimeoutException → ExoPlayer error → auto-switch server
5. If reachable → return IPs, ExoPlayer streams normally
```

### Netu Extractor Flow
```
1. Load embed page (netu.frembed.bond/e/ID or waaw1.tv/e/ID)
2. Try direct video URL extraction (regex: player.src, sources, .m3u8, .mp4, data-src)
3. Try iframe extraction (recursive)
4. For non-Frembed: try hash/POST flow (getVideo API)
5. Return Video(source=URL, headers={Referer})
```

### Auto-Fallback in onPlayerError
```
Error detected → check causeMsg for:
  - SocketTimeoutException, failed to connect, Connection timed out
  - ERR_CONNECTION_TIMED_OUT, ISP blocked, cfglobalcdn IP
→ Get next server in list → viewModel.getVideo(nextServer)
```

### Frembed Architecture
- Main site: frembed.cyou (Next.js on Cloudflare)
- Iframe chain: /movies/TITLE/TMDB → /embed/movie/TMDB → /films?id=TMDB → netu.frembed.bond/e/ID
- API: /api/films?id=TMDB returns link1..link7 (VF), link1vostfr..link7vostfr, link1vo..link7vo
- Each link redirects (302) to actual extractor URL (voe, dood, netu, etc.)
- Server order sorted by: language (French > VOSTFR > VO) then reliability score

---

## Unsolved / Future Work

1. **Netu on truly blocked IPs**: When cfglobalcdn resolves to 84.16.243.199 (blocked at TCP level), nothing works except a proxy. The TCP pre-check detects this in 3s and falls back to next server. To actually play from blocked Netu servers, would need a proxy mechanism (e.g. route through Cloudflare Workers or similar).

2. **Frembed web player investigation**: We tried to see how frembed.cyou's web player bypasses the block. Found that for Fierce Town, Frembed doesn't even offer Netu as a server (only Voe/Dood). The `/api/dns` endpoint is just an ISP status monitor, not a proxy.

3. **Testing needed**: The DoH fix should work for cfglobalcdn URLs that resolve to unblocked IPs (50.7.230.18). Need to find a movie that uses an unblocked Netu CDN server to verify.

---

## Build Instructions
```
# In local.properties:
APP_LAYOUT=tv      # for Android TV build
APP_LAYOUT=mobile  # for phone/tablet build

# Gradle sync then Run
# Filter logcat with: "PlayerNetwork" or "NetuExtractor"
```

## Key Log Tags
- `PlayerNetwork` — DataSource selection, DoH resolution, TCP pre-check, fallback
- `NetuExtractor` — Netu extraction steps
- `PlayerTvFragment` / `PlayerMobileFragment` — Player errors
