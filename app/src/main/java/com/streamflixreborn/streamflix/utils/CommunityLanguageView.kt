package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.util.Log
import android.view.View
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
 * Câble le widget de langue communautaire (cases VF / VOSTFR / VO).
 *
 * Comportement (validé user) :
 *  - 1 vote / appareil. Tant que ce n'est pas figé, on peut voter / corriger /
 *    retirer son vote (la case votée devient un bouton orange « retirer »).
 *  - figé pour TOUT LE MONDE quand total ≥ 10 ET écart ≥ 5 (cf LanguageReportService)
 *    → plus de cases, libellé final en vert.
 *  - les votes vivent sur le serveur (Firestore) : voter/retirer = écrire/supprimer
 *    côté serveur, pas en local.
 *  - `onLabel` permet à l'appelant de refléter la langue retenue ailleurs (ex: synopsis).
 */
object CommunityLanguageView {

    private const val TAG = "CommunityLanguage"
    private const val COLOR_DEFAULT = 0xFFFFFFFF.toInt()
    private const val COLOR_USER = 0xFFFFA500.toInt()    // orange = mon vote / retirer
    private const val COLOR_LOCKED = 0xFF4CAF50.toInt()  // vert = figé/retenu
    private const val COLOR_INFO = 0xFFC0C0C0.toInt()

    fun bind(
        rootView: View,
        contentKey: String,
        title: String,
        context: Context,
        onLabel: ((String?) -> Unit)? = null,
    ) {
        val btnVf = rootView.findViewById<TextView>(R.id.btn_lang_vf) ?: return
        val btnVostfr = rootView.findViewById<TextView>(R.id.btn_lang_vostfr) ?: return
        val btnVo = rootView.findViewById<TextView>(R.id.btn_lang_vo) ?: return
        val info = rootView.findViewById<TextView>(R.id.tv_community_language_info) ?: return

        val buttons = mapOf(
            LanguageReportService.Lang.VF to btnVf,
            LanguageReportService.Lang.VOSTFR to btnVostfr,
            LanguageReportService.Lang.VO to btnVo,
        )

        val deviceId = RatingService.getDeviceId(context)
        val scope: CoroutineScope = rootView.findViewTreeLifecycleOwner()?.lifecycleScope
            ?: CoroutineScope(Dispatchers.Main)

        info.text = "Chargement…"
        var current: LanguageReportService.LanguageInfo? = null

        fun render(data: LanguageReportService.LanguageInfo?) {
            current = data
            if (data == null) {
                buttons.values.forEach { it.visibility = View.GONE }
                info.text = "Hors ligne"
                info.setTextColor(COLOR_INFO)
                return
            }
            when {
                // Figé : plus de vote possible, libellé final.
                data.locked -> {
                    buttons.values.forEach { it.visibility = View.GONE }
                    info.text = "${data.label} ✓"
                    info.setTextColor(COLOR_LOCKED)
                }
                // Cet appareil a voté : on ne montre que SA case (orange) = « retirer ».
                data.userVote != null -> {
                    buttons.forEach { (lang, btn) ->
                        val mine = data.userVote.equals(lang.name, ignoreCase = true)
                        btn.visibility = if (mine) View.VISIBLE else View.GONE
                        btn.setTextColor(COLOR_USER)
                    }
                    info.text = "${data.label} (${data.total})"
                    info.setTextColor(COLOR_INFO)
                }
                // Pas encore voté : on montre les 3 cases.
                else -> {
                    buttons.forEach { (_, btn) ->
                        btn.visibility = View.VISIBLE
                        btn.setTextColor(COLOR_DEFAULT)
                    }
                    info.text = if (data.total == 0) "Quelle langue ? Aide la communauté"
                    else "${data.label} (${data.total})"
                    info.setTextColor(COLOR_INFO)
                }
            }
            onLabel?.invoke(if (data.locked || data.total > 0) data.label else null)
        }

        fun reload() {
            scope.launch {
                val d = LanguageReportService.getLanguage(contentKey, deviceId)
                withContext(Dispatchers.Main) { render(d) }
            }
        }

        fun vote(lang: LanguageReportService.Lang) {
            info.text = "${lang.name} — envoi…"
            scope.launch {
                try {
                    LanguageReportService.submitVote(contentKey, deviceId, lang, title)
                    val updated = LanguageReportService.getLanguage(contentKey, deviceId)
                    withContext(Dispatchers.Main) {
                        render(updated)
                        Toast.makeText(context, "Langue ${lang.name} enregistrée !", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "vote failed", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Erreur réseau", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        fun remove() {
            info.text = "Suppression…"
            scope.launch {
                try {
                    LanguageReportService.removeVote(contentKey, deviceId)
                    val updated = LanguageReportService.getLanguage(contentKey, deviceId)
                    withContext(Dispatchers.Main) {
                        render(updated)
                        Toast.makeText(context, "Vote retiré", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "remove failed", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Erreur réseau", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        buttons.forEach { (lang, btn) ->
            btn.setOnClickListener {
                val mine = current?.userVote
                if (mine != null && mine.equals(lang.name, ignoreCase = true)) remove() else vote(lang)
            }
        }

        reload()
    }
}
