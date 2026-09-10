package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.streamflixreborn.streamflix.backup.BackupRestoreManager
import com.streamflixreborn.streamflix.backup.ProviderBackupContext
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.IptvSource
import com.streamflixreborn.streamflix.models.Profile
import com.streamflixreborn.streamflix.providers.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 2026-05-29 : Synchronisation appareil ↔ appareil via Cloudflare Worker + D1.
 *
 * L'émetteur sérialise TOUTES les données utilisateur en un seul JSON,
 * l'upload via POST /sync/send → reçoit un code 6 chars unique.
 * Le récepteur saisit le code, POST /sync/receive → récupère le payload,
 * et applique les données localement.
 *
 * Données synchronisées (v2 — exhaustif) :
 *   1. Profils (noms, emojis, PIN, admin, maxAge)
 *   2. Room DB POUR CHAQUE profil × provider (favoris, historique, reprise)
 *   3. Sources IPTV
 *   4. Favoris IPTV (par provider)
 *   5. Favoris saisons (par profil)
 *   6. Favoris épisodes (par profil)
 *   7. Extracteur toggles (désactivés + favoris)
 *   8. Préférences utilisateur (thème, provider, qualité, etc.)
 *   9. Filtres catalogue
 *  10. Historique de recherche (par profil)
 *  11. Continue-watching dismissed (par profil)
 *
 * Chaque transfert est identifié par son code. Pas de croisement possible
 * entre utilisateurs.
 */
object DeviceSyncManager {

    private const val TAG = "DeviceSync"

    /** 2026-09-10 (user : « j'aurai plus qu'à transférer le code de mon Oppo vers ma télé ») :
     *  les COMPTES sont désormais transférables. Ce sont les seuls fichiers concernés — ils
     *  contiennent des mots de passe, d'où le chiffrement ci-dessous : le Worker et D1 ne
     *  voient qu'un blob illisible, la clé n'existe que dans le code lu par l'utilisateur. */
    private val FICHIERS_COMPTES = listOf(
        "bowd_creds",
        "replay_auth_tf1_creds",
        "replay_auth_m6_creds",
        "replay_auth_bfm_creds",
    )

