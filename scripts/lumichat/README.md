# scripts/lumichat

`refresh_lumichat.py` : teste chaque chaine LumiChat via la passerelle (meme fingerprint que
l'app), ne garde que celles qui resolvent, et regenere `data/lumichat/data-lumichat.m3u`.
Declenche par `.github/workflows/refresh_lumichat.yml` (12h + manuel).
