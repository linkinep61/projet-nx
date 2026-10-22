package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

/**
 * 2026-10-22 (user : « il faut qu'on regarde sur les divers, comme Vidara, si on prend bien en
 * compte les sous-titres ») : beaucoup d'hébergeurs (famille jwplayer : VidHide, Darkibox,
 * Upzone, Vidmoly, FSVid…) déclarent leurs sous-titres dans `tracks:[{file,label,kind}]`
 * — souvent dans un script « packé » — et l'extracteur les ignorait. Lecture COMMUNE ici :
 *   · depuisPage : HTML/JS brut (+ dépaquetage des eval(function(p,a,c,k,e,d)…)) ;
 *   · depuisJson : réponse d'API (clés tracks / subtitles / captions).
 * Ne garde que de vrais sous-titres (kind captions/subtitles, ou fichier .vtt/.srt/.ass) —
 * jamais les vignettes (thumbnails) ni les chapitres. Liste vide si rien : sans effet.
 */
object SousTitresHebergeur {

    private const val TAG = "SousTitresHebergeur"

    fun depuisPage(texte: String?, base: String): List<Video.Subtitle> {
        if (texte.isNullOrBlank()) return emptyList()
        val morceaux = mutableListOf(texte)
        var i = texte.indexOf("eval(function(p,a,c,k,e,")
        while (i >= 0 && morceaux.size < 6) {
            val fin = texte.indexOf("</script>", i).let { if (it < 0) texte.length else it }
            runCatching { JsUnpacker(texte.substring(i, fin)).unpack() }.getOrNull()?.let { morceaux.add(it) }
            i = texte.indexOf("eval(function(p,a,c,k,e,", fin)
        }
        val vus = LinkedHashMap<String, Video.Subtitle>()
        for (t in morceaux) {
            val propre = t.replace("\\/", "/")
            Regex("""tracks["']?\s*[:=]\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).findAll(propre).forEach { bloc ->
                Regex("""\{([^{}]*)\}""").findAll(bloc.groupValues[1]).forEach { o ->
                    piste(o.groupValues[1], base)?.let { vus.putIfAbsent(it.file, it) }
                }
            }
        }
        val res = vus.values.toList()
        if (res.isNotEmpty()) Log.d(TAG, "${res.size} sous-titre(s) trouvé(s) : ${res.joinToString { it.label }}")
        return res
    }

    private fun champ(obj: String, vararg cles: String): String? {
        for (c in cles) {
            Regex("""["']?\b$c\b["']?\s*:\s*["']([^"']*)["']""").find(obj)?.groupValues?.get(1)
                ?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun piste(obj: String, base: String): Video.Subtitle? {
        val file = champ(obj, "file", "src", "url") ?: return null
        val kind = champ(obj, "kind")?.lowercase()
        if (kind != null && kind != "captions" && kind != "subtitles") return null
        val ext = file.substringBefore('?').lowercase()
        val estFichierSt = ext.endsWith(".vtt") || ext.endsWith(".srt") || ext.endsWith(".ass") || ext.endsWith(".ssa")
        if (kind == null && !estFichierSt) return null
        if (ext.endsWith(".jpg") || ext.endsWith(".png") || ext.contains("thumb") || ext.contains("sprite")) return null
        val label = champ(obj, "label", "srclang", "language", "lang") ?: "Inconnu"
        val defaut = Regex("""["']?default["']?\s*:\s*(true|1|["']true["'])""").containsMatchIn(obj)
        return Video.Subtitle(label = libelle(label), file = absolue(file, base), initialDefault = defaut)
    }

    fun depuisJson(json: JSONObject?, base: String): List<Video.Subtitle> {
        if (json == null) return emptyList()
        val vus = LinkedHashMap<String, Video.Subtitle>()
        fun parcourir(o: Any?, profondeur: Int) {
            if (profondeur > 4 || o == null) return
            when (o) {
                is JSONObject -> o.keys().forEach { k ->
                    val v = o.opt(k)
                    if (k.lowercase() in setOf("tracks", "subtitles", "subtitle", "captions") && v is JSONArray) {
                        for (i in 0 until v.length()) {
                            val p = v.optJSONObject(i) ?: continue
                            val file = listOf("file", "url", "src", "file_path", "path").firstNotNullOfOrNull { c -> p.optString(c).takeIf { it.isNotBlank() } } ?: continue
                            val kind = p.optString("kind").lowercase()
                            if (kind.isNotBlank() && kind != "captions" && kind != "subtitles") continue
                            val label = listOf("label", "language", "lang", "srclang", "name").firstNotNullOfOrNull { c -> p.optString(c).takeIf { it.isNotBlank() } } ?: "Inconnu"
                            val f = absolue(file, base)
                            vus.putIfAbsent(f, Video.Subtitle(label = libelle(label), file = f, initialDefault = p.optBoolean("default", false)))
                        }
                    } else parcourir(v, profondeur + 1)
                }
                is JSONArray -> for (i in 0 until o.length()) parcourir(o.opt(i), profondeur + 1)
            }
        }
        parcourir(json, 0)
        val res = vus.values.toList()
        if (res.isNotEmpty()) Log.d(TAG, "${res.size} sous-titre(s) (API) : ${res.joinToString { it.label }}")
        return res
    }

    /**
     * 2026-10-22 — sous-titres DANS L'ADRESSE du lecteur (doc officielle DoodStream et
     * Filemoon/Byse, standard XFileSharing) : c'est le site intégrateur qui les ajoute.
     *   · `c1_file=<vtt/srt>&c1_label=French` (c2_…, c3_… pour plusieurs pistes) ;
     *   · `subtitle_json=[{"src":…,"label":…,"default":true}]` (Dood) ;
     *   · `sub.info=<url d'un .json>` (Byse) — lu ici, délai court.
     */
    fun depuisAdresse(vararg liens: String?): List<Video.Subtitle> {
        val vus = LinkedHashMap<String, Video.Subtitle>()
        for (lien in liens.filterNotNull().distinct()) {
            val q = lien.substringAfter('?', "").substringBefore('#')
            if (q.isBlank()) continue
            val params = q.split('&').mapNotNull { kv ->
                val k = kv.substringBefore('=')
                val v = kv.substringAfter('=', "")
                if (k.isBlank() || v.isBlank()) null
                else k to runCatching { java.net.URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
            }.toMap()
            params.keys.mapNotNull { Regex("""^c(\d+)_file$""").find(it)?.groupValues?.get(1) }.sortedBy { it.toInt() }.forEach { n ->
                val f = params["c${n}_file"] ?: return@forEach
                if (!f.startsWith("http") && !f.startsWith("//")) return@forEach
                val fa = absolue(f, lien)
                vus.putIfAbsent(fa, Video.Subtitle(label = libelle(params["c${n}_label"] ?: "Inconnu"), file = fa))
            }
            params["subtitle_json"]?.let { j ->
                runCatching { JSONArray(j) }.getOrNull()?.let { tableau(it, lien, vus) }
            }
            (params["sub.info"] ?: params["sub_info"])?.takeIf { it.startsWith("http") }?.let { u ->
                runCatching {
                    val client = Extractor_client()
                    client.newCall(okhttp3.Request.Builder().url(u).build()).execute().use { r ->
                        val corps = r.body?.string().orEmpty().trim()
                        if (corps.startsWith("[")) tableau(JSONArray(corps), u, vus)
                        else if (corps.startsWith("{")) depuisJson(JSONObject(corps), u).forEach { vus.putIfAbsent(it.file, it) }
                    }
                }.onFailure { Log.d(TAG, "sub.info illisible : ${it.message}") }
            }
        }
        val res = vus.values.toList()
        if (res.isNotEmpty()) Log.d(TAG, "${res.size} sous-titre(s) dans l'adresse du lecteur : ${res.joinToString { it.label }}")
        return res
    }

    private fun Extractor_client(): okhttp3.OkHttpClient = NetworkClient.default.newBuilder()
        .callTimeout(6, java.util.concurrent.TimeUnit.SECONDS).build()

    private fun tableau(a: JSONArray, base: String, vus: LinkedHashMap<String, Video.Subtitle>) {
        for (i in 0 until a.length()) {
            val p = a.optJSONObject(i) ?: continue
            val file = listOf("src", "file", "url").firstNotNullOfOrNull { c -> p.optString(c).takeIf { it.isNotBlank() } } ?: continue
            val label = listOf("label", "language", "lang", "srclang").firstNotNullOfOrNull { c -> p.optString(c).takeIf { it.isNotBlank() } } ?: "Inconnu"
            val f = absolue(file, base)
            vus.putIfAbsent(f, Video.Subtitle(label = libelle(label), file = f, initialDefault = p.optBoolean("default", false)))
        }
    }

    /** Ajoute à `video` les sous-titres trouvés dans l'adresse (sans doublon). */
    fun completer(video: Video, vararg liens: String?): Video {
        val enPlus = runCatching { depuisAdresse(*liens) }.getOrDefault(emptyList())
            .filter { n -> video.subtitles.none { it.file == n.file } }
        return if (enPlus.isEmpty()) video else video.copy(subtitles = video.subtitles + enPlus)
    }

    /** Libellés courts → lisibles (le filtre « français seulement » reconnaît aussi les codes). */
    fun libelle(l: String): String {
        val b = l.trim()
        return when (b.lowercase()) {
            "fr", "fre", "fra", "french", "francais", "français" -> "Français"
            "en", "eng", "english" -> "Anglais"
            else -> b
        }
    }

    private fun absolue(f: String, base: String): String = when {
        f.startsWith("http") -> f
        f.startsWith("//") -> "https:$f"
        else -> runCatching { URL(URL(base), f).toString() }.getOrDefault(f)
    }
}
