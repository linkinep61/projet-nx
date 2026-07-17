/**
 * Streamflix Community API — Cloudflare Worker + D1
 * Remplace Firebase Firestore pour les notes, votes langue et sync.
 *
 * Endpoints:
 *   POST /rating/get        {contentKey}
 *   POST /rating/vote       {contentKey, deviceId, rating, title}
 *   POST /lang/get          {contentKey}
 *   POST /lang/vote         {contentKey, deviceId, lang}
 *   POST /lang/remove       {contentKey, deviceId}
 *   POST /sync/send         {payload}  → {code}
 *   POST /sync/receive      {code}     → {payload}
 *
 *   ── CF BYPASS (QR relay TV ↔ téléphone via D1) ──
 *   POST /cf-bypass/create       {slug}              → {token, pageUrl}
 *   GET  /cf-bypass/<token>                           → HTML page (phone)
 *   POST /cf-bypass/submit       {token, m3u8Url}    → {ok}
 *   POST /cf-bypass/poll         {token}             → {status, m3u8Url?}
 *   POST /cf-bypass/auto-resolve {token, slug}       → {resolved, m3u8Url?}
 */

export default {
  async fetch(request, env) {
    // CORS
    if (request.method === 'OPTIONS') {
      return new Response(null, {
        headers: {
          'Access-Control-Allow-Origin': '*',
          'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
          'Access-Control-Allow-Headers': 'Content-Type',
        }
      });
    }

    const url = new URL(request.url);
    const path = url.pathname;

    // ── GET: CF bypass page (QR scanned by phone) ──────────────
    if (request.method === 'GET' && path.startsWith('/cf-bypass/')) {
      const token = path.slice('/cf-bypass/'.length);
      if (token && token.length > 8) {
        return await cfBypassPage(env.DB, token, url.origin);
      }
      return json({ error: 'Missing token' }, 400);
    }

    if (request.method !== 'POST') {
      return json({ error: 'POST only' }, 405);
    }

    try {
      const body = await request.json();
      const db = env.DB;

      switch (path) {
        // ── RATINGS ──────────────────────────────────────────
        case '/rating/get':
          return await ratingGet(db, body);
        case '/rating/vote':
          return await ratingVote(db, body);
        case '/rating/import':
          return await ratingImport(db, body);

        // ── LANGUAGE VOTES ───────────────────────────────────
        case '/lang/get':
          return await langGet(db, body);
        case '/lang/vote':
          return await langVote(db, body);
        case '/lang/remove':
          return await langRemove(db, body);
        case '/lang/import':
          return await langImport(db, body);

        // ── DEVICE SYNC ──────────────────────────────────────
        case '/sync/send':
          return await syncSend(db, body);
        case '/sync/receive':
          return await syncReceive(db, body);

        // ── CF BYPASS ────────────────────────────────────────
        case '/cf-bypass/create':
          return await cfBypassCreate(db, body, url.origin);
        case '/cf-bypass/submit':
          return await cfBypassSubmit(db, body);
        case '/cf-bypass/poll':
          return await cfBypassPoll(db, body);
        case '/cf-bypass/auto-resolve':
          return await cfBypassAutoResolve(db, body);

        default:
          return json({ error: 'Unknown endpoint' }, 404);
      }
    } catch (e) {
      return json({ error: e.message }, 500);
    }
  }
};

// ── HELPERS ──────────────────────────────────────────────────

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      'Content-Type': 'application/json',
      'Access-Control-Allow-Origin': '*',
    }
  });
}

function generateCode() {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  let code = '';
  for (let i = 0; i < 6; i++) {
    code += chars[Math.floor(Math.random() * chars.length)];
  }
  return code;
}

function generateToken() {
  const chars = 'abcdefghijklmnopqrstuvwxyz0123456789';
  let token = '';
  for (let i = 0; i < 32; i++) {
    token += chars[Math.floor(Math.random() * chars.length)];
  }
  return token;
}

// ── RATINGS ──────────────────────────────────────────────────

async function ratingGet(db, { contentKey }) {
  if (!contentKey) return json({ error: 'contentKey required' }, 400);

  const rating = await db.prepare(
    'SELECT average_rating, total_votes, title FROM ratings WHERE content_key = ?'
  ).bind(contentKey).first();

  return json({
    averageRating: rating?.average_rating || 0,
    totalVotes: rating?.total_votes || 0,
    title: rating?.title || null,
  });
}

// ── IMPORT (migration Firebase → D1) ──
async function ratingImport(db, { contentKey, title, averageRating, totalVotes }) {
  if (!contentKey) return json({ error: 'contentKey required' }, 400);
  await db.prepare(
    `INSERT INTO ratings (content_key, title, average_rating, total_votes)
     VALUES (?, ?, ?, ?)
     ON CONFLICT(content_key) DO UPDATE SET
       title = COALESCE(excluded.title, title),
       average_rating = excluded.average_rating,
       total_votes = excluded.total_votes`
  ).bind(contentKey, title || null, averageRating || 0, totalVotes || 0).run();
  return json({ ok: true });
}

async function langImport(db, { contentKey, votesVF, votesVOSTFR, votesVO }) {
  if (!contentKey) return json({ error: 'contentKey required' }, 400);
  // Upsert les compteurs langue directement
  await db.prepare(
    `INSERT INTO language_counts (content_key, votes_vf, votes_vostfr, votes_vo)
     VALUES (?, ?, ?, ?)
     ON CONFLICT(content_key) DO UPDATE SET
       votes_vf = excluded.votes_vf,
       votes_vostfr = excluded.votes_vostfr,
       votes_vo = excluded.votes_vo`
  ).bind(contentKey, votesVF || 0, votesVOSTFR || 0, votesVO || 0).run();
  return json({ ok: true });
}

async function ratingVote(db, { contentKey, deviceId, rating, title }) {
  if (!contentKey || !deviceId || rating == null) {
    return json({ error: 'contentKey, deviceId, rating required' }, 400);
  }

  const now = Date.now();

  // Upsert le vote
  await db.prepare(
    'INSERT OR REPLACE INTO rating_votes (content_key, device_id, rating, timestamp) VALUES (?, ?, ?, ?)'
  ).bind(contentKey, deviceId, rating, now).run();

  // Recalculer la moyenne
  const stats = await db.prepare(
    'SELECT AVG(rating) as avg, COUNT(*) as cnt FROM rating_votes WHERE content_key = ?'
  ).bind(contentKey).first();

  const avg = Math.round((stats.avg || 0) * 10) / 10;
  const cnt = stats.cnt || 0;

  await db.prepare(
    'INSERT OR REPLACE INTO ratings (content_key, average_rating, total_votes, title) VALUES (?, ?, ?, ?)'
  ).bind(contentKey, avg, cnt, title || '').run();

  return json({ averageRating: avg, totalVotes: cnt });
}

// ── LANGUAGE VOTES ───────────────────────────────────────────

async function langGet(db, { contentKey }) {
  if (!contentKey) return json({ error: 'contentKey required' }, 400);

  const counts = await db.prepare(
    'SELECT votes_vf, votes_vostfr, votes_vo FROM language_counts WHERE content_key = ?'
  ).bind(contentKey).first();

  return json({
    votesVF: counts?.votes_vf || 0,
    votesVOSTFR: counts?.votes_vostfr || 0,
    votesVO: counts?.votes_vo || 0,
  });
}

async function langVote(db, { contentKey, deviceId, lang }) {
  if (!contentKey || !deviceId || !lang) {
    return json({ error: 'contentKey, deviceId, lang required' }, 400);
  }
  if (!['VF', 'VOSTFR', 'VO'].includes(lang)) {
    return json({ error: 'lang must be VF, VOSTFR or VO' }, 400);
  }

  const now = Date.now();

  // Upsert le vote
  await db.prepare(
    'INSERT OR REPLACE INTO language_votes (content_key, device_id, lang, timestamp) VALUES (?, ?, ?, ?)'
  ).bind(contentKey, deviceId, lang, now).run();

  // Recalculer les compteurs
  await recountLanguage(db, contentKey);

  const counts = await db.prepare(
    'SELECT votes_vf, votes_vostfr, votes_vo FROM language_counts WHERE content_key = ?'
  ).bind(contentKey).first();

  return json({
    votesVF: counts?.votes_vf || 0,
    votesVOSTFR: counts?.votes_vostfr || 0,
    votesVO: counts?.votes_vo || 0,
  });
}

