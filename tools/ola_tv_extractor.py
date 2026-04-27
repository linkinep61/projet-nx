#!/usr/bin/env python3
"""
OLA TV Channel Extractor
Extracts French IPTV channel sources from OLA TV's API.

Usage:
  pip install pycryptodome requests
  python3 ola_tv_extractor.py [server_id]

  server_id: 1985-2011 (OLA TV server numbers, default: 2000)
"""

import json
import sys
import hashlib
import base64
import random
import string

API_URL = "http://iptvdroid.monster/IP11/api.php"
AES_KEY = "3234567890123453"

def get_random_salt(length=16):
    return ''.join(random.choices(string.ascii_letters + string.digits, k=length))

def md5_hash(text):
    return hashlib.md5(text.encode()).hexdigest()

def pad_pkcs5(data):
    pad_len = 16 - (len(data) % 16)
    return data + bytes([pad_len] * pad_len)

def unpad_pkcs5(data):
    pad_len = data[-1]
    if pad_len > 16:
        return data
    return data[:-pad_len]

def aes_encrypt(plaintext, key):
    from Crypto.Cipher import AES
    key_bytes = key.encode('utf-8')
    iv = key_bytes[:16]
    cipher = AES.new(key_bytes, AES.MODE_CBC, iv)
    padded = pad_pkcs5(plaintext.encode('utf-8'))
    encrypted = cipher.encrypt(padded)
    return base64.b64encode(encrypted).decode()

def aes_decrypt(ciphertext_b64, key):
    from Crypto.Cipher import AES
    key_bytes = key.encode('utf-8')
    iv = key_bytes[:16]
    cipher = AES.new(key_bytes, AES.MODE_CBC, iv)
    raw = base64.b64decode(ciphertext_b64)
    decrypted = cipher.decrypt(raw)
    return unpad_pkcs5(decrypted).decode('utf-8', errors='replace')

def api_call(action, extra_params=None):
    """Try multiple request formats to find the right one."""
    import requests

    params = {"action": action}
    if extra_params:
        params.update(extra_params)

    payload_json = json.dumps(params)
    print(f"[*] Payload: {payload_json}")

    # Approach 1: Encrypted POST with data param
    try:
        encrypted = aes_encrypt(payload_json, AES_KEY)
        print(f"[*] Approach 1: encrypted POST (data=...)")
        r = requests.post(API_URL, data={"data": encrypted}, timeout=15)
        print(f"    Status: {r.status_code}, Body ({len(r.text)} bytes): {r.text[:300]}")
        if r.text.strip():
            try:
                return json.loads(r.text)
            except:
                try:
                    dec = aes_decrypt(r.text.strip(), AES_KEY)
                    print(f"    Decrypted: {dec[:300]}")
                    return json.loads(dec)
                except Exception as e:
                    print(f"    Decrypt error: {e}")
    except Exception as e:
        print(f"    Failed: {e}")

    # Approach 2: Encrypted body directly
    try:
        encrypted = aes_encrypt(payload_json, AES_KEY)
        print(f"[*] Approach 2: encrypted body directly")
        r = requests.post(API_URL, data=encrypted, headers={"Content-Type": "text/plain"}, timeout=15)
        print(f"    Status: {r.status_code}, Body ({len(r.text)} bytes): {r.text[:300]}")
        if r.text.strip():
            try:
                dec = aes_decrypt(r.text.strip(), AES_KEY)
                print(f"    Decrypted: {dec[:300]}")
                return json.loads(dec)
            except Exception as e:
                print(f"    Decrypt error: {e}")
    except Exception as e:
        print(f"    Failed: {e}")

    # Approach 3: Raw JSON POST
    try:
        print(f"[*] Approach 3: raw JSON POST")
        r = requests.post(API_URL, json=params, timeout=15)
        print(f"    Status: {r.status_code}, Body ({len(r.text)} bytes): {r.text[:300]}")
        if r.text.strip():
            try:
                return json.loads(r.text)
            except:
                try:
                    dec = aes_decrypt(r.text.strip(), AES_KEY)
                    print(f"    Decrypted: {dec[:300]}")
                    return json.loads(dec)
                except:
                    pass
    except Exception as e:
        print(f"    Failed: {e}")

    # Approach 4: Form-encoded with salt and md5
    try:
        salt = get_random_salt()
        sig = md5_hash(action + salt + AES_KEY)
        form_data = {"action": action, "salt": salt, "sig": sig}
        if extra_params:
            form_data.update(extra_params)
        print(f"[*] Approach 4: form-encoded with salt+sig")
        r = requests.post(API_URL, data=form_data, timeout=15)
        print(f"    Status: {r.status_code}, Body ({len(r.text)} bytes): {r.text[:300]}")
        if r.text.strip():
            try:
                return json.loads(r.text)
            except:
                try:
                    dec = aes_decrypt(r.text.strip(), AES_KEY)
                    print(f"    Decrypted: {dec[:300]}")
                    return json.loads(dec)
                except:
                    pass
    except Exception as e:
        print(f"    Failed: {e}")

    # Approach 5: Encrypted params with action outside
    try:
        encrypted_params = aes_encrypt(json.dumps(extra_params or {}), AES_KEY)
        print(f"[*] Approach 5: action + encrypted params")
        r = requests.post(API_URL, data={"action": action, "data": encrypted_params}, timeout=15)
        print(f"    Status: {r.status_code}, Body ({len(r.text)} bytes): {r.text[:300]}")
        if r.text.strip():
            try:
                return json.loads(r.text)
            except:
                try:
                    dec = aes_decrypt(r.text.strip(), AES_KEY)
                    print(f"    Decrypted: {dec[:300]}")
                    return json.loads(dec)
                except:
                    pass
    except Exception as e:
        print(f"    Failed: {e}")

    return None


