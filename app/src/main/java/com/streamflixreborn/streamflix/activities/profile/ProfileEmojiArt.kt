package com.streamflixreborn.streamflix.activities.profile

import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target

/**
 * 2026-05-20 (user "des trucs design cool" / "les emojis systeme sont pourris") :
 * rendu des avatars de profil en **Microsoft Fluent Emoji 3D** au lieu des
 * emojis systeme (plats / carres sur vieux Chromecast).
 *
 * Principe :
 *  - On garde [Profile.emoji] tel quel (le caractere Unicode) comme identite
 *    stockee — AUCUNE migration de donnees, retro-compatible.
 *  - A l'affichage, on mappe l'emoji vers l'URL de son image Fluent 3D sur
 *    jsDelivr (CDN), chargee via Glide + cache disque. => 0 Mo dans l'APK,
 *    rendu identique partout (mobile + TV), pas de fond (PNG transparent).
 *  - Fallback : si l'emoji n'est pas mappe OU si le reseau echoue au tout
 *    premier affichage (hors-ligne, pas encore en cache), on retombe sur le
 *    TextView emoji systeme. Une fois charge, Glide le garde en cache => OK
 *    hors-ligne ensuite.
 *
 * Les chemins Fluent ont ete verifies 1 par 1 (HTTP 200) le 2026-05-20.
 * Repo : github.com/microsoft/fluentui-emoji (licence MIT).
 */
object ProfileEmojiArt {

    private const val BASE =
        "https://cdn.jsdelivr.net/gh/microsoft/fluentui-emoji@main/assets/"

    /** emoji Unicode -> chemin relatif Fluent (sous /assets/). Les espaces sont
     *  encodes en %20 a la volee dans [urlFor]. */
    private val PATHS: Map<String, String> = mapOf(
        // Cinema / divertissement
        "🎬" to "Clapper board/3D/clapper_board_3d.png",
        "🍿" to "Popcorn/3D/popcorn_3d.png",
        "📺" to "Television/3D/television_3d.png",
        "🎮" to "Video game/3D/video_game_3d.png",
        "🎵" to "Musical note/3D/musical_note_3d.png",
        "🎨" to "Artist palette/3D/artist_palette_3d.png",
        "📷" to "Camera/3D/camera_3d.png",
        "🎁" to "Wrapped gift/3D/wrapped_gift_3d.png",
        // Smileys / faces
        "😀" to "Grinning face/3D/grinning_face_3d.png",
        "😎" to "Smiling face with sunglasses/3D/smiling_face_with_sunglasses_3d.png",
        "😊" to "Smiling face with smiling eyes/3D/smiling_face_with_smiling_eyes_3d.png",
        "🤩" to "Star-struck/3D/star-struck_3d.png",
        "🥳" to "Partying face/3D/partying_face_3d.png",
        "😘" to "Face blowing a kiss/3D/face_blowing_a_kiss_3d.png",
        "🤖" to "Robot/3D/robot_3d.png",
        "👽" to "Alien/3D/alien_3d.png",
        // Animaux 1
        "🐱" to "Cat face/3D/cat_face_3d.png",
        "🐶" to "Dog face/3D/dog_face_3d.png",
        "🦁" to "Lion/3D/lion_3d.png",
        "🐯" to "Tiger face/3D/tiger_face_3d.png",
        "🐼" to "Panda/3D/panda_3d.png",
        "🦊" to "Fox/3D/fox_3d.png",
        "🐰" to "Rabbit face/3D/rabbit_face_3d.png",
        "🐻" to "Bear/3D/bear_3d.png",
        // Animaux 2
        "🐧" to "Penguin/3D/penguin_3d.png",
        "🦄" to "Unicorn/3D/unicorn_3d.png",
        "🦖" to "T-rex/3D/t-rex_3d.png",
        "🦉" to "Owl/3D/owl_3d.png",
        "🐨" to "Koala/3D/koala_3d.png",
        "🐷" to "Pig face/3D/pig_face_3d.png",
        "🐸" to "Frog/3D/frog_3d.png",
        "🐵" to "Monkey face/3D/monkey_face_3d.png",
        // Personnages (variantes de couleur de peau -> /Default/, fichier _3d_default)
        "🧛" to "Person vampire/Default/3D/person_vampire_3d_default.png",
        "🧙" to "Person mage/Default/3D/person_mage_3d_default.png",
        "🦸" to "Person superhero/Default/3D/person_superhero_3d_default.png",
        "🤡" to "Clown face/3D/clown_face_3d.png",
        "🥷" to "Ninja/Default/3D/ninja_3d_default.png",
        "🕵️" to "Detective/Default/3D/detective_3d_default.png",
        "👮" to "Police officer/Default/3D/police_officer_3d_default.png",
        "👨‍🚀" to "Man astronaut/Default/3D/man_astronaut_3d_default.png",
        // Symboles
        "⭐" to "Star/3D/star_3d.png",
        "🔥" to "Fire/3D/fire_3d.png",
        "⚡" to "High voltage/3D/high_voltage_3d.png",
        "💖" to "Sparkling heart/3D/sparkling_heart_3d.png",
        "💎" to "Gem stone/3D/gem_stone_3d.png",
        "🏆" to "Trophy/3D/trophy_3d.png",
        "💰" to "Money bag/3D/money_bag_3d.png",
        "⏳" to "Hourglass not done/3D/hourglass_not_done_3d.png",
        "💡" to "Light bulb/3D/light_bulb_3d.png",
        // Nourriture
        "🍕" to "Pizza/3D/pizza_3d.png",
        "🍔" to "Hamburger/3D/hamburger_3d.png",
        "🍟" to "French fries/3D/french_fries_3d.png",
        "🍩" to "Doughnut/3D/doughnut_3d.png",
        "🍦" to "Soft ice cream/3D/soft_ice_cream_3d.png",
        "🍉" to "Watermelon/3D/watermelon_3d.png",
        "🍹" to "Tropical drink/3D/tropical_drink_3d.png",
        "🎂" to "Birthday cake/3D/birthday_cake_3d.png",
        "🍪" to "Cookie/3D/cookie_3d.png",
        // Voyage / nature
        "🌍" to "Globe showing europe-africa/3D/globe_showing_europe-africa_3d.png",
        "✈️" to "Airplane/3D/airplane_3d.png",
        "🚀" to "Rocket/3D/rocket_3d.png",
        "⛵" to "Sailboat/3D/sailboat_3d.png",
        "🌈" to "Rainbow/3D/rainbow_3d.png",
        "☀️" to "Sun/3D/sun_3d.png",
        "🌙" to "Crescent moon/3D/crescent_moon_3d.png",
        "🌧️" to "Cloud with rain/3D/cloud_with_rain_3d.png",
        "❄️" to "Snowflake/3D/snowflake_3d.png",
    )

