package com.streamflixreborn.streamflix.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * AniListTitres — rattrape les titres d'un anime quand TMDB n'a pas su le résoudre.
 *
 * ── POURQUOI ─────────────────────────────────────────────────────────────────────────────────
 * Mesuré sur la box le 07/08, deux animes ouverts depuis AnimeSama :
 *
 *   Yani Neko    tmdbId RÉSOLU 312949 → knownTitles = 6 titres → iAnime rend 6 serveurs
 *   Katainaka…   tmdbId = null        → knownTitles = 1 titre  → les 14 sources rendent 0
 *
 * AnimeSama indexe en ROMAJI. Quand TMDB ne reconnaît pas ce romaji, `knownTitles` se réduit au
 * seul titre romaji, et plus AUCUNE source française ne peut aboutir : le site range l'œuvre sous
 * son titre français. DessinAnime l'avait d'ailleurs trouvée — « Béryl : de paysan à maître
 * d'armes » — et l'a refusée, à juste titre, faute de correspondance.
 *
 * Le point de rupture est donc unique : la résolution du tmdbId sur un titre romaji.
 *
 * ── CE QUE ÇA FAIT ───────────────────────────────────────────────────────────────────────────
 * AniList (GraphQL public, sans clé) rend pour un même anime : le romaji, l'anglais, le natif
 * japonais, et ses synonymes régionaux. Vérifié en direct :
 *
 *   « Katainaka no Ossan Kensei ni Naru »
 *     → romaji  : Katainaka no Ossan, Kensei ni Naru
 *     → english : From Old Country Bumpkin to Master Swordsman
 *     → natif   : 片田舎のおっさん、剣聖になる
 *     → synonymes : polonais, portugais, indonésien, malais…
 *
 * Le titre ANGLAIS est la vraie clé : c'est lui que TMDB reconnaît, et TMDB fournit alors le
 * titre FRANÇAIS via `alternative_titles`. On enchaîne donc AniList → TMDB → titres FR.
 *
 * ⚠ On n'écrase JAMAIS la recherche existante : ces titres viennent EN PLUS (demande du user,
 *   07/08 : « activer les 2 leviers plutôt »). Le titre d'origine reste essayé en premier.
 *
 * ⚠ Sûreté du rapprochement : tous les titres rendus appartiennent à UNE SEULE fiche AniList,
 *   celle du meilleur résultat de recherche. Ce ne sont donc pas des œuvres différentes — la
 *   correspondance stricte des providers reste valable.
 */
object AniListTitres {
    private const val TAG = "AniListTitres"
    private const val ENDPOINT = "https://graphql.anilist.co"

    /** Une œuvre est réinterrogée au plus une fois par session. */
    private val cache = ConcurrentHashMap<String, List<String>>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    private const val REQUETE =
        "query(\$s:String){ Media(search:\$s, type:ANIME){ id title{ romaji english native } synonyms } }"

    /**
     * @return les titres connus de l'anime (romaji, anglais, natif, synonymes), ou une liste vide
     *   si AniList ne connaît pas l'œuvre ou ne répond pas. N'échoue jamais bruyamment : c'est un
     *   complément, pas une dépendance.
     */
    suspend fun titresPour(titre: String): List<String> = withContext(Dispatchers.IO) {
        if (titre.isBlank()) return@withContext emptyList()
        cache[titre]?.let { return@withContext it }

        val resultat = runCatching {
            val corps = JSONObject()
                .put("query", REQUETE)
                .put("variables", JSONObject().put("s", titre))
                .toString()
                .toRequestBody("application/json".toMediaType())

            val req = Request.Builder().url(ENDPOINT).post(corps)
                .header("Accept", "application/json")
                .build()

            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    Log.w(TAG, "AniList → HTTP ${r.code}")
                    return@use emptyList<String>()
                }
                val media = JSONObject(r.body?.string().orEmpty())
                    .optJSONObject("data")?.optJSONObject("Media")
                    ?: return@use emptyList<String>()

                val titres = linkedSetOf<String>()
                media.optJSONObject("title")?.let { t ->
                    listOf("romaji", "english", "native").forEach { cle ->
                        t.optString(cle).takeIf { it.isNotBlank() && it != "null" }?.let(titres::add)
                    }
                }
                media.optJSONArray("synonyms")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        arr.optString(i).takeIf { it.isNotBlank() }?.let(titres::add)
                    }
                }
                titres.toList()
            }
        }.getOrElse {
            Log.w(TAG, "AniList indisponible : ${it.message}")
            emptyList()
        }

        cache[titre] = resultat
        if (resultat.isNotEmpty()) Log.i(TAG, "'$titre' → ${resultat.size} titres : ${resultat.take(3)}")
        resultat
    }

    /** Le titre anglais, celui que TMDB reconnaît le mieux. */
    suspend fun titreAnglais(titre: String): String? =
        titresPour(titre).getOrNull(1)?.takeIf { it != titre }
}
