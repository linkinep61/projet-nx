package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.streamflixreborn.streamflix.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Helper pour câbler le widget de notation communautaire.
 * TV : chaque OK sur la barre incrémente (1→2→3→4→5→1) et valide.
 * Mobile : clic direct sur l'étoile voulue.
 */
object CommunityRatingView {

    private const val TAG = "CommunityRating"
    private const val COLOR_STAR_FILLED = 0xFFFFD700.toInt()   // Or (moyenne)
    private const val COLOR_STAR_EMPTY = 0xFF555555.toInt()     // Gris foncé
    private const val COLOR_STAR_USER = 0xFFFFA500.toInt()      // Orange (vote user)

    fun bind(
        rootView: View,
        contentKey: String,
        title: String,
        context: Context,
        year: String? = null,
        isTvShow: Boolean = false,
    ) {
        // Anti-reset : si le contentKey n'a pas changé, ne pas re-bind
        // (le ViewModel émet 2-3x pour la même fiche → évite de perdre les étoiles)
        val prevKey = rootView.getTag(R.id.include_community_rating) as? String
        if (prevKey == contentKey) {
            Log.d(TAG, "bind() skip — même contentKey=$contentKey")
            return
        }
        rootView.setTag(R.id.include_community_rating, contentKey)

        val star1 = rootView.findViewById<ImageView>(R.id.iv_star_1) ?: return
        val star2 = rootView.findViewById<ImageView>(R.id.iv_star_2) ?: return
        val star3 = rootView.findViewById<ImageView>(R.id.iv_star_3) ?: return
        val star4 = rootView.findViewById<ImageView>(R.id.iv_star_4) ?: return
        val star5 = rootView.findViewById<ImageView>(R.id.iv_star_5) ?: return
        val infoText = rootView.findViewById<TextView>(R.id.tv_community_rating_info) ?: return

        val stars = listOf(star1, star2, star3, star4, star5)

        Log.d(TAG, "bind() contentKey=$contentKey, stars found=${stars.size}")

        // État initial : étoiles grises
        stars.forEach { it.setColorFilter(COLOR_STAR_EMPTY) }
        infoText.text = "Chargement…"

        val deviceId = RatingService.getDeviceId(context)

        // État mutable — contentKey peut être mis à jour après résolution TMDB
        var effectiveKey = contentKey
        var currentAvg = 0.0
        var currentTotal = 0
        var currentUserRating: Int? = null
        var dpadPreview = 0

        // Scope : utilise le lifecycleOwner si dispo, sinon un scope global IO
        val scope: CoroutineScope = rootView.findViewTreeLifecycleOwner()?.lifecycleScope
            ?: CoroutineScope(Dispatchers.Main)

        // ── Résolution TMDB si le contentKey est un hash (provider sans TMDB) ──
        if (contentKey.startsWith("hash_")) {
            scope.launch {
                val resolvedId = RatingService.resolveTmdbId(title, year, isTvShow)
                if (resolvedId != null) {
                    effectiveKey = resolvedId
                    Log.d(TAG, "TMDB resolved: $contentKey → $resolvedId")
                    // Recharger la note avec la bonne clé
                    try {
                        val rating = RatingService.getRating(resolvedId, deviceId)
                        if (rating != null) {
                            currentAvg = rating.averageRating
                            currentTotal = rating.totalVotes
                            currentUserRating = rating.userRating
                            updateStars(stars, currentAvg, currentUserRating)
                            infoText.text = formatRatingInfo(currentAvg, currentTotal)
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        // ── Fonction de soumission ──
        fun submitRating(selectedRating: Int) {
            Log.d(TAG, "submitRating($selectedRating) for $effectiveKey")
            updateStarsForUserVote(stars, selectedRating)
            scope.launch {
                try {
                    RatingService.submitRating(
                        contentKey = effectiveKey,
                        deviceId = deviceId,
                        rating = selectedRating,
                        title = title,
                    )
                    val updated = RatingService.getRating(effectiveKey, deviceId)
                    if (updated != null) {
                        currentAvg = updated.averageRating
                        currentTotal = updated.totalVotes
                        currentUserRating = updated.userRating
                        withContext(Dispatchers.Main) {
                            updateStars(stars, currentAvg, currentUserRating)
                            infoText.text = formatRatingInfo(currentAvg, currentTotal)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "★ $selectedRating/5 enregistré !", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "submitRating failed", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Erreur réseau", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // ── Charger la note existante ──
        scope.launch {
            try {
                val rating = RatingService.getRating(effectiveKey, deviceId)
                withContext(Dispatchers.Main) {
                    if (rating != null) {
                        currentAvg = rating.averageRating
                        currentTotal = rating.totalVotes
                        currentUserRating = rating.userRating
                        updateStars(stars, currentAvg, currentUserRating)
                        infoText.text = formatRatingInfo(currentAvg, currentTotal)
                    } else {
                        infoText.text = "Note"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "getRating failed", e)
                withContext(Dispatchers.Main) {
                    infoText.text = "Hors ligne"
                }
            }
        }

        // ── Mobile : clic direct sur chaque étoile ──
        stars.forEachIndexed { index, star ->
            star.setOnClickListener {
                Log.d(TAG, "Star ${index+1} clicked (mobile)")
                submitRating(index + 1)
            }
        }

        // ── TV D-pad : 1 clic = mode sélection, gauche/droite = étoiles, OK = valider ──
        val starBar = rootView.findViewById<LinearLayout>(R.id.ll_star_bar)
        Log.d(TAG, "starBar found: ${starBar != null}")
        var isSelecting = false
        if (starBar != null) {
            starBar.setOnFocusChangeListener { _, hasFocus ->
                Log.d(TAG, "starBar focus=$hasFocus")
                if (hasFocus) {
                    dpadPreview = currentUserRating ?: 0
                    isSelecting = false
                    if (dpadPreview > 0) {
                        updateStarsForUserVote(stars, dpadPreview)
                    }
                    infoText.text = "OK pour noter"
                } else {
                    // Quitte le focus → annule la sélection, restore l'affichage normal
                    dpadPreview = 0
                    isSelecting = false
                    updateStars(stars, currentAvg, currentUserRating)
                    infoText.text = formatRatingInfo(currentAvg, currentTotal)
                }
            }

            // D-pad gauche/droite pour ajuster les étoiles en mode sélection
            starBar.setOnKeyListener { _, keyCode, event ->
                if (!isSelecting || event.action != android.view.KeyEvent.ACTION_DOWN) {
                    return@setOnKeyListener false
                }
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        dpadPreview = (dpadPreview + 1).coerceAtMost(5)
                        updateStarsForUserVote(stars, dpadPreview)
                        infoText.text = "★ $dpadPreview/5 · OK pour valider"
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                        dpadPreview = (dpadPreview - 1).coerceAtLeast(1)
                        updateStarsForUserVote(stars, dpadPreview)
                        infoText.text = "★ $dpadPreview/5 · OK pour valider"
                        true
                    }
                    else -> false
                }
            }

            starBar.setOnClickListener {
                if (!isSelecting) {
                    // 1er clic → entre en mode sélection, commence à 3 étoiles (milieu)
                    isSelecting = true
                    dpadPreview = currentUserRating ?: 3
                    updateStarsForUserVote(stars, dpadPreview)
                    infoText.text = "★ $dpadPreview/5 · ◄ ► puis OK"
                    Log.d(TAG, "starBar → mode sélection, preview=$dpadPreview")
                } else {
                    // 2e clic → confirme le vote
                    isSelecting = false
                    Log.d(TAG, "starBar confirmed → submit $dpadPreview")
                    infoText.text = "★ $dpadPreview/5 — envoi…"
                    submitRating(dpadPreview)
                }
            }
        }
    }

    /** Affiche la moyenne + vote user (étoiles colorées). */
    private fun updateStars(
        stars: List<ImageView>,
        averageRating: Double,
        userRating: Int?,
    ) {
        stars.forEachIndexed { index, star ->
            val starNumber = index + 1
            val color = when {
                userRating != null && starNumber <= userRating -> COLOR_STAR_USER
                starNumber <= averageRating.toInt() -> COLOR_STAR_FILLED
                starNumber == averageRating.toInt() + 1 && averageRating % 1 >= 0.5 -> COLOR_STAR_FILLED
                else -> COLOR_STAR_EMPTY
            }
            star.setColorFilter(color)
        }
    }

    /** Feedback immédiat : étoiles orange jusqu'à la note choisie. */
    private fun updateStarsForUserVote(stars: List<ImageView>, rating: Int) {
        stars.forEachIndexed { index, star ->
            star.setColorFilter(if (index < rating) COLOR_STAR_USER else COLOR_STAR_EMPTY)
        }
    }

    private fun formatRatingInfo(average: Double, totalVotes: Int): String {
        if (totalVotes == 0) return "Note"
        val avgStr = String.format("%.1f", average)
        val votesLabel = if (totalVotes == 1) "vote" else "votes"
        return "★ $avgStr ($totalVotes $votesLabel)"
    }
}
