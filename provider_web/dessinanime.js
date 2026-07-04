/*
 * dessinanime.js — Provider JS hébergé (moteur WebJsProvider).
 * Site : https://dessinanime.cc (Next.js + Cloudflare)
 * S'exécute DANS une WebView sur le site → fetch same-origin + DOM + cookies CF.
 *
 * v4 (2026-07-04) — FIX SAISONS/ÉPISODES + optimisation home :
 *   - getTvShow : LIT LE DOM LIVE (plus de fetch+DOMParser qui recevait un
 *     shell SPA 6KB sans données). WebJsProvider.kt navigate() a déjà chargé
 *     la page → on poll l'hydratation Next.js pour trouver les saisons.
 *   - getEpisodesBySeason : idem, DOM live + polling hydratation.
 *   - getHome : réduit de 7 à 4 fetches parallèles (/ + catalogue 1-3).
 *   - extractServers (v2 inchangé) : intercepte fetch/XHR pour capter
 *     extractor.nmlnode.cc/proxy/{hls,mp4}?token=... générées au clic des
 *     boutons hosts. Sans whitelist host.
 *
 * Contrat (window.__P) :
 *   getHome()                     -> [ {name, items:[item]} ]
 *   search(q, page)               -> [item]
 *   getMovies(page) / getTvShows(page) -> [item]
 *   getMovie(id) / getTvShow(id)  -> item (+ seasons pour tv)
 *   getEpisodesBySeason(seasonId)  -> [episode]
 *   extractServers()              -> [ {name, url} ]
 *   item = {type:'movie'|'tv', id:'movie/<slug>'|'tv/<slug>', title, poster, banner, year, rating, overview}
 *   season = {id:'<showId>/<n>', number, title, poster}
 *   episode= {id:'<showId>/<season>/<ep>', number, title, poster}
 */
(function () {
  const BASE = 'https://dessinanime.cc';

  async function getText(path) {
    const r = await fetch(path, { headers: { 'Accept': 'text/html,application/json' } });
    return await r.text();
  }
  // Fetch avec retry si CF challenge ("Just a moment..."). Attend que le cookie
  // cf_clearance soit posé par le Turnstile (traitement async en background).
  async function getTextSafe(path, maxRetries) {
    maxRetries = maxRetries || 4;
    for (var i = 0; i < maxRetries; i++) {
      var html = await getText(path);
      if (html.length > 8000 && !/Just a moment|checking your browser|challenge-platform|cf-browser-verification/i.test(html)) {
        return html;
      }
      console.log('[DA] fetch ' + path + ' got CF challenge (' + html.length + ' chars), retry ' + (i + 1) + '/' + maxRetries);
      await new Promise(function(r) { setTimeout(r, 3000); });
    }
    return await getText(path);
  }
  async function getJson(path) {
    const r = await fetch(path, { headers: { 'Accept': 'application/json' } });
    return await r.json();
  }
  // OPTIMISATION CRITIQUE : réduit taille images TMDB original → w342 (10× + léger).
  function optimizeImgUrl(url) {
    if (!url) return url;
    return url.replace('/t/p/original/', '/t/p/w342/');
  }

  function decode(s) {
    return (s || '')
      .replace(/&#x27;/g, "'").replace(/&#39;/g, "'").replace(/&amp;/g, '&')
      .replace(/&quot;/g, '"').replace(/&lt;/g, '<').replace(/&gt;/g, '>');
  }

  /**
   * Parse les cartes du HOME ou /catalogue. Structure réelle SSR de dessinanime.cc :
   *   <img alt="<title>" src="<poster>" ...>
   *   ... <a href="/(movie|tv)/<slug>" ...>Titre</a>
   * Rating + année ne sont PAS dans le SSR (= hydratés CSR), on les ignore.
   */
  function parseCards(html) {
    const re = /<img\b[^>]*\balt="([^"]*)"[^>]*\bsrc="([^"]+)"[^>]*>[\s\S]{0,1500}?<a[^>]+href="\/(movie|tv)\/([^"\/]+)"[^>]*>/g;
    const out = [];
    const seen = new Set();
    let m;
    while ((m = re.exec(html))) {
      const type = m[3];
      const slug = m[4];
      const id = type + '/' + slug;
      if (seen.has(id)) continue;
      seen.add(id);
      out.push({
        type: type,
        id: id,
        title: decode(m[1]),
        poster: optimizeImgUrl(m[2]),
        banner: optimizeImgUrl(m[2]),
      });
    }
    return out;
  }

  // og tags depuis le DOM live → title/poster/overview
  function parseDetailFromDOM(id) {
    const ogGet = function(p) {
      const el = document.querySelector('meta[property="og:' + p + '"]');
      return el ? (el.getAttribute('content') || '') : '';
    };
    let title = decode(ogGet('title')).replace(/\s*[—|].*$/, '').trim();
    const poster = ogGet('image');
    const overview = decode(ogGet('description'));
    return {
      type: id.indexOf('tv/') === 0 ? 'tv' : 'movie',
      id: id,
      title: title,
      poster: poster,
      banner: poster,
      overview: overview,
    };
  }

  // og tags d'une page détail (SSR string) → title/poster/overview/year
  function parseDetail(html, id) {
    const og = (p) => (html.match(new RegExp('og:' + p + '" content="([^"]*)"')) || [])[1] || '';
    let title = decode(og('title')).replace(/\s*[—|].*$/, '').trim();
    const poster = og('image');
    const overview = decode(og('description'));
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
      const type = (x.mediaType === 'TV') ? 'tv' : 'movie';
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

  // ─── Détection langue + clic bouton langue (VF/VOSTFR/VO) ──────────────
  function findLangBtn(lang) {
    const targets = lang === 'vf' ? ['VF', 'VF (1)', 'VF (2)', 'VF (3)', 'VF (4)']
      : lang === 'vostfr' ? ['VOSTFR']
      : lang === 'vo' ? ['VO'] : [];
    const btns = [...document.querySelectorAll('button')];
    for (const t of targets) {
      const b = btns.find(b => (b.textContent || '').trim() === t);
      if (b) return b;
    }
    return null;
  }
  async function clickLangIfNeeded(lang) {
    if (!lang) return;
    let btn = null;
    for (let i = 0; i < 16; i++) {
      btn = findLangBtn(lang);
      if (btn) break;
      await new Promise(r => setTimeout(r, 500));
    }
    if (btn) {
      try {
        ['pointerdown', 'mousedown', 'mouseup', 'click'].forEach(t => {
          btn.dispatchEvent(new MouseEvent(t, { bubbles: true, cancelable: true, view: window }));
        });
        await new Promise(r => setTimeout(r, 1500));
      } catch (e) {}
    }
  }
  async function detectAvailableLanguages() {
    for (let i = 0; i < 3; i++) {
      const btns = [...document.querySelectorAll('button')];
      const out = [];
      if (btns.some(b => /^VF($|\s*\()/.test((b.textContent || '').trim()))) out.push('vf');
      if (btns.some(b => /^VOSTFR$/.test((b.textContent || '').trim()))) out.push('vostfr');
      if (btns.some(b => /^VO$/.test((b.textContent || '').trim()))) out.push('vo');
      if (out.length > 0) return out;
      await new Promise(r => setTimeout(r, 500));
    }
    return [];
  }

  // ─── Heuristique boutons hosts (extractServers v2) ──────────────────────
  const NAV_NOISE = /Catalogue|Recherche|Connect|Partager|Open|Dismiss|Précédent|Suivant|Previous|Next|Proposer|Slide|Search|Share|Settings|Profile/i;
  function isHostBtn(b) {
    const t = (b.textContent || '').trim();
    if (t.length < 2 || t.length > 24) return false;
    if (NAV_NOISE.test(t)) return false;
    const par = b.parentElement;
    if (!par) return false;
    const siblings = [...par.children].filter(c => c.tagName === 'BUTTON');
    if (siblings.length < 2 || siblings.length > 20) return false;
    const cls = (par.className || '').toString();
    return /flex\s+flex-wrap/.test(cls) || /gap-2/.test(cls) || siblings.length >= 3;
  }
  function findHostBtns() {
    return [...document.querySelectorAll('button')].filter(isHostBtn);
  }

  const P = {
    /**
     * Home : fetch '/' + 3 pages catalogue en parallèle → split en catégories
     * disjointes. v4 = réduit de 7 à 4 fetches (+ rapide, même qualité).
     */
    async getHome() {
      const [home, c1, c2, c3] = await Promise.all([
        getText('/').catch(() => ''),
        getText('/catalogue?page=1').catch(() => ''),
        getText('/catalogue?page=2').catch(() => ''),
        getText('/catalogue?page=3').catch(() => ''),
      ]);
      const allItems = [];
      const seenIds = new Set();
      [parseCards(home), parseCards(c1), parseCards(c2), parseCards(c3)].forEach(arr => {
        arr.forEach(it => { if (!seenIds.has(it.id)) { seenIds.add(it.id); allItems.push(it); } });
      });
      const films = allItems.filter(i => i.type === 'movie');
      const tvs = allItems.filter(i => i.type === 'tv');

      // FEATURED = 8 premiers items du home (mix films+séries) avec banner.
      const featured = [];
      const featuredIds = new Set();
      for (const it of allItems) {
        if (featured.length >= 8) break;
        if (!it.poster) continue;
        featured.push(it);
        featuredIds.add(it.id);
      }

      const usedFilms = new Set();
      const usedShows = new Set();
      const pick = (src, used, max) => {
        const out = [];
        for (const it of src) {
          if (used.has(it.id)) continue;
          used.add(it.id);
          out.push(it);
          if (out.length >= max) break;
        }
        return out;
      };

      const topFilms = pick(films, usedFilms, 10);
      const classicFilms = pick(films, usedFilms, 10);
      const remainFilms = pick(films, usedFilms, 10);
      const topShows = pick(tvs, usedShows, 10);
      const classicShows = pick(tvs, usedShows, 10);
      const remainShows = pick(tvs, usedShows, 10);

      const usedAll = new Set([...usedFilms, ...usedShows]);
      const mixRemain = allItems.filter(it => !usedAll.has(it.id)).slice(0, 12);

      const MIN = 1;
      const cats = [];
      if (featured.length >= 3) cats.push({ name: 'À l\'affiche', items: featured });
      if (topFilms.length >= MIN) cats.push({ name: 'Films populaires', items: topFilms });
      if (topShows.length >= MIN) cats.push({ name: 'Séries populaires', items: topShows });
      if (classicFilms.length >= MIN) cats.push({ name: 'Films classiques', items: classicFilms });
      if (classicShows.length >= MIN) cats.push({ name: 'Séries classiques', items: classicShows });
      if (remainFilms.length >= MIN) cats.push({ name: 'Films', items: remainFilms });
      if (remainShows.length >= MIN) cats.push({ name: 'Séries', items: remainShows });
      if (mixRemain.length >= MIN) cats.push({ name: 'À découvrir', items: mixRemain });
      return cats;
    },

    async search(q, page) {
      const arr = await getJson('/api/search?q=' + encodeURIComponent(q));
      return mapSearch(arr);
    },

    async getMovies(page) {
      const BATCH = 20;
      const start = ((page || 1) - 1) * BATCH + 1;
      const htmls = await Promise.all(
        Array.from({ length: BATCH }, (_, i) => getText('/catalogue?page=' + (start + i)))
      );
      const items = [];
      const seen = new Set();
      htmls.forEach(html => {
        parseCards(html).filter(i => i.type === 'movie').forEach(it => {
          if (!seen.has(it.id)) { seen.add(it.id); items.push(it); }
        });
      });
      return items;
    },

    async getTvShows(page) {
      const BATCH = 20;
      const start = ((page || 1) - 1) * BATCH + 1;
      const htmls = await Promise.all(
        Array.from({ length: BATCH }, (_, i) => getText('/catalogue?page=' + (start + i)))
      );
      const items = [];
      const seen = new Set();
      htmls.forEach(html => {
        parseCards(html).filter(i => i.type === 'tv').forEach(it => {
          if (!seen.has(it.id)) { seen.add(it.id); items.push(it); }
        });
      });
      return items;
    },

    async getMovie(id) {
      const html = await getText('/' + id);
      return parseDetail(html, id);
    },

    /**
     * v4 — Détail série : LIT LE DOM LIVE (plus de fetch+DOMParser).
     * WebJsProvider.kt a DÉJÀ navigué vers /tv/<slug> → le DOM est rendu.
     * On poll l'hydratation Next.js pour trouver les liens de saison.
     * Le shell SSR (6KB) ne contient PAS les saisons → fetch était cassé.
     */
    async getTvShow(id, lang) {
      const slug = id.replace(/^tv\//, '');

      // Metadata depuis les og tags du DOM live (disponibles immédiatement dans le SSR)
      const base = parseDetailFromDOM(id);
      base.availableLanguages = [];

      // Regex pour matcher les liens de saison : /tv/{slug}/{N}/1
      const escSlug = slug.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      const seasonHrefRe = new RegExp('^\\/tv\\/' + escSlug + '\\/(\\d+)\\/1$');

      // Poll le DOM live pour les liens de saison (hydratation Next.js)
      var seasonNums = new Set();
      for (var attempt = 0; attempt < 20; attempt++) {
        var anchors = document.querySelectorAll('a[href]');
        for (var ai = 0; ai < anchors.length; ai++) {
          var href = anchors[ai].getAttribute('href') || '';
          var m = href.match(seasonHrefRe);
          if (m) seasonNums.add(parseInt(m[1], 10));
        }
        if (seasonNums.size > 0) break;
        await new Promise(function(r) { setTimeout(r, 500); });
      }
      console.log('[DA] getTvShow ' + id + ' → ' + seasonNums.size + ' seasons (DOM live, ' + attempt + ' polls)');

      // Construire la liste des saisons avec poster
      var sortedNums = [...seasonNums].sort(function(a, b) { return a - b; });
      var seasons = sortedNums.map(function(n) {
        var sPoster = base.poster;
        // Chercher le lien de cette saison pour récupérer son image
        var allLinks = document.querySelectorAll('a[href]');
        for (var li = 0; li < allLinks.length; li++) {
          var lm = (allLinks[li].getAttribute('href') || '').match(seasonHrefRe);
          if (lm && parseInt(lm[1], 10) === n) {
            var img = allLinks[li].querySelector('img');
            if (img) {
              sPoster = optimizeImgUrl(img.src || img.getAttribute('data-src')) || sPoster;
            }
            break;
          }
        }
        return {
          id: id + '/' + n,
          number: n,
          title: 'Saison ' + n,
          poster: sPoster,
        };
      });

      if (seasons.length === 0) {
        seasons.push({ id: id + '/1', number: 1, title: 'Saison 1', poster: base.poster });
      }
      base.seasons = seasons;
      return base;
    },

    /**
     * v4 — Liste les épisodes d'une saison via DOM LIVE.
     * WebJsProvider.kt a DÉJÀ navigué vers /tv/{slug}/{season}/1.
     * On poll l'hydratation Next.js pour trouver les liens d'épisode.
     * Structure DOM d'un épisode :
     *   <a href="/tv/{slug}/{season}/{ep}">
     *     <img alt="Titre épisode" src="...">
     *     <span>EP N</span>
     *     <p class="font-semibold">Titre (Nom Série)</p>
     *   </a>
     */
    async getEpisodesBySeason(seasonId, lang) {
      var parts = seasonId.split('/');
      if (parts.length < 3 || parts[0] !== 'tv') return [];
      var slug = parts[1];
      var seasonNum = parts[2];
      var escSlug = slug.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      var epHrefRe = new RegExp('^\\/tv\\/' + escSlug + '\\/' + seasonNum + '\\/(\\d+)$');

      // Poll le DOM live pour les liens d'épisode (hydratation Next.js)
      var seen = new Set();
      var out = [];
      for (var attempt = 0; attempt < 20; attempt++) {
        var anchors = document.querySelectorAll('a[href]');
        for (var ai = 0; ai < anchors.length; ai++) {
          var a = anchors[ai];
          var href = a.getAttribute('href') || '';
          var m = href.match(epHrefRe);
          if (!m) continue;
          var epNum = parseInt(m[1], 10);
          if (seen.has(epNum)) continue;
          seen.add(epNum);

          // Titre : préférer img.alt (propre, sans suffixe nom de série)
          var img = a.querySelector('img');
          var title = null;
          if (img && img.alt) {
            title = img.alt.trim();
          }
          if (!title) {
            // Fallback : texte du premier <p> (contient souvent "Titre (Série)")
            var p = a.querySelector('p');
            if (p) {
              title = p.textContent.trim().replace(/\s*\([^)]+\)\s*$/, '').trim();
            }
          }

          var poster = null;
          if (img) {
            poster = optimizeImgUrl(img.src || img.getAttribute('data-src')) || null;
          }

          out.push({
            id: 'tv/' + slug + '/' + seasonNum + '/' + epNum,
            number: epNum,
            title: title || ('Épisode ' + epNum),
            poster: poster,
          });
        }
        if (out.length > 0) break;
        await new Promise(function(r) { setTimeout(r, 500); });
      }
      console.log('[DA] getEpisodesBySeason ' + seasonId + ' → ' + out.length + ' episodes (DOM live, ' + attempt + ' polls)');
      out.sort(function(a, b) { return a.number - b.number; });
      return out;
    },

    /**
     * extractServers v2 — Intercepte fetch + XHR pour capter les URLs
     * extractor.nmlnode.cc/proxy/{hls,mp4,dash}?token=... générées au clic des
     * boutons hosts (hydrax/uqload/vidhide/sendvid/…).
     */
    async extractServers(lang) {
      if (lang) await clickLangIfNeeded(lang);
      const captured = [];
      const seenToken = new Set();
      function captureUrl(url) {
        if (!url || typeof url !== 'string') return;
        if (url.indexOf('extractor.nmlnode.cc/proxy/') < 0) return;
        const m = url.match(/\/proxy\/(hls|mp4|dash)\?token=([A-Za-z0-9_-]+)/);
        if (!m) return;
        if (seenToken.has(m[2])) return;
        seenToken.add(m[2]);
        captured.push('https://extractor.nmlnode.cc/proxy/' + m[1] + '?token=' + m[2]);
      }
      const origFetch = window.fetch;
      window.fetch = function (input, init) {
        try {
          const u = typeof input === 'string' ? input : (input && input.url);
          captureUrl(u);
        } catch (e) {}
        return origFetch.apply(this, arguments);
      };
      const origOpen = XMLHttpRequest.prototype.open;
      XMLHttpRequest.prototype.open = function (method, url) {
        try { captureUrl(url); } catch (e) {}
        return origOpen.apply(this, arguments);
      };

      // Attente boutons hosts (Next.js hydration jusqu'à 12s)
      let btns = [];
      for (let i = 0; i < 24; i++) {
        btns = findHostBtns();
        if (btns.length > 0) break;
        await new Promise(r => setTimeout(r, 500));
      }

      // Click + capture par bouton
      const counts = {};
      const out = [];
      for (const btn of btns) {
        const label = (btn.textContent || '').trim();
        counts[label] = (counts[label] || 0) + 1;
        const niceName = counts[label] > 1 ? (label + ' #' + counts[label]) : label;
        const beforeLen = captured.length;
        try { btn.scrollIntoView({ block: 'center' }); } catch (e) {}
        try {
          ['pointerdown', 'mousedown', 'mouseup', 'click'].forEach(function (t) {
            btn.dispatchEvent(new MouseEvent(t, { bubbles: true, cancelable: true, view: window }));
          });
        } catch (e) {}
        let url = null;
        for (let i = 0; i < 25; i++) {
          if (captured.length > beforeLen) { url = captured[captured.length - 1]; break; }
          const vid = document.querySelector('video');
          if (vid && vid.currentSrc && vid.currentSrc.indexOf('extractor.nmlnode.cc') >= 0) {
            captureUrl(vid.currentSrc);
            if (captured.length > beforeLen) { url = captured[captured.length - 1]; break; }
          }
          await new Promise(r => setTimeout(r, 200));
        }
        if (url) out.push({ name: niceName, url: url });
      }

      try { window.fetch = origFetch; } catch (e) {}
      try { XMLHttpRequest.prototype.open = origOpen; } catch (e) {}

      return out;
    },
  };

  window.__P = P;
})();