def extract_channels(server_id):
    print(f"\n{'='*60}")
    print(f"OLA TV Channel Extractor - Server {server_id}")
    print(f"{'='*60}")

    print("\n[1] Getting app details...")
    details = api_call("get_app_details")
    if details:
        print(f"\n[+] App details:\n{json.dumps(details, indent=2, ensure_ascii=False)[:1000]}")

    print(f"\n[2] Getting channels for server {server_id}...")
    channels = api_call("get_channel", {"server": str(server_id)})

    if not channels:
        print("\n[!] Could not retrieve channels. The API format may need adjusting.")
        print("    Try running with mitmproxy to capture the actual request format:")
        print("    1. Install mitmproxy: pip install mitmproxy")
        print("    2. Run: mitmweb --listen-port 8080")
        print("    3. Set phone proxy to <computer-ip>:8080")
        print("    4. Open OLA TV and select a server")
        print("    5. Check mitmweb for the request/response format")
        return []

    # Save raw response
    with open(f"ola_tv_server_{server_id}_raw.json", 'w') as f:
        json.dump(channels, f, indent=2, ensure_ascii=False)
    print(f"\n[+] Raw response saved to ola_tv_server_{server_id}_raw.json")

    # Try to extract channel list
    ch_list = channels if isinstance(channels, list) else \
              channels.get("channels", channels.get("data", channels.get("result", [])))

    if not isinstance(ch_list, list):
        print(f"[!] Unexpected response format: {type(ch_list)}")
        return []

    # Filter French channels
    french = []
    for ch in ch_list:
        name = str(ch.get("category_name", ch.get("name", "")))
        if any(k in name.upper() for k in ["FR|", "FR ", "FRANCE", "CANAL", "TF1", "M6", "ARTE", "BFM", "BEIN"]):
            french.append(ch)

    print(f"\n[+] {len(french)} French channels / {len(ch_list)} total")

    # Generate M3U
    m3u = "#EXTM3U\n"
    for ch in french:
        name = ch.get("category_name", ch.get("name", "Unknown"))
        t1 = ch.get("token1", ch.get("url", ""))
        t2 = ch.get("token2", "")
        logo = ch.get("logo", ch.get("icon", ""))
        group = ch.get("category_type", ch.get("group", "France"))

        if t1:
            # Xtream Codes format: https://host/token1/token2
            if t2 and not t1.startswith("http"):
                url = f"https://{t1}/{t2}"
            elif t1.startswith("http"):
                url = t1
            else:
                url = t1
            m3u += f'#EXTINF:-1 group-title="{group}" tvg-logo="{logo}",{name}\n{url}\n'

    outfile = f"ola_tv_server_{server_id}_fr.m3u"
    with open(outfile, 'w') as f:
        f.write(m3u)
    print(f"[+] M3U playlist saved to {outfile}")

    for ch in french[:15]:
        print(f"  - {ch.get('category_name', ch.get('name', '?'))}")
    if len(french) > 15:
        print(f"  ... and {len(french) - 15} more")

    return french


if __name__ == "__main__":
    server_id = int(sys.argv[1]) if len(sys.argv) > 1 else 2000

    try:
        from Crypto.Cipher import AES
        print("[+] PyCryptodome OK")
    except ImportError:
        print("[!] Install PyCryptodome: pip install pycryptodome")
        sys.exit(1)

    try:
        import requests
        print("[+] requests OK")
    except ImportError:
        print("[!] Install requests: pip install requests")
        sys.exit(1)

    extract_channels(server_id)
