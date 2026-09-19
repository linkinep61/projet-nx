# -*- coding: utf-8 -*-
"""Scanne LENTEMENT les ids /live/<id>_.m3u8 sur blcco.linkip.org.
Le CDN est derriere Cloudflare et repond 429 des qu'on tape vite :
=> sequentiel, pause entre chaque requete, backoff long sur 429.
Usage : python otf_scan_ids.py <lo> <hi> [delai]
Ecrit/complete otf_ids_alive.json au fil de l'eau (reprise possible).
"""
import json, os, re, sys, time
import requests

BASE = "https://blcco.linkip.org/live/%s_.m3u8"
H = {"User-Agent": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"}
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "otf_ids_alive.json")


def load(path, default):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return default


def known_ids():
    out = {}
    for c in load(os.path.join(HERE, "otf_catalogue.json"), []):
        for u in c["urls"]:
            m = re.search(r"/live/(\d+)_\.m3u8", u)
            if m and "linkip.org" in u:
                out.setdefault(m.group(1), "%s | %s" % (c["group"], c["name"]))
    return out


def probe(sess, i):
    for attempt in range(4):
        try:
            r = sess.get(BASE % i, headers=H, timeout=12)
            if r.status_code == 429:
                time.sleep(20 * (attempt + 1))
                continue
            if r.status_code == 200 and "#EXTM3U" in r.text:
                return "alive"
            return "dead:%s" % r.status_code
        except Exception:
            time.sleep(3)
    return "throttled"


def main():
    lo, hi = int(sys.argv[1]), int(sys.argv[2])
    delay = float(sys.argv[3]) if len(sys.argv) > 3 else 1.5
    kn = known_ids()
    res = load(OUT, {})
    sess = requests.Session()
    for i in range(lo, hi + 1):
        k = str(i)
        cur = res.get(k) or ""
        if cur == "alive" or cur.startswith("dead"):
            continue
        st = probe(sess, k)
        res[k] = st
        if st == "alive":
            print("%s ALIVE  %s" % (k, kn.get(k, "<<< ABSENT DU CATALOGUE >>>")), flush=True)
        with open(OUT, "w", encoding="utf-8") as f:
            json.dump(res, f, indent=0, sort_keys=True)
        time.sleep(delay)
    alive = sorted([k for k, v in res.items() if v == "alive"], key=int)
    unknown = [k for k in alive if k not in kn]
    print("FINI. vivants=%d  absents du catalogue=%d" % (len(alive), len(unknown)), flush=True)
    print("ABSENTS: %s" % ",".join(unknown), flush=True)


if __name__ == "__main__":
    main()
