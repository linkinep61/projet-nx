package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * 2026-10-22 (user : « on a un sérieux décalage avec les sous-titres » — recherche faite :
 * méthode ffsubsync) — RECALAGE AUTOMATIQUE d'un sous-titre (OpenSubtitles, téléchargé…)
 * sur un sous-titre DÉJÀ CALÉ sur la vidéo : celui que fournit le serveur, même dans une
 * autre langue (les répliques tombent aux mêmes moments).
 *
 * Principe (ffsubsync « référence = autre sous-titre », aucun audio à décoder) :
 *  1. on découpe le temps en tranches de 200 ms, « parle / ne parle pas » pour chacun ;
 *  2. on cherche le décalage (±90 s) et la cadence (25 / 24 / 23,976 i/s) qui font le mieux
 *     coïncider les deux ;
 *  3. si le gain est net, on RÉÉCRIT les heures dans une copie du fichier.
 * Sans référence, ou gain trop faible → fichier rendu tel quel (aucun risque).
 */
object RecalageAuto {
    private const val TAG = "RecalageAuto"
    private const val PAS = 200L
    private const val PLAGE_MS = 120_000L

    class Replique(val debut: Long, val fin: Long)

    private val HEURE = Regex("""(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[.,](\d{1,3})""")

    private fun ms(m: MatchResult): Long {
        val h = m.groupValues[1].toLongOrNull() ?: 0L
        val mi = m.groupValues[2].toLong()
        val s = m.groupValues[3].toLong()
        val f = m.groupValues[4].padEnd(3, '0').take(3).toLong()
        return ((h * 60 + mi) * 60 + s) * 1000 + f
    }

    fun lireRepliques(texte: String): List<Replique> {
        val res = ArrayList<Replique>()
        for (l in texte.lineSequence()) {
            if (!l.contains("-->")) continue
            val m = HEURE.findAll(l).toList()
            if (m.size < 2) continue
            val d = ms(m[0]); val f = ms(m[1])
            if (f > d) res.add(Replique(d, f))
        }
        return res
    }

    /**
     * Meilleur réglage (decalageMs, rapport) au sens de [SyncSousTitres] : la réplique du
     * fichier à l'heure s s'affiche à la vidéo (s + D) / r. null = rien de convaincant.
     */
    fun aligner(ref: List<Replique>, cible: List<Replique>): Pair<Long, Double>? {
        if (ref.size < 30 || cible.size < 30) return null
        val fin = maxOf(ref.last().fin, cible.maxOf { it.fin }) + PLAGE_MS
        val n = (fin / PAS).toInt() + 2
        val refUns = BooleanArray(n)
        for (r in ref) for (i in (r.debut / PAS).toInt()..(r.fin / PAS).toInt().coerceAtMost(n - 1)) refUns[i] = true
        val debut = (ref.first().debut / PAS).toInt()
        val finRef = (ref.last().fin / PAS).toInt().coerceAtMost(n - 1)
        val uns = (debut..finRef).filter { refUns[it] }.toIntArray()
        val zeros = (debut..finRef).filter { !refUns[it] }.toIntArray()
        if (uns.size < 100) return null
        return alignerIdx(uns, zeros, cible, rapportLibre = true, gainMin = 0.04)
    }

    /**
     * Voix détectées → réglage. [uns] = tranches de [PAS] ms où ça parle, [zeros] = tranches
     * écoutées où personne ne parle (heure vidéo). [rapportLibre] = false (écoute encore
     * courte) : la dérive n'est retenue que si elle l'emporte nettement.
     */
    fun alignerSurVoix(uns: IntArray, zeros: IntArray, cible: List<Replique>, rapportLibre: Boolean): Pair<Long, Double>? {
        if (uns.size < 40 || zeros.size < 40 || cible.size < 30) return null
        // Score = (part du temps « avec texte » quand ça parle) − (idem quand personne ne parle).
        //   Moyennes séparées : un sous-titre très dense ou un détecteur bavard ne fausse plus.
        //   Plage ±3 min (Fight Club DVDRip : ~1 min 40 d'écart ; la dérive est couverte par les
        //   cadences).
        val plage = (180_000L / PAS).toInt()
        val dernier = maxOf(uns.max(), zeros.max()).toLong()
        val n = ((maxOf(dernier * PAS, cible.maxOf { it.fin }) + 300_000L) / PAS).toInt() + 2
        var meilleur = -9.0; var mK = 0; var mR = 1.0
        var meilleur1 = -9.0; var mK1 = 0
        var base = 0.0
        for ((r, _) in SyncSousTitres.DERIVES) {
            val v = BooleanArray(n)
            for (c in cible) {
                val a = (c.debut / r / PAS).toInt(); val b = (c.fin / r / PAS).toInt()
                for (i in a..b) if (i in 0 until n) v[i] = true
            }
            for (sh in -plage..plage) {
                var su = 0; var sz = 0
                for (i in uns) { val j = i - sh; if (j in 0 until n && v[j]) su++ }
                for (i in zeros) { val j = i - sh; if (j in 0 until n && v[j]) sz++ }
                val score = su.toDouble() / uns.size - sz.toDouble() / zeros.size
                if (r == 1.0 && sh == 0) base = score
                if (r == 1.0 && score > meilleur1) { meilleur1 = score; mK1 = sh }
                if (score > meilleur) { meilleur = score; mK = sh; mR = r }
            }
        }
        if (!rapportLibre && mR != 1.0 && meilleur < meilleur1 + 0.05) { meilleur = meilleur1; mK = mK1; mR = 1.0 }
        Log.d(TAG, "voix : décalage=${mK * PAS} ms, rapport=$mR, score=${"%.3f".format(meilleur)} (sans réglage ${"%.3f".format(base)}, ${uns.size} voix / ${zeros.size} silences)")
        if (kotlin.math.abs(mK) >= plage - 1) return null
        // Il faut un vrai pic : nettement mieux que sans réglage, et un écart voix/silence net.
        if (meilleur < 0.15) return null
        if (meilleur - base < 0.08) return if (meilleur - base < 0.02) Pair(0L, 1.0) else null
        return Pair((mK * PAS * mR).toLong(), mR)
    }

    /**
     * Score d'un réglage = Σ(tranches où ça parle ET texte affiché) − Σ(ça parle, pas de texte)
     *                    − Σ(silence ET texte affiché) + Σ(silence, pas de texte).
     * Les silences comptent aussi : sans eux, un sous-titre très dense « colle » partout.
     */
    private fun alignerIdx(uns: IntArray, zeros: IntArray, cible: List<Replique>, rapportLibre: Boolean, gainMin: Double): Pair<Long, Double>? {
        val dernier = maxOf(uns.maxOrNull() ?: 0, zeros.maxOrNull() ?: 0).toLong()
        val fin = maxOf(dernier * PAS, cible.maxOf { it.fin }) + PLAGE_MS * 2
        val n = (fin / PAS).toInt() + 2
        val k = (PLAGE_MS / PAS).toInt()
        val total = uns.size + zeros.size
        var meilleur = Int.MIN_VALUE; var mK = 0; var mR = 1.0
        var meilleur1 = Int.MIN_VALUE; var mK1 = 0
        var base = 0
        for ((r, _) in SyncSousTitres.DERIVES) {
            // cible ramenée en « temps vidéo » : s / r, codée +1 (texte) / −1 (pas de texte)
            val v = IntArray(n) { -1 }
            for (c in cible) {
                val a = (c.debut / r / PAS).toInt(); val b = (c.fin / r / PAS).toInt()
                for (i in a..b) if (i in 0 until n) v[i] = 1
            }
            for (sh in -k..k) {
                var score = 0
                for (i in uns) { val j = i - sh; if (j in 0 until n) score += v[j] }
                for (i in zeros) { val j = i - sh; if (j in 0 until n) score -= v[j] }
                if (r == 1.0 && sh == 0) base = score
                if (r == 1.0 && score > meilleur1) { meilleur1 = score; mK1 = sh }
                if (score > meilleur) { meilleur = score; mK = sh; mR = r }
            }
        }
        // Écoute courte : sans dérive sauf si la dérive gagne de plus de 10 %.
        if (!rapportLibre && mR != 1.0 && meilleur < meilleur1 + total * 0.10) { meilleur = meilleur1; mK = mK1; mR = 1.0 }
        val gain = meilleur - base
        Log.d(TAG, "alignement : décalage=${mK * PAS} ms, rapport=$mR, score=$meilleur (sans réglage $base, ${uns.size} voix / ${zeros.size} silences)")
        // Résultat collé au bord de la plage = pas un vrai optimum.
        if (kotlin.math.abs(mK) >= k - 1) return null
        if (gain < total * gainMin) return if (mK == 0 && mR == 1.0) Pair(0L, 1.0) else null
        return Pair((mK * PAS * mR).toLong(), mR)
    }

    private fun format(t: Long, virgule: Boolean): String {
        val x = t.coerceAtLeast(0)
        val h = x / 3_600_000; val m = x / 60_000 % 60; val s = x / 1000 % 60; val f = x % 1000
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d%s%03d", h, m, s, if (virgule) "," else ".", f)
    }

    /** Réécrit les heures : nouvelle = (ancienne + D) / r. */
    fun reecrire(texte: String, decalage: Long, rapport: Double): String =
        texte.lineSequence().joinToString("\n") { l ->
            if (!l.contains("-->")) l
            else HEURE.replace(l) { m ->
                format(((ms(m) + decalage) / rapport).toLong(), m.value.contains(','))
            }
        }

    /** Répliques d'un sous-titre (fichier local ou adresse), null si illisible. */
    fun repliquesDe(adresse: String, entetes: Map<String, String>?): List<Replique>? =
        lireTexte(adresse, entetes)?.let { lireRepliques(it) }

    /** Accord voix / sous-titre SANS réglage : ≥ 0,5 = ce sous-titre est déjà calé. */
    fun scoreVoix(uns: IntArray, zeros: IntArray, cible: List<Replique>): Double {
        if (uns.isEmpty() || zeros.isEmpty()) return 0.0
        fun avecTexte(i: Int): Boolean { val t = i * PAS; return cible.any { t >= it.debut && t <= it.fin } }
        val su = uns.count { avecTexte(it) }; val sz = zeros.count { avecTexte(it) }
        return su.toDouble() / uns.size - sz.toDouble() / zeros.size
    }

    private fun lireTexte(adresse: String, entetes: Map<String, String>?): String? = try {
        if (adresse.startsWith("http")) {
            val req = Request.Builder().url(adresse.substringBefore('#')).apply {
                entetes?.get("Referer")?.let { header("Referer", it) }
                entetes?.get("User-Agent")?.let { header("User-Agent", it) }
            }.build()
            NetworkClient.default.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null }
        } else {
            val f = File(android.net.Uri.parse(adresse).path ?: adresse.removePrefix("file://"))
            val b = f.readBytes()
            val u = String(b, Charsets.UTF_8)
            if (u.count { it == '�' } > 3) String(b, Charsets.ISO_8859_1) else u
        }
    } catch (e: Exception) { Log.w(TAG, "lecture $adresse : ${e.message}"); null }

    /** Sous-titres du SERVEUR (calés sur sa vidéo), hors ceux qu'on a ajoutés nous-mêmes. */
    fun references(video: Video?, sauf: String? = null): List<Video.Subtitle> =
        video?.subtitles.orEmpty().filter {
            it.file != sauf && !it.label.contains("OpenSubtitles", true) &&
                (it.file.startsWith("http") || it.file.startsWith("file:"))
        }

    /**
     * Bouton « Recaler automatiquement » : calcule le réglage du sous-titre affiché ([source] :
     * fichier local ou adresse) sur la référence du serveur et l'applique via [SyncSousTitres].
     * Renvoie le message à afficher.
     */
    suspend fun reglerSurReference(source: String, video: Video?): String = withContext(Dispatchers.Default) {
        val refs = references(video, sauf = source)
        if (refs.isEmpty()) return@withContext "Ce serveur ne fournit aucun sous-titre de référence : recalage automatique impossible ici"
        val texte = withContext(Dispatchers.IO) { lireTexte(source, video?.headers) }
            ?: return@withContext "Sous-titre illisible"
        val cible = lireRepliques(texte)
        for (st in refs.take(3)) {
            val t = withContext(Dispatchers.IO) { lireTexte(st.file, video?.headers) } ?: continue
            val ref = lireRepliques(t)
            if (ref.size < 30) continue
            val reglage = aligner(ref, cible)
            return@withContext if (reglage == null) {
                "Aucun réglage sûr trouvé avec le sous-titre du serveur"
            } else if (reglage.first == 0L && reglage.second == 1.0) {
                SyncSousTitres.reinitialiser()
                "Sous-titres déjà calés sur la vidéo"
            } else {
                SyncSousTitres.decalageMs = reglage.first
                SyncSousTitres.rapport = reglage.second
                "Sous-titres recalés (${SyncSousTitres.libelleDecalage()}" +
                    (if (reglage.second != 1.0) ", dérive ${SyncSousTitres.libelleDerive()})" else ")")
            }
        }
        "Référence du serveur illisible : recalage automatique impossible"
    }

    /**
     * Recale [fichier] sur la première référence utilisable. Renvoie une COPIE recalée
     * (cache `st_recales`), ou le fichier d'origine si rien n'est à faire.
     */
    suspend fun recaler(fichier: File, video: Video?): File = withContext(Dispatchers.Default) {
        // 2026-10-22 (user : « mode léger pour désactiver ça, pour ne pas avoir de crash ») :
        //   calcul évité sur les appareils faibles / en mode léger.
        if (UserPreferences.modeLeger) return@withContext fichier
        try {
            val refs = references(video, sauf = android.net.Uri.fromFile(fichier).toString())
            if (refs.isEmpty()) return@withContext fichier
            val texte = withContext(Dispatchers.IO) { lireTexte(android.net.Uri.fromFile(fichier).toString(), null) }
                ?: return@withContext fichier
            val cible = lireRepliques(texte)
            for (st in refs.take(3)) {
                val t = withContext(Dispatchers.IO) { lireTexte(st.file, video?.headers) } ?: continue
                val ref = lireRepliques(t)
                if (ref.size < 30) continue
                val (d, r) = aligner(ref, cible)?.takeIf { it.first != 0L || it.second != 1.0 } ?: run {
                    Log.d(TAG, "« ${fichier.name} » déjà calé sur « ${st.label} » (ou rien de sûr)")
                    return@withContext fichier
                }
                val dossier = File(fichier.parentFile?.parentFile ?: fichier.parentFile, "st_recales").apply { mkdirs() }
                val sortie = File(dossier, "${fichier.nameWithoutExtension}-${d}-${(r * 1000).toInt()}.${fichier.extension.ifBlank { "srt" }}")
                withContext(Dispatchers.IO) { sortie.writeText(reecrire(texte, d, r)) }
                Log.i(TAG, "« ${fichier.name} » recalé sur « ${st.label} » : décalage ${d} ms, rapport $r")
                return@withContext sortie
            }
            fichier
        } catch (e: Throwable) {
            Log.w(TAG, "recalage : ${e.message}")
            fichier
        }
    }
}
