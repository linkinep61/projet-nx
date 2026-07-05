/*
 * dessinanime.js — Provider WebJS hébergé. v11 2026-07-04 (Hydrax restaure - fix codec cote player).
 * Site : https://dessinanime.cc (Next.js RSC + Cloudflare).
 * Le moteur navigue la WebView sur la page détail ET résout le challenge Turnstile CF
 * (waitForRealContent) → on lit la page RÉELLEMENT chargée (innerHTML + meta og).
 * Home/catalogue = fetch RSC (CF autorise /catalogue) ; recherche = /api/search JSON.
 */
(function () {
  var BASE = 'https://dessinanime.cc';
  function rsc(path) { return fetch(path, { headers: { 'RSC': '1' }, credentials: 'include' }).then(function (r) { return r.text(); }).then(function (t) { return t.replace(/\\"/g, '"'); }); }
  function json(path) { return fetch(path, { headers: { 'Accept': 'application/json' }, credentials: 'include' }).then(function (r) { return r.json(); }); }
  function pageHtml() { return document.documentElement.innerHTML.replace(/\\"/g, '"'); }
  function og(p) { var m = document.querySelector('meta[property="og:' + p + '"]'); return m ? m.content : ''; }
  function cleanTitle(t) { return (t || '').replace(/\s*[—|].*$/, '').replace(/\s*Streaming.*$/i, '').trim(); }
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
  function parseSources(un) {
    var mk = '"sources":[{"type"'; var i = un.indexOf(mk); if (i < 0) return [];
    var start = i + '"sources":'.length, depth = 0, end = -1;
    for (var k = start; k < un.length && k < start + 60000; k++) { if (un[k] === '[') depth++; else if (un[k] === ']') { depth--; if (depth === 0) { end = k + 1; break; } } }
    if (end < 0) return []; try { return JSON.parse(un.substring(start, end)); } catch (e) { return []; }
  }
  function chunk(arr, size, names) { var out = []; for (var i = 0, n = 0; i < arr.length; i += size, n++) out.push({ name: names[n] || (names[0] + ' ' + (n + 1)), items: arr.slice(i, i + size) }); return out; }

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

    // DÉTAIL : la WebView est navigée sur la page (challenge résolu par le moteur) → on lit la page.
    getMovie: async function (id) {
      return { type: 'movie', id: id, title: cleanTitle(og('title')) || id.replace(/^movie\//, '').replace(/^\d+-/, '').replace(/-/g, ' '),
        poster: lightPoster(og('image')), banner: bigBackdrop(og('image')), overview: decode(og('description')) };
    },
    getTvShow: async function (id) {
      var slug = id.replace(/^tv\//, ''), nums = {};
      var collect = function (un) { var re = new RegExp('/tv/' + escRe(slug) + '/(\\d+)/1', 'g'), m; while ((m = re.exec(un))) { var n = parseInt(m[1]); if (n > 0) nums[n] = true; } };
      for (var att = 0; att < 5; att++) { collect(pageHtml()); if (Object.keys(nums).length >= 1) break; await delay(500); }
      var _sp = lightPoster(og('image'));
      var seasons = Object.keys(nums).map(Number).sort(function (a, b) { return a - b; }).map(function (n) { return { id: id + '/' + n, number: n, title: 'Saison ' + n, poster: _sp }; });
      if (seasons.length === 0) seasons.push({ id: id + '/1', number: 1, title: 'Saison 1', poster: _sp });
      return { type: 'tv', id: id, title: cleanTitle(og('title')) || slug.replace(/^\d+-/, '').replace(/-/g, ' '),
        poster: lightPoster(og('image')), banner: bigBackdrop(og('image')), overview: decode(og('description')), seasons: seasons };
    },
    getEpisodesBySeason: async function (seasonId) {
      var parts = seasonId.split('/'), sn = parts[parts.length - 1], slug = parts.slice(1, parts.length - 1).join('/');
      var nums = {};
      var collect = function (un) { var re = new RegExp('/tv/' + escRe(slug) + '/' + sn + '/(\\d+)', 'g'), m; while ((m = re.exec(un))) { var n = parseInt(m[1]); if (n > 0) nums[n] = true; } };
      for (var att = 0; att < 5; att++) { collect(pageHtml()); if (Object.keys(nums).length >= 1) break; await delay(500); }
      var sorted = Object.keys(nums).map(Number).sort(function (a, b) { return a - b; });
      var un = pageHtml();
      return sorted.map(function (n) {
        var href = '/tv/' + slug + '/' + sn + '/' + n, ci = un.indexOf('"href":"' + href + '"'), title = 'Épisode ' + n, poster = '';
        if (ci >= 0) { var c = un.substring(ci, ci + 1000); var im = c.match(/"src":"(https:\/\/image\.tmdb\.org\/[^"]+)","alt":"([^"]*)"/); if (im) { poster = lightPoster(im[1]); var t = decode(im[2]); if (t && !/^épisode/i.test(t)) title = t; } }
        return { id: seasonId + '/' + n, number: n, title: title, poster: poster };
      });
    },
    extractServers: async function () {
      var sources = [];
      for (var att = 0; att < 5 && sources.length === 0; att++) { sources = parseSources(pageHtml()); if (sources.length === 0) await delay(500); }
      var servers = [];
      for (var i = 0; i < sources.length; i++) {
        var g = sources[i], host = (g.host || 'lecteur'), name = host.charAt(0).toUpperCase() + host.slice(1);
        if (g.sources && g.sources.length) {
          if (g.type === 'm3u8') { if (g.sources[0] && g.sources[0].source) servers.push({ name: name + ' ' + (g.sources[0].label || ''), url: g.sources[0].source }); }
          else { var s = g.sources.slice().sort(function (a, b) { return (parseInt(b.label) || 0) - (parseInt(a.label) || 0); }); for (var j = 0; j < s.length; j++) if (s[j].source) servers.push({ name: name + ' ' + (s[j].label || ''), url: s[j].source }); }
        }
        if (g.iframe_url) servers.push({ name: name + ' (embed)', url: g.iframe_url });
      }
      return servers;
    },
  };
  window.__P = P;
})();
