package com.streamflixreborn.streamflix.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template

/**
 * 2026-07-23 (projet Android Auto, LAB) — MENU D'ACCUEIL ONYX (surface voiture).
 *
 * Deux entrées (décision user 2026-07-23 : les gens lancent la lecture depuis le téléphone) :
 *  - Projeter la lecture en cours (miroir du player téléphone → OnyxCarVideoScreen)
 *  - Radio (stations audio jouables directement sur la voiture)
 */
@androidx.media3.common.util.UnstableApi
class OnyxCarHomeScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
            .addItem(
                Row.Builder()
                    .setTitle("▶ Projeter la lecture en cours")
                    .addText("Affiche la vidéo qui joue sur le téléphone")
                    .setOnClickListener { screenManager.push(OnyxCarVideoScreen(carContext)) }
                    .setBrowsable(true)
                    .build(),
            )
            .addItem(
                Row.Builder()
                    .setTitle("Radio")
                    .addText("Écouter une radio")
                    .setOnClickListener {
                        screenManager.push(OnyxCarListScreen(carContext, OnyxCarListScreen.Kind.RADIO))
                    }
                    .setBrowsable(true)
                    .build(),
            )
            .build()

        return ListTemplate.Builder()
            .setSingleList(list)
            .setTitle("ONYX")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }
}
