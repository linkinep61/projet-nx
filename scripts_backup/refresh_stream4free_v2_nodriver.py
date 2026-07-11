#!/usr/bin/env python3
"""
Stream4Free scraper -- nodriver (CDP direct) + Xvfb virtual display.

CF Turnstile bypass via genuine Chrome (non-headful) on a virtual display.
nodriver = official successor of undetected-chromedriver, communicates via CDP,
no Selenium/WebDriver detection vectors.

Key insight: CF detects headless mode -> we run headful Chrome on Xvfb.
"""

import asyncio
import json
import logging
import os
import re
import sys
import time
from html.parser import HTMLParser
from pathlib import Path

# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------
logging.basicConfig(
    level=logging.INFO,
    format="[%(asctime)s] %(levelname)s: %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
    stream=sys.stdout,
)
log = logging.getLogger(__name__)

# Force stdout flush (GitHub Actions buffers)
_orig_print = print
def print(*a, **kw):
    kw.setdefault("flush", True)
    _orig_print(*a, **kw)

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------
PAGE_URL = "https://www.stream4free.tv/tv-live-france"
M3U_FILE = Path("data-stream4free.m3u")
MAX_RETRIES = 3
CF_WAIT_INITIAL = 12           # seconds to wait for auto-solve (no click)
CF_WAIT_AFTER_CLICK = 12       # seconds to wait after clicking Turnstile
CF_WAIT_EXTRA = 5              # extra wait for JS render
PAGE_LOAD_WAIT = 8             # seconds to wait for JS rendering
CF_INDICATORS = [
    "just a moment",
    "checking your browser",
    "turnstile",
    "cf-challenge",
    "cf_clearance",
    "security check",
    "please wait",
    "verification",
    "enable javascript",
]

# ---------------------------------------------------------------------------
# HTML parser for channel extraction
# ---------------------------------------------------------------------------
class ChannelParser(HTMLParser):
    """Extract channel name + page URL from stream4free listing page.

    Channel cards are <a href="/slug"> containing <img src="/images/avatars/...">
    Nav/menu items have images in /images/menu/ or /images/megamenu/ -- skip those.
    """

    def __init__(self):
        super().__init__()
        self.channels = []  # list of (name, href)
        self._in_a = False
        self._current_href = None
        self._current_text = ""
        self._has_avatar_img = False

    def handle_starttag(self, tag, attrs):
        d = dict(attrs)
        if tag == "a":
            href = d.get("href", "")
            if href.startswith("/") and len(href) > 2:
                self._in_a = True
                self._current_href = href
                self._current_text = ""
                self._has_avatar_img = False
        elif tag == "img" and self._in_a:
            src = d.get("src", "")
            if "/images/avatars/" in src:
                self._has_avatar_img = True

    def handle_data(self, data):
        if self._in_a:
            self._current_text += data

    def handle_endtag(self, tag):
        if tag == "a" and self._in_a:
            name = self._current_text.strip()
            if name and self._current_href and self._has_avatar_img:
                self.channels.append((name, self._current_href))
            self._in_a = False
            self._current_href = None
            self._current_text = ""
            self._has_avatar_img = False


def extract_channels_from_html(html: str) -> list[tuple[str, str]]:
    """Parse the listing page and return (channel_name, channel_page_url)."""
    parser = ChannelParser()
    parser.feed(html)
    return parser.channels


def extract_stream_urls(html: str) -> list[str]:
    """Pull m3u8/mpd URLs from a channel detail page."""
    patterns = [
        r'(?:source|src|file|url)\s*[:=]\s*["\']([^"\']+\.m3u8[^"\']*)["\']',
        r'(?:source|src|file|url)\s*[:=]\s*["\']([^"\']+\.mpd[^"\']*)["\']',
        r'(https?://[^\s"\'<>]+\.m3u8[^\s"\'<>]*)',
        r'(https?://[^\s"\'<>]+\.mpd[^\s"\'<>]*)',
    ]
    urls = []
    seen = set()
    for pat in patterns:
        for m in re.finditer(pat, html, re.IGNORECASE):
            u = m.group(1)
            if u not in seen:
                seen.add(u)
                urls.append(u)
    return urls


# ---------------------------------------------------------------------------
# Virtual display helper
# ---------------------------------------------------------------------------
def setup_virtual_display():
    """Start Xvfb virtual display on Linux (for CI). Returns Display or None."""
    if sys.platform != "linux":
        log.info("Not Linux -- skipping virtual display setup")
        return None
    # Check if a real display is available
    if os.environ.get("DISPLAY"):
        log.info("DISPLAY already set (%s) -- skipping Xvfb", os.environ["DISPLAY"])
        return None
    try:
        from pyvirtualdisplay import Display
        display = Display(visible=False, size=(1920, 1080))
        display.start()
        log.info("Virtual display started (Xvfb) -- DISPLAY=%s", os.environ.get("DISPLAY"))
        return display
    except Exception as e:
        log.warning("Failed to start virtual display: %s -- trying headless fallback", e)
        return None


def is_cf_challenge(html: str) -> bool:
    """Check if the page is a Cloudflare challenge page."""
    if not html:
        return True
    lower = html.lower()
    # Check for clear CF challenge indicators
    cf_strong = ["just a moment", "cf-challenge", "checking your browser", "turnstile"]
    return any(ind in lower for ind in cf_strong)


def is_real_content(html: str) -> bool:
    """Check if we got the real stream4free page (not CF challenge)."""
    if not html or len(html) < 3000:
        return False
    lower = html.lower()
    # Real page should have channel-related content
    content_markers = ["stream4free", "tv-live", "channel", "m3u8", ".mpd", "iframe"]
    return any(m in lower for m in content_markers) and not is_cf_challenge(html)


# ---------------------------------------------------------------------------
# Core: nodriver CF bypass + page fetch
# ---------------------------------------------------------------------------
async def launch_browser_with_retry(headless: bool = False, max_attempts: int = 3):
    """Launch nodriver browser with retry for flaky CDP connections."""
    import nodriver as uc
    import shutil

    # Find Chrome binary -- prefer google-chrome-stable over snap chromium
    chrome_path = (
        shutil.which("google-chrome-stable")
        or shutil.which("google-chrome")
        or shutil.which("chromium-browser")
        or shutil.which("chromium")
    )

    browser_args = [
        "--no-first-run",
        "--no-default-browser-check",
        "--no-sandbox",
        "--disable-dev-shm-usage",
        "--disable-features=Translate",
        "--disable-blink-features=AutomationControlled",
        "--disable-extensions",
        "--disable-popup-blocking",
        "--disable-infobars",
        "--lang=fr-FR",
        "--window-size=1920,1080",
    ]

    for attempt in range(1, max_attempts + 1):
        try:
            log.info("Browser launch attempt %d/%d (headless=%s, binary=%s)",
                     attempt, max_attempts, headless, chrome_path)
            browser = await uc.start(
                headless=headless,
                sandbox=False,
                browser_executable_path=chrome_path,
                browser_args=browser_args,
                lang="fr-FR",
            )
            log.info("Browser connected successfully (attempt %d)", attempt)
            return browser
        except Exception as e:
            log.warning("Browser launch attempt %d failed: %s", attempt, e)
            if attempt < max_attempts:
                await asyncio.sleep(2)
            else:
                raise


async def safe_evaluate(tab, js_expr: str):
    """
    Evaluate JS and reliably return a Python dict.
    nodriver's tab.evaluate() can return list instead of dict for objects.
    Workaround: use JSON.stringify in JS + json.loads in Python.
    """
    try:
        raw = await tab.evaluate(f"JSON.stringify({js_expr})")
        if isinstance(raw, str):
            return json.loads(raw)
        if isinstance(raw, list):
            # nodriver sometimes wraps result in a list
            if len(raw) == 1 and isinstance(raw[0], str):
                return json.loads(raw[0])
            if len(raw) == 1 and isinstance(raw[0], dict):
                return raw[0]
            # Try to convert list of pairs to dict
            try:
                return dict(raw)
            except (ValueError, TypeError):
                return {}
        if isinstance(raw, dict):
            return raw
        return {}
    except Exception as e:
        log.warning("safe_evaluate error: %s", e)
        return {}


async def cdp_click(tab, x: float, y: float):
    """
    Click at (x, y) using CDP Input.dispatchMouseEvent via nodriver's CDP bindings.
    Falls back to raw send if the typed API fails.
    """
    try:
        import nodriver.cdp.input_ as cdp_input
        await tab.send(cdp_input.dispatch_mouse_event(
            type_="mousePressed", x=x, y=y,
            button=cdp_input.MouseButton.LEFT,
            click_count=1, buttons=1
        ))
        await asyncio.sleep(0.05)
        await tab.send(cdp_input.dispatch_mouse_event(
            type_="mouseReleased", x=x, y=y,
            button=cdp_input.MouseButton.LEFT,
            click_count=1, buttons=0
        ))
        return True
    except Exception as e1:
        log.warning("CDP typed click failed (%s) -- trying raw CDP", e1)
        try:
            # Fallback: raw CDP dict (works in some nodriver versions)
            await tab.send({"method": "Input.dispatchMouseEvent", "params": {
                "type": "mousePressed", "x": int(x), "y": int(y), "button": "left",
                "clickCount": 1, "buttons": 1
            }})
            await asyncio.sleep(0.05)
            await tab.send({"method": "Input.dispatchMouseEvent", "params": {
                "type": "mouseReleased", "x": int(x), "y": int(y), "button": "left",
                "clickCount": 1, "buttons": 0
            }})
            return True
        except Exception as e2:
            log.warning("Raw CDP click also failed: %s", e2)
            return False


async def try_click_turnstile(tab) -> bool:
    """
    Try to find and click the Cloudflare Turnstile checkbox.
    Returns True if we found and clicked something.
    """
    try:
        # Method 1: Find Turnstile iframe and click its checkbox
        log.info("Looking for Turnstile iframe...")

        result = await safe_evaluate(tab, """
            (() => {
                const iframes = document.querySelectorAll('iframe[src*="challenges.cloudflare.com"], iframe[src*="turnstile"]');
                if (iframes.length > 0) {
                    const iframe = iframes[0];
                    const rect = iframe.getBoundingClientRect();
                    return {found: true, x: rect.x + rect.width/2, y: rect.y + rect.height/2,
                            width: rect.width, height: rect.height, src: iframe.src.substring(0, 100)};
                }
                const cfDiv = document.querySelector('.cf-turnstile, #cf-turnstile, [class*="turnstile"]');
                if (cfDiv) {
                    const rect = cfDiv.getBoundingClientRect();
                    return {found: true, x: rect.x + rect.width/2, y: rect.y + rect.height/2,
                            width: rect.width, height: rect.height, src: 'cf-turnstile-div'};
                }
                return {found: false};
            })()
        """)

        if result.get("found"):
            x = float(result.get("x", 300))
            y = float(result.get("y", 300))
            log.info("Found Turnstile element: %s (pos: %.0f,%.0f size: %dx%d)",
                     result.get("src", "?"), x, y,
                     result.get("width", 0), result.get("height", 0))

            ok = await cdp_click(tab, x, y)
            if ok:
                log.info("Clicked Turnstile at (%.0f, %.0f)", x, y)
                return True
        else:
            log.info("No Turnstile iframe/div found on page")

        # Method 2: Try clicking on common CF challenge button positions
        result2 = await safe_evaluate(tab, """
            (() => {
                const btns = document.querySelectorAll('input[type="button"], button, .big-button, #challenge-stage');
                for (const btn of btns) {
                    const text = (btn.textContent || btn.value || '').toLowerCase();
                    if (text.includes('verify') || text.includes('human') || text.includes('verif')) {
                        const rect = btn.getBoundingClientRect();
                        return {found: true, x: rect.x + rect.width/2, y: rect.y + rect.height/2, text: text.substring(0,50)};
                    }
                }
                return {found: false};
            })()
        """)

        if result2.get("found"):
            x = float(result2.get("x", 300))
            y = float(result2.get("y", 300))
            log.info("Found verify button: '%s' at (%.0f,%.0f)", result2.get("text"), x, y)
            ok = await cdp_click(tab, x, y)
            if ok:
                log.info("Clicked verify button at (%.0f, %.0f)", x, y)
                return True

        # Method 3: Try nodriver's built-in element finding
        try:
            elem = await tab.query_selector('iframe[src*="challenges.cloudflare.com"]')
            if elem:
                log.info("Found Turnstile iframe via query_selector -- clicking")
                await elem.click()
                return True
        except Exception as e3:
            log.info("query_selector approach: %s", e3)

    except Exception as e:
        log.warning("Error trying to click Turnstile: %s", e)

    return False


async def fetch_page_with_nodriver(url: str, headless: bool = False) -> str | None:
    """
    Open *url* in genuine Chrome via nodriver, handle CF Turnstile, return HTML.
    headless=False is CRITICAL -- CF detects headless mode.
    """
    browser = await launch_browser_with_retry(headless=headless)

    try:
        log.info("Navigating to %s", url)
        tab = await browser.get(url)
        await asyncio.sleep(PAGE_LOAD_WAIT)

        # Get page content and check for CF
        page_content = await tab.get_content()
        content_len = len(page_content) if page_content else 0
        log.info("Initial page content: %d chars", content_len)

        # Log first 500 chars for diagnostic
        if page_content:
            snippet = page_content[:500].replace('\n', ' ').replace('\r', '')
            log.info("Page snippet: %s", snippet)

        # Check for CF challenge
        if is_cf_challenge(page_content):
            log.info("CF challenge detected -- attempting Turnstile bypass")

            # Step 1: Wait a bit for Turnstile to render
            await asyncio.sleep(3)

            # Step 2: Try to click the Turnstile checkbox
            clicked = await try_click_turnstile(tab)

            if clicked:
                log.info("Waiting %ds after Turnstile click...", CF_WAIT_AFTER_CLICK)
                await asyncio.sleep(CF_WAIT_AFTER_CLICK)
            else:
                log.info("No clickable Turnstile found -- waiting %ds for auto-solve...", CF_WAIT_INITIAL)
                await asyncio.sleep(CF_WAIT_INITIAL)

            # Step 3: Check if we passed
            page_content = await tab.get_content()
            content_len = len(page_content) if page_content else 0
            log.info("After Turnstile attempt: %d chars", content_len)

            if is_cf_challenge(page_content):
                # Step 4: Try re-navigating (cookies should be set)
                log.info("Still on CF -- re-navigating with cookies")
                tab = await browser.get(url)
                await asyncio.sleep(PAGE_LOAD_WAIT)
                page_content = await tab.get_content()
                content_len = len(page_content) if page_content else 0
                log.info("After re-navigation: %d chars", content_len)

                if is_cf_challenge(page_content):
                    # Step 5: One more Turnstile click attempt
                    await asyncio.sleep(3)
                    clicked2 = await try_click_turnstile(tab)
                    if clicked2:
                        await asyncio.sleep(CF_WAIT_AFTER_CLICK)
                        page_content = await tab.get_content()
                        content_len = len(page_content) if page_content else 0
                        log.info("After 2nd Turnstile click: %d chars", content_len)
        else:
            log.info("No CF challenge detected -- page loaded directly")

        # Final content check
        if page_content and is_real_content(page_content):
            log.info("SUCCESS: Got real page content (%d chars)", len(page_content))
        elif page_content:
            # Log diagnostic info about what we got
            title_match = re.search(r'<title>(.*?)</title>', page_content, re.IGNORECASE | re.DOTALL)
            title = title_match.group(1).strip() if title_match else "NO TITLE"
            log.warning("Page title: %s", title)
            log.warning("Page is_cf=%s, is_real=%s, len=%d",
                       is_cf_challenge(page_content), is_real_content(page_content), len(page_content))
            # Log a bigger snippet
            snippet = page_content[:1000].replace('\n', ' ').replace('\r', '')
            log.info("Full snippet: %s", snippet)

        return page_content

    finally:
        try:
            browser.stop()
            log.info("terminated browser with pid %s successfully",
                     getattr(getattr(browser, '_process', None), 'pid', '?'))
        except Exception:
            pass
        # Kill any remaining chrome processes from our profile
        import subprocess, glob, shutil as _shutil
        for tmp in glob.glob("/tmp/uc_*"):
            try:
                _shutil.rmtree(tmp, ignore_errors=True)
                log.info("successfully removed temp profile %s", tmp)
            except Exception:
                pass


# ---------------------------------------------------------------------------
# Extract m3u8 from a channel detail page (in the same browser session)
# ---------------------------------------------------------------------------
BASE_URL = "https://www.stream4free.tv"
CHANNEL_PAGE_WAIT = 5  # seconds to wait for channel page JS to load


async def extract_m3u8_from_channel_page(tab, channel_name: str, channel_href: str) -> str | None:
    """
    Navigate to a channel detail page and extract the m3u8/stream URL.
    The tab stays open (same browser session -> CF cookies carry over).
    Returns the m3u8 URL or None.
    """
    url = BASE_URL + channel_href
    try:
        log.info("  -> Visiting channel page: %s", url)
        await tab.get(url)
        await asyncio.sleep(CHANNEL_PAGE_WAIT)

        page_html = await tab.get_content()
        if not page_html:
            log.warning("  [X] %s: empty page", channel_name)
            return None

        # Method 1: Try to get m3u8 from Video.js player setup via JS
        js_result = await safe_evaluate(tab, """
            (() => {
                // Look for Video.js player with "file" property in setup scripts
                const scripts = document.querySelectorAll('script');
                for (const s of scripts) {
                    const txt = s.textContent || '';
                    // Pattern: "file":"https://...m3u8..."
                    const m = txt.match(/"file"\\s*:\\s*"(https?:\\/\\/[^"]+\\.m3u8[^"]*)"/);
                    if (m) return {found: true, url: m[1], method: 'file_property'};
                    // Pattern: source: "https://...m3u8..."
                    const m2 = txt.match(/source\\s*:\\s*"(https?:\\/\\/[^"]+\\.m3u8[^"]*)"/);
                    if (m2) return {found: true, url: m2[1], method: 'source_property'};
                }
                // Look for <source> or <video> tags
                const sources = document.querySelectorAll('source[src*=".m3u8"], video[src*=".m3u8"]');
                if (sources.length > 0) {
                    return {found: true, url: sources[0].src, method: 'source_tag'};
                }
                // Look for iframes with stream content
                const iframes = document.querySelectorAll('iframe[src]');
                for (const ifr of iframes) {
                    if (ifr.src.includes('.m3u8') || ifr.src.includes('stream') || ifr.src.includes('player')) {
                        return {found: true, url: ifr.src, method: 'iframe', isIframe: true};
                    }
                }
                return {found: false};
            })()
        """)

        if js_result.get("found") and not js_result.get("isIframe"):
            m3u8_url = js_result["url"]
            log.info("  [OK] %s: %s (via %s)", channel_name, m3u8_url[:80], js_result.get("method"))
            return m3u8_url

        # Method 2: Regex on raw HTML
        stream_urls = extract_stream_urls(page_html)
        if stream_urls:
            # Prefer m3u8 over mpd, prefer data-stream.top domain
            best = None
            for u in stream_urls:
                if ".m3u8" in u:
                    if "data-stream" in u or "stream4free" in u:
                        best = u
                        break
                    if best is None:
                        best = u
            if best is None:
                best = stream_urls[0]
            log.info("  [OK] %s: %s (via regex)", channel_name, best[:80])
            return best

        # Method 3: If there's an iframe player, try to get its src and navigate into it
        if js_result.get("isIframe"):
            iframe_src = js_result["url"]
            log.info("  -> %s: found player iframe, navigating: %s", channel_name, iframe_src[:80])
            try:
                await tab.get(iframe_src)
                await asyncio.sleep(3)
                iframe_html = await tab.get_content()
                if iframe_html:
                    iframe_streams = extract_stream_urls(iframe_html)
                    if iframe_streams:
                        log.info("  [OK] %s: %s (via iframe)", channel_name, iframe_streams[0][:80])
                        return iframe_streams[0]
                    # Also try JS inside iframe
                    iframe_js = await safe_evaluate(tab, """
                            (() => {
                                const scripts = document.querySelectorAll('script');
                                for (const s of scripts) {
                                    const txt = s.textContent || '';
                                    const m = txt.match(/"file"\\s*:\\s*"(https?:\\/\\/[^"]+\\.m3u8[^"]*)"/);
                                    if (m) return {found: true, url: m[1], method: 'iframe_file'};
                                    const m2 = txt.match(/source\\s*:\\s*"(https?:\\/\\/[^"]+\\.m3u8[^"]*)"/);
                                    if (m2) return {found: true, url: m2[1], method: 'iframe_source'};
                                }
                                return {found: false};
                            })()
                    """)
                    if iframe_js.get("found"):
                        log.info("  [OK] %s: %s (via %s)", channel_name, iframe_js["url"][:80], iframe_js.get("method"))
                        return iframe_js["url"]
            except Exception as ie:
                log.warning("  iframe nav failed for %s: %s", channel_name, ie)

        log.warning("  [X] %s: no stream URL found", channel_name)
        return None

    except Exception as e:
        log.warning("  [X] %s: error: %s", channel_name, e)
        return None


