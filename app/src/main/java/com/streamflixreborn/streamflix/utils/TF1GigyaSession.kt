package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import org.json.JSONObject

/**
 * 2026-09-17 (user : « TF1 se déconnecte, M6 non — fais en sorte que ça garde de
 * quoi se reconnecter tout seul »).
 *
 * POURQUOI CE FICHIER EXISTE. Le renouvellement silencieux (TF1JwtRefresher)
 * ouvre tf1.fr dans une WebView invisible et demande un nouveau JWT. Il ne peut
 * aboutir QUE si la page reconnaît une session Gigya. Mesure sur l'appareil du
 * user, base de cookies de la WebView :
 *     cookie « glt_ » : AUCUN      domaine tf1/gigya : AUCUN
 * d'où, dans les logs, `authEvent => parsedJwt=null` puis `callback: token=0`,
 * et au final `mediainfo → PERMISSION_DENIED` (écran noir). Rien n'était
 * conservé pour se ré-authentifier.
 *
 * CE QU'ON SAUVEGARDE, ET POURQUOI PAS LE MOT DE PASSE. Le user se connecte à
 * TF1 AVEC GOOGLE : il n'existe aucun mot de passe TF1 à stocker (même limite
 * que celle notée dans M6GigyaAuth pour Google/Apple/Facebook). On conserve donc
 * la SESSION Gigya elle-même, qui atteste que le compte est connecté quelle
 * qu'ait été la méthode d'identification — exactement ce que fait M6UidResolver
 * côté 6play.
 *
 * 2026-09-18 — LA SESSION EST SUR DEUX DOMAINES, ON N'EN GARDAIT QU'UN.
 * Première version : capture des cookies de tf1.fr seulement. Résultat mesuré
 * sur l'Oppo, diagnostic renvoyé par la page :
 *     {"gigya":true,
 *      "cookies":"gig_bootstrap_3_hWgJ…,glt_3_hWgJ…",   <- bien réinjectés
 *      "r1":{"code":403005,"msg":"Unauthorized user"}}  <- refus quand même
 * Le SDK voyait donc le jeton de connexion et Gigya le refusait. En inspectant
 * la page TF1 sur le navigateur du user (session valable, getAccountInfo → 0),
 * le SDK Gigya vient de `https://compte.tf1.fr/js/gigya.js?apiKey=…` : TF1
 * héberge Gigya sur un sous-domaine à lui. Les cookies y sont répartis :
 *     tf1.fr        -> glt_<apiKey>, gig_bootstrap_<apiKey>
 *     compte.tf1.fr -> les mêmes + hasGmid, _gig_llu, _gig_llp (+ gmid/ucid,
 *                      HttpOnly, invisibles au JS mais lisibles par
 *                      CookieManager) = l'identité d'appareil
 * On ne gardait que la première moitié : le jeton sans l'appareil auquel il est
 * lié, d'où 403005 à tous les coups.
 * Piste écartée au passage : le localStorage ne sert à rien ici, la seule clé
 * Gigya (`<apiKey>_gig`) vaut « {} » même sur une session qui marche.
 *
 * Stockage en préférences privées à l'app, comme les jetons déjà gérés ici.
 */
object TF1GigyaSession {

    private const val TAG = "TF1GigyaSession"
    private const val PREF_NAME = "replay_auth_tf1_gigya"
    private const val KEY = "cookies_json"

    /**
     * Domaines porteurs de la session Gigya de TF1.
     *
     * 2026-09-18 — `compte.tf1.fr` EST LE DOMAINE QUI COMPTE, et il manquait.
     *   TF1 n'appelle pas Gigya sur gigya.com : le SDK est servi par
     *   `https://compte.tf1.fr/js/gigya.js?apiKey=…` (constaté sur la page,
     *   c'est le seul script Gigya chargé). Tout le dialogue passe donc par ce
     *   sous-domaine à eux, et c'est là que sont déposés `hasGmid`, `gmid`,
     *   `ucid`, `_gig_llu`, `_gig_llp` — l'IDENTITÉ D'APPAREIL sans laquelle
     *   Gigya refuse le jeton de connexion avec 403005 « Unauthorized user ».
     *   Sur tf1.fr il n'y a que `glt_` et `gig_bootstrap_`, ce qu'on sauvait :
     *   la moitié de la session, d'où l'échec systématique du renouvellement.
     *   accounts./socialize.eu1.gigya.com sont gardés par précaution mais n'ont
     *   jamais rien donné.
     */
    private val URLS = listOf(
        "https://compte.tf1.fr",
        "https://www.tf1.fr",
        "https://tf1.fr",
        "https://accounts.eu1.gigya.com",
        "https://socialize.eu1.gigya.com",
    )

    /** Ne garde que ce qui sert à ré-authentifier (évite d'archiver pub/mesure). */
    private fun estCookieDeSession(nom: String): Boolean {
        val n = nom.lowercase()
        return n.startsWith("glt_") || n.startsWith("gig_") || n.startsWith("_gig") ||
            n.startsWith("gac_") || n.startsWith("gmid") || n.startsWith("ucid") ||
            n.startsWith("hasgmid") || n.contains("gigya") || n.startsWith("gltexp_")
    }

    /**
     * Capture la session Gigya courante. Appelé JUSTE APRÈS une connexion TF1
     * réussie, moment où les cookies sont forcément présents.
     * Retourne le nombre de cookies retenus (0 = rien à sauvegarder).
     */
    fun capturer(ctx: Context): Int {
        return try {
            val cm = CookieManager.getInstance()
            val json = JSONObject()
            var total = 0
            for (url in URLS) {
                val brut = cm.getCookie(url) ?: continue
                val gardes = brut.split(";")
                    .map { it.trim() }
                    .filter { it.contains("=") && estCookieDeSession(it.substringBefore("=")) }
                if (gardes.isEmpty()) continue
                json.put(url, gardes.joinToString("; "))
                total += gardes.size
            }
            if (total == 0) {
                Log.w(TAG, "capture : aucun cookie de session Gigya trouvé")
                return 0
            }
            ctx.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY, json.toString()).apply()
            Log.i(TAG, "capture : $total cookie(s) de session Gigya sauvegardés")
            total
        } catch (t: Throwable) {
            Log.w(TAG, "capture échouée : ${t.message}")
            0
        }
    }

    // 2026-09-18 — PISTE ÉCARTÉE, MESURÉE : le localStorage ne contient RIEN de
    //   réutilisable. Sur le navigateur du user, connecté et fonctionnel, la
    //   seule clé Gigya est `<apiKey>_gig` et elle vaut littéralement « {} ».
    //   Toute la session tient donc dans les cookies — mais répartis sur DEUX
    //   domaines, dont compte.tf1.fr qu'on ne regardait pas (cf. URLS).
    //   Ne pas réintroduire de capture/restauration du localStorage.

    /** true si une session Gigya est en réserve. */
    fun disponible(ctx: Context): Boolean =
        !ctx.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY, null).isNullOrBlank()

    /**
     * Réinjecte la session sauvegardée dans le CookieManager. Appelé AVANT de
     * charger tf1.fr dans la WebView du renouvellement : sans ça la page ne
     * reconnaît pas le compte et rend `parsedJwt=null`.
     * Retourne le nombre de cookies réinjectés.
     */
    fun restaurer(ctx: Context): Int {
        return try {
            val brut = ctx.applicationContext
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(KEY, null) ?: return 0
            val json = JSONObject(brut)
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            var total = 0
            for (url in json.keys()) {
                val chaine = json.optString(url).takeIf { it.isNotBlank() } ?: continue
                // 2026-09-18 — DOMAINE .tf1.fr OBLIGATOIRE, ET C'ÉTAIT LÀ LE BUG.
                //   Sans attribut Domain, un cookie réinjecté devient « host-only » :
                //   valable sur www.tf1.fr et NULLE PART ailleurs. Or le SDK Gigya
                //   authentifie contre https://compte.tf1.fr/accounts.getAccountInfo
                //   avec authMode=cookie — c'est le COOKIE qui compte, le paramètre
                //   login_token seul ne suffit pas. Vérifié en rejouant l'appel :
                //     jeton du navigateur SANS cookies -> 403005 Unauthorized user
                //     jeton du navigateur AVEC cookies -> 0, compte reconnu
                //   Nos cookies restaurés n'atteignaient donc jamais compte.tf1.fr,
                //   et le renouvellement échouait quoi qu'on fasse par ailleurs.
                val domaine = if (url.endsWith("tf1.fr")) "; Domain=.tf1.fr" else ""
                for (paire in chaine.split(";").map { it.trim() }.filter { it.contains("=") }) {
                    cm.setCookie(url, "$paire$domaine; Path=/")
                    total++
                }
            }
            runCatching { cm.flush() }
            Log.i(TAG, "restaure : $total cookie(s) de session Gigya réinjectés")
            total
        } catch (t: Throwable) {
            Log.w(TAG, "restauration échouée : ${t.message}")
            0
        }
    }

    /** Effacement explicite (déconnexion volontaire de l'utilisateur). */
    fun effacer(ctx: Context) {
        ctx.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().clear().apply()
        Log.d(TAG, "session Gigya effacée")
    }
}
