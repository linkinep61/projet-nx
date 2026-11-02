package com.streamflixreborn.streamflix.utils

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * 2026-10-29 (user : « SendVid, on peut quasiment pas lire un épisode sans coupure ») :
 * le lien SendVid porte `rate=250k` (signé, non modifiable) → ~250 Ko/s PAR CONNEXION, alors que
 * l'épisode en consomme ~625 Ko/s. Mesuré dans Chrome : 4 connexions en parallèle = ~400 Ko/s
 * CHACUNE. On télécharge donc les morceaux suivants du fichier sur [PARALLELE] connexions à la
 * fois (requêtes Range), et on les rend au lecteur dans l'ordre.
 */
@UnstableApi
class ParallelRangeDataSource(
    private val client: OkHttpClient,
    private val entetes: Map<String, String>,
    // 2026-10-31 : nombre de connexions réglable (8 pour le CDN Vidara, 4 par défaut / SendVid).
    private val PARALLELE: Int = 4,
) : BaseDataSource(true), HttpDataSource {

    companion object {
        private const val TAG = "ParallelRange"
        private const val PREMIER = 256 * 1024L      // petit premier morceau : démarrage rapide
        /** Segments déjà amorcés d'avance (cf. amorcerSuivants) et le petit pool qui s'en charge. */
        private val dejaAmorces: MutableSet<String> = java.util.Collections.synchronizedSet(LinkedHashSet())
        private val amorceurs = Executors.newFixedThreadPool(2)
    }


    // Recréé à chaque open() : le lecteur réutilise le même objet après close().
    // Morceaux plus petits quand on ouvre plus de connexions : un segment HLS de ~4 Mo doit
    //   se répartir sur toutes.
    private val MORCEAU = if (PARALLELE > 4) 512 * 1024L else 1024 * 1024L

    private var morceauFichier = MORCEAU
    private var pool: ExecutorService? = null
    private var transfertDemarre = false
    private val enCours = ArrayDeque<Future<ByteArray>>()
    private var uri: Uri? = null
    private var url = ""
    private var prochain = 0L          // début du prochain morceau à demander
    private var fin = C.LENGTH_UNSET.toLong()   // position de fin (exclue), connue après le 1er morceau
    private var courant: ByteArray = ByteArray(0)
    private var lu = 0
    private var restant = C.LENGTH_UNSET.toLong()
    private val proprietes = HashMap<String, String>()
    private var codeReponse = -1
    private var entetesReponse: Map<String, List<String>> = emptyMap()

    /** Numéro du segment ouvert : change à chaque open(), pour reconnaître les morceaux périmés. */
    @Volatile private var generation = 0

    /** [ouvert] : plage « à partir de [debut] jusqu'à la fin » dont on ne lit que [taille] octets —
     *  utilisé pour OUVRIR un segment quand l'amorçage a échoué. Une petite plage fermée en 1ʳᵉ
     *  demande (« bytes=0-65535 ») empoisonne le cache du CDN Vidara pour TOUT le monde : il ressert
     *  ensuite ces 64 Ko, même à un GET complet (vu sur Projet Dernière Chance, segment 19 :
     *  « HIT, bytes 0-65535/5368904 » → serveur passé au rouge, même en pause). */
    private fun telecharger(debut: Long, taille: Long, premier: Boolean = false, gen: Int = generation,
                            cible: String = url, essai: Int = 0, ouvert: Boolean = false): ByteArray {
        if (gen != generation) throw IOException("ParallelRange : morceau d'un segment abandonné")
        val finMorceau = debut + taille - 1
        val req = Request.Builder().url(cible).apply {
            header("User-Agent", NetworkClient.USER_AGENT)
            (entetes + proprietes).forEach { (k, v) -> header(k, v) }
            header("Range", if (ouvert) "bytes=$debut-" else "bytes=$debut-$finMorceau")
        }.build()
        val t0 = System.currentTimeMillis()
        client.newCall(req).execute().use { r ->
            if (r.code != 206 && r.code != 200) throw HttpDataSource.InvalidResponseCodeException(
                r.code, r.message, null, r.headers.toMultimap(), DataSpec(Uri.parse(url)), ByteArray(0))
            // 2026-11-01 (user : « quand on avance, de 0 à 15 minutes, là ça se met à bugger ») —
            //   C'ÉTAIT ICI. Ces champs sont PARTAGÉS par l'objet, que le lecteur réutilise d'un
            //   segment à l'autre. Lors d'un saut, les morceaux en vol du segment abandonné finissent
            //   quand même et écrivaient leur taille de fichier dans `fin` du NOUVEAU segment → fin
            //   fausse → segment tronqué ou débordant → image cassée. Seul le PREMIER morceau, lancé
            //   par open() lui-même (même génération), a le droit de les écrire.
            val ecrit = premier && gen == generation
            if (ecrit && fin == C.LENGTH_UNSET.toLong()) {
                r.header("Content-Range")?.substringAfter('/')?.toLongOrNull()?.let { fin = it }
            }
            if (ecrit) {
                codeReponse = r.code
                entetesReponse = r.headers.toMultimap()
            }
            if (gen != generation) throw IOException("ParallelRange : morceau d'un segment abandonné")
            // 2026-11-02 (user : « Vidara commence directement à la fin du film ») - CAUSE : sur une
            //   plage ouverte (« bytes=0- »), le serveur répond parfois 200 (plage ignorée). On ne lisait
            //   que 64 Ko, puis la règle « 200 = fichier entier » posait fin = 64 Ko : la LISTE DE LECTURE
            //   (200 Ko, 1 436 lignes) arrivait tronquée, sans marque de fin → le lecteur la prenait pour un
            //   direct et démarrait « en bout de flux », à 1 h 03 d'un film de 1 h 59 (durée vue : 3 867 s).
            //   Un 200 à une plage ouverte = le serveur ignore les plages : on lit alors le fichier ENTIER.
            var corps = if (ouvert && r.code == 206 && r.body != null) {
                // Plage ouverte honorée : on ne lit que le début voulu, puis on coupe.
                val src = r.body!!.source()
                src.request(taille)
                src.readByteArray(minOf(taille, src.buffer.size))
            } else r.body?.bytes() ?: ByteArray(0)
            // 2026-10-31 : un 200 (Range ignoré) rend le fichier ENTIER → c'est la fin, sinon
            //   on redemanderait des morceaux au-delà (416). Cas des playlists HLS.
            if (ecrit && r.code == 200 && fin == C.LENGTH_UNSET.toLong()) fin = debut + corps.size
            // 2026-11-01 (user, Vidara sur la télé : « à moitié buggé », image revenue correcte en
            //   relançant) — garde-fous d'ASSEMBLAGE : un morceau mal placé ou incomplet donne une
            //   image cassée sans erreur visible. On vérifie donc chaque réponse :
            //   · 200 (Range ignoré) alors qu'on visait un morceau → on découpe la bonne tranche ;
            //   · 206 qui ne commence pas où on l'attendait → erreur (le lecteur réessaie) ;
            //   · 206 plus court que demandé (et pas la fin du fichier) → on complète le reste.
            // 2026-11-02 (user : « le serveur Vidara saute en début de lecture, devient rouge ») - relevé
            //   au banc de test : le serveur du SON (m696yefd.s1q2105.com, fichiers passés en .woff2)
            //   répond à « bytes=65536-131071 » par « 206 bytes 0-149459/149460 » = le fichier ENTIER
            //   étiqueté comme une plage. On le traitait en « morceau mal placé » puis « segment
            //   empoisonné » (410) → serveur abandonné. C'est en réalité le même cas qu'un 200 : on
            //   découpe la bonne tranche.
            val entier206 = r.code == 206 && r.header("Content-Range")?.let { cr ->
                Regex("""bytes\s+0-(\d+)/(\d+)""").find(cr)?.groupValues?.let { g ->
                    g[1].toLongOrNull()?.plus(1) == g[2].toLongOrNull() } } == true &&
                corps.size.toLong() >= debut + 1
            if (entier206 && debut > 0) {
                Log.w(TAG, "fichier entier renvoyé en 206 (${corps.size} o)  tranche $debut+$taille découpée")
                if (ecrit && fin == C.LENGTH_UNSET.toLong()) fin = corps.size.toLong()
                val a = debut.coerceAtMost(corps.size.toLong()).toInt()
                corps = corps.copyOfRange(a, (debut + taille).coerceAtMost(corps.size.toLong()).toInt())
            } else if (r.code == 200 && (debut > 0 || corps.size > taille)) {
                Log.w(TAG, "Range ignoré (HTTP 200, ${corps.size} o) → tranche $debut+$taille découpée")
                val a = debut.coerceAtMost(corps.size.toLong()).toInt()
                corps = corps.copyOfRange(a, (debut + taille).coerceAtMost(corps.size.toLong()).toInt())
            } else if (r.code == 206) {
                val depart = r.header("Content-Range")?.let { Regex("""bytes\s+(\d+)-""").find(it)?.groupValues?.get(1)?.toLongOrNull() }
                if (depart != null && depart != debut) {
                    // 2026-11-01 (relevé sur la télé, puis vérifié au curl) : le CDN Vidara répond
                    //   parfois « 206 bytes 0-65535 » QUEL QUE SOIT l'octet demandé — un bout de
                    //   remplacement (lien périmé, ou trop de demandes à la fois). Avant ces contrôles,
                    //   ce bout était recollé au milieu du segment → image cassée (« à moitié buggé »,
                    //   surtout après un saut). On réessaie ce morceau un peu plus tard (3 fois), puis
                    //   on renvoie une erreur propre au lecteur — JAMAIS de morceau faux recollé.
                    if (essai < 3) {
                        Log.w(TAG, "morceau mal placé : demandé à $debut, reçu à $depart → nouvel essai ${essai + 1}/3")
                        Thread.sleep(400L * (essai + 1))
                        return telecharger(debut, taille, premier, gen, cible, essai + 1)
                    }
                    // 2026-11-01 (user, Oppo : « il se passe quand même des trucs bizarres ») — un segment
                    //   empoisonné dans le cache du CDN ne se répare pas en réessayant (vérifié au curl :
                    //   même un GET complet rend « HIT, bytes 0-65535 »). Une simple IOException faisait
                    //   réessayer le lecteur en boucle : il reculait, rechargeait, puis basculait très
                    //   tard. On renvoie une erreur DÉFINITIVE (410, « n'existe plus ») : le lecteur
                    //   abandonne ce serveur tout de suite et passe au suivant.
                    Log.w(TAG, "segment empoisonné dans le cache du CDN (demandé à $debut, reçu à $depart, 3 essais) → serveur abandonné")
                    throw HttpDataSource.InvalidResponseCodeException(
                        410, "Segment illisible (cache du serveur abîmé)", null, r.headers.toMultimap(),
                        DataSpec(Uri.parse(url)), ByteArray(0))
                }
                val attendu = if (fin != C.LENGTH_UNSET.toLong()) minOf(taille, fin - debut) else taille
                if (corps.size in 1 until attendu) {
                    Log.w(TAG, "morceau incomplet : ${corps.size}/$attendu o à $debut → on complète")
                    corps += telecharger(debut + corps.size, attendu - corps.size, gen = gen, cible = cible)
                }
            }
            if (corps.size >= 256 * 1024) Log.d(TAG, "morceau ${corps.size / 1024} Ko en ${System.currentTimeMillis() - t0} ms (${r.protocol})")
            return corps
        }
    }

    /**
     * 2026-11-01 (user : Vidara « à moitié buggé » après un saut, puis rouge) — CAUSE PROUVÉE au
     *   curl : le cache du CDN Vidara garde la PREMIÈRE réponse reçue pour un segment, plage
     *   comprise, et la ressert ensuite à TOUT le monde quelle que soit la plage demandée
     *   (X-Cache-Status: HIT, « bytes 0-65535 » pour une demande à 1 000 000). Notre 1er morceau
     *   (une plage de 64 Ko) « empoisonnait » donc le segment : les morceaux suivants recevaient
     *   ces 64 Ko → image cassée. Si la 1ʳᵉ demande est un GET ENTIER (même coupé après 64 Ko),
     *   le CDN met le fichier complet en cache et toutes les plages suivantes sont justes.
     * On ouvre donc chaque segment par un GET sans plage : on lit la taille et les 64 premiers Ko,
     *   puis on coupe. Rend null si ça n'a pas marché (on retombe sur l'ancienne 1ʳᵉ plage).
     */
    private fun amorcer(position: Long): ByteArray? = runCatching {
        val gen = generation
        val req = Request.Builder().url(url).apply {
            header("User-Agent", NetworkClient.USER_AGENT)
            (entetes + proprietes).forEach { (k, v) -> header(k, v) }
        }.build()
        client.newCall(req).execute().use { r ->
            if (gen != generation) throw IOException("segment abandonné")
            // 2026-11-01 : un 206 qui couvre TOUT le fichier (« bytes 0-176531/176532 ») est un fichier
            //   complet — avant, on le prenait pour un échec et on retombait sur une petite plage.
            val plage = r.header("Content-Range")?.let { Regex("""bytes\s+(\d+)-(\d+)/(\d+)""").find(it)?.groupValues }
            val complet206 = r.code == 206 && plage != null && plage[1] == "0" &&
                plage[2].toLongOrNull()?.plus(1) == plage[3].toLongOrNull()
            if (r.code != 200 && !complet206) {
                Log.w(TAG, "amorçage : HTTP ${r.code} ${r.header("Content-Range").orEmpty()} → méthode par plages")
                return@runCatching null
            }
            val total = r.body?.contentLength() ?: -1L
            if (total <= 0) return@runCatching null
            codeReponse = r.code
            entetesReponse = r.headers.toMultimap()
            fin = total
            if (position > 0) return@runCatching null   // cache amorcé ; la suite part par plages
            val src = r.body!!.source()
            val voulu = minOf(64 * 1024L, total)
            src.request(voulu)
            src.readByteArray(minOf(voulu, src.buffer.size))
        }
    }.onFailure { if (it is IOException && it.message == "segment abandonné") throw it }.getOrNull()

    /**
     * 2026-11-01 (user : « Vidara a du mal sur une reprise ») — mesuré au curl : sur un segment que
     *   le CDN n'a pas encore, la fin du fichier arrive en 7,8 s juste après l'amorçage, contre 3,9 s
     *   si on l'a amorcé 8 s avant (le CDN le rapatrie de sa source en attendant). Sur une reprise
     *   au milieu du film, AUCUN segment n'est en cache : les derniers morceaux traînaient 9 à 13 s.
     *   → on amorce aussi les 2 segments SUIVANTS (GET coupé aussitôt les en-têtes reçus), une fois
     *   chacun, pour qu'ils soient prêts quand le lecteur les demandera.
     *   Uniquement pour les noms numérotés (« …_255.woff » → 256, 257).
     */
    private fun amorcerSuivants(courante: String) {
        // Segments HLS seulement (« seg_…_255.woff », « segment_108.woff ») — pas les playlists ni
        //   les fichiers vidéo entiers (un MP4 « …/12345.mp4 » n'a pas de « suivant »).
        if (!Regex("""(?i)/seg[^/]*\d+\.(woff2?|ts|m4s|jpg|png|html|js|css)(\?|$)""").containsMatchIn(courante)) return
        val m = Regex("""^(.*[_/-])(\d+)(\.[A-Za-z0-9]+)(\?.*)?$""").find(courante) ?: return
        val n = m.groupValues[2].toLongOrNull() ?: return
        val largeur = m.groupValues[2].length
        val entetesCopie = entetes + proprietes
        for (k in 1..2) {
            val suivante = m.groupValues[1] + (n + k).toString().padStart(largeur, '0') + m.groupValues[3] + m.groupValues[4]
            if (!dejaAmorces.add(suivante)) continue
            if (dejaAmorces.size > 400) dejaAmorces.clear()
            amorceurs.execute {
                runCatching {
                    val req = Request.Builder().url(suivante).apply {
                        header("User-Agent", NetworkClient.USER_AGENT)
                        entetesCopie.forEach { (kk, v) -> header(kk, v) }
                    }.build()
                    client.newCall(req).execute().use { r -> Log.d(TAG, "amorcé d'avance : …${suivante.takeLast(24)} → HTTP ${r.code}") }
                }
            }
        }
    }

    private fun planifier() {
        while (enCours.size < PARALLELE && (fin == C.LENGTH_UNSET.toLong() || prochain < fin)) {
            val debut = prochain
            val taille = if (fin == C.LENGTH_UNSET.toLong()) MORCEAU else minOf(morceauFichier, fin - debut)
            prochain += taille
            val g = generation
            val u = url
            enCours.addLast(pool!!.submit(Callable { telecharger(debut, taille, gen = g, cible = u) }))
        }
    }

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        url = dataSpec.uri.toString()
        fin = C.LENGTH_UNSET.toLong()
        generation++   // les morceaux encore en vol du segment précédent deviennent périmés
        enCours.clear()
        pool = Executors.newFixedThreadPool(PARALLELE)
        transferInitializing(dataSpec)
        try {
            // 1er morceau seul et petit : il donne la taille du fichier et démarre vite.
            // 2026-11-01 : 64 Ko au lieu de 256 Ko en mode 8 connexions — ce 1er morceau passe SEUL
            //   sur une connexion bridée (0,7 à 4 s pour 256 Ko relevés sur l'Oppo).
            courant = amorcer(dataSpec.position) ?: telecharger(dataSpec.position, if (PARALLELE > 4) 64 * 1024L else PREMIER, premier = true, ouvert = true)
            amorcerSuivants(url)
            lu = 0
            prochain = dataSpec.position + courant.size
        } catch (e: Exception) {
            if (e is HttpDataSource.InvalidResponseCodeException) throw e
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e as? IOException ?: IOException(e), dataSpec, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
        }
        val total = if (fin != C.LENGTH_UNSET.toLong()) fin - dataSpec.position else C.LENGTH_UNSET.toLong()
        restant = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else total
        if (dataSpec.length != C.LENGTH_UNSET.toLong()) fin = minOf(
            if (fin == C.LENGTH_UNSET.toLong()) Long.MAX_VALUE else fin, dataSpec.position + dataSpec.length)
        // 2026-11-01 (Wiflix · Vidara, segments 720p de 0,2 à 1,5 Mo) : avec des morceaux fixes de
        //   512 Ko, un segment de 600 Ko n'utilisait qu'UNE connexion après le 1er morceau → débit
        //   d'une seule connexion bridée. On découpe maintenant le reste du fichier en parts égales
        //   sur toutes les connexions (64 Ko minimum, MORCEAU maximum).
        morceauFichier = if (fin == C.LENGTH_UNSET.toLong()) MORCEAU
            else ((fin - prochain + PARALLELE - 1) / PARALLELE).coerceIn(64 * 1024L,
                // 2026-11-01 (user, mode léger sur l'Oppo : « Vidara j'ai pas l'impression qu'il
                //   fonctionne bien ») — 4 connexions bloquaient le 1080p (le CDN bride CHAQUE
                //   connexion) : on garde les 8 connexions (le débit), mais des parts de 1 Mo au lieu
                //   de 2 en mode léger (la mémoire en vol est divisée par deux sur les petites box).
                if (PARALLELE > 4 && !UserPreferences.modeLeger) 2048 * 1024L else 1024 * 1024L)
        //   (en 8 connexions, jusqu'à 2 Mo par part : un segment 1080p Vidara de 6 à 8 Mo part en UN
        //   seul tour sur les 8 connexions, au lieu de 2 tours de 512 Ko où le plus lent bloquait tout.)
        planifier()
        Log.d(TAG, "ouvert à ${dataSpec.position} (fichier ${fin} o) — $PARALLELE connexions, morceaux de ${morceauFichier / 1024} Ko")
        transferStarted(dataSpec)
        transfertDemarre = true
        return restant
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (restant == 0L) return C.RESULT_END_OF_INPUT
        if (lu >= courant.size) {
            val f = enCours.removeFirstOrNull() ?: return C.RESULT_END_OF_INPUT
            courant = try { f.get() } catch (e: Exception) {
                // Erreur définitive (segment empoisonné) : transmise TELLE QUELLE pour que le lecteur
                //   la reconnaisse et abandonne le serveur au lieu de réessayer en boucle.
                (e.cause as? HttpDataSource.InvalidResponseCodeException)?.let { throw it }
                throw HttpDataSource.HttpDataSourceException.createForIOException(
                    (e.cause as? IOException) ?: IOException(e), DataSpec(uri ?: Uri.EMPTY),
                    HttpDataSource.HttpDataSourceException.TYPE_READ)
            }
            lu = 0
            planifier()
            if (courant.isEmpty()) return C.RESULT_END_OF_INPUT
        }
        var n = minOf(length, courant.size - lu)
        if (restant != C.LENGTH_UNSET.toLong()) n = minOf(n.toLong(), restant).toInt()
        System.arraycopy(courant, lu, buffer, offset, n)
        lu += n
        if (restant != C.LENGTH_UNSET.toLong()) restant -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        generation++   // ce qui est encore en vol ne touche plus à rien
        enCours.forEach { it.cancel(true) }
        enCours.clear()
        pool?.shutdownNow()
        pool = null
        uri = null
        courant = ByteArray(0); lu = 0
        // N'annonce la fin que d'un transfert réellement démarré (sinon NPE dans BaseDataSource).
        if (transfertDemarre) { transfertDemarre = false; transferEnded() }
    }

    override fun setRequestProperty(name: String, value: String) { proprietes[name] = value }
    override fun clearRequestProperty(name: String) { proprietes.remove(name) }
    override fun clearAllRequestProperties() { proprietes.clear() }
    override fun getResponseCode(): Int = codeReponse
    override fun getResponseHeaders(): Map<String, List<String>> = entetesReponse

    // 2026-11-01 (user : Vidara sur Projet Dernière Chance, « comme si le flux n'arrivait pas
    //   normalement ») : le CDN Vidara parle HTTP/2 → OkHttp faisait passer les 8 requêtes Range
    //   dans UNE SEULE connexion TCP, et le CDN bride PAR connexion (~2,5 Mbit/s). Mesuré depuis
    //   le PC : 1 connexion = 2,3 Mbit/s · 8 vraies connexions = 15 Mbit/s · 16 = 21 Mbit/s.
    //   Sur l'Oppo, avec « 8 connexions », un morceau de 3,9 Mo mettait 11,9 s (2,6 Mbit/s).
    //   → on force HTTP/1.1 : chaque requête Range a sa propre connexion.
    class Factory(client: OkHttpClient, private val parallele: Int = 4) : HttpDataSource.Factory {
        private val client = client.newBuilder().protocols(listOf(okhttp3.Protocol.HTTP_1_1)).build()
        private val defauts = HashMap<String, String>()
        override fun setDefaultRequestProperties(props: Map<String, String>): HttpDataSource.Factory {
            defauts.clear(); defauts.putAll(props); return this
        }
        override fun createDataSource(): HttpDataSource = ParallelRangeDataSource(client, HashMap(defauts), parallele)
    }
}
