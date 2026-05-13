package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Video

/**
 * Trie une liste de [Video.Server] par fiabilité décroissante en croisant
 * deux signaux que l'app traque déjà :
 *  1. **healthScore** ([Extractor.healthScore]) : 0 si l'extracteur a foiré
 *     ≥3 fois dans les 5 dernières minutes, 1 sinon. C'est le signal "broken
 *     right now".
 *  2. **failure count** ([ExtractorFailureTracker.getFailures]) : nombre
 *     d'échecs consécutifs depuis le dernier succès, persisté entre sessions
 *     et auto-resété au bump de versionCode. C'est le signal "broken across
 *     sessions" qui révèle les extracteurs cassés en profondeur (eg.
 *     Filemoon dont l'unpacker JS a cassé).
 *
 * Score final par server = `healthScore × 100 - clamp(failureCount × 8, 0..80)`
 * Sort stable par score décroissant.
 *
 * Pourquoi ce design ?
 * --------------------
 * On veut que les extracteurs récemment-cassés tombent au fond du picker
 * (l'utilisateur n'a pas envie d'attendre 20s pour un échec connu) MAIS
 * sans les éliminer (parfois ils remarchent, et le user a pas d'autre
 * source). Donc même un extracteur avec 10 échecs garde un score positif
 * (100 - 80 = 20) qui le place après les sains (100) mais avant les
 * actuellement-cassés (0).
 *
 * Effet utilisateur typique
 * -------------------------
 * Aujourd'hui : Filemoon en 1er, lent à démarrer, parfois fail.
 * Avec ce trieur : si Filemoon a foiré une fois récemment, il passe à la
 * 4e/5e position et VOE/Vidoza/Uqload remontent. Au fil du temps, l'ordre
 * du picker reflète la fiabilité réelle observée sur le device de l'user
 * (couverture / réseau / version Android).
 *
 * Bonus : on déprorise les hosts connus pour bloquer les FAI FR (netu, waaw,
 * hqq, etc.) — pattern hardcodé qui était déjà dans PlayerViewModel.
 */
object ExtractorRanker {

    private const val TAG = "ExtractorRanker"

    /** Hosts/extracteurs notoirement bloqués par les FAI FR — toujours en
     *  bas de la liste, même s'ils sont "sains" côté tracker. */
    private val FRENCH_ISP_BLOCKED_PATTERNS = listOf(
        "netu", "waaw", "hqq", "hqcloud", "younetu",
    )

    /**
     * Bias empirique de vitesse de démarrage par extracteur.
     * Ajouté au score : positif = remonte dans le picker, négatif = descend.
     * Mesuré observationnellement (logs StreamFlixES) sur Pixel 8 + Wi-Fi FR.
     *
     *  Catégories
     *  ----------
     *   +20..+25 : direct m3u8/mp4 sans unpacking JS, démarre <1s
     *   +10..+15 : direct mais 1-2 roundtrips, ~1-1.5s
     *    0..+5   : JS léger ou redirect chain, ~1.5-2s
     *   -5..-15  : JS unpacker lourd, ~2-3s (Filemoon, Doodstream)
     *   -20..-50 : WebView-based ou multi-step, >3s (Meritend, BigWarp VLC-only)
     *
     *  Les noms doivent matcher EXACTEMENT [Extractor.name] (CamelCase exact),
     *  case-insensitive grâce au lookup avec lowercase().
     *
     *  Update au fur et à mesure : si on voit un extracteur où l'observation
     *  diverge du bias, on ajuste. Bias absent → 0 = neutre.
     */
    private val SPEED_BIAS: Map<String, Int> = mapOf(
        // ─── ULTRA rapides : MP4/HLS direct, AUCUN extracteur, URL connue d'avance ───
        // Les serveurs Cloudstream natifs (cs_rd_*, cs_resource_*, cs_playinfo_*)
        // sortent directement du CDN bcdn.hakunaymatata.com — pas de regex, pas
        // de décryption, pas de Web View. C'est objectivement le chemin le plus
        // court, donc ils méritent la priorité absolue. +50 = au-dessus de tout.
        // Ils doivent passer devant les wrappers Movix/Nakios — c'est leur source
        // native, le user veut Cloudstream d'abord quand il est sur le provider.
        "cloudstream" to 50,
        // ─── Très rapides : m3u8 direct, regex simple ───
        "voe" to 25,
        "vidoza" to 20,
        "uqload" to 20,
        "vidguard" to 15,
        "hxfile" to 15,
        "ridoo" to 15,
        "mailru" to 15,
        "videosibnet" to 15,
        "yandexdisk" to 15,
        "streamtape" to 15,
        "streamwish" to 15,
        "uqloads" to 15,
        "swiftplayer" to 15,
        "swish" to 15,
        "hlswish" to 15,
        "playerwish" to 15,
        "luluvdo" to 15,
        "okru" to 15,
        "googledrive" to 15,
        "amazondrive" to 15,
        "pcloud" to 15,
        "gupload" to 12,
        "embedseek" to 18,    // 2026-05-09 : single JSON API call /api/v1/player, devrait être très rapide

        // ─── Rapides : 1-2 roundtrips ───
        "frembed" to 10,
        "vidmoly" to 10,
        "vidplay" to 10,
        "mycloud" to 10,
        "vidplayonline" to 10,
        "darkibox" to 10,
        "streamhub" to 10,
        "vidzy" to 10,
        "savefiles" to 10,
        "rpmvid" to 8,
        "vtube" to 8,
        "up4stream" to 8,
        "vidsonic" to 8,
        "vidnest" to 8,
        "vidara" to 8,
        "vidrock" to 8,
        "videasy" to 8,
        "primesrc" to 8,
        "kakaflix" to 8,
        "moflix" to 8,
        "moiflix" to 8,
        "papadustream" to 8,
        "moviebox" to 8,

        // ─── Médians : JS léger / redirect ───
        "doodstream" to 0,
        "doodli" to 0,
        "doodla" to 0,
        "dood" to 0,
        "vidhide" to 5,
        "veev" to 0,
        "goodstream" to 0,
        "mixdrop" to 0,
        "magasavors" to 5,
        "closeload" to 5,
        "vidora" to 5,
        "lpayer" to 5,
        "supervideo" to 0,
        "dropload" to 0,
        "fsvid" to 5,
        "yflix" to 0,
        "lamovie" to 0,
        "hxlowx" to 0,

        // ─── Lents : JS unpacker lourd, multi-roundtrips ───
        "filemoon" to -15,        // packed JS unpacker, 2-3s observé
        "chillx" to -10,
        "jean" to -10,
        "moviesapi" to -10,
        "rabbitstream" to -10,
        "megacloud" to -10,
        "dokicloud" to -10,
        "premiumembeding" to -10,
        "vidsrc.to" to -10,
        "vidsrc.net" to -10,
        "vidsrc.ru" to -15,
        "loadx" to -15,
        "vixsrc" to -10,
        "darkiworld" to -10,
        "ustr" to -10,

        // ─── Très lents : WebView ou multi-step ───
        "meritend" to -25,        // WebView m3u8 intercept, 5-15s
        "bigwarp (vlc only)" to -50,  // VLC-only = inutilisable dans ExoPlayer
        "vidsrcto" to -25,
        "mazquest" to -10,        // Yandex Disk via embed
        "streamup" to -15,
        "guploadextractor" to -15,
        "shareciously" to -15,
        "sharecloudy" to -15,

        // ─── Notoirement bloqués (déjà -200 via ISP_BLOCKED) ───
        "netu" to -50,            // safeguard si pattern ISP_BLOCKED rate
    )

    /**
     * Lookup case-insensitive du speed bias.
     *
     * Stratégie hybride avec 2 sources :
     *  1. **Mesure réelle** ([ExtractorLatencyTracker]) : si on a au moins
     *     [ExtractorLatencyTracker.MIN_SAMPLES_FOR_RELIABLE_AVG] samples
     *     pour cet extracteur, on convertit la moyenne observée en bias.
     *     C'est l'autorité dès qu'on a assez de données pour faire confiance.
     *  2. **Fallback hardcodé** : sinon [SPEED_BIAS] (basé sur la réputation
     *     générale de l'extracteur). Sert au bootstrap les premiers jours
     *     d'usage avant qu'on ait collecté assez de mesures.
     *
     * Avec ce setup, le ranker s'auto-corrige : si Filemoon s'avère rapide
     * sur le device de l'user, sa moyenne réelle (~1500ms par exemple)
     * lui donnera un bias positif qui écrasera le -15 hardcodé. Et
     * inversement si VOE s'avère lent.
     */
    private fun speedBiasFor(extractorName: String): Int {
        // Source 1 : mesure réelle observée sur le device de l'user.
        val avgMs = ExtractorLatencyTracker.getAvgMs(extractorName)
        if (avgMs != null) {
            return latencyToBias(avgMs)
        }
        // Source 2 : fallback hardcodé (réputation).
        return SPEED_BIAS[extractorName.lowercase()] ?: 0
    }

    /**
     * Convertit une latence d'extraction observée (ms) en bias de score.
     *
     * Mapping :
     *   <800ms  → +25  (très rapide, niveau Voe direct)
     *   800-1200 → +20
     *   1200-1700 → +12
     *   1700-2300 → +5
     *   2300-3000 → -5
     *   3000-4500 → -15
     *   >4500   → -25  (équivalent Meritend WebView)
     *
     * Échelle calibrée pour qu'un extracteur typique (~2s) ait bias 0 et que
     * les écarts soient comparables au [SPEED_BIAS] hardcodé pour la
     * cohérence du ranking.
     */
    private fun latencyToBias(avgMs: Long): Int = when {
        avgMs < 800   -> 25
        avgMs < 1200  -> 20
        avgMs < 1700  -> 12
        avgMs < 2300  -> 5
        avgMs < 3000  -> -5
        avgMs < 4500  -> -15
        else          -> -25
    }

    /**
     * Renvoie la liste triée. Préserve l'ordre original pour les égalités
     * (sort stable Kotlin).
     */
    fun rankServers(servers: List<Video.Server>): List<Video.Server> {
        if (servers.size <= 1) return servers

        // Précalcule le mapping name → failureCount pour éviter de re-parser
        // le JSON à chaque comparaison.
        val failureMap: Map<String, Int> = ExtractorFailureTracker.getFailures()
            .associate { it.name to it.count }

        // Score chaque server une fois.
        val scored = servers.map { server -> ScoredServer(server, computeScore(server, failureMap)) }

        // Log pour debug (visible dans le rapport bug aussi).
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            scored.forEach { s ->
                Log.d(TAG, "  ${s.server.name.padEnd(40)} → score=${s.score}")
            }
        }