async function langRemove(db, { contentKey, deviceId }) {
  if (!contentKey || !deviceId) {
    return json({ error: 'contentKey, deviceId required' }, 400);
  }

  await db.prepare(
    'DELETE FROM language_votes WHERE content_key = ? AND device_id = ?'
  ).bind(contentKey, deviceId).run();

  await recountLanguage(db, contentKey);

  const counts = await db.prepare(
    'SELECT votes_vf, votes_vostfr, votes_vo FROM language_counts WHERE content_key = ?'
  ).bind(contentKey).first();

  return json({
    votesVF: counts?.votes_vf || 0,
    votesVOSTFR: counts?.votes_vostfr || 0,
    votesVO: counts?.votes_vo || 0,
  });
}

async function recountLanguage(db, contentKey) {
  const rows = await db.prepare(
    "SELECT lang, COUNT(*) as cnt FROM language_votes WHERE content_key = ? GROUP BY lang"
  ).bind(contentKey).all();

  let vf = 0, vostfr = 0, vo = 0;
  for (const r of rows.results || []) {
    if (r.lang === 'VF') vf = r.cnt;
    else if (r.lang === 'VOSTFR') vostfr = r.cnt;
    else if (r.lang === 'VO') vo = r.cnt;
  }

  await db.prepare(
    'INSERT OR REPLACE INTO language_counts (content_key, votes_vf, votes_vostfr, votes_vo) VALUES (?, ?, ?, ?)'
  ).bind(contentKey, vf, vostfr, vo).run();
}

// ── DEVICE SYNC ──────────────────────────────────────────────

async function syncSend(db, { payload }) {
  if (!payload) return json({ error: 'payload required' }, 400);

  // Nettoyer les syncs expirés
  await db.prepare('DELETE FROM device_sync WHERE expires_at < ?').bind(Date.now()).run();

  // Générer un code unique
  let code;
  let attempts = 0;
  do {
    code = generateCode();
    const existing = await db.prepare('SELECT code FROM device_sync WHERE code = ?').bind(code).first();
    if (!existing) break;
    attempts++;
  } while (attempts < 10);

  const now = Date.now();
  const ttl = 5 * 60 * 1000; // 5 minutes

  await db.prepare(
    'INSERT INTO device_sync (code, payload, created_at, expires_at) VALUES (?, ?, ?, ?)'
  ).bind(code, payload, now, now + ttl).run();

  return json({ code });
}

async function syncReceive(db, { code }) {
  if (!code) return json({ error: 'code required' }, 400);

  const normalized = code.toUpperCase().replace(/\s/g, '');

  const row = await db.prepare(
    'SELECT payload, expires_at FROM device_sync WHERE code = ?'
  ).bind(normalized).first();

  if (!row) return json({ error: 'Code introuvable ou expiré' }, 404);

  if (Date.now() > row.expires_at) {
    await db.prepare('DELETE FROM device_sync WHERE code = ?').bind(normalized).run();
    return json({ error: 'Code expiré' }, 410);
  }

  // Supprimer après import (usage unique)
  await db.prepare('DELETE FROM device_sync WHERE code = ?').bind(normalized).run();

  return json({ payload: row.payload });
}

// ══════════════════════════════════════════════════════════════
//  CF BYPASS — QR relay TV ↔ téléphone via D1
//
//  Flow :
//    1. TV  → POST /cf-bypass/create {slug} → {token, pageUrl}
//    2. TV affiche QR code avec pageUrl
//    3. Phone scanne QR → GET /cf-bypass/<token> → page HTML
//    4. Page tente auto-resolve (Worker fetch) puis app ONYX,
//       puis fallback manuel (copier-coller m3u8)
//    5. Résultat → POST /cf-bypass/submit {token, m3u8Url}
//    6. TV poll → POST /cf-bypass/poll {token} → {status, m3u8Url}
// ══════════════════════════════════════════════════════════════