# ---------------------------------------------------------------------------
# Main scraper -- keeps browser alive across all channel pages
# ---------------------------------------------------------------------------
_final_count = 0  # survives RuntimeError from nodriver cleanup


async def scrape_stream4free():
    """
    Main scraper:
    1. Launch browser + bypass CF on listing page
    2. Parse channel list from listing HTML
    3. Visit each channel page (same browser session) to extract m3u8
    4. Write M3U playlist
    """
    global _final_count

    display = setup_virtual_display()

    import nodriver as uc
    import shutil

    chrome_path = (
        shutil.which("google-chrome-stable")
        or shutil.which("google-chrome")
        or shutil.which("chromium-browser")
        or shutil.which("chromium")
    )

    browser_args = [
        "--no-first-run",
        "--no-default-browser-check",
        "--no-sandbox",
        "--disable-dev-shm-usage",
        "--disable-features=Translate",
        "--disable-blink-features=AutomationControlled",
        "--disable-extensions",
        "--disable-popup-blocking",
        "--disable-infobars",
        "--lang=fr-FR",
        "--window-size=1920,1080",
    ]

    browser = None
    try:
        for attempt in range(1, MAX_RETRIES + 1):
            try:
                log.info("Browser launch attempt %d/%d (headless=False, binary=%s)",
                         attempt, MAX_RETRIES, chrome_path)
                browser = await uc.start(
                    headless=False,
                    sandbox=False,
                    browser_executable_path=chrome_path,
                    browser_args=browser_args,
                    lang="fr-FR",
                )
                log.info("Browser connected (attempt %d)", attempt)
                break
            except Exception as e:
                log.warning("Browser launch attempt %d failed: %s", attempt, e)
                if attempt < MAX_RETRIES:
                    await asyncio.sleep(2)
                else:
                    raise

        # Step 1: Navigate to listing page
        log.info("Navigating to listing page: %s", PAGE_URL)
        tab = await browser.get(PAGE_URL)
        await asyncio.sleep(PAGE_LOAD_WAIT)

        page_content = await tab.get_content()
        content_len = len(page_content) if page_content else 0
        log.info("Initial listing page: %d chars", content_len)

        # Handle CF challenge
        if is_cf_challenge(page_content):
            log.info("CF challenge on listing page -- attempting bypass")
            await asyncio.sleep(3)
            clicked = await try_click_turnstile(tab)
            if clicked:
                await asyncio.sleep(CF_WAIT_AFTER_CLICK)
            else:
                await asyncio.sleep(CF_WAIT_INITIAL)

            page_content = await tab.get_content()
            content_len = len(page_content) if page_content else 0
            log.info("After CF bypass: %d chars", content_len)

            if is_cf_challenge(page_content):
                log.info("Still CF -- re-navigating")
                tab = await browser.get(PAGE_URL)
                await asyncio.sleep(PAGE_LOAD_WAIT)
                page_content = await tab.get_content()
                content_len = len(page_content) if page_content else 0
                log.info("After re-nav: %d chars", content_len)

        if not page_content or not is_real_content(page_content):
            log.error("FAILED to get real listing page content (len=%d)", content_len)
            _final_count = 0
            return

        # Step 2: Parse channels
        channels = extract_channels_from_html(page_content)
        log.info("Found %d channels on listing page", len(channels))

        if not channels:
            log.error("No channels found -- parser may need update")
            _final_count = 0
            return

        for i, (name, href) in enumerate(channels):
            log.info("  Channel %d: %s -> %s", i + 1, name, href)

        # Step 3: Visit each channel page and extract m3u8
        results = []  # list of (name, m3u8_url)
        for i, (name, href) in enumerate(channels):
            log.info("--- Channel %d/%d: %s ---", i + 1, len(channels), name)
            m3u8_url = await extract_m3u8_from_channel_page(tab, name, href)
            if m3u8_url:
                results.append((name, m3u8_url))
            # Small delay between channels to be polite
            await asyncio.sleep(1)

        log.info("=== RESULTS: %d/%d channels with streams ===", len(results), len(channels))
        for name, url in results:
            log.info("  %s: %s", name, url[:100])

        # Step 4: Write M3U file
        M3U_FILE.parent.mkdir(parents=True, exist_ok=True)
        with open(M3U_FILE, "w", encoding="utf-8") as f:
            f.write("#EXTM3U\n")
            for name, url in results:
                # Clean channel name for display
                display_name = name.strip()
                f.write(f'#EXTINF:-1 tvg-name="{display_name}",{display_name}\n')
                f.write(f"{url}\n")

        _final_count = len(results)
        log.info("Wrote %d channels to %s", _final_count, M3U_FILE)

    finally:
        if browser:
            try:
                browser.stop()
                log.info("Browser stopped")
            except Exception:
                pass
        if display:
            try:
                display.stop()
                log.info("Virtual display stopped")
            except Exception:
                pass


def main():
    global _final_count
    log.info("=" * 60)
    log.info("Stream4Free scraper v2 (test) -- nodriver + Xvfb")
    log.info("=" * 60)

    try:
        asyncio.run(scrape_stream4free())
    except RuntimeError as e:
        # nodriver sometimes raises RuntimeError during cleanup
        log.warning("RuntimeError during cleanup (ignored): %s", e)
    except Exception as e:
        log.error("Fatal error: %s", e, exc_info=True)
        sys.exit(1)

    log.info("Final count: %d channels", _final_count)
    if _final_count == 0:
        log.error("No channels found -- exiting with error")
        sys.exit(1)
    else:
        log.info("SUCCESS: %d channels written to %s", _final_count, M3U_FILE)
        sys.exit(0)


if __name__ == "__main__":
    main()
