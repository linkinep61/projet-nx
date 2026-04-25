/**
 * Cloudflare Worker — HLS Proxy pour Streamflix
 *
 * Ce Worker relaye les requêtes vers cfglobalcdn.com (bloqué par les FAI français)
 * à travers le réseau Cloudflare, qui n'est pas bloqué.
 *
 * Déploiement :
 * 1. Crée un compte gratuit sur https://dash.cloudflare.com
 * 2. Va dans Workers & Pages → Create Worker
 * 3. Colle ce code et clique Deploy
 * 4. Copie l'URL du Worker (ex: https://hls-proxy.ton-compte.workers.dev)
 * 5. Dans Streamflix → Paramètres → Réseau → HLS Proxy URL, colle cette URL
 *
 * Usage : GET https://worker-url/?url=ENCODED_TARGET_URL
 * Le Worker fetch l'URL cible et retourne le contenu tel quel.
 *
 * Tier gratuit : 100 000 requêtes/jour — largement suffisant pour un usage personnel.
 */

export default {
  async fetch(request) {
    // Handle CORS preflight
    if (request.method === 'OPTIONS') {
      return new Response(null, {
        headers: {
          'Access-Control-Allow-Origin': '*',
          'Access-Control-Allow-Methods': 'GET, OPTIONS',
          'Access-Control-Allow-Headers': '*',
          'Access-Control-Max-Age': '86400',
        },
      });
    }

    const url = new URL(request.url);
    const targetUrl = url.searchParams.get('url');

    if (!targetUrl) {
      return new Response(
        JSON.stringify({ error: 'Missing ?url= parameter', usage: 'GET /?url=ENCODED_URL' }),
        { status: 400, headers: { 'Content-Type': 'application/json' } }
      );
    }

    try {
      // Forward the request to the target URL
      const targetResponse = await fetch(targetUrl, {
        headers: {
          'User-Agent': request.headers.get('User-Agent') || 'Mozilla/5.0',
          'Accept': '*/*',
          'Referer': request.headers.get('X-Referer') || '',
        },
        redirect: 'follow',
      });

      // Clone response headers and add CORS
      const responseHeaders = new Headers(targetResponse.headers);
      responseHeaders.set('Access-Control-Allow-Origin', '*');
      responseHeaders.set('Access-Control-Expose-Headers', '*');
      // Remove headers that might cause issues
      responseHeaders.delete('Content-Security-Policy');
      responseHeaders.delete('X-Frame-Options');

      return new Response(targetResponse.body, {
        status: targetResponse.status,
        statusText: targetResponse.statusText,
        headers: responseHeaders,
      });
    } catch (err) {
      return new Response(
        JSON.stringify({ error: 'Fetch failed', message: err.message, target: targetUrl }),
        { status: 502, headers: { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' } }
      );
    }
  },
};
