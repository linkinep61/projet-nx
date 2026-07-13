package com.streamflixreborn.streamflix.activities.profile

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.streamflixreborn.streamflix.StreamFlixApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
     * connu, sinon le nom du dossier Fluent (ex: "Fox/3D/fox_3d.png" -> "Fox"),
     * sinon "Image perso" pour les URLs custom.
     */
    fun displayName(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        if (PATHS.containsKey(value)) return value
        if (value.startsWith("http")) return "Image perso"
        if (value.contains("/")) return value.substringBefore("/")
        return value
    }

    /** Vérifie si la valeur stockée est une URL custom (pas Fluent). */
    fun isCustomUrl(value: String?): Boolean {
        return value?.startsWith("http") == true
    }

    // ══════════════════════════════════════════════════════════════
    //  Cache local des avatars (2026-05-22)
    //  User : "une fois l'émoticône choisi, il est implémenté
    //  jusqu'au prochain changement — pas d'appels réseau inutiles"
    // ══════════════════════════════════════════════════════════════

    private const val AVATAR_DIR = "profile_avatars"
    private const val TAG = "ProfileEmojiArt"

    /** Nom de fichier local pour un emoji/chemin donné. */
    private fun localFileName(emojiValue: String): String {
        // Hash simple pour un nom de fichier safe
        val hash = emojiValue.toByteArray().fold(0L) { acc, b ->
            acc * 31 + b.toLong()
        }.let { kotlin.math.abs(it) }
        return "avatar_${hash}.png"
    }

    /** Fichier local pour un emoji (peut ne pas exister). */
    private fun localFile(context: Context, emojiValue: String): File {
        val dir = File(context.filesDir, AVATAR_DIR)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, localFileName(emojiValue))
    }

    /** CoroutineScope interne pour les téléchargements fire-and-forget
     *  déclenchés depuis [bind] quand l'avatar n'est pas encore en cache
     *  local (cas des avatars choisis avant ce fix). */
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Télécharge l'image (Fluent OU URL custom — PNG, JPG, GIF animé, WebP)
     *  et la sauvegarde en local. Appelé au moment du CHOIX d'un emoji dans
     *  le picker → 1 seul appel réseau, puis plus jamais tant que l'avatar
     *  ne change pas.
     *
     *  Robuste face aux hébergeurs anti-hotlink (Tenor, Giphy, Imgur,
     *  Discord CDN…) grâce à un User-Agent navigateur + Accept + suivi de
     *  redirections + timeout. Valide les magic-bytes après download pour
     *  éviter de sauvegarder une page HTML d'erreur 403/404 par accident.
     *  Plafonné à 10 MB pour ne pas remplir le stockage avec un GIF géant. */
    /** True si l'avatar est déjà cached localement (visible sans internet).
     *  Utilisé par EmojiPickerDialog pour donner un feedback "OK hors-ligne". */
    fun hasLocalCache(context: Context, emojiValue: String): Boolean {
        val file = localFile(context, emojiValue)
        return file.exists() && file.length() > 100 && isImageFile(file)
    }

    suspend fun cacheLocally(context: Context, emojiValue: String) {
        val rawUrl = urlForValue(emojiValue) ?: return
        // 2026-06-03 (user "si y a pas d'internet, y a plus d'image") :
        //   Si l'URL est une page Tenor/Giphy/Imgur (HTML), on la résout AVANT
        //   le download pour garantir qu'on télécharge un vrai fichier image.
        //   Sinon download → content-type=text/html → reject → pas de cache local
        //   → image dépend du réseau pour s'afficher → KO offline.
        val url = withContext(Dispatchers.IO) {
            try {
                if (rawUrl.contains("tenor.com") || rawUrl.contains("giphy.com") ||
                    rawUrl.contains("imgur.com")) {
                    com.streamflixreborn.streamflix.utils.AvatarUrlResolver
                        .resolveBlocking(rawUrl) ?: rawUrl
                } else rawUrl
            } catch (_: Throwable) { rawUrl }
        }
        val file = localFile(context, emojiValue)
        // Déjà en cache valide → rien à faire.
        if (file.exists() && file.length() > 100 && isImageFile(file)) return
        // Vestige d'un ancien download cassé (HTML d'erreur sauvé par l'ancien
        // code) → on le vire avant de retenter.
        if (file.exists()) file.delete()

        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    instanceFollowRedirects = true
                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                                "(KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
                    )
                    setRequestProperty("Accept", "image/*,*/*;q=0.8")
                }

                val code = conn.responseCode
                if (code !in 200..299) {
                    Log.w(TAG, "cache download HTTP $code pour $emojiValue")
                    return@withContext
                }

                val contentType = conn.contentType?.lowercase() ?: ""
                if (contentType.isNotEmpty() && !contentType.startsWith("image/")) {
                    Log.w(TAG, "cache download Content-Type invalide ($contentType) pour $emojiValue")
                    return@withContext
                }

                val maxSize = 10 * 1024 * 1024 // 10 MB
                val buffer = java.io.ByteArrayOutputStream()
                conn.inputStream.use { stream ->
                    val chunk = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val n = stream.read(chunk)
                        if (n < 0) break
                        total += n
                        if (total > maxSize) {
                            Log.w(TAG, "cache download trop volumineux (>10MB) pour $emojiValue")
                            return@withContext
                        }
                        buffer.write(chunk, 0, n)
                    }
                }
                val bytes = buffer.toByteArray()

                if (!isImageBytes(bytes)) {
                    Log.w(TAG, "cache download n'est pas une image valide (magic-bytes) pour $emojiValue")
                    return@withContext
                }

                // Écriture atomique : tmp puis rename → si le process meurt en
                // plein download, on ne se retrouve pas avec un fichier
                // tronqué qui passerait le test length > 100.
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(file)) {
                    tmp.delete()
                    Log.w(TAG, "cache rename échoué pour $emojiValue")
                    return@withContext
                }

                Log.d(TAG, "avatar cached: ${file.name} (${bytes.size} bytes, $contentType)")
            } catch (e: Exception) {
                Log.w(TAG, "cache download failed for $emojiValue: ${e.message}")
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }
    }

    /** Vérifie les magic-bytes d'un buffer pour s'assurer qu'on a une vraie
     *  image (et pas une page HTML d'erreur, du JSON, etc.). Couvre les
     *  formats supportés par Glide : GIF, PNG, JPEG, WebP, BMP. */
    private fun isImageBytes(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        // GIF : "GIF87a" ou "GIF89a"
        if (bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte()) return true
        // PNG : 89 50 4E 47 0D 0A 1A 0A
        if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) return true
        // JPEG : FF D8 FF
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) return true
        // WebP : "RIFF" .... "WEBP"
        if (bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() && bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()) return true
        // BMP : "BM"
        if (bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte()) return true
        return false
    }

    /** Vérifie qu'un fichier sur disque est bien une image (ne lit que les
     *  12 premiers octets, pas tout le fichier en mémoire). */
    private fun isImageFile(file: File): Boolean {
        return try {
            file.inputStream().use { stream ->
                val head = ByteArray(12)
                val n = stream.read(head)
                n >= 12 && isImageBytes(head)
            }
        } catch (_: Exception) { false }
    }

    /**
     * Affiche l'avatar : fichier local en priorité (0 réseau), sinon CDN via
     * Glide, sinon emoji système en fallback.
     */
    fun bind(emoji: String?, image: ImageView?, fallback: TextView?) {
        val fallbackText = if (emoji != null && PATHS.containsKey(emoji)) emoji
                          else if (emoji != null && emoji.startsWith("http")) ""
                          else emoji ?: ""
        if (image == null) {
            fallback?.apply { visibility = View.VISIBLE; text = fallbackText }
            return
        }
        val url = urlForValue(emoji)
        if (url == null) {
            image.visibility = View.GONE
            fallback?.apply { visibility = View.VISIBLE; text = fallbackText }
            return
        }

        // 1) Essayer le fichier local d'abord (pas de réseau).
        //    On exige que ce soit une vraie image (magic-bytes), sinon on
        //    ignore un éventuel vestige corrompu (ex: HTML 403 sauvé par
        //    l'ancienne version du code) et on retombe sur l'URL.
        val ctx = image.context.applicationContext
        val local = emoji?.let { localFile(ctx, it) }
        val source: Any? = if (local != null && local.exists() && local.length() > 100 && isImageFile(local)) {
            local   // cache local valide → 0 réseau
        } else {
            // 2026-07-12 : PAS de download ici. L'image se télécharge UNIQUEMENT
            // quand l'user choisit un emoji dans le picker (cacheLocally appelé
            // dans ProfilePickerActivity / ProfilePickerTvActivity / EmojiPickerDialog).
            // Au boot / affichage profil sans cache → on affiche le fallback texte.
            null
        }

        if (source == null) {
            // Pas de cache → emoji texte natif (ex: 🎬), zéro réseau
            image.visibility = View.GONE
            fallback?.apply { visibility = View.VISIBLE; text = fallbackText }
            return
        }

        fallback?.apply { visibility = View.VISIBLE; text = fallbackText }
        image.visibility = View.VISIBLE
        // 2026-06-03 v4 (user "le but c'est que le fond d'écran actuel apparaisse
        //   au fond, comme Deadpool") : LA VRAIE CAUSE du fond blanc opaque était
        //   le DecodeFormat par défaut de Glide = PREFER_RGB_565 → PERD L'ALPHA.
        //   Force ARGB_8888 pour préserver la transparence du PNG Tenor → on voit
        //   bien le wallpaper de l'app à travers les zones transparentes.
        //   Pas de clipToOutline ni circleCrop : l'image PNG transparente est
        //   déjà bien découpée par l'auteur, on l'affiche telle quelle.
        image.clipToOutline = false
        image.outlineProvider = null
        Glide.with(image)
            .load(source)
            .format(com.bumptech.glide.load.DecodeFormat.PREFER_ARGB_8888)
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
