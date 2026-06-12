package com.streamflixreborn.streamflix.providers

import android.content.Context

/**
 * 2026-06-10 — Dialogs réutilisables pour gérer les sources World TV.
 *  Marche sur TV et Mobile (= juste AlertDialog standard).
 */
object WorldLiveSourcesDialog {

    fun showManager(context: Context, onChanged: () -> Unit = {}) {
        val all = WorldLiveSourcesStore.allSources(context)
        val activeIdx = WorldLiveSourcesStore.getActiveIndex(context)
        val labels = all.mapIndexed { idx, src ->
            val active = if (idx == activeIdx) " ✓" else ""
            val builtin = if (src.isBuiltin) "  [prédéfini]" else ""
            "${src.name}${active}${builtin}"
        }.toTypedArray()

        android.app.AlertDialog.Builder(context)
            .setTitle("Mes sources World TV")
            .setItems(labels) { _, idx ->
                showSourceActions(context, idx, all[idx], onChanged)
            }
            // 2026-06-10 (user "option pour télécharger des fichiers JSON
            //   dans l'app") : 3 façons d'ajouter une source :
            //   - URL en ligne (= fetch live à chaque chargement)
            //   - Télécharger URL (= download + stocke en local, offline OK)
            //   - Importer fichier local (= SAF picker, no permission)
            .setNeutralButton("+ Ajouter") { _, _ -> showAddOptionsDialog(context, onChanged) }
            .setNegativeButton("Fermer", null)
            .show()
    }

