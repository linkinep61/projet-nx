# MÉMO — remettre les identifiants TMDB sur les fichiers VOE

**Pourquoi** : les administrateurs déposent des films dans les dossiers partagés
sans identifiant TMDB en tête du nom. Sans lui, ONYX ne rattache pas la fiche :
pas de jaquette, pas de synopsis, et le rapprochement par titre peut se tromper.

**Format attendu par l'application**

```
550 - Fight Club.mkv                  (film)
1399 - Game of Thrones - S01E01.mkv   (épisode : identifiant de la SÉRIE)
```

## La manip

```
cd C:\Users\guill\StudioProjects\streamflix\tools
python verifier_ids_voe.py              # contrôle seul, écrit rapport_ids_voe.csv
python verifier_ids_voe.py --appliquer  # renomme uniquement les cas « sûrs »
```

Le script parcourt **tout le compte VOE sauf les dossiers dont le chemin contient
« clip »** — ce sont des clips musicaux, ils n'ont rien à voir avec TMDB et ne
doivent jamais être identifiés ni proposés comme source d'un film.

## Ce qu'il faut savoir

- **DNS** : ton FAI ne résout pas `voe.sx`. Le script embarque le contournement
  DNS-over-HTTPS (1.1.1.1 / 8.8.8.8), comme `ONYX_Uploader.pyw` et l'appli
  Android. Sans lui : `[Errno 11001] getaddrinfo failed`.
- **Clés** : `VOE_API_KEY` est lue dans `local.properties`, jamais écrite dans le
  script.
- **API VOE** : `/api/folder/list` (le champ `files` est **paginé** :
  `{current_page, data, last_page}`, pas une liste), `/api/file/rename?file_code=&title=`.
  7 appels rapides passent, le 8ᵉ prend un 429 — le script attend et réessaie.
- **Prudence volontaire** : ne sont renommés automatiquement que les titres qui
  correspondent **exactement** (accents/casse/ponctuation neutralisés) et dont
  l'année colle à un an près quand le nom en porte une. Deux films connus du même
  titre sans année dans le nom (« La Machine à explorer le temps » : 1960 et 2002)
  → laissés à la main, sinon on se trompe une fois sur deux.
- Le reste part dans `rapport_ids_voe.csv`, colonne `confiance` = `doute` / `rien`.

## ⚠ Piège : l'identifiant doit être un identifiant de FILM

L'application ne va chercher la fiche que sur `https://api.themoviedb.org/3/**movie**/{id}`
(cf. `VoeLibrary.completerFiches`). Mettre un identifiant de **série** en tête d'un nom
de fichier est donc dangereux : TMDB numérote films et séries séparément, et le même
numéro désigne souvent autre chose côté film.

Exemple vécu le 18/08 : les épisodes de *Hell Mode* renommés `280049 - …` auraient affiché
la jaquette d'un **film tamoul de 2016** qui porte ce numéro côté films. Identifiant retiré,
retour à la vignette VOE — c'est propre. À l'inverse, *Silo* (125988) et *Scènes de ménages*
(55216) n'existent pas côté films : aucun risque, on les laisse.

**Règle** : avant d'écrire un identifiant de série, vérifier `/3/movie/<id>`.
S'il répond autre chose qu'un 404, ne pas mettre l'identifiant.

## Identifier à l'image quand le nom ne suffit pas

VOE génère pour chaque fichier une planche de 25 vignettes :

```
https://i.voe.sx/cache/<file_code>_storyboard_L1.jpg
```

`tools/_vignettes.py` les télécharge pour tous les fichiers encore sans identifiant.
C'est ce qui a permis de trancher là où le titre seul se trompait :

- `eagle.avi` → **L'Aigle de la Neuvième Légion** (2011), pas « Aigle de fer » ;
- `Dream Team.avi` → **La Dream Team** (2016, français), pas le film tchèque homonyme ;
- `La Machine à explorer le temps` → version **1960**, pas le remake de 2002 ;
- `Combats De Rues.avi` → **Fighting** (2009) ;
- `le Hobbit.mkv` → **Un voyage inattendu**, le premier volet.

## Historique

- **2026-08-18** : 1117 fichiers sur le compte, 47 sans identifiant hors clips.
  20 renommés automatiquement (Dune 1984, Blade Runner 1982, L'Esprit Coubertin,
  la saison 1 de Silo…), 11 confirmés à la main (Apocalypse Now, Le voyage
  fantastique 1966, 20 000 Lieues sous les mers 1954, Scènes de ménages,
  Hell Mode saison 2 → série TMDB 280049). 16 restants à trancher : Mickey Mouse,
  Dora, « Je veux une glace », « Combats de rues », un doc 360° GEO, « Dream Team »,
  « le Hobbit », « eagle », « La Pièce », « Halo The Fall of Reach ».


---

## 2026-08-19 — État à la reprise

### Ce qui est fait
- **878 épisodes** (19 séries de `D:\video\séries`) envoyés dans `Série`, saison par saison.
  11 doublons locaux écartés dans `_doublons` (rien supprimé) ; `carnival row S01E02`
  laissé de côté : le fichier fait **0 octet** sur le disque.
- **199 films** du Bureau envoyés dans leurs 9 genres sous `Films`.
- Compte : **2195 fichiers, 81 dossiers**.

### Doublons — 1 seul sur 1660 fichiers (clips exclus)
`Films / Fantastique` — `2134 - La Machine à explorer le temps.mp4` **en double**
codes `twnkpzcfqmsw` et `bwhpmq1cpcz8`. **À supprimer par l'utilisateur** (je ne
supprime jamais de données).

### Identifiants TMDB manquants
- **100 films** sans identifiant (voir `tools/sans_id.csv` et `tools/films_identifies.csv`)
- 897 épisodes de séries sans identifiant : **normal**, ne pas leur mettre l'id de
  la série (il ferait correspondre chaque épisode à tous les autres).

Répartition de la recherche automatique (`identifier_films.py`) :
`sur` 1 · `doute` 57 · `rien` 42.

⚠ Les verdicts « doute » contiennent de **fausses pistes évidentes** à refuser :
`20.mp4`→Ralph 2.0, `02.mp4`→Digimon 02, `03/05/07/08.mp4`→Zuffa Boxing,
`Air (VO)`→Air (2023, Nike) alors que c'est l'anime de 2005, `Numero 4`,
`Viana`, `La Pièce`, `Book girl`, `09 petit ami`. TMDB s'accroche à n'importe
quoi quand le nom est un simple numéro.

### Identification par l'image — trouvailles du jour
Méthode : `python tools/vignettes_lot.py <code> …` télécharge
`https://i.voe.sx/cache/<code>_storyboard_L1.jpg` dans `tools/_vignettes/`,
puis on stage le fichier vers le conteneur (`device_stage_files`) et on le lit
comme image. Le dossier `tools/` est dans le dossier connecté, donc ça marche
sans rien déplacer.

- **`Films / Non classé`, les 24 fichiers « 01 … 24 »** (titres du genre
  « la sorcière dans le tonneau », « la fille du temps ») =
  **ZETSUEN NO TEMPEST** (絶園のテンペスト, « Blast of Tempest », 2012, 24 épisodes).
  Preuves : carton-titre japonais dans le générique, personnages **Fuwa Mahiro**
  et **Fuwa Aika** (cf. le fichier « 22 Aïka Fuwa »), OP « Spirit Inspiration ».
  → Ce n'est PAS un film : à déplacer dans `Série / Zetsuen no Tempest`,
  nommé `<idSérie> - Zetsuen no Tempest - S01Exx`.
- **`Films / Combat`, `03.mp4`** = un anime scolaire, carton final **« SEIKI »**,
  personnage « Saotome », mention 善導課. À confirmer (piste : *Seiki no Tempest*
  non — chercher « SEIKI » + Saotome).

### Reste à faire
1. Finir l'identification par l'image des fichiers numérotés :
   Combat `05/07/08`, Drame `06/15/16/17/18`, Animations `02/20`,
   Horreur `09/23`, Non classé `01/04` — vignettes **déjà téléchargées** dans
   `tools/_vignettes/`.
2. Puis les nommés introuvables : `Viana`, `Identity.ZiW`,
   `Harold et la légende du Pikpoketos`, `Je Veux Une Glace`, `La Pièce`,
   Mickey ×4, DORA ×2, `Michael Gregorio`, `360 GEO Guyane`, `Halo The Fall Of Reach`
   (TMDB ne l'a qu'en série, 77184), `Sexy dance 3` ×2, `Yobie`,
   `Ookami Kodomo`, `Le Chateau De Caliostro` (= Lupin III, Cagliostro),
   `Kung fu panda - joyeux noel`, `Fairy Tail - La prêtresse du Phénix`.
3. Valider à la main les 57 « doute » (beaucoup sont justes : akira #149,
   coco #354912, Kiki #16859, Le vent se lève #149870, Le tombeau des lucioles
   #12477, les 6 Broken Blade, les 2 Sakura, Silent Voice #378064…).
4. Renommer `very bad blagues / Very Bad Blagues - Saison 2 AVI` → `… Saison 2`.
5. Supprimer les 3 dossiers de test `ZZFP0/1/2` à la racine.
6. Construire le garde-fou « 60 jours sans vue » (journal des compteurs
   `file_views_full` relevé à chaque lecture du compte, puis renouvellement
   depuis la source locale). `/api/file/info` accepte **plusieurs codes**
   séparés par des virgules → ~20 requêtes pour tout le compte.
