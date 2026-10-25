package com.streamflixreborn.streamflix.sport

import android.util.Log
import com.streamflixreborn.streamflix.sport.providers.SportProviders
import com.streamflixreborn.streamflix.sport.utils.DecodeEnc

/**
 * Vérifications du module sport (brief § 10). Le projet n'a pas de lanceur de tests et le
 * brief interdit d'en installer : ces contrôles tournent DANS l'appli de débogage, à la
 * première ouverture du dossier Sport, et écrivent « SportSelfTest OK/KO » dans le journal
 * (`adb logcat -s SportSelfTest`). Aucun réseau : rien ne dépend d'un hôte réel.
 */
object SportSelfTest {

    private const val TAG = "SportSelfTest"
    @Volatile private var fait = false

    suspend fun lancerUneFois() {
        if (fait) return
        fait = true
        val resultats = listOf(
            "decodeEnc aller-retour" to decodeAllerRetour(),
            "decodeEnc entrée invalide → null" to (DecodeEnc.decodeEnc("enc%3A%%%") == null),
            "cache 60 s : 2 appels = 1 chargement" to cacheUnSeulChargement(),
            "dédoublonnage équipes" to (SportProviders.cleEquipes("Inter Milan vs Fiorentina · Serie A") ==
                SportProviders.cleEquipes("Fiorentina - Inter Milan")),
        )
        resultats.forEach { (nom, ok) -> Log.i(TAG, "${if (ok) "OK" else "KO"} — $nom") }
        Log.i(TAG, if (resultats.all { it.second }) "SportSelfTest OK" else "SportSelfTest KO")
    }

    /** Brief § 4 : round-trip, sans assertion sur un hôte réel. */
    private fun decodeAllerRetour(): Boolean {
        val s = "https://exemple.tld/stream.m3u8"
        val encode = DecodeEnc.encodePourTest(s)
        return DecodeEnc.decodeEnc(java.net.URLEncoder.encode(encode, "UTF-8")) == s &&
            DecodeEnc.decodeEnc(encode) == s
    }

    private suspend fun cacheUnSeulChargement(): Boolean {
        val c = TtlCache<String>(60_000L)
        c.get { "a" }
        c.get { "b" }
        return c.chargements == 1 && c.get { "c" } == "a"
    }
}
