package com.streamflixreborn.streamflix.utils

import android.os.Build
import android.util.Log
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.extractors.Extractor

/**
 * 2026-07-13 — Rapport AUTOMATIQUE des sources cassées vers GitHub.
 *
 * But (user) : « quand un backup/provider échoue parce que l'URL a changé, je veux recevoir UNE
 *   notification GitHub pour réparer facilement sans chercher — sur tout, sans spam, 1 fois par URL ».
 *
 * Fonctionnement : branché sur l'échec d'extraction (Extractor.extract). On ne signale QUE les
 *   erreurs qui veulent dire « le domaine / l'URL a changé » — dns-fail (domaine ne résout plus),
 *   connect-fail, ssl-fail, 404 (chemin changé), parsing (HTML changé). PAS les timeouts (transitoires).
 *   Dédup STRICTE : une seule issue par couple `source|domaine` (persistée en SharedPreferences) →
 *   jamais de spam. Réutilise le token/repo déjà configurés (BuildConfig.GITHUB_CRASH_*, celui de
 *   CrashActivity qui écrit sur xdata-mix/onyx-crash-reports).
 */
object BrokenSourceReporter {

    private const val TAG = "BrokenSourceReporter"
    private const val PREFS = "broken_source_reports"
    private const val KEY_REPORTED = "reported_source_hosts"

    /** Erreurs VRAIMENT actionnables = « l'URL/le domaine a changé » : domaine mort (dns/connect/
     *  ssl) ou HTML/JSON changé (parsing). On EXCLUT : timeout & other (transitoires) et 404
     *  (= le plus souvent CETTE vidéo supprimée, pas un changement d'URL → sinon faux positifs). */
    private val URL_CHANGED_TYPES = setOf("dns-fail", "connect-fail", "ssl-fail", "parsing")

    /**
     * Appelé à chaque échec d'extraction. No-op si : toggle off / erreur transitoire /
     * déjà signalé pour ce (source, domaine). Le POST réseau se fait en tâche de fond.
     */
    fun maybeReport(sourceName: String, url: String, error: Throwable?, providerName: String?) {
        try {
            if (!UserPreferences.reportBrokenSources) return
            if (error == null) return
            val errorType = Extractor.classifyError(error)
            if (errorType !in URL_CHANGED_TYPES) return
            val host = hostOf(url) ?: return
            val dedupKey = "$sourceName|$host"

            val prefs = StreamFlixApp.instance.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            val reported = prefs.getStringSet(KEY_REPORTED, emptySet()) ?: emptySet()
            if (dedupKey in reported) return
            // Marque AVANT le POST (évite un double si deux échecs simultanés sur le même host).
            prefs.edit().putStringSet(KEY_REPORTED, reported + dedupKey).apply()

            Thread {
                val ok = runCatching { postIssue(sourceName, host, errorType, url, providerName) }
                    .getOrDefault(false)
                if (!ok) {
                    // Échec réseau/API → on retire la clé pour pouvoir retenter plus tard.
                    val cur = prefs.getStringSet(KEY_REPORTED, emptySet()) ?: emptySet()
                    prefs.edit().putStringSet(KEY_REPORTED, cur - dedupKey).apply()
                    Log.w(TAG, "report KO pour $dedupKey (retry possible plus tard)")
                } else {
                    Log.d(TAG, "source cassée signalée : $dedupKey ($errorType)")
                }
            }.apply { isDaemon = true }.start()
        } catch (e: Exception) {
            Log.w(TAG, "maybeReport exception: ${e.message}")
        }
    }

    /** Domaine du provider LUI-MÊME mort/déménagé (dns/connect/ssl sur sa base URL). Distinct des
     *  sources : ici c'est le catalogue/API du provider (Movix, Cloudstream…) qui ne répond plus. */
    private val PROVIDER_DEAD_TYPES = setOf("dns-fail", "connect-fail", "ssl-fail")

