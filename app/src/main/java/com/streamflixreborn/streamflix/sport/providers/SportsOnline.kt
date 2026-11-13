package com.streamflixreborn.streamflix.sport.providers

import android.util.Log
import com.streamflixreborn.streamflix.sport.ProviderError
import com.streamflixreborn.streamflix.sport.SportEvent
import com.streamflixreborn.streamflix.sport.StreamSource
import com.streamflixreborn.streamflix.sport.TtlCache
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

/**
 * 2026-11-15 (user : « très intéressant… ajoute l'ensemble des vidéos, et celles qui sont
 * françaises, on les met dans l'onglet sport ») — planning du réseau SportsOnline.
 *
 * Vu dans le Chrome du user : `https://sportsonline.st/prog.txt` est un simple texte, refait chaque
 * jour. Par jour : le nom du jour (« SUNDAY »), la LÉGENDE des chaînes (« HD3 FRENCH », « HD5
 * TURKISH », « BR1 BRAZILIAN »…), puis une ligne par diffusion :
 *   `19:45   France x Turkey | https://sportsoonline.click/channels/hd/hd4.php`
 * (le même match revient sur plusieurs lignes, une par chaîne).
 *
 * Le français DÉPEND DE L'HEURE (user : « HD3 et HD4 pour l'instant ne sont pas françaises ») : une
 * chaîne marquée FRENCH n'est en français que pendant les matchs inscrits dessus ; le reste du
 * temps elle relaie autre chose. On ne propose donc PAS ces chaînes comme chaînes fixes : seulement
 * les matchs du planning, « français » si l'une de leurs chaînes est marquée FRENCH ce jour-là.
 * Les chaînes 24 h/24 du site (247.txt) sont toutes portugaises : non reprises.
 *
 * Lecture : la page de chaîne est lue par [com.streamflixreborn.streamflix.extractors.SportsOnlineExtractor]
 * (cf. SportProviders.video). Heures du planning : heure du Portugal (site portugais ; égale à
 * l'UTC en hiver) — à corriger ici si un match s'affichait décalé d'une heure en été.
 */
object SportsOnline {

    const val ID = "sportsonline"
    private const val TAG = "SportSportsOnline"
    private const val PLANNING = "https://sportsonline.st/prog.txt"
    private val FUSEAU = ZoneId.of("Europe/Lisbon")
    /** Fenêtre montrée (comme DaddyLive) : commencé il y a moins de 3 h, ou dans moins de 12 h. */
    private const val AVANT_MS = 3 * 3600_000L
    private const val APRES_MS = 12 * 3600_000L
    private const val FOOT_FINI_MS = 135 * 60_000L

    private val cache = TtlCache<List<SportEvent>>(5 * 60_000L)
    private val client by lazy {
        NetworkClient.default.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
    }

    suspend fun getStreams(): List<SportEvent> = cache.get { charger() }
    fun derniereListe(): List<SportEvent> = cache.derniere() ?: emptyList()

    private val JOURS = DayOfWeek.values().associateBy { it.name }
    private val RX_LEGENDE = Regex("^((?:HD|BR)\\d+)\\s+([A-Z][A-Z &]+?)\\s*$")
    private val RX_DIFFUSION = Regex("^(\\d{1,2}):(\\d{2})\\s+(.+?)\\s*\\|\\s*(https?://\\S+/channels/\\S+?\\.php)\\s*$")
    private val RX_PREFIXE_SPORT = Regex("^[\\p{L} ]{3,14}:\\s+")
    private val LANGUES = mapOf(
        "FRENCH" to "Français", "ENGLISH" to "Anglais", "SPANISH" to "Espagnol", "ITALIAN" to "Italien",
        "TURKISH" to "Turc", "BRAZILIAN" to "Brésilien", "PORTUGUESE" to "Portugais",
        "GERMAN" to "Allemand", "ALBANIAN" to "Albanais", "CROATIAN" to "Croate",
    )

    private class Diffusion(val debut: Long, val titre: String, val sources: ArrayList<StreamSource>, var francais: Boolean)

