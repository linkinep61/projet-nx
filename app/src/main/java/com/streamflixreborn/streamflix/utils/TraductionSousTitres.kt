package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.streamflixreborn.streamflix.StreamFlixApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 2026-10-22 (user : « garder les serveurs anglais sous-titrés et les convertir en français ») :
 * traduction AUTOMATIQUE d'un sous-titre anglais en français, SUR L'APPAREIL (Google ML Kit).
 *   · Le modèle anglais → français (~30 Mo) se télécharge à la première utilisation, une fois.
 *   · Le sous-titre (SRT ou VTT) est lu, chaque réplique traduite, puis réécrit en VTT dans le
 *     cache (`st_traduits/<empreinte>.vtt`) : le 2e lancement de l'épisode est instantané.
 *   · Une traduction déjà en cours pour la même URL est réutilisée (pré-extraction + lecture).
 */
object TraductionSousTitres {

    private const val TAG = "TraductionST"
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val enCours = ConcurrentHashMap<String, Deferred<File?>>()

    // 2026-10-22 (user : « pas forcément des sous-titres en anglais, notre logiciel peut traduire
    //   d'autres langues ») : un traducteur PAR langue source (modèle ~30 Mo chacun, téléchargé
    //   à la première utilisation de cette langue).
    private val traducteurs = ConcurrentHashMap<String, com.google.mlkit.nl.translate.Translator>()
    private fun traducteur(langue: String) = traducteurs.getOrPut(langue) {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.fromLanguageTag(langue) ?: TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.FRENCH)
                .build()
        )
    }

    /** Code ISO 639-1 d'un libellé de piste (« English », « eng », « Español », « Coréen »…), ou null. */
    fun codeDepuisLibelle(libelle: String): String? {
        val l = libelle.lowercase().replace(Regex("""\(.*?\)|\[.*?]"""), "").replace("forced", "").replace("forcé", "").trim()
        if (l.isBlank()) return null
        val mot = l.split(Regex("""[\s_.,-]+""")).firstOrNull { it.isNotBlank() } ?: return null
        for (code in java.util.Locale.getISOLanguages()) {
            val loc = java.util.Locale(code)
            val iso3 = runCatching { loc.isO3Language }.getOrDefault("")
            if (mot == code || mot == iso3 ||
                loc.getDisplayLanguage(java.util.Locale.ENGLISH).lowercase() == mot ||
                loc.getDisplayLanguage(java.util.Locale.FRENCH).lowercase() == mot ||
                loc.getDisplayLanguage(loc).lowercase() == mot
            ) return code
        }
        // Codes bibliographiques ISO 639-2/B que Locale ne connaît pas (« fre », « ger », « chi »…).
        return mapOf("fre" to "fr", "ger" to "de", "chi" to "zh", "dut" to "nl", "gre" to "el", "rum" to "ro",
            "cze" to "cs", "slo" to "sk", "ice" to "is", "per" to "fa", "alb" to "sq", "arm" to "hy",
            "mac" to "mk", "may" to "ms", "bur" to "my", "baq" to "eu", "geo" to "ka", "wel" to "cy")[mot]
    }

    /** Le traducteur sait-il traduire cette langue vers le français ? */
    fun traduisible(code: String?): Boolean =
        code != null && code != "fr" && TranslateLanguage.fromLanguageTag(code) != null

    private suspend fun <T> Task<T>.attendre(): T = suspendCancellableCoroutine { c ->
        addOnSuccessListener { c.resume(it) }
        addOnFailureListener { c.resumeWithException(it) }
    }

    private fun empreinte(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private val dossiersNettoyes = java.util.Collections.synchronizedSet(HashSet<String>())

    /** 2026-10-22 : supprime les sous-titres (traduits / OpenSubtitles) de plus de 30 jours. */
    fun menage(dossier: File) {
        if (!dossiersNettoyes.add(dossier.path)) return
        val limite = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        var n = 0
        dossier.listFiles()?.forEach { if (it.lastModified() < limite && it.delete()) n++ }
        if (n > 0) Log.d(TAG, "ménage ${dossier.name} : $n fichier(s) de plus de 30 jours supprimé(s)")
    }

    private fun fichierPour(url: String, langue: String = "en"): File {
        val dossier = File(StreamFlixApp.instance.applicationContext.cacheDir, "st_traduits").apply { mkdirs() }
        menage(dossier)
        // la query (jeton, expiration) change à chaque lecture : la clé de cache ne la garde pas.
        return File(dossier, empreinte(url.substringBefore('?')) + (if (langue == "en") "" else "-$langue") + ".vtt")
    }

    /** Fichier déjà traduit (sans rien lancer), ou null. */
    fun dejaTraduit(url: String, langue: String = "en"): File? = fichierPour(url, langue).takeIf { it.exists() && it.length() > 20 }

    /** Lance (ou rejoint) la traduction de [url]. Le résultat est un .vtt local, ou null si échec. */
    fun lancer(url: String, entetes: Map<String, String>?, langue: String = "en"): Deferred<File?> =
        // 2026-10-23 : jamais en mode léger (ML Kit en mémoire).
        if (UserPreferences.modeLeger) kotlinx.coroutines.CompletableDeferred(null)
        else enCours.getOrPut("$langue|$url") {
            portee.async {
                try { traduire(url, entetes, langue) } catch (e: Exception) {
                    Log.w(TAG, "traduction KO : ${e.message}"); null
                } finally { enCours.remove("$langue|$url") }
            }
        }

    /**
     * 2026-10-22 (user : « un paramètre dans les sous-titres qui dit que la traduction est en
     * cours, ou quand c'est terminé ») : état de la traduction de la vidéo EN COURS, lu par le
     * menu Sous-titres du lecteur. null = aucune traduction pour cette vidéo.
     */
    data class Etat(val langue: String, val total: Int, val faites: Int, val terminee: Boolean, val echec: Boolean = false)
    @Volatile var etatCourant: Etat? = null

    /**
     * 2026-10-22 (user : « garder la possibilité de l'activer s'il y en a besoin ») — sur un VOSTFR
     * de site français (français gravé dans l'image), la traduction n'est plus lancée d'office ;
     * le lecteur la propose dans le menu Sous-titres (« Traduire en français ») et l'appelle ici.
     */
    @Volatile var demandeTraduction: (() -> Unit)? = null
    @Volatile private var urlCourante: String? = null

    /** Le lecteur annonce la piste qu'il fait traduire (ou null : rien à traduire pour cette vidéo). */
    fun suivre(url: String?, langue: String = "en") {
        urlCourante = url
        etatCourant = when {
            url == null -> null
            dejaTraduit(url, langue) != null -> Etat(langue, 1, 1, terminee = true)
            else -> Etat(langue, 0, 0, terminee = false)
        }
    }

    private fun majEtat(url: String, e: Etat) { if (url == urlCourante) etatCourant = e }

    private data class Replique(val temps: String, val texte: String)

    private suspend fun traduire(url: String, entetes: Map<String, String>?, langue: String): File? {
        dejaTraduit(url, langue)?.let { return it }
        val traducteur = traducteur(langue)
        val debut = System.currentTimeMillis()
        // 2026-10-22 : la piste peut être un FICHIER LOCAL (sous-titre OpenSubtitles téléchargé,
        //   cf. PlayerViewModel « autre langue à traduire »), et au format ASS (animes).
        val brutLu = if (url.startsWith("file:")) runCatching {
            File(java.net.URLDecoder.decode(url.removePrefix("file://"), "UTF-8")).readText()
        }.getOrNull() else NetworkClient.default.newCall(
            Request.Builder().url(url).apply {
                entetes?.forEach { (k, v) -> if (k.equals("Referer", true) || k.equals("Origin", true) || k.equals("User-Agent", true)) header(k, v) }
            }.build()
        ).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
        val brut = brutLu?.let { ConversionAss.versVtt(it) ?: it } ?: run {
            Log.w(TAG, "sous-titre anglais illisible : $url"); return null
        }
        val repliques = lire(brut)
        if (repliques.isEmpty()) { Log.w(TAG, "aucune réplique lue (${brut.length} car.)"); majEtat(url, Etat(langue, 0, 0, true, echec = true)); return null }
        majEtat(url, Etat(langue, repliques.size, 0, false))

        Log.d(TAG, "modèle $langue→français : vérification / téléchargement…")
        traducteur.downloadModelIfNeeded(DownloadConditions.Builder().build()).attendre()
        Log.d(TAG, "modèle prêt (${System.currentTimeMillis() - debut} ms) — ${repliques.size} répliques à traduire")

        // 2026-10-22 : mesuré sur l'Oppo ~28 répliques/s. Essai « 12 répliques par appel » :
        //   ÉCHEC — ML Kit fusionne les lignes, les 58 paquets ont dû être retraduits un par un
        //   (37 s au lieu de 25). On reste donc réplique par réplique, 8 en parallèle ; la
        //   traduction s'affiche de toute façon en cours de lecture dès qu'elle est prête.
        val traduites = ArrayList<String>(repliques.size)
        repliques.chunked(8).forEach { paquet ->
            traduites += paquet.map { r ->
                portee.async {
                    if (r.texte.isBlank()) "" else runCatching { traducteur.translate(r.texte).attendre() }.getOrDefault(r.texte)
                }
            }.awaitAll()
            majEtat(url, Etat(langue, repliques.size, traduites.size, false))
        }

        val sortie = buildString {
            append("WEBVTT\n\n")
            repliques.forEachIndexed { i, r ->
                append(r.temps).append('\n').append(traduites[i]).append("\n\n")
            }
        }
        val f = fichierPour(url, langue)
        f.writeText(sortie)
        majEtat(url, Etat(langue, repliques.size, repliques.size, true))
        Log.i(TAG, "traduction prête : ${repliques.size} répliques en ${System.currentTimeMillis() - debut} ms")
        return f
    }

    /** SRT ou VTT → répliques (temps au format VTT, texte nettoyé des balises). */
    private fun lire(brut: String): List<Replique> {
        val texte = brut.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        return texte.split(Regex("\n\\s*\n")).mapNotNull { bloc ->
            val lignes = bloc.trim().lines()
            val i = lignes.indexOfFirst { it.contains("-->") }
            if (i < 0) return@mapNotNull null
            // SRT « 00:00:01,000 » → VTT « 00:00:01.000 » ; on garde les réglages de position VTT.
            val temps = lignes[i].trim().replace(Regex("""(\d{2}:\d{2}:\d{2}),(\d{3})"""), "$1.$2")
            val corps = lignes.drop(i + 1).joinToString(" ") { it.trim() }
                .replace(Regex("<[^>]+>"), "")
                .replace(Regex("""\{\\[^}]*\}"""), "")
                .replace(Regex("\\s+"), " ").trim()
            Replique(temps, corps)
        }
    }
}
