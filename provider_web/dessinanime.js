/*
 * dessinanime.js — Provider WebJS hébergé (moteur WebJsProvider). v6 2026-07-04.
 * Site : https://dessinanime.cc (Next.js RSC + Cloudflare).
 * detailViaFetch=true côté moteur : le WebView RESTE sur baseUrl (contexte validé CF), et le
 * JS récupère TOUTES les données par fetch RSC (naviguer vers une page détail déclenche un
 * challenge CF sur TV → 403). Home/catalogue = RSC ; recherche = /api/search JSON.
 */
(function () {
  var BASE = 'https://dessinanime.cc';
  function rsc(path) { return fetch(path, { headers: { 'RSC': '1' }, credentials: 'include' }).then(function (r) { return r.text(); }).then(function (t) { return t.replace(/\\"/g, '"'); }); }
  function json(path) { return fetch(path, { headers: { 'Accept': 'application/json' }, credentials: 'include' }).then(function (r) { return r.json(); }); }
  function decode(s) { return (s || '').replace(/&#x27;/g, "'").replace(/&#39;/g, "'").replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/\\u002F/gi, '/'); }
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
  function firstImg(un) { var m = un.match(/"src":"(https:\/\/image\.tmdb\.org\/[^"]+)","alt":"([^"]*)"/); return m ? { src: m[1], alt: decode(m[2]) } : null; }
  function parseSources(un) {
    var mk = '"sources":[{"type"'; var i = un.indexOf(mk); if (i < 0) return [];
    var start = i + '"sources":'.length, depth = 0, end = -1;
    for (var k = start; k < un.length && k < start + 60000; k++) { if (un[k] === '[') depth++; else if (un[k] === ']') { depth--; if (depth === 0) { end = k + 1; break; } } }
    if (end < 0) return []; try { return JSON.parse(un.substring(start, end)); } catch (e) { return []; }
  }
  function chunk(arr, size, names) { var out = []; for (var i = 0, n = 0; i < arr.length; i += size, n++) out.push({ name: names[n] || (names[0] + ' ' + (n + 1)), items: arr.slice(i, i + size) }); return out; }
  // fetch RSC robuste (retry si 403/vide) — CF peut throttler ponctuellement
  async function rscRetry(path, tries) {
    var last = '';
    for (var i = 0; i < (tries || 4); i++) {
      try { last = await rsc(path); if (last && last.length > 12000 && last.indexOf('Just a moment') < 0) return last; } catch (e) {}
      await delay(500);
    }
    return last;
  }

  var P = {
    getHome: async function () {
      var all = [], seen = {};
      for (var pg = 1; pg <= 8; pg++) {
        try { var items = parseItems(await rsc('/catalogue?page=' + pg)); if (!items.length) break;
          for (var i = 0; i < items.length; i++) if (!seen[items[i].id]) { seen[items[i].id] = true; all.push(items[i]); }
        } catch (e) { break; }
      }
      var tv = all.filter(function (x) { return x.type === 'tv'; });
      var mv = all.filter(function (x) { return x.type === 'movie'; });
      var cats = [];
      if (all.length) cats.push({ name: "À l'affiche", items: all.slice(0, 6).map(function (x) { return { type: x.type, id: x.id, title: x.title, poster: x.poster, banner: bigBackdrop(x.poster) }; }) });
      cats = cats.concat(chunk(tv, 16, ['Séries', 'Séries — suite', 'Encore plus de séries', 'Séries à découvrir', 'Sélection séries', 'Séries populaires']));
      cats = cats.concat(chunk(mv, 16, ['Films', 'Films — suite', 'Encore plus de films']));
      return cats;
    },
    getMovies: async function (page) { return parseItems(await rsc('/catalogue?page=' + (page || 1))).filter(function (x) { return x.type === 'movie'; }); },
    getTvShows: async function (page) { return parseItems(await rsc('/catalogue?page=' + (page || 1))).filter(function (x) { return x.type === 'tv'; }); },
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
      var slug = id.replace(/^movie\//, '');
      var un = await rscRetry('/movie/' + slug);
      var img = firstImg(un);
      var ov = un.match(/"overview":"([^"]{20,})"/);
      return { type: 'movie', id: id, title: (img && img.alt) || slug.replace(/^\d+-/, '').replace(/-/g, ' '),
        poster: img ? lightPoster(img.src) : '', banner: img ? bigBackdrop(img.src) : '', overview: ov ? decode(ov[1]) : '' };
    },

    getTvShow: async function (id) {
      var slug = id.replace(/^tv\//, '');
      var un = await rscRetry('/tv/' + slug);
      var nums = {}, re = new RegExp('/tv/' + escRe(slug) + '/(\\d+)/1', 'g'), m;
      while ((m = re.exec(un))) { var n = parseInt(m[1]); if (n > 0) nums[n] = true; }
      var seasons = Object.keys(nums).map(Number).sort(function (a, b) { return a - b; }).map(function (k) { return { id: id + '/' + k, number: k, title: 'Saison ' + k }; });
      if (seasons.length === 0) seasons.push({ id: id + '/1', number: 1, title: 'Saison 1' });
      var img = firstImg(un);
      var ov = un.match(/"overview":"([^"]{20,})"/);
      return { type: 'tv', id: id, title: (img && img.alt) || slug.replace(/^\d+-/, '').replace(/-/g, ' '),
        poster: img ? lightPoster(img.src) : '', banner: img ? bigBackdrop(img.src) : '', overview: ov ? decode(ov[1]) : '', seasons: seasons };
    },

    getEpisodesBySeason: async function (seasonId) {
      var parts = seasonId.split('/'), sn = parts[parts.length - 1], slug = parts.slice(1, parts.length - 1).join('/');
      var un = await rscRetry('/tv/' + slug + '/' + sn + '/1');
      var nums = {}, re = new RegExp('/tv/' + escRe(slug) + '/' + sn + '/(\\d+)', 'g'), m;
      while ((m = re.exec(un))) { var n = parseInt(m[1]); if (n > 0) nums[n] = true; }
      var sorted = Object.keys(nums).map(Number).sort(function (a, b) { return a - b; });
      return sorted.map(function (n) {
        var href = '/tv/' + slug + '/' + sn + '/' + n, ci = un.indexOf('"href":"' + href + '"'), title = 'Épisode ' + n, poster = '';
        if (ci >= 0) { var c = un.substring(Math.max(0, ci - 500), ci); var im = firstImg(c); if (im) { poster = lightPoster(im.src); if (im.alt && !/^épisode/i.test(im.alt)) title = im.alt; } }
        return { id: seasonId + '/' + n, number: n, title: title, poster: poster };
      });
    },

    // path = "/tv/<slug>/<season>/<episode>" ou "/movie/<slug>" (passé par le moteur)
    extractServers: async function (path) {
      var un = await rscRetry(path || location.pathname);
      var sources = parseSources(un);
      var servers = [];
      for (var i = 0; i < sources.length; i++) {
        var g = sources[i], host = (g.host || 'lecteur'), name = host.charAt(0).toUpperCase() + host.slice(1);
        if (g.sources && g.sources.length) {
          if (g.type === 'm3u8') { if (g.sources[0] && g.sources[0].source) servers.push({ name: name, url: g.sources[0].source }); }
          else { var s = g.sources.slice().sort(function (a, b) { return (parseInt(b.label) || 0) - (parseInt(a.label) || 0); }); for (var j = 0; j < s.length; j++) if (s[j].source) servers.push({ name: name + ' ' + (s[j].label || ''), url: s[j].source }); }
        }
        if (g.iframe_url) servers.push({ name: name + ' (embed)', url: g.iframe_url });
      }
      return servers;
    },
  };
  window.__P = P;
})();
