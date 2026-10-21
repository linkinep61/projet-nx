package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.streamflixreborn.streamflix.BuildConfig

/**
 * 2026-10-21 (user : « quand on a un serveur rouge, qu'il nous l'envoie directement
 *   quelque part pour le consulter dans la foulée ») — VERSION DEBUG UNIQUEMENT.
 *   Une ligne de journal par serveur passé au rouge, au format :
 *     SERVEUR_ROUGE: <nom> ||| <lien du serveur> ||| <flux en lecture au moment de l'échec>
 *   Lue sur le PC par outils/serveurs_rouges.ps1 (projet-nx), qui l'écrit dans
 *   serveurs_rouges.txt sur le bureau. Ne fait rien dans l'APK publié.
 */
object ServeurRougeDebug {
    private val dejaSignales = java.util.Collections.synchronizedSet(HashSet<String>())

    fun signaler(nom: String, lien: String?, lecture: String? = null) {
        if (!BuildConfig.DEBUG) return
        val cle = nom + "|" + lien.orEmpty()
        if (!dejaSignales.add(cle)) return
        Log.w("SERVEUR_ROUGE", "$nom ||| ${lien.orEmpty()} ||| ${lecture.orEmpty()}")
    }
}