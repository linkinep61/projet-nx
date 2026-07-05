/*
 * dessinanime.js — Provider WebJS hébergé (moteur WebJsProvider).
 * Site : https://dessinanime.cc  (Next.js App Router / RSC + Cloudflare).
 *
 * RÉÉCRITURE 2026-07-04 (v2) : tourne dans une WebView CHAUDE sur le site.
 *   - LISTINGS (home/catalogue/recherche) : le WebView est sur baseUrl → fetch fiables.
 *       home/catalogue = flux RSC (header RSC:1) ; recherche = API JSON /api/search.
 *   - DÉTAIL (fiche/saisons/épisodes/serveurs) : le moteur NAVIGUE la WebView sur la page
 *       AVANT d'appeler ces fonctions → on lit la PAGE DÉJÀ CHARGÉE (document.innerHTML +
 *       meta og), PAS un re-fetch (qui racait la navigation et prenait des 403 sur TV).
 *       innerHTML contient le RSC (saisons/épisodes/sources) ; og donne titre/poster/overview.
 *
 * Contrat (window.__P) :
 *   getHome() -> [ {name, items:[item]} ]
 *   search(q, page) -> [item]
 *   getMovies(page) / getTvShows(page) -> [item]
 *   getMovie(id) / getTvShow(id) -> item (+ seasons pour tv)
 *   getEpisodesBySeason(seasonId) -> [episode]
 *   extractServers() -> [ {name, url} ]
 *   item = {type:'movie'|'tv', id:'movie/<slug>'|'tv/<slug>', title, poster, banner, year, overview}
 */
