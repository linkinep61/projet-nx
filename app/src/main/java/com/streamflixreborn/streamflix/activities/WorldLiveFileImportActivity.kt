package com.streamflixreborn.streamflix.activities

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.streamflixreborn.streamflix.providers.WorldLiveSourcesStore
import com.streamflixreborn.streamflix.providers.WorldLiveTvProvider
import com.streamflixreborn.streamflix.utils.ProviderChangeNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 2026-06-10 (user "option pour ajouter des fichiers JSON dans l'app") :
 *  activity transparente qui lance le SAF (Storage Access Framework) picker
 *  pour permettre à l'user de choisir un fichier .json/.txt local. Le
 *  fichier est copié dans le storage interne de l'app et ajouté comme
 *  source World Live avec URL `file://...`.
 *
 *  Avantage SAF : AUCUNE permission Android requise (ni READ_EXTERNAL_STORAGE
 *  ni MANAGE_EXTERNAL_STORAGE). Marche sur tous les Android 4.4+.
 */
class WorldLiveFileImportActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            finish()
            return@registerForActivityResult
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val body = contentResolver.openInputStream(uri)?.bufferedReader()
                    ?.use { it.readText() }
                if (body.isNullOrBlank()) {
                    showToast("Fichier vide ou illisible")
                    finish(); return@launch
                }
                val displayName = getDisplayName(uri) ?: "Source importée"
                val nameNoExt = displayName.substringBeforeLast('.')
                val hash = uri.toString().hashCode().toString().replace("-", "n")
                val dir = File(filesDir, "wl_local_sources").apply { mkdirs() }
                val file = File(dir, "$hash.json")
                file.writeText(body, Charsets.UTF_8)
                val fileUrl = "file://${file.absolutePath}"
                WorldLiveSourcesStore.add(this@WorldLiveFileImportActivity, nameNoExt, fileUrl)
                // Invalide le cache provider + notifie le home
                kotlin.runCatching { WorldLiveTvProvider.invalidateCache() }
                kotlin.runCatching {
                    ProviderChangeNotifier.notifyProviderChanged(forceRelaunch = true)
                }
                showToast("Importé : $nameNoExt")
            } catch (e: Throwable) {
                showToast("Échec import : ${e.message}")
            } finally {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 2026-06-25 : ne lancer le picker QUE sur un fresh start (pas après
        //   process death / recreation → sinon le picker ouvre en boucle).
        if (savedInstanceState != null) return
        try {
            // 2026-06-25 : MIME type simplifié à "*/*" seul — certains file
            //   pickers OEM (ColorOS OPPO, MagicOS Honor) crashent sur le
            //   combo ["application/json","text/*","*/*"].
            launcher.launch(arrayOf("*/*"))
        } catch (e: Throwable) {
            Toast.makeText(this, "Sélecteur de fichiers indisponible", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun getDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) cursor.getString(nameIdx) else null
                } else null
            }
        } catch (_: Throwable) { null }
    }

    private suspend fun showToast(msg: String) = withContext(Dispatchers.Main) {
        Toast.makeText(this@WorldLiveFileImportActivity, msg, Toast.LENGTH_LONG).show()
    }
}
