package com.streamflixreborn.streamflix.extractors

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.TimeUnit

/**
 * Franchit le contrôle d'accès « Byse » qui protège les lecteurs Filemoon
 * (filemoon.sx, bysebuho.com, q8y5z.com, moflix-stream.link…).
 *
 * ── POURQUOI CE FICHIER EXISTE (2026-08-06) ──────────────────────────────────────────────
 *   L'appel `/embed/playback` répondait **HTTP 428 `{"error":"captcha_required"}`** et
 *   Filemoon ne lisait plus rien. Le nom trompe : il n'y a AUCUNE image à déchiffrer, c'est
 *   une preuve de travail purement calculatoire, donc automatisable.
 *
 *   Toute la chaîne ci-dessous a été établie et VÉRIFIÉE EN DIRECT dans le navigateur du
 *   user (règle maison : on teste dans Chrome AVANT de coder), pas déduite :
 *
 *     1. POST /api/videos/access/challenge          → {challenge_id, nonce}
 *     2. signature ECDSA P-256 / SHA-256 du `nonce`, encodée base64url, format BRUT r‖s
 *     3. POST /api/videos/access/attest             → {token, viewer_id, device_id, confidence}
 *     4. POST /api/videos/{id}/embed/captcha        → {pow_nonce, pow_difficulty, pow_token}
 *     5. résolution de la preuve de travail         → solution (un simple compteur)
 *     6. POST /api/videos/{id}/embed/captcha/verify → {status:"ok", token}
 *     7. le jeton part dans l'en-tête **X-Captcha-Token** de /embed/playback → 200 ✅
 *
 * ── LES TROIS PIÈGES QUI M'ONT COÛTÉ DES HEURES ─────────────────────────────────────────
 *   · `public_key` doit être le JWK EXPORTÉ TEL QUEL. J'en avais retiré `key_ops` et `ext`
 *     en croyant faire propre → `invalid payload` systématique. Ne pas les enlever.
 *   · Le serveur annonce `algorithm: "sha256-leading-zero-bits"` : c'est FAUX, ou du moins
 *     ce n'est pas SHA-256. C'est un hachage maison (quart-de-tour façon ChaCha sur un
 *     tampon de 512 mots, graine = les mots d'initialisation de SHA-256). Résoudre en
 *     SHA-256 renvoie `pow_failed`.
 *   · La préimage est `nonce + ":" + compteur` — avec le DEUX-POINTS. Sans lui, jamais.
 *
 *   ⚠ Ne rien « simplifier » ici sans rejouer la chaîne dans un navigateur : chacun de ces
 *     détails a été payé par un aller-retour raté.
 */
internal object ByseAcces {

    private const val TAG = "ByseAcces"

    /** Durée de validité annoncée : 1800 s. On garde une marge confortable. */
    private const val VALIDITE_MS = 20 * 60 * 1000L

    /** Au-delà, on abandonne la preuve de travail : mieux vaut rendre la main au lecteur. */
    // Généreux À DESSEIN : depuis que la négociation tourne dans son propre fil, un budget
    //   long ne fait plus attendre personne — l'appelant rend la main au bout de quelques
    //   secondes et c'est le passage suivant qui profitera du jeton. Mieux vaut aboutir en
    //   40 s en arrière-plan que renoncer à 25 s et tout recommencer au prochain essai.
    private const val BUDGET_POW_MS = 40_000L

    /** Empreinte + jeton de captcha, prêts à être envoyés à `/embed/playback`. */
    data class Acces(
        val token: String,
        val viewerId: String,
        val deviceId: String,
        val confiance: Double,
        val jetonCaptcha: String,
        val obtenuLe: Long = System.currentTimeMillis(),
    ) {
        val valide: Boolean get() = System.currentTimeMillis() - obtenuLe < VALIDITE_MS
    }

    /** Un accès par domaine + vidéo : les jetons sont liés à l'origine qui les a émis. */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Acces>()