const CF_BYPASS_TTL_MS = 5 * 60 * 1000; // 5 minutes
const S4F_BASE = 'https://www.stream4free.tv';
const M3U8_RE = /https?:\/\/[a-z0-9]+\.data-stream\.top\/[a-f0-9]+\/hls\/[^"'\s<>]+\.m3u8/;

async function cfBypassCreate(db, { slug }, origin) {
  if (!slug) return json({ error: 'slug required' }, 400);

  // Cleanup expired sessions
  await db.prepare('DELETE FROM cf_bypass_sessions WHERE expires_at < ?').bind(Date.now()).run();

  const token = generateToken();
  const now = Date.now();

  await db.prepare(
    'INSERT INTO cf_bypass_sessions (token, slug, status, created_at, expires_at) VALUES (?, ?, ?, ?, ?)'
  ).bind(token, slug, 'pending', now, now + CF_BYPASS_TTL_MS).run();

  return json({
    token,
    pageUrl: `${origin}/cf-bypass/${token}`,
  });
}

async function cfBypassSubmit(db, { token, m3u8Url }) {
  if (!token || !m3u8Url) return json({ error: 'token and m3u8Url required' }, 400);

  const session = await db.prepare(
    'SELECT status, expires_at FROM cf_bypass_sessions WHERE token = ?'
  ).bind(token).first();

  if (!session) return json({ error: 'Session introuvable' }, 404);
  if (Date.now() > session.expires_at) return json({ error: 'Session expirée' }, 410);
  if (session.status === 'resolved') return json({ ok: true, alreadyResolved: true });

  await db.prepare(
    'UPDATE cf_bypass_sessions SET m3u8_url = ?, status = ? WHERE token = ?'
  ).bind(m3u8Url, 'resolved', token).run();

  return json({ ok: true });
}

async function cfBypassPoll(db, { token }) {
  if (!token) return json({ error: 'token required' }, 400);

  const session = await db.prepare(
    'SELECT slug, m3u8_url, status, expires_at FROM cf_bypass_sessions WHERE token = ?'
  ).bind(token).first();

  if (!session) return json({ error: 'Session introuvable' }, 404);
  if (Date.now() > session.expires_at) {
    await db.prepare('DELETE FROM cf_bypass_sessions WHERE token = ?').bind(token).run();
    return json({ status: 'expired' });
  }

  return json({
    status: session.status,
    m3u8Url: session.m3u8_url || null,
  });
}

/**
 * Tentative de résolution serveur (Worker edge → stream4free).
 * Marche quand CF ne challenge pas le Worker (pas de Turnstile côté Worker).
 */
async function cfBypassAutoResolve(db, { token, slug }) {
  if (!token || !slug) return json({ error: 'token and slug required' }, 400);

  try {
    const resp = await fetch(`${S4F_BASE}/${slug}`, {
      headers: {
        'User-Agent': 'Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.72 Mobile Safari/537.36',
        'Accept': 'text/html,application/xhtml+xml,*/*',
        'Accept-Language': 'fr-FR,fr;q=0.9',
      },
      redirect: 'follow',
    });

    const html = await resp.text();
    const match = html.match(M3U8_RE);

    if (match) {
      // Store result in D1
      await db.prepare(
        'UPDATE cf_bypass_sessions SET m3u8_url = ?, status = ? WHERE token = ?'
      ).bind(match[0], 'resolved', token).run();
      return json({ resolved: true, m3u8Url: match[0] });
    }
  } catch (e) {
    // Fetch failed (CF block, network error) — fall through to manual
  }

  return json({ resolved: false });
}

/**
 * Page HTML servie au téléphone après scan QR.
 * Fonctionne sur TOUT appareil (iPhone, Android, tablette).
 * 3 modes :
 *   1. Auto-resolve (Worker fetch) — instantané si pas de CF
 *   2. App ONYX (intent Android) — automatique si app installée
 *   3. Manuel (copier-coller) — fallback universel
 */
async function cfBypassPage(db, token, origin) {
  const session = await db.prepare(
    'SELECT slug, status, m3u8_url, expires_at FROM cf_bypass_sessions WHERE token = ?'
  ).bind(token).first();

  if (!session) {
    return new Response('Session introuvable ou expirée.', { status: 404, headers: { 'Content-Type': 'text/html; charset=utf-8' } });
  }
  if (Date.now() > session.expires_at) {
    return new Response('Session expirée. Relancez depuis la TV.', { status: 410, headers: { 'Content-Type': 'text/html; charset=utf-8' } });
  }
  if (session.status === 'resolved') {
    return new Response(cfBypassSuccessHtml(), { headers: { 'Content-Type': 'text/html; charset=utf-8' } });
  }

  const slug = session.slug;
  const html = cfBypassPageHtml(token, slug, origin);
  return new Response(html, { headers: { 'Content-Type': 'text/html; charset=utf-8' } });
}

function cfBypassSuccessHtml() {
  return `<!DOCTYPE html>
<html lang="fr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>ONYX</title>
<style>body{font-family:-apple-system,system-ui,sans-serif;background:#0f0f0f;color:#e0e0e0;margin:0;padding:24px;display:flex;justify-content:center;align-items:center;min-height:100vh}
.card{background:#1a1a1a;border-radius:20px;padding:32px;max-width:380px;width:100%;text-align:center}
.ok{color:#4ade80;font-size:48px;margin-bottom:16px}
h2{color:#fff;margin:0 0 8px}p{color:#999;margin:0}</style>
</head><body><div class="card"><div class="ok">&#10003;</div><h2>Déjà débloqué</h2><p>La chaîne est en cours de lecture sur votre TV.<br>Vous pouvez fermer cette page.</p></div></body></html>`;
}

function cfBypassPageHtml(token, slug, origin) {
  return `<!DOCTYPE html>
<html lang="fr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>ONYX — Déblocage TV</title>
<style>
*{box-sizing:border-box}
body{font-family:-apple-system,system-ui,'Segoe UI',sans-serif;background:#0f0f0f;color:#e0e0e0;margin:0;padding:16px;display:flex;justify-content:center;min-height:100vh}
.card{background:#1a1a1a;border-radius:20px;padding:24px;max-width:400px;width:100%;margin-top:24px;align-self:flex-start}
.logo{text-align:center;margin-bottom:4px}
.logo span{font-size:28px;font-weight:800;letter-spacing:2px;color:#fff}
.sub{text-align:center;color:#777;font-size:13px;margin-bottom:20px}
.step{display:none}.step.active{display:block}
.status-box{background:#222;border-radius:14px;padding:20px;text-align:center;margin:12px 0}
.spinner{width:32px;height:32px;border:3px solid #333;border-top-color:#7c3aed;border-radius:50%;animation:spin .8s linear infinite;margin:0 auto 12px}
@keyframes spin{to{transform:rotate(360deg)}}
.ok-icon{font-size:48px;color:#4ade80;margin-bottom:8px}
.err-icon{font-size:36px;color:#f87171;margin-bottom:8px}
.btn{display:block;width:100%;padding:14px;border:none;border-radius:14px;font-size:15px;font-weight:600;cursor:pointer;margin:8px 0;text-align:center;text-decoration:none}
.btn-primary{background:#7c3aed;color:#fff}
.btn-primary:active{background:#6d28d9}
.btn-secondary{background:#262626;color:#e0e0e0}
.btn-secondary:active{background:#333}
input[type=text]{width:100%;padding:14px;border:1px solid #333;border-radius:14px;background:#222;color:#fff;font-size:14px;margin:8px 0}
input[type=text]:focus{border-color:#7c3aed;outline:none}
ol{padding-left:20px;margin:12px 0;line-height:1.8;color:#bbb;font-size:14px}
ol li{margin:4px 0}
.channel-name{background:#262626;border-radius:10px;padding:10px 14px;margin:8px 0;font-size:14px;color:#aaa;text-align:center}
.small{font-size:12px;color:#666;text-align:center;margin-top:16px}
</style>
</head><body>
<div class="card">
  <div class="logo"><span>ONYX</span></div>
  <p class="sub">Déblocage Cloudflare pour votre TV</p>

  <!-- Step 1: Auto-resolve -->
  <div class="step active" id="s1">
    <div class="status-box">
      <div class="spinner"></div>
      <p>Résolution automatique...</p>
    </div>
  </div>

  <!-- Step 2: ONYX app attempt -->
  <div class="step" id="s2">
    <div class="status-box">
      <div class="spinner"></div>
      <p>Ouverture de l'app ONYX...</p>
      <p style="font-size:12px;color:#666;margin-top:8px">Si l'app ne s'ouvre pas, patientez</p>
    </div>
  </div>

  <!-- Step 3: Manual mode -->
  <div class="step" id="s3">
    <div class="channel-name">Chaîne : <strong>${escHtml(slug)}</strong></div>
    <ol>
      <li>Appuyez sur le bouton ci-dessous pour ouvrir la chaîne</li>
      <li>Attendez que Cloudflare se valide (quelques secondes)</li>
      <li>Quand la vidéo joue, <strong>appui long sur la vidéo</strong></li>
      <li>Sélectionnez <strong>« Copier l'adresse de la vidéo »</strong></li>
      <li>Revenez ici et collez le lien</li>
    </ol>
    <a class="btn btn-secondary" href="${S4F_BASE}/${encodeURIComponent(slug)}" target="_blank" rel="noopener">
      Ouvrir la chaîne &rarr;
    </a>
    <input type="text" id="url-input" placeholder="Collez l'URL ici (m3u8 ou data-stream)...">
    <button class="btn btn-primary" onclick="doSubmit()">Envoyer à la TV</button>
    <p class="small">Fonctionne sur iPhone, Android, tablette — tout navigateur</p>
  </div>

  <!-- Step 4: Success -->
  <div class="step" id="s4">
    <div class="status-box">
      <div class="ok-icon">&#10003;</div>
      <p style="color:#4ade80;font-weight:600;font-size:18px">Chaîne débloquée !</p>
      <p style="color:#999;margin-top:8px">La TV va commencer la lecture.<br>Vous pouvez fermer cette page.</p>
    </div>
  </div>

  <!-- Step 5: Error -->
  <div class="step" id="s5">
    <div class="status-box">
      <div class="err-icon">&#10007;</div>
      <p style="color:#f87171" id="err-msg">Erreur</p>
    </div>
    <button class="btn btn-secondary" onclick="goManual()">Réessayer manuellement</button>
  </div>
</div>

<script>
const TOKEN = '${token}';
const SLUG = '${escJs(slug)}';
const API = '${origin}';

function show(id) {
  document.querySelectorAll('.step').forEach(s => s.classList.remove('active'));
  document.getElementById(id).classList.add('active');
}

// ── Step 1: Auto-resolve (Worker edge fetch) ──
async function tryAutoResolve() {
  try {
    const r = await fetch(API + '/cf-bypass/auto-resolve', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({token: TOKEN, slug: SLUG})
    });
    const d = await r.json();
    if (d.resolved) { show('s4'); return; }
  } catch(e) {}
  tryOnyxApp();
}

// ── Step 2: Try ONYX app (Android intent) ──
function tryOnyxApp() {
  show('s2');
  const intent = 'intent://resolve?type=stream4free&slug=' + encodeURIComponent(SLUG)
    + '&workerToken=' + encodeURIComponent(TOKEN)
    + '&workerApi=' + encodeURIComponent(API)
    + '#Intent;scheme=streamflix;package=com.streamfr.app;end';

  const start = Date.now();
  // Attempt to launch the app
  const iframe = document.createElement('iframe');
  iframe.style.display = 'none';
  iframe.src = intent;
  document.body.appendChild(iframe);
  setTimeout(() => { try { document.body.removeChild(iframe); } catch(e){} }, 2000);

  // If still on this page after 3s, app didn't open → manual mode
  setTimeout(() => {
    // Also start polling in case app DID open but in background
    startPoll();
    show('s3');
  }, 3000);
}

// ── Step 3: Manual submit ──
async function doSubmit() {
  const url = document.getElementById('url-input').value.trim();
  if (!url) { alert('Collez une URL valide'); return; }
  if (!url.includes('data-stream') && !url.includes('.m3u8') && !url.includes('http')) {
    alert('URL invalide. Cherchez un lien contenant .m3u8 ou data-stream.');
    return;
  }
  try {
    const r = await fetch(API + '/cf-bypass/submit', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({token: TOKEN, m3u8Url: url})
    });
    const d = await r.json();
    if (d.ok) { show('s4'); stopPoll(); }
    else { document.getElementById('err-msg').textContent = d.error || 'Erreur inconnue'; show('s5'); }
  } catch(e) {
    document.getElementById('err-msg').textContent = 'Erreur réseau';
    show('s5');
  }
}

function goManual() { show('s3'); }

// ── Polling (detect if ONYX app resolved it) ──
let pollId = null;
function startPoll() {
  if (pollId) return;
  pollId = setInterval(async () => {
    try {
      const r = await fetch(API + '/cf-bypass/poll', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({token: TOKEN})
      });
      const d = await r.json();
      if (d.status === 'resolved') { show('s4'); stopPoll(); }
      else if (d.status === 'expired') { stopPoll(); }
    } catch(e) {}
  }, 3000);
}
function stopPoll() { if (pollId) { clearInterval(pollId); pollId = null; } }

// Start
tryAutoResolve();
</script>
</body></html>`;
}

function escHtml(s) { return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;'); }
function escJs(s) { return String(s).replace(/\\/g,'\\\\').replace(/'/g,"\\'").replace(/"/g,'\\"'); }
