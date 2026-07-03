/*
 * papadustream-v2.js — Provider JS hébergé (moteur WebJsProvider), backup PAR TITRE.
 * Site : https://papadustream-v2.org (DLE-like, SANS captcha). Nom affiché "Papadustream V2".
 * Sources = liste de serveurs EN CLAIR dans le HTML de la page (JSON server_name/version/link).
 *   link = host direct (vidzy.cc, extrait par l'app) OU redirecteur kokoflix.lol/*_go.php -> Dood.
 * Recherche : /?s=<query> (HTML). Films OK ; séries = suivi.
 * Contrat WebJsProvider : search, getMovie, getTvShow, getEpisodesBySeason, extractServers.
 */
(function () {
  function slugTitle(s) { return (s || '').replace(/-/g, ' ').replace(/\b\w/g, function (c) { return c.toUpperCase(); }); }
  async function getText(path) { var r = await fetch(path); return await r.text(); }

  async function doSearch(query) {
    try {
      var html = await getText('/?s=' + encodeURIComponent(query));
      var doc = new DOMParser().parseFromString(html, 'text/html');
      var as = doc.querySelectorAll('a[href*="/movie/"],a[href*="/tv/"],a[href*="/tv-shows/"]');
      var seen = {}, out = [];
      for (var i = 0; i < as.length; i++) {
        var href = as[i].getAttribute('href') || '';
        var m = href.match(/\/(movie|tv-shows|tv)\/([a-z0-9-]+)/i);
        if (!m) continue;
        var type = /movie/i.test(m[1]) ? 'movie' : 'tv';
        var slug = m[2];
        var id = (type === 'movie' ? 'movie/' : 'tv/') + slug;
        if (seen[id]) continue; seen[id] = 1;
        var img = as[i].querySelector('img');
        var poster = img ? (img.getAttribute('data-src') || img.getAttribute('src') || '') : '';
        out.push({ type: type, id: id, title: slugTitle(slug), poster: poster });
      }
      return out;
    } catch (e) { return []; }
  }

  // Parse les serveurs présents dans le HTML de la page courante (après navigation).
  function parseServers() {
    var html = document.documentElement.outerHTML;
    var out = [], seen = {};
    var re = /"server_name"\s*:\s*"([^"]+)"[^{}]*?"version"\s*:\s*"([^"]+)"[^{}]*?"link"\s*:\s*"([^"]+)"/g;
    var m;
    while ((m = re.exec(html)) !== null) {
      var name = m[1], version = m[2], link = m[3].replace(/\\\//g, '/');
      if (!/^https?:/.test(link)) continue;
      if (seen[link]) continue; seen[link] = 1;
      out.push({ name: name + ' [' + version + ']', url: link, lang: version });
    }
    return out;
  }

  var P = {
    async search(q, page) { return await doSearch(q); },
    async getMovie(id) { return { type: 'movie', id: id, title: '', poster: '', banner: '', overview: '' }; },
    async getTvShow(id) { return { type: 'tv', id: id, title: '', poster: '', banner: '', overview: '', seasons: [] }; },
    async getEpisodesBySeason(seasonId) { return []; },
    async extractServers() { return parseServers(); },
    async getHome() { return []; },
    async getMovies() { return []; },
    async getTvShows() { return []; }
  };
  window.__P = P;
})();