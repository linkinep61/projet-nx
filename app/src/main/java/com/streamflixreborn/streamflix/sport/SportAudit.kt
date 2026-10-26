package com.streamflixreborn.streamflix.sport

import android.util.Log
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.LiveTvHubProvider
import com.streamflixreborn.streamflix.utils.SportHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Débogage uniquement : audit des tuiles du dossier Sport pour les sources AUTRES qu'OLA,
 * Vegeta et Vavoo (RénéVéo, Mix FR, damitv). Lancé quand le fichier drapeau
 * `<externalFilesDir>/sport_audit` existe (créé par adb), à l'ouverture d'un sous-dossier.
 * Pour chaque source : serveurs → getVideo (même chemin que le lecteur) → manifeste →
 * premier segment, dont les premiers octets doivent être de la vraie vidéo (TS / fMP4).
 * Résultat : journal « SportAudit » + `<externalFilesDir>/sport_audit.txt`.
 */
object SportAudit {

    private const val TAG = "SportAudit"
    private val IGNOREES = setOf("ola", "vegeta", "vavoo")
    private const val MAX_WEB = 8

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true).build()
    }

    fun drapeau(): File? = StreamFlixApp.instance.getExternalFilesDir(null)?.let { File(it, "sport_audit") }

    suspend fun lancer(filtre: String = "") = withContext(Dispatchers.IO) {
        val motif = filtre.takeIf { it.isNotBlank() }?.let { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() }
        val dossier = StreamFlixApp.instance.getExternalFilesDir(null) ?: return@withContext
        val rapport = StringBuilder()
        fun note(l: String) { Log.i(TAG, l); rapport.appendLine(l) }
        val debut = System.currentTimeMillis()
        val tuiles = SportHub.toutesLesTuiles()
        val cibles = tuiles.entries.filter { (_, e) -> e.sources.any { it.proprio !in IGNOREES } }
            .filter { (_, e) -> motif == null || motif.containsMatchIn(e.nom) }
            .sortedWith(compareBy({ it.value.rubrique }, { it.value.nom }))
        note("début : ${tuiles.size} tuiles, ${cibles.size} avec RénéVéo / Mix FR / damitv")
        var web = 0
        var ok = 0
        var ko = 0
        val vus = HashSet<String>()
        for ((cle, e) in cibles) for (s in e.sources.filter { it.proprio !in IGNOREES }) {
            val estWeb = SportHub.estSourceWeb(s.proprio)
            // 2026-10-28 (user : « il doit y avoir plein d'autres trucs comme ça à masquer ») :
            //   ChaineDirect (WiTV) est testée en entier, sans la limite des sources web.
            if (estWeb && s.proprio != com.streamflixreborn.streamflix.sport.providers.ChaineDirect.ID) {
                if (!e.live || web >= MAX_WEB) continue; web++
            }
            if (!vus.add("${s.proprio}|${s.id}")) continue // même chaîne dans deux rubriques
            val t0 = System.currentTimeMillis()
            val verdict = runCatching { tester(SportHub.idTuile(cle), e.nom, s, estWeb) }
                .getOrElse { "KO exception ${it.javaClass.simpleName}: ${it.message?.take(120)}" }
            if (verdict.startsWith("OK")) ok++ else ko++
            note("${if (verdict.startsWith("OK")) "✔" else "✘"} [${e.rubrique}] ${e.nom} · ${s.proprio} → $verdict (${System.currentTimeMillis() - t0} ms)")
        }
        note("fin : $ok OK, $ko KO (${(System.currentTimeMillis() - debut) / 1000} s)")
        runCatching { File(dossier, "sport_audit.txt").writeText(rapport.toString()) }
    }

    private suspend fun tester(idTuile: String, nom: String, s: SportHub.Source, estWeb: Boolean): String {
        val type = Video.Type.Episode(
            id = idTuile, number = 1, title = nom, poster = null, overview = null,
            tvShow = Video.Type.Episode.TvShow(idTuile, nom, null, null, null, null),
            season = Video.Type.Episode.Season(1, "En Direct"),
        )
        val serveurs = withTimeoutOrNull(30_000) {
            if (estWeb) SportHub.serveursWebAudit(s) else LiveTvHubProvider.getServers(s.id, type)
        } ?: return "KO serveurs : délai dépassé"
        if (serveurs.isEmpty()) return "KO aucun serveur"
        val essais = mutableListOf<String>()
        for (srv in serveurs.take(if (estWeb) 2 else 3)) {
            val r = runCatching {
                val v = withTimeoutOrNull(45_000) { LiveTvHubProvider.getVideo(srv) }
                    ?: return@runCatching "getVideo délai dépassé"
                if (v.drmType != null) return@runCatching "OK (DRM ${v.drmType}, non sondé)"
                sonder(v)
            }.getOrElse { "getVideo : ${it.message?.take(80)}" }
            if (r.startsWith("OK")) return "$r · ${srv.name}"
            essais += "${srv.name}: $r"
        }
        return "KO ${serveurs.size} serveur(s) — " + essais.joinToString(" | ")
    }

    private class Reponse(val code: Int, val octets: ByteArray, val type: String?, val url: String)

    private fun lire(url: String, entetes: Map<String, String>?, max: Int): Reponse {
        val req = Request.Builder().url(url).apply { entetes?.forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(req).execute().use { r ->
            val src = r.body?.byteStream()
            val buf = ByteArray(max)
            var n = 0
            if (src != null) while (n < max) { val k = src.read(buf, n, max - n); if (k <= 0) break; n += k }
            return Reponse(r.code, buf.copyOf(n), r.header("Content-Type"), r.request.url.toString())
        }
    }

    private fun resoudre(base: String, ref: String): String =
        runCatching { java.net.URI(base).resolve(ref.replace(" ", "%20")).toString() }.getOrDefault(ref)

    private fun sonder(v: Video): String {
        var url = v.source
        if (url.isBlank()) return "source vide"
        var rep = lire(url, v.headers, 64_000)
        for (niveau in 0..2) {
            if (rep.code !in 200..299) return "HTTP ${rep.code} sur ${if (niveau == 0) "manifeste" else "sous-liste"}"
            val txt = String(rep.octets, Charsets.UTF_8)
            if (!txt.trimStart().startsWith("#EXTM3U")) return verdictSegment(rep, "flux direct")
            val lignes = txt.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val variante = lignes.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
                .takeIf { it >= 0 }?.let { i -> lignes.drop(i + 1).firstOrNull { !it.startsWith("#") } }
            if (variante != null) { url = resoudre(rep.url, variante); rep = lire(url, v.headers, 64_000); continue }
            if (lignes.any { it.startsWith("#EXT-X-KEY") && !it.contains("METHOD=NONE") })
                return "OK (segments chiffrés AES, manifeste valide)"
            val seg = lignes.lastOrNull { !it.startsWith("#") } ?: return "manifeste sans segment"
            // 2026-10-28 : écran leurre de WiTV (« Ce site copie le catalogue de WiTV.eu ») = vraie
            //   vidéo, mais playlist courte terminée dont les segments portent `notice_ts`.
            if (seg.contains("notice_ts") || url.contains("vc=notice")) return "écran leurre WiTV"
            val r = lire(resoudre(rep.url, seg), v.headers, 4_096)
            if (r.code !in 200..299) return "HTTP ${r.code} sur segment"
            return verdictSegment(r, "HLS")
        }
        return "trop de niveaux de manifeste"
    }

    private fun verdictSegment(r: Reponse, genre: String): String {
        val b = r.octets
        if (b.size < 16) return "segment vide (${b.size} o)"
        for (i in 0 until minOf(b.size - 377, 2_048)) {
            if (b[i] == 0x47.toByte() && b[i + 188] == 0x47.toByte() && b[i + 376] == 0x47.toByte())
                return "OK $genre TS${if (i > 0) " (décalé de $i o)" else ""}"
        }
        val ascii = String(b, 0, minOf(b.size, 64), Charsets.ISO_8859_1)
        if (ascii.contains("ftyp") || ascii.contains("moof") || ascii.contains("styp")) return "OK $genre fMP4"
        if (ascii.startsWith("ID3")) return "OK $genre audio/ID3"
        val hex = b.take(8).joinToString(" ") { "%02x".format(it) }
        return "segment illisible ($hex, ${r.type})"
    }
}
