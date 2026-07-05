/*
 * dessinanime.js — Provider WebJS hébergé (moteur WebJsProvider).
 * Site : https://dessinanime.cc  (Next.js App Router / RSC + Cloudflare).
 *
 * RÉÉCRITURE COMPLÈTE 2026-07-04 : s'exécute dans une WebView CHAUDE sur le site
 *   (clearance CF déjà posée) → les fetch same-origin sont FIABLES (200), contrairement
 *   au WebView transitoire natif qui racait navigation+fetch et prenait des 403.
 *
 * Sources de données confirmées :
 *   - Recherche : /api/search?q=<q>  → JSON propre [{id,slug,title,releaseYear,
 *       voteAverage,posterPath,mediaType:"MOVIE"|"TV"}]
 *   - Tout le reste (home, catalogue, fiche, saisons, épisodes, sources player) : flux
 *       RSC (header "RSC:1") server-rendu, léger et fiable. On le déséchappe puis on parse.
 *   - Poster/backdrop = URLs image.tmdb.org DIRECTES présentes dans le RSC.
 *   - Sources player = tableau "sources":[{type,host,sources:[{label,source}],iframe_url}]
 *       (extractor.nmlnode.cc, jouable direct par ExoPlayer).
 *
 * Contrat (window.__P) — cf moteur WebJsProvider.kt :
 *   getHome() -> [ {name, items:[item]} ]
 *   search(q, page) -> [item]
 *   getMovies(page) / getTvShows(page) -> [item]
 *   getMovie(id) / getTvShow(id) -> item (+ seasons pour tv)
 *   getEpisodesBySeason(seasonId) -> [episode]
 *   extractServers() -> [ {name, url} ]   (sur la page détail déjà navigée)
 *   item = {type:'movie'|'tv', id:'movie/<slug>'|'tv/<slug>', title, poster, banner, year, overview}
 */
