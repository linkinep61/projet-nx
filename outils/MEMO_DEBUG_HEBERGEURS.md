# Mémo — déboguer un hébergeur qui répond 403

Écrit le 2026-08-08, après **5 h 30 et ~15 builds** pour un correctif de deux lignes (upbolt).
But : que le prochain cas du même genre prenne 20 minutes.

---

## 1. Le réflexe qui aurait tout évité : chercher le jumeau

Avant toute hypothèse, identifier la **famille** de l'hébergeur par la forme de son URL.

Famille « hls2 / urlset » (XFileSharing-like) — signature :

```
https://<edge>/hls2/NN/NNNNN/,<id>_l,<id>_n,<id>_h,.urlset/master.m3u8?t=…&s=…&e=…&v=…&i=…&sp=…
```

Membres connus dans ce dépôt : **upbolt.to**, **uqload.is**. Même logiciel, mêmes pièges.

**→ Si un membre de la famille fonctionne déjà, lire SON extracteur en premier.**
La règle qui a débloqué upbolt était écrite depuis des semaines dans `UqloadExtractor.kt` :

> « le serveur filtre sur la COMBINAISON UA + Sec-Fetch » → UA **Chrome desktop**, pas mobile.

## 2. Le correctif upbolt (2026-08-08)

Le jeton `t=` est délivré **pour une identité client** et doit être rejoué avec **exactement la même**,
de bout en bout : page d'embed, `POST /dl`, appel CDN, et **le lecteur**.

- UA `Mozilla/5.0 (Windows NT 10.0; Win64; x64) … Chrome/148.0.0.0 Safari/537.36`
- en-têtes `Sec-Fetch-*` + `Sec-Ch-Ua*` cohérents avec cet UA (`?0`, `"Windows"`)
- une seule variante servie au lecteur (`rewriteUpboltMasterToVariant`) : sonder une 2ᵉ qualité grille le jeton
- un appel de « mise en route » de la playlist côté extracteur avant de rendre la main

Résultat : `seg-20` (le saut à la position de reprise, qui échouait tout le temps) → **200**.

## 3. Les trois pièges de lecture de journal qui ont coûté les 5 h

| Piège | Symptôme | Parade |
|---|---|---|
| Ligne de diag qui affiche les en-têtes **fabriqués** (`currentVideo.headers`) et non ceux envoyés | on « corrige » un `Cookie=` qui n'existe que dans le texte | poser un **intercepteur réseau** OkHttp et logger `chain.request().headers` |
| `Select-String` ne garde que la 1ʳᵉ ligne d'un message multi-lignes (logcat découpe) | « il n'envoie que le User-Agent » alors que tout est là | grep aussi `Referer:` / `Origin:` séparément |
| Compteur posé dans un `DataSource` qui ne voit que les playlists | 157 Ko comptés quand le film en télécharge des Mo | vérifier le point de passage avant d'en tirer un modèle |

**Règle** : avant de bâtir une hypothèse sur un chiffre, vérifier d'où il sort.

## 4. Discipline de correction

- **Un seul changement à la fois.** Deux changements simultanés rendent la mesure ininterprétable.
- **Si la mesure ne progresse pas, le changement repart.** Ne rien garder « au cas où ».
- Empiler sept couches, c'est ce qui a fait passer la lecture de 1 min 45 à zéro.

## 5. Fausses pistes déjà éliminées sur cette famille (ne pas y retourner)

Débit plafonné (`sp=`), volume par jeton, fenêtre de segments, cookies DDoS-Guard périmés,
`Accept-Encoding`, `Range`, DNS/edge différent, rotation de jeton en cours de lecture
(**contre-productive** : un jeton neuf repart avec un budget vide et tue celui du lecteur).

Point de rupture qui se déplace (seg-4, 14, 25, 27…) : ce n'est **pas** une limite serveur,
c'est la position de reprise de l'utilisateur.

---

## 6. Cas Uqload (2026-08-08) — deux contournements qui s'annulent

**Symptôme** : `FrenchStream · Uqload` rouge, alors que le lien joue parfaitement dans le navigateur.

**Séquence au journal** :

```
Forced DoT-OkHttp for DNS-blocked host strm7.uqload.is
403 sur .../,l,n,h,.urlset/master.m3u8
```

Le FAI bloque `uqload.is` au DNS (vérifié : le PC ne résout pas ce nom non plus). L'app
contourne via son résolveur DoT — mais change AUSSI de pile réseau, Cronet → OkHttp, et perd
le JA3 Chrome sur lequel Uqload signe son jeton. Chaque pile n'a que la moitié :

| pile | résolution DNS | signature TLS | résultat |
|---|---|---|---|
| OkHttp + DoT | OK | mauvaise | **403** |
| Cronet seul | échoue | bonne | **code=-1** |

**Parade en place** (`StreamFlixApp.getCronetEngineAvecAdresse`) : garder Cronet et lui imposer
l'adresse via `HostResolverRules` de Chromium, en lisant l'IP dans le cache de `DnsResolver`.
⚠ **Non éprouvée sur le terrain** : le jour du diagnostic, le serveur a cessé de renvoyer 403
avant que ce chemin ne s'exécute. À valider au prochain 403.

**Lecture des codes d'erreur** :
- `code=-1` → aucune réponse HTTP, donc **nom non résolu** (ce n'est pas un blocage serveur)
- `code=403` → le serveur a répondu et refuse : jeton, identité client, ou signature TLS

## 7. Un même hébergeur ≠ un même fichier

Chaque site téléverse SA copie. `Frembed · Uqload` et `FrenchStream · Uqload` sont deux fichiers
distincts qui partagent seulement l'hébergeur — l'un peut être supprimé et l'autre intact.

**Comment trancher sans rien deviner** : `collapseIdenticalServers` (PlayerViewModel) fusionne
les serveurs de MÊME `src`, tous fournisseurs confondus, et affiche `×N`. Donc :

- serveurs affichés **séparément** → URL différentes → fichiers différents
- serveurs affichés **`×2`, `×3`** → même lien trouvé par plusieurs sites

Corollaire : si après un correctif d'HÔTE un seul reste rouge, c'est SA copie qui est morte —
rien à réparer côté application.
