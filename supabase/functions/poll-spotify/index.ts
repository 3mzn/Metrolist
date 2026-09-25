/**
 * poll-spotify — SPEC_SPOTIFY_MIRROR Phase 1.
 *
 * DUAL MODE, one code path:
 *   cron mode   : any non-POST (pg_cron) → poll ALL sources, store rows, return summary.
 *   direct mode : POST {source_ids?} → poll those (or all), store rows AND return them.
 *
 * Read path (proven, plain fetch — no secret/OAuth/TLS tricks, never /v1/*):
 *   1. embed playlist page → anonymous accessToken (~0.5s, ~1h life, minted per poll)
 *   2. spclient playlist/v2/playlist/{id}?from=&length= → URIs + length + revision
 *   3. per NEW track only: embed/track page → title/artist/duration_ms
 *
 * Per-invocation resolve cap (BACKFILL_CAP): a fresh 1000-track source resolves over
 * successive polls/invokes instead of blowing the function wall clock. Re-polls never
 * duplicate (unique(source_id, spotify_id)).
 *
 * Fail-closed: any Spotify error → source.error set, logged, nothing deleted.
 * Quiet backoff (Fix-A): consecutive failures wait min(60, 2^count) minutes.
 * Poison backoff (Fix-A): URIs failing resolve 5× park for 7 days (mirror_skipped).
 * FCM (Fix-A): best-effort data wake after new rows; WorkManager stays the guarantee.
 */

const UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
const EMBED_H = {
  "User-Agent": UA,
  Accept: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
  "Accept-Language": "en-US,en;q=0.9",
};
const BACKFILL_CAP = 100;
const TRACK_SPACING_MS = 300;
const FETCH_TIMEOUT_MS = 15000;
const POISON_MAX = 5;
const POISON_RETRY_MS = 7 * 24 * 3600 * 1000;

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/** Plain fetch with a hard timeout (a hung socket must never eat an invocation). */
async function sfetch(url: string, init: RequestInit = {}): Promise<Response> {
  return fetch(url, { ...init, signal: AbortSignal.timeout(FETCH_TIMEOUT_MS) });
}

// Supabase client via esm (service_role bypasses RLS; injected by the runtime).
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
const supabase = createClient(
  Deno.env.get("SUPABASE_URL")!,
  Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
);

const apiH = (token: string) => ({
  Authorization: `Bearer ${token}`,
  "User-Agent": UA,
  Accept: "application/json",
  Origin: "https://open.spotify.com",
  Referer: "https://open.spotify.com/",
  "App-Platform": "WebPlayer",
  "Spotify-App-Version": "1.2.50",
});

async function mintToken(playlistId: string): Promise<string | null> {
  try {
    const r = await sfetch(`https://open.spotify.com/embed/playlist/${playlistId}`, { headers: EMBED_H });
    if (!r.ok) return null;
    const m = (await r.text()).match(/"accessToken":"([^"]{20,})"/);
    return m ? m[1] : null;
  } catch {
    return null;
  }
}

async function readUris(playlistId: string, token: string): Promise<{ uris: string[]; total: number; revision: string } | null> {
  try {
    const uris: string[] = [];
    let from = 0;
    let total = -1;
    let revision = "";
    for (let page = 0; page < 40; page++) {
      const r = await sfetch(
        `https://spclient.wg.spotify.com/playlist/v2/playlist/${playlistId}?from=${from}&length=200`,
        { headers: apiH(token) },
      );
      if (!r.ok) return null;
      const j = await r.json();
      total = j.length;
      revision = j.revision ?? "";
      const items: string[] = (j.contents?.items ?? []).map((it: { uri: string }) => it.uri.split(":")[2]);
      uris.push(...items);
      if (!j.contents?.truncated || items.length === 0) break;
      from += items.length;
    }
    return { uris, total, revision };
  } catch {
    return null;
  }
}

async function resolveTrack(trackId: string): Promise<{ title: string; artist: string; duration_ms: number } | null> {
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      const r = await sfetch(`https://open.spotify.com/embed/track/${trackId}`, { headers: EMBED_H });
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      const h = await r.text();
      // Allow escaped quotes inside values (\" in titles like ... From \"Fifty ...\"):
      // a naive [^"] class truncates at the escape's quote.
      const names = [...h.matchAll(/"name":"((?:[^"\\]|\\.){1,200})"/g)].map((m) => m[1]);
      const dur = (h.match(/"duration":(\d{4,7})/) || [])[1];
      // The embed HTML carries JSON-escaped strings: decode (\uXXXX, \\, \/) or the
      // stored titles come out mangled ("Kings \\u0026 Queens") and unmatchable.
      // A trailing lone backslash means truncation at an escaped quote — drop it.
      const title = names[0] ? unescapeJsonString(names[0]) : null;
      const artist = names[1] ? unescapeJsonString(names[1]) : null;
      if (title && artist && dur) {
        return { title, artist, duration_ms: +dur };
      }
      throw new Error("parse-incomplete");
    } catch {
      if (attempt < 2) await sleep(1500);
    }
  }
  return null;
}

