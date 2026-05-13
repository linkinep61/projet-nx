package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.models.Profile
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 2026-05-12 : persistance des profils dans SharedPreferences sous forme JSON.
 *
 * Choix volontaire de NE PAS utiliser Room :
 * 1. Les profils sont très peu nombreux (1-5 typiquement) → JSON suffit.
 * 2. AppDatabase est déjà segmentée par provider — ajouter un schema pour
 *    profiles complexifierait la migration et la cache d'instances.
 * 3. Lecture synchrone simple, pas de DAO/ViewModel à écrire.
 *
 * Le SharedPreferences utilisé est le même que [UserPreferences] (clé partagée).
 */
object ProfileStore {
    private const val TAG = "ProfileStore"
    private const val KEY_PROFILES_JSON = "profiles_json"
    private const val KEY_CURRENT_PROFILE_ID = "current_profile_id"
    private const val KEY_PROFILES_BOOTSTRAPPED = "profiles_bootstrapped"

    private lateinit var prefs: SharedPreferences

    fun setup(context: Context) {
        prefs = context.getSharedPreferences(
            "${BuildConfig.APPLICATION_ID}.preferences",
            Context.MODE_PRIVATE,
        )
    }

    /** Crée le profil par défaut si aucun profil n'existe. Idempotent.
     *  Appelé depuis [StreamFlixApp.onCreate] après [UserPreferences.setup].
     *
     *  Ne SET PAS automatiquement le profil comme actif — l'utilisateur doit
     *  passer par l'écran "Qui regarde ?" à chaque cold start (Netflix-style).
     *  Pour le 1er lancement après update, ça affichera juste "Principal"
     *  (avec leurs favoris/historique existants) + une carte "+ Ajouter". */
    fun bootstrapIfNeeded() {
        if (prefs.getBoolean(KEY_PROFILES_BOOTSTRAPPED, false)) return
        if (getAll().isEmpty()) {
            val defaultProfile = Profile(
                id = Profile.DEFAULT_ID,
                name = "Principal",
                emoji = "🎬",
                isAdmin = true,
            )
            saveAll(listOf(defaultProfile))
            Log.d(TAG, "Bootstrap: default profile 'Principal' created (not auto-selected)")
        }
        prefs.edit().putBoolean(KEY_PROFILES_BOOTSTRAPPED, true).apply()
    }

    fun getAll(): List<Profile> {
        val json = prefs.getString(KEY_PROFILES_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Profile(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    emoji = obj.optString("emoji", "🎬"),
                    isAdmin = obj.optBoolean("isAdmin", false),
                    pinHash = obj.optString("pinHash", "").takeIf { it.isNotBlank() },
                    maxAge = if (obj.has("maxAge") && !obj.isNull("maxAge")) obj.getInt("maxAge") else null,
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse profiles JSON: ${e.message}")
            emptyList()
        }
    }

    fun getById(id: String): Profile? = getAll().firstOrNull { it.id == id }

    /** Ajoute ou remplace un profil (matché par id). */
    fun upsert(profile: Profile) {
        val current = getAll().toMutableList()
        val idx = current.indexOfFirst { it.id == profile.id }
        if (idx >= 0) {
            current[idx] = profile
        } else {
            current.add(profile)
        }
        saveAll(current)
    }

    /** Supprime un profil par id. Refuse si c'est le dernier admin. */
    fun delete(id: String): Boolean {
        val all = getAll()
        val target = all.firstOrNull { it.id == id } ?: return false
        val remainingAdmins = all.count { it.isAdmin && it.id != id }
        if (target.isAdmin && remainingAdmins == 0) {
            Log.w(TAG, "Refusing to delete last admin profile ($id)")
            return false
        }
        val updated = all.filter { it.id != id }
        saveAll(updated)
        // Si le profil supprimé était actif → reset sur le premier admin restant.
        if (getCurrentProfileId() == id) {
            setCurrentProfileId(updated.firstOrNull { it.isAdmin }?.id ?: updated.firstOrNull()?.id)
        }
        return true
    }

    fun getCurrentProfileId(): String? = prefs.getString(KEY_CURRENT_PROFILE_ID, null)

    fun setCurrentProfileId(id: String?) {
        prefs.edit().putString(KEY_CURRENT_PROFILE_ID, id).apply()
    }

    fun getCurrentProfile(): Profile? = getCurrentProfileId()?.let { getById(it) }

    /** Génère un id stable pour un nouveau profil — basé sur timestamp + suffix
     *  random pour éviter les collisions sur création rapide. */
    fun generateId(): String =
        "p_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"

    /** Hash SHA-256 d'un PIN. Évite de stocker le PIN en clair. */
    fun hashPin(pin: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(pin.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun verifyPin(profile: Profile, pin: String): Boolean {
        val stored = profile.pinHash ?: return true  // Profile sans PIN = ouvert
        return stored == hashPin(pin)
    }

    private fun saveAll(profiles: List<Profile>) {
        val arr = JSONArray()
        profiles.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("emoji", p.emoji)
                put("isAdmin", p.isAdmin)
                if (p.pinHash != null) put("pinHash", p.pinHash)
                if (p.maxAge != null) put("maxAge", p.maxAge)
            })
        }
        prefs.edit().putString(KEY_PROFILES_JSON, arr.toString()).apply()
    }
}
