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

    // 2026-05-21 (user "VOE marche très bien mais se fait squeezer par Doodstream") :
    //   un serveur PROUVÉ fonctionnel (a déjà des mesures d'extraction réussies +
    //   zéro échec récent) doit passer AU-DESSUS d'un serveur jamais testé. Sans
    //   ça, la latence mesurée d'un bon serveur (ex VOE ~2.5s → bias -5) le faisait
    //   tomber sous un serveur neutre jamais mesuré (bias 0). Le bonus garantit
    //   "prouvé qui marche > inconnu".
    private const val PROVEN_BONUS = 15
    // 2026-06-29 (REPAIR — user) : gros bonus pour les serveurs VÉRIFIÉS (étoile
    //   verte = a déjà joué sur CE titre) → remontent en haut, juste sous les favoris.
    private const val VERIFIED_BONUS = 300
    // 2026-06-29 (REPAIR — user "remonter les serveurs étoiles dans le picker") :
    //   les serveurs natifs marqués d'une ★ (Frembed Premium/Free VF + hosters
    //   natifs résolus) remontent en haut, juste sous favoris/vérifiés.
    private const val STAR_BONUS = 250
    // 2026-06-29 (REPAIR — user "ils n'ont pas bougé") : les ★ Frembed VIP
    //   (Premium/Free VF) sont des m3u8 senpai SANS extracteur connu → ils
    //   tombaient sur le return 50 anticipé AVANT le bonus ★. Score de base élevé
    //   appliqué tout en haut (juste sous les favoris) pour les remonter vraiment.
    private const val STAR_BASE_SCORE = 450

    // 2026-05-27 : bonus qualité vidéo (user "les serveurs 1080p/720p en premier").
    // Ajouté au score pour que les hautes résolutions remontent dans le picker.
    // Les noms de serveurs contiennent souvent "1080p", "720p", "4K", "HD", etc.
    private const val QUALITY_4K = 30
    private const val QUALITY_1080P = 20
    private const val QUALITY_720P = 10
    // 480p/SD/sans mention = 0 (pas de bonus)

    // 2026-05-21 : Papadustream natif → score fixe -100 (early return dans computeScore).
    // Ses sources captcha Cloudflare passent TOUJOURS en dernier (juste au-dessus
    // des ISP-blocked à -200). Tout backup sain passe devant.

    /** État de fiabilité d'un serveur, pour l'affichage couleur du picker et le
     *  filtrage de la 2ᵉ passe (on ne retente que VERIFIED/UNSURE/UNTESTED, jamais DEAD).
     *  VERIFIED=vert, DEAD=rouge, UNSURE=orange, UNTESTED=blanc. */
    enum class ServerStatus { VERIFIED, DEAD, UNSURE, UNTESTED }

    // 2026-05-24 : liste noire ISP SUPPRIMÉE. Aucun serveur n'est bloqué
    // a priori — si ça marche pas, le fallback passe au suivant (onPlayerError).
    // Pas besoin de deviner qui est bloqué ou pas.
    private val FRENCH_ISP_BLOCKED_PATTERNS = emptyList<String>()

    // 2026-07-05 : domaines Netu/WAAW pour le fast-path du filtre disabled.
    //   Quand disabled = {"netu"} seul (= FORCE_DISABLED, cas standard), on filtre
    //   par simple check de domaine URL au lieu de résoudre le nom d'extracteur
    //   (identifyServiceName = itère 100+ extracteurs, lazy-lock SYNCHRONIZED,
    //   bloque 5s+ après un restart froid du Chromecast).
    private val NETU_HOSTS = listOf(
        "waaw1.tv", "netu.tv", "hqq.tv", "waaw.tv",
        "netu.ac", "hqq.ac", "waaw.ac", "waaw.to",
        "younetu.org", "netu.frembed.bond"
    )
    private val NETU_HOSTS_SET = setOf("netu")

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
        "kokoflix" to 8,
        "redirectproxy" to 8,
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

        // ─── (netu retiré 2026-05-24 — fonctionne normalement) ───
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
        val _rs = System.currentTimeMillis()
        Log.d("RANK", "ENTER n=${servers.size} thread=${Thread.currentThread().name}")
        if (servers.size <= 1) return servers

        // 2026-05-27 : filtre les extracteurs désactivés manuellement par l'user.
        // 2026-07-05 : OPTIM — resolveExtractorName() itère 80+ extracteurs par serveur
        //   (coûte 30-4500ms sous charge CPU) et n'est nécessaire QUE si l'user a désactivé
        //   des extracteurs PERSONNALISÉS. Quand disabled = FORCE_DISABLED seul (= "netu"),
        //   on filtre par simple check de domaine URL → O(1) par serveur, zéro contention.
        Log.d("RANK", "A avant getDisabled ${System.currentTimeMillis()-_rs}ms")
        val disabled = ExtractorToggleStore.getDisabled()
        Log.d("RANK", "B après getDisabled=${disabled.size} ${System.currentTimeMillis()-_rs}ms")
        val filtered = if (disabled.isEmpty()) servers else {
            val userDisabled = disabled - NETU_HOSTS_SET  // domaines netu = gérés par URL
            val hasNetuDisabled = disabled.contains("netu")
            Log.d("RANK", "C userDisabled=${userDisabled.size} hasNetu=$hasNetuDisabled ${System.currentTimeMillis()-_rs}ms")
            if (userDisabled.isEmpty() && hasNetuDisabled) {
                // Fast path : seul "netu" est disabled → filtre par domaine URL (quasi-gratuit)
                Log.d("RANK", "D FAST-PATH netu-only")
                servers.filter { server ->
                    val host = try { java.net.URI(server.src).host?.lowercase() ?: "" } catch (_: Exception) { "" }
                    !NETU_HOSTS.any { host.contains(it) }
                }
            } else if (disabled.isNotEmpty()) {
                // Slow path : l'user a des extracteurs custom désactivés → résolution complète
                Log.d("RANK", "D SLOW-PATH resolveExtractorName")
                servers.filter { server ->
                    val extName = resolveExtractorName(server)?.lowercase()
                    extName == null || extName !in disabled  // inconnu = on garde (sécurité)
                }
            } else servers
        }
        Log.d("RANK", "E après filtre filtered=${filtered.size} ${System.currentTimeMillis()-_rs}ms")
        if (filtered.isEmpty()) return servers  // sécurité : si tout filtré, on garde tout

        // 2026-07-04 (user "qualité, cœur, langue ça suffit" + "un serveur qui arrive en 0,5s
        //   doit s'afficher en 0,5s, il y a un blocage qui retient l'arrivée"). Tri ULTRA-LÉGER.
        //   AVANT : par serveur on appelait UserPreferences.currentProvider (itère Provider.
        //   providers), ExtractorToggleStore.isFavorite (getStringSet prefs), resolveExtractorName
        //   (identifyServiceName = boucle sur TOUS les extracteurs). × N serveurs × re-tri à
        //   CHAQUE lot → 4-10s par lot pendant que le registre tourne → serveurs en bloc.
        //   MAINTENANT : on lit currentProvider + favoris UNE SEULE FOIS ; on ne résout le nom
        //   d'extracteur QUE si l'user a des favoris (rare). Sans favori → score = qualité pure
        //   (match de chaîne) = quasi-gratuit, aucune contention.
        val currentProviderName = UserPreferences.currentProvider?.name ?: ""
        val favorites = if (currentProviderName.isNotEmpty())
            ExtractorToggleStore.getFavorites(currentProviderName) else emptySet()

        val scored = filtered.map { server -> ScoredServer(server, computeScore(server, favorites)) }

        // Sort par score décroissant. `sortedByDescending` est stable → ordre d'origine préservé
        //   pour les égalités (donc l'ordre d'arrivée des serveurs à qualité égale).
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
    // 2026-07-04 — VERSION LÉGÈRE (user "qualité, cœur, langue VF/VOSTFR/VO, ça suffit").
    //   AUCUNE lecture de stats d'extracteurs (santé/échecs/latence/vitesse/vérifié) → zéro
    //   contention avec le registre (qui les ÉCRIT en probant 20 sources). Score =
    //     • cœur (favori) → supplante tout
    //     • sinon Papadustream natif (captcha CF) → tout en bas
    //     • sinon QUALITÉ (4K > 1080 > 720 > …)
    //   La LANGUE (VF > VOSTFR > VO) est appliquée par orderByFrenchBuckets par-dessus.
    //   Le marquage ROUGE/VERT d'un serveur (échec/vérifié) reste géré à l'AFFICHAGE via
    //   statusOf()/TitleServerStatus (map mémoire par titre), indépendamment de ce tri.
    private fun computeScore(server: Video.Server, favorites: Set<String>): Int {
        val nameLower = server.name.lowercase()
        val srcLower = server.src.lowercase()
        // 2026-07-04 (user "il devait y avoir des passes de qualité, et les qualités devaient
        //   remonter au classement, pourquoi ça le fait plus") : la qualité PROBÉE (server.quality,
        //   remplie par QualityProbe passe 1/2/3) PRIME sur le nom. Avant je ne lisais que le nom
        //   → les qualités probées (4K/1080/720) ne remontaient plus. On concatène quality+nom.
        val qualityStr = server.quality?.lowercase().orEmpty() + " " + nameLower

        // Cœur : SEULEMENT si l'user a défini des favoris (sinon on NE résout PAS le nom
        //   d'extracteur = coûteux : identifyServiceName boucle sur tous les extracteurs).
        // 2026-07-11 : clé LANGUE-AWARE ("vidmoly:vf" ≠ "vidmoly:vostfr") pour ne pas
        //   contaminer le VOSTFR quand on heart le VF.
        if (favorites.isNotEmpty()) {
            val fk = favKeyFor(server)
            if (fk in favorites) return ExtractorToggleStore.FAVORITE_BONUS + computeQualityBonus(qualityStr)
        }

        // Papadustream natif (captcha Cloudflare Turnstile) → dernier recours (check de nom, gratuit).
        val isCaptchaGated = srcLower.contains("papadustream") || srcLower.contains("#xf=")
        if (isCaptchaGated) return -100

        // Sinon : la qualité (probée en priorité, sinon nom).
        return computeQualityBonus(qualityStr)
    }

    /**
     * Détecte la qualité vidéo depuis le nom du serveur et retourne un bonus.
     * Les serveurs hautes résolutions remontent dans le picker.
     *
     *  Détection (ordre de priorité) :
     *   - "4k" / "2160p" / "uhd"  → +30
     *   - "1080p" / "fhd"         → +20
     *   - "720p" / "hd" (seul)    → +10
     *   - "480p" / "sd" / rien    → 0
     *
     *  Edge case : "HD" seul matche 720p (+10), mais "FHD" matche 1080p (+20).
     *  "HD" dans "FHD" ou "UHD" ne matche PAS via le word-boundary \b.
     */
    private fun computeQualityBonus(nameLower: String): Int {
        // 4K / UHD en premier (priorité max)
        if (nameLower.contains("4k") || nameLower.contains("2160p") || nameLower.contains("uhd")) {
            return QUALITY_4K
        }
        // 1080p / Full HD
        if (nameLower.contains("1080p") || nameLower.contains("1080") || nameLower.contains("fhd")) {
            return QUALITY_1080P
        }
        // 720p / HD (attention : "hd" seul, pas dans "fhd"/"uhd"/"vhd")
        if (nameLower.contains("720p") || nameLower.contains("720")) {
            return QUALITY_720P
        }
        // "HD" seul comme mot (pas dans FHD/UHD) → 720p
        if (nameLower.contains(Regex("\\bhd\\b"))) {
            return QUALITY_720P
        }
        // Pas de mention de qualité ou 480p/SD → pas de bonus
        return 0
    }

    // ─── 2026-07-11 : clé favori LANGUE-AWARE ──────────────────────────
    // Avant, les favoris étaient stockés par nom d'extracteur seul ("vidmoly")
    // → heartVF contaminait VOSTFR (même extracteur). Maintenant la clé =
    // "extracteur:langue" (ex "vidmoly:vf", "vidmoly:vostfr").

    /** Détecte la langue d'un serveur depuis son nom : "vf", "vostfr", "vo" ou "unknown". */
    fun serverLangBucket(serverName: String): String {
        val n = serverName.lowercase()
        return when {
            n.contains("vostfr") || n.contains("sous-titr") -> "vostfr"
            Regex("""\b(vf|vff|vfq|vfi)\b""").containsMatchIn(n)
                || n.contains("(vf)") -> "vf"
            Regex("""(^|[^a-z])vo([^a-z]|${'$'})""").containsMatchIn(n)
                || n.contains(Regex("\\b(raw|eng|english|spa|ita|german|deu|jap)\\b")) -> "vo"
            else -> "unknown"
        }
    }

    /** Construit la clé favori langue-aware pour un serveur.
     *  2026-07-16 : inclut le PRÉFIXE source (avant le ·) dans la clé pour que
     *  « AniCloud · Filemoon (VOSTFR) » ≠ « Filemoon (VOSTFR) » ≠ « Movix · Filemoon (VOSTFR) ».
     *  Avant, tous les serveurs du même extracteur partageaient la même clé →
     *  favoriser un Filemoon les favorisait TOUS. */
    fun favKeyFor(server: Video.Server): String {
        val parts = server.name.split(Regex("\\s[—–·-]\\s"), limit = 2)
        val wrapperPrefix = if (parts.size == 2) parts[0].trim().lowercase() + "·" else ""
        val extName = (resolveExtractorName(server) ?: server.name).lowercase()
        val lang = serverLangBucket(server.name)
        return "$wrapperPrefix$extName:$lang"
    }

    /** Construit la clé favori langue-aware depuis un nom de serveur. */
    fun favKeyFor(serverName: String): String {
        val parts = serverName.split(Regex("\\s[—–·-]\\s"), limit = 2)
        val wrapperPrefix = if (parts.size == 2) parts[0].trim().lowercase() + "·" else ""
        val extName = (resolveExtractorName(Video.Server(id = "", name = serverName))
            ?: serverName).lowercase()
        val lang = serverLangBucket(serverName)
        return "$wrapperPrefix$extName:$lang"
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
        // 2026-05-22 : pénalité MASSIVE (user "mets -1000000 dessus, je veux
        // pas les voir en premier"). Les sources étrangères ne doivent JAMAIS
        // passer avant une source FR, même un backup lent ou jamais testé.
        if (nameLower.contains(Regex("\\b(vo|raw|multi|eng|english|spa|ita|german|deu|jap)\\b"))) {
            return 1_000_000
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
        val emDashSplit = name.split(Regex("\\s[—–·-]\\s"), limit = 2)
        val candidate = if (emDashSplit.size == 2) emDashSplit[1] else name

        val first = candidate.split(Regex("[\\s\\-(\\[]"), limit = 2).firstOrNull()
            ?.takeIf { it.length >= 3 }
            ?: return null
        // Normalise première lettre majuscule (le tracker stocke "Filemoon"
        // pas "filemoon" parce qu'Extractor.name est CamelCase).
        return first.replaceFirstChar { it.uppercase() }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 2026-05-21 : STATUT serveur (rouge/orange/blanc) pour le picker + 2ᵉ passe.
    // ─────────────────────────────────────────────────────────────────────

    // Cache court (2 s) des entrées d'échec pour éviter de re-parser le JSON à
    // chaque ligne du picker (≈50 binds).
    @Volatile private var cachedFailureEntries: Map<String, ExtractorFailureTracker.FailureEntry> = emptyMap()
    @Volatile private var cachedFailureEntriesAtMs = 0L
    private fun failureEntriesCached(): Map<String, ExtractorFailureTracker.FailureEntry> {
        val now = System.currentTimeMillis()
        if (now - cachedFailureEntriesAtMs > 2_000L) {
            cachedFailureEntries = ExtractorFailureTracker.getFailures().associateBy { it.name }
            cachedFailureEntriesAtMs = now
        }
        return cachedFailureEntries
    }

    /** Résout le nom d'extracteur d'un serveur (même logique que computeScore). */
    fun resolveExtractorName(server: Video.Server): String? {
        val nameBased = extractKeywordFromServerName(server.name)
        val urlBased = if (server.src.isNotBlank()) Extractor.identifyServiceName(server.src) else null
        val isWrapped = server.name.contains(Regex("\\s[—–·-]\\s"))
        return when {
            isWrapped && nameBased != null -> nameBased
            urlBased != null -> urlBased
            nameBased != null -> nameBased
            else -> null
        }
    }

    // Types d'échec qui NE comptent PAS comme une vraie tentative de lecture ratée :
    //  - "embed-head-failed" = soupçon du scan HEAD de fond (pas un vrai essai)
    //  - "dead-content"      = URL morte côté contenu, pas la faute de l'extracteur
    private val SOFT_FAILURE_TYPES = setOf("embed-head-failed", "dead-content")
    // Latence moyenne au-delà de laquelle l'extracteur est "lent" → orange (marche mais rame).
    private const val SLOW_LATENCY_MS = 3_000L

    /**
     * Statut de fiabilité, calé sur le modèle user (2026-05-21) :
     *  - DEAD (rouge)     : NE MARCHE PAS — FAI-bloqué, cassé (<5 min), OU au moins
     *                       une VRAIE tentative de lecture a échoué (404/DNS/timeout/
     *                       connexion/parsing…). 1 vrai échec suffit (plus besoin de 3).
     *  - UNSURE (orange)  : LENT ou PAS SÛR — lent à l'extraction, OU seulement
     *                       soupçonné par le scan de fond (jamais essayé pour de vrai).
     *  - VERIFIED (vert)  : déjà extrait avec succès + aucun échec courant → ça marche.
     *  - UNTESTED (blanc) : aucun signal, jamais essayé.
     *
     * Basé sur le NOM (le picker n'a pas l'URL) : health/échecs sont indexés par nom.
     */
    fun statusOf(server: Video.Server): ServerStatus {
        val nameLower = server.name.lowercase()
        val srcLower = server.src.lowercase()

        // ─── PAR TITRE d'abord (2026-05-21, user "lié à l'épisode/film, pas de bave
        //     sur les autres") : résultat RÉEL observé pour CE titre. Prioritaire.
        //       VERIFIED = a joué (READY) ici · DEAD = a échoué ici · UNSURE = douteux ici.
        //     Un échec sur l'épisode 1 ne colore PAS l'épisode 2 (clé = titre courant).
        //     Le résultat réel PRIME SUR TOUT — y compris la liste ISP-blocked (le serveur
        //     a peut-être été débloqué ou l'user est sur VPN).
        TitleServerStatus.statusOf(server.id)?.let { return it }

        // ─── ISP-blocked comme signal FAIBLE (orange) — plus rouge inconditionnel.
        //     Si le serveur a réellement joué (VERIFIED ci-dessus), il est vert.
        //     Sinon, on le marque orange = "probablement bloqué, pas sûr" au lieu de
        //     rouge = "mort certain". L'user peut quand même le tenter.
        if (FRENCH_ISP_BLOCKED_PATTERNS.any { nameLower.contains(it) || srcLower.contains(it) }) {
            return ServerStatus.UNSURE
        }

        // ─── Pas encore essayé sur CE titre → indice FAIBLE, sans mentir "ça marche".
        //     On ne réutilise PLUS les échecs/succès globaux comme couleur (ça bavait
        //     entre titres + le vert global mentait : UQLOAD extrait parfois une URL
        //     morte). Seul indice gardé : extracteur globalement LENT → orange ; sinon
        //     blanc (= pas vérifié pour cette source-ci).
        val extractorName = resolveExtractorName(server) ?: return ServerStatus.UNTESTED
        val avgMs = ExtractorLatencyTracker.getAvgMs(extractorName)
        return if (avgMs != null && avgMs >= SLOW_LATENCY_MS) ServerStatus.UNSURE else ServerStatus.UNTESTED
    }
}
