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
    val list = listOf(
        // Cinéma / divertissement
        "🎬", "🍿", "📺", "🎮", "🎵", "🎨",
        // Smileys
        "😀", "😎", "😊", "🤩", "🥳", "🤖",
        // Animaux
        "🐱", "🐶", "🦁", "🐯", "🐼", "🦊",
        "🐰", "🐻", "🐧", "🦄",
        // Persos fantaisie
        "👾", "👽", "🧛", "🧙", "🦸", "🦹",
        // Symboles
        "⭐", "🔥", "⚡", "💖",
    )
}
