/*
 * dessinanime.js — Provider JS hébergé (moteur WebJsProvider).
 * Site : https://dessinanime.cc  (Next.js + Cloudflare)
 * S'exécute DANS une WebView sur le site → fetch same-origin + DOM + cookies CF.
 *
 * v3 (2026-06-27) — Catalogue COMPLET (port de DessinAnimeProvider Kotlin) :
 *   - getHome : fetch '/' + split en 9 catégories disjointes (Films populaires,
 *     Séries populaires, classiques, etc.), 15 items/rail
 *   - getMovies / getTvShows : pagination réelle via /catalogue?page=N
 *   - getTvShow : parse les VRAIES saisons (href="/tv/slug/N/1")
 *   - getEpisodesBySeason : parse les VRAIS épisodes (/tv/slug/seasonN/1 + href="/tv/slug/seasonN/M")
 *   - extractServers (v2 inchangé) : intercepte fetch/XHR pour capter
 *     extractor.nmlnode.cc/proxy/{hls,mp4}?token=... générées au clic des
 *     boutons hosts. Sans whitelist host.
 *
 * Contrat (window.__P) :
 *   getHome() -> [ {name, items:[item]} ]
 *   search(q, page) -> [item]
 *   getMovies(page) / getTvShows(page) -> [item]
 *   getMovie(id) / getTvShow(id) -> item (+ seasons pour tv)
 *   getEpisodesBySeason(seasonId) -> [episode]
 *   extractServers() -> [ {name, url} ]
 *   item   = {type:'movie'|'tv', id:'movie/<slug>'|'tv/<slug>', title, poster, banner, year, rating, overview}
 *   season = {id:'<showId>/<n>', number, title, poster}
 *   episode= {id:'<showId>/<season>/<ep>', number, title, poster}
 */
(function () {
  const BASE = 'https://dessinanime.cc';

  async function getText(path) {
    const r = await fetch(path, { headers: { 'Accept': 'text/html,application/json' } });
    return await r.text();
  }
  async function getJson(path) {
    const r = await fetch(path, { headers: { 'Accept': 'application/json' } });
    return await r.json();
  }
  // OPTIMISATION CRITIQUE : réduit taille images TMDB original → w342 (10× + léger).
  // Avant : 1-1.7 MB / image → home charge 10-15s. Après : 50-100 KB / image → 1-2s.
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
   *   (l'<a> ne CONTIENT PAS l'<img> — l'img est AVANT l'<a> dans le DOM)
   * Le regex inversé (img → href) capture 48 cards sur le home (testé live).
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

  // og tags d'une page détail (SSR) → title/poster/overview/year
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
  // dessinanime.cc affiche en haut de la page film/série des boutons langue :
  // "Synopsis | VF | VOSTFR | VO | VF (4)". Le clic sur l'un change les sources
  // ET les saisons VF correspondantes. On filtre les <button> par texte exact.
  function findLangBtn(lang) {
    // lang = "vf" / "vostfr" / "vo"
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
    // Poll jusqu'à 8s pour attendre l'hydratation Next.js des boutons langue
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
        // Laisse le temps au site de switcher les sources
        await new Promise(r => setTimeout(r, 1500));
      } catch (e) {}
    }
  }
  // Détecte quelles langues sont disponibles (boutons VF/VOSTFR/VO visibles).
  // Polling COURT (1.5s max) → pas de blocage Mutex WebView. Si pas hydraté =
  // fallback empty (le Kotlin gérera "single lang").
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
     * Home : fetch '/' + 2 pages catalogue en parallèle → split en 9 catégories
     * disjointes (= chaque item dans UNE seule catégorie). Calque le natif Kotlin.
     */
    async getHome() {
      // STRATÉGIE 2026-06-28 v4.0 :
      //  - ✖ supprime parseCards(document.documentElement.outerHTML) — POLLUTION :
      //    quand le re-fetch tourne pendant que la WebView est sur une page
      //    série/film, le DOM hydraté ajoute les items de CETTE page → résultat
      //    instable (4 cats → 2 cats sur le 2e fetch). Stabilité > qté.
      //  - ✔ fetch /  +  catalogue page 1..6 (= STABLE quelle que soit la nav).
      //  - ✔ ajoute FEATURED ("À l'affiche") = carrousel en tête avec banner.
      //  - ✔ MIN = 1 (= aucune catégorie perdue, même si elle a 1 item).
      const [home, c1, c2, c3, c4, c5, c6] = await Promise.all([
        getText('/').catch(() => ''),
        getText('/catalogue?page=1').catch(() => ''),
        getText('/catalogue?page=2').catch(() => ''),
        getText('/catalogue?page=3').catch(() => ''),
        getText('/catalogue?page=4').catch(() => ''),
        getText('/catalogue?page=5').catch(() => ''),
        getText('/catalogue?page=6').catch(() => ''),
      ]);
      // Combine en gardant l'ordre (home en 1er = priorité), dédup par id
      // PAS de DOM hydraté → stabilité de l'output entre fetches successifs.
      const allItems = [];
      const seenIds = new Set();
      [parseCards(home), parseCards(c1), parseCards(c2), parseCards(c3),
       parseCards(c4), parseCards(c5), parseCards(c6)].forEach(arr => {
        arr.forEach(it => { if (!seenIds.has(it.id)) { seenIds.add(it.id); allItems.push(it); } });
      });
      const films = allItems.filter(i => i.type === 'movie');
      const tvs = allItems.filter(i => i.type === 'tv');

      // FEATURED = 8 premiers items du home (mix films+séries) avec banner.
      // Le code home Onyx sait afficher cette catégorie comme un carrousel.
      const featured = [];
      const featuredIds = new Set();
      for (const it of allItems) {
        if (featured.length >= 8) break;
        if (!it.poster) continue;
        featured.push(it);
        featuredIds.add(it.id);
      }

      // Pick disjoint : pas de rating/year en SSR → split séquentiel
      // (les 1ers items du HTML = les plus visibles = "populaires" implicite)
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

      // Films : 4 tranches disjointes (12 chacune = plus de catégories remplies)
      const topFilms = pick(films, usedFilms, 12);
      const classicFilms = pick(films, usedFilms, 12);
      const remainFilms1 = pick(films, usedFilms, 12);
      const remainFilms2 = pick(films, usedFilms, 12);

      // Séries : 4 tranches disjointes (12 chacune)
      const topShows = pick(tvs, usedShows, 12);
      const classicShows = pick(tvs, usedShows, 12);
      const remainShows1 = pick(tvs, usedShows, 12);
      const remainShows2 = pick(tvs, usedShows, 12);

      // À découvrir = items restants non utilisés (mix films+tv)
      const usedAll = new Set([...usedFilms, ...usedShows]);
      const mixRemain = allItems.filter(it => !usedAll.has(it.id)).slice(0, 15);

      // MIN abaissé à 1 → aucune catégorie ne disparaît.
      const MIN = 1;
      const cats = [];
      if (featured.length >= 3) cats.push({ name: 'À l\'affiche', items: featured });
      if (topFilms.length >= MIN) cats.push({ name: 'Films populaires', items: topFilms });
      if (topShows.length >= MIN) cats.push({ name: 'Séries populaires', items: topShows });
      if (classicFilms.length >= MIN) cats.push({ name: 'Films classiques', items: classicFilms });
      if (classicShows.length >= MIN) cats.push({ name: 'Séries classiques', items: classicShows });
      if (remainFilms1.length >= MIN) cats.push({ name: 'Films', items: remainFilms1 });
      if (remainShows1.length >= MIN) cats.push({ name: 'Séries', items: remainShows1 });
      if (remainFilms2.length >= MIN) cats.push({ name: 'Encore plus de films', items: remainFilms2 });
      if (remainShows2.length >= MIN) cats.push({ name: 'Encore plus de séries', items: remainShows2 });
      if (mixRemain.length >= MIN) cats.push({ name: 'À découvrir', items: mixRemain });
      return cats;
    },

    async search(q, page) {
      const arr = await getJson('/api/search?q=' + encodeURIComponent(q));
      return mapSearch(arr);
    },

    /**
     * Pagination Films/Séries : fetch 5 pages /catalogue consécutives en parallèle
     * (= ~25-35 items par page Onyx au lieu de ~5-7). Le user scrolle = page 2
     * = pages catalogue 6-10, etc. Plus de défilement fluide.
     */
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
     * Détail série : fetch() same-origin (cookies CF du warmup) + DOMParser.
     * PAS de navigation WebView → zéro nouveau challenge CF Turnstile.
     * v7 (2026-07-03) : remplace le polling DOM live par fetch + DOMParser.
     */
    async getTvShow(id, lang) {
      const slug = id.replace(/^tv\//, '');
      // Fetch la page de la série via same-origin (cookies CF passent)
      const html = await getText('/' + id);
      const base = parseDetail(html, id);
      base.availableLanguages = [];
      // Parse les saisons depuis le HTML fetchè (SSR = saisons déjà présentes)
      const doc = new DOMParser().parseFromString(html, 'text/html');
      const escSlug = slug.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      const seasonHrefRe = new RegExp('^\\/tv\\/' + escSlug + '\\/(\\d+)\\/1$');
      const seasonNums = new Set();
      for (const a of doc.querySelectorAll('a[href]')) {
        const m = (a.getAttribute('href') || '').match(seasonHrefRe);
        if (m) seasonNums.add(parseInt(m[1], 10));
      }
      // Pour chaque saison, cherche son image associée dans le HTML parsé
      const seasons = [...seasonNums].sort((a, b) => a - b).map(n => {
        const a = [...doc.querySelectorAll('a[href]')].find(x => {
          const m = (x.getAttribute('href') || '').match(seasonHrefRe);
          return m && +m[1] === n;
        });
        let poster = base.poster;
        if (a) {
          const img = a.querySelector('img') || a.parentElement?.querySelector('img');
          if (img) poster = img.src || img.getAttribute('data-src') || poster;
        }
        return {
          id: id + '/' + n,
          number: n,
          title: 'Saison ' + n,
          poster: poster,
        };
      });
      if (seasons.length === 0) {
        seasons.push({ id: id + '/1', number: 1, title: 'Saison 1', poster: base.poster });
      }
      base.seasons = seasons;
      return base;
    },

    /**
     * Liste les épisodes d'une saison via fetch() same-origin + DOMParser.
     * PAS de navigation WebView → zéro nouveau challenge CF Turnstile.
     * v7 (2026-07-03) : remplace le polling DOM live par fetch + DOMParser.
     * Le SSR Next.js contient TOUS les épisodes d'un coup (pas de polling nécessaire).
     */
    async getEpisodesBySeason(seasonId, lang) {
      const parts = seasonId.split('/');
      if (parts.length < 3 || parts[0] !== 'tv') return [];
      const slug = parts[1];
      const seasonNum = parts[2];
      const escSlug = slug.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      const epHrefRe = new RegExp('^\\/tv\\/' + escSlug + '\\/' + seasonNum + '\\/(\\d+)$');
      // Fetch la page saison via same-origin (cookies CF passent)
      const html = await getText('/tv/' + slug + '/' + seasonNum + '/1');
      const doc = new DOMParser().parseFromString(html, 'text/html');
      const seen = new Set();
      const out = [];
      for (const a of doc.querySelectorAll('a[href]')) {
        const m = (a.getAttribute('href') || '').match(epHrefRe);
        if (!m) continue;
        const epNum = parseInt(m[1], 10);
        if (seen.has(epNum)) continue;
        seen.add(epNum);
        // Image (vignette épisode) : <img> dans le bloc <a> ou parent
        const img = a.querySelector('img') || a.parentElement?.querySelector('img');
        let poster = img ? (img.src || img.getAttribute('data-src')) : null;
        // Titre : cherche un élément avec class title, sinon le texte du <a>
        let title = null;
        const titleEl = a.querySelector('[class*=title], h1, h2, h3, h4, h5') || a.parentElement?.querySelector('[class*=title], h1, h2, h3, h4, h5');
        if (titleEl) title = (titleEl.textContent || '').trim();
        if (!title) title = (a.textContent || '').trim();
        if (title && (title === ('Épisode ' + epNum) || title.length > 80)) title = null;
        out.push({
          id: 'tv/' + slug + '/' + seasonNum + '/' + epNum,
          number: epNum,
          title: title || ('Épisode ' + epNum),
          poster: poster || null,
        });
      }
      out.sort((a, b) => a.number - b.number);
      return out;
    },

    /**
     * extractServers v2 — Intercepte fetch + XHR pour capter les URLs
     * extractor.nmlnode.cc/proxy/{hls,mp4,dash}?token=... générées au clic des
     * boutons hosts (hydrax/uqload/vidhide/sendvid/…). Trouve les boutons hosts
     * dynamiquement sans whitelist.
     */
    async extractServers(lang) {
      // Si langue passée → click le bouton langue AVANT le scan des hosts.
      // Le site renouvelle les tokens nmlnode selon la langue choisie.
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