(function () {
  var BASE = 'https://dessinanime.cc';

  function rsc(path) {
    return fetch(path, { headers: { 'RSC': '1' }, credentials: 'include' })
      .then(function (r) { return r.text(); })
      .then(function (t) { return t.replace(/\\"/g, '"'); });
  }
  function json(path) {
    return fetch(path, { headers: { 'Accept': 'application/json' }, credentials: 'include' })
      .then(function (r) { return r.json(); });
  }
  function pageHtml() { return document.documentElement.innerHTML.replace(/\\"/g, '"'); }
  function og(p) { var m = document.querySelector('meta[property="og:' + p + '"]'); return m ? m.content : ''; }
  function cleanTitle(t) { return (t || '').replace(/\s*[—|].*$/, '').replace(/\s*Streaming.*$/i, '').trim(); }
  function decode(s) {
    return (s || '').replace(/&#x27;/g, "'").replace(/&#39;/g, "'").replace(/&amp;/g, '&')
      .replace(/&quot;/g, '"').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/\\u002F/gi, '/');
  }
  function escRe(s) { return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }
  function lightPoster(u) { return (u || '').replace('/original/', '/w342/').replace('/w1280/', '/w342/').replace('/w500/', '/w342/'); }
  function bigBackdrop(u) { return (u || '').replace('/w342/', '/w780/').replace('/original/', '/w780/'); }

  // items d'un flux RSC (home/catalogue) : src(tmdb) ... href(type/slug) ... children(titre)
  function parseItems(un) {
    var re = /"src":"(https:\/\/image\.tmdb\.org\/[^"]+)"[\s\S]{0,700}?"href":"\/(movie|tv)\/([^"\/]+)"[^}]*?"children":"([^"]*)"/g;
    var out = [], seen = {}, m;
    while ((m = re.exec(un))) {
      var type = m[2], slug = m[3], id = type + '/' + slug;
      if (seen[id]) continue; seen[id] = true;
      var p = lightPoster(m[1]);
      out.push({ type: type, id: id, title: decode(m[4]) || slug.replace(/^\d+-/, '').replace(/-/g, ' '), poster: p, banner: p });
    }
    return out;
  }

  // sources player (nmlnode) depuis un HTML/RSC déséchappé
  function parseSources(un) {
    var mk = '"sources":[{"type"';
    var i = un.indexOf(mk); if (i < 0) return [];
    var start = i + '"sources":'.length, depth = 0, end = -1;
    for (var k = start; k < un.length && k < start + 60000; k++) {
      if (un[k] === '[') depth++;
      else if (un[k] === ']') { depth--; if (depth === 0) { end = k + 1; break; } }
    }
    if (end < 0) return [];
    try { return JSON.parse(un.substring(start, end)); } catch (e) { return []; }
  }

  var P = {
    // ——— LISTINGS (WebView sur baseUrl → fetch) ———
    getHome: async function () {
      var all = [], seen = {};
      for (var pg = 1; pg <= 4; pg++) {
        try {
          var items = parseItems(await rsc('/catalogue?page=' + pg));
          for (var i = 0; i < items.length; i++) if (!seen[items[i].id]) { seen[items[i].id] = true; all.push(items[i]); }
        } catch (e) { break; }
      }
      var tv = all.filter(function (x) { return x.type === 'tv'; });
      var mv = all.filter(function (x) { return x.type === 'movie'; });
      var cats = [];
      if (all.length) cats.push({ name: 'Nouveautés', items: all.slice(0, 24) });
      if (tv.length) cats.push({ name: 'Séries', items: tv });
      if (mv.length) cats.push({ name: 'Films', items: mv });
      if (all.length > 24) cats.push({ name: 'À découvrir', items: all.slice(24) });
      return cats;
    },
    getMovies: async function (page) {
      return parseItems(await rsc('/catalogue?page=' + (page || 1))).filter(function (x) { return x.type === 'movie'; });
    },
    getTvShows: async function (page) {
      return parseItems(await rsc('/catalogue?page=' + (page || 1))).filter(function (x) { return x.type === 'tv'; });
    },
    search: async function (q, page) {
      if (!q) return [];
      var arr = await json('/api/search?q=' + encodeURIComponent(q));
      return (arr || []).map(function (x) {
        var type = ('' + x.mediaType).toUpperCase() === 'MOVIE' ? 'movie' : 'tv';
        var p = lightPoster(x.posterPath || '');
        return { type: type, id: type + '/' + x.slug, title: x.title, poster: p, banner: p, year: x.releaseYear ? String(x.releaseYear) : null };
      });
    },

    // ——— DÉTAIL (page DÉJÀ navigée → on lit la page chargée) ———
    getMovie: async function (id) {
      var poster = lightPoster(og('image'));
      return { type: 'movie', id: id, title: cleanTitle(og('title')) || id.replace(/^movie\//, '').replace(/^\d+-/, '').replace(/-/g, ' '),
        poster: poster, banner: bigBackdrop(og('image')), overview: decode(og('description')) };
    },

    getTvShow: async function (id) {
      var slug = id.replace(/^tv\//, '');
      var un = pageHtml();
      var re = new RegExp('/tv/' + escRe(slug) + '/(\\d+)/1', 'g');
      var nums = {}, m;
      while ((m = re.exec(un))) nums[parseInt(m[1])] = true;
      var seasons = Object.keys(nums).map(Number).sort(function (a, b) { return a - b; })
        .map(function (n) { return { id: id + '/' + n, number: n, title: 'Saison ' + n }; });
      if (seasons.length === 0) seasons.push({ id: id + '/1', number: 1, title: 'Saison 1' });
      var poster = lightPoster(og('image'));
      return { type: 'tv', id: id, title: cleanTitle(og('title')) || slug.replace(/^\d+-/, '').replace(/-/g, ' '),
        poster: poster, banner: bigBackdrop(og('image')), overview: decode(og('description')), seasons: seasons };
    },

    getEpisodesBySeason: async function (seasonId) {
      var parts = seasonId.split('/');
      var sn = parts[parts.length - 1];
      var slug = parts.slice(1, parts.length - 1).join('/');
      var un = pageHtml();
      var re = new RegExp('/tv/' + escRe(slug) + '/' + sn + '/(\\d+)', 'g');
      var nums = {}, m;
      while ((m = re.exec(un))) { var n = parseInt(m[1]); if (n > 0) nums[n] = true; }
      var sorted = Object.keys(nums).map(Number).sort(function (a, b) { return a - b; });
      return sorted.map(function (n) {
        var href = '/tv/' + slug + '/' + sn + '/' + n;
        var ci = un.indexOf('"href":"' + href + '"');
        var title = 'Épisode ' + n, poster = '';
        if (ci >= 0) {
          var chunk = un.substring(Math.max(0, ci - 500), ci);
          var im = chunk.match(/"src":"(https:\/\/image\.tmdb\.org\/[^"]+)","alt":"([^"]*)"/);
          if (im) { poster = lightPoster(im[1]); var t = decode(im[2]); if (t && !/^épisode/i.test(t)) title = t; }
        }
        return { id: seasonId + '/' + n, number: n, title: title, poster: poster };
      });
    },

    // page épisode/film DÉJÀ navigée → sources dans son innerHTML
    extractServers: async function () {
      var un = pageHtml();
      var sources = parseSources(un);
      if (sources.length === 0) { try { sources = parseSources(await rsc(location.pathname)); } catch (e) {} }
      var servers = [];
      for (var i = 0; i < sources.length; i++) {
        var g = sources[i];
        var host = (g.host || 'lecteur');
        var name = host.charAt(0).toUpperCase() + host.slice(1);
        if (g.sources && g.sources.length) {
          if (g.type === 'm3u8') {
            if (g.sources[0] && g.sources[0].source) servers.push({ name: name, url: g.sources[0].source });
          } else {
            var s = g.sources.slice().sort(function (a, b) { return (parseInt(b.label) || 0) - (parseInt(a.label) || 0); });
            for (var j = 0; j < s.length; j++) if (s[j].source) servers.push({ name: name + ' ' + (s[j].label || ''), url: s[j].source });
          }
        }
        if (g.iframe_url) servers.push({ name: name + ' (embed)', url: g.iframe_url });
      }
      return servers;
    },
  };

  window.__P = P;
})();