(function () {
  var BASE = 'https://dessinanime.cc';

  // ————— fetch helpers —————
  function rsc(path) {
    return fetch(path, { headers: { 'RSC': '1' }, credentials: 'include' })
      .then(function (r) { return r.text(); })
      .then(function (t) { return t.replace(/\\"/g, '"'); }); // déséchappe le RSC une fois
  }
  function json(path) {
    return fetch(path, { headers: { 'Accept': 'application/json' }, credentials: 'include' })
      .then(function (r) { return r.json(); });
  }
  function decode(s) {
    return (s || '')
      .replace(/&#x27;/g, "'").replace(/&#39;/g, "'").replace(/&amp;/g, '&')
      .replace(/&quot;/g, '"').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
      .replace(/\\u002F/gi, '/');
  }
  function escRe(s) { return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }
  // Poster plus léger pour la TV (original → w342)
  function lightPoster(u) {
    return (u || '').replace('/original/', '/w342/').replace('/w1280/', '/w342/').replace('/w500/', '/w342/');
  }
  function bigBackdrop(u) {
    return (u || '').replace('/w342/', '/w780/').replace('/original/', '/w780/');
  }

  // ————— parse des items d'un flux RSC (home/catalogue) —————
  //   Ordre RSC : "src":"<tmdb>" ... "href":"/type/slug" ... "children":"<titre>"
  function parseItems(un) {
    var re = /"src":"(https:\/\/image\.tmdb\.org\/[^"]+)"[\s\S]{0,700}?"href":"\/(movie|tv)\/([^"\/]+)"[^}]*?"children":"([^"]*)"/g;
    var out = [], seen = {}, m;
    while ((m = re.exec(un))) {
      var type = m[2], slug = m[3];
      var id = type + '/' + slug;
      if (seen[id]) continue; seen[id] = true;
      var poster = lightPoster(m[1]);
      out.push({
        type: type, id: id,
        title: decode(m[4]) || slug.replace(/^\d+-/, '').replace(/-/g, ' '),
        poster: poster, banner: poster,
      });
    }
    return out;
  }

  // ————— détail (fiche) depuis le RSC —————
  //   1er couple {src tmdb, alt titre} = affiche/backdrop du titre.
  function parseDetail(un, id) {
    var type = id.indexOf('tv/') === 0 ? 'tv' : 'movie';
    var img = un.match(/"src":"(https:\/\/image\.tmdb\.org\/[^"]+)","alt":"([^"]*)"/);
    var poster = img ? img[1] : '';
    var title = img ? decode(img[2]) : '';
    if (!title) {
      var slug = id.replace(/^(movie|tv)\//, '');
      title = slug.replace(/^\d+-/, '').replace(/-/g, ' ');
      title = title.charAt(0).toUpperCase() + title.slice(1);
    }
    // overview : cherchée dans le RSC si présente (clé "overview":"...")
    var ov = un.match(/"overview":"([^"]{20,})"/);
    return {
      type: type, id: id, title: title,
      poster: lightPoster(poster), banner: bigBackdrop(poster),
      overview: ov ? decode(ov[1]) : '',
    };
  }

  // ————— sources player (nmlnode) depuis le RSC déséchappé —————
  function parseSources(un) {
    var mk = '"sources":[{"type"';
    var i = un.indexOf(mk);
    if (i < 0) return [];
    var start = i + '"sources":'.length; // pointe sur '['
    var depth = 0, end = -1;
    for (var k = start; k < un.length && k < start + 60000; k++) {
      if (un[k] === '[') depth++;
      else if (un[k] === ']') { depth--; if (depth === 0) { end = k + 1; break; } }
    }
    if (end < 0) return [];
    try { return JSON.parse(un.substring(start, end)); } catch (e) { return []; }
  }

  // ————— Provider API —————
  var P = {
    getHome: async function () {
      var items = parseItems(await rsc('/catalogue?page=1'));
      var items2 = [];
      try { items2 = parseItems(await rsc('/catalogue?page=2')); } catch (e) {}
      var all = items.concat(items2);
      var seen = {}, uniq = [];
      for (var i = 0; i < all.length; i++) { if (!seen[all[i].id]) { seen[all[i].id] = true; uniq.push(all[i]); } }
      var tv = uniq.filter(function (x) { return x.type === 'tv'; });
      var mv = uniq.filter(function (x) { return x.type === 'movie'; });
      var cats = [];
      if (uniq.length) cats.push({ name: 'Nouveautés', items: uniq.slice(0, 24) });
      if (tv.length) cats.push({ name: 'Séries', items: tv });
      if (mv.length) cats.push({ name: 'Films', items: mv });
      return cats;
    },

    getMovies: async function (page) {
      return parseItems(await rsc('/catalogue?page=' + (page || 1)))
        .filter(function (x) { return x.type === 'movie'; });
    },
    getTvShows: async function (page) {
      return parseItems(await rsc('/catalogue?page=' + (page || 1)))
        .filter(function (x) { return x.type === 'tv'; });
    },

    search: async function (q, page) {
      if (!q) return [];
      var arr = await json('/api/search?q=' + encodeURIComponent(q));
      return (arr || []).map(function (x) {
        var type = ('' + x.mediaType).toUpperCase() === 'MOVIE' ? 'movie' : 'tv';
        var poster = lightPoster(x.posterPath || '');
        return {
          type: type, id: type + '/' + x.slug, title: x.title,
          poster: poster, banner: poster,
          year: x.releaseYear ? String(x.releaseYear) : null,
        };
      });
    },

    getMovie: async function (id) {
      return parseDetail(await rsc('/' + id), id);
    },

    getTvShow: async function (id) {
      var un = await rsc('/' + id);
      var base = parseDetail(un, id);
      var slug = id.replace(/^tv\//, '');
      var re = new RegExp('/tv/' + escRe(slug) + '/(\\d+)/1', 'g');
      var nums = {}, m;
      while ((m = re.exec(un))) nums[parseInt(m[1])] = true;
      var seasons = Object.keys(nums).map(Number).sort(function (a, b) { return a - b; })
        .map(function (n) { return { id: id + '/' + n, number: n, title: 'Saison ' + n }; });
      if (seasons.length === 0) seasons.push({ id: id + '/1', number: 1, title: 'Saison 1' });
      base.seasons = seasons;
      return base;
    },

    getEpisodesBySeason: async function (seasonId) {
      // seasonId = "tv/<slug>/<season>"
      var parts = seasonId.split('/');
      var seasonNum = parts[parts.length - 1];
      var slug = parts.slice(1, parts.length - 1).join('/');
      var un = await rsc('/tv/' + slug + '/' + seasonNum + '/1');
      var re = new RegExp('/tv/' + escRe(slug) + '/' + seasonNum + '/(\\d+)', 'g');
      var nums = {}, m;
      while ((m = re.exec(un))) { var n = parseInt(m[1]); if (n > 0) nums[n] = true; }
      var sorted = Object.keys(nums).map(Number).sort(function (a, b) { return a - b; });
      return sorted.map(function (n) {
        // titre/poster de l'épisode : img {src,alt} proche du lien épisode
        var href = '/tv/' + slug + '/' + seasonNum + '/' + n;
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

    // La WebView est déjà navigée sur la page épisode/film → on lit son RSC.
    extractServers: async function () {
      var un = await rsc(location.pathname);
      var sources = parseSources(un);
      if (sources.length === 0) sources = parseSources(document.documentElement.innerHTML.replace(/\\"/g, '"'));
      var servers = [];
      for (var i = 0; i < sources.length; i++) {
        var g = sources[i];
        var host = (g.host || 'lecteur');
        var name = host.charAt(0).toUpperCase() + host.slice(1);
        if (g.sources && g.sources.length) {
          if (g.type === 'm3u8') {
            if (g.sources[0] && g.sources[0].source) servers.push({ name: name, url: g.sources[0].source });
          } else {
            var sorted = g.sources.slice().sort(function (a, b) { return (parseInt(b.label) || 0) - (parseInt(a.label) || 0); });
            for (var j = 0; j < sorted.length; j++) {
              if (sorted[j].source) servers.push({ name: name + ' ' + (sorted[j].label || ''), url: sorted[j].source });
            }
          }
        }
        if (g.iframe_url) servers.push({ name: name + ' (embed)', url: g.iframe_url });
      }
      return servers;
    },
  };

  window.__P = P;
})();
