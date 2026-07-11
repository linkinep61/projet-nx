/*
 * dessinanime.js — Provider JS hébergé (moteur WebJsProvider).
 * Site : https://dessinanime.cc  (Next.js + Cloudflare)
 * S'exécute DANS une WebView sur le site → fetch same-origin + DOM + cookies CF.
 *
 * v5 (2026-07-04) — Home = VRAIES catégories du site (DOM hydraté) :
 *   - getHome : parse les sections RÉELLES du home après hydratation Next.js
 *     (En Tendance, Nouveaux Films, Mieux Notés, Top Français, Nouveaux Épisodes).
 *     Carrousel + 5 rails. Fallback fetch si DOM pas hydraté.
 *   - getMovies : parcourt les 372 pages catalogue (batches de 10×5), filtre
 *     type=movie. Couvre l'intégralité du catalogue en ~8 pages de scroll.
 *   - getTvShows : idem mais type=tv (plus abondant → batch plus petit).
 *   - getTvShow / getEpisodesBySeason / extractServers : inchangés (v3).
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
  // OPTIMISATION : réduit taille images TMDB original → w342 (10× + léger).
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
   * Parse les cartes depuis le HTML brut (SSR) — utilisé par le fallback getHome,
   * getMovies, getTvShows. Regex inversé img→href.
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
    const ogD=(p)=>{const el=document.querySelector('meta[property="og:'+p+'"]')||document.querySelector('meta[name="og:'+p+'"]');return el?(el.getAttribute('content')||''):'';};const ogH=(p)=>(html&&(html.match(new RegExp('og:'+p+'" content="([^"]*)"'))||[])[1])||'';const og=(p)=>ogD(p)||ogH(p);
    let title = decode(og('title')).replace(/\s*[—|].*$/, '').trim(); if(!title){const h1=document.querySelector('h1'); title=h1?h1.textContent.trim():'';}
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

  // ─── DOM Section Parser (v5) ────────────────────────────────────────────
  // Parse les VRAIES sections du home hydraté Next.js.
  // Structure DOM :
  //   <div class="relative flex flex-col gap-2">   ← section container
  //     <header class="flex items-end ...">
  //       <p>EN TENDANCE</p>                        ← titre section ALL CAPS
  //     </header>
  //     <div> ... [role="group"] slides ...          ← items
  //   </div>
  // Chaque slide :
  //   <div role="group">
  //     <div><div><img alt="..." src="poster"></div>  ← poster
  //           <div><a href="/movie|tv/slug">Title</a></div></div>
  //   </div>

  /**
   * Trouve les sections du home par titre <p> ALL CAPS.
   * Retourne [{title, container}] pour chaque section demandée.
   */
  function findDomSections(wantedTitles) {
    const allPs = [...document.querySelectorAll('p')];
    const usedPs = new Set();
    const result = [];
    for (const wanted of wantedTitles) {
      const p = allPs.find(p => !usedPs.has(p) && p.textContent.trim() === wanted);
      if (!p) continue;
      usedPs.add(p);
      // Remonte au container de la section
      let container = null;
      if (p.parentElement && p.parentElement.tagName === 'HEADER') {
        container = p.parentElement.parentElement;
      } else if (p.parentElement && p.parentElement.tagName === 'SECTION') {
        container = p.parentElement;
      }
      if (!container) {
        let el = p.parentElement;
        for (let i = 0; i < 5; i++) {
          if (!el) break;
          const cards = el.querySelectorAll('a[href*="/movie/"], a[href*="/tv/"]');
          if (cards.length >= 2 && cards.length <= 30) { container = el; break; }
          el = el.parentElement;
        }
      }
      if (container) result.push({ title: wanted, container: container });
    }
    return result;
  }

  /**
   * Extrait les items d'un container de section.
   * Cherche les slides [role="group"] (carousel) OU les <a> directs (grid).
   */
  function extractSectionItems(container, seenIds) {
    const items = [];
    // Stratégie 1 : slides carousel [role="group"]
    const slides = container.querySelectorAll('[role="group"]');
    if (slides.length >= 2) {
      slides.forEach(function (slide) {
        const a = slide.querySelector('a[href*="/movie/"], a[href*="/tv/"]');
        if (!a) return;
        const href = a.getAttribute('href') || '';
        const m = href.match(/^\/(movie|tv)\/([^\/]+)/);
        if (!m) return;
        const id = m[1] + '/' + m[2];
        if (seenIds.has(id)) return;
        seenIds.add(id);
        const img = slide.querySelector('img');
        const poster = img ? (img.src || img.getAttribute('data-src') || '') : '';
        const title = img ? (img.alt || '') : (a.textContent || '').trim();
        items.push({
          type: m[1],
          id: id,
          title: decode(title),
          poster: optimizeImgUrl(poster),
          banner: optimizeImgUrl(poster),
        });
      });
      return items;
    }
    // Stratégie 2 : liens <a> directs (sections grid)
    const links = container.querySelectorAll('a[href*="/movie/"], a[href*="/tv/"]');
    links.forEach(function (a) {
      const href = a.getAttribute('href') || '';
      const m = href.match(/^\/(movie|tv)\/([^\/]+)/);
      if (!m) return;
      const id = m[1] + '/' + m[2];
      if (seenIds.has(id)) return;
      seenIds.add(id);
      // Cherche l'image dans le parent (card)
      const card = a.closest('[role="group"]') || a.closest('div.group') || a.parentElement?.parentElement;
      const img = card ? card.querySelector('img') : null;
      const poster = img ? (img.src || img.getAttribute('data-src') || '') : '';
      const title = img ? (img.alt || '') : (a.textContent || '').trim();
      items.push({
        type: m[1],
        id: id,
        title: decode(title),
        poster: optimizeImgUrl(poster),
        banner: optimizeImgUrl(poster),
      });
    });
    return items;
  }

  const P = {
    /**
     * Home v5 : VRAIES catégories du site depuis le DOM hydraté.
     * Sections parsées (dans l'ordre) :
     *   1. EN TENDANCE → carrousel "À l'affiche"
     *   2. NOUVEAUX AJOUTS (FILMS) → rail films
     *   3. MIEUX NOTES → rail films
     *   4. TOP FRANCAIS → rail séries
     *   5. NOUVEAUX EPISODES → rail séries
     * Fallback : fetch SSR + split artificiel si DOM pas hydraté.
     */
    async getHome() {
      // Attendre l'hydratation Next.js (sections ALL CAPS dans des <p>)
      const WANTED = [
        'EN TENDANCE',
        'NOUVEAUX AJOUTS (FILMS)',
        'MIEUX NOTES',
        'TOP FRANCAIS',
        'NOUVEAUX EPISODES'
      ];
      let domSections = [];
      for (let i = 0; i < 20; i++) { // 10s max
        domSections = findDomSections(WANTED);
        if (domSections.length >= 3) break;
        await new Promise(function (r) { setTimeout(r, 500); });
      }

      // ── DOM hydraté : parse les vraies sections ──
      if (domSections.length >= 3) {
        const cats = [];
        const seenIds = new Set();
        const displayNames = {
          'EN TENDANCE': 'À l\'affiche',
          'NOUVEAUX AJOUTS (FILMS)': 'Nouveaux Films',
          'MIEUX NOTES': 'Mieux Notés',
          'TOP FRANCAIS': 'Top Français',
          'NOUVEAUX EPISODES': 'Nouveaux Épisodes',
        };
        for (const sec of domSections) {
          const items = extractSectionItems(sec.container, seenIds);
          if (items.length < 1) continue;
          cats.push({ name: displayNames[sec.title] || sec.title, items: items });
        }
        // Si ≥3 catégories, on a un bon home
        if (cats.length >= 3) return cats;
      }

      // ── Fallback : fetch SSR (si hydratation échouée / WebView pas sur home) ──
      var pages = await Promise.all([
        getText('/').catch(function () { return ''; }),
        getText('/catalogue?page=1').catch(function () { return ''; }),
        getText('/catalogue?page=2').catch(function () { return ''; }),
        getText('/catalogue?page=3').catch(function () { return ''; }),
        getText('/catalogue?page=4').catch(function () { return ''; }),
        getText('/catalogue?page=5').catch(function () { return ''; }),
        getText('/catalogue?page=6').catch(function () { return ''; }),
      ]);
      var allItems = [];
      var seenFb = new Set();
      pages.forEach(function (html) {
        parseCards(html).forEach(function (it) {
          if (!seenFb.has(it.id)) { seenFb.add(it.id); allItems.push(it); }
        });
      });
      var films = allItems.filter(function (i) { return i.type === 'movie'; });
      var tvs = allItems.filter(function (i) { return i.type === 'tv'; });
      var featured = allItems.slice(0, 8).filter(function (it) { return !!it.poster; });
      var fbCats = [];
      if (featured.length >= 3) fbCats.push({ name: 'À l\'affiche', items: featured });
      if (films.length >= 1) fbCats.push({ name: 'Nouveaux Films', items: films.slice(0, 15) });
      if (tvs.length >= 1) fbCats.push({ name: 'Top Séries', items: tvs.slice(0, 15) });
      var remainMix = allItems.filter(function (it) { return !featured.includes(it); }).slice(0, 15);
      if (remainMix.length >= 1) fbCats.push({ name: 'À découvrir', items: remainMix });
      return fbCats;
    },

    async search(q, page) {
      const arr = await getJson('/api/search?q=' + encodeURIComponent(q));
      return mapSearch(arr);
    },

    /**
     * Pagination Films : parcourt les pages catalogue par batches de 10,
     * jusqu'à 5 batches (= 50 pages catalogue par page app).
     * Avec ~1.2 films/page catalogue, ça donne ~60 films par page app.
     * Les 372 pages catalogue sont couvertes en ~8 pages de scroll.
     */
    async getMovies(page) {
      var BATCH = 10;       // pages en parallèle par batch
      var MAX_BATCHES = 5;  // batches max avant de rendre la main
      var MIN_ITEMS = 20;   // objectif minimum de films
      var start = ((page || 1) - 1) * BATCH * MAX_BATCHES + 1;
      var items = [];
      var seen = new Set();

      for (var b = 0; b < MAX_BATCHES; b++) {
        var batchStart = start + b * BATCH;
        var htmls = await Promise.all(
          Array.from({ length: BATCH }, function (_, i) {
            return getText('/catalogue?page=' + (batchStart + i)).catch(function () { return ''; });
          })
        );
        htmls.forEach(function (html) {
          parseCards(html).filter(function (i) { return i.type === 'movie'; }).forEach(function (it) {
            if (!seen.has(it.id)) { seen.add(it.id); items.push(it); }
          });
        });
        // Stop tôt si on a assez de films
        if (items.length >= MIN_ITEMS) break;
      }
      return items;
    },

    async getTvShows(page) {
      var BATCH = 10;
      var MAX_BATCHES = 3;  // TV shows plus abondants → 30 pages suffisent
      var start = ((page || 1) - 1) * BATCH * MAX_BATCHES + 1;
      var items = [];
      var seen = new Set();

      for (var b = 0; b < MAX_BATCHES; b++) {
        var batchStart = start + b * BATCH;
        var htmls = await Promise.all(
          Array.from({ length: BATCH }, function (_, i) {
            return getText('/catalogue?page=' + (batchStart + i)).catch(function () { return ''; });
          })
        );
        htmls.forEach(function (html) {
          parseCards(html).filter(function (i) { return i.type === 'tv'; }).forEach(function (it) {
            if (!seen.has(it.id)) { seen.add(it.id); items.push(it); }
          });
        });
        if (items.length >= 20) break;
      }
      return items;
    },

    async getMovie(id) {
      return parseDetail(document.documentElement.outerHTML, id);
    },

    /**
     * Détail série : og tags du SSR + saisons depuis le DOM hydraté.
     */
    async getTvShow(id, lang) {
      const slug = id.replace(/^tv\//, '');
      const base = parseDetail(document.documentElement.outerHTML, id);
      if (lang) await clickLangIfNeeded(lang);
      base.availableLanguages = [];
      const escSlug = slug.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      const seasonHrefRe = new RegExp('^\\/tv\\/' + escSlug + '\\/(\\d+)\\/1$');
      let seasonNums = new Set();
      for (let i = 0; i < 24; i++) {
        const links = [...document.querySelectorAll('a[href]')];
        seasonNums = new Set();
        for (const a of links) {
          const m = (a.getAttribute('href') || '').match(seasonHrefRe);
          if (m) seasonNums.add(parseInt(m[1], 10));
        }
        if (seasonNums.size > 0) break;
        await new Promise(r => setTimeout(r, 500));
      }
      const seasons = [...seasonNums].sort((a, b) => a - b).map(n => {
        const a = [...document.querySelectorAll('a[href]')].find(x => {
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
     * Liste les épisodes d'une saison via le DOM hydraté Next.js.
     */
    async getEpisodesBySeason(seasonId, lang) {
      const parts = seasonId.split('/');
      if (parts.length < 3 || parts[0] !== 'tv') return [];
      const slug = parts[1];
      const seasonNum = parts[2];
      const escSlug = slug.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      const epHrefRe = new RegExp('^\\/tv\\/' + escSlug + '\\/' + seasonNum + '\\/(\\d+)$');
      if (lang) await clickLangIfNeeded(lang);
      let bestCount = 0;
      let stableCycles = 0;
      for (let i = 0; i < 24; i++) {
        const links = [...document.querySelectorAll('a[href]')];
        const found = new Set();
        for (const a of links) {
          const m = (a.getAttribute('href') || '').match(epHrefRe);
          if (m) found.add(parseInt(m[1], 10));
        }
        if (found.size === bestCount) {
          stableCycles++;
          if (stableCycles >= 2 && bestCount >= 5) break;
        } else {
          bestCount = found.size;
          stableCycles = 0;
        }
        await new Promise(r => setTimeout(r, 500));
      }
      const links = [...document.querySelectorAll('a[href]')];
      const seen = new Set();
      const out = [];
      for (const a of links) {
        const m = (a.getAttribute('href') || '').match(epHrefRe);
        if (!m) continue;
        const epNum = parseInt(m[1], 10);
        if (seen.has(epNum)) continue;
        seen.add(epNum);
        const img = a.querySelector('img') || a.parentElement?.querySelector('img');
        let poster = img ? (img.src || img.getAttribute('data-src')) : null;
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
     * extractor.nmlnode.cc/proxy/{hls,mp4,dash}?token=...
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

      let btns = [];
      for (let i = 0; i < 24; i++) {
        btns = findHostBtns();
        if (btns.length > 0) break;
        await new Promise(r => setTimeout(r, 500));
      }

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
