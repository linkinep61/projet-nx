/*
 * wiflix.js — Provider JS hébergé (moteur WebJsProvider).
 * Site : https://flemmix.fast  (miroir Wiflix, DLE CMS, Cloudflare)
 * S'exécute DANS une WebView sur le site → fetch same-origin + DOM + cookies CF.
 *
 * v1 (2026-06-30) — Catalogue + détail + épisodes + serveurs natifs.
 *
 * Astuce hash `#` (le moteur ajoute "/1" à l'URL de nav pour getEpisodesBySeason) :
 *   - Film ID    : "film-en-streaming/36017-obsession-2026.html"        (type movie)
 *   - Série ID   : "serie-en-streaming/35858-from-....html"             (type tv)
 *   - Season ID  : "<seriePath>#blocfr" | "<seriePath>#blocvostfr"      (VF / VOSTFR)
 *   - Episode ID : "<seriePath>#<rel>"  (rel = attribut du <li>, ex "ep1vf")
 *   Le # est un fragment → le serveur charge la page show, le JS lit le hash.
 *
 * Contrat (window.__P) :
 *   getHome() -> [ {name, items:[item]} ]
 *   search(q,page) / getMovies(page) / getTvShows(page) -> [item]
 *   getMovie(id) / getTvShow(id) -> item (+ seasons pour tv)
 *   getEpisodesBySeason(seasonId) -> [episode]
 *   extractServers() -> [ {name, url} ]
 */
