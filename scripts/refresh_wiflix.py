#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""refresh_wiflix.py : pre-scrape Wiflix via WARP + Playwright -> provider_web/wiflix.json"""
import os, sys, json, time, re, subprocess, shutil, base64

BASE = "https://flemmix.fast"
YEAR = time.gmtime().tm_year
MAX_FILMS_SOURCES = int(os.environ.get("WIFLIX_MAX_SOURCES", "40"))
WARP_PROXY = ""

_ANTI = base64.b64decode(
    "CiAgT2JqZWN0LmRlZmluZVByb3BlcnR5KG5hdmlnYXRvciwgJ3dlYmRyaXZlcicsIHsgZ2V0OiAoKSA9PiB1"
    "bmRlZmluZWQgfSk7CiAgd2luZG93LmNocm9tZSA9IHdpbmRvdy5jaHJvbWUgfHwgeyBydW50aW1lOiB7fSwg"
    "YXBwOiB7fSwgY3NpOiBmdW5jdGlvbigpe30sIGxvYWRUaW1lczogZnVuY3Rpb24oKXt9IH07CiAgT2JqZWN0"
    "LmRlZmluZVByb3BlcnR5KG5hdmlnYXRvciwgJ3BsdWdpbnMnLCB7IGdldDogKCkgPT4gWzEsMiwzLDQsNV0g"
    "fSk7CiAgT2JqZWN0LmRlZmluZVByb3BlcnR5KG5hdmlnYXRvciwgJ2xhbmd1YWdlcycsIHsgZ2V0OiAoKSA9"
    "PiBbJ2ZyLUZSJywnZnInLCdlbi1VUycsJ2VuJ10gfSk7CiAgdHJ5IHsgT2JqZWN0LmRlZmluZVByb3BlcnR5"
    "KG5hdmlnYXRvciwgJ3BsYXRmb3JtJywgeyBnZXQ6ICgpID0+ICdMaW51eCBhcm12OGwnIH0pOyB9IGNhdGNo"
    "KGUpe30K"
).decode()

def setup_warp():
    global WARP_PROXY
    if WARP_PROXY: return True
    if shutil.which("warp-cli"): pass
    elif os.path.exists("/etc/os-release"):
        print("[VPN] Install WARP...", file=sys.stderr)
        cmds = [
            "curl -fsSL https://pkg.cloudflareclient.com/pubkey.gpg | sudo gpg --yes --dearmor -o /usr/share/keyrings/cloudflare-warp-archive-keyring.gpg",
            'echo "deb [arch=amd64 signed-by=/usr/share/keyrings/cloudflare-warp-archive-keyring.gpg] https://pkg.cloudflareclient.com/ $(lsb_release -cs) main" | sudo tee /etc/apt/sources.list.d/cloudflare-client.list',
            "sudo apt-get update -qq && sudo apt-get install -y -qq cloudflare-warp",
        ]
        for c in cmds:
            r = subprocess.run(c, shell=True, capture_output=True)
            if r.returncode != 0:
                print(f"[VPN] install fail: {r.stderr.decode()[:200]}", file=sys.stderr); return False
    else: return False
    for cmd in [["warp-cli","--accept-tos","registration","new"],
                ["warp-cli","--accept-tos","mode","proxy"],
                ["warp-cli","--accept-tos","proxy","port","40000"],
                ["warp-cli","--accept-tos","connect"]]:
        r = subprocess.run(cmd, capture_output=True)
        print(f"[VPN] {' '.join(cmd[1:])} rc={r.returncode} {r.stderr.decode()[:120]}", file=sys.stderr)
    time.sleep(8)
    t = subprocess.run(["curl","-s","-x","socks5h://127.0.0.1:40000","--max-time","10","https://ipinfo.io/ip"], capture_output=True)
    if t.returncode == 0 and t.stdout.strip():
        WARP_PROXY = "socks5://127.0.0.1:40000"
        print(f"[VPN] WARP OK IP: {t.stdout.decode().strip()}", file=sys.stderr); return True
    print("[VPN] WARP KO", file=sys.stderr); return False

