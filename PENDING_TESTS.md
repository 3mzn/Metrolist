# PENDING_TESTS.md

Each section explains what the feature does and how to test it in plain steps. **7** holds the
checklist for `SPEC_7.md:7` live branches (verified on 1–2 devices), **4** is the `PENDINGTESTS.md`
one-click-backdate recipe already shipped and re-checked. No open blockers remain; add a new
section here only for a future `MAYBE_LATER.md` item that builds but hasn't been device-proven
yet.

---

## "Us" playlists (feature #8 / SPEC_8) — first pass 2026-08-26

Test devices: phone `ylwwmn85w4ifb6z9` as eman and MuMu emulator `127.0.0.1:7555` as aswini.
Both ran the same `13.6.3` Foss debug APK.

### Passed

- Share created the partner copy; a 408-song playlist fully populated on aswini.
- Receive, bidirectional rename, add-song, duplicate prevention, and local-song blocking.
- Per-device drag order and independent playback.
- Offline edit recovery after reconnect.
- Symmetric whole-playlist deletion and no unshare option.
- Account-delete survivor path counted as passed by user without destructively deleting an account.

### Failed or partial

- [ ] Bulk receive crashed once. Logcat proves a `playlist_song_map` foreign-key race in
  `SharedPlaylistRepository.addSongLocally`; restart recovered and all 408 songs eventually arrived.
- [ ] Remove-song did not persist to cloud/partner; the song returned locally after 1–2 minutes.
- [ ] The `X new` badge was not observed.
- [ ] Two-person glyph appeared, but the whole-card outline was not clearly visible and did not
  visibly change with the current song palette.
- [x] Cover sync is no longer a requirement. Covers are intentionally independent per device.

Full evidence and the next fix list are in `SPEC_8.md:8`.

---

## Listen Together invites (feature #7 / SPEC_7) — verif. 2026-08-25–26

### Delivery (checked on emulator-5556 + emulator-5554 + phone `ylwwmn85w4ifb6z9`)

* **Foreground:** `10:11:31 Incoming emission: TST4F` → screenshot `verify01_banner.png` banner `eman wants to listen together / Live listening session / Join Reject` on Home; `dumpsys notification` shows `(none)` for `lt_invites` — banner owns delivery.
* **Backgrounded (alive) transition:** `HOME` at `10:14` → `NotificationRecord id=2800 channel=lt_invites importance=4` `when=1787728411650` / `when=1787728494480`; `App in foreground, banner owns delivery` never posted a second notification while foregrounded, dedupe `LT_LAST_NOTIFIED_INVITE_CREATED_AT` held.
* **Fully closed — poll:** kill `16792` → `am kill` schedulable dead (`STILL DEAD`), jobs `cb2c75b` `TIME=+10m` resurrect → `verify_t5d.txt` at `10:31:46 RESURRECTED pid: 17810` `when=1787729228359`. 30 min expiry (`EXPIRY_MS = 30 * 60 * 1000L`) gives the poll two cycles.
* **Fully closed — reopen:** `monkey -p com.metrolist.music.debug` at `10:32` → `verify_reopen.png` banner `eman wants to listen together` on Home over the content.
* **Cleanup:** `DELETE invites/EuM3KTt...` → `Incoming emission: null` → `verify_clean.png` clean Home. 15→30 fix diagnosed from `t4a_final.txt` at `00:54:05 STILL DEAD / age 16.0`; built `assembleFossDebug` + installed both emulators + phone `10:48:10`.

Build: `13.6.3` `app-foss-debug.apk` installed `emulator-5554`/`emulator-5556`/`ylwwmn85w4ifb6z9` `10:48:10`. Tap path navigation verified: `MainActivity.kt:241 EXTRA_LT_INVITE_TAP` + `InviteBanner.kt` `Join/Reject`.

---

## Gentle Nudge (feature #4) — ✅ TESTED

### What it does

If a song sits unlistened for 3 days after being sent, both phones get one soft notification
per day:

- eman's phone: "aswini hasn't gotten to 'song name' yet"
- aswini's phone: "You haven't listened to 'song name' (sent by eman)"

Rules it must obey: max ONE notification per day, max 2 reminders per song ever, no reminder
while the partner is actively listening, no reminder about a song that is playing right now,
and the "you haven't listened" reminder stays quiet while that person has the app open.

### How to test (no waiting required)

The app compares the song's sent-date with today's clock, so we just lie about the date.

1. **Send a song** to aswini like normal.
2. **Open the Firebase console** → Firestore Database → `sentSongs` → click the document for
   that song.
3. **Edit the `sentAt` field** so the song looks 4 days old:
   - In your browser press F12, open the Console tab, type `Date.now() - 4*24*60*60*1000`
     and press Enter. Copy the number it prints.
   - Paste that number into `sentAt` in the Firebase console.
   - IMPORTANT: it must be saved as a **number**, not text. If the field shows "string",
     change the field type to number.
4. **Also check these fields in the same document:** `listenedAt` and `completedAt` should be
   deleted (or empty). `nudgeCount` should be 0 (or deleted). `lastNudgedAt` deleted.
5. **Pause/close music on both phones** — the reminder stays silent if someone is actively
   listening.
6. **Install the app and log in.** The check runs on its own within a few minutes of login.
   To watch it live, connect the phone to a computer and run
   `adb logcat | Select-String GentleNudge`.
7. **Expected result:** eman's phone shows "aswini hasn't gotten to '…' yet" and aswini's
   phone shows "You haven't listened to '…' (sent by eman)". The song's document now has
   `nudgeCount: 1` and a `lastNudgedAt` date.

### Things to check one by one

| What to verify | How |
|---|---|
| Reminder fires | Steps above → notification appears on both phones |
| Only once per day | Force-close and reopen the app the same day → nothing new (log says "Already nudged today") |
| Max 2 reminders per song | In Firebase set `nudgeCount` back to 0, clear the app's storage (App info → Clear data), log in again → second reminder. Repeat once more → no third reminder, ever |
| Quiet while partner listens | With both phones playing music when the check runs → no notification that day |
| Quiet about a song that's playing | Play the stale song on your own phone when the check runs → no reminder about that song |
| "You haven't listened" is quiet while her app is open | On aswini's phone: open the Metrolist app and leave it on screen when the check runs → she gets no reminder, but eman still gets his |
| "She hasn't listened" fires even with app open | Same test as above → eman's notification appears anyway |
| Multiple stale songs = one message | Make 2 songs stale → a single notification: "…'X' and 1 more yet" |

### Cleanup after testing

Delete the fake stale song documents from Firebase, otherwise the reminders fire for real.

### Status

- [x] Reminder fires on both phones
- [x] Daily rule works
- [x] 2-reminder cap works
- [x] Quiet while partner listens
- [x] Quiet about currently-playing song
- [x] Receiver reminder quiet while her app is open
- [x] Sender reminder fires even with app open
- [x] Multi-song message batches into one notification