function unescapeJsonString(s: string): string {
  const cleaned = s.replace(/\\$/, "");
  try {
    return JSON.parse('"' + cleaned + '"');
  } catch {
    return cleaned;
  }
}

type Source = {
  id: string;
  spotify_id: string;
  last_revision: string | null;
  error_count: number | null;
  last_checked_at: string | null;
};

async function markFailed(source: Source, code: string) {
  await supabase.from("mirror_sources").update({
    error: code,
    error_count: (source.error_count ?? 0) + 1,
    last_checked_at: new Date().toISOString(),
  }).eq("id", source.id);
}

async function markOk(source: Source, revision: string | null) {
  await supabase.from("mirror_sources").update({
    last_revision: revision,
    last_checked_at: new Date().toISOString(),
    error: null,
    error_count: 0,
  }).eq("id", source.id);
}

async function noteSkipped(sourceId: string, trackId: string) {
  const { data } = await supabase.from("mirror_skipped")
    .select("failures").eq("source_id", sourceId).eq("spotify_id", trackId).maybeSingle();
  if (data) {
    await supabase.from("mirror_skipped").update({
      failures: (data.failures ?? 0) + 1,
      failed_at: new Date().toISOString(),
    }).eq("source_id", sourceId).eq("spotify_id", trackId);
  } else {
    await supabase.from("mirror_skipped")
      .insert({ source_id: sourceId, spotify_id: trackId, failures: 1 });
  }
}

async function clearSkipped(sourceId: string, trackId: string) {
  await supabase.from("mirror_skipped").delete().eq("source_id", sourceId).eq("spotify_id", trackId);
}

async function pollSource(source: Source) {
  // Quiet backoff (Fix-A): while inside the penalty window, touch nothing so the
  // last_checked_at base stays stable and the next cron re-evaluates correctly.
  const fails = source.error_count ?? 0;
  if (fails > 0 && source.last_checked_at) {
    const waitMs = Math.min(60, 2 ** fails) * 60 * 1000;
    if (Date.now() - Date.parse(source.last_checked_at) < waitMs) {
      return { added: [] as object[], skipped_backoff: true };
    }
  }
  const token = await mintToken(source.spotify_id);
  if (!token) {
    await markFailed(source, "token-mint-failed");
    return { added: [] as object[], error: "token-mint-failed" };
  }
  const read = await readUris(source.spotify_id, token);
  if (!read) {
    await markFailed(source, "playlist-read-failed");
    return { added: [] as object[], error: "playlist-read-failed" };
  }
  if (read.uris.length < read.total) {
    // Short read (transient partial page): fail closed rather than diffing against a
    // partial URI set, which would misreport old songs as new and set revision on garbage.
    await markFailed(source, "short-read");
    return { added: [] as object[], error: "short-read" };
  }
  if (read.revision && read.revision === source.last_revision) {
    await markOk(source, source.last_revision);
    return { added: [] as object[], skipped_unchanged: true };
  }
  const { data: known, error: knownErr } = await supabase.from("mirror_tracks").select("spotify_id").eq("source_id", source.id);
  if (knownErr) {
    // Never diff against an unknown set: an empty known-set would re-resolve (and
    // misreport) the entire playlist.
    await markFailed(source, "known-read-failed");
    return { added: [] as object[], error: "known-read-failed" };
  }
  const knownSet = new Set((known ?? []).map((r: { spotify_id: string }) => r.spotify_id));
  // Poison set: parked after POISON_MAX failures, retried after POISON_RETRY_MS. A
  // failed park-table read degrades to empty (bounded waste, self-heals) rather than
  // failing the poll.
  let parked = new Set<string>();
  try {
    const { data: skipped } = await supabase.from("mirror_skipped")
      .select("spotify_id,failures,failed_at").eq("source_id", source.id);
    parked = new Set(
      (skipped ?? [])
        .filter((s: { failures: number; failed_at: string }) =>
          s.failures >= POISON_MAX && Date.now() - Date.parse(s.failed_at) < POISON_RETRY_MS)
        .map((s: { spotify_id: string }) => s.spotify_id),
    );
  } catch { /* park unavailable: proceed unparked */ }
  const unknown = read.uris.filter((u) => !knownSet.has(u) && !parked.has(u));
  const fresh = unknown.slice(0, BACKFILL_CAP);

  const added: object[] = [];
  for (const tid of fresh) {
    const meta = await resolveTrack(tid);
    await sleep(TRACK_SPACING_MS);
    if (!meta) {
      await noteSkipped(source.id, tid);
      continue; // retried 3x; next poll rediscovers unless parked
    }
    const row = { source_id: source.id, spotify_id: tid, ...meta };
    // No ignoreDuplicates: a 409 means a concurrent poll won it (not new); any other
    // error leaves it unknown for retry. Either way `added` stays exact.
    const { error } = await supabase.from("mirror_tracks")
      .upsert(row, { onConflict: "source_id,spotify_id" });
    if (!error) {
      added.push(row);
      await clearSkipped(source.id, tid);
    } else if (!isConflict(error)) {
      await noteSkipped(source.id, tid);
    }
  }
  // Advance the revision ONLY when every unknown URI got resolved this pass. Otherwise
  // the next poll would see a matching revision and skip, stranding the remainder
  // (BACKFILL_CAP cut or resolve failures) forever.
  const stillUnknown = unknown.length - added.length;
  if (stillUnknown === 0) {
    await markOk(source, read.revision || null);
  } else {
    await supabase.from("mirror_sources").update({
      last_checked_at: new Date().toISOString(),
      error: null,
      error_count: 0,
    }).eq("id", source.id);
  }
  return { added, remaining: stillUnknown };
}