def parse_cards(page):
    js = r"""() => { const out=[]; document.querySelectorAll('div.mov').forEach(mov=>{ const a=mov.querySelector('a.mov-t')||mov.querySelector('a[href]'); if(!a) return; const href=a.getAttribute('href')||''; const path=href.replace(/^https?:\/\/[^/]+\//,'').replace(/\/+$/,''); if(!path) return; const img=mov.querySelector('img'); const poster=img?(img.getAttribute('data-src')||img.getAttribute('src')||''):''; const title=(a.textContent||'').trim(); const type=path.indexOf('serie')>=0?'tv':'movie'; const ym=path.match(/-(\d{4})\.html/)||title.match(/\((\d{4})\)/); out.push({type,id:path,title,poster,year:ym?ym[1]:''}); }); return out; }"""
    try: return page.evaluate(js)
    except Exception as e: print(f"  parse err {e}", file=sys.stderr); return []

def extract_sources(page):
    js = r"""() => { const urls=[]; const seen=new Set(); document.querySelectorAll('[onclick]').forEach(el=>{ const oc=el.getAttribute('onclick')||''; const m=oc.match(/loadVideo\(\s*['"]([^'"]+)['"]/); if(m){ const u=m[1]; if(u&&u.indexOf('http')===0&&!seen.has(u)){ seen.add(u); urls.push({name:(el.textContent||'').trim().slice(0,30),url:u,lang:/vostfr/i.test(el.className)?'VOSTFR':'VF'}); } } }); document.querySelectorAll('div.tabs-sel a[href^="http"]').forEach(a=>{ const u=a.getAttribute('href'); if(u&&!seen.has(u)){ seen.add(u); urls.push({name:(a.textContent||'').trim().slice(0,30),url:u,lang:'VF'}); } }); return urls; }"""
    try: return page.evaluate(js)
    except Exception: return []

def goto(page, path, wait_ms=2500, diag=False):
    url = path if path.startswith("http") else BASE + "/" + path.lstrip("/")
    page.goto(url, wait_until="domcontentloaded", timeout=60000)
    got = False
    for _ in range(45):
        try:
            if page.evaluate("() => !!document.querySelector('div.mov, div.tabs-sel, header.full-title, [onclick*=loadVideo], ul.eplist')"):
                got = True; break
        except Exception: pass
        page.wait_for_timeout(1000)
    if diag or not got:
        try:
            title = page.title()
            btxt = page.evaluate("() => (document.body?document.body.innerText:'').slice(0,180).replace(/\\s+/g,' ')")
            origin = page.evaluate("() => location.origin")
            print(f"  [diag {path}] got={got} origin={origin} title={title!r} body={btxt!r}", file=sys.stderr)
        except Exception as e: print(f"  [diag {path}] err {e}", file=sys.stderr)
    page.wait_for_timeout(wait_ms)

def run():
    setup_warp()
    from playwright.sync_api import sync_playwright
    data = {"generatedAt": int(time.time()), "domain": BASE, "recentFilms": [], "recentSeries": [], "year": {}, "sources": {}}
    la = {"headless": True}
    if WARP_PROXY: la["proxy"] = {"server": WARP_PROXY}
    with sync_playwright() as pw:
        b = pw.chromium.launch(**la)
        ctx = b.new_context(locale="fr-FR", user_agent="Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
        ctx.add_init_script(_ANTI)
        page = ctx.new_page()
        goto(page, "/", diag=True)
        data["domain"] = page.evaluate("() => location.origin")
        print(f"[wiflix] domaine={data['domain']}", file=sys.stderr)
        goto(page, "/film-en-streaming/"); data["recentFilms"] = parse_cards(page)
        print(f"[wiflix] recentFilms={len(data['recentFilms'])}", file=sys.stderr)
        goto(page, "/serie-en-streaming/"); data["recentSeries"] = parse_cards(page)
        print(f"[wiflix] recentSeries={len(data['recentSeries'])}", file=sys.stderr)
        goto(page, f"/xfsearch/{YEAR}/"); data["year"][str(YEAR)] = parse_cards(page)
        print(f"[wiflix] year={len(data['year'][str(YEAR)])}", file=sys.stderr)
        pool = (data["recentFilms"] + data["recentSeries"])[:MAX_FILMS_SOURCES]
        for it in pool:
            try:
                goto(page, it["id"], wait_ms=1500)
                s = extract_sources(page)
                if s: data["sources"][it["id"]] = s
            except Exception as e: print(f"  src err {it['id']}: {e}", file=sys.stderr)
        print(f"[wiflix] sources={len(data['sources'])}", file=sys.stderr)
        b.close()
    os.makedirs("provider_web", exist_ok=True)
    with open("provider_web/wiflix.json", "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
    print(f"[wiflix] wiflix.json ecrit", file=sys.stderr)

if __name__ == "__main__":
    run()
