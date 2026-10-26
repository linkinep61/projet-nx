package com.streamflixreborn.streamflix.sport.providers

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.sport.ProviderError
import com.streamflixreborn.streamflix.sport.SportEvent
import com.streamflixreborn.streamflix.sport.StreamSource
import com.streamflixreborn.streamflix.sport.TtlCache
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Planning thedaddy / DaddyLive (liste du user, priorité 2 ; miroirs dlhd.pk, dlhd.st).
 *
 * Vérifié le 25/10/2026 dans le Chrome du user : la page d'accueil contient tout le planning
 * en HTML (`.schedule__day` > `.schedule__category` > `.schedule__event` : `.schedule__time`
 * en heure de Londres, `.schedule__eventTitle`, `.schedule__channels a[href=/watch.php?id=N]`),
 * 627 événements ce jour-là. Les numéros N sont ceux du relais damitv (`dlhd-N`) : testés
 * 200 sur `damitv.st/dl/<N>.m3u8` (1205, 1207, 308, 107, 203, 271…), certains 404 (5000, 6001).
 * → chaque match a pour serveurs ses chaînes, lues par [DamiTvChaines] (jeton + PNG décodé).
 */
object DaddyLive {

    const val ID = "daddylive"
    private const val TAG = "SportDaddyLive"
    private val MIROIRS = listOf("https://thedaddy.pk", "https://dlhd.pk", "https://dlhd.st")
    // 2026-10-27 (vérifié dans le Chrome du user) : les horaires du planning sont en GMT FIXE (= UTC) —
    //   le site les convertit lui-même avec un sélecteur « GMT +01:00 » (décalages purs, pas de fuseau
    //   nommé) ; un utilisateur en France réglait « GMT+2 » cet été. Mesuré : data-time 11:00 affiché
    //   12:00 sur un PC en heure de Paris (UTC+1). « Europe/London » donnait la même chose l'hiver mais
    //   aurait avancé tous les matchs d'une heure de fin mars à fin octobre (heure d'été britannique).
    private val LONDRES = ZoneId.of("UTC")
    /** Fenêtre montrée : commencé il y a moins de 3 h, ou commence dans moins de 12 h. */
    private const val AVANT_MS = 3 * 3600_000L
    private const val APRES_MS = 12 * 3600_000L
    private const val FOOT_FINI_MS = 135 * 60_000L

    private val cache = TtlCache<List<SportEvent>>(5 * 60_000L)
    private val client by lazy {
        NetworkClient.default.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
    }

    suspend fun getStreams(): List<SportEvent> = cache.get { charger() }
    fun derniereListe(): List<SportEvent> = cache.derniere() ?: emptyList()

    private fun page(): String {
        var derniere: Exception? = null
        for (base in MIROIRS) {
            try {
                val req = okhttp3.Request.Builder().url("$base/").build()
                client.newCall(req).execute().use { r ->
                    val corps = r.body?.string().orEmpty()
                    if (r.isSuccessful && corps.contains("schedule__event")) return corps
                    derniere = ProviderError(ID, r.code, "$base sans planning")
                }
            } catch (e: Exception) { derniere = e }
        }
        throw derniere ?: ProviderError(ID, -1)
    }