    /** Chiffrement AES-GCM, clé dérivée du secret par SHA-256. Sortie : base64(iv|chiffré). */
    private fun chiffrer(clair: String, secret: String): String {
        val cle = javax.crypto.spec.SecretKeySpec(
            java.security.MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()), "AES")
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, cle, javax.crypto.spec.GCMParameterSpec(128, iv))
        val chiffre = c.doFinal(clair.toByteArray())
        return android.util.Base64.encodeToString(iv + chiffre, android.util.Base64.NO_WRAP)
    }

    private fun dechiffrer(b64: String, secret: String): String? = try {
        val brut = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
        val cle = javax.crypto.spec.SecretKeySpec(
            java.security.MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()), "AES")
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.DECRYPT_MODE, cle,
            javax.crypto.spec.GCMParameterSpec(128, brut.copyOfRange(0, 12)))
        String(c.doFinal(brut.copyOfRange(12, brut.size)))
    } catch (t: Throwable) {
        Log.w(TAG, "déchiffrement des comptes impossible : ${t.message}")
        null
    }

    /** Secret aléatoire de 4 caractères, accolé au code du serveur et jamais transmis à lui. */
    private fun genererSecret(): String {
        val alpha = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  // sans I/O/0/1, illisibles à la télé
        val r = java.security.SecureRandom()
        return (1..4).map { alpha[r.nextInt(alpha.length)] }.joinToString("")
    }

    private fun collectComptes(context: Context, secret: String): String? {
        val tout = JSONObject()
        var n = 0
        for (nom in FICHIERS_COMPTES) {
            val p = context.getSharedPreferences(nom, Context.MODE_PRIVATE)
            if (p.all.isEmpty()) continue
            val o = JSONObject()
            p.all.forEach { (k, v) ->
                when (v) {
                    is String -> o.put(k, v)
                    is Long -> o.put(k, v)
                    is Boolean -> o.put(k, v)
                    is Int -> o.put(k, v)
                }
            }
            tout.put(nom, o); n++
        }
        if (n == 0) return null
        Log.d(TAG, "comptes collectés : $n service(s)")
        return chiffrer(tout.toString(), secret)
    }

    private fun applyComptes(context: Context, blob: String, secret: String) {
        val clair = dechiffrer(blob, secret) ?: return
        val tout = JSONObject(clair)
        var n = 0
        for (nom in tout.keys()) {
            if (nom !in FICHIERS_COMPTES) continue          // on n'écrit que nos fichiers
            val o = tout.optJSONObject(nom) ?: continue
            val e = context.getSharedPreferences(nom, Context.MODE_PRIVATE).edit()
            for (k in o.keys()) {
                when (val v = o.get(k)) {
                    is String -> e.putString(k, v)
                    is Boolean -> e.putBoolean(k, v)
                    is Int -> e.putLong(k, v.toLong())
                    is Long -> e.putLong(k, v)
                }
            }
            e.apply(); n++
        }
        Log.i(TAG, "comptes restaurés : $n service(s)")
    }
    private const val API_URL = "https://streamflix-api.logami61250.workers.dev"
    private val JSON_MEDIA = "application/json".toMediaType()

    // ── ENVOI ──────────────────────────────────────────────────────────

    /**
     * Sérialise toutes les données et les upload via le Worker API.
     * @return le code 6 chars si succès, null sinon.
     */
    suspend fun sendData(context: Context): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Le code est attribué par le Worker APRÈS l'envoi : il ne peut donc pas servir
            //   de clé. On tire un secret ici, on chiffre avec, et on l'accole au code —
            //   l'utilisateur lit une chaîne, le serveur n'en connaît que la moitié.
            val secret = genererSecret()
            val payload = collectAllData(context, secret)

            val body = JSONObject().apply {
                put("payload", payload.toString())
            }
            val request = Request.Builder()
                .url("$API_URL/sync/send")
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build()

            val response = NetworkClient.default.newCall(request).execute()
            val respBody = response.body?.string()
            response.close()

            if (!response.isSuccessful || respBody == null) {
                return@withContext Result.failure(
                    Exception("Erreur serveur: HTTP ${response.code}")
                )
            }

            val json = JSONObject(respBody)
            val code = json.optString("code", "")
            if (code.isBlank()) {
                return@withContext Result.failure(Exception("Code vide dans la réponse"))
            }

            val codeComplet = if (payload.has("comptesChiffres")) "$code-$secret" else code
            Log.d(TAG, "Data uploaded with code $code (${payload.toString().length} chars)")
            Result.success(codeComplet)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send data", e)
            Result.failure(e)
        }
    }

    // ── RÉCEPTION ──────────────────────────────────────────────────────

    /**
     * Télécharge les données depuis le Worker API avec le code donné et les applique.
     * @return true si succès.
     */
    suspend fun receiveData(context: Context, code: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // « ABC123-K7M9 » → code serveur + secret de déchiffrement. Sans tiret, c'est
            //   un transfert sans comptes (ou émis par une version antérieure) : rien à faire.
            val saisi = code.uppercase().replace(" ", "").trim()
            val normalizedCode = saisi.substringBefore("-")
            val secret = saisi.substringAfter("-", "").takeIf { it.isNotBlank() }

            val body = JSONObject().apply {
                put("code", normalizedCode)
            }
            val request = Request.Builder()
                .url("$API_URL/sync/receive")
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build()

            val response = NetworkClient.default.newCall(request).execute()
            val respBody = response.body?.string()
            response.close()

            if (!response.isSuccessful || respBody == null) {
                val errorMsg = when (response.code) {
                    404 -> "Code introuvable ou expiré"
                    410 -> "Code expiré"
                    else -> "Erreur serveur: HTTP ${response.code}"
                }
                return@withContext Result.failure(Exception(errorMsg))
            }

            val json = JSONObject(respBody)
            val payloadStr = json.optString("payload", "")
            if (payloadStr.isBlank()) {
                return@withContext Result.failure(Exception("Données vides"))
            }

            val payload = JSONObject(payloadStr)
            applyAllData(context, payload, secret)

            Log.d(TAG, "Data received and applied from code $normalizedCode")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to receive data", e)
            Result.failure(e)
        }
    }

    // ── SÉRIALISATION ──────────────────────────────────────────────────

    private suspend fun collectAllData(context: Context, secret: String?): JSONObject {
        val root = JSONObject()
        // v3 : ajout du bloc « comptes » (chiffré). Un récepteur plus ancien ignore
        //   simplement la clé qu'il ne connaît pas — la rétrocompatibilité est gratuite.
        root.put("syncVersion", 3)
        root.put("timestamp", System.currentTimeMillis())

        // 1. Profils (noms, emojis, PIN, admin, restrictions)
        val profiles = ProfileStore.getAll()
        val profilesArr = JSONArray()
        profiles.forEach { p ->
            profilesArr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("emoji", p.emoji)
                put("isAdmin", p.isAdmin)
                if (p.pinHash != null) put("pinHash", p.pinHash)
                if (p.maxAge != null) put("maxAge", p.maxAge)
            })
        }
        root.put("profiles", profilesArr)
        root.put("currentProfileId", ProfileStore.getCurrentProfileId() ?: Profile.DEFAULT_ID)

        // 2. Room DB — POUR CHAQUE profil × provider (favoris, historique, reprise)
        val profileIds = profiles.map { it.id }.ifEmpty { listOf(Profile.DEFAULT_ID) }
        val allRoomBackups = JSONObject()
        for (profileId in profileIds) {
            val backupManager = buildBackupManagerForProfile(profileId, context)
            val userData = backupManager.exportUserData()
            if (userData != null) {
                allRoomBackups.put(profileId, JSONObject(userData))
            }
        }
        root.put("roomBackups", allRoomBackups)

        // 3. Sources IPTV
        val iptvSources = IptvSourceStore.getAll()
        val iptvArr = JSONArray()
        iptvSources.forEach { s ->
            iptvArr.put(JSONObject().apply {
                put("id", s.id)
                put("type", s.type.name)
                put("name", s.name)
                put("url", s.url)
                if (s.mac != null) put("mac", s.mac)
                if (s.serial != null) put("serial", s.serial)
                if (s.userAgent != null) put("userAgent", s.userAgent)
                if (s.referer != null) put("referer", s.referer)
            })
        }
        root.put("iptvSources", iptvArr)

        // 4. Favoris IPTV
        val iptvFavs = collectIptvFavorites(context)
        root.put("iptvFavorites", iptvFavs)

        // 5. Favoris saisons (SharedPrefs brutes — couvre tous les profils)
        val seasonPrefs = context.getSharedPreferences("season_favorites", Context.MODE_PRIVATE)
        val seasonObj = JSONObject()
        seasonPrefs.all.forEach { (k, v) ->
            if (v is String) seasonObj.put(k, v)
        }
        root.put("seasonFavorites", seasonObj)

        // 6. Favoris épisodes (SharedPrefs brutes — couvre tous les profils)
        val episodeFavPrefs = context.getSharedPreferences("episode_favorites", Context.MODE_PRIVATE)
        val episodeFavObj = JSONObject()
        episodeFavPrefs.all.forEach { (k, v) ->
            if (v is String) episodeFavObj.put(k, v)
        }
        root.put("episodeFavorites", episodeFavObj)

        // 7. Extracteur toggles (désactivés + favoris)
        val extractorToggles = collectExtractorToggles(context)
        root.put("extractorToggles", extractorToggles)

        // 8. Préférences utilisateur (thème, provider, qualité, etc.)
        val prefs = collectUserPreferences(context)
        root.put("userPreferences", prefs)

        // 9. Filtre catalogue
        val catalogFilter = collectCatalogFilters(context)
        root.put("catalogFilters", catalogFilter)

        // 10. Historique de recherche (SharedPrefs brutes — couvre tous les profils)
        val searchPrefs = context.getSharedPreferences("search_history", Context.MODE_PRIVATE)
        val searchObj = JSONObject()
        searchPrefs.all.forEach { (k, v) ->
            if (v is String) searchObj.put(k, v)
        }
        root.put("searchHistory", searchObj)

        // 11. Continue-watching dismissed (SharedPrefs — couvre tous les profils)
        val dismissedPrefs = context.getSharedPreferences("continue_watching_dismissed", Context.MODE_PRIVATE)
        val dismissedObj = JSONObject()
        dismissedPrefs.all.forEach { (k, v) ->
            when (v) {
                is Set<*> -> dismissedObj.put(k, JSONArray(v.toList()))
                is String -> dismissedObj.put(k, v)
            }
        }
        root.put("continueWatchingDismissed", dismissedObj)

        // 12. Sources World Live (playlists custom ajoutées via le bouton +)
        //     2026-06-10 (user "il faut que ce soit transféré au moment du
        //     partage") : sync les sources perso + l'index active. Les BUILTIN
        //     sont déjà dans le code, pas besoin de les transférer.
        val wlSourcesPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        val wlSourcesObj = JSONObject()
        wlSourcesPrefs.getString("world_live_sources_list", null)?.let {
            wlSourcesObj.put("list", it)
        }
        if (wlSourcesPrefs.contains("world_live_sources_active_index")) {
            wlSourcesObj.put(
                "activeIndex",
                wlSourcesPrefs.getInt("world_live_sources_active_index", 0),
            )
        }
        if (wlSourcesObj.length() > 0) root.put("worldLiveSources", wlSourcesObj)
        // 13. Comptes (Bowd, TF1+, M6+, BFM) — CHIFFRÉS avec le secret du code.
        //     Ce sont des mots de passe : le Worker et D1 n'en voient qu'un blob.
        if (secret != null) {
            collectComptes(context, secret)?.let { root.put("comptesChiffres", it) }
        }


        return root
    }

    private fun collectIptvFavorites(context: Context): JSONObject {
        val result = JSONObject()
        val providerNames = listOf(
            "OlaTV", "VegetaTV", "Movix LiveTV", "Sport Live", "Mon IPTV"
        )
        providerNames.forEach { name ->
            val prefsName = "iptv_favorites_${name.lowercase().replace(" ", "_")}"
            val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            val all = prefs.all
            if (all.isNotEmpty()) {
                val obj = JSONObject()
                all.forEach { (k, v) ->
                    when (v) {
                        is Boolean -> obj.put(k, v)
                        is String -> obj.put(k, v)
                        is Set<*> -> obj.put(k, JSONArray(v.toList()))
                    }
                }
                result.put(prefsName, obj)
            }
        }
        return result
    }

    private fun collectExtractorToggles(context: Context): JSONObject {
        val result = JSONObject()
        val prefs = context.getSharedPreferences("extractor_toggles", Context.MODE_PRIVATE)
        val disabled = prefs.getStringSet("disabled_extractors", emptySet()) ?: emptySet()
        result.put("disabled", JSONArray(disabled.toList()))

        val allPrefs = prefs.all
        allPrefs.forEach { (k, v) ->
            if (k.startsWith("fav_") && v is Set<*>) {
                result.put(k, JSONArray(v.toList()))
            }
        }
        return result
    }

    private fun collectUserPreferences(context: Context): JSONObject {
        val result = JSONObject()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        val stringKeys = listOf(
            "selectedTheme", "quality", "subtitles_language",
            "pref_dns", "pref_custom_avatar_url",
            "theme_preference", "APP_LANGUAGE", "preferred_player",
            "p_doh_provider_url", "HLS_PROXY_URL",
            "TMDB_API_KEY", "SUBDL_API_KEY",
            "provider_url", "provider_portal_url",
            "provider_streamingcommunity_domain", "provider_cuevana_domain",
            "provider_poseidon_domain",
            "pref_iptv_language_filter",
            "PARENTAL_CONTROL_PIN", "PARENTAL_CONTROL_ADMIN_PIN",
            "PARENTAL_CONTROL_MAX_AGE"
        )
        val boolKeys = listOf(
            "global_search_enabled", "keep_screen_on_paused",
            "profile_picker_enabled",
            "AUTOPLAY", "FORCE_EXTRA_BUFFERING",
            "KEEP_SCREEN_ON_WHEN_PAUSED", "KEEP_SCREEN_ON_APP",
            "PROFILE_PICKER_ENABLED", "MINI_PLAYER_ENABLED",
            "ENABLE_TMDB", "provider_autoupdate",
            "pc_frenchstream_new_interface",
            "SERVER_AUTO_SUBTITLES_DISABLED",
            "UPDATE_CHECK_ENABLED"
        )
        stringKeys.forEach { key ->
            prefs.getString(key, null)?.let { result.put(key, it) }
        }
        boolKeys.forEach { key ->
            if (prefs.contains(key)) {
                try { result.put(key, prefs.getBoolean(key, false)) } catch (_: Exception) {}
            }
        }
        // SeekBarPreference stocke en Int, EditTextPreference stocke en String
        // → on lit chaque clé en essayant Int puis String (défensif)
        val intKeys = listOf("scale_x", "scale_y")
        intKeys.forEach { key ->
            if (prefs.contains(key)) {
                try { result.put(key, prefs.getInt(key, 0)) } catch (_: Exception) {
                    try { result.put(key, prefs.getString(key, "0")) } catch (_: Exception) {}
                }
            }
        }
        // EditTextPreference avec inputType=number → stocké en String
        val numericStringKeys = listOf("p_settings_autoplay_buffer")
        numericStringKeys.forEach { key ->
            prefs.getString(key, null)?.let { result.put(key, it) }
        }

        // Provider actuel par profil — les clés sont "CURRENT_PROVIDER_<profileId>"
        prefs.all.forEach { (k, v) ->
            if (k.startsWith("current_provider_") && v is String) {
                result.put(k, v)
            }
            if (k.startsWith("CURRENT_PROVIDER_") && v is String) {
                result.put(k, v)
            }
        }

        return result
    }

    private fun collectCatalogFilters(context: Context): JSONObject {
        val result = JSONObject()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.all.forEach { (k, v) ->
            if (k.startsWith("pref_catalog_filter_") && v is String) {
                result.put(k, v)
            }
        }
        return result
    }

    // ── DÉSÉRIALISATION / APPLICATION ──────────────────────────────────

    private suspend fun applyAllData(context: Context, payload: JSONObject, secret: String? = null) {
        // Comptes : uniquement si l'émetteur en a envoyé ET que le secret accompagne le code.
        if (secret != null) {
            payload.optString("comptesChiffres").takeIf { it.isNotBlank() }?.let {
                runCatching { applyComptes(context, it, secret) }
            }
        }

        // 1. Profils
        payload.optJSONArray("profiles")?.let { arr ->
            val importedProfiles = mutableListOf<Profile>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                importedProfiles.add(Profile(
                    id = obj.optString("id", Profile.DEFAULT_ID),
                    name = obj.optString("name", "Profil"),
                    emoji = obj.optString("emoji", "🎬"),
                    isAdmin = obj.optBoolean("isAdmin", false),
                    pinHash = obj.optString("pinHash", "").takeIf { it.isNotBlank() },
                    maxAge = if (obj.has("maxAge") && !obj.isNull("maxAge")) obj.getInt("maxAge") else null
                ))
            }
            if (importedProfiles.isNotEmpty()) {
                // Merge : on ajoute les profils manquants, on met à jour les existants
                val existingProfiles = ProfileStore.getAll().toMutableList()
                importedProfiles.forEach { imported ->
                    val idx = existingProfiles.indexOfFirst { it.id == imported.id }
                    if (idx >= 0) {
                        existingProfiles[idx] = imported // mise à jour (emoji, nom, etc.)
                    } else {
                        existingProfiles.add(imported)
                    }
                }
                // On sauve via upsert pour chaque profil
                existingProfiles.forEach { ProfileStore.upsert(it) }
            }
            // Restaurer le profil actif
            val currentId = payload.optString("currentProfileId", "")
            if (currentId.isNotBlank()) {
                ProfileStore.setCurrentProfileId(currentId)
            }
            Log.d(TAG, "Profiles imported: ${importedProfiles.size}")
        }

        // 2. Room DB — POUR CHAQUE profil × provider
        // v2 : multi-profil via "roomBackups"
        payload.optJSONObject("roomBackups")?.let { backups ->
            backups.keys().forEach { profileId ->
                val roomJson = backups.optJSONObject(profileId) ?: return@forEach
                val backupManager = buildBackupManagerForProfile(profileId, context)
                backupManager.importUserData(roomJson.toString())
                Log.d(TAG, "Room backup imported for profile $profileId")
            }
        }
        // v1 compat : ancien format "roomBackup" (profil unique = profil actif)
        if (!payload.has("roomBackups")) {
            payload.optJSONObject("roomBackup")?.let { roomJson ->
                val profileId = ProfileManager.currentProfileIdOrDefault()
                val backupManager = buildBackupManagerForProfile(profileId, context)
                backupManager.importUserData(roomJson.toString())
                Log.d(TAG, "Room backup imported (v1 compat, profile $profileId)")
            }
        }

        // 3. Sources IPTV (merge, pas de remplacement — on ajoute les nouvelles)
        payload.optJSONArray("iptvSources")?.let { arr ->
            val existing = IptvSourceStore.getAll().map { it.url to it.mac }.toSet()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val url = obj.optString("url", "")
                val mac = obj.optString("mac", null)
                if (existing.contains(url to mac)) continue

                val typeName = obj.optString("type", "M3U")
                val type = try {
                    IptvSource.Type.valueOf(typeName)
                } catch (_: Exception) {
                    IptvSource.Type.M3U
                }
                val source = IptvSource(
                    id = IptvSourceStore.generateId(),
                    type = type,
                    name = obj.optString("name", "Source importée"),
                    url = url,
                    mac = mac,
                    serial = obj.optString("serial", null),
                    userAgent = obj.optString("userAgent", null),
                    referer = obj.optString("referer", null)
                )
                IptvSourceStore.upsert(source)
                Log.d(TAG, "IPTV source imported: ${source.name}")
            }
        }

        // 4. Favoris IPTV
        payload.optJSONObject("iptvFavorites")?.let { favs ->
            favs.keys().forEach { prefsName ->
                val obj = favs.optJSONObject(prefsName) ?: return@forEach
                val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                val editor = prefs.edit()
                obj.keys().forEach { key ->
                    when (val v = obj.get(key)) {
                        is Boolean -> editor.putBoolean(key, v)
                        is String -> editor.putString(key, v)
                        is JSONArray -> {
                            val set = mutableSetOf<String>()
                            for (i in 0 until v.length()) set.add(v.optString(i, ""))
                            editor.putStringSet(key, set)
                        }
                    }
                }
                editor.apply()
            }
            Log.d(TAG, "IPTV favorites imported")
        }

        // 5. Favoris saisons (SharedPrefs brutes)
        payload.optJSONObject("seasonFavorites")?.let { obj ->
            val prefs = context.getSharedPreferences("season_favorites", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            obj.keys().forEach { key ->
                editor.putString(key, obj.optString(key))
            }
            editor.apply()
            Log.d(TAG, "Season favorites imported")
        }

        // 6. Favoris épisodes (SharedPrefs brutes)
        payload.optJSONObject("episodeFavorites")?.let { obj ->
            val prefs = context.getSharedPreferences("episode_favorites", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            obj.keys().forEach { key ->
                editor.putString(key, obj.optString(key))
            }
            editor.apply()
            Log.d(TAG, "Episode favorites imported")
        }

        // 7. Extracteur toggles
        payload.optJSONObject("extractorToggles")?.let { obj ->
            val prefs = context.getSharedPreferences("extractor_toggles", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            obj.optJSONArray("disabled")?.let { arr ->
                val set = mutableSetOf<String>()
                for (i in 0 until arr.length()) set.add(arr.optString(i, ""))
                editor.putStringSet("disabled_extractors", set)
            }
            obj.keys().forEach { key ->
                if (key.startsWith("fav_")) {
                    val arr = obj.optJSONArray(key) ?: return@forEach
                    val set = mutableSetOf<String>()
                    for (i in 0 until arr.length()) set.add(arr.optString(i, ""))
                    editor.putStringSet(key, set)
                }
            }
            editor.apply()
            Log.d(TAG, "Extractor toggles imported")
        }

        // 8. Préférences utilisateur
        // Les SeekBarPreference (scale_x, scale_y) stockent en Int,
        // les EditTextPreference en String — on respecte le type attendu.
        val knownIntPrefs = setOf("scale_x", "scale_y")
        payload.optJSONObject("userPreferences")?.let { obj ->
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val editor = prefs.edit()
            obj.keys().forEach { key ->
                val v = obj.get(key)
                if (key in knownIntPrefs) {
                    // Forcer Int même si le JSON l'a sérialisé en String
                    val intVal = when (v) {
                        is Int -> v
                        is String -> v.toIntOrNull() ?: 0
                        is Number -> v.toInt()
                        else -> null
                    }
                    if (intVal != null) editor.putInt(key, intVal)
                } else {
                    when (v) {
                        is Boolean -> editor.putBoolean(key, v)
                        is String -> editor.putString(key, v)
                        is Int -> editor.putString(key, v.toString()) // EditTextPreference attend String
                        is Number -> editor.putString(key, v.toString())
                    }
                }
            }
            editor.apply()
            Log.d(TAG, "User preferences imported")
        }

        // 9. Filtres catalogue
        payload.optJSONObject("catalogFilters")?.let { obj ->
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val editor = prefs.edit()
            obj.keys().forEach { key ->
                editor.putString(key, obj.optString(key))
            }
            editor.apply()
            Log.d(TAG, "Catalog filters imported")
        }

        // 10. Historique de recherche
        payload.optJSONObject("searchHistory")?.let { obj ->
            val prefs = context.getSharedPreferences("search_history", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            obj.keys().forEach { key ->
                editor.putString(key, obj.optString(key))
            }
            editor.apply()
            Log.d(TAG, "Search history imported")
        }

        // 11. Continue-watching dismissed
        payload.optJSONObject("continueWatchingDismissed")?.let { obj ->
            val prefs = context.getSharedPreferences("continue_watching_dismissed", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            obj.keys().forEach { key ->
                val v = obj.opt(key)
                when (v) {
                    is JSONArray -> {
                        val set = mutableSetOf<String>()
                        for (i in 0 until v.length()) set.add(v.optString(i, ""))
                        editor.putStringSet(key, set)
                    }
                    is String -> editor.putString(key, v)
                }
            }
            editor.apply()
            Log.d(TAG, "Continue-watching dismissed imported")
        }

        // 12. Sources World Live (playlists custom)
        //     2026-06-10 : restaure la liste des sources perso et l'index
        //     active. Le provider est invalidé pour re-fetch la nouvelle
        //     source au prochain affichage.
        payload.optJSONObject("worldLiveSources")?.let { obj ->
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val editor = prefs.edit()
            obj.optString("list", "").takeIf { it.isNotBlank() }?.let {
                editor.putString("world_live_sources_list", it)
            }
            if (obj.has("activeIndex")) {
                editor.putInt("world_live_sources_active_index", obj.optInt("activeIndex", 0))
            }
            editor.apply()
            try {
                com.streamflixreborn.streamflix.providers
                    .WorldLiveTvProvider.invalidateCache()
            } catch (_: Throwable) {}
            Log.d(TAG, "World Live sources imported")
        }
    }

    // ── HELPERS ─────────────────────────────────────────────────────────

    /**
     * Construit un BackupRestoreManager pour un profil SPÉCIFIQUE.
     * Ouvre la DB de chaque provider pour ce profileId (pas le profil actif).
     * Ne crée de DB que si elle existe déjà sur disque (évite les vides).
     */
    private fun buildBackupManagerForProfile(profileId: String, context: Context): BackupRestoreManager {
        val providerContexts = Provider.providers.keys
            .filter { it.name != "Mon IPTV" }
            .mapNotNull { provider ->
                try {
                    // N'ouvrir que si la DB existe déjà (évite de créer 30 fichiers vides)
                    if (!AppDatabase.providerDbExistsForProfile(profileId, provider.name, context)) {
                        return@mapNotNull null
                    }
                    val db = AppDatabase.getInstanceForProfileAndProvider(profileId, provider.name, context)
                    ProviderBackupContext(
                        name = provider.name,
                        movieDao = db.movieDao(),
                        tvShowDao = db.tvShowDao(),
                        episodeDao = db.episodeDao(),
                        seasonDao = db.seasonDao(),
                        provider = provider
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Skip provider ${provider.name} for profile $profileId: ${e.message}")
                    null
                }
            }
        return BackupRestoreManager(context, providerContexts)
    }
}
