package com.streamflixreborn.streamflix.extractors

import android.util.Log
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 2026-07-17 — Vidara (viewdara.com / vidara.to / vidara.so).
 *
 * Nouveau hoster apparu côté FrenchStream / Flemmix(Wiflix) — signalé
 * « No extractors found » dans les logs. viewdara.com est une FAÇADE : le
 * backend réel est Vidara (cf. https://viewdara.com/api qui documente
 * api.vidara.so). Tous ces domaines servent la même plateforme.
 *
 * EMBED : https://<host>/e/<filecode>   (filecode ~13 chars)
 *
 * EXTRACTION 100 % NATIVE (aucune WebView) — validé en direct dans Chrome :
 *   POST https://<host>/api/stream
 *        Content-Type: application/json
 *        {"filecode":"<code>","device":"android"}
 *   → 200 JSON EN CLAIR :
 *     {
 *       "filecode": "...", "title": "...", "thumbnail": "...",
 *       "streaming_url": "<master m3u8 signé ~247 chars>",
 *       "subtitles": {...}, "default_sub_lang": "...", "vast_ads": ""
 *     }
 *
 * ⚠️ PIÈGE ÉVITÉ : la page embed charge crypto-js + pako et contient un blob
 *   base64 de ~2000 chars manipulé via atob/charCodeAt. C'est un LEURRE : la
 *   source vient simplement de /api/stream en clair. Ne PAS réimplémenter le
 *   déchiffrement JS.
 *
 * `device` est dérivé du User-Agent par le player (ios/android/web) ; on envoie
 * "android" puisque c'est notre cas.
 */
class VidaraExtractor : Extractor() {

    override val name = "Vidara"
    override val mainUrl = "https://vidara.to"

    // Le matching d'extracteur est un startsWith sur l'URL sans protocole :
    // il faut donc lister chaque host exact.
    override val aliasUrls = listOf(
        "https://viewdara.com",
        "https://vidara.so",
        // 2026-10-03 : nouveaux domaines Vidara (relevés en amont, Streamflix Reborn).
        "https://ano.cx", "https://bufsao.com",
        // 2026-08-07 : nouveau front relevé sur CoflixWiki — noter le DOUBLE « a ».
        //   Vérifié en direct : `vidaraa.cc/e/<filecode>` répond au MÊME `/api/stream`
        //   (payload filecode+device, réponse streaming_url/subtitles/title). C'est donc
        //   bien Vidara, mais aucun de nos alias ne matchait « vidaraa » → ces serveurs
        //   tombaient en « aucun extracteur ». 4 occurrences sur 20 épisodes sondés.
        "https://vidaraa.cc",
        // 2026-08-07 (audit DNS) : `vidara.cc` et `vidara.net` retirés — plus d'enregistrement A.
        //   `vidaraa.com` répond, on le prend au passage : même famille, même /api/stream.
        "https://vidaraa.com",
        // 2026-09-15 (logcat Oppo, film Movix) : « HOTE NON COUVERT: vidvara.site ».
        //   Noter le « v » à la place du deuxième « a » — encore une variante d'écriture.
        //   Confirmé : la page se revendique elle-même comme Vidara, son jwplayer porte
        //   `abouttext:"Vidara"` et `aboutlink:"https://vidara.so/"`, même schéma `/e/<id>`.
        "https://vidvara.site",
        // 2026-09-20 (user : « je viens de voir qu'il y avait un vidar.cc ») : AJOUT PREVENTIF,
        //   aucun provider ne sert ce front aujourd'hui. Note l'orthographe : `vidar`, SANS le
        //   deuxieme « a » — a ne pas confondre avec `vidara.cc`, qui est mort (retire le
        //   2026-08-07, toujours aucun enregistrement A verifie ce jour).
        //   Verifie en direct : POST https://vidar.cc/api/stream avec un filecode bidon rend
        //   {"error":"Video not found"} — l'API comprend la requete et cherche le fichier, donc
        //   c'est bien le moteur Vidara, pas une page qui se contente de repondre 404.
        //   ⚠ Bitdefender classe ce domaine en hameconnage : c'est un FAUX POSITIF (la page est
        //   le vrai site Vidara, meme argumentaire que viewdara.com). Ne pas s'y fier pour
        //   conclure qu'un front est illegitime.
        //   Motif de l'ajout : cette famille change de front en permanence (vidara.cc mort,
        //   puis vidaraa.cc, puis vidvara.site) et les deux precedents ont ete ajoutes APRES
        //   coup, une fois des lectures tombees en « HOTE NON COUVERT ». Celui-ci est pose
        //   d'avance : un domaine qui n'apparait jamais n'est jamais teste, cout nul.
        "https://vidar.cc",
        // 2026-10-22 : front vers lequel vidara.to/e/<id> redirige (vérifié dans Chrome).
        "https://gaqghe.com",
    )

    /**
     * 2026-08-07 (user : « VIDARA HS ») — PAS DE CACHE.
     *   Journal : `Cache HIT for vidara.to/e/gsqQk68VixKRG` juste avant l'échec, alors que
     *   le même serveur avait parfaitement joué 9 minutes plus tôt. L'URL rendue par
     *   `/api/stream` est signée :
     *     …/master.m3u8?token=<hash>-<expiration>-<IP du client>-<signature>
     *   Elle porte donc une date de péremption ET l'adresse IP. Resservie depuis le cache,
     *   elle est déjà refusée par le CDN — l'extracteur n'y est pour rien.
     *   Vidara héritait du défaut de 10 minutes ; on tombait pile dans la fenêtre.
     *   Même piège que DoodLa et LuluVdo, qui mettent 0L pour cette raison exacte.
     */
    override val cacheTtlMs: Long = 0L

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    override suspend fun extract(link: String): Video = withContext(Dispatchers.IO) {
        // Origine réelle du lien (on reste sur le domaine fourni : les fronts
        // sont interchangeables mais le filecode est servi par celui-là).
        val origin = Regex("^(https?://[^/]+)").find(link)?.groupValues?.get(1)
            ?: mainUrl

        // /e/<filecode>  (le player fait exactement pathname.split('/').pop())
        val filecode = link.substringBefore('?')
            .trimEnd('/')
            .substringAfterLast('/')
        if (filecode.isBlank()) {
            throw Exception("Vidara: filecode introuvable dans $link")
        }

        fun appelStream(front: String): Pair<Int, String> {
            val payload = JSONObject()
                .put("filecode", filecode)
                .put("device", "android")
                .toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$front/api/stream")
                .post(payload)
                .header("User-Agent", userAgent)
                .header("Referer", "$front/")
                .header("Origin", front)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "fr-FR,fr;q=0.9")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            return sharedClient.newCall(request).execute().use { resp ->
                resp.code to (if (resp.isSuccessful) resp.body?.string().orEmpty() else "")
            }
        }

        var (code, body) = appelStream(origin)
        // 2026-10-22 (user : « on est censé avoir ce serveur là aussi mais il fonctionne pas »,
        //   Lecteur Movix 4 = vidara.to) — vidara.to/e/<id> REDIRIGE désormais vers un autre
        //   front (vérifié dans Chrome : gaqghe.com/e/<id>, dont le lecteur appelle
        //   gaqghe.com/api/stream). L'API de vidara.to répond alors 404 ou 504. On suit la
        //   redirection de la page /e/ et on rappelle /api/stream sur le front d'arrivée.
        if (code !in 200..299) {
            val front = runCatching {
                sharedClient.newCall(
                    Request.Builder().url(link).header("User-Agent", userAgent).build()
                ).execute().use { r ->
                    Regex("^(https?://[^/]+)").find(r.request.url.toString())?.groupValues?.get(1)
                }
            }.getOrNull()
            if (front != null && !front.equals(origin, ignoreCase = true)) {
                android.util.Log.d("VidaraExtractor", "/api/stream HTTP $code sur $origin → front redirigé $front")
                val r2 = appelStream(front)
                code = r2.first; body = r2.second
            }
        }
        if (code !in 200..299) throw Exception("Vidara: /api/stream HTTP $code")
        if (body.isBlank()) throw Exception("Vidara: réponse /api/stream vide")

        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: throw Exception("Vidara: réponse /api/stream non-JSON")

        // Erreur explicite renvoyée par l'API (fichier supprimé, en cours…).
        // ⚠️ "source unavailable" est reconnu par classifyError() (Extractor.kt)
        //   → catégorie "dead-content" : c'est la SOURCE qui est morte, pas
        //   l'extracteur. Sans ça, quelques fichiers HS suffisent à faire
        //   blacklister Vidara en entier ("marked broken, N failures").
        json.optString("error").takeIf { it.isNotBlank() }?.let {
            throw Exception("Vidara: source unavailable — $it")
        }

        val streamUrl = json.optString("streaming_url")
            .takeIf { it.isNotBlank() && it.startsWith("http") }
            ?: throw Exception("Vidara: source unavailable — streaming_url absent (filecode=$filecode)")

        // 2026-08-16 — Fichier JAMAIS ENCODÉ (page d'embed = « Waiting in queue »).
        //   PIÈGE : /api/stream répond 200 en ~150 ms avec EXACTEMENT la même
        //   structure qu'un fichier sain — pas de champ `error`, rien qui signale
        //   quoi que ce soit. Le seul écart est la FORME de streaming_url :
        //     • encodé   → https://<cdn>/hls/<id>/master.m3u8    (20 cas sur 21 relevés)
        //     • en file  → https://streamix.so/uploads/<id>.mp4  (le téléversement brut)
        //   Logique : sans encodage il n'existe aucune rendition HLS, donc l'API
        //   retombe sur le fichier source, qui lui n'est pas diffusé. Comme on
        //   déclarait la sortie en APPLICATION_M3U8 quoi qu'il arrive, ExoPlayer
        //   partait bufferiser un HLS inexistant et n'abandonnait qu'au plafond.
        //   ⚠ On teste le CHEMIN, PAS l'hôte : les CDN sains tournent (8 domaines
        //   différents sur 20 fichiers) — une règle par domaine casserait seule.
        //   « source unavailable » est volontaire : classifyError() le range en
        //   « dead-content » → « santé extracteur NON pénalisée » (Extractor.kt).
        //   ⚠ Précision (user, 2026-08-16) : il n'y a PLUS de blacklist d'URL ni de
        //   retrait de serveur — un serveur rouge l'est seulement pour le titre en
        //   cours et repasse blanc au suivant. Ce qui reste en jeu ici, c'est
        //   uniquement le compteur de santé de l'EXTRACTEUR, pas la liste affichée.
        if (Regex("""/uploads/[^/]+\.mp4($|\?)""").containsMatchIn(streamUrl)) {
            throw Exception(
                "Vidara: source unavailable — fichier non encodé (en file d'attente), filecode=$filecode"
            )
        }

        Log.d("VidaraExtractor", "resolved $filecode → m3u8 (${streamUrl.length} chars)")

        // 2026-10-22 (user : « pourquoi les sous-titres ne sont pas directs avec Vidara ») : Vidara
        //   extrait à l'envoi les pistes de sous-titres du fichier et les sert en .vtt dans
        //   `subtitles` [{language, file_path, status}] — on les ignorait. Ordre : français complet,
        //   français « forcé », puis le reste. Aucun activé ici : c'est la LANGUE du fichier qui
        //   décide (PlayerViewModel.reglerSousTitresSelonLangue). `default_sub_lang` n'aide pas :
        //   c'est un réglage du COMPTE (« French » sur tous les fichiers, VF compris).
        //   Le titre sert de nom de fichier : « … VOSTFR » → sous-titres français activés.
        val sousTitres = json.optJSONArray("subtitles")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                if (o.optInt("status", 1) != 1) return@mapNotNull null
                val url = o.optString("file_path").takeIf { it.startsWith("http") } ?: return@mapNotNull null
                val langue = o.optString("language").ifBlank { "Inconnu" }
                val label = when {
                    langue.startsWith("French", true) && langue.contains("FORCED", true) -> "Français (forcé)"
                    langue.startsWith("French", true) -> "Français"
                    langue.startsWith("English", true) -> langue.replace("English", "Anglais")
                    else -> langue
                }
                Video.Subtitle(label = label, file = url)
            }
        }.orEmpty().sortedBy {
            when {
                it.label == "Français" -> 0
                it.label.startsWith("Français") -> 1
                else -> 2
            }
        }
        if (sousTitres.isNotEmpty()) Log.d("VidaraExtractor", "$filecode : ${sousTitres.size} sous-titre(s) — ${sousTitres.joinToString { it.label }}")

        Video(
            source = streamUrl,
            subtitles = sousTitres,
            fileName = json.optString("title").takeIf { it.isNotBlank() },
            type = MimeTypes.APPLICATION_M3U8,
            headers = mapOf(
                "Referer" to "$origin/",
                "Origin" to origin,
                "User-Agent" to userAgent,
            ),
        )
    }
}