    private val client by lazy {
        // ── 2026-09-11 : DES DELAIS COURTS, PARCE QUE LE CALCUL NE COUTE PLUS RIEN ────────
        //   Mesure faite ce soir en rejouant toute la negociation depuis le PC, 10 fois de
        //   suite : difficulte 12 a chaque fois (~4 000 essais, ~0,1 s sur le telephone) et
        //   les trois POST bouclent en 2 s. La preuve n'est donc PAS le gouffre.
        //   Le journal du 2026-09-11 21:17 montre l'inverse : 15 s brulees AVANT la preuve
        //   (ni « preuve resolue », ni « abandon » — le calcul n'avait pas commence), puis
        //   l'abandon. C'etait un appel HTTP qui trainait, avec un plafond d'appel a 20 s,
        //   soit plus que tout le budget de l'appelant. Un appel lent doit echouer VITE pour
        //   qu'on reparte sur un miroir : ils repondent tous en moins d'une seconde et
        //   menent tous au meme domaine de lecture.
        Extractor.sharedClient.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private val JSON = "application/json".toMediaType()

    /**
     * Renvoie un accès valide pour cette vidéo, en le négociant si besoin.
     * `null` = négociation impossible ; l'appelant se rabat sur la WebView.
     */
    /**
     * ── 2026-08-06 : LA NÉGOCIATION EST DÉTACHÉE DE L'APPELANT ───────────────────────────
     *   Symptôme : `HTTP 428` de nouveau, et AUCUNE trace `ByseAcces` dans le journal. Cause :
     *   la sonde de qualité coupe ses extractions au bout de 10 s (`Timed out waiting for
     *   10000 ms`), or la preuve de travail en demande une vingtaine sur cette box. La
     *   négociation était donc tuée en cours et repartait de zéro à l'appel suivant — elle
     *   n'aboutissait jamais.
     *   Elle tourne désormais dans son propre fil, hors de portée de l'annulation : l'appelant
     *   attend ce que SON budget permet, et même s'il renonce, le travail continue et remplit
     *   le cache. Le passage suivant (la vraie lecture) trouve le jeton prêt — mesuré à 279 ms.
     */
    private val executeur by lazy {
        java.util.concurrent.Executors.newCachedThreadPool { r ->
            Thread(r, "byse-acces").apply { isDaemon = true }
        }
    }
    private val enCours = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.Future<Acces?>>()

    fun obtenir(
        domaine: String,
        videoId: String,
        enTetesEmbed: Map<String, String>,
        attenteMaxMs: Long = 8_000L,
    ): Acces? {
        val cle = "$domaine|$videoId"
        cache[cle]?.let { if (it.valide) return it }

        val tache = enCours.computeIfAbsent(cle) {
            executeur.submit(java.util.concurrent.Callable {
                try {
                    negocier(domaine, videoId, enTetesEmbed)?.also { a -> cache[cle] = a }
                } catch (e: Exception) {
                    Log.w(TAG, "négociation impossible : ${e.message}")
                    null
                } finally {
                    // Retirée du registre pour qu'un échec puisse être retenté plus tard.
                    if (cache[cle] == null) enCours.remove(cle)
                }
            })
        }

        return try {
            tache.get(attenteMaxMs, TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            // ⚠ On NE l'annule PAS : elle poursuit son calcul et servira au prochain passage.
            Log.d(TAG, "négociation en cours, on rend la main (elle continue en arrière-plan)")
            null
        } catch (e: Exception) {
            Log.w(TAG, "négociation échouée : ${e.message}")
            null
        }
    }

    private fun negocier(domaine: String, videoId: String, x: Map<String, String>): Acces? {
        // ── 1-3 : identité de l'appareil, prouvée par signature ──────────────────────────
        val generateur = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }
        val paire = generateur.generateKeyPair()

        val defi = JSONObject(poster("$domaine/api/videos/access/challenge", null, x))
        val nonce = defi.getString("nonce")

        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(paire.private)
            update(nonce.toByteArray())
            derVersBrut(sign())
        }

        val corpsAttest = JSONObject().apply {
            put("viewer_id", "")
            put("device_id", "")
            put("challenge_id", defi.getString("challenge_id"))
            put("nonce", nonce)
            put("signature", base64Url(signature))
            put("public_key", jwk(paire.public as ECPublicKey))
            put("client", JSONObject().apply {
                put("user_agent", Extractor.DEFAULT_USER_AGENT)
                put("language", "fr-FR")
                put("timezone", java.util.TimeZone.getDefault().id)
                put("pixel_ratio", 1)
                put("screen_width", 1920)
                put("screen_height", 1080)
                put("color_depth", 24)
                put("hardware_concurrency", Runtime.getRuntime().availableProcessors())
                put("touch_points", 0)
            })
        }
        val attest = JSONObject(poster("$domaine/api/videos/access/attest", corpsAttest, x))

        val empreinte = JSONObject().apply {
            put("token", attest.getString("token"))
            put("viewer_id", attest.getString("viewer_id"))
            put("device_id", attest.getString("device_id"))
            put("confidence", attest.optDouble("confidence", 0.5))
        }

        // ── 4-6 : la « vérification joueur » — une preuve de travail ─────────────────────
        val defiPow = JSONObject(
            poster(
                "$domaine/api/videos/$videoId/embed/captcha",
                JSONObject().put("fingerprint", empreinte),
                x,
            )
        )
        val difficulte = defiPow.optInt("pow_difficulty", 16)
        // 2026-09-11 : tracée AVANT de calculer. Jusqu'ici elle n'apparaissait qu'en cas de
        //   succès ou d'abandon — donc jamais quand on renonçait plus tôt, précisément le
        //   cas qu'on cherchait à comprendre. Repère mesuré : 12 ≈ 0,1 s, 16 ≈ 1,8 s,
        //   20 ≈ 26 s sur l'appareil (~40 000 hachages/s sur 3 fils).
        Log.d(TAG, "défi reçu : difficulté $difficulte")
        val debut = System.currentTimeMillis()
        val solution = resoudrePreuve(defiPow.getString("pow_nonce"), difficulte)
            ?: run {
                Log.w(TAG, "preuve de travail non résolue en ${BUDGET_POW_MS} ms (difficulté $difficulte)")
                return null
            }
        Log.d(TAG, "preuve résolue en ${System.currentTimeMillis() - debut} ms (difficulté $difficulte)")

        val verif = JSONObject(
            poster(
                "$domaine/api/videos/$videoId/embed/captcha/verify",
                JSONObject().apply {
                    put("pow_token", defiPow.getString("pow_token"))
                    put("solution", solution)
                    put("fingerprint", empreinte)
                },
                x,
            )
        )
        if (verif.optString("status") != "ok") {
            Log.w(TAG, "vérification refusée : ${verif.optString("reason")}")
            return null
        }

        return Acces(
            token = empreinte.getString("token"),
            viewerId = empreinte.getString("viewer_id"),
            deviceId = empreinte.getString("device_id"),
            confiance = empreinte.getDouble("confidence"),
            jetonCaptcha = verif.getString("token"),
        )
    }

