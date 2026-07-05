# LumiChat — tri automatique des chaînes

Pipeline qui garde le catalogue LumiChat propre pour l'app Onyx (TV Hub).

- `refresh_lumichat.py` : teste EN AMONT chaque chaîne via la passerelle
  (gateway.lumichat.fun) avec le même fingerprint que l'app (UA Chrome + Origin/Referer
  frenchtv.vdfr.uk). Ne garde que les chaînes qui résolvent réellement (plus d'écran noir),
  puis régénère `../data-lumichat.m3u` (lu par l'app).
- Déclenché par `.github/workflows/refresh_lumichat.yml` (toutes les 12h + manuel),
  avec auto-suppression des anciens runs pour ne pas polluer le dépôt.

L'app regroupe ensuite les doublons de même nom en une chaîne multi-serveurs
(le player essaie chaque source et joue celle qui marche).
