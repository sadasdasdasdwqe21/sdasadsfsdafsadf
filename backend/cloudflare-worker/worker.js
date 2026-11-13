/**
 * LiveLingo token server (Cloudflare Worker, free tier is enough).
 *
 * The phone never holds your real Soniox key. Each translation session it calls this worker,
 * which asks Soniox for a short-lived temporary key and returns it.
 *
 * Secrets (set with `wrangler secret put NAME`):
 *   SONIOX_API_KEY   - your Soniox project key (needs "Temporary API keys" + "Speech-to-text, real-time")
 *   CLIENT_SECRET    - any long random string; paste the same value into LiveLingo > Engine settings
 */
export default {
  async fetch(request, env) {
    if (request.method !== "POST") {
      return json({ error: "POST only" }, 405);
    }
    const secret = request.headers.get("X-LiveLingo-Secret") || "";
    if (!env.CLIENT_SECRET || !timingSafeEqual(secret, env.CLIENT_SECRET)) {
      return json({ error: "unauthorised" }, 401);
    }

    const resp = await fetch("https://api.soniox.com/v1/auth/temporary-api-key", {
      method: "POST",
      headers: {
        Authorization: `Bearer ${env.SONIOX_API_KEY}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        usage_type: "transcribe_websocket",
        expires_in_seconds: 60,             // only needs to live until the socket opens
        single_use: true,
        max_session_duration_seconds: 4 * 60 * 60,
        client_reference_id: "livelingo",
      }),
    });

    if (!resp.ok) {
      const status = resp.status === 429 ? 429 : 502;
      return json({ error: `soniox ${resp.status}` }, status);
    }
    const data = await resp.json();
    return json({ api_key: data.api_key, expires_at: data.expires_at }, 200);
  },
};

function json(body, status) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
  });
}

function timingSafeEqual(a, b) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}
