# PENDING_TESTS.md

Features that are built but not yet verified on real devices. Each section explains what the
feature does and how to test it in plain steps. Move it to "tested" (or delete it) once both
phones have confirmed it works.

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
