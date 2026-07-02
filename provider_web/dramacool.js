/* dramacool.js — provider WEB Dramacool (backup K-Dramas/asiatiques)
 * Site: dramacool9.com.ro (pas de Cloudflare dur). Backup-only : getHome/getMovies/
 * getTvShows/getGenre = [] (non browsable). Contrat window.__P classique.
 * Structure site (verifiee 2026-07-02) :
 *   search  : /?s=<q+>  -> a.mask (href=slug, title=attr, img=src, parent LI)
 *   detail  : /<slug>   -> h1 + #all-episodes a
 *   episode : /<slug>-episode-<N>.html -> button.server-btn[data-src] + iframe#video-frame
 */
(function () {
  var BASE = 'https://dramacool9.com.ro';

  async function getText(path) {
    var ctrl = new AbortController();
    var t = setTimeout(function () { try { ctrl.abort(); } catch (e) {} }, 12000);
    try {
      var r = await fetch(path, { headers: { 'Accept': 'text/html' }, signal: ctrl.signal });
      return await r.text();
    } finally { clearTimeout(t); }
  }
  function parseHtml(html) {
    try { return new DOMParser().parseFromString(html, 'text/html'); }
    catch (e) { return document.implementation.createHTMLDocument(''); }
  }
  function abs(u) { if (!u) return ''; if (u.indexOf('http') === 0) return u; return BASE + (u.charAt(0) === '/' ? u : '/' + u); }
  function relId(href) { return String(href || '').replace(/^https?:\/\/[^\/]+\//, '').replace(/^\//, '').replace(/\/$/, ''); }
  function imgUrl(el) {
    if (!el) return '';
    var img = el.tagName === 'IMG' ? el : el.querySelector('img');
    if (!img) return '';
    return abs(img.getAttribute('src') || img.getAttribute('data-src') || img.getAttribute('data-original') || '');
  }
  function cleanTitle(t) { return String(t || '').replace(/\s+Episode\s+\d+\s*$/i, '').trim(); }
  function hostShort(url) { try { return new URL(url).hostname.replace('www.', '').split('.')[0]; } catch (e) { return 'Server'; } }

  function parseSearchCards(doc) {
    var out = [];
    var masks = doc.querySelectorAll('a.mask');
    for (var i = 0; i < masks.length; i++) {
      var a = masks[i];
      var href = a.getAttribute('href');
      if (!href) continue;
      var p = a.parentElement || a;
      var title = a.getAttribute('title') || (p.querySelector('h3') ? p.querySelector('h3').textContent : '');
      title = cleanTitle(title);
      if (!title) continue;
      out.push({ type: 'tv', id: relId(href), title: title, poster: imgUrl(p) });
    }
    return out;
  }

  window.__P = {
    getHome: async function () { return []; },
    getMovies: async function () { return []; },
    getTvShows: async function () { return []; },
    getGenre: async function () { return []; },
    getPeople: async function () { return []; },

    search: async function (q, page) {
      if (!q || !String(q).trim()) return [];
      var doc = parseHtml(await getText('/?s=' + encodeURIComponent(String(q).replace(/\s+/g, '+'))));
      return parseSearchCards(doc);
    },

    getMovie: async function (id) {
      var doc = parseHtml(document.documentElement.outerHTML);
      var h1 = doc.querySelector('h1');
      return { type: 'movie', id: id, title: h1 ? h1.textContent.trim() : id.replace(/-/g, ' '),
        poster: imgUrl(doc.querySelector('article, .single-info, .image')) };
    },

    getTvShow: async function (id) {
      var doc = parseHtml(document.documentElement.outerHTML);
      var h1 = doc.querySelector('h1');
      var title = h1 ? h1.textContent.trim() : id.replace(/-/g, ' ');
      var poster = imgUrl(doc.querySelector('article, .single-info, .image'));
      var d = doc.querySelector('.desc-content, .single-info p, .description p, .info .content, .synopsis');
      var slug = String(id).replace(/#.*$/, '');
      return { type: 'tv', id: id, title: title, poster: poster, banner: poster,
        overview: d ? d.textContent.trim() : '',
        seasons: [{ id: slug + '#eps', number: 1, title: 'Saison 1' }] };
    },

    getEpisodesBySeason: async function (seasonId) {
      var doc = parseHtml(document.documentElement.outerHTML);
      var links = doc.querySelectorAll('#all-episodes a, #episode-list a, a[href*="-episode-"]');
      var eps = [], seen = {};
      for (var i = 0; i < links.length; i++) {
        var href = links[i].getAttribute('href') || '';
        var m = href.match(/-episode-(\d+)/i);
        if (!m) continue;
        var n = parseInt(m[1], 10);
        if (seen[n]) continue; seen[n] = 1;
        eps.push({ id: relId(href), number: n, title: 'Episode ' + n });
      }
      eps.sort(function (a, b) { return a.number - b.number; });
      return eps;
    },

    extractServers: async function () {
      var doc = parseHtml(document.documentElement.outerHTML);
      var out = [], seen = {};
      var btns = doc.querySelectorAll('button.server-btn[data-src], [data-src]');
      for (var i = 0; i < btns.length; i++) {
        var src = btns[i].getAttribute('data-src') || '';
        if (src.indexOf('http') !== 0 || seen[src]) continue; seen[src] = 1;
        var name = (btns[i].textContent || '').replace('▶', '').trim();
        out.push({ name: name || hostShort(src), url: src });
      }
      var ifr = doc.querySelectorAll('iframe[src]');
      for (var j = 0; j < ifr.length; j++) {
        var s = ifr[j].getAttribute('src') || '';
        if (s.indexOf('http') !== 0 || seen[s]) continue; seen[s] = 1;
        out.push({ name: hostShort(s), url: s });
      }
      return out;
    }
  };
})();