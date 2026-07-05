/*
 * dessinanime.js — Provider WebJS hébergé (moteur WebJsProvider).
 * Site : https://dessinanime.cc  (Next.js App Router / RSC + Cloudflare).
 * v3 2026-07-04 : home NON-REDONDANT (Séries/Films disjoints, plusieurs pages) +
 *   DÉTAIL robuste (union page chargée innerHTML + fetch RSC + retry → saisons/épisodes
 *   fiables même si l'hydratation TV est tardive). Recherche via API JSON /api/search.
 *
 * Contrat window.__P : getHome, search, getMovies, getTvShows, getMovie, getTvShow,
 *   getEpisodesBySeason, extractServers. item = {type,id:'movie/<slug>'|'tv/<slug>',title,poster,banner,year,overview}
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
  function delay(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }

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
  function chunk(arr, size, names) {
    var out = [];
    for (var i = 0, n = 0; i < arr.length; i += size, n++) {
      out.push({ name: names[n] || (names[0] + ' ' + (n + 1)), items: arr.slice(i, i + size) });
    }
    return out;
  }

  var P = {
    // ——— HOME : Séries + Films DISJOINTS, plusieurs pages, découpés en rails ———
    getHome: async function () {
      var all = [], seen = {};
      for (var pg = 1; pg <= 6; pg++) {
        try {
          var items = parseItems(await rsc('/catalogue?page=' + pg));
          if (!items.length) break;
          for (var i = 0; i < items.length; i++) if (!seen[items[i].id]) { seen[items[i].id] = true; all.push(items[i]); }
        } catch (e) { break; }
      }
      var tv = all.filter(function (x) { return x.type === 'tv'; });
      var mv = all.filter(function (x) { return x.type === 'movie'; });
      var cats = [];
      // rails de 14, noms variés, ZÉRO doublon (un item n'est que dans Séries OU Films)
      cats = cats.concat(chunk(tv, 14, ['Séries', 'Séries — suite', 'Encore plus de séries', 'Séries à découvrir']));
      cats = cats.concat(chunk(mv, 14, ['Films', 'Films — suite', 'Encore plus de films', 'Films à découvrir']));
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

    getMovie: async function (id) {
      return { type: 'movie', id: id, title: cleanTitle(og('title')) || id.replace(/^movie\//, '').replace(/^\d+-/, '').replace(/-/g, ' '),
        poster: lightPoster(og('image')), banner: bigBackdrop(og('image')), overview: decode(og('description')) };
    },

    // ——— SAISONS robustes : union innerHTML + fetch RSC + retry ———
    getTvShow: async function (id) {
      var slug = id.replace(/^tv\//, '');
      var nums = {};
      var collect = function (un) {
        var re = new RegExp('/tv/' + escRe(slug) + '/(\\d+)/1', 'g'), m;
        while ((m = re.exec(un))) { var n = parseInt(m[1]); if (n > 0) nums[n] = true; }
      };
      collect(pageHtml());                                   // page déjà navigée
      try { collect(await rsc('/tv/' + slug)); } catch (e) {} // + fetch RSC (source serveur)
      if (Object.keys(nums).length === 0) { await delay(700); collect(pageHtml()); } // retry hydratation
      var seasons = Object.keys(nums).map(Number).sort(function (a, b) { return a - b; })
        .map(function (n) { return { id: id + '/' + n, number: n, title: 'Saison ' + n }; });
      if (seasons.length === 0) seasons.push({ id: id + '/1', number: 1, title: 'Saison 1' });
      return { type: 'tv', id: id, title: cleanTitle(og('title')) || slug.replace(/^\d+-/, '').replace(/-/g, ' '),
        poster: lightPoster(og('image')), banner: bigBackdrop(og('image')), overview: decode(og('description')), seasons: seasons };
    },

    // ——— ÉPISODES robustes : union innerHTML + fetch RSC + retry ———
    getEpisodesBySeason: async function (seasonId) {
      var parts = seasonId.split('/');
      var sn = parts[parts.length - 1];
      var slug = parts.slice(1, parts.length - 1).join('/');
      var nums = {}, chunks = [];
      var collect = function (un) {
        chunks.push(un);
        var re = new RegExp('/tv/' + escRe(slug) + '/' + sn + '/(\\d+)', 'g'), m;
        while ((m = re.exec(un))) { var n = parseInt(m[1]); if (n > 0) nums[n] = true; }
      };
      collect(pageHtml());
      try { collect(await rsc('/tv/' + slug + '/' + sn + '/1')); } catch (e) {}
      if (Object.keys(nums).length <= 1) { await delay(700); collect(pageHtml()); }
      var sorted = Object.keys(nums).map(Number).sort(function (a, b) { return a - b; });
      var big = chunks.join('');
      return sorted.map(function (n) {
        var href = '/tv/' + slug + '/' + sn + '/' + n;
        var ci = big.indexOf('"href":"' + href + '"');
        var title = 'Épisode ' + n, poster = '';
        if (ci >= 0) {
          var c = big.substring(Math.max(0, ci - 500), ci);
          var im = c.match(/"src":"(https:\/\/image\.tmdb\.org\/[^"]+)","alt":"([^"]*)"/);
          if (im) { poster = lightPoster(im[1]); var t = decode(im[2]); if (t && !/^épisode/i.test(t)) title = t; }
        }
        return { id: seasonId + '/' + n, number: n, title: title, poster: poster };
      });
    },

    extractServers: async function () {
      var sources = parseSources(pageHtml());
      if (sources.length === 0) { try { sources = parseSources(await rsc(location.pathname)); } catch (e) {} }
      if (sources.length === 0) { await delay(700); sources = parseSources(pageHtml()); }
      var servers = [];
      for (var i = 0; i < sources.length; i++) {
        var g = sources[i], host = (g.host || 'lecteur');
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
