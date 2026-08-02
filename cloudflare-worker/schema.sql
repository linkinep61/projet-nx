-- Cloudflare D1 Schema for Streamflix Community Features
-- Remplace Firebase Firestore (quota 50K/jour) par D1 (5M/jour)

-- Notes communautaires (remplace ratings/{contentKey})
CREATE TABLE IF NOT EXISTS ratings (
    content_key TEXT PRIMARY KEY,
    title TEXT,
    average_rating REAL DEFAULT 0,
    total_votes INTEGER DEFAULT 0
);

-- Votes individuels (remplace ratings/{contentKey}/votes/{deviceId})
CREATE TABLE IF NOT EXISTS rating_votes (
    content_key TEXT NOT NULL,
    device_id TEXT NOT NULL,
    rating REAL NOT NULL,
    timestamp INTEGER NOT NULL,
    PRIMARY KEY (content_key, device_id)
);

-- Votes langue communautaire (remplace ratings/{contentKey}/langs/{deviceId})
CREATE TABLE IF NOT EXISTS language_votes (
    content_key TEXT NOT NULL,
    device_id TEXT NOT NULL,
    lang TEXT NOT NULL, -- VF, VOSTFR, VO
    timestamp INTEGER NOT NULL,
    PRIMARY KEY (content_key, device_id)
);

-- Compteurs langue (remplace langVotesVF/VOSTFR/VO sur le doc parent)
CREATE TABLE IF NOT EXISTS language_counts (
    content_key TEXT PRIMARY KEY,
    votes_vf INTEGER DEFAULT 0,
    votes_vostfr INTEGER DEFAULT 0,
    votes_vo INTEGER DEFAULT 0
);

-- Sync appareil (remplace device_sync/{code})
CREATE TABLE IF NOT EXISTS device_sync (
    code TEXT PRIMARY KEY,
    payload TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL
);

-- QR CF bypass sessions (relais TV ↔ téléphone via D1)
-- La TV crée une session, affiche un QR. Le téléphone scanne,
-- résout le CF, et poste le m3u8 ici. La TV poll le résultat.
CREATE TABLE IF NOT EXISTS cf_bypass_sessions (
    token TEXT PRIMARY KEY,
    slug TEXT NOT NULL,
    m3u8_url TEXT,
    status TEXT DEFAULT 'pending',  -- pending, resolved
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL
);

-- ── SANTÉ DES EXTRACTEURS / SOURCES (télémétrie anti-faux-positifs) ──
-- 2026-07-29 : un extracteur peut être bloqué dans UNE région et marcher ailleurs. On ne veut PAS
-- de rapport tant que l'échec n'est pas confirmé sur PLUSIEURS pays/appareils. On stocke des
-- événements bruts (léger, nettoyés > 7 jours par le cron) et le Worker décide « mort partout ».
CREATE TABLE IF NOT EXISTS source_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    source TEXT NOT NULL,        -- nom extracteur/provider (ex "Filemoon", "Movix (natif)")
    kind TEXT NOT NULL,          -- categorie : dead | blocked | streamdead | empty | source | provider
    error_type TEXT,             -- parsing | dns-fail | 403 | head-fail | 0-source ...
    ok INTEGER NOT NULL,         -- 1 = succes, 0 = echec
    country TEXT,                -- pays (request.cf.country) — pose par le Worker
    device_id TEXT NOT NULL,     -- id anonyme (UUID) pour compter les appareils distincts
    app_version TEXT,
    host TEXT,
    ts INTEGER NOT NULL          -- epoch ms
);
CREATE INDEX IF NOT EXISTS idx_source_events_src_ts ON source_events(source, ts);
CREATE INDEX IF NOT EXISTS idx_source_events_ts ON source_events(ts);

-- Extracteurs déjà signalés (anti-doublon d'issue GitHub, cote Worker).
CREATE TABLE IF NOT EXISTS source_reported (
    source TEXT NOT NULL,
    kind TEXT NOT NULL,
    reported_at INTEGER NOT NULL,
    PRIMARY KEY (source, kind)
);

-- Index pour les requetes frequentes
CREATE INDEX IF NOT EXISTS idx_rating_votes_key ON rating_votes(content_key);
CREATE INDEX IF NOT EXISTS idx_language_votes_key ON language_votes(content_key);
CREATE INDEX IF NOT EXISTS idx_device_sync_expires ON device_sync(expires_at);
CREATE INDEX IF NOT EXISTS idx_cf_bypass_expires ON cf_bypass_sessions(expires_at);
