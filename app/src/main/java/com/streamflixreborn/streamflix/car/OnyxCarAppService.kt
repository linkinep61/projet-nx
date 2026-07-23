package com.streamflixreborn.streamflix.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * 2026-07-22 — PROJET ANDROID AUTO (étape 1 : apparaître dans le lanceur AA).
 *
 * Point d'entrée Android Auto d'ONYX. Déclaré en catégorie NAVIGATION dans le manifeste →
 * Android Auto (mode développeur) le liste dans son lanceur et lui accorde une SURFACE plein
 * écran (permission androidx.car.app.ACCESS_SURFACE) sur laquelle on rendra la vidéo aux étapes
 * suivantes — même technique que CarStream/AAStream.
 *
 * Étape 1 = squelette : un Session qui ouvre un écran d'accueil. Objectif immédiat : valider
 * qu'ONYX apparaît et se lance dans Android Auto. Le catalogue + la lecture vidéo sur la surface
 * viendront ensuite.
 */
@androidx.media3.common.util.UnstableApi
class OnyxCarAppService : CarAppService() {

    // ⚠ Étape 1 : on autorise TOUS les hôtes (dev). À restreindre à Android Auto en prod via
    //   HostValidator.Builder + les certificats officiels Google si on durcit la sécurité.
    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = OnyxCarSession()
}