    fun urlFor(emoji: String?): String? {
        val path = emoji?.let { PATHS[it] } ?: return null
        return BASE + path.replace(" ", "%20")
    }

    /**
     * Resout une valeur stockee (champ Profile.emoji) en URL Fluent. La valeur
     * peut etre :
     *  - un emoji Unicode des 67 cures (ex: "😎")            -> via [PATHS]
     *  - un chemin Fluent complet (ex: "Fox/3D/fox_3d.png")  -> set complet
     *  - une URL http(s) deja formee                          -> telle quelle
     */
    fun urlForValue(value: String?): String? {
        if (value.isNullOrEmpty()) return null
        PATHS[value]?.let { return BASE + it.replace(" ", "%20") }
        if (value.startsWith("http")) return value
        if (value.contains("/3D/")) return BASE + value.replace(" ", "%20")
        return null
    }

    /**
     * Libelle lisible pour les dialogs texte : l'emoji si c'est un caractere
     * connu, sinon le nom du dossier Fluent (ex: "Fox/3D/fox_3d.png" -> "Fox").
     */
    fun displayName(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        if (PATHS.containsKey(value)) return value
        if (value.contains("/")) return value.substringBefore("/")
        return value
    }

    /**
     * Affiche l'avatar : image Fluent 3D dans [image] si dispo, sinon l'emoji
     * systeme dans [fallback]. A appeler a chaque (re)bind d'un ViewHolder.
     */
    fun bind(emoji: String?, image: ImageView?, fallback: TextView?) {
        // Texte de fallback : seulement si la valeur est un VRAI emoji Unicode
        // (les chemins Fluent ne doivent jamais s'afficher en texte).
        val fallbackText = if (emoji != null && PATHS.containsKey(emoji)) emoji else ""
        if (image == null) {
            fallback?.apply { visibility = View.VISIBLE; text = fallbackText }
            return
        }
        val url = urlForValue(emoji)
        if (url == null) {
            // Pas de mapping (ex: emoji custom) -> emoji systeme.
            image.visibility = View.GONE
            fallback?.apply { visibility = View.VISIBLE; text = fallbackText }
            return
        }
        // Pendant le chargement on cache le fallback (evite le double rendu).
        fallback?.visibility = View.GONE
        image.visibility = View.VISIBLE
        Glide.with(image)
            .load(url)
            .fitCenter()
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean,
                ): Boolean {
                    image.visibility = View.GONE
                    fallback?.apply { visibility = View.VISIBLE; text = fallbackText }
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean,
                ): Boolean {
                    fallback?.visibility = View.GONE
                    image.visibility = View.VISIBLE
                    return false
                }
            })
            .into(image)
    }
}
