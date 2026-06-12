package com.streamflixreborn.streamflix.utils

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import kotlin.math.max
import kotlin.math.min

/**
 * 2026-06-12 — Placeholder universel pour les chaînes IPTV qui n'ont pas
 * d'image (logo 404, URL vide, etc.) — peu importe la playlist (World Live,
 * 3boxTv, Dric4r, OLA, Vavoo, etc.).
 *
 * Dessine un fond SEMI-TRANSPARENT (alpha ~140 noir) + le nom de la chaîne
 * centré en blanc, sur 1 à 3 lignes selon la longueur. Le fond du card
 * derrière reste visible (= « voir le fond d'écran derrière » demandé par l'user).
 *
 * Implémentation full-Canvas → zéro requête réseau, instantané, scale-friendly.
 */
class ChannelPlaceholderDrawable(
    private val name: String,
    private val bgAlpha: Int = 140,
) : Drawable() {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(bgAlpha, 0, 0, 0)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        setShadowLayer(4f, 0f, 1f, Color.argb(200, 0, 0, 0))
    }

    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 255, 255)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return

        // 1) Fond semi-transparent qui laisse passer le fond du card derrière.
        canvas.drawRect(b, bgPaint)

        // 2) Léger trait d'accent en bas (= petit liseré blanc subtil pour signifier
        //    « tuile chaîne » sans casser la transparence).
        val accentH = max(2f, b.height() * 0.012f)
        canvas.drawRect(
            b.left.toFloat(),
            b.bottom - accentH,
            b.right.toFloat(),
            b.bottom.toFloat(),
            accentPaint
        )

        // 3) Texte du nom centré, scalé selon la taille de la tuile.
        val padding = b.width() * 0.08f
        val maxW = b.width() - 2f * padding
        val maxH = b.height() * 0.55f

        val cleaned = name
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "?" }

        // Découpe en max 3 lignes en cherchant les espaces les plus proches du tiers/deux-tiers.
        val lines = splitIntoLines(cleaned, maxLines = 3)

        // Trouve la taille de police max qui fait tenir tout le bloc.
        var size = b.height() * 0.22f
        repeat(8) {
            textPaint.textSize = size
            val widest = lines.maxOf { textPaint.measureText(it) }
            val lineH = textPaint.descent() - textPaint.ascent()
            val totalH = lineH * lines.size
            if (widest <= maxW && totalH <= maxH) return@repeat
            size *= 0.88f
        }
        textPaint.textSize = max(size, b.height() * 0.07f)

        val lineH = textPaint.descent() - textPaint.ascent()
        val totalH = lineH * lines.size
        val cx = b.exactCenterX()
        var y = b.exactCenterY() - totalH / 2f - textPaint.ascent()
        for (line in lines) {
            canvas.drawText(line, cx, y, textPaint)
            y += lineH
        }
    }

    private fun splitIntoLines(text: String, maxLines: Int): List<String> {
        if (text.length <= 10 || !text.contains(' ')) return listOf(text)
        val words = text.split(' ')
        if (words.size == 1) return listOf(text)
        if (maxLines <= 1) return listOf(text)

        // Heuristique : équilibrer les lignes par longueur de caractères.
        val targetLen = text.length / min(maxLines, words.size)
        val lines = mutableListOf<StringBuilder>()
        var current = StringBuilder()
        for (w in words) {
            if (current.isEmpty()) {
                current.append(w)
            } else if (current.length + 1 + w.length <= targetLen + 4 || lines.size >= maxLines - 1) {
                current.append(' ').append(w)
            } else {
                lines.add(current)
                current = StringBuilder(w)
            }
        }
        if (current.isNotEmpty()) lines.add(current)
        return lines.map { it.toString() }
    }

    override fun setAlpha(alpha: Int) {
        bgPaint.alpha = (bgAlpha * alpha) / 255
        textPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        textPaint.colorFilter = colorFilter
        bgPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = -1
    override fun getIntrinsicHeight(): Int = -1

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        invalidateSelf()
    }
}