function isConflict(error: { code?: string; message?: string }): boolean {
  return error.code === "23505" || /conflict|duplicate|already exists/i.test(error.message ?? "");
}

const b64url = (bytes: Uint8Array): string =>
  btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const b64urlStr = (s: string): string => b64url(new TextEncoder().encode(s));

function pemToDer(pem: string): Uint8Array {
  const b64 = pem.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

/**
 * Best-effort FCM data wake (Fix-A F16): tells awake phones to pull NOW so closed-app
 * freshness doesn't rest on the 15-min worker alone. Every failure mode returns null;
 * WorkManager remains the guarantee, so a dead push path only costs latency.
 */
async function sendMirrorWake(total: number): Promise<boolean> {
  try {
    const raw = Deno.env.get("FIREBASE_SERVICE_ACCOUNT");
    if (!raw) return false;
    const sa = JSON.parse(raw) as { client_email: string; private_key: string; project_id: string };
    const now = Math.floor(Date.now() / 1000);
    const h = b64urlStr(JSON.stringify({ alg: "RS256", typ: "JWT" }));
    const c = b64urlStr(JSON.stringify({
      iss: sa.client_email,
      scope: "https://www.googleapis.com/auth/firebase.messaging",
      aud: "https://oauth2.googleapis.com/token",
      iat: now,
      exp: now + 600,
    }));
    const key = await crypto.subtle.importKey(
      "pkcs8",
      pemToDer(sa.private_key),
      { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
      false,
      ["sign"],
    );
    const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(`${h}.${c}`));
    const tokenResp = await fetch("https://oauth2.googleapis.com/token", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=${h}.${c}.${b64url(new Uint8Array(sig))}`,
      signal: AbortSignal.timeout(10000),
    });
    if (!tokenResp.ok) return false;
    const { access_token } = await tokenResp.json();
    const push = await fetch(
      `https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`,
      {
        method: "POST",
        headers: { Authorization: `Bearer ${access_token}`, "Content-Type": "application/json" },
        body: JSON.stringify({
          message: {
            topic: "metrolist_foss_updates",
            data: { type: "mirror_wake", count: String(total) },
            android: { priority: "high" },
          },
        }),
        signal: AbortSignal.timeout(10000),
      },
    );
    return push.ok;
  } catch {
    return false;
  }
}

// deno-lint-ignore no-explicit-any
async function handler(req: Request): Promise<Response> {
  let only: string[] | null = null;
  if (req.method === "POST") {
    const body = await req.json().catch(() => null);
    if (body && Array.isArray(body.source_ids)) only = body.source_ids;
    // Probe mode (Phase 3 backfill confirmation): mint + read length only, no
    // resolve, no store. Returns {total, revision} for a raw Spotify playlist id.
    if (body && typeof body.probe_spotify_id === "string") {
      const pid = body.probe_spotify_id as string;
      if (!/^[A-Za-z0-9]{10,30}$/.test(pid)) {
        return Response.json({ ok: false, error: "bad-playlist-id" }, { status: 400 });
      }
      const token = await mintToken(pid);
      if (!token) return Response.json({ ok: false, error: "token-mint-failed" });
      const read = await readUris(pid, token);
      if (!read || read.uris.length < read.total) {
        return Response.json({ ok: false, error: "playlist-read-failed" });
      }
      return Response.json({ ok: true, total: read.total, revision: read.revision });
    }
  }
  let q = supabase.from("mirror_sources").select("id,spotify_id,last_revision,error_count,last_checked_at");
  if (only) q = q.in("id", only);
  const { data: sources, error } = await q;
  if (error) return Response.json({ ok: false, error: error.message }, { status: 500 });

  const perSource: Record<string, unknown> = {};
  let addedTotal = 0;
  for (const s of sources ?? []) {
    const res = await pollSource(s as Source);
    perSource[(s as { id: string }).id] = res;
    // deno-lint-ignore no-explicit-any
    addedTotal += ((res as any).added ?? []).length;
  }
  if (addedTotal > 0) {
    await sendMirrorWake(addedTotal);
  }
  console.log(`poll-spotify: ${sources?.length ?? 0} sources, ${addedTotal} new rows`);
  return Response.json({ ok: true, sources: perSource, added_total: addedTotal });
}

Deno.serve(handler);
