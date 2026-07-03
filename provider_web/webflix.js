/*
 * webflix.js — Provider JS hébergé (moteur WebJsProvider).
 * Site : https://webflix.art (SPA React). Rôle : BACKUP par TMDB id.
 * Source = /api/fastflux (MP4 DIRECT VF, sans gate ni auth ; CDN cdn.drinkoflix.lol,
 * protégé Cloudflare → l'app résout le cf_clearance comme pour Nakios).
 * S'exécute dans une WebView sur webflix.art → fetch same-origin de /api/fastflux.
 * Contrat : getSourcesByTmdb(kind, tmdbId, season, episode) -> [{name,url,quality,lang}]
 */
(function () {
  async function apiJson(path) {
    var r = await fetch(path, { headers: { 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest' } });
    if (!r.ok) throw new Error('HTTP ' + r.status);
    return await r.json();
  }
  function mapData(j) {
    if (!j || !j.success || !j.available || !j.data) return [];
    var d = j.data;
    var url = d.url || d.file || d.src || '';
    if (!url || url.indexOf('http') !== 0) return [];
    var quality = (d.quality && d.quality !== 'Unknown') ? d.quality : '';
    return [{ name: 'Webflix', url: url, quality: quality, lang: d.language || d.lang || 'VF' }];
  }
  var P = {
    async getSourcesByTmdb(kind, tmdbId, season, episode) {
      if (!tmdbId) return [];
      var path;
      if (kind === 'tv') {
        if (!season || !episode) return [];
        path = '/api/fastflux?type=series&tmdb_id=' + tmdbId + '&season=' + season + '&episode=' + episode;
      } else {
        path = '/api/fastflux?type=movie&tmdb_id=' + tmdbId;
      }
      try { return mapData(await apiJson(path)); } catch (e) { return []; }
    },
    async getHome() { return []; },
    async search(q, page) { return []; },
    async getMovies(page) { return []; },
    async getTvShows(page) { return []; },
    async getMovie(id) { return { type: 'movie', id: id, title: '', poster: '', banner: '', overview: '' }; },
    async getTvShow(id) { return { type: 'tv', id: id, title: '', poster: '', banner: '', overview: '', seasons: [] }; },
    async getEpisodesBySeason(seasonId) { return []; },
    async extractServers() { return []; }
  };
  window.__P = P;
})();
