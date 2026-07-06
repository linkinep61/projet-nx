#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
refresh_lumichat : teste EN AMONT toutes les chaines LumiChat (gateway.lumichat.fun),
ne garde que celles qui RESOLVENT reellement (success + url), et regenere data-lumichat.m3u.
Meme fingerprint que l'app (Chrome/131 + Origin/Referer frenchtv.vdfr.uk) -> resultat representatif.
Env: LUMI_LIMIT (0=toutes), LUMI_WORKERS (20), LUMI_TIMEOUT (30), LUMI_OUT (data-lumichat.m3u)
"""
import os, time, urllib.parse
from concurrent.futures import ThreadPoolExecutor, as_completed
import requests

BASE = "https://gateway.lumichat.fun"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
HEADERS = {"Accept": "*/*", "User-Agent": UA, "Origin": "https://frenchtv.vdfr.uk", "Referer": "https://frenchtv.vdfr.uk/"}
LIMIT   = int(os.environ.get("LUMI_LIMIT", "0"))
WORKERS = int(os.environ.get("LUMI_WORKERS", "20"))
TIMEOUT = int(os.environ.get("LUMI_TIMEOUT", "30"))
OUT     = os.environ.get("LUMI_OUT", "data-lumichat.m3u")
COUNTRY = os.environ.get("LUMI_COUNTRY", "FR").upper()          # ne garder que ce pays (ALL = tous)
SKIP_DEAD = os.environ.get("LUMI_SKIP_DEAD", "1") == "1"        # sauter les sources 0% vivantes
DEAD_SRC_PREFIXES = ("vavoo", "livewatch")

import re
def parse_existing_m3u(path):
    """Fallback : lit la liste de chaines depuis le M3U deja dans le depot (si l'API timeout)."""
    if not os.path.exists(path):
        return []
    out, pending = [], None
    for line in open(path, encoding="utf-8"):
        t = line.strip()
        if t.startswith("#EXTINF"):
            pending = t
        elif pending and t.startswith("lumichat://"):
            cid = t[len("lumichat://"):]
            def g(a):
                m = re.search(a + r'="([^"]*)"', pending); return m.group(1) if m else ""
            name = pending.rsplit(",", 1)[-1].strip()
            out.append({"id": cid, "name": name or cid, "group": g("group-title") or "LumiChat",
                        "logo": g("tvg-logo"), "cc": g("tvg-country")})
            pending = None
        elif not t.startswith("#"):
            pending = None
    return out

def fetch_channels():
    last = None
    for attempt in range(5):
        try:
            r = requests.get(f"{BASE}/api/categories",
                             headers={"Accept": "application/json", "User-Agent": UA,
                                      "Origin": "https://frenchtv.vdfr.uk", "Referer": "https://frenchtv.vdfr.uk/"},
                             timeout=60)
            r.raise_for_status()
            break
        except Exception as e:
            last = e
            print(f"[lumichat] fetch_channels essai {attempt+1}/5 KO ({e}) - retry dans {5*(attempt+1)}s", flush=True)
            time.sleep(5 * (attempt + 1))
    else:
        print(f"[lumichat] API KO ({last}) -> FALLBACK sur le M3U existant {OUT}", flush=True)
        fb = parse_existing_m3u(OUT)
        print(f"[lumichat] fallback: {len(fb)} chaines lues depuis {OUT}", flush=True)
        return fb
    cats = (r.json().get("categories") or {})
    out = []
    for cat in cats.values():
        for ch in cat.get("channels", []):
            cid = ch.get("channel_id")
            if not cid: continue
            out.append({"id": cid, "name": ch.get("channel_name") or cid,
                        "group": ch.get("category_name") or cat.get("name") or "LumiChat",
                        "logo": ch.get("logo_url") or "", "cc": ch.get("country_code") or ""})
    return out

def resolve_ok(cid):
    enc = urllib.parse.quote(cid, safe="")
    u = f"{BASE}/api/python-stream/{enc}?force=1&_={int(time.time()*1000)}"
    for _ in range(2):
        try:
            r = requests.get(u, headers=HEADERS, timeout=TIMEOUT)
            if r.status_code != 200: return False
            j = r.json()
            return bool(j.get("success") and j.get("url"))
        except Exception:
            time.sleep(1)
    return False

def main():
    t0 = time.time()
    chans = fetch_channels()
    # PRE-FILTRE : on ne teste que ce qui est reellement utile -> probe bien plus rapide.
    if COUNTRY and COUNTRY != "ALL":
        chans = [c for c in chans if (c.get("cc") or "").upper() == COUNTRY]
    if SKIP_DEAD:
        chans = [c for c in chans if not c["id"].lower().startswith(DEAD_SRC_PREFIXES)]
    print(f"[lumichat] pre-filtre: pays={COUNTRY}, skip_morts={SKIP_DEAD} -> {len(chans)} chaines a tester", flush=True)
    if LIMIT > 0: chans = chans[:LIMIT]
    total = len(chans)
    print(f"[lumichat] {total} chaines a tester (workers={WORKERS}, timeout={TIMEOUT}s)", flush=True)
    working, done = [], 0
    with ThreadPoolExecutor(max_workers=WORKERS) as ex:
        futs = {ex.submit(resolve_ok, c["id"]): c for c in chans}
        for fut in as_completed(futs):
            c = futs[fut]
            try: ok = fut.result()
            except Exception: ok = False
            if ok: working.append(c)
            done += 1
            if done % 200 == 0 or done == total:
                print(f"[lumichat] {done}/{total} testees, {len(working)} OK ({int(time.time()-t0)}s)", flush=True)
    order = {c["id"]: i for i, c in enumerate(chans)}
    working.sort(key=lambda c: order[c["id"]])
    lines = ["#EXTM3U"]
    for c in working:
        lines.append(f'#EXTINF:-1 tvg-id="{c["id"]}" tvg-logo="{c["logo"]}" group-title="{c["group"]}" tvg-country="{c["cc"]}",{c["name"]}')
        lines.append(f'lumichat://{c["id"]}')
    open(OUT, "w", encoding="utf-8").write("\n".join(lines) + "\n")
    print(f"[lumichat] TERMINE : {len(working)}/{total} vivantes gardees ({total-len(working)} mortes retirees) en {int(time.time()-t0)}s -> {OUT}", flush=True)

if __name__ == "__main__":
    main()
