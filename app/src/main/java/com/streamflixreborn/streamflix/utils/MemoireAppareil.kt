package com.streamflixreborn.streamflix.utils

import android.app.ActivityManager
import android.content.Context
import com.streamflixreborn.streamflix.StreamFlixApp

/**
 * 2026-10-27 : appareils « justes » en mémoire (moins de 3 Go au total, ou déclarés faible
 *   mémoire par Android). Ex. TV TCL 2,4 Go : ~500 Mo libres seulement. Sur ces appareils, les
 *   chargements lourds se font un par un au lieu d'être cumulés (dossier Sport, Stream4Free…).
 */
object MemoireAppareil {
    val juste: Boolean by lazy {
        runCatching {
            val am = StreamFlixApp.instance.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            // 2026-10-27 (user : « les gens ont des problèmes pour lire des films et des séries ») —
            //   photo mémoire sur l'Oppo pendant un film Movix : les index de secours (recherche ONYX
            //   Films 30 Mo, Ciné Films 25 Mo, OLA 22 Mo) restaient chargés pendant toute la lecture,
            //   ~80 Mo de données. Un téléphone de 4 à 8 Go plafonné à 256 Mo PAR APPLI (cas courant)
            //   n'était pas « juste » et gardait tout → risque d'arrêt en pleine lecture. On se fie
            //   donc aussi au plafond par appli, qui est la vraie limite.
            am.isLowRamDevice || mi.totalMem < 3L * 1024 * 1024 * 1024 || am.memoryClass <= 256
        }.getOrDefault(false)
    }

    /**
     * 2026-10-27 (user : « je te laisse voir tout ce que tu peux faire » pour la mémoire des télés)
     *   — photos mémoire sur la télé TCL (plafond Java 192 Mo par appli) : pendant une recherche, les
     *   trois gros index (recherche ONYX Films, Ciné Films, OLA) se construisaient EN MÊME TEMPS
     *   (pic ~120 Mo) puis restaient chargés jusqu'à 6 h (~73 Mo après la fermeture du film).
     *   Sur un appareil juste : ils se construisent UN PAR UN ([verrouLourd]) et chacun est relâché
     *   2 min après sa dernière utilisation ([libererPlusTard]).
     */
    val verrouLourd = kotlinx.coroutines.sync.Mutex()

    /** Exécute [bloc] seul (un gros index à la fois) sur un appareil juste, directement sinon. */
    suspend fun <T> unParUn(bloc: suspend () -> T): T =
        if (juste) verrouLourd.lock().let { try { bloc() } finally { verrouLourd.unlock() } } else bloc()

    private val minuteur = android.os.Handler(android.os.Looper.getMainLooper())
    private val taches = java.util.concurrent.ConcurrentHashMap<String, Runnable>()

    /** Relâche [cle] [delaiMs] après le DERNIER appel (chaque nouvel appel repousse l'échéance). */
    // 2026-10-27 : sur les autres appareils aussi, mais au bout de 10 min sans usage (avant : jamais,
    //   l'index restait jusqu'à la fermeture de l'appli).
    fun libererPlusTard(cle: String, delaiMs: Long = 120_000L, action: () -> Unit) {
        actions[cle] = action
        armer(cle, if (juste) delaiMs else maxOf(delaiMs, 10 * 60_000L), action)
    }

    private val actions = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()

    /** Index qui ne servent qu'à chercher (catalogue, serveurs de secours) : inutiles pendant un film. */
    private val CLES_RECHERCHE = listOf("index de recherche ONYX Films", "index Ciné Films", "index OLA")

    /**
     * 2026-10-27 (user : « le but, c'est d'optimiser pour les mini-box ») : appelé quand la lecture
     *   démarre vraiment (STATE_READY). Les serveurs sont trouvés, les index de recherche ne servent
     *   plus → rendus 1 min après, sur TOUS les appareils (au lieu de 2 ou 10 min), au moment où le
     *   lecteur a besoin de mémoire. S'ils resservent (épisode suivant, serveur de secours), ils
     *   sont relus (~3 s) et le délai normal reprend.
     */
    fun lectureDemarree() {
        for (cle in CLES_RECHERCHE) {
            if (!taches.containsKey(cle)) continue   // déjà relâché (ou jamais chargé)
            val action = actions[cle] ?: continue
            armer(cle, 60_000L, action)
        }
    }

    private fun armer(cle: String, delai: Long, action: () -> Unit) {
        taches.remove(cle)?.let { minuteur.removeCallbacks(it) }
        val r = Runnable {
            taches.remove(cle)
            kotlin.concurrent.thread(isDaemon = true, name = "liberation-$cle") {
                runCatching(action).onSuccess {
                    android.util.Log.i("MemoireAppareil", "$cle relâché (${delai / 60_000} min sans usage)")
                }
            }
        }
        taches[cle] = r
        minuteur.postDelayed(r, delai)
    }
}