(function () {
  const BASE = 'https://flemmix.fast';

  async function getText(path) {
    // Timeout 12s (AbortController) → évite que getHome/listing hang 30s si le
    //   fetch se fait bloquer par un CF challenge.
    const ctrl = new AbortController();
    const t = setTimeout(function () { try { ctrl.abort(); } catch (e) {} }, 12000);
    try {
      const r = await fetch(path, { headers: { 'Accept': 'text/html' }, signal: ctrl.signal });
      return await r.text();
    } finally { clearTimeout(t); }
  }
  function parseHtml(html) {
    try { return new DOMParser().parseFromString(html, 'text/html'); }
    catch (e) { return document.implementation.createHTMLDocument(''); }
  }
  function decode(s) {
    return (s || '')
      .replace(/&#x27;/g, "'").replace(/&#039;/g, "'").replace(/&#39;/g, "'")
      .replace(/&amp;/g, '&').replace(/&quot;/g, '"')
      .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&nbsp;/g, ' ').trim();
  }
  function absUrl(u) {
    if (!u) return '';
    if (u.indexOf('http') === 0) return u;
    if (u.indexOf('//') === 0) return 'https:' + u;
    if (u.charAt(0) === '/') return BASE + u;
    return BASE + '/' + u;
  }
  // href absolu ou relatif → id relatif ("serie-en-streaming/35858-from.html")
  function hrefToId(href) {
    if (!href) return '';
    try {
      let p = href;
      if (p.indexOf('http') === 0) p = new URL(p).pathname;
      return p.replace(/^\//, '');
    } catch (e) { return ''; }
  }
  function typeOfHref(href) {
    return (href || '').indexOf('serie-en-streaming') >= 0 ? 'tv' : 'movie';
  }

  // Parse les cartes "div.mov" (home / listing / recherche).
  // Structure DLE Wiflix : div.mov > a.mov-t (href+titre) + img (poster).
  function parseMovCards(root) {
    const out = [];
    const seen = new Set();
    const movs = root.querySelectorAll('div.mov');
    movs.forEach(function (mov) {
      const a = mov.querySelector('a.mov-t') || mov.querySelector('a[href]');
      if (!a) return;
      const href = a.getAttribute('href') || '';
      const id = hrefToId(href);
      if (!id || seen.has(id)) return;
      // Ne garde que les fiches film/série
      if (id.indexOf('film-en-streaming') < 0 && id.indexOf('serie-en-streaming') < 0) return;
      seen.add(id);
      let title = decode(a.getAttribute('title') || a.textContent || '');
      // 2026-06-30 : on N'ÉMET PLUS le poster du site (checkimg.php derrière
      //   Cloudflare → 403 pour Glide + fait throttler l'IP). Les jaquettes
      //   viennent de TMDB côté app (tmdbPosters=true). Poster vide ici.
      out.push({ type: typeOfHref(href), id: id, title: title, poster: '', banner: '' });
    });
    return out;
  }

  // Détail (film ou série) : titre header.full-title h1, poster #posterimg,
  //   overview via ul.mov-list ou meta description.
  function parseDetail(doc, id, type) {
    let title = '';
    const h1 = doc.querySelector('header.full-title h1') || doc.querySelector('h1');
    if (h1) title = decode(h1.textContent || '');
    let poster = '';
    const posterImg = doc.querySelector('img#posterimg') || doc.querySelector('.full-poster img') || doc.querySelector('img');
    if (posterImg) poster = absUrl(posterImg.getAttribute('src') || posterImg.getAttribute('data-src') || '');
    let overview = '';
    const ogDesc = doc.querySelector('meta[property="og:description"], meta[name="description"]');
    if (ogDesc) overview = decode(ogDesc.getAttribute('content') || '');
    if (!overview) {
      const desc = doc.querySelector('.full-story, .fdesc, div[itemprop="description"]');
      if (desc) overview = decode(desc.textContent || '');
    }
    return { type: type, id: id, title: title, poster: poster, banner: poster, overview: overview };
  }

  // Parse les sections home d'un document (block-main) + construit la catégorie
  //   FEATURED "À l'affiche" (carrousel) à partir des 1ers items posterisés.
  // Le GRAND slider principal du site (owl-carousel, "À l'affiche" curé par flemmix).
  //   Item = a[href] > span.title1 (titre) + img. Les clones owl sont dédupliqués.
  function parseSliderItems(doc) {
    const out = [];
    const seen = new Set();
    const anchors = doc.querySelectorAll('.owl-carousel .item a[href], .owl-carousel a.item[href]');
    anchors.forEach(function (a) {
      const href = a.getAttribute('href') || '';
      const id = hrefToId(href);
      if (!id || seen.has(id)) return;
      if (id.indexOf('film-en-streaming') < 0 && id.indexOf('serie-en-streaming') < 0) return;
      seen.add(id);
      const t1 = a.querySelector('.title1, span.title1');
      let title = decode(t1 ? (t1.textContent || '') : (a.getAttribute('title') || ''));
      out.push({ type: typeOfHref(href), id: id, title: title, poster: '', banner: '' });
    });
    return out;
  }

  function parseHomeSections(doc) {
    const cats = [];
    const seenGlobal = new Set();
    // FEATURED = le grand slider principal flemmix (catégorie dédiée en tête).
    const slider = parseSliderItems(doc);
    if (slider.length >= 3) {
      cats.push({ name: "À l'affiche", items: slider.slice(0, 20) });
    }
    const blocks = doc.querySelectorAll('div.block-main');
    blocks.forEach(function (block) {
      const titleEl = block.querySelector('div.block-title, .block-title, h2');
      let name = titleEl ? decode(titleEl.textContent || '') : '';
      // collapse whitespace + retire "Voir la suite..." collé au titre
      name = name.replace(/\s+/g, ' ').replace(/\s*(voir la suite|voir tout|tout voir|»|\.\.\.).*$/i, '').trim();
      const items = parseMovCards(block).filter(function (it) {
        if (seenGlobal.has(it.id)) return false;
        seenGlobal.add(it.id); return true;
      });
      if (items.length > 0) cats.push({ name: name, items: items });
    });
    // Fallback : si pas de block-main ni slider, prend toutes les div.mov de la page
    if (cats.length === 0) {
      const items = parseMovCards(doc);
      if (items.length > 0) cats.push({ name: 'À la une', items: items });
    }
    return cats;
  }

  const P = {
    // HOME : fetch '/' (comportement d'origine qui marchait) → sections + slider.
    async getHome() {
      try {
        const cats = parseHomeSections(parseHtml(await getText('/')));
        if (cats.length > 0) return cats;
      } catch (e) {}
      // fallback : document courant si le fetch échoue
      return parseHomeSections(document);
    },

    // RECHERCHE : POST DLE /index.php?do=search (same-origin, cookie h_check=25).
    async search(q, page) {
      try { document.cookie = 'h_check=25; path=/'; } catch (e) {}
      if (!q || !String(q).trim()) {
        try {
          var gdoc = parseHtml(await getText('/'));
          var sbs = gdoc.querySelectorAll('div.side-b');
          var gblock = sbs[1] || sbs[0];
          var gout = [];
          if (gblock) gblock.querySelectorAll('ul li a').forEach(function (a) {
            var gh = a.getAttribute('href') || '';
            var gslug = gh.replace(/\/$/, '').split('/').pop();
            var gname = (a.textContent || '').trim();
            if (gslug && gname) gout.push({ type: 'genre', id: gslug, title: gname });
          });
          return gout;
        } catch (e) { return []; }
      }
      const p = (page || 1);
      const r = await fetch('/index.php?do=search&subaction=search&story=' + encodeURIComponent(q) + '&search_start=' + (p - 1) + '&full_search=0&result_from=' + ((p - 1) * 10 + 1), { headers: { 'Accept': 'text/html' } });
      const html = await r.text();
      return parseMovCards(parseHtml(html));
    },

    // FILMS : /film-en-streaming/ (page 1) puis /film-en-streaming/page/N/.
    async getMovies(page) {
      const p = page || 1;
      const path = p <= 1 ? '/film-en-streaming/' : '/film-en-streaming/page/' + p + '/';
      const doc = parseHtml(await getText(path));
      return parseMovCards(doc).filter(function (i) { return i.type === 'movie'; });
    },

    // SÉRIES : /serie-en-streaming/ (page 1) puis /serie-en-streaming/page/N/.
    async getGenre(id, page) {
      var p = page || 1;
      var year = new Date().getFullYear();
      if (id === '__releases_year__') {
        var yp = p <= 1 ? '/xfsearch/' + year + '/' : '/xfsearch/' + year + '/page/' + p + '/';
        try { return parseMovCards(parseHtml(await getText(yp))); } catch (e) { return []; }
      }
      if (id === '__recent__') {
        var fp = p <= 1 ? '/film-en-streaming/' : '/film-en-streaming/page/' + p + '/';
        var sp = p <= 1 ? '/serie-en-streaming/' : '/serie-en-streaming/page/' + p + '/';
        var films = [], series = [];
        try { films = parseMovCards(parseHtml(await getText(fp))); } catch (e) {}
        try { series = parseMovCards(parseHtml(await getText(sp))); } catch (e) {}
        var out = [], i = 0, j = 0;
        while (i < films.length || j < series.length) {
          if (i < films.length) out.push(films[i++]);
          if (j < series.length) out.push(series[j++]);
        }
        return out;
      }
      // Genre du SITE (slug barre laterale) -> films de ce genre.
      var gp = p <= 1 ? '/film-en-streaming/' + id + '/' : '/film-en-streaming/' + id + '/page/' + p + '/';
      try { return parseMovCards(parseHtml(await getText(gp))); } catch (e) { return []; }
    },
    async getTvShows(page) {
      const p = page || 1;
      const path = p <= 1 ? '/serie-en-streaming/' : '/serie-en-streaming/page/' + p + '/';
      const doc = parseHtml(await getText(path));
      return parseMovCards(doc).filter(function (i) { return i.type === 'tv'; });
    },

    // DÉTAIL FILM : le moteur ne navigue pas → fetch la page.
    async getMovie(id) {
      const doc = parseHtml(await getText('/' + id));
      return parseDetail(doc, id, 'movie');
    },

    // DÉTAIL SÉRIE : le moteur a NAVIGUÉ la WebView sur /<id> → on lit document.
    //   Saisons = blocs langue VF (div.blocfr) / VOSTFR (div.blocvostfr).
    async getTvShow(id, lang) {
      const base = parseDetail(document, id, 'tv');
      const seasons = [];
      if (document.querySelector('div.blocfr')) {
        seasons.push({ id: id + '#blocfr', number: seasons.length + 1, title: 'VF', poster: base.poster });
      }
      if (document.querySelector('div.blocvostfr')) {
        seasons.push({ id: id + '#blocvostfr', number: seasons.length + 1, title: 'VOSTFR', poster: base.poster });
      }
      if (seasons.length === 0) {
        seasons.push({ id: id + '#blocfr', number: 1, title: 'VF', poster: base.poster });
      }
      base.seasons = seasons;
      return base;
    },

    // ÉPISODES : le moteur a navigué sur /<seriePath>#blocXX/1 → on lit document.
    //   seasonId = "<seriePath>#blocfr" | "#blocvostfr". Épisodes = ul.eplist li.
    async getEpisodesBySeason(seasonId, lang) {
      const hashIdx = seasonId.indexOf('#');
      const seriePath = hashIdx >= 0 ? seasonId.slice(0, hashIdx) : seasonId;
      const bloc = hashIdx >= 0 ? seasonId.slice(hashIdx + 1) : 'blocfr'; // "blocfr"|"blocvostfr"
      const container = document.querySelector('div.' + bloc) || document;
      const lis = container.querySelectorAll('ul.eplist li');
      const out = [];
      let n = 0;
      lis.forEach(function (li) {
        n++;
        const rel = li.getAttribute('rel') || ('ep' + n + (bloc === 'blocvostfr' ? 'vostfr' : 'vf'));
        let title = decode(li.textContent || '');
        const numMatch = title.match(/(\d+)/);
        const num = numMatch ? parseInt(numMatch[1], 10) : n;
        if (!title || title.length > 60) title = 'Épisode ' + num;
        out.push({ id: seriePath + '#' + rel, number: num, title: title });
      });
      out.sort(function (a, b) { return a.number - b.number; });
      return out;
    },

    // SERVEURS : le moteur a navigué sur /<id> (film) ou /<seriePath>#<rel> (épisode).
    //   Épisode → div.<rel> a[onclick] ; Film → div.tabs-sel a[onclick].
    //   URL extraite du onclick="loadVideo('url')".
    async extractServers(lang) {
      const hash = location.hash ? location.hash.replace(/^#/, '') : '';
      let anchors;
      if (hash && /^ep/i.test(hash)) {
        anchors = document.querySelectorAll('div.' + hash + ' a, li[rel="' + hash + '"] a');
      } else {
        anchors = document.querySelectorAll('div.tabs-sel a, .tabs-sel a, div.eplinks a');
      }
      const out = [];
      const seen = new Set();
      let i = 0;
      anchors.forEach(function (a) {
        i++;
        const oc = a.getAttribute('onclick') || a.getAttribute('data-url') || '';
        let url = '';
        const m = oc.match(/loadVideo\(\s*['"]([^'"]+)['"]/i);
        if (m) url = m[1];
        else if (a.getAttribute('data-url')) url = a.getAttribute('data-url');
        else {
          const href = a.getAttribute('href') || '';
          if (href && href.indexOf('#') !== 0 && href.indexOf('javascript') !== 0) url = href;
        }
        url = absUrl(url);
        if (!url || seen.has(url)) return;
        seen.add(url);
        let name = decode(a.getAttribute('title') || a.textContent || '');
        if (!name || name.length > 24) name = 'Lecteur ' + i;
        out.push({ name: name, url: url });
      });
      return out;
    },
  };

  window.__P = P;
})();
