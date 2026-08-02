package com.streamflixreborn.streamflix.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.io.Serializable

data class Video(
    val source: String,
    val subtitles: List<Subtitle> = listOf(),
    val headers: Map<String, String>? = null,
    val type: String? = null,
    val extraBuffering: Boolean = false,
    val useServerSubtitleSetting: Boolean = false,
    val webViewUrl: String? = null,
    /** When true, the TV player must show a WebView overlay so the user can
     *  click the anti-bot play button before the M3U8 becomes accessible. */
    val needsWebViewClick: Boolean = false,
    /**
     * 2026-08-01 (user : « Movix Rpmvid VF est en réalité en VOSTFR ») : NOM DE FICHIER réel
     * du flux, quand l'hébergeur le fournit (ex. « In.the.Grey.2026.VOSTFR.1080p.WEBRip.mp4 »).
     * Beaucoup de sites étiquettent leurs liens « VF » sans vérifier, alors que le nom du
     * fichier, lui, dit la vérité. Il sert donc à CORRIGER la langue affichée du serveur.
     * null = l'hébergeur ne le donne pas.
     */
    val fileName: String? = null,
    /**
     * 2026-08-09 (user, playlist FREETV Inspiration Links) : DRM déclaré par la SOURCE
     * elle-même, pas deviné par un resolver. `#KODIPROP:inputstream.adaptive.license_type`
     * dans un M3U vaut "clearkey" ou "com.widevine.alpha".
     *  - "clearkey" → [drmLicense] contient DIRECTEMENT la réponse de licence JSON
     *    (`{"keys":[{"kty":"oct","k":…,"kid":…}],"type":"temporary"}`), prête à servir
     *    hors ligne : la clé est écrite en clair dans le M3U, aucun serveur à interroger.
     *  - "widevine" → [drmLicense] contient l'URL du serveur de licence.
     * null = pas de DRM (cas de l'immense majorité des flux).
     */
    val drmType: String? = null,
    val drmLicense: String? = null,
) : Serializable {

    sealed class Type : Parcelable, Serializable {
        @Parcelize
        data class Movie(
            val id: String,
            val title: String,
            val releaseDate: String,
            val poster: String,
            val imdbId: String?,
        ) : Type(), Serializable

        @Parcelize
        data class Episode(
            val id: String,
            val number: Int,
            val title: String?,
            val poster: String?,
            val overview: String?,
            val tvShow: TvShow,
            val season: Season,
        ) : Type(), Serializable {
            @Parcelize
            data class TvShow(
                val id: String,
                val title: String,
                val poster: String?,
                val banner: String?,
                val releaseDate: String?,
                val imdbId: String?,
            ) : Parcelable, Serializable

            @Parcelize
            data class Season(
                val number: Int,
                val title: String?,
            ) : Parcelable, Serializable
        }
    }

    data class Subtitle(
        val label: String,
        val file: String,
        var default: Boolean = false,
        val initialDefault: Boolean = false
    ) : Serializable

    data class Server(
        val id: String,
        val name: String,
        val src: String = "",
        /** Miroirs alternatifs (meme host, ex plusieurs liens Uqload pour le
         *  meme film). src reste l'URL principale (1 seule, valide) pour le
         *  pre-extract/HEAD/cache ; getVideo essaie src puis ces miroirs jusqu'au
         *  premier vivant. Vide = pas de miroir, comportement classique. */
        val mirrors: List<String> = emptyList(),
    ) : Serializable {
        var video: Video? = null
        @Volatile var quality: String? = null

        /**
         * 2026-07-31 (user : « le #2 est automatiquement VOSTFR alors que le #3 a bien 2
         * langues, FR et anglais ») : langue(s) RÉELLE(S) du flux, lues dans le manifeste HLS
         * pendant le sondage de qualité (aucune requête supplémentaire).
         * Deux hébergeurs peuvent servir le même film sous des langues différentes sans que
         * l'URL ou l'API ne le disent — seule la piste audio du manifeste fait foi.
         * Ex. « VOSTFR » (audio anglais seul) ou « VF+VO » (deux pistes). null = inconnue.
         */
        @Volatile var language: String? = null

        /**
         * Indice de qualité CHIFFRÉ, en kilobits par seconde, qui sert à départager deux serveurs
         * affichant la même définition : à « 1080p » égal, le mieux encodé a le plus gros débit.
         *
         * Origine UNIQUE : `BANDWIDTH` du manifeste HLS, déjà en mémoire pendant le sondage de
         * qualité — donc gratuit, jamais une requête de plus.
         *
         * ⚠ 2026-08-03 : la seconde origine (poids d'un fichier direct mesuré en HEAD) a été
         * RETIRÉE à la demande du user. Elle ne pouvait atteindre que les 4 serveurs pré-extraits,
         * et parmi eux les seuls fichiers directs restés sans qualité — rendement quasi nul.
         * Ne pas la réintroduire sans élargir la couverture, sinon elle ne servira pas davantage.
         *
         * 0 = inconnu : le serveur garde alors sa place, il n'est jamais rétrogradé pour ça.
         */
        @Volatile var debitKbps: Int = 0
    }
}