    private suspend fun charger(): List<SportEvent> = withContext(Dispatchers.IO) {
        val doc = Jsoup.parse(page())
        val maintenant = System.currentTimeMillis()
        val aujourdhui = LocalDate.now(LONDRES)
        val out = ArrayList<SportEvent>()
        // Seul le PREMIER jour est celui du moment (le second bloc est parfois une vieille date).
        val jour = doc.selectFirst(".schedule__day") ?: throw ProviderError(ID, 200, "pas de planning")
        for (cat in jour.select(".schedule__category")) {
            val nomCat = cat.selectFirst(".schedule__catHeader")?.text()?.trim().orEmpty()
            for (ev in cat.select(".schedule__event")) {
                val heure = ev.selectFirst(".schedule__time")?.attr("data-time")?.ifBlank { null }
                    ?: ev.selectFirst(".schedule__time")?.text()?.trim() ?: continue
                val brut = ev.selectFirst(".schedule__eventTitle")?.text()?.let(::nettoyer) ?: continue
                // « Spain - La Liga : Real Sociedad vs Levante » → match « Real Sociedad vs Levante »,
                //   compétition « Spain - La Liga » : le match rejoint la même tuile chez streamed/damitv.
                val (ligue, titre) = brut.indexOf(" : ").takeIf { it > 0 }
                    ?.let { i -> brut.substring(0, i).trim() to brut.substring(i + 3).trim() }
                    ?.takeIf { it.second.isNotBlank() } ?: ("" to brut)
                val debut = runCatching {
                    aujourdhui.atTime(LocalTime.parse(heure.take(5))).atZone(LONDRES).toInstant().toEpochMilli()
                }.getOrNull() ?: continue
                if (debut < maintenant - AVANT_MS || debut > maintenant + APRES_MS) continue
                val chaines = ev.select(".schedule__channels a").mapNotNull { a ->
                    val n = Regex("[?&]id=(\\d+)").find(a.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
                    // Numéros >= 5000 (« Event Stream ») : damitv ne les relaie pas (404, tri du 25/10).
                    if ((n.toIntOrNull() ?: 0) >= 5000) return@mapNotNull null
                    StreamSource(name = a.text().trim().ifBlank { "Chaîne $n" }, embedUrl = n)
                }.distinctBy { it.embedUrl }
                if (chaines.isEmpty()) continue
                // Un match de foot commencé il y a plus de 2 h 15 est fini (tri du 25/10 : des « Event SD
                //   Stream » morts dans Foot d'ailleurs).
                if (categorie(nomCat, brut) == "football" && debut < maintenant - FOOT_FINI_MS) continue
                out += SportEvent(
                    id = "${heure.take(5)}_${titre.hashCode()}", provider = ID, title = titre,
                    league = ligue, category = categorie(nomCat, brut), poster = "",
                    startsAt = debut, endsAt = null,
                    status = if (debut <= maintenant) "live" else "upcoming", viewers = 0,
                    sources = chaines, embedUrl = chaines.first().embedUrl,
                    francais = chaines.any { estFrancaise(it.name) },
                )
            }
        }
        Log.i(TAG, "${out.size} événements dans la fenêtre (${out.count { it.enDirect }} commencés)")
        out
    }

    /** « ⚽ Japan J League : Nagoya Grampus vs Yokohama F. Marinos » → sans émojis ni drapeaux. */
    private fun nettoyer(t: String): String =
        t.replace(Regex("[\\p{So}\\p{Sk}\\uFE0F\\u200D]"), "")
            .replace(Regex("\\s+"), " ").trim().trim('|', ' ')

    /** Catégorie au sens de SportHub.rubriqueEvenement (« football », « basketball »…). */
    private fun categorie(cat: String, titre: String): String {
        val c = (cat + " " + titre).lowercase()
        return when {
            "soccer" in c || "football" in c && "american" !in c && "nfl" !in c -> "football"
            "basket" in c || "nba" in c -> "basketball"
            "nfl" in c || "american football" in c || "nhl" in c || "hockey" in c || "mlb" in c || "baseball" in c -> "american-football"
            "tennis" in c -> "tennis"
            "rugby" in c -> "rugby"
            "motor" in c || "f1" in c || "formula" in c || "motogp" in c || "nascar" in c -> "motor-sports"
            "ufc" in c || "boxing" in c || "mma" in c || "wwe" in c || "fight" in c -> "fight"
            "golf" in c -> "golf"
            "cycling" in c -> "cycling"
            else -> cat
        }
    }

    /** « beIN SPORTS 1 France », « CANAL+ MotoGP France », « RMC Sport 1 FR »… */
    private val FRANCAISE = Regex("france|french|\\bfr\\b|\\bvf\\b", RegexOption.IGNORE_CASE)
    fun estFrancaise(nom: String) = FRANCAISE.containsMatchIn(nom)

    /** Serveurs : les chaînes du match (les françaises d'abord), lues par le relais damitv. */
    fun serveurs(ev: SportEvent): List<Video.Server> =
        ev.sources.sortedBy { if (estFrancaise(it.name)) 0 else 1 }
            .map { s -> DamiTvChaines.serveur("${DamiTvChaines.PREFIX}${s.embedUrl}", s.name, "DaddyLive") }
}
