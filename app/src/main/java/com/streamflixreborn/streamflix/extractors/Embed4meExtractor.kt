package com.streamflixreborn.streamflix.extractors

import android.util.Log
import com.streamflixreborn.streamflix.models.Video

/**
 * 2026-07-29 — Extracteur pour lpayer.embed4me.com (« Lecteur embed4me »), utilisé par anime-sama
 * (Lecteur 2) et FRAnime.
 *
 * embed4me est un player **vidstack** dont l'API est chiffrée (`/api/v1/info` → hex déchiffré par
 * un JS obfusqué).
 *
 * ── 2026-08-02 : EXTRACTION NATIVE D'ABORD ──────────────────────────────────────────────────
 * Cette classe se contentait de renvoyer `needsWebViewClick = true` (lecteur affiché dans une
 * WebView, l'utilisateur clique play). Or `LpayerExtractor` — 600 lignes — implémente déjà une
 * extraction complète pour CE MÊME hôte : capture de la clé AES via les hooks `crypto.subtle`,
 * déchiffrement autonome, interception du m3u8, clics `MotionEvent` réels pour passer le contrôle
 * `isTrusted` de Vidstack.
 *
 * Ce code n'avait jamais tourné : les deux extracteurs déclarent le même `mainUrl`, et celui-ci
 * est enregistré bien plus haut dans `Extractor.extractors` — il gagnait donc systématiquement.
 * On tente désormais l'extraction native en premier ; le mode manuel ne sert plus que de repli,
 * si l'extraction échoue ou expire. Aucune régression possible : en cas d'échec, on retombe
 * exactement sur le comportement précédent.
 */
class Embed4meExtractor : Extractor() {
    override val name = "Embed4me"
    override val mainUrl = "https://lpayer.embed4me.com"
    override val aliasUrls = listOf(
        "https://embed4me.com",
        "https://player.embed4me.com",
        // ⚠⚠ 2026-08-05 — `doremifasol.ezplayer.me` RETIRÉ DES ALIAS. Ne pas le remettre.
        //
        //   Il avait été ajouté le 2 août au motif que « RpmvidExtractor ne sait pas lire ce
        //   player ». Les logs disent l'inverse, et c'est vérifiable :
        //     · 31/07 et 01/08 — `[EXTRACTOR] -> Starting: Rpmvid` sur cet hôte, puis
        //       `[VIDEO] -> Extracted: …/tt/master.m3u8` en UNE SECONDE. Rpmvid le lisait.
        //     · 05/08 — depuis la bascule : `LpayerExtractor: pont JS → LP_KEY:AES-CBC`,
        //       puis `extraction native KO (timeout or no media URL) → repli WebView manuel`
        //       après 18,7 s. L'utilisateur doit cliquer play à la main.
        //
        //   Le pont JS et la capture de clé AES fonctionnent pourtant : ce n'est donc ni un
        //   problème de rendu, ni d'opacité de la WebView (piste creusée puis écartée le
        //   05/08). C'est simplement que Lpayer ne va pas au bout sur CE player-là, alors que
        //   Rpmvid y arrivait. On rend donc l'hôte à Rpmvid.
        //
        //   `lpayer.embed4me.com` et `player.embed4me.com` restent ici : eux sont bien le
        //   moteur d'Embed4me.
    )
    override val cacheTtlMs: Long = 0L

    override suspend fun extract(link: String): Video {
        // 1) Extraction native (lecture ensuite par ExoPlayer : seek, qualité, contrôles natifs).
        try {
            val natif = LpayerExtractor().extract(link)
            Log.d(TAG, "extract($link) → extraction native OK")
            return natif
        } catch (e: Throwable) {
            Log.w(TAG, "extraction native KO (${e.message}) → repli WebView manuel")
        }
        // 2) Repli : ancien comportement, l'utilisateur clique play dans l'overlay.
        return Video(source = link, webViewUrl = link, needsWebViewClick = true)
    }

    companion object {
        private const val TAG = "Embed4meExtractor"
    }
}