        // Sort par score décroissant. `sortedByDescending` est stable côté
        // ordre original pour les égalités.
        return scored.sortedByDescending { it.score }.map { it.server }
    }

    private data class ScoredServer(val server: Video.Server, val score: Int)

    /**
     * Calcule le score d'un server. Plus le score est élevé, plus il remonte
     * dans le picker.
     *
     *  - **Base** : healthScore × 100 (0 si broken < 5min, sinon 100)
     *  - **Speed bias** : ±25 selon vitesse de démarrage empirique
     *    ([SPEED_BIAS]). C'est ce qui fait remonter Voe (+25) au-dessus de
     *    Filemoon (-15) même quand les deux sont sains.
     *  - **Pénalité failures persistants** : -8 par échec consécutif, max -80
     *  - **Pénalité langue** : -100 si VOSTFR, -150 si VO. Force TOUS les
     *    VF en haut, peu importe l'extracteur. Streamflix est FR-only, l'user
     *    veut toujours du VF en priorité quand dispo.
     *  - **Pénalité ISP-block** : -200 (force tout en bas, sous les broken)
     *
     *  Score typique d'un Voe (VF) : 100 + 25 = 125.
     *  Score typique d'un Voe (VOSTFR) : 100 + 25 - 100 = 25.
     *  Score typique d'un Voe (VO) : 100 + 25 - 150 = -25.
     *  Score d'un Filemoon (VF) sain : 100 - 15 = 85.
     *  Score d'un Filemoon (VF) qui vient de péter : 0 - 15 - 8 = -23.
     *  Score d'un Netu : -200.
     *  Ordre final : tous les VF (sains) > Filemoon broken > VOSTFR sains > VO > Netu.
     */
    private fun computeScore(server: Video.Server, failureMap: Map<String, Int>): Int {
        val nameLower = server.name.lowercase()
        val srcLower = server.src.lowercase()

        // ─── Pénalité ISP-block : force en bas de liste ───
        val isFrenchIspBlocked = FRENCH_ISP_BLOCKED_PATTERNS.any { p ->
            nameLower.contains(p) || srcLower.contains(p)
        }
        if (isFrenchIspBlocked) return -200

        // ─── Identifie l'extracteur ───
        // Stratégie hybride en 2 temps :
        //  1. Si le NOM du server matche un pattern "Wrapper — Real" (Movix —
        //     Voe, Nakios — Filemoon, etc.), prends "Real" pour le speed bias
        //     parce que c'est ce que le user perçoit (et ce qui détermine la
        //     vitesse perçue côté CDN final).
        //  2. Sinon, identifyServiceName(URL) — c'est l'extracteur réel qui
        //     va tourner.
        //  Fallback : extractKeywordFromServerName si rien d'autre marche.
        val nameBasedExtractor = extractKeywordFromServerName(server.name)
        val urlBasedExtractor = Extractor.identifyServiceName(server.src)
        val isWrappedName = server.name.contains(Regex("\\s[—–-]\\s"))
        val extractorName = when {
            // Pattern "Wrapper — Real" : prends le name-based qui reflète le vrai extracteur
            isWrappedName && nameBasedExtractor != null -> nameBasedExtractor
            urlBasedExtractor != null -> urlBasedExtractor
            nameBasedExtractor != null -> nameBasedExtractor
            else -> return 50 - computeLanguagePenalty(nameLower)
        }

        // healthScore : 0 si broken dans les 5 dernières min, 1 sinon.
        val health = (Extractor.healthScore(extractorName) * 100).toInt()

        // Failure count persistant. Cap à 10 → max -80 de pénalité.
        val failureCount = failureMap[extractorName] ?: 0
        val failurePenalty = minOf(failureCount, 10) * 8

        // Speed bias : +25 (Voe) à -25 (Meritend WebView).
        val speedBias = speedBiasFor(extractorName)

        // Pénalité langue : VF en haut, VOSTFR au milieu, VO tout en bas.
        val languagePenalty = computeLanguagePenalty(nameLower)

        return health + speedBias - failurePenalty - languagePenalty
    }

    /**
     * Détecte la langue depuis le nom du server et retourne la pénalité.
     * Streamflix est FR-only → VF/Français = priorité, VOSTFR/sub = fallback,
     * VO/original = dernier recours.
     *
     *  Détection :
     *   - "vostfr" / "sub" / "subbed" → -100 (subtitle français)
     *   - "vo" tout seul / "raw" / "multi" / "eng" → -150 (pas de sub FR)
     *   - sinon (VF, VFF, VFQ, ou pas de marker) → 0 (assumé VF)
     *
     *  Edge case important : "VF" inclut "FR Sub" et "VFF" (Vrai Français,
     *  pas remasterisé). On match sur ces patterns positifs aussi pour ne
     *  pas sur-pénaliser un VF mal taggé.
     */
    private fun computeLanguagePenalty(nameLower: String): Int {
        // VF explicite ou mention "français" → 0
        if (nameLower.contains(Regex("\\b(vff|vfq|vfi|vf|fr|french|francais|français)\\b"))) {
            // Mais attention : "VOSTFR" contient "FR" — on doit checker VOSTFR AVANT.
            if (nameLower.contains(Regex("\\b(vostfr|vost|sub|subbed)\\b"))) {
                return 100  // VOSTFR — sub français mais pas du VF
            }
            return 0  // VF pur
        }
        // VOSTFR sans VF explicite
        if (nameLower.contains(Regex("\\b(vostfr|vost|sub|subbed)\\b"))) {
            return 100
        }
        // VO / original / multi / langues étrangères
        if (nameLower.contains(Regex("\\b(vo|raw|multi|eng|english|spa|ita|german|deu|jap)\\b"))) {
            return 150
        }
        // Pas de marker langue → assume VF (cas typique des IPTV/Sport, et
        // de pas mal de providers qui ne tagguent pas explicitement)
        return 0
    }

    /**
     * Extrait un mot-clé probable depuis le nom du server pour fallback du
     * lookup tracker.
     *
     *  Patterns reconnus :
     *  - `"Filemoon (Link 1)"` → `"Filemoon"`
     *  - `"VOE (VF - HD)"` → `"VOE"`
     *  - `"Movix — Voe (DEFAULT - HD)"` → `"Voe"`  ← CRITIQUE
     *  - `"Nakios — Filemoon - FileMon Vidéo 10 (VF)"` → `"Filemoon"`  ← CRITIQUE
     *  - `"Sport Live — WiTV — Canal+ Sport"` → `"WiTV"`
     *
     *  Pour les patterns "Wrapper — Real" : c'est CRITIQUE car Cloudstream
     *  wrappe Voe via Kakaflix (URL pointe sur Kakaflix, mais le serveur
     *  s'appelle "Movix — Voe"). Sans ce fix, identifyServiceName retourne
     *  "Kakaflix" (bias 0) et Voe ne profite pas de son +25 → VidMoLy/Vidzy
     *  remontent injustement.
     *
     *  Renvoie null si on peut pas inférer.
     */
    private fun extractKeywordFromServerName(name: String): String? {
        // 2026-05-09 : pattern "Wrapper — Real" (em-dash unicode) ou
        // "Wrapper - Real" (hyphen). Si présent, prends le mot APRÈS le tiret.
        // Ex: "Movix — Voe (DEFAULT - HD)" → après le 1er " — " : "Voe (DEFAULT - HD)"
        // → puis on extrait le 1er mot : "Voe"
        val emDashSplit = name.split(Regex("\\s[—–-]\\s"), limit = 2)
        val candidate = if (emDashSplit.size == 2) emDashSplit[1] else name

        val first = candidate.split(Regex("[\\s\\-(\\[]"), limit = 2).firstOrNull()
            ?.takeIf { it.length >= 3 }
            ?: return null
        // Normalise première lettre majuscule (le tracker stocke "Filemoon"
        // pas "filemoon" parce qu'Extractor.name est CamelCase).
        return first.replaceFirstChar { it.uppercase() }
    }
}