    fun maybeReportProvider(providerName: String, url: String, error: Throwable?) {
        try {
            if (!UserPreferences.reportBrokenSources) return
            if (error == null) return
            val errorType = Extractor.classifyError(error)
            if (errorType !in PROVIDER_DEAD_TYPES) return
            val host = hostOf(url) ?: return
            val dedupKey = "provider|$providerName|$host"
            val prefs = StreamFlixApp.instance.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            val reported = prefs.getStringSet(KEY_REPORTED, emptySet()) ?: emptySet()
            if (dedupKey in reported) return
            prefs.edit().putStringSet(KEY_REPORTED, reported + dedupKey).apply()
            Thread {
                val ok = runCatching { postProviderIssue(providerName, host, errorType, url) }.getOrDefault(false)
                if (!ok) {
                    val cur = prefs.getStringSet(KEY_REPORTED, emptySet()) ?: emptySet()
                    prefs.edit().putStringSet(KEY_REPORTED, cur - dedupKey).apply()
                } else Log.d(TAG, "provider cassé signalé : $providerName ($host)")
            }.apply { isDaemon = true }.start()
        } catch (e: Exception) { Log.w(TAG, "maybeReportProvider: ${e.message}") }
    }

    /**
     * 2026-07-16 : signalement MANUEL par un bêtatesteur — « ce serveur joue le mauvais contenu »
     * (mauvaise saison/épisode/langue) pour le titre demandé. Envoyé au MÊME repo que d'habitude
     * (onyx-crash-reports). Toujours actif (action explicite, indépendant du toggle auto-report).
     * Dédup sur (serveur + titre + SxEy) pour ne pas dupliquer si plusieurs testeurs signalent pareil.
     * `onDone` est rappelé sur un thread daemon (l'UI doit reposter sur le main pour un toast).
     */
    fun reportBadMatch(
        serverName: String,
        sourceLabel: String?,
        resolvedUrl: String?,
        contentTitle: String,
        season: Int,
        episode: Int,
        onDone: ((Boolean) -> Unit)? = null,
    ) {
        try {
            val token = try { BuildConfig.GITHUB_CRASH_TOKEN } catch (_: Throwable) { "" }
            val repo = try { BuildConfig.GITHUB_CRASH_REPO } catch (_: Throwable) { "" }
            if (token.isBlank() || repo.isBlank()) { onDone?.invoke(false); return }
            val se = when {
                episode > 0 && season > 0 -> "S${season}E${episode}"
                season > 0 -> "S$season"
                episode > 0 -> "E$episode"
                else -> ""
            }
            val cleanTitle = contentTitle.ifBlank { "?" }
            val suffix = if (se.isNotBlank()) " $se" else ""
            val issueTitle = "[mauvais serveur] $serverName — $cleanTitle$suffix"

            // Dédup LOCALE : ce serveur+titre+épisode n'est signalé qu'UNE fois par appareil.
            val dedupKey = "badmatch|$serverName|$cleanTitle|$se"
            val prefs = StreamFlixApp.instance.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            val reported = prefs.getStringSet(KEY_REPORTED, emptySet()) ?: emptySet()
            if (dedupKey in reported) {
                Log.d(TAG, "reportBadMatch: déjà signalé localement ($issueTitle)")
                onDone?.invoke(true); return
            }
            prefs.edit().putStringSet(KEY_REPORTED, reported + dedupKey).apply()

            Thread {
                try {
                    val tokens = buildList { add(serverName); add(cleanTitle); if (se.isNotBlank()) add(se) }
                    if (githubTitleExists(repo, token, *tokens.toTypedArray())) {
                        Log.d(TAG, "reportBadMatch: déjà signalé sur GitHub ($issueTitle)")
                        onDone?.invoke(true); return@Thread
                    }
                    val host = resolvedUrl?.let { hostOf(it) } ?: "?"
                    val body = buildString {
                        appendLine("Signalé manuellement par un testeur : ce serveur joue le **mauvais contenu** (mauvaise saison/épisode/langue).")
                        appendLine()
                        appendLine("| Champ | Valeur |")
                        appendLine("|---|---|")
                        appendLine("| Contenu demandé | $cleanTitle$suffix |")
                        appendLine("| Serveur | $serverName |")
                        appendLine("| Source | ${sourceLabel ?: "?"} |")
                        appendLine("| Hôte | $host |")
                        appendLine("| URL résolue | `${resolvedUrl ?: "?"}` |")
                        appendLine("| App | ${runCatching { BuildConfig.VERSION_NAME }.getOrDefault("?")} |")
                        appendLine("| Appareil | ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE}) |")
                    }
                    val ok = runCatching { httpPostIssue(repo, token, issueTitle, body) }.getOrDefault(false)
                    if (!ok) {
                        // Échec réseau/API → on retire la clé locale pour pouvoir retenter plus tard.
                        val cur = prefs.getStringSet(KEY_REPORTED, emptySet()) ?: emptySet()
                        prefs.edit().putStringSet(KEY_REPORTED, cur - dedupKey).apply()
                    }
                    Log.d(TAG, "reportBadMatch: ${if (ok) "OK" else "KO"} $issueTitle")
                    onDone?.invoke(ok)
                } catch (e: Exception) {
                    val cur = prefs.getStringSet(KEY_REPORTED, emptySet()) ?: emptySet()
                    prefs.edit().putStringSet(KEY_REPORTED, cur - dedupKey).apply()
                    Log.w(TAG, "reportBadMatch thread: ${e.message}"); onDone?.invoke(false)
                }
            }.apply { isDaemon = true }.start()
        } catch (e: Exception) {
            Log.w(TAG, "reportBadMatch: ${e.message}"); onDone?.invoke(false)
        }
    }

    private fun postProviderIssue(provider: String, host: String, errorType: String, url: String): Boolean {
        val token = try { BuildConfig.GITHUB_CRASH_TOKEN } catch (_: Throwable) { "" }
        val repo = try { BuildConfig.GITHUB_CRASH_REPO } catch (_: Throwable) { "" }
        if (token.isNullOrBlank() || repo.isNullOrBlank()) return false
        // Dédup globale : issue "provider cassé" déjà présente pour ce provider ?
        if (githubTitleExists(repo, token, "provider cassé", provider)) {
            Log.d(TAG, "provider cassé déjà signalé : $provider → skip"); return true
        }
        val meaning = when (errorType) {
            "dns-fail" -> "Le domaine du provider ne résout plus (déménagé / mort)"
            "connect-fail" -> "Connexion refusée au domaine du provider"
            "ssl-fail" -> "Erreur SSL sur le domaine du provider"
            else -> errorType
        }
        val title = "[provider cassé] $provider ($host)"
        val body = buildString {
            append("## Provider à réparer (URL de base changée)\n\n")
            append("| Champ | Valeur |\n|---|---|\n")
            append("| Provider | **$provider** |\n")
            append("| Domaine | `$host` |\n")
            append("| Type d'erreur | `$errorType` |\n")
            append("| Signification | $meaning |\n")
            append("| URL exemple | `$url` |\n")
            append("| App | v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}) |\n")
            append("| Date | ${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss z", java.util.Locale.FRANCE).format(java.util.Date())} |\n")
            append("\n> Rapport automatique ONYX : le domaine/catalogue du provider ne répond plus. Signalé une seule fois.\n")
        }
        return httpPostIssue(repo, token, title, body)
    }

    /** POST générique d'une issue. */
    private fun httpPostIssue(repo: String, token: String, title: String, body: String): Boolean {
        val json = org.json.JSONObject().apply { put("title", title); put("body", body) }
        val conn = (java.net.URL("https://api.github.com/repos/$repo/issues").openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "token $token")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/vnd.github.v3+json")
            setRequestProperty("User-Agent", "ONYX-BrokenSourceReporter")
            doOutput = true; connectTimeout = 15_000; readTimeout = 15_000
        }
        conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        Log.d(TAG, "httpPostIssue: HTTP $code pour $title")
        return code in 200..299
    }

    /** Recherche générique : une issue dont le titre contient TOUS les tokens donnés existe-t-elle ? */
    private fun githubTitleExists(repo: String, token: String, vararg titleTokens: String): Boolean {
        return try {
            val q = "repo:$repo is:issue is:open in:title " + titleTokens.joinToString(" ") { "\"$it\"" }
            val apiUrl = "https://api.github.com/search/issues?per_page=5&q=" + java.net.URLEncoder.encode(q, "UTF-8")
            val conn = (java.net.URL(apiUrl).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "token $token")
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "ONYX-BrokenSourceReporter")
                connectTimeout = 15_000; readTimeout = 15_000
            }
            if (conn.responseCode !in 200..299) return false
            val items = org.json.JSONObject(conn.inputStream.bufferedReader().readText()).optJSONArray("items") ?: return false
            for (i in 0 until items.length()) {
                val t = items.getJSONObject(i).optString("title", "")
                if (titleTokens.all { t.contains(it, true) }) return true
            }
            false
        } catch (e: Exception) { Log.w(TAG, "githubTitleExists: ${e.message}"); false }
    }

    private fun hostOf(url: String): String? = try {
        java.net.URI(url).host?.removePrefix("www.")?.lowercase()?.ifBlank { null }
    } catch (_: Exception) {
        // Fallback parse manuel si URI throw (URLs bizarres)
        runCatching {
            url.substringAfter("://").substringBefore("/").substringBefore(":")
                .removePrefix("www.").lowercase().ifBlank { null }
        }.getOrNull()
    }

    /**
     * Dédup GLOBALE : cherche une issue existante (ouverte OU fermée) dont le titre contient
     * à la fois `source` et `(host)`. Vrai = déjà signalé → on ne recrée pas.
     * En cas d'erreur réseau/API on renvoie false (on préfère un doublon rare qu'un raté).
     */
    private fun issueAlreadyExists(repo: String, token: String, source: String, host: String): Boolean {
        return try {
            val q = "repo:$repo is:issue is:open in:title \"$source\" \"$host\""
            val apiUrl = "https://api.github.com/search/issues?per_page=5&q=" +
                java.net.URLEncoder.encode(q, "UTF-8")
            val conn = (java.net.URL(apiUrl).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "token $token")
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "ONYX-BrokenSourceReporter")
                connectTimeout = 15_000
                readTimeout = 15_000
            }
            val code = conn.responseCode
            if (code !in 200..299) return false
            val resp = conn.inputStream.bufferedReader().readText()
            val items = org.json.JSONObject(resp).optJSONArray("items") ?: return false
            // Vérif fine : un titre qui contient bien source ET (host).
            for (i in 0 until items.length()) {
                val t = items.getJSONObject(i).optString("title", "")
                if (t.contains(source, true) && t.contains("($host)", true)) return true
            }
            false
        } catch (e: Exception) {
            Log.w(TAG, "issueAlreadyExists error: ${e.message}")
            false
        }
    }

    private fun postIssue(
        source: String, host: String, errorType: String, sampleUrl: String, provider: String?
    ): Boolean {
        val token = try { BuildConfig.GITHUB_CRASH_TOKEN } catch (_: Throwable) { "" }
        val repo = try { BuildConfig.GITHUB_CRASH_REPO } catch (_: Throwable) { "" }
        if (token.isNullOrBlank() || repo.isNullOrBlank()) {
            Log.w(TAG, "postIssue: token/repo non configurés")
            return false
        }

        val meaning = when (errorType) {
            "dns-fail" -> "Le domaine ne résout plus (déménagé / mort)"
            "connect-fail" -> "Connexion refusée (serveur down ou domaine changé)"
            "ssl-fail" -> "Erreur SSL (certificat / domaine changé)"
            "404" -> "404 — le chemin / l'endpoint a changé"
            "parsing" -> "Parsing cassé — ils ont probablement changé leur HTML/JSON"
            else -> errorType
        }
        // Dédup GLOBALE (tous appareils) : avant de créer, on regarde si une issue existe DÉJÀ
        //   sur le repo pour ce source+domaine (le repo = source de vérité partagée). Si oui, on
        //   marque en local et on ne recrée pas → l'user ne reçoit qu'UNE notif pour tous ses appareils.
        if (issueAlreadyExists(repo, token, source, host)) {
            Log.d(TAG, "issue déjà existante sur $repo pour $source ($host) → skip (dédup globale)")
            return true // succès logique : déjà signalé quelque part
        }

        val title = "[source cassée] $source — $errorType ($host)"
        val body = buildString {
            append("## Source à réparer\n\n")
            append("| Champ | Valeur |\n|---|---|\n")
            append("| Source / extracteur | **$source** |\n")
            if (!provider.isNullOrBlank()) append("| Provider | $provider |\n")
            append("| Domaine | `$host` |\n")
            append("| Type d'erreur | `$errorType` |\n")
            append("| Signification | $meaning |\n")
            append("| URL exemple | `$sampleUrl` |\n")
            append("| App | v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}) |\n")
            append("| Appareil | ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}) |\n")
            append("| Date | ${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss z", java.util.Locale.FRANCE).format(java.util.Date())} |\n")
            append("\n> Rapport automatique ONYX (détection URL changée). Signalé une seule fois par source+domaine.\n")
        }

        val json = org.json.JSONObject().apply {
            put("title", title)
            put("body", body)
        }
        val apiUrl = "https://api.github.com/repos/$repo/issues"
        val conn = (java.net.URL(apiUrl).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "token $token")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/vnd.github.v3+json")
            setRequestProperty("User-Agent", "ONYX-BrokenSourceReporter")
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        Log.d(TAG, "postIssue: HTTP $code pour $title")
        return code in 200..299
    }

    // ════════════════════════════════════════════════════════════════════════
    //  DÉTECTION « EXTRACTEUR MORT » (ils ont changé leur schéma → échoue sur TOUT)
    //  Distinct du rapport de source : ici on cumule les échecs CONSÉCUTIFS d'un
    //  extracteur (hors dead-content) sur PLUSIEURS domaines/contenus → 1 issue.
    // ════════════════════════════════════════════════════════════════════════
    private const val PREFS_DEAD = "dead_extractor_detection"
    private const val KEY_DEAD_STATE = "state"
    /** Seuls ces types comptent comme « extracteur mort » (schéma cassé). Pas timeout/other/404. */
    private val DEAD_COUNT_TYPES = setOf("parsing", "dns-fail", "connect-fail", "ssl-fail")

    /** Config par catégorie de détection consécutive : titre d'issue, seuil, min domaines, sens. */
    private data class CatCfg(val tag: String, val threshold: Int, val minHosts: Int, val desc: String)
    private val CATS = mapOf(
        "dead"       to CatCfg("extracteur mort",   6, 3, "échoue systématiquement sur plusieurs contenus (schéma/JS/domaine changé)"),
        "blocked"    to CatCfg("bloqué",            5, 2, "refus 403 répétés — Cloudflare/anti-bot probablement ajouté (bypass à mettre à jour)"),
        "streamdead" to CatCfg("flux mort",         4, 0, "extrait une URL mais le flux final est mort (404/HEAD KO) → liens périmés"),
        "empty"      to CatCfg("casse silencieuse", 8, 0, "répond 200 OK mais 0 source (structure du site probablement changée)"),
    )

    /**
     * À appeler à CHAQUE issue d'extraction. success=true → reset TOUS les compteurs de l'extracteur.
     * failure → incrémente la bonne catégorie : dead (parsing/dns/connect/ssl) ou blocked (403).
     * timeout/other/404/dead-content = ignorés (transitoires / contenu spécifique).
     */
    fun noteExtractorOutcome(name: String, success: Boolean, error: Throwable? = null, url: String? = null) {
        if (!UserPreferences.reportBrokenSources) return
        if (success) { listOf("dead", "blocked", "streamdead").forEach { resetCat(it, name) }; return }
        val et = error?.let { Extractor.classifyError(it) } ?: "other"
        val host = url?.let { hostOf(it) }
        when {
            et in DEAD_COUNT_TYPES -> bumpCat("dead", name, host, et, null)
            et == "403" -> bumpCat("blocked", name, host, et, null)
        }
    }

    /** Flux extrait mais MORT (échec de la validation HEAD post-extraction). */
    fun noteStreamDead(name: String) {
        if (!UserPreferences.reportBrokenSources) return
        bumpCat("streamdead", name, null, "head-fail", null)
    }

    /**
     * Résultat d'un provider pour un titre. found=false (0 source) → incrémente la casse
     * silencieuse ; found=true → reset. On joint le TITRE recherché (aiguille la réparation).
     */
    fun noteProviderResult(providerName: String, found: Boolean, searchedTitle: String?) {
        if (!UserPreferences.reportBrokenSources) return
        if (found) resetCat("empty", providerName)
        else bumpCat("empty", providerName, null, "0-source", searchedTitle)
    }

    /** Compteur consécutif générique pour une catégorie. Reporte 1 fois au franchissement du seuil. */
    @Synchronized
    private fun bumpCat(cat: String, name: String, host: String?, errType: String, extra: String?) {
        try {
            val cfg = CATS[cat] ?: return
            val prefs = StreamFlixApp.instance.getSharedPreferences(PREFS_DEAD, android.content.Context.MODE_PRIVATE)
            val root = org.json.JSONObject(prefs.getString(KEY_DEAD_STATE, "{}") ?: "{}")
            val key = "$cat::$name"
            val entry = root.optJSONObject(key) ?: org.json.JSONObject().apply {
                put("count", 0); put("hosts", org.json.JSONArray()); put("errors", org.json.JSONObject())
                put("extras", org.json.JSONArray()); put("reported", false)
            }
            entry.put("count", entry.optInt("count") + 1)
            if (host != null) {
                val hosts = entry.optJSONArray("hosts") ?: org.json.JSONArray()
                var seen = false; for (i in 0 until hosts.length()) if (hosts.optString(i) == host) { seen = true; break }
                if (!seen) hosts.put(host); entry.put("hosts", hosts)
            }
            val errors = entry.optJSONObject("errors") ?: org.json.JSONObject()
            errors.put(errType, errors.optInt(errType) + 1); entry.put("errors", errors)
            if (!extra.isNullOrBlank()) {
                val extras = entry.optJSONArray("extras") ?: org.json.JSONArray()
                var seen = false; for (i in 0 until extras.length()) if (extras.optString(i) == extra) { seen = true; break }
                if (!seen && extras.length() < 8) extras.put(extra); entry.put("extras", extras)
            }
            val hostsLen = entry.optJSONArray("hosts")?.length() ?: 0
            val crossed = entry.optInt("count") >= cfg.threshold && hostsLen >= cfg.minHosts
            root.put(key, entry)
            if (!entry.optBoolean("reported", false) && crossed) {
                entry.put("reported", true)
                prefs.edit().putString(KEY_DEAD_STATE, root.toString()).apply()
                val cnt = entry.optInt("count")
                val en = errors.names()
                val errSummary = if (en == null) "" else (0 until en.length()).joinToString(", ") { val k = en.getString(it); "$k×${errors.optInt(k)}" }
                val h2 = entry.optJSONArray("hosts") ?: org.json.JSONArray()
                val hostSummary = (0 until minOf(h2.length(), 6)).joinToString(", ") { h2.optString(it) }
                val x2 = entry.optJSONArray("extras") ?: org.json.JSONArray()
                val extraSummary = (0 until minOf(x2.length(), 8)).joinToString(", ") { x2.optString(it) }
                Thread {
                    val ok = runCatching { reportCategory(cfg, name, cnt, errSummary, hostSummary, extraSummary) }.getOrDefault(false)
                    if (!ok) {
                        val r2 = org.json.JSONObject(prefs.getString(KEY_DEAD_STATE, "{}") ?: "{}")
                        r2.optJSONObject(key)?.put("reported", false)
                        prefs.edit().putString(KEY_DEAD_STATE, r2.toString()).apply()
                    } else Log.d(TAG, "[${cfg.tag}] signalé : $name ($cnt)")
                }.apply { isDaemon = true }.start()
            } else {
                prefs.edit().putString(KEY_DEAD_STATE, root.toString()).apply()
            }
        } catch (e: Exception) { Log.w(TAG, "bumpCat($cat): ${e.message}") }
    }

    @Synchronized
    private fun resetCat(cat: String, name: String) {
        try {
            val prefs = StreamFlixApp.instance.getSharedPreferences(PREFS_DEAD, android.content.Context.MODE_PRIVATE)
            val root = org.json.JSONObject(prefs.getString(KEY_DEAD_STATE, "{}") ?: "{}")
            val key = "$cat::$name"
            if (root.has(key)) { root.remove(key); prefs.edit().putString(KEY_DEAD_STATE, root.toString()).apply() }
        } catch (_: Exception) {}
    }

    private fun reportCategory(cfg: CatCfg, name: String, count: Int, errSummary: String, hostSummary: String, extraSummary: String): Boolean {
        val token = try { BuildConfig.GITHUB_CRASH_TOKEN } catch (_: Throwable) { "" }
        val repo = try { BuildConfig.GITHUB_CRASH_REPO } catch (_: Throwable) { "" }
        if (token.isNullOrBlank() || repo.isNullOrBlank()) return false
        if (githubTitleExists(repo, token, cfg.tag, name)) { Log.d(TAG, "[${cfg.tag}] déjà signalé : $name → skip"); return true }
        val title = "[${cfg.tag}] $name"
        val body = buildString {
            append("## À réparer — ${cfg.tag}\n\n")
            append("| Champ | Valeur |\n|---|---|\n")
            append("| Cible | **$name** |\n")
            append("| Occurrences | $count (0 succès depuis) |\n")
            if (errSummary.isNotBlank()) append("| Types | $errSummary |\n")
            if (hostSummary.isNotBlank()) append("| Domaines | $hostSummary |\n")
            if (extraSummary.isNotBlank()) append("| Films concernés | $extraSummary |\n")
            append("| Signification | ${cfg.desc} |\n")
            append("| App | v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}) |\n")
            append("| Appareil | ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}) |\n")
            append("| Date | ${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss z", java.util.Locale.FRANCE).format(java.util.Date())} |\n")
            append("\n> Rapport automatique ONYX. Signalé une seule fois (tant que l'issue reste ouverte).\n")
        }
        return httpPostIssue(repo, token, title, body)
    }
}
