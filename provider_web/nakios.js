/*
 * nakios.js — Provider JS hébergé (moteur WebJsProvider).
 * Site  : https://nakios.store  (SPA Vite + API api.nakios.store)
 * Rôle  : BACKUP par TMDB id (agrégateur de sources, souvent VF direct MP4/HLS).
 *
 * S'exécute DANS une WebView chargée sur le FRONT (nakios.store) → le fetch vers
 * api.nakios.store part avec l'Origin du front (CORS autorisé, validé en direct).
 *
 * RÉSILIENCE (but user) : l'URL de l'API est dérivée de location.origin
 *   → https://nakios.store  ==>  https://api.nakios.store
 *   Si Nakios change de domaine (.ink → .store → …), le front redirige, la
 *   WebView suit, location.origin reflète le nouveau domaine, l'API suit toute
 *   seule. Aucun rebuild APK nécessaire pour un changement de domaine.
 *
 * Contrat exposé (window.__P) :
 *   getSourcesByTmdb(kind, tmdbId, season, episode) -> [ {name, url, quality, lang} ]
 *     kind = "movie" | "tv"
 *   + stubs standard (getHome/search/... ) pour que le moteur soit content
 *     (Nakios n'est PAS browsable, il ne sert que de backup source-par-id).
 */
(function () {
  // https://nakios.store -> https://api.nakios.store  (suit les redirections de domaine)
  function apiBase() {
    try {
      var o = location.origin; // ex "https://nakios.store"
      // insère "api." après le schéma
      return o.replace(/^(https?:\/\/)/, '$1api.');
    } catch (e) {
      return 'https://api.nakios.store';
    }
  }

  async function apiJson(path) {
    var r = await fetch(apiBase() + path, { headers: { 'Accept': 'application/json' } });
    if (!r.ok) throw new Error('HTTP ' + r.status);
    return await r.json();
  }

  // Mappe la réponse {success, sources:[{name,url,quality,lang,provider,isEmbed,...}]}
  // vers le contrat serveur [{name,url,quality,lang}]. On garde les URLs http(s)
  // (embed OU flux direct) → l'extraction finale est faite par les Extractors in-app.
  function mapSources(j) {
    var arr = (j && j.sources) || [];
    var out = [];
    for (var i = 0; i < arr.length; i++) {
      var s = arr[i] || {};
      var url = s.url || s.file || s.src || '';
      if (!url || url.indexOf('http') !== 0) continue;
      var name = s.name || s.provider || ('Source ' + (i + 1));
      out.push({
        name: name,
        url: url,
        quality: s.quality || '',
        lang: s.lang || s.language || '',
      });
    }
    return out;
  }

  var P = {
    /**
     * Sources par TMDB id (LE point d'entrée backup).
     *   kind="movie" -> /api/sources/movie/<id>
     *   kind="tv"    -> /api/sources/tv/<id>/<season>/<episode>
     */
    async getSourcesByTmdb(kind, tmdbId, season, episode) {
      if (!tmdbId) return [];
      var path;
      if (kind === 'tv') {
        if (!season || !episode) return [];
        path = '/api/sources/tv/' + tmdbId + '/' + season + '/' + episode;
      } else {
        path = '/api/sources/movie/' + tmdbId;
      }
      try {
        var j = await apiJson(path);
        return mapSources(j);
      } catch (e) {
        return [];
      }
    },

    // ─── stubs standard (Nakios n'est pas browsable) ───
    async getHome() { return []; },
    async search(q, page) { return []; },
    async getMovies(page) { return []; },
    async getTvShows(page) { return []; },
    async getMovie(id) { return { type: 'movie', id: id, title: '', poster: '', banner: '', overview: '' }; },
    async getTvShow(id) { return { type: 'tv', id: id, title: '', poster: '', banner: '', overview: '', seasons: [] }; },
    async getEpisodesBySeason(seasonId) { return []; },
    async extractServers() { return []; },
  };

  window.__P = P;
})();
