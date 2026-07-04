/*
 * dessinanime.js — Provider JS hébergé (moteur WebJsProvider).
 * Site : https://dessinanime.cc  (Next.js RSC + Cloudflare)
 * S'exécute DANS une WebView sur le site → fetch same-origin + DOM + cookies CF.
 *
 * 2026-07-04 : REFONTE SSR — tout extrait du HTML serveur-rendu (saisons,
 *   épisodes, serveurs). Zéro clic DOM, zéro polling. Les URLs extractor
 *   nmlnode sont directement jouables par ExoPlayer (court-circuit getVideo).
 *
 * Contrat (window.__P) :
 *   getHome() -> [ {name, items:[item]} ]
 *   search(q, page) -> [item]
 *   getMovies(page) / getTvShows(page) -> [item]
 *   getMovie(id) / getTvShow(id, lang) -> item (+ seasons pour tv)
 *   getEpisodesBySeason(seasonId, lang) -> [episode]
 *   extractServers(lang) -> [ {name, url} ]   (sur la page détail déjà navigée)
 *   item = {type:'movie'|'tv', id:'movie/<slug>'|'tv/<slug>', title, poster, banner, year, overview}
 */
(function () {
  var BASE = 'https://dessinanime.cc';

  function getText(path) {
    return fetch(path.indexOf('http') === 0 ? path : path, {
      headers: { 'Accept': 'text/html,application/json' }
    }).then(function (r) { return r.text(); });
  }
  function getJson(path) {
    return fetch(path, { headers: { 'Accept': 'application/json' } })
      .then(function (r) { return r.json(); });
  }
  function decode(s) {
    return (s || '')
      .replace(/&#x27;/g, "'").replace(/&#39;/g, "'").replace(/&amp;/g, '&')
      .replace(/&quot;/g, '"').replace(/&lt;/g, '<').replace(/&gt;/g, '>');
  }

  // Parse les cartes catalogue/home (href movie|tv/slug + img alt + src)
  function parseCards(html) {
    // Approche 1 : href AVANT img (ordre réel du HTML Next.js)
    var re = /href="\/(movie|tv)\/([^"\/]+)"[\s\S]{0,800}?<img\b[^>]*\balt="([^"]*)"[^>]*\bsrc="([^"]+)"/g;
    var out = [];
    var seen = {};
    var m;
    while ((m = re.exec(html))) {
      var id = m[1] + '/' + m[2];
      if (seen[id]) continue;
      seen[id] = true;
      out.push({
        type: m[1],
        id: id,
        title: decode(m[3]),
        poster: m[4],
        banner: m[4],
      });
    }
    // Fallback : ancien ordre img AVANT href (compat catalogue SSR)
    if (out.length === 0) {
      re = /<img\b[^>]*\balt="([^"]*)"[^>]*\bsrc="([^"]+)"[\s\S]{0,600}?href="\/(movie|tv)\/([^"\/]+)"/g;
      while ((m = re.exec(html))) {
        var id2 = m[3] + '/' + m[4];
        if (seen[id2]) continue;
        seen[id2] = true;
        out.push({
          type: m[3],
          id: id2,
          title: decode(m[1]),
          poster: m[2],
          banner: m[2],
        });
      }
    }
    return out;
  }

  // og tags d'une page détail (SSR) → title/poster/overview
  function parseDetail(html, id) {
    var og = function (p) {
      return (html.match(new RegExp('og:' + p + '"\\s*content="([^"]*)"')) || [])[1] || '';
    };
    var title = decode(og('title')).replace(/\s*[—|].*$/, '').trim();
    var poster = og('image');
    var overview = decode(og('description'));
    return {
      type: id.indexOf('tv/') === 0 ? 'tv' : 'movie',
      id: id,
      title: title,
      poster: poster,
      banner: poster,
      overview: overview,
    };
  }

  function mapSearch(arr) {
    return (arr || []).map(function (x) {
      var type = (x.mediaType === 'TV') ? 'tv' : 'movie';
      return {
        type: type,
        id: type + '/' + x.slug,
        title: x.title,
        poster: x.posterPath || '',
        banner: x.posterPath || '',
        year: x.releaseYear || null,
      };
    });
  }

  // Escape regex special chars in a string
  function escRe(s) { return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }

  // ———————— Extraction serveurs depuis le payload RSC ————————
  function parseSourcesFromHtml(html) {
    var titleMarker = '\\"title\\":\\"';
    var titleIdx = html.indexOf(titleMarker);
    if (titleIdx < 0) return [];

    var srcMarker = '\\"sources\\":[';
    var srcIdx = html.indexOf(srcMarker, titleIdx);
    if (srcIdx < 0) return [];

    var arrStart = srcIdx + srcMarker.length - 1;
    var depth = 0;
    var arrEnd = -1;
    for (var i = arrStart; i < html.length && i < arrStart + 50000; i++) {
      if (html[i] === '[') depth++;
      else if (html[i] === ']') {
        depth--;
        if (depth === 0) { arrEnd = i + 1; break; }
      }
    }
    if (arrEnd < 0) return [];

    var raw = html.substring(arrStart, arrEnd).replace(/\\"/g, '"');
    try { return JSON.parse(raw); } catch (e) { return []; }
  }

  // ———————— Provider API ————————
  var P = {
    getHome: async function () {
      var html = await getText('/catalogue?page=1');
      var items = parseCards(html);
      var movies = items.filter(function (i) { return i.type === 'movie'; });
      var tv = items.filter(function (i) { return i.type === 'tv'; });
      var cats = [];
      if (items.length) cats.push({ name: 'Nouveautés', items: items.slice(0, 24) });
      if (movies.length) cats.push({ name: 'Films', items: movies });
      if (tv.length) cats.push({ name: 'Séries', items: tv });
      return cats;
    },

    search: async function (q, page) {
      var arr = await getJson('/api/search?q=' + encodeURIComponent(q));
      return mapSearch(arr);
    },

    getMovies: async function (page) {
      var html = await getText('/catalogue?page=' + (page || 1));
      return parseCards(html).filter(function (i) { return i.type === 'movie'; });
    },

    getTvShows: async function (page) {
      var html = await getText('/catalogue?page=' + (page || 1));
      return parseCards(html).filter(function (i) { return i.type === 'tv'; });
    },

    getMovie: async function (id) {
      var html = await getText('/' + id);
      return parseDetail(html, id);
    },

    // ————— REFONTE 2026-07-04 : extraction saisons depuis le SSR —————
    getTvShow: async function (id) {
      var html = await getText('/' + id);
      var base = parseDetail(html, id);

      // Parse season links : href="/tv/<slug>/<N>/1"
      var slug = id.replace(/^tv\//, '');
      var re = new RegExp('href="/tv/' + escRe(slug) + '/(\\d+)/1"', 'g');
      var seasons = [];
      var seen = {};
      var m;
      while ((m = re.exec(html))) {
        var num = parseInt(m[1]);
        if (!seen[num]) {
          seen[num] = true;
          seasons.push({ id: id + '/' + num, number: num, title: 'Saison ' + num });
        }
      }
      seasons.sort(function (a, b) { return a.number - b.number; });
      // Fallback : au moins 1 saison
      if (seasons.length === 0) {
        seasons.push({ id: id + '/1', number: 1, title: 'Saison 1' });
      }
      base.seasons = seasons;
      return base;
    },

    // ————— REFONTE 2026-07-04 : extraction épisodes depuis le SSR —————
    getEpisodesBySeason: async function (seasonId) {
      // seasonId = "tv/<slug>/<season>"
      var html = await getText('/' + seasonId + '/1');

      var parts = seasonId.split('/');
      var slug = parts.slice(1, parts.length - 1).join('/');
      var seasonNum = parts[parts.length - 1];

      var escapedSlug = escRe(slug);
      var re = new RegExp(
        'href="(/tv/' + escapedSlug + '/' + seasonNum + '/(\\d+))"', 'g'
      );
      var epNums = {};
      var m;
      while ((m = re.exec(html))) {
        epNums[parseInt(m[2])] = true;
      }

      var sorted = Object.keys(epNums).map(Number).sort(function (a, b) { return a - b; });
      var episodes = [];

      for (var idx = 0; idx < sorted.length; idx++) {
        var num = sorted[idx];
        var epHref = '/tv/' + slug + '/' + seasonNum + '/' + num;
        var title = 'Episode ' + num;
        var poster = '';

        var cardIdx = html.indexOf('href="' + epHref + '"');
        if (cardIdx >= 0) {
          var chunk = html.substring(cardIdx, cardIdx + 800);
          var imgMatch = chunk.match(/<img\b[^>]*alt="([^"]*)"[^>]*src="([^"]+)"/);
          if (imgMatch) {
            title = decode(imgMatch[1]);
            poster = imgMatch[2];
          }
        }

        episodes.push({
          id: seasonId + '/' + num,
          number: num,
          title: title,
          poster: poster,
        });
      }

      return episodes;
    },

    // ————— REFONTE 2026-07-04 : extraction serveurs depuis le payload RSC —————
    extractServers: async function () {
      var html = await getText(window.location.pathname);
      var sources = parseSourcesFromHtml(html);

      if (sources.length === 0) {
        html = document.documentElement.innerHTML;
        sources = parseSourcesFromHtml(html);
      }

      var servers = [];
      for (var i = 0; i < sources.length; i++) {
        var group = sources[i];
        var host = group.host || 'lecteur';
        var hostName = host.charAt(0).toUpperCase() + host.slice(1);

        if (group.sources && group.sources.length > 0) {
          if (group.type === 'm3u8') {
            if (group.sources[0] && group.sources[0].source) {
              servers.push({ name: hostName, url: group.sources[0].source });
            }
          } else {
            var sorted = group.sources.slice().sort(function (a, b) {
              return (parseInt(b.label) || 0) - (parseInt(a.label) || 0);
            });
            for (var j = 0; j < sorted.length; j++) {
              if (sorted[j].source) {
                servers.push({
                  name: hostName + ' ' + sorted[j].label,
                  url: sorted[j].source,
                });
              }
            }
          }
        }

        if (group.iframe_url) {
          servers.push({ name: hostName + ' (embed)', url: group.iframe_url });
        }
      }

      return servers;
    },
  };

  window.__P = P;
})();