    private suspend fun charger(): List<SportEvent> = withContext(Dispatchers.IO) {
        val texte = client.newCall(okhttp3.Request.Builder().url(PLANNING).build()).execute().use { r ->
            val corps = r.body?.string().orEmpty()
            if (!r.isSuccessful || !corps.contains("/channels/")) throw ProviderError(ID, r.code, "planning illisible")
            corps
        }
        val maintenant = System.currentTimeMillis()
        val aujourdhui = LocalDate.now(FUSEAU)
        var jour: LocalDate = aujourdhui
        val legende = HashMap<String, String>() // « hd3 » → « FRENCH » (refaite à chaque jour du planning)
        val parMatch = LinkedHashMap<String, Diffusion>()
        for (ligneBrute in texte.lineSequence()) {
            val ligne = ligneBrute.trim()
            if (ligne.isEmpty()) continue
            val nomJour = JOURS[ligne.uppercase()]
            if (nomJour != null) {
                // Le planning ne nomme que le jour de la semaine : aujourd'hui ou le prochain.
                jour = aujourdhui.with(TemporalAdjusters.nextOrSame(nomJour)); legende.clear()
                continue
            }
            val lg = RX_LEGENDE.find(ligne)
            if (lg != null) { legende[lg.groupValues[1].lowercase()] = lg.groupValues[2].trim(); continue }
            val m = RX_DIFFUSION.find(ligne) ?: continue
            val (h, mn, brut, lien) = m.destructured
            val debut = runCatching {
                jour.atTime(LocalTime.of(h.toInt(), mn.toInt())).atZone(FUSEAU).toInstant().toEpochMilli()
            }.getOrNull() ?: continue
            if (debut < maintenant - AVANT_MS || debut > maintenant + APRES_MS) continue
            // « France x Turkey » → « France vs Turkey » : même écriture que les autres plannings,
            //   pour que le match rejoigne la même tuile (cf. SportProviders.cleEquipes).
            val titre = brut.replace(Regex("\\s+x\\s+", RegexOption.IGNORE_CASE), " vs ").trim()
            val chaine = lien.substringAfterLast('/').removeSuffix(".php").lowercase()
            val langueBrute = legende[chaine] ?: if ("/channels/pt/" in lien) "PORTUGUESE" else ""
            val francais = "FRENCH" in langueBrute
            val langue = langueBrute.split('&').map { it.trim() }.filter { it.isNotEmpty() }
                .joinToString(" / ") { LANGUES[it] ?: it.lowercase().replaceFirstChar(Char::uppercase) }
            val nom = chaine.uppercase() + if (langue.isNotEmpty()) " · $langue" else ""
            val d = parMatch.getOrPut("$debut|${titre.lowercase()}") { Diffusion(debut, titre, ArrayList(), false) }
            if (d.sources.none { it.embedUrl == lien }) {
                // Les chaînes en français en tête : ce sont elles que le lecteur essaie d'abord.
                if (francais) d.sources.add(d.sources.count { "Français" in it.name }, StreamSource(nom, lien))
                else d.sources.add(StreamSource(nom, lien))
            }
            if (francais) d.francais = true
        }
        val out = parMatch.values.mapNotNull { d ->
            val cat = categorie(d.titre)
            if (cat == "football" && d.debut < maintenant - FOOT_FINI_MS) return@mapNotNull null
            SportEvent(
                id = "${d.debut / 60_000L}_${d.titre.hashCode()}", provider = ID,
                // « Rugby: Scotland vs Australia » → « Scotland vs Australia » (le sport est dans la
                //   catégorie) ; les titres sans deux équipes (« Golf - … : D4 ») restent entiers.
                title = if (" vs " in d.titre) d.titre.replace(RX_PREFIXE_SPORT, "") else d.titre,
                league = "", category = cat, poster = "",
                startsAt = d.debut, endsAt = null,
                status = if (d.debut <= maintenant) "live" else "upcoming", viewers = 0,
                sources = d.sources, embedUrl = d.sources.first().embedUrl, francais = d.francais,
            )
        }
        Log.i(TAG, "${out.size} matchs dans la fenêtre (${out.count { it.enDirect }} commencés, ${out.count { it.francais }} en français)")
        out
    }

    /** Le planning préfixe les autres sports (« Rugby: », « Padel: », « Golf - »…) ; un simple
     *  « A x B » sans préfixe est du football. Catégories au sens de SportHub.rubriqueEvenement. */
    private fun categorie(titre: String): String {
        val c = titre.lowercase()
        return when {
            "nba" in c || "basket" in c || "euroleague" in c -> "basketball"
            "nfl" in c || "nhl" in c || "mlb" in c || "hockey" in c -> "american-football"
            "tennis" in c || "atp" in c || "wta" in c || "padel" in c -> "tennis"
            "rugby" in c -> "rugby"
            "f1" in c || "formula" in c || "motogp" in c || "moto2" in c || "moto3" in c || "nascar" in c -> "motor-sports"
            "ufc" in c || "boxing" in c || "mma" in c || "wwe" in c -> "fight"
            "golf" in c -> "golf"
            "cycling" in c -> "cycling"
            " vs " in c -> "football"
            else -> ""
        }
    }
}
