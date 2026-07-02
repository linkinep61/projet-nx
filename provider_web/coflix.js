/* Coflix WebJS provider — window.__P (2026-07-01)
 * Backup source (Cloudstream/Movix) hébergé sur GitHub, chargé par WebJsProvider.
 * Flux : /suggest.php?query= (search JSON) → /film/<slug>/ ou
 *        /episode/<slug>-SxE/ → <iframe lecteurvideo> → fetch embed
 *        → onclick="showVideo('<base64>')" → décode → URL host (voe/lulustream/…).
 * Validé en direct (Chrome) : search + getTvShow + extractServers OK.
 * Domaine actif : coflix.trade (cymru/date/click redirigent). PAS de Cloudflare.
 */
(function () {
  const BASE = location.origin;
  const enc = encodeURIComponent;

  function slugFromUrl(u) {
    try { return new URL(u, BASE).pathname.replace(/^\/|\/$/g, ''); } catch (e) { return String(u || ''); }
  }
  function guessHost(u) {
    try {
      const h = new URL(u).host.replace(/^www\./, '');
      return h.split('.')[0].replace(/^./, function (c) { return c.toUpperCase(); });
    } catch (e) { return 'Lecteur'; }
  }
  function detail() {
    return {
      title: (document.querySelector('h1') ? document.querySelector('h1').textContent : '').trim(),
      poster: (document.querySelector('meta[property="og:image"]') || {}).content || '',
      overview: (document.querySelector('meta[property="og:description"]') || {}).content || '',
    };
  }

  async function search(query) {
    try {
      const arr = await fetch(BASE + '/suggest.php?query=' + enc(query)).then(function (r) { return r.json(); });
      return (arr || []).map(function (o) {
        return {
          type: o.post_type === 'series' ? 'tv' : 'movie',
          id: slugFromUrl(o.url),
          title: o.title || '',
          year: o.year || '',
        };
      }).filter(function (x) { return x.id && x.title; });
    } catch (e) { return []; }
  }

  async function getMovie(id) {
    const d = detail();
    return { type: 'movie', id: id, title: d.title, poster: d.poster, overview: d.overview };
  }

  async function getTvShow(id) {
    const d = detail();
    const eps = Array.prototype.slice.call(document.querySelectorAll('a[href*="/episode/"]'))
      .map(function (a) { return a.getAttribute('href') || ''; });
    const seasons = Array.from(new Set(eps.map(function (h) {
      const m = h.match(/-(\d+)x\d+\/?$/); return m ? m[1] : null;
    }).filter(Boolean))).sort(function (a, b) { return a - b; });
    const list = (seasons.length ? seasons : ['1']).map(function (s) {
      return { id: id + '#s' + s, number: parseInt(s, 10), title: 'Saison ' + s };
    });
    return { type: 'tv', id: id, title: d.title, poster: d.poster, overview: d.overview, seasons: list };
  }

  async function getEpisodesBySeason(seasonId) {
    const s = (seasonId.match(/#s(\d+)/) || [])[1] || '1';
    const slug = seasonId.split('#')[0].replace(/^serie\//, '');
    const parsed = Array.prototype.slice.call(document.querySelectorAll('a[href*="/episode/"]'))
      .map(function (a) { return a.getAttribute('href') || ''; })
      .map(function (h) { const m = h.match(/-(\d+)x(\d+)\/?$/); return m ? { s: m[1], e: parseInt(m[2], 10) } : null; })
      .filter(function (x) { return x && x.s === s; })
      .map(function (x) { return x.e; });
    const nums = parsed.length ? parsed : Array.from({ length: 40 }, function (_, i) { return i + 1; });
    const seen = {}; const out = [];
    nums.sort(function (a, b) { return a - b; }).forEach(function (e) {
      if (!seen[e]) { seen[e] = 1; out.push({ id: 'episode/' + slug + '-' + s + 'x' + e, number: e, title: 'Épisode ' + e }); }
    });
    return out;
  }

  async function extractServers() {
    const ifr = document.querySelector('iframe[src*="lecteurvideo"]') || document.querySelector('iframe');
    const src = ifr && ifr.getAttribute('src');
    if (!src) return [];
    let embed;
    try { embed = await fetch(new URL(src, location.href).href).then(function (r) { return r.text(); }); }
    catch (e) { return []; }
    const out = []; const seen = {};
    const re = /onclick="showVideo\('([^']+)'/g; let m;
    while ((m = re.exec(embed))) {
      let url; try { url = atob(m[1]); } catch (e) { continue; }
      if (!url || !/^https?:/.test(url) || seen[url]) continue;
      seen[url] = 1;
      out.push({ name: guessHost(url), url: url });
    }
    return out;
  }

  // Backup-only : pas de navigation catalogue (home/movies/tvshows vides).
  async function getHome() { return []; }
  async function getMovies() { return []; }
  async function getTvShows() { return []; }

  window.__P = {
    getHome: getHome, search: search, getMovies: getMovies, getTvShows: getTvShows,
    getMovie: getMovie, getTvShow: getTvShow, getEpisodesBySeason: getEpisodesBySeason,
    extractServers: extractServers,
  };
})();
