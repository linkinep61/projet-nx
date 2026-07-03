/*
 * papadustream-v2.js — backup PAR TITRE (moteur WebJsProvider), sans captcha.
 * baseUrl = passerelle stable papadustream.info (gateway:true). resolveContentBase() lit
 * le lien « Accéder » → domaine de contenu courant ; le moteur y navigue (top-level, pas
 * de CORS) puis travaille same-origin. Auto-répare les changements de domaine.
 * Sources = JSON en clair dans la page (server_name/version/link) → Dood(kokoflix)/Vidzy.
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

  // Lit le domaine de CONTENU courant depuis la passerelle (lien « Accéder »).
  function resolveContentBase() {
    try {
      var links = [].slice.call(document.querySelectorAll('a[href]')).map(function (a) {
        try { return new URL(a.href); } catch (e) { return null; }
      }).filter(function (u) { return u && /papadustream/i.test(u.host) && u.host !== location.host; });
      // priorité aux hôtes "de contenu" (souvent en -v2/-v3), sinon le 1er externe.
      var pref = links.filter(function (u) { return /-v\d/i.test(u.host); });
      var pick = (pref[0] || links[0]);
      return pick ? pick.origin : location.origin;
    } catch (e) { return location.origin; }
  }

  var P = {
    async search(q, page) { return await doSearch(q); },
    async getMovie(id) { return { type: 'movie', id: id, title: '', poster: '', banner: '', overview: '' }; },
    async getTvShow(id) { return { type: 'tv', id: id, title: '', poster: '', banner: '', overview: '', seasons: [] }; },
    async getEpisodesBySeason(seasonId) { return []; },
    async extractServers() { return parseServers(); },
    async resolveContentBase() { return resolveContentBase(); },
    async getHome() { return []; },
    async getMovies() { return []; },
    async getTvShows() { return []; }
  };
  window.__P = P;
})();