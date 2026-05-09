package com.streamflixreborn.streamflix.fragments.extractor_stats

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.utils.ExtractorFailureTracker
import com.streamflixreborn.streamflix.utils.ExtractorFailureTracker.FailureEntry

/**
 * Onglet "Extracteurs" — affiche les extracteurs ayant échoué depuis la
 * dernière mise à jour de l'app, triés par nombre d'échecs décroissant.
 *
 *  - Compteurs persistés via [ExtractorFailureTracker] (auto-reset au bump
 *    de versionCode).
 *  - Pour chaque extracteur, affiche :
 *      * le **type d'erreur** (timeout / 403 / parsing / …)
 *        → "serveur mort" vs "ils ont changé leur HTML"
 *      * le **provider source** (Movix / FrenchStream / …)
 *        → cible le fix au bon endroit
 *  - Bouton "Copier" → met le rapport dans le presse-papier
 *  - Bouton "Rapport bug Telegram" → copie + ouvre le canal Telegram
 *  - Bouton "Effacer" → wipe local des compteurs.
 */
open class ExtractorStatsMobileFragment : Fragment() {

    private val TELEGRAM_CHANNEL = "https://t.me/+Jxyj7znoNQsyMjg0"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_extractor_stats, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val toolbar = view.findViewById<Toolbar>(R.id.toolbar_extractor_stats)
        val rv = view.findViewById<RecyclerView>(R.id.rv_extractor_failures)
        val empty = view.findViewById<TextView>(R.id.tv_extractor_stats_empty)
        val header = view.findViewById<TextView>(R.id.tv_extractor_stats_header)
        val btnCopy = view.findViewById<Button>(R.id.btn_extractor_stats_copy)
        val btnTelegram = view.findViewById<Button>(R.id.btn_extractor_stats_telegram)
        val btnClear = view.findViewById<Button>(R.id.btn_extractor_stats_clear)

        toolbar.setNavigationOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }

        val adapter = FailuresAdapter()
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        fun refresh() {
            val data = ExtractorFailureTracker.getFailures()
            adapter.submit(data)
            if (data.isEmpty()) {
                rv.visibility = View.GONE
                empty.visibility = View.VISIBLE
            } else {
                rv.visibility = View.VISIBLE
                empty.visibility = View.GONE
            }
            // Header explicite : ces compteurs reflètent les échecs CONSÉCUTIFS
            // (un succès reset le compteur). Reset auto au bump versionCode.
            header.text = "Échecs consécutifs depuis dernier succès — v${BuildConfig.VERSION_NAME} • " +
                "${data.size} extracteur${if (data.size > 1) "s" else ""} en échec"
        }
        refresh()

        // Helper : copie le rapport dans le clipboard (utilisé par "Copier" et
        // "Rapport Telegram" qui pré-remplit avant d'ouvrir le canal).
        fun copyReportToClipboard(): String {
            val data = ExtractorFailureTracker.getFailures()
            val report = buildBugReport(data)
            val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Streamflix bug report", report))
            return report
        }

        btnCopy.setOnClickListener {
            copyReportToClipboard()
            android.widget.Toast.makeText(
                requireContext(),
                "Rapport copié — tu peux le coller où tu veux",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }

        btnTelegram.setOnClickListener {
            // Copie + ouvre Telegram (le user n'a plus qu'à coller).
            copyReportToClipboard()
            android.widget.Toast.makeText(
                requireContext(),
                "Rapport copié — colle-le dans Telegram",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TELEGRAM_CHANNEL)))
        }

        btnClear.setOnClickListener {
            ExtractorFailureTracker.resetAll()
            refresh()
            android.widget.Toast.makeText(
                requireContext(),
                "Compteurs effacés",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun buildBugReport(data: List<FailureEntry>): String {
        val sb = StringBuilder()
        sb.append("📊 Rapport extracteurs — Streamflix v${BuildConfig.VERSION_NAME}\n")
        sb.append("Build: ${BuildConfig.VERSION_CODE}\n")
        sb.append("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE})\n\n")
        if (data.isEmpty()) {
            sb.append("Aucun échec enregistré.")
        } else {
            sb.append("Extracteurs en échec :\n")
            data.forEach { entry ->
                sb.append("• ${entry.name} : ${entry.count}")
                // Breakdown par type d'erreur — "(timeout x3, 403 x2)" — utile
                // pour identifier instantanément si le serveur est mort, geo-bloqué,
                // ou si le HTML a changé.
                if (entry.errors.isNotEmpty()) {
                    sb.append(" (")
                    sb.append(entry.errors.joinToString(", ") { (type, n) -> "$type x$n" })
                    sb.append(")")
                }
                // Provider source — "via Movix x4, FrenchStream x1" — permet de
                // cibler le fix : si l'extracteur n'échoue que via un provider,
                // c'est probablement la chaîne de backup de ce provider qui passe
                // une mauvaise URL d'iframe, pas l'extracteur lui-même.
                if (entry.providers.isNotEmpty()) {
                    sb.append(" — via ")
                    sb.append(entry.providers.joinToString(", ") { (p, n) -> "$p x$n" })
                }
                sb.append("\n")
            }
        }
        return sb.toString()
    }

    // ─────────── Adapter ───────────

    private class FailuresAdapter : RecyclerView.Adapter<FailuresAdapter.VH>() {
        private val items = mutableListOf<FailureEntry>()

        fun submit(data: List<FailureEntry>) {
            items.clear()
            items.addAll(data)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_extractor_failure, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = items[position]
            holder.name.text = entry.name
            holder.count.text = entry.count.toString()

            // Ligne errors : "timeout ×3, 403 ×2"
            // S'il n'y a pas de breakdown (anciennes données format v1 sans
            // type d'erreur), fallback sur "échec(s) d'extraction".
            holder.errors.text = if (entry.errors.isNotEmpty()) {
                entry.errors.joinToString(", ") { (type, n) -> "$type ×$n" }
            } else {
                "échec(s) d'extraction"
            }

            // Ligne providers : "via Movix ×4, FrenchStream ×1"
            // Si pas connu, on cache la ligne pour ne pas afficher du vide.
            if (entry.providers.isNotEmpty()) {
                holder.providers.visibility = View.VISIBLE
                holder.providers.text = "via " + entry.providers.joinToString(", ") { (p, n) -> "$p ×$n" }
            } else {
                holder.providers.visibility = View.GONE
            }
        }

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val name: TextView = itemView.findViewById(R.id.tv_extractor_name)
            val count: TextView = itemView.findViewById(R.id.tv_extractor_count)
            val errors: TextView = itemView.findViewById(R.id.tv_extractor_errors)
            val providers: TextView = itemView.findViewById(R.id.tv_extractor_providers)
        }
    }
}
