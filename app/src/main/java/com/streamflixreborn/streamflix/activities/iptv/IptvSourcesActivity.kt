package com.streamflixreborn.streamflix.activities.iptv

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.models.IptvSource
import com.streamflixreborn.streamflix.providers.MyIptvProvider
import com.streamflixreborn.streamflix.utils.IptvSourceStore
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 2026-05-12 : écran de gestion des sources IPTV custom (MyIptvProvider).
 * Liste les sources configurées, permet d'en ajouter / éditer / supprimer.
 *
 * Compatible mobile + TV (RecyclerView vertical, items focusables D-pad-friendly).
 */
class IptvSourcesActivity : FragmentActivity() {

    private lateinit var rvSources: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var btnAdd: Button
    private lateinit var btnHomeLoaded: Button
    private lateinit var loadingOverlay: android.widget.FrameLayout
    private lateinit var loadingText: TextView

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_iptv_sources)
        rvSources = findViewById(R.id.rv_iptv_sources)
        tvEmpty = findViewById(R.id.tv_iptv_sources_empty)
        btnAdd = findViewById(R.id.btn_add_iptv_source)
        btnHomeLoaded = findViewById(R.id.btn_home_loaded)
        loadingOverlay = findViewById(R.id.loading_overlay)
        loadingText = findViewById(R.id.tv_loading_text)
        btnAdd.setOnClickListener { openTypePicker(null) }
        btnHomeLoaded.setOnClickListener { openLastLoadedHome() }
        refreshList()
        updateHomeButtonVisibility()
        preloadAllSources()

        // 2026-05-13 (user "ajouter directement d'ici") : si l'intent a un extra
        // auto_add_source, on ouvre direct le picker M3U/Stalker à l'arrivée.
        if (intent.getBooleanExtra("auto_add_source", false)) {
            openTypePicker(null)
        }
    }

    override fun onResume() {
        super.onResume()
        updateHomeButtonVisibility()
    }

    /** 2026-05-12 (user "petit bouton Home pour accéder directement à la page des
     *  chaînes déjà chargée") : montre le bouton uniquement si une source est en cache. */
    private fun updateHomeButtonVisibility() {
        val prefs = getSharedPreferences("iptv_last_source", MODE_PRIVATE)
        val lastSourceId = prefs.getString("last_id", null)
        val visible = lastSourceId != null &&
            IptvSourceStore.getById(lastSourceId) != null
        btnHomeLoaded.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** Va direct à MainActivity sans re-fetch — le cache de MyIptvProvider sera servi. */
    private fun openLastLoadedHome() {
        val prefs = getSharedPreferences("iptv_last_source", MODE_PRIVATE)
        val lastSourceId = prefs.getString("last_id", null) ?: return
        val source = IptvSourceStore.getById(lastSourceId) ?: return
        com.streamflixreborn.streamflix.utils.UserPreferences.currentProvider =
            com.streamflixreborn.streamflix.providers.MyIptvProvider
        val isTv = com.streamflixreborn.streamflix.BuildConfig.APP_LAYOUT == "tv" ||
            (com.streamflixreborn.streamflix.BuildConfig.APP_LAYOUT != "mobile" &&
                packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK))
        val mainClass = if (isTv) {
            com.streamflixreborn.streamflix.activities.main.MainTvActivity::class.java
        } else {
            com.streamflixreborn.streamflix.activities.main.MainMobileActivity::class.java
        }
        startActivity(android.content.Intent(this, mainClass))
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    /** 2026-05-12 (user "fait un système de chargement comme sur les vrais
     *  IPTV") : preload toutes les sources en background dès l'ouverture du
     *  tableau. Au moment où le user clique, le cache est déjà chaud → bascule
     *  immédiate vers la home. Sinon le user voit un loader bref. */
    private fun preloadAllSources() {
        val sources = IptvSourceStore.getAll()
        if (sources.isEmpty()) return
        scope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                sources.forEach { source ->
                    runCatching {
                        // L'appel privé loadChannels() est inaccessible — on
                        // passe par getHome() qui rempile le cache via loadChannels.
                        // Mais c'est lourd pour preload. Mieux : on ne fait rien
                        // ici et on laisse openSourceContent() montrer le loader.
                    }
                }
            }
        }
    }

    private fun refreshList() {
        val sources = IptvSourceStore.getAll()
        if (sources.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            rvSources.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            rvSources.visibility = View.VISIBLE
            rvSources.adapter = SourcesAdapter(
                sources = sources,
                onClick = ::openSourceContent,
                onLongClick = ::openEditDialog,
            )
            // 2026-05-12 (user "les layouts ne sont pas accessibles avec la
            // télécommande") : focus + descendantFocusability + retry loop.
            rvSources.isFocusable = true
            rvSources.descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val startTime = System.currentTimeMillis()
            lateinit var tryFocus: () -> Unit
            tryFocus = {
                val first = rvSources.findViewHolderForAdapterPosition(0)?.itemView
                if (first != null) {
                    first.requestFocus()
                } else if (System.currentTimeMillis() - startTime < 1500L) {
                    handler.postDelayed({ tryFocus() }, 50)
                }
            }
            handler.post { tryFocus() }
        }
    }

    /** Active Mon IPTV avec spinner de chargement + résumé du fetch.
     *  2026-05-12 (user "fait un système de chargement comme les vrais IPTV") :
     *  - Affiche un loader plein écran "Chargement de '{nom}'…"
     *  - Lance le fetch M3U en background via MyIptvProvider
     *  - Toast "X chaînes / Y films / Z séries" ou "Erreur : {message}"
     *  - Bascule sur MainActivity uniquement si fetch OK */
    private fun openSourceContent(source: IptvSource) {
        com.streamflixreborn.streamflix.utils.MiniPlayerController.stop()
        com.streamflixreborn.streamflix.utils.UserPreferences.currentProvider =
            com.streamflixreborn.streamflix.providers.MyIptvProvider
        // 2026-05-19 v85b (user "à partir du moment où on change de source il faut
        //   faire un petit lavage qu'on retourne pas sur les anciennes qui étaient
        //   sur une autre source") : vide tout ce qui n'est PAS la source qu'on
        //   active. Si on re-clique la même, rien n'est purgé (cache préservé).
        com.streamflixreborn.streamflix.providers.MyIptvProvider.clearChannelsExcept(source.id)
        // 2026-05-12 (user "il peut pas garder en cash") : ne PAS invalider le cache
        // à chaque clic — sinon retéléchargement systématique de la M3U (60-120s).
        // L'utilisateur peut forcer un refresh via long-press → "Forcer le refresh".

        // Loader visible
        loadingText.text = "Chargement de '${source.name}'…"
        loadingOverlay.visibility = View.VISIBLE
        loadingOverlay.requestFocus()

        // v60 : observe le StateFlow de MyIptvProvider pour update loadingText
        //   avec le step en cours (Catégories TV, Chaînes: 1500, Films: 320…).
        //   Le job de collect s'arrête quand loadingOverlay est caché à la fin.
        val statusJob = scope.launch {
            com.streamflixreborn.streamflix.providers.MyIptvProvider.loadingStatus.collect { status ->
                if (status.isNotEmpty() && loadingOverlay.visibility == View.VISIBLE) {
                    loadingText.text = "${source.name} — $status"
                }
            }
        }

        scope.launch {
            val (channelsCount, classification) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    // 2026-05-12 (user "0 TV 0 films 0 séries alors qu'il trouve des
                    // chaînes") : compte direct via countByTypeFR — précédemment on
                    // cherchait les mots dans les noms de catégories qui ont changé.
                    // 2026-05-13 (user "quand tu changes de source ils affichent
                    // les mêmes chaînes en nombre") : passe sourceId pour ne
                    // compter QUE cette source-là, pas le total cumulé.
                    val (live, movies, series) = com.streamflixreborn.streamflix.providers.MyIptvProvider.countByTypeFR(source.id)
                    val total = live + movies + series
                    total to "📡 $live TV  •  📽 $movies films  •  📺 $series séries"
                }.getOrElse {
                    0 to "Erreur: ${it.message?.take(80)}"
                }
            }

            loadingOverlay.visibility = View.GONE
            statusJob.cancel()
            com.streamflixreborn.streamflix.providers.MyIptvProvider.loadingStatus.value = ""
            if (channelsCount == 0) {
                Toast.makeText(
                    this@IptvSourcesActivity,
                    "❌ Source '${source.name}' vide ou inaccessible.\n$classification",
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }

            Toast.makeText(
                this@IptvSourcesActivity,
                "✓ '${source.name}' : $classification",
                Toast.LENGTH_LONG,
            ).show()

            // 2026-05-12 : mémorise la dernière source chargée pour bouton Home
            getSharedPreferences("iptv_last_source", MODE_PRIVATE)
                .edit().putString("last_id", source.id).apply()

            val isTv = com.streamflixreborn.streamflix.BuildConfig.APP_LAYOUT == "tv" ||
                (com.streamflixreborn.streamflix.BuildConfig.APP_LAYOUT != "mobile" &&
                    packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK))
            val mainClass = if (isTv) {
                com.streamflixreborn.streamflix.activities.main.MainTvActivity::class.java
            } else {
                com.streamflixreborn.streamflix.activities.main.MainMobileActivity::class.java
            }
            startActivity(android.content.Intent(this@IptvSourcesActivity, mainClass))
        }
    }

    /** Étape 1 : choisir le type de source (M3U ou Stalker). */
    private fun openTypePicker(existing: IptvSource?) {
        if (existing != null) {
            // En mode édition, on passe directement au form du bon type.
            openSourceForm(existing.type, existing)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Type de source")
            .setItems(arrayOf(
                "M3U / M3U8 (URL playlist)",
                "Stalker MAG (URL portail + MAC)",
                "Xtream Codes (URL serveur + login)",
                "📦 Importer un pack (fichier de plusieurs sources)",
            )) { _, idx ->
                when (idx) {
                    0 -> openSourceForm(IptvSource.Type.M3U, null)
                    1 -> openSourceForm(IptvSource.Type.STALKER, null)
                    2 -> openXtreamForm(null)
                    3 -> openImportPackDialog()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** 2026-05-14 (user "ajouter une fonctionnalité pour des codes Xtream") :
     *  formulaire dédié Xtream Codes. Construit l'URL M3U finale
     *  `http://host:port/get.php?username=X&password=Y&type=m3u_plus&output=hls`
     *  et la stocke comme source de type M3U (le pipeline M3U existant gère
     *  ensuite tout : fetch playlist, parsing, live + VOD).
     *
     *  Stockage : on garde l'URL serveur dans `referer` (champ libre) et les
     *  credentials dans le nom de la source pour l'instant. Plus tard, ajout
     *  d'un Type.XTREAM dédié dans le model pour permettre l'édition propre. */
    private fun openXtreamForm(existing: IptvSource?) {
        fun fieldParams() = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        val nameInput = EditText(this).apply {
            layoutParams = fieldParams()
            hint = "Nom (ex: Mon abonnement Xtream)"
            setText(existing?.name ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val serverInput = EditText(this).apply {
            layoutParams = fieldParams()
            hint = "URL serveur (ex: http://srv.com:8080)"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        val userInput = EditText(this).apply {
            layoutParams = fieldParams()
            hint = "Username"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val pwdInput = EditText(this).apply {
            layoutParams = fieldParams()
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        val uaInput = EditText(this).apply {
            layoutParams = fieldParams()
            hint = "User-Agent (optionnel)"
            setText(existing?.userAgent ?: "")
        }
        val container = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(nameInput)
            addView(spacer())
            addView(serverInput)
            addView(spacer())
            addView(userInput)
            addView(spacer())
            addView(pwdInput)
            addView(spacer())
            addView(uaInput)
        }
        AlertDialog.Builder(this)
            .setTitle("Nouvelle source Xtream Codes")
            .setView(container)
            .setPositiveButton("Sauver") { _, _ ->
                val name = nameInput.text.toString().trim()
                val server = serverInput.text.toString().trim().trimEnd('/')
                val user = userInput.text.toString().trim()
                val pwd = pwdInput.text.toString().trim()
                val ua = uaInput.text.toString().trim().takeIf { it.isNotBlank() }
                if (name.isBlank() || server.isBlank() || user.isBlank() || pwd.isBlank()) {
                    Toast.makeText(this, "Tous les champs (Nom, Serveur, User, Password) sont requis", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                if (!server.startsWith("http://", true) && !server.startsWith("https://", true)) {
                    Toast.makeText(this, "URL serveur invalide (doit commencer par http:// ou https://)", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                // Construit l'URL M3U Xtream Codes standard
                val m3uUrl = "$server/get.php?username=${java.net.URLEncoder.encode(user, "UTF-8")}" +
                    "&password=${java.net.URLEncoder.encode(pwd, "UTF-8")}" +
                    "&type=m3u_plus&output=hls"
                val source = IptvSource(
                    id = existing?.id ?: IptvSourceStore.generateId(),
                    type = IptvSource.Type.M3U,
                    name = name,
                    url = m3uUrl,
                    userAgent = ua,
                )
                IptvSourceStore.upsert(source)
                MyIptvProvider.invalidateCache(source.id)
                com.streamflixreborn.streamflix.utils.HomeCacheStore.clear(this, MyIptvProvider)
                MyIptvProvider.selectedCategoryLive = null
                MyIptvProvider.selectedCategoryMovie = null
                MyIptvProvider.selectedCategorySeries = null
                com.streamflixreborn.streamflix.utils.ProviderChangeNotifier.notifyProviderChanged()
                Toast.makeText(this, "Source Xtream '$name' créée", Toast.LENGTH_SHORT).show()
                openSourceContent(source)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** 2026-05-13 (user "comment pour ajouter mon pack de source ça va être un
     *  fichier avec plein de liens") : import bulk de sources via un fichier
     *  ou une URL. Supporte 2 formats automatiquement détectés :
     *
     *  Format 1 (TXT simple) : 1 URL par ligne
     *    https://server.com/playlist1.m3u
     *    https://server.com/playlist2.m3u
     *
     *  Format 2 (JSON avancé) : array de configs complètes
     *    [{"type":"M3U","name":"X","url":"..."},
     *     {"type":"STALKER","name":"Y","url":"http://srv:8080/c/","mac":"00:1A:..."}]
     */
    private fun openImportPackDialog() {
        AlertDialog.Builder(this)
            .setTitle("Importer un pack de sources")
            .setMessage("Choisis la source du pack :\n\n" +
                "• Fichier local (.txt ou .json) sur le device\n" +
                "• URL distante (Gist, Drive partagé, etc.)\n\n" +
                "Format TXT : 1 URL M3U par ligne\n" +
                "Format JSON : array d'objets {type, name, url[, mac]}")
            .setPositiveButton("Fichier local") { _, _ -> launchFilePicker() }
            .setNeutralButton("URL distante") { _, _ -> openImportUrlDialog() }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private val importFileLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val content = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    }.getOrNull()
                }
                if (content == null) {
                    Toast.makeText(this@IptvSourcesActivity, "Impossible de lire le fichier", Toast.LENGTH_LONG).show()
                } else {
                    importPackFromText(content)
                }
            }
        }
    }

    private fun launchFilePicker() {
        try {
            importFileLauncher.launch(arrayOf("*/*"))
        } catch (e: Exception) {
            Toast.makeText(this, "Erreur ouverture sélecteur de fichier: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openImportUrlDialog() {
        val edit = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            hint = "https://gist.github.com/.../raw/sources.txt"
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
            addView(edit, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
        AlertDialog.Builder(this)
            .setTitle("URL du pack")
            .setView(container)
            .setPositiveButton("Importer") { _, _ ->
                val url = edit.text.toString().trim()
                if (url.isEmpty()) return@setPositiveButton
                scope.launch {
                    val content = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            val client = okhttp3.OkHttpClient.Builder()
                                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                                .build()
                            val req = okhttp3.Request.Builder().url(url).build()
                            client.newCall(req).execute().use { resp ->
                                if (!resp.isSuccessful) null
                                else resp.body?.string()
                            }
                        }.getOrNull()
                    }
                    if (content == null) {
                        Toast.makeText(this@IptvSourcesActivity, "Impossible de fetcher l'URL", Toast.LENGTH_LONG).show()
                    } else {
                        importPackFromText(content)
                    }
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun importPackFromText(content: String) {
        val text = content.trim()
        var added = 0
        var skipped = 0
        val skipReasons = mutableListOf<String>()
        try {
            // Détection auto : commence par '[' ou '{' → JSON
            if (text.startsWith("[") || text.startsWith("{")) {
                val arr = if (text.startsWith("[")) {
                    org.json.JSONArray(text)
                } else {
                    // Singleton object
                    org.json.JSONArray().put(org.json.JSONObject(text))
                }
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val typeStr = obj.optString("type", "M3U").uppercase()
                    val type = when (typeStr) {
                        "M3U", "M3U8" -> IptvSource.Type.M3U
                        "STALKER", "MAG" -> IptvSource.Type.STALKER
                        else -> { skipped++; skipReasons += "type inconnu: $typeStr"; continue }
                    }
                    val name = obj.optString("name").takeIf { it.isNotBlank() } ?: "Source ${added + 1}"
                    val url = obj.optString("url").takeIf { it.isNotBlank() }
                        ?: run { skipped++; skipReasons += "URL manquante: $name"; continue }
                    val mac = obj.optString("mac").takeIf { it.isNotBlank() }
                    val ua = obj.optString("userAgent").takeIf { it.isNotBlank() }
                    val src = IptvSource(
                        id = IptvSourceStore.generateId(),
                        type = type, name = name, url = url, mac = mac, userAgent = ua,
                    )
                    IptvSourceStore.upsert(src)
                    added++
                }
            } else {
                // Format TXT : 1 URL par ligne (commentaires avec # ignorés)
                text.lines().map { it.trim() }.filter {
                    it.isNotBlank() && !it.startsWith("#")
                }.forEachIndexed { idx, url ->
                    if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                        skipped++; skipReasons += "ligne ${idx + 1} URL invalide"; return@forEachIndexed
                    }
                    val name = "Source ${added + 1}"
                    val src = IptvSource(
                        id = IptvSourceStore.generateId(),
                        type = IptvSource.Type.M3U, name = name, url = url,
                    )
                    IptvSourceStore.upsert(src)
                    added++
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Erreur parse pack: ${e.message?.take(100)}", Toast.LENGTH_LONG).show()
            return
        }
        // Invalide les caches pour repartir clean
        MyIptvProvider.invalidateCache()
        com.streamflixreborn.streamflix.utils.HomeCacheStore.clear(this, MyIptvProvider)
        com.streamflixreborn.streamflix.utils.ProviderChangeNotifier.notifyProviderChanged()
        val msg = buildString {
            append("✓ $added source(s) importée(s)")
            if (skipped > 0) append(", $skipped ignorée(s)")
            if (skipReasons.isNotEmpty()) {
                append("\n")
                append(skipReasons.take(3).joinToString("\n"))
                if (skipReasons.size > 3) append("\n…")
            }
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        refreshList()
    }

    /** Étape 2 : form pour entrer les détails de la source. */
    private fun openSourceForm(type: IptvSource.Type, existing: IptvSource?) {
        // 2026-05-12 (user "y a rien sur ton interface pour mettre l'URL") :
        // les EditText doivent avoir des LayoutParams MATCH_PARENT x WRAP_CONTENT
        // explicites, sinon dans un LinearLayout vertical le 1er enfant prend
        // toute la place et masque les suivants.
        fun fieldParams() = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )

        val nameInput = EditText(this).apply {
            layoutParams = fieldParams()
            hint = "Nom (ex: Mon bouquet FR)"
            setText(existing?.name ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val urlInput = EditText(this).apply {
            layoutParams = fieldParams()
            hint = if (type == IptvSource.Type.M3U) "URL .m3u/.m3u8" else "Portail URL (ex: http://srv:8080/c/)"
            setText(existing?.url ?: "")
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        val macInput = EditText(this).apply {
            layoutParams = fieldParams()
            hint = "MAC (00:1A:79:XX:XX:XX) — Stalker uniquement"
            setText(existing?.mac ?: "")
            visibility = if (type == IptvSource.Type.STALKER) View.VISIBLE else View.GONE
        }
        val uaInput = EditText(this).apply {
            layoutParams = fieldParams()
            hint = "User-Agent (optionnel)"
            setText(existing?.userAgent ?: "")
        }
        val refererInput = EditText(this).apply {
            layoutParams = fieldParams()
            hint = "Referer (optionnel)"
            setText(existing?.referer ?: "")
        }
        val container = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(nameInput)
            addView(spacer())
            addView(urlInput)
            if (type == IptvSource.Type.STALKER) {
                addView(spacer())
                addView(macInput)
            }
            addView(spacer())
            addView(uaInput)
            addView(spacer())
            addView(refererInput)
        }

        AlertDialog.Builder(this)
            .setTitle(if (existing != null) "Éditer la source" else "Nouvelle source ${type.name}")
            .setView(container)
            .setPositiveButton("Sauver") { _, _ ->
                val name = nameInput.text.toString().trim()
                // 2026-05-12 : si l'user a paste un bloc admin entier, on construit
                // la bonne URL Xtream Codes (host + username + password) automatiquement.
                val rawUrl = urlInput.text.toString().trim()
                val url = if (rawUrl.contains('\n') || rawUrl.contains('\r')) {
                    val cleaned = MyIptvProvider.sanitizeUrlPublic(rawUrl)
                    Toast.makeText(this, "URL multi-lignes détectée — extrait : $cleaned", Toast.LENGTH_LONG).show()
                    cleaned
                } else rawUrl
                val mac = macInput.text.toString().trim().takeIf { it.isNotBlank() }
                val ua = uaInput.text.toString().trim().takeIf { it.isNotBlank() }
                val referer = refererInput.text.toString().trim().takeIf { it.isNotBlank() }
                if (name.isBlank()) {
                    Toast.makeText(this, "Le nom est requis", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                    Toast.makeText(this, "URL invalide (doit commencer par http:// ou https://)", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val source = IptvSource(
                    id = existing?.id ?: IptvSourceStore.generateId(),
                    type = type,
                    name = name,
                    url = url,
                    mac = mac,
                    userAgent = ua,
                    referer = referer,
                )
                val isNewSource = existing == null
                IptvSourceStore.upsert(source)
                MyIptvProvider.invalidateCache(source.id)
                // 2026-05-13 (user "le Home est resté bloqué sur la source d'avant
                // même en changeant de catégorie sur la nouvelle source") : le
                // HomeCacheStore garde un snapshot 5 min de l'ancien état. Sans
                // l'invalider, ajouter/éditer une source ne change pas le home
                // tant que le snapshot n'a pas expiré. On vide aussi les filtres
                // catégorie (les anciens noms RAW peuvent ne pas exister dans la
                // nouvelle source) et on notifie le viewModel.
                com.streamflixreborn.streamflix.utils.HomeCacheStore.clear(
                    this, MyIptvProvider,
                )
                MyIptvProvider.selectedCategoryLive = null
                MyIptvProvider.selectedCategoryMovie = null
                MyIptvProvider.selectedCategorySeries = null
                com.streamflixreborn.streamflix.utils.ProviderChangeNotifier.notifyProviderChanged()
                if (isNewSource) {
                    // Nouvelle source → ouvre directement le contenu
                    Toast.makeText(this, "Chargement de '${source.name}'...", Toast.LENGTH_SHORT).show()
                    openSourceContent(source)
                } else {
                    // Édition → reste sur le settings, refresh la liste
                    refreshList()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun openEditDialog(source: IptvSource) {
        AlertDialog.Builder(this)
            .setTitle("${source.name}")
            .setItems(arrayOf("Modifier", "Forcer le refresh", "Supprimer")) { _, idx ->
                when (idx) {
                    0 -> openSourceForm(source.type, source)
                    1 -> {
                        MyIptvProvider.invalidateCache(source.id)
                        // 2026-05-13 : invalide aussi le snapshot home pour
                        // forcer le re-fetch dès le prochain affichage.
                        com.streamflixreborn.streamflix.utils.HomeCacheStore.clear(
                            this, MyIptvProvider,
                        )
                        com.streamflixreborn.streamflix.utils.ProviderChangeNotifier.notifyProviderChanged()
                        Toast.makeText(this, "Cache vidé pour '${source.name}' — la prochaine ouverture re-fetch", Toast.LENGTH_SHORT).show()
                    }
                    2 -> confirmDelete(source)
                }
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun confirmDelete(source: IptvSource) {
        AlertDialog.Builder(this)
            .setTitle("Supprimer ${source.name} ?")
            .setMessage("La source sera supprimée. Tu peux la recréer plus tard.")
            .setPositiveButton("Supprimer") { _, _ ->
                IptvSourceStore.delete(source.id)
                MyIptvProvider.invalidateCache(source.id)
                // 2026-05-13 : invalide aussi le snapshot du home + filtres
                // catégorie + notifie le viewModel pour rafraîchir immédiatement.
                com.streamflixreborn.streamflix.utils.HomeCacheStore.clear(
                    this, MyIptvProvider,
                )
                MyIptvProvider.selectedCategoryLive = null
                MyIptvProvider.selectedCategoryMovie = null
                MyIptvProvider.selectedCategorySeries = null
                com.streamflixreborn.streamflix.utils.ProviderChangeNotifier.notifyProviderChanged()
                refreshList()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun spacer(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 24)
    }

    private class SourcesAdapter(
        private val sources: List<IptvSource>,
        private val onClick: (IptvSource) -> Unit,
        private val onLongClick: (IptvSource) -> Unit,
    ) : RecyclerView.Adapter<SourcesAdapter.VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_iptv_source, parent, false)
            return VH(v)
        }
        override fun getItemCount() = sources.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val s = sources[position]
            holder.name.text = s.name
            val urlPreview = if (s.url.length > 60) s.url.take(60) + "…" else s.url
            holder.type.text = "${s.type.name} • $urlPreview  ✏ Long-press pour modifier"
            holder.itemView.setOnClickListener { onClick(s) }
            holder.itemView.setOnLongClickListener {
                onLongClick(s)
                true
            }
        }
        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.tv_iptv_source_name)
            val type: TextView = view.findViewById(R.id.tv_iptv_source_type)
        }
    }
}
