package com.streamflixreborn.streamflix.activities.profile

/**
 * 2026-05-12 (user "la télé on n'a pas d'emoticônes à mettre pour le profil") :
 * Liste curated d'emojis pour les avatars de profils.
 *
 * Sélection orientée Netflix-style — faces, animaux, persos fantaisie, symboles
 * fun. Tous sont des emojis BMP standards qui s'affichent correctement sur
 * Android 7+ (y compris Chromecast). Évite les emojis très récents (Unicode 13+)
 * qui peuvent s'afficher comme des carrés sur les vieux firmwares TV.
 */
object ProfileEmojis {
    /** 2026-05-13 (user "image avec plein d'émoticones super à extraire") :
     *  liste étendue à partir de l'image sample envoyée par le user — 67 emojis
     *  organisés en 8 catégories. Tous Unicode <= 13, OK sur Chromecast Android
     *  7+ (le ninja 🥷 et l'astronaute homme 👨‍🚀 peuvent fallback sur firmware
     *  très ancien mais marchent sur Chromecast récent). */
    val list = listOf(
        // Cinéma / divertissement
        "🎬", "🍿", "📺", "🎮", "🎵", "🎨", "📷", "🎁",
        // Smileys / faces
        "😀", "😎", "😊", "🤩", "🥳", "😘", "🤖", "👽",
        // Animaux 1
        "🐱", "🐶", "🦁", "🐯", "🐼", "🦊", "🐰", "🐻",
        // Animaux 2
        "🐧", "🦄", "🦖", "🦉", "🐨", "🐷", "🐸", "🐵",
        // Personnages
        "🧛", "🧙", "🦸", "🤡", "🥷", "🕵️", "👮", "👨‍🚀",
        // Symboles
        "⭐", "🔥", "⚡", "💖", "💎", "🏆", "💰", "⏳", "💡",
        // Nourriture
        "🍕", "🍔", "🍟", "🍩", "🍦", "🍉", "🍹", "🎂", "🍪",
        // Voyage / nature
        "🌍", "✈️", "🚀", "⛵", "🌈", "☀️", "🌙", "🌧️", "❄️",
    )
}