    /** 2026-06-10 : menu de choix de la méthode d'ajout. */
    private fun showAddOptionsDialog(context: Context, onChanged: () -> Unit) {
        val options = arrayOf(
            "🌐 URL en ligne (fetch live)",
            "⬇️ Télécharger depuis une URL (stocké en local)",
            "📁 Importer un fichier local (.json/.txt)",
        )
        android.app.AlertDialog.Builder(context)
            .setTitle("Ajouter une source")
            .setItems(options) { _, idx ->
                when (idx) {
                    0 -> showAddDialog(context, onChanged)
                    1 -> showDownloadDialog(context, onChanged)
                    2 -> {
                        // Lance l'activity SAF qui s'occupe de tout
                        val intent = android.content.Intent(
                            context,
                            com.streamflixreborn.streamflix.activities
                                .WorldLiveFileImportActivity::class.java,
                        )
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** 2026-06-10 : télécharge un fichier JSON depuis une URL et le stocke
     *  dans le storage interne de l'app. Source ajoutée avec URL `file://...`
     *  → marche offline après le 1er download. */
    private fun showDownloadDialog(context: Context, onChanged: () -> Unit) {
        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val nameInput = android.widget.EditText(context).apply {
            hint = "Nom (ex: Ma playlist)"
        }
        val urlInput = android.widget.EditText(context).apply {
            hint = "URL (https://…)"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        container.addView(nameInput)
        container.addView(urlInput)
        android.app.AlertDialog.Builder(context)
            .setTitle("Télécharger une source")
            .setView(container)
            .setPositiveButton("Télécharger") { _, _ ->
                val name = nameInput.text.toString().trim()
                val url = urlInput.text.toString().trim()
                if (name.isBlank() || url.isBlank() || !url.startsWith("http")) {
                    android.widget.Toast.makeText(
                        context, "Nom et URL requis", android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    return@setPositiveButton
                }
                android.widget.Toast.makeText(
                    context, "Téléchargement en cours…", android.widget.Toast.LENGTH_SHORT,
                ).show()
                Thread {
                    try {
                        val client = okhttp3.OkHttpClient()
                        val req = okhttp3.Request.Builder().url(url)
                            .header("User-Agent",
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:115.0) " +
                                        "Gecko/20100101 Firefox/115.0")
                            .header("Accept", "application/json, text/plain, */*")
                            .build()
                        val body = client.newCall(req).execute().use {
                            if (!it.isSuccessful) throw RuntimeException("HTTP ${it.code}")
                            it.body?.string() ?: throw RuntimeException("body vide")
                        }
                        val hash = url.hashCode().toString().replace("-", "n")
                        val dir = java.io.File(context.filesDir, "wl_local_sources")
                            .apply { mkdirs() }
                        val file = java.io.File(dir, "$hash.json")
                        file.writeText(body, Charsets.UTF_8)
                        WorldLiveSourcesStore.add(context, name, "file://${file.absolutePath}")
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            android.widget.Toast.makeText(
                                context, "Téléchargé : $name",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                            kotlin.runCatching {
                                com.streamflixreborn.streamflix.providers
                                    .WorldLiveTvProvider.invalidateCache()
                            }
                            kotlin.runCatching {
                                com.streamflixreborn.streamflix.utils.ProviderChangeNotifier
                                    .notifyProviderChanged(forceRelaunch = true)
                            }
                            onChanged()
                        }
                    } catch (e: Throwable) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            android.widget.Toast.makeText(
                                context, "Échec : ${e.message}",
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }.start()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showSourceActions(
        context: Context, indexInAll: Int, source: WorldLiveSourcesStore.Source,
        onChanged: () -> Unit,
    ) {
        val actions = if (source.isBuiltin) {
            arrayOf("✓ Activer cette source")
        } else {
            arrayOf("✓ Activer cette source", "Modifier", "Supprimer")
        }
        android.app.AlertDialog.Builder(context)
            .setTitle(source.name)
            .setItems(actions) { _, action ->
                when (action) {
                    0 -> {
                        WorldLiveSourcesStore.setActiveIndex(context, indexInAll)
                        android.widget.Toast.makeText(context,
                            "Source active : ${source.name} — chargement…",
                            android.widget.Toast.LENGTH_SHORT).show()
                        // 2026-06-10 (user "ne se charge pas") : invalide
                        //   l'intérieur du provider (channelRegistry) pour
                        //   FORCER un re-fetch avec la nouvelle URL.
                        kotlin.runCatching {
                            com.streamflixreborn.streamflix.providers
                                .WorldLiveTvProvider.invalidateCache()
                        }
                        kotlin.runCatching {
                            com.streamflixreborn.streamflix.utils.HomeCacheStore.clear(
                                context,
                                com.streamflixreborn.streamflix.providers.LiveTvHubPlusProvider,
                            )
                        }
                        kotlin.runCatching {
                            // 2026-06-10 : forceRelaunch=true → HomeViewModel
                            //   relance même si un job tourne déjà pour le
                            //   même provider (= notre cas : on reste sur
                            //   World Live mais on veut un re-fetch).
                            com.streamflixreborn.streamflix.utils.ProviderChangeNotifier
                                .notifyProviderChanged(forceRelaunch = true)
                        }
                        onChanged()
                    }
                    1 -> {
                        val personIdx = indexInAll - WorldLiveSourcesStore.BUILTIN_SOURCES.size
                        showEditDialog(context, personIdx, source, onChanged)
                    }
                    2 -> {
                        val personIdx = indexInAll - WorldLiveSourcesStore.BUILTIN_SOURCES.size
                        WorldLiveSourcesStore.remove(context, personIdx)
                        android.widget.Toast.makeText(context,
                            "Supprimée : ${source.name}",
                            android.widget.Toast.LENGTH_SHORT).show()
                        onChanged()
                    }
                }
            }
            .setNegativeButton("Retour", null)
            .show()
    }

    private fun showAddDialog(context: Context, onChanged: () -> Unit) {
        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val nameInput = android.widget.EditText(context).apply {
            hint = "Nom (ex: Ma playlist)"
        }
        val urlInput = android.widget.EditText(context).apply {
            hint = "URL JSON 3box-tv (ex: http://dric4rt.free.fr/1.json)"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        container.addView(nameInput)
        container.addView(urlInput)

        android.app.AlertDialog.Builder(context)
            .setTitle("Ajouter une source")
            .setView(container)
            .setPositiveButton("Ajouter") { _, _ ->
                val name = nameInput.text.toString().trim()
                val url = urlInput.text.toString().trim()
                if (name.isBlank() || url.isBlank()) {
                    android.widget.Toast.makeText(context,
                        "Nom + URL requis", android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                WorldLiveSourcesStore.add(context, name, url)
                android.widget.Toast.makeText(context,
                    "Ajoutée : $name", android.widget.Toast.LENGTH_SHORT).show()
                onChanged()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showEditDialog(
        context: Context, personIdx: Int, source: WorldLiveSourcesStore.Source,
        onChanged: () -> Unit,
    ) {
        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val nameInput = android.widget.EditText(context).apply {
            setText(source.name)
        }
        val urlInput = android.widget.EditText(context).apply {
            setText(source.url)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        container.addView(nameInput)
        container.addView(urlInput)

        android.app.AlertDialog.Builder(context)
            .setTitle("Modifier la source")
            .setView(container)
            .setPositiveButton("Enregistrer") { _, _ ->
                val name = nameInput.text.toString().trim()
                val url = urlInput.text.toString().trim()
                if (name.isBlank() || url.isBlank()) return@setPositiveButton
                WorldLiveSourcesStore.update(context, personIdx, name, url)
                android.widget.Toast.makeText(context,
                    "Modifiée : $name", android.widget.Toast.LENGTH_SHORT).show()
                onChanged()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
