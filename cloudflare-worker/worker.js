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
 *   POST /iptv/fetch        {}         → {channels: {key: {cids, latencies, urls}}}
 *   POST /iptv/report       {channelKey, cid, latencyMs, streamUrl?}
 *   POST /iptv/report-batch {reports: [{channelKey, cid, latencyMs?, streamUrl?}]}
 */

export default {
  async fetch(request, env) {
    // CORS
    if (request.method === 'OPTIONS') {
      return new Response(null, {
        headers: {
          'Access-Control-Allow-Origin': '*',
          'Access-Control-Allow-Methods': 'POST, OPTIONS',
          'Access-Control-Allow-Headers': 'Content-Type',
        }
      });
    }

    if (request.method !== 'POST') {
      return json({ error: 'POST only' }, 405);
    }

    const url = new URL(request.url);
    const path = url.pathname;

    try {
      const body = await request.json();
      const db = env.DB;

      switch (path) {
        // ── RATINGS ──────────────────────────────────────────
        case '/rating/get':
          return await ratingGet(db, body);
        case '/rating/vote':
          return await ratingVote(db, body);

        // ── LANGUAGE VOTES ───────────────────────────────────
        case '/lang/get':
          return await langGet(db, body);
        case '/lang/vote':
          return await langVote(db, body);
        case '/lang/remove':
          return await langRemove(db, body);

        // ── DEVICE SYNC ──────────────────────────────────────
        case '/sync/send':
          return await syncSend(db, body);
        case '/sync/receive':
          return await syncReceive(db, body);

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
