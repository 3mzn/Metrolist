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
 * TODO(Phase 2): best-effort FCM data message {source_id, count} after new rows.
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

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

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
    const r = await fetch(`https://open.spotify.com/embed/playlist/${playlistId}`, { headers: EMBED_H });
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
      const r = await fetch(
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
      const r = await fetch(`https://open.spotify.com/embed/track/${trackId}`, { headers: EMBED_H });
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      const h = await r.text();
      const names = [...h.matchAll(/"name":"([^"]{1,120})"/g)].map((m) => m[1]);
      const dur = (h.match(/"duration":(\d{4,7})/) || [])[1];
      if (names[0] && names[1] && dur) {
        return { title: names[0], artist: names[1], duration_ms: +dur };
      }
      throw new Error("parse-incomplete");
    } catch {
      if (attempt < 2) await sleep(1500);
    }
  }
  return null;
}

async function pollSource(source: { id: string; spotify_id: string; last_revision: string | null }) {
  const token = await mintToken(source.spotify_id);
  if (!token) {
    await supabase.from("mirror_sources").update({ error: "token-mint-failed", last_checked_at: new Date().toISOString() }).eq("id", source.id);
    return { added: [] as object[], error: "token-mint-failed" };
  }
  const read = await readUris(source.spotify_id, token);
  if (!read) {
    await supabase.from("mirror_sources").update({ error: "playlist-read-failed", last_checked_at: new Date().toISOString() }).eq("id", source.id);
    return { added: [] as object[], error: "playlist-read-failed" };
  }
  if (read.uris.length < read.total) {
    // Short read (transient partial page): fail closed rather than diffing against a
    // partial URI set, which would misreport old songs as new and set revision on garbage.
    await supabase.from("mirror_sources").update({ error: "short-read", last_checked_at: new Date().toISOString() }).eq("id", source.id);
    return { added: [] as object[], error: "short-read" };
  }
  if (read.revision && read.revision === source.last_revision) {
    await supabase.from("mirror_sources").update({ last_checked_at: new Date().toISOString(), error: null }).eq("id", source.id);
    return { added: [] as object[], skipped_unchanged: true };
  }
  const { data: known, error: knownErr } = await supabase.from("mirror_tracks").select("spotify_id").eq("source_id", source.id);
  if (knownErr) {
    // Never diff against an unknown set: an empty known-set would re-resolve (and
    // misreport) the entire playlist.
    await supabase.from("mirror_sources").update({ error: "known-read-failed", last_checked_at: new Date().toISOString() }).eq("id", source.id);
    return { added: [] as object[], error: "known-read-failed" };
  }
  const knownSet = new Set((known ?? []).map((r: { spotify_id: string }) => r.spotify_id));
  const unknown = read.uris.filter((u) => !knownSet.has(u));
  const fresh = unknown.slice(0, BACKFILL_CAP);

  const added: object[] = [];
  for (const tid of fresh) {
    const meta = await resolveTrack(tid);
    await sleep(TRACK_SPACING_MS);
    if (!meta) continue; // retried 3x; next poll rediscovers (still unknown)
    const row = { source_id: source.id, spotify_id: tid, ...meta };
    const { error } = await supabase.from("mirror_tracks").upsert(row, { onConflict: "source_id,spotify_id", ignoreDuplicates: true });
    if (!error) added.push(row);
  }
  // Advance the revision ONLY when every unknown URI got resolved this pass. Otherwise
  // the next poll would see a matching revision and skip, stranding the remainder
  // (BACKFILL_CAP cut or resolve failures) forever.
  const stillUnknown = unknown.length - added.length;
  await supabase.from("mirror_sources").update({
    last_revision: read.revision && stillUnknown === 0 ? read.revision : source.last_revision,
    last_checked_at: new Date().toISOString(),
    error: null,
  }).eq("id", source.id);
  return { added, remaining: stillUnknown };
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
  let q = supabase.from("mirror_sources").select("id,spotify_id,last_revision");
  if (only) q = q.in("id", only);
  const { data: sources, error } = await q;
  if (error) return Response.json({ ok: false, error: error.message }, { status: 500 });

  const perSource: Record<string, unknown> = {};
  let addedTotal = 0;
  for (const s of sources ?? []) {
    const res = await pollSource(s as { id: string; spotify_id: string; last_revision: string | null });
    perSource[(s as { id: string }).id] = res;
    // deno-lint-ignore no-explicit-any
    addedTotal += ((res as any).added ?? []).length;
  }
  // TODO(Phase 2): best-effort FCM data message {source_id, count} per source with additions.
  console.log(`poll-spotify: ${sources?.length ?? 0} sources, ${addedTotal} new rows`);
  return Response.json({ ok: true, sources: perSource, added_total: addedTotal });
}

Deno.serve(handler);
