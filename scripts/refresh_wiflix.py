#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
refresh_wiflix.py — Pré-scrape Wiflix (flemmix.fast) depuis GitHub Actions via
Cloudflare WARP (IP propre) + Playwright (Chromium headless qui exécute le
challenge Cloudflare). Produit provider_web/wiflix.json que l'app Onyx lit
directement (GitHub raw = pas de CF) → plus besoin de VPN sur le device.

Sortie wiflix.json :
{
  "generatedAt": <unix>,
  "domain": "https://flemmax.rapide",     # domaine live après redirection
  "recentFilms":  [ item, ... ],          # /film-en-streaming/ (date d'ajout desc)
  "recentSeries": [ item, ... ],          # /serie-en-streaming/
  "year":        { "2026": [ item, ... ] },# /xfsearch/<year>/
  "sources":     { "<id>": [ {name,url,lang}, ... ] }  # URLs hôtes par film
}
item = { "type":"movie"|"tv", "id":"film-en-streaming/35890-....html",
         "title", "poster", "year" }
"""

import os, sys, json, time, re, subprocess, shutil

BASE = "https://flemmix.fast"
YEAR = time.gmtime().tm_year
MAX_FILMS_SOURCES = int(os.environ.get("WIFLIX_MAX_SOURCES", "40"))  # bornage

WARP_PROXY = ""  # "socks5://127.0.0.1:40000" une fois WARP prêt


# ───────── Cloudflare WARP (proxy SOCKS5) — repris de refresh_replays.py ─────────
def setup_warp():
    global WARP_PROXY
    if WARP_PROXY:
        return True
    if shutil.which("warp-cli"):
        pass
    elif os.path.exists("/etc/os-release"):
        print("[VPN] Installation de Cloudflare WARP...", file=sys.stderr)
        cmds = [
            "curl -fsSL https://pkg.cloudflareclient.com/pubkey.gpg | sudo gpg --yes --dearmor -o /usr/share/keyrings/cloudflare-warp-archive-keyring.gpg",
            'echo "deb [arch=amd64 signed-by=/usr/share/keyrings/cloudflare-warp-archive-keyring.gpg] https://pkg.cloudflareclient.com/ $(lsb_release -cs) main" | sudo tee /etc/apt/sources.list.d/cloudflare-client.list',
            "sudo apt-get update -qq && sudo apt-get install -y -qq cloudflare-warp",
        ]
        for c in cmds:
            r = subprocess.run(c, shell=True, capture_output=True)
            if r.returncode != 0:
                print(f"[VPN] Install échouée: {r.stderr.decode()[:200]}", file=sys.stderr)
                return False
    else:
        print("[VPN] OS non supporté", file=sys.stderr)
        return False
    for cmd in [
        ["warp-cli", "--accept-tos", "registration", "new"],
        ["warp-cli", "--accept-tos", "mode", "proxy"],
        ["warp-cli", "--accept-tos", "set-mode", "proxy"],  # syntaxe récente
        ["warp-cli", "--accept-tos", "proxy", "port", "40000"],
        ["warp-cli", "--accept-tos", "connect"],
    ]:
        r = subprocess.run(cmd, capture_output=True)
        print(f"[VPN] {' '.join(cmd[1:])} → rc={r.returncode} {r.stderr.decode()[:120]}", file=sys.stderr)
    time.sleep(8)
    test = subprocess.run(
        ["curl", "-s", "-x", "socks5h://127.0.0.1:40000", "--max-time", "10",
         "https://ipinfo.io/ip"], capture_output=True)
    if test.returncode == 0 and test.stdout.strip():
        WARP_PROXY = "socks5://127.0.0.1:40000"
        print(f"[VPN] WARP OK — IP: {test.stdout.decode().strip()}", file=sys.stderr)
        return True
    print("[VPN] WARP KO — accès direct", file=sys.stderr)
    return False


# ───────── Parsing des cartes (DLE Wiflix : div.mov > a.mov-t + img) ─────────
def parse_mov_cards(html):
    out, seen = [], set()
    # bloc div.mov ... jusqu'au prochain div.mov (approx via regex tolérante)
    for m in re.finditer(r'<a[^>]+class="mov-t"[^>]+href="([^"]+)"[^>]*>(.*?)</a>', html, re.S):
        href, title = m.group(1), re.sub(r"<[^>]+>", "", m.group(2)).strip()
        path = re.sub(r"^https?://[^/]+/", "", href).strip("/")
        if not path or path in seen:
            continue
        seen.add(path)
        typ = "tv" if "serie" in path else "movie"
        ym = re.search(r"-(\d{4})\.html", path) or re.search(r"\((\d{4})\)", title)
        # poster : cherché dans un rayon proche (best-effort)
        out.append({"type": typ, "id": path, "title": title,
                    "poster": "", "year": (ym.group(1) if ym else "")})
    return out


def parse_mov_cards_dom(page):
    """Version DOM (plus fiable que regex) : lit les div.mov rendus."""
    js = r"""
    () => {
      const out = [];
      document.querySelectorAll('div.mov').forEach(mov => {
        const a = mov.querySelector('a.mov-t') || mov.querySelector('a[href]');
        if (!a) return;
        const href = a.getAttribute('href') || '';
        const path = href.replace(/^https?:\/\/[^/]+\//, '').replace(/\/+$/, '');
        if (!path) return;
        const img = mov.querySelector('img');
        const poster = img ? (img.getAttribute('data-src') || img.getAttribute('src') || '') : '';
        const title = (a.textContent || '').trim();
        const type = path.indexOf('serie') >= 0 ? 'tv' : 'movie';
        const ym = path.match(/-(\d{4})\.html/) || title.match(/\((\d{4})\)/);
        out.push({ type, id: path, title, poster, year: ym ? ym[1] : '' });
      });
      return out;
    }
    """
    try:
        return page.evaluate(js)
    except Exception as e:
        print(f"  parse dom err: {e}", file=sys.stderr)
        return []


def extract_sources_dom(page):
    """Sur une page film/série : lit les URLs des boutons loadVideo(...)."""
    js = r"""
    () => {
      const urls = [];
      const seen = new Set();
      // boutons serveurs : onclick loadVideo('url', ...) ou data-href
      document.querySelectorAll('[onclick]').forEach(el => {
        const oc = el.getAttribute('onclick') || '';
        const m = oc.match(/loadVideo\(\s*['"]([^'"]+)['"]/);
        if (m) {
          const u = m[1];
          if (u && u.indexOf('http') === 0 && !seen.has(u)) {
            seen.add(u);
            urls.push({ name: (el.textContent || '').trim().slice(0, 30), url: u,
                        lang: /vostfr/i.test(el.className + ' ' + (el.closest('[rel]')?.getAttribute('rel')||'')) ? 'VOSTFR' : 'VF' });
          }
        }
      });
      // fallback : liens dans div.tabs-sel
      document.querySelectorAll('div.tabs-sel a[href^="http"]').forEach(a => {
        const u = a.getAttribute('href');
        if (u && !seen.has(u)) { seen.add(u); urls.push({ name: (a.textContent||'').trim().slice(0,30), url: u, lang: 'VF' }); }
      });
      return urls;
    }
    """
    try:
        return page.evaluate(js)
    except Exception:
        return []


STEALTH_JS = """
  Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
  window.chrome = window.chrome || { runtime: {}, app: {}, csi: function(){}, loadTimes: function(){} };
  Object.defineProperty(navigator, 'plugins', { get: () => [1,2,3,4,5] });
  Object.defineProperty(navigator, 'languages', { get: () => ['fr-FR','fr','en-US','en'] });
  try { Object.defineProperty(navigator, 'platform', { get: () => 'Linux armv8l' }); } catch(e){}
"""

def goto(page, path, wait_ms=2500, diag=False):
    url = path if path.startswith("http") else BASE + "/" + path.lstrip("/")
    page.goto(url, wait_until="domcontentloaded", timeout=60000)
    # attendre que le challenge CF se résolve (le vrai contenu apparaît). Jusqu'à 45s.
    got = False
    for _ in range(45):
        try:
            has = page.evaluate("() => !!document.querySelector('div.mov, div.tabs-sel, header.full-title, [onclick*=loadVideo], ul.eplist')")
            if has:
                got = True
                break
        except Exception:
            pass
        page.wait_for_timeout(1000)
    if diag or not got:
        try:
            title = page.title()
            btxt = page.evaluate("() => (document.body ? document.body.innerText : '').slice(0,180).replace(/\\s+/g,' ')")
            print(f"  [diag {path}] got={got} origin={page.evaluate('()=>location.origin')} title={title!r} body={btxt!r}", file=sys.stderr)
        except Exception as e:
            print(f"  [diag {path}] err {e}", file=sys.stderr)
    page.wait_for_timeout(wait_ms)


def run():
    setup_warp()
    from playwright.sync_api import sync_playwright

    data = {"generatedAt": int(time.time()), "domain": BASE,
            "recentFilms": [], "recentSeries": [], "year": {}, "sources": {}}

    launch_args = {"headless": True}
    if WARP_PROXY:
        launch_args["proxy"] = {"server": WARP_PROXY}

    with sync_playwright() as pw:
        browser = pw.chromium.launch(**launch_args)
        ctx = browser.new_context(
            locale="fr-FR",
            user_agent="Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 "
                       "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        )
        ctx.add_init_script(STEALTH_JS)
        page = ctx.new_page()

        # 1) home → domaine live après redirection
        goto(page, "/", diag=True)
        data["domain"] = page.evaluate("() => location.origin")
        print(f"[wiflix] domaine live = {data['domain']}", file=sys.stderr)

        # 2) catalogues récents (date d'ajout desc)
        goto(page, "/film-en-streaming/")
        data["recentFilms"] = parse_mov_cards_dom(page)
        print(f"[wiflix] recentFilms = {len(data['recentFilms'])}", file=sys.stderr)

        goto(page, "/serie-en-streaming/")
        data["recentSeries"] = parse_mov_cards_dom(page)
        print(f"[wiflix] recentSeries = {len(data['recentSeries'])}", file=sys.stderr)

        # 3) année en cours
        goto(page, f"/xfsearch/{YEAR}/")
        data["year"][