package com.streamflixreborn.streamflix.utils

/**
 * 2026-10-19 (user « sur Vidzy, The Ark S1E1, le segment tourne en boucle ») — CLÉ DE SEGMENTS VIDZY.
 *
 * Mesuré dans le navigateur sur vidzy.cc : la playlist est saine (VOD, ENDLIST, 299 segments
 * distincts), mais le CDN ne sert que les 9 PREMIERS segments (≈ 90 s). À partir du 10ᵉ :
 * HTTP 403, quel que soit l'ordre ou les cookies. Le lecteur de la page ajoute à CHAQUE adresse
 * de segment un paramètre `gk` (60 caractères, identique pour toute la séance) qui n'est ni dans
 * la playlist, ni dans les cookies, ni dans le HTML : leur JavaScript le range dans
 * `window.__vzgk`. Vérifié : segment 20 sans gk → 403, avec gk → 200 ; segment 200 avec gk → 200.
 * Sans cette clé, ExoPlayer bute au 10ᵉ segment et rejoue le même morceau en boucle.
 *
 * VidzyExtractor la lit dans sa WebView (déjà utilisée pour capter le flux) et la mémorise ici ;
 * FiltreManifesteHls l'ajoute aux requêtes de segments Vidzy qui ne l'ont pas.
 */
object CleVidzy {
    @Volatile
    var cle: String? = null
        private set

    fun memoriser(valeur: String) {
        cle = valeur
    }

    // 2026-10-22 (user : « Premium VF HD recommence depuis le début au bout de quelques minutes »,
    //   série 100 % physique sur la TV) : FSVid (« FS · Premium ») tourne sur le MÊME moteur — 403
    //   sur seg-10 (1 min 30), puis rechargement depuis 0. Sa page pose elle aussi `window.__vzgk`
    //   (60 car.), mais une clé DIFFÉRENTE de celle de Vidzy (vérifié dans Chrome) → clé à part.
    @Volatile
    var cleFsvid: String? = null
        private set
    @Volatile
    var cleFsvidDepuis: Long = 0L
        private set

    fun memoriserFsvid(valeur: String) {
        cleFsvid = valeur
        cleFsvidDepuis = System.currentTimeMillis()
    }
}