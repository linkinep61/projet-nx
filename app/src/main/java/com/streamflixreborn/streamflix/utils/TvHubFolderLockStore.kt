package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.content.SharedPreferences
import com.streamflixreborn.streamflix.BuildConfig

/**
 * 2026-07-04 (user "peux-tu ajouter le contrôle parental sur les différentes
 * catégories du TV hub — par exemple si on reste appuyé longtemps sur un dossier
 * comme TF1+ replay, une proposition arrive pour mettre le contrôle parental,
 * pour chaque dossier") : Contrôle parental par DOSSIER du TV Hub.
 *
 * Parallèle du [ProviderLockStore] (qui verrouille des providers entiers) mais
 * ici on verrouille des DOSSIERS individuels du TV Hub : Replay TF1+, Replay
 * M6+, Replay Arte, Musique, Plex Séries, etc. → chaque dossier peut être
 * indépendamment verrouillé/déverrouillé.
 *
 * Le PIN est PARTAGÉ avec [ProviderLockStore] (une seule config, moins de
 * friction UX) — on lit/écrit le `pin_hash` du même SharedPreferences
 * `${appId}.provider_lock`. Ainsi si l'user a déjà configuré son PIN pour
 * verrouiller un provider, il n'a pas besoin de le refaire pour un dossier.
 *
 * Flow utilisateur :
 *   1. Long-press sur un dossier "Replay TF1+" → menu avec "🔒 Contrôle parental"
 *   2. Si aucun PIN → setup PIN 4 chiffres, puis verrouille le dossier
 *   3. Si PIN existe → demande PIN pour toggler le verrou du dossier
 *   4. Dossier verrouillé apparaît avec icône 🔒 en surimpression
 *   5. Clic sur dossier verrouillé → demande le PIN pour l'ouvrir (déblocage
 *      TEMPORAIRE pour la session courante — 30 min d'inactivité)
 *
 * Stockage : SharedPreferences (file `${appId}.provider_lock` — partagé).
 *   - `pin_hash` (ProviderLockStore) : String, SHA-256 du PIN
 *   - `locked_folders` : Set<String>, keys de dossiers verrouillés
 *   - `unlocked_folders_session` : Map<String, Long> EN MÉMOIRE, dossiers
 *     débloqués pour la session courante (reset à la fermeture app)
 *
 * Clé de dossier : `folderKey` est la chaîne stable qui identifie un dossier
 * dans le TV Hub. Pour les replays c'est le suffixe après `livehub::replay::`
 * (ex "tf1plus", "m6play", "bfmplay"). Pour les autres dossiers TV Hub c'est
 * le `folderKey` interne (voir [LiveHubFolderDialog]).
 */
object TvHubFolderLockStore {

    /** Fichier de SharedPreferences partagé avec ProviderLockStore. */
    private const val PREFS_FILE = "provider_lock"
    private const val KEY_LOCKED_FOLDERS = "locked_folders"

    /** Dossiers débloqués pour la session courante. En mémoire uniquement. */
    private val sessionUnlocked = mutableMapOf<String, Long>()

    /** Timeout d'inactivité : 30 minutes (aligné sur ProviderLockStore). */
    private const val SESSION_TIMEOUT_MS = 30L * 60L * 1000L

    private fun prefs(context: Context): SharedPreferences {
        val name = "${BuildConfig.APPLICATION_ID}.$PREFS_FILE"
        return context.getSharedPreferences(name, Context.MODE_PRIVATE)
    }

    /** Liste des dossiers verrouillés (permanents, persistés). */
    fun getLockedFolders(context: Context): Set<String> {
        return prefs(context).getStringSet(KEY_LOCKED_FOLDERS, emptySet()) ?: emptySet()
    }

    /** Verrouille un dossier. */
    fun lockFolder(context: Context, folderKey: String) {
        val current = getLockedFolders(context).toMutableSet()
        current.add(folderKey)
        prefs(context).edit().putStringSet(KEY_LOCKED_FOLDERS, current).apply()
    }

    /** Retire le verrou (permanent) d'un dossier. */
    fun unlockFolder(context: Context, folderKey: String) {
        val current = getLockedFolders(context).toMutableSet()
        current.remove(folderKey)
        prefs(context).edit().putStringSet(KEY_LOCKED_FOLDERS, current).apply()
        synchronized(sessionUnlocked) { sessionUnlocked.remove(folderKey) }
    }

    /** Toggle le verrou. Retourne le nouvel état (true = verrouillé). */
    fun toggleLock(context: Context, folderKey: String): Boolean {
        val nowLocked = !isLocked(context, folderKey)
        if (nowLocked) lockFolder(context, folderKey) else unlockFolder(context, folderKey)
        return nowLocked
    }

    /** True si le dossier est verrouillé (permanent, ignore session unlock). */
    fun isLocked(context: Context, folderKey: String): Boolean {
        return getLockedFolders(context).contains(folderKey)
    }

    /** True si le dossier est ACCESSIBLE — soit pas verrouillé, soit débloqué
     *  pour la session courante dans la fenêtre de 30 min. */
    fun isAccessible(context: Context, folderKey: String): Boolean {
        if (!isLocked(context, folderKey)) return true
        val unlockedAt = synchronized(sessionUnlocked) {
            sessionUnlocked[folderKey]
        } ?: return false
        val elapsed = System.currentTimeMillis() - unlockedAt
        if (elapsed > SESSION_TIMEOUT_MS) {
            synchronized(sessionUnlocked) { sessionUnlocked.remove(folderKey) }
            return false
        }
        return true
    }

    /** Débloque un dossier pour la session courante. */
    fun unlockForSession(folderKey: String) {
        synchronized(sessionUnlocked) {
            sessionUnlocked[folderKey] = System.currentTimeMillis()
        }
    }

    /** Renouvelle le timestamp d'un dossier déjà déverrouillé (refresh d'inactivité). */
    fun touchSessionUnlock(folderKey: String) {
        synchronized(sessionUnlocked) {
            if (sessionUnlocked.containsKey(folderKey)) {
                sessionUnlocked[folderKey] = System.currentTimeMillis()
            }
        }
    }

    /** Réinitialise tous les déblocages de session (force re-PIN partout). */
    fun resetSessionUnlocks() {
        synchronized(sessionUnlocked) { sessionUnlocked.clear() }
    }

    /** Extrait la clé de dossier depuis un id TvShow du TV Hub.
     *
     *  Convention : les items dossiers du TV Hub ont deux préfixes possibles :
     *
     *  1) Dossiers GÉNÉRIQUES (Stream4Free, FrenchTV, Musique, Adar, OTF, etc.)
     *     - Préfixe `livehub::folder::<key>` (parfois suivi d'un tail vide ou `::`)
     *     - Ex : `livehub::folder::stream4`, `livehub::folder::musique`,
     *       `livehub::folder::worldwide`, `livehub::folder::adarmap`, etc.
     *
     *  2) Dossiers REPLAY (racine d'un service replay, PAS un show individuel)
     *     - Préfixe `livehub::replay::<key>::` où <key> est le nom du service
     *     - Ex : `livehub::replay::tf1plus::`, `livehub::replay::m6play::`,
     *       `livehub::replay::bfmplay::`, `livehub::replay::arteshow::`,
     *       `livehub::replay::plexshow::`, `livehub::replay::plutoshow::`,
     *       `livehub::replay::ftvshow::`
     *
     *  On renvoie une clé PRÉFIXÉE `folder:<key>` ou `replay:<key>` pour ne pas
     *  qu'un dossier "folder::stream4" collisionne avec une éventuelle clé
     *  "replay::stream4" (défense en profondeur).
     *
     *  Renvoie null si l'id ne matche AUCUN pattern de dossier de TV Hub.
     */
    fun folderKeyFromId(id: String): String? {
        // 1) Dossiers génériques
        val folderPrefix = "livehub::folder::"
        if (id.startsWith(folderPrefix)) {
            val tail = id.substring(folderPrefix.length)
            val separator = tail.indexOf("::")
            val key = if (separator >= 0) tail.substring(0, separator) else tail
            if (key.isBlank()) return null
            return "folder:$key"
        }
        // 2) Dossiers racine des services replay (sans ::show:: ni ::program/)
        val replayPrefix = "livehub::replay::"
        if (id.startsWith(replayPrefix)) {
            val tail = id.substring(replayPrefix.length)
            val separator = tail.indexOf("::")
            val key = if (separator >= 0) tail.substring(0, separator) else tail
            if (key.isBlank()) return null
            return "replay:$key"
        }
        return null
    }

    /** 2026-07-04 (user "quand on va dans Stream4Free on pourrait verrouiller
     *  un film ou une série ou un Live") : dérive la clé d'un SOUS-dossier à
     *  partir de sa clé parente + son libellé user.
     *
     *  Le libellé est normalisé (retire emoji, accents, cas, espaces) pour
     *  produire une clé stable même si l'affichage change légèrement.
     *
     *  Ex :
     *   - `subFolderKey("folder:stream4", "🎬 Films")` → `"folder:stream4::films"`
     *   - `subFolderKey("folder:stream4", "📺 Séries")` → `"folder:stream4::series"`
     *   - `subFolderKey("folder:stream4", "📡 Chaînes en direct")` → `"folder:stream4::chaines_en_direct"`
     */
    fun subFolderKey(parentKey: String, subLabel: String): String {
        // Retire les emoji et signes de ponctuation courants au début.
        val cleaned = subLabel.trim()
            .replace(Regex("^[^\\p{L}\\p{N}]+"), "")  // Enlève emoji + espaces initiales
            .lowercase()
            .replace(Regex("[éèêë]"), "e")
            .replace(Regex("[àâä]"), "a")
            .replace(Regex("[îï]"), "i")
            .replace(Regex("[ôö]"), "o")
            .replace(Regex("[ûü]"), "u")
            .replace(Regex("[ç]"), "c")
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
        if (cleaned.isEmpty()) return parentKey
        return "$parentKey::$cleaned"
    }

    /** Libellé user-friendly d'un dossier à partir de sa clé. Utilisé dans les
     *  dialogs de confirmation.
     *
     *  Les clés sont préfixées `folder:` ou `replay:` (cf. [folderKeyFromId]).
     *  Les sous-dossiers sont suffixés `::<subKey>` (cf. [subFolderKey]).
     */
    fun folderLabel(folderKey: String): String {
        // Retire le préfixe `folder:` / `replay:` pour la lisibilité.
        val stripped = folderKey.substringAfter(':', folderKey)
        return when (stripped) {
            // Replays
            "tf1plus" -> "Replay TF1+"
            "m6play" -> "Replay M6+"
            "bfmplay" -> "Replay BFM"
            "arteshow" -> "Replay Arte"
            "plexshow" -> "Plex Séries"
            "plutoshow" -> "Pluto Séries"
            "ftvshow" -> "France TV Séries"
            // Dossiers génériques du TV Hub
            "stream4" -> "Stream4Free"
            "frenchtv" -> "FrenchTV"
            "worldwide" -> "Worldwide"
            "musique" -> "Musique"
            "adar" -> "Adar"
            "adarmap" -> "AdarMap"
            "otf" -> "OTF"
            "plex" -> "Plex"
            "plex_lineup" -> "Plex Lineup"
            "plex_vod" -> "Plex VOD"
            "pluto_tv" -> "Pluto TV"
            "sony_one" -> "Sony One"
            "autres_replay" -> "Autres Replays"
            "vavoo" -> "Vavoo"
            else -> stripped.replaceFirstChar { it.uppercase() }
        }
    }
}
