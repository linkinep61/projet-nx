# -*- coding: utf-8 -*-
"""Dump du catalogue OTF TV (reproduit exactement OtfTvService.kt).
Usage : python otf_dump.py [motif_de_recherche]
Ecrit otf_catalogue.json a cote pour analyse.
"""
import base64, json, os, random, re, sys, unicodedata
import requests
from Crypto.Cipher import AES
from Crypto.Util.Padding import pad, unpad

API = "https://app.otf-tv.com/otf/authV4.php"
KEY = b"@z5wFi5vDgtF_vds"


def enc(plain: str) -> str:
    iv = os.urandom(16)
    c = AES.new(KEY, AES.MODE_CBC, iv)
    return base64.b64encode(iv + c.encrypt(pad(plain.encode(), 16))).decode()


def dec(b64: str) -> str:
    raw = base64.b64decode(b64)
    c = AES.new(KEY, AES.MODE_CBC, raw[:16])
    return unpad(c.decrypt(raw[16:]), 16).decode("utf-8", "replace")


def norm(name: str) -> str:
    nfd = unicodedata.normalize("NFD", name.lower())
    nfd = "".join(ch for ch in nfd if not unicodedata.combining(ch))
    return re.sub(r"[^a-z0-9]", "", nfd)


def main():
    device_id = "%016x" % random.getrandbits(64)
    r = requests.post(
        API,
        data={"DeviceID": device_id, "hash": enc("5wF%s_Opd" % device_id)},
        headers={"User-Agent": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"},
        timeout=45,
        allow_redirects=False,
    )
    print("HTTP %s, %d octets" % (r.status_code, len(r.text)))
    if r.status_code != 200 or len(r.text) < 50:
        print(r.text[:300])
        return
    body = dec(r.text)
    body = re.sub(r",\s*\]", "]", body)
    body = re.sub(r",\s*\}", "}", body)
    data = json.loads(body)
    groups = data.get("Streams", [])

    out = []
    for g in groups:
        gname = (g.get("name") or "").strip()
        for ch in g.get("Channels", []) or []:
            name = (ch.get("name") or "").strip()
            if not name:
                continue
            urls = [q.get("url", "").strip() for q in (ch.get("vq") or [])]
            urls = [u for u in urls if u.startswith("http")]
            out.append({
                "group": gname,
                "name": name,
                "key": norm(name),
                "catId": ch.get("CatID", 0),
                "urls": urls,
                "logo": ch.get("thumbnail") or ch.get("logo") or ch.get("image") or "",
            })

    with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "otf_catalogue.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)

    print("Groupes (%d) :" % len(groups))
    from collections import Counter
    cnt = Counter(c["group"] for c in out)
    for gname, n in cnt.most_common():
        print("   %-30s %4d chaines" % (gname or "(vide)", n))
    print("TOTAL %d chaines, %d sans URL" % (len(out), sum(1 for c in out if not c["urls"])))

    motif = sys.argv[1] if len(sys.argv) > 1 else "france"
    k = norm(motif)
    print("\n--- chaines dont la cle contient '%s' ---" % k)
    for c in out:
        if k in c["key"]:
            hosts = sorted({u.split("/")[2] for u in c["urls"]})
            print("  [%s] %-28s key=%-22s cat=%-5s urls=%d %s" % (
                c["group"], c["name"], c["key"], c["catId"], len(c["urls"]), ",".join(hosts)))


if __name__ == "__main__":
    main()