    private fun poster(url: String, corps: JSONObject?, x: Map<String, String>): String {
        // 2026-09-11 : on chronometre CHAQUE appel. Sans ça, un journal d'echec ne dit pas
        //   si le temps est parti dans le reseau ou dans la preuve — il a fallu deux heures
        //   et une sonde Python pour trancher une fois. Le nom de l'etape suffit : c'est le
        //   dernier segment du chemin (challenge / attest / captcha / verify).
        val etape = url.substringAfterLast('/')
        val debutAppel = System.currentTimeMillis()
        try {
            return posterInterne(url, corps, x).also {
                Log.d(TAG, "étape $etape : ${System.currentTimeMillis() - debutAppel} ms")
            }
        } catch (e: Exception) {
            Log.w(TAG, "étape $etape échouée en ${System.currentTimeMillis() - debutAppel} ms : ${e.message}")
            throw e
        }
    }

    private fun posterInterne(url: String, corps: JSONObject?, x: Map<String, String>): String {
        val requete = Request.Builder()
            .url(url)
            .post((corps?.toString() ?: "").toRequestBody(JSON))
            .header("User-Agent", Extractor.DEFAULT_USER_AGENT)
            .header("Accept", "application/json")
            .header("Accept-Language", "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7")
            .apply { x.forEach { (k, v) -> header(k, v) } }
            .build()
        client.newCall(requete).execute().use { reponse ->
            val texte = reponse.body?.string().orEmpty()
            if (!reponse.isSuccessful) throw Exception("$url → HTTP ${reponse.code} ${texte.take(120)}")
            return texte
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════════════
    //  Preuve de travail — portage fidèle du module `pow-*.js` du site.
    //  Toute divergence, même d'un bit, donne `pow_failed` côté serveur.
    // ══════════════════════════════════════════════════════════════════════════════════════

    private const val TAILLE = 512
    private const val MASQUE = TAILLE - 1
    private const val PASSES = 2
    // ⚠ ÉCRITURE HEXADÉCIMALE OBLIGATOIRE. Première version, j'avais converti 0x9E3779B1
    //   « à la main » en -1640531527 au lieu de -1640531535 : huit d'écart, tout le hachage
    //   diverge, et la preuve de travail ne tombait jamais (journal : « non résolue en
    //   15000 ms »). Ne jamais réécrire ces valeurs en décimal.
    private val PREMIER_A = 0x9E3779B1u.toInt()
    private val PREMIER_B = 0x85EBCA77u.toInt()

    private fun rot(v: Int, n: Int): Int = (v shl n) or (v ushr (32 - n))

    /** Quart-de-tour façon ChaCha sur l'état de 4 mots. */
    private fun tour(e: IntArray) {
        e[0] += e[1]; e[3] = rot(e[3] xor e[0], 16)
        e[2] += e[3]; e[1] = rot(e[1] xor e[2], 12)
        e[0] += e[1]; e[3] = rot(e[3] xor e[0], 8)
        e[2] += e[3]; e[1] = rot(e[1] xor e[2], 7)
    }

    /**
     * Jeu de tampons réutilisés d'un essai à l'autre, un par fil d'exécution.
     * Sans ça on allouait 2 Ko à chaque tentative — des dizaines de milliers d'allocations
     * par résolution, dont le ramasse-miettes faisait les frais sur une box TV.
     */
    private class Ardoise {
        val etat = IntArray(4)
        val brouillon = IntArray(TAILLE)
        val sortie = IntArray(8)
    }

    /**
     * ⚠ VERSION EN TABLEAU — NE PAS « OPTIMISER » EN VARIABLES LOCALES.
     *   Tenté le 2026-08-06 : état en `var e0..e3` et quart-de-tour recopié à la main, en
     *   pensant éviter les vérifications de bornes. Résultat MESURÉ sur la box : **6 144
     *   essais en 25 s** contre **40 960 en 15 s** pour cette version-ci — dix fois plus lent.
     *   L'intuition était fausse, la mesure a tranché. Toute nouvelle idée d'optimisation
     *   doit être chiffrée sur l'appareil avant d'être gardée.
     */
    private fun Ardoise.hacher(donnees: ByteArray, taille: Int): IntArray {
        // Graine = les quatre premiers mots d'initialisation de SHA-256 (d'où le nom trompeur).
        val e = etat
        e[0] = 0x6A09E667u.toInt(); e[1] = 0xBB67AE85u.toInt()
        e[2] = 0x3C6EF372u.toInt(); e[3] = 0xA54FF53Au.toInt()
        for (i in 0 until taille) {
            e[0] += (donnees[i].toInt() and 0xFF)
            e[0] = rot(e[0], 7)
            tour(e)
        }
        repeat(8) { tour(e) }

        val r = brouillon
        for (i in 0 until TAILLE) { tour(e); r[i] = e[0] xor e[2] }

        repeat(PASSES) {
            for (s in 0 until TAILLE) {
                val a = r[s] and MASQUE
                var c = r[s] + r[a]
                c = rot(c, 13)
                c = c xor (r[(s + 1) and MASQUE] * PREMIER_A)
                r[s] = c
                e[0] = e[0] xor c
                tour(e)
            }
        }

        val bloc = TAILLE / 8
        for (i in 0 until 8) {
            tour(e)
            var s = e[0]
            val a = i * bloc
            for (c in 0 until bloc) {
                val d = r[a + c]
                s += d
                s = rot(s, 5)
                s = s xor (d * PREMIER_B)
            }
            sortie[i] = s xor e[2]
        }
        return sortie
    }

    private fun zerosEnTete(mots: IntArray): Int {
        var n = 0
        for (m in mots) {
            if (m == 0) { n += 32; continue }
            return n + Integer.numberOfLeadingZeros(m)
        }
        return n
    }

    /**
     * Cherche le compteur tel que le haché de « nonce:compteur » commence par
     * `difficulte` bits à zéro. ⚠ Le deux-points fait partie de la préimage.
     */
    private fun resoudrePreuve(nonce: String, difficulte: Int): String? {
        if (difficulte <= 0) return "0"

        // ── 2026-08-06 : POURQUOI C'EST PARALLÉLISÉ ──────────────────────────────────────
        //   Première version, mono-fil : **40 960 essais en 15 s** sur la box du user, soit
        //   ~2 700 hachages/s (chaque haché coûte plus de 2 000 tours de mélange). Or à
        //   difficulté 16 il en faut ~65 000 en moyenne : on abandonnait juste avant le but,
        //   alors que le portage était bon. Un navigateur de bureau y met 0,6 s.
        //   La recherche est parfaitement découpable : chaque fil explore les compteurs
        //   ≡ son indice (modulo le nombre de fils), le premier qui trouve arrête les autres.
        val prefixe = ("$nonce:").toByteArray(Charsets.ISO_8859_1)
        // ⚠ 2026-08-06 : on laisse TOUJOURS au moins un cœur libre. Première version : autant de
        //   fils que de cœurs, en priorité normale, pendant 40 s. Sur la box du user (Amlogic,
        //   4 cœurs) ça saturait le processeur entier — il a constaté que « les serveurs
        //   arrivent moins vite ». La collecte des serveurs fait du parsing HTML, elle a besoin
        //   de CPU elle aussi. Mieux vaut une preuve un peu plus lente qu'une application qui rame.
        val fils = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 3)
        val limite = System.currentTimeMillis() + BUDGET_POW_MS
        val trouve = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val essais = java.util.concurrent.atomic.AtomicLong(0)

        val equipe = (0 until fils).map { indice ->
            Thread {
                val ardoise = Ardoise()
                val tampon = ByteArray(prefixe.size + 20)
                System.arraycopy(prefixe, 0, tampon, 0, prefixe.size)
                var compteur = indice.toLong()
                var locaux = 0L
                while (trouve.get() == null) {
                    val chiffres = compteur.toString()
                    for (i in chiffres.indices) tampon[prefixe.size + i] = chiffres[i].code.toByte()
                    val h = ardoise.hacher(tampon, prefixe.size + chiffres.length)
                    if (zerosEnTete(h) >= difficulte) {
                        trouve.compareAndSet(null, compteur.toString())
                        break
                    }
                    compteur += fils
                    if (++locaux and 0x3FF == 0L && System.currentTimeMillis() > limite) break
                }
                essais.addAndGet(locaux)
            }.apply { priority = Thread.MIN_PRIORITY; start() }
        }
        equipe.forEach { runCatching { it.join(BUDGET_POW_MS + 2_000) } }

        val solution = trouve.get()
        if (solution == null) {
            Log.w(TAG, "abandon après ${essais.get()} essais sur $fils fils en ${BUDGET_POW_MS} ms")
        } else {
            Log.d(TAG, "trouvé (compteur $solution) après ${essais.get()} essais sur $fils fils")
        }
        return solution
    }

    // ══════════════════════════════════════════════════════════════════════════════════════
    //  Utilitaires cryptographiques
    // ══════════════════════════════════════════════════════════════════════════════════════

    /**
     * Java signe en DER (SEQUENCE de deux entiers) alors que WebCrypto — donc le serveur —
     * attend le format BRUT r‖s sur 2×32 octets. Sans cette conversion : signature refusée.
     */
    private fun derVersBrut(der: ByteArray): ByteArray {
        var i = 2
        if (der[1].toInt() and 0xFF > 0x80) i = 3
        i++ // marqueur INTEGER
        val tailleR = der[i].toInt(); i++
        val r = der.copyOfRange(i, i + tailleR); i += tailleR
        i++ // marqueur INTEGER
        val tailleS = der[i].toInt(); i++
        val s = der.copyOfRange(i, i + tailleS)

        val brut = ByteArray(64)
        val rUtile = r.dropWhile { it == 0.toByte() }.toByteArray()
        val sUtile = s.dropWhile { it == 0.toByte() }.toByteArray()
        System.arraycopy(rUtile, 0, brut, 32 - rUtile.size, rUtile.size)
        System.arraycopy(sUtile, 0, brut, 64 - sUtile.size, sUtile.size)
        return brut
    }

    /** JWK identique à celui que produit `crypto.subtle.exportKey('jwk', …)` — key_ops et ext COMPRIS. */
    private fun jwk(cle: ECPublicKey): JSONObject = JSONObject().apply {
        put("crv", "P-256")
        put("ext", true)
        put("key_ops", JSONArray().put("verify"))
        put("kty", "EC")
        put("x", base64Url(coordonnee(cle.w.affineX)))
        put("y", base64Url(coordonnee(cle.w.affineY)))
    }

    /** Une coordonnée P-256 fait 32 octets, sans l'octet de signe que Java peut ajouter. */
    private fun coordonnee(valeur: java.math.BigInteger): ByteArray {
        val brut = valeur.toByteArray()
        return when {
            brut.size == 32 -> brut
            brut.size > 32 -> brut.copyOfRange(brut.size - 32, brut.size)
            else -> ByteArray(32).also { System.arraycopy(brut, 0, it, 32 - brut.size, brut.size) }
        }
    }

    private fun base64Url(octets: ByteArray): String =
        android.util.Base64.encodeToString(
            octets,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
        )
}
