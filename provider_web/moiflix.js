/* moiflix.js — backup WebJS pour Movix (moiflix.fans)
 * Le "serveur" moiflix = une PAGE que seul MoiflixExtractor (WebView m3u8) extrait.
 * URLs deterministes : /movie/<hash> ou /episode/<hash>/season-N-episode-N.
 * getServersByTitle = search AJAX (same-origin, pas de CF) + matching (replique de
 * searchMoiflix natif) + construit l'URL page. Le domaine vit ici (location.origin).
 */
(function () {
  function norm(s) { return (s || '').toLowerCase().replace(/[^a-z0-9 ]/g, ' ').replace(/\s+/g, ' ').trim(); }
  function words(s) { return norm(s).split(' ').filter(function (w) { return w.length > 1; }); }
  function containsAll(big, small) { for (var i = 0; i < small.length; i++) { if (big.indexOf(small[i]) < 0) return false; } return true; }

  async function getServersByTitle(kind, title, year, season, episode) {
    if (!title) return [];
    try {
      var r = await fetch('/ajax/posts?q=' + encodeURIComponent(title), { headers: { 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest' } });
      var j = await r.json();
      var items = (j && j.data) || [];
      if (!items.length) return [];
      var preferType = (kind === 'tv') ? 'Show' : 'Film';
      var tNorm = norm(title), tWords = words(title);
      var best = null, bestScore = -1;
      for (var i = 0; i < items.length; i++) {
        var it = items[i];
        var nName = it.name || '', nUrl = it.url || '', nType = it.type || '';
        if (!nName || !nUrl) continue;
        var nNorm = norm(nName), cWords = words(nName);
        var lenDiff = Math.abs(nNorm.length - tNorm.length) / Math.max(nNorm.length, tNorm.length, 1);
        var score = 0;
        if (nNorm === tNorm) score = 100;
        else if (tWords.length && containsAll(cWords, tWords) && lenDiff <= 0.30) score = 90;
        else if (cWords.length && containsAll(tWords, cWords) && lenDiff <= 0.30) score = 80;
        else score = 0;
        if (nType === preferType) score += 20;
        if (score > bestScore) { bestScore = score; best = it; }
      }
      if (!best || bestScore < 90) return [];
      var origin = location.origin;
      var parts = best.url.split('/').filter(Boolean);
      var hash = parts[parts.length - 1];
      var finalUrl;
      if (kind === 'tv' && season && episode) finalUrl = origin + '/episode/' + hash + '/season-' + season + '-episode-' + episode;
      else finalUrl = origin + (best.url.charAt(0) === '/' ? best.url : '/' + best.url);
      return [{ url: finalUrl, name: 'Moiflix', lang: 'VF' }];
    } catch (e) { return []; }
  }

  window.__P = {
    getServersByTitle: getServersByTitle,
    getHome: async function () { return []; },
    search: async function () { return []; },
    getMovies: async function () { return []; },
    getTvShows: async function () { return []; },
    getGenre: async function () { return []; },
    getMovie: async function (id) { return { id: id, title: '', type: 'movie' }; },
    getTvShow: async function (id) { return { id: id, title: '', type: 'tv', seasons: [] }; },
    getEpisodesBySeason: async function () { return []; },
    extractServers: async function () { return []; }
  };
})();