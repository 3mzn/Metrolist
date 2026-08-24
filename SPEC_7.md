# SPEC 7 — Listen Together, Trivially

Implementation spec for MAYBE_LATER.md feature #7. Replaces the entire Listen Together
join UI with a single partner invite. The LT sync protocol, `ListenTogetherClient`,
`ListenTogetherManager`, and metroproto are **untouched** — only room discovery and the
surrounding UI change.

Status of all decisions: **FINAL — nothing here needs further sign-off.**

---

## 0. Locked decisions

| # | Decision |
|---|----------|
| D1 | One button replaces username/room-code join: **"Invite aswini to listen together"** (directional via PartnerResolver). |
| D2 | Expiry: **15 minutes**. Expiry is judged by the RECEIVER's clock: `now − createdAt ≥ 15 min` = expired. There is no `expiresAt` field — `createdAt` alone decides, which makes the check immune to sender/receiver clock skew. |
| D3 | Decline is an **explicit Decline button**. Decline writes `status: "declined"` on the invite doc (sender sees "aswini declined" toast in real time), then the doc is deleted. Accept writes `status: "accepted"`. |
| D4 | A hidden **manual room-code join survives** (collapsed "advanced" section in the LT tab) for debugging/emulator testing. |
| D5 | Mid-session, the invite button is **disabled** and reads "listening together now · End session". Invites cannot be *sent* during a session. |
| D6 | Incoming invites are **never suppressed by session state**. If the recipient's app is in a stale/in-progress session, tapping Join cleans up (leaves the stale room) and then joins. |
| D7 | LT usernames for create/join are **auto-derived from PartnerResolver** (eman/aswini). The old username preference is ignored for invite flows. |
| D8 | **Inviter is host.** Both participants can add songs to the queue: suggestion **auto-approve is forced ON** for invite-based sessions regardless of the global setting (global setting itself untouched). |
| D9 | Sent invite shows "Invite sent · waiting… **[Cancel]**". Cancel deletes the doc; the recipient's banner disappears. |
| D10 | After a successful join, **both phones navigate to the LT screen** showing the live session. |
| D11 | Edge cases: expired tap → "invite expired" toast · simultaneous invites → doc-per-recipient makes newest-write win, loser gets a toast · host vanished before join → "the session ended" toast · failed join (server unreachable) → **invite survives until expiry** so Join can be retried. |
| D12 | Mutual-invite collision: accepting an incoming invite **auto-cancels your own pending outgoing invite** (and your outgoing being accepted clears any incoming banner). Two parallel sessions are impossible. |
| D13 | Delivery by app state: **foreground** → app-wide pop-up banner (Join/Decline) + LT tab badge · **backgrounded (process alive)** → system notification, tap opens the app DIRECTLY into the join UI (no banner) · **fully closed** → 15-min WorkManager poll posts the notification (Android's hard floor; live delivery in normal use is seconds via the Firestore listener) → on next manual open, an unexpired invite waits in the LT tab. |
| D14 | Force stop is OS-controlled: no work of any kind runs until manual reopen. Documented, not fought. |
| D15 | All strings in `metrolist_strings.xml` with `%1$s` placeholders for names. |
| D16 | Three commits, each compiling green (§5). |

---

## 1. Firestore: `invites` collection

One document per RECIPIENT: `invites/{recipientUid}` — last-write-wins for simultaneous
invites falls out of the doc-id choice for free.

```json
{
  "roomCode": "ABCD1234",       // from LT RoomCreated event
  "fromUid": "<sender uid>",
  "fromName": "eman",           // PartnerResolver display name
  "createdAt": 1756000000000,   // sender clock, ms
  "status": "pending"           // "pending" -> "accepted" | "declined"
}
```

Lifecycle:
- **send**: sender `set()`s the doc (overwrites any previous invite to the same person).
- **accept**: recipient updates `status: "accepted"`, joins the room, deletes the doc.
- **decline**: recipient updates `status: "declined"`, deletes the doc.
- **cancel**: sender deletes the doc.
- **expiry**: no server action. Readers treat `now − createdAt ≥ 15 min` as expired and
  delete opportunistically (recipient on open/poll; sender after expiry + grace).

### Rules addition (firestore.rules)

```text
match /invites/{recipientUid} {
  function isInviteParty() {
    return request.auth != null
      && (request.auth.uid == recipientUid
          || request.auth.uid == resource.data.fromUid);
  }
  allow read: if isInviteParty();
  allow create: if request.auth != null
    && request.auth.uid == request.resource.data.fromUid
    && request.resource.data.roomCode is string
    && request.resource.data.fromName is string
    && request.resource.data.createdAt is number
    && request.resource.data.status == "pending";
  // Recipient flips status (accepted/declined); key fields immutable.
  allow update: if isInviteParty()
    && request.resource.data.fromUid == resource.data.fromUid
    && request.resource.data.roomCode == resource.data.roomCode;
  // Recipient declines/accepts-cleanup; sender cancels.
  allow delete: if isInviteParty();
}
```

**DEPLOY REQUIRED** (`firebase deploy --only firestore:rules`) — unlike feature #13, the
existing deployed rules do NOT cover this collection. No composite indexes needed
(doc-id lookups and single-collection listeners only).

---

## 2. Phase 1 — Data layer (commit ① `feat(lt-invite): invite repository and firestore rules`) — ✅ COMPLETE

Shipped as `fa109d87c`. Model + repository + rules block implemented as specified below.

**Files:**
- `firestore.rules` — add the `invites` match block (§1).
- NEW `social/ListenTogetherInviteModels.kt`:
  ```kotlin
  data class ListenTogetherInvite(
      val roomCode: String,
      val fromUid: String,
      val fromName: String,
      val createdAt: Long,
      val status: String,            // "pending" | "accepted" | "declined"
  ) {
      fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
          now - createdAt >= EXPIRY_MS
      companion object {
          const val EXPIRY_MS = 15 * 60 * 1000L
          const val STATUS_PENDING = "pending"
          const val STATUS_ACCEPTED = "accepted"
          const val STATUS_DECLINED = "declined"
          fun fromMap(map: Map<String, Any?>): ListenTogetherInvite?
      }
  }
  ```
- NEW `social/ListenTogetherInviteRepository.kt` (`@Singleton`, Hilt-injected:
  `FirebaseFirestore`, `FirebaseAuth`, `PartnerResolver`):
  - `suspend fun sendInvite(roomCode: String): Result` — writes `invites/{partnerUid}`
    (partnerUid via `partnerResolver.awaitPartnerUid()`; fails with a typed error if
    unresolved). Caches "outgoing invite pending, sentAt" in DataStore
    (`LT_OUTGOING_INVITE_SENT_AT`).
  - `fun observeIncomingInvite(): Flow<ListenTogetherInvite?>` — snapshot listener on
    `invites/{myUid}`; emits null for missing doc; **does not filter expiry** (UI/worker
    decide, so "expired but present" states stay observable).
  - `fun observeOutgoingInvite(): Flow<...>` — listener on `invites/{partnerUid}` filtered
    client-side to `fromUid == myUid`; emits status changes so the sender sees
    accepted/declined/deleted in real time.
  - `suspend fun acceptInvite(invite)` — set `status: "accepted"` (best-effort), then
    delete doc. Caller performs the actual LT join.
  - `suspend fun declineInvite(invite)` — set `status: "declined"`, then delete doc.
  - `suspend fun cancelInvite()` — delete `invites/{partnerUid}` (sender side), clear
    DataStore cache.
  - `suspend fun clearMyInvite()` — delete `invites/{myUid}` (recipient-side cleanup).
  - `suspend fun cleanupExpiredInvites()` — delete own incoming invite if expired; delete
    `invites/{partnerUid}` if it is ours and expired. Called on app open and by the worker.
  - Outgoing-state reconciliation: on repository init, if DataStore says outgoing pending
    but the doc is gone or no longer ours → clear cache (covers "declined/cancelled while
    I was dead").

**Verification:** `:app:compileFossDebugKotlin` green. Rules deploy deferred to §6.

---

## 3. Phase 2 — Delivery infrastructure (commit ② `feat(lt-invite): banner, notification and background poll delivery`) — ✅ COMPLETE

Shipped as `057518b54`. Channel/helper, InvitePollWorker, InviteNotifier (with the
foreground→background transition re-notification), lifecycle wiring, and MainActivity
invite-tap routing implemented as specified below. `AppForegroundTracker` gained an
observable `isForegroundFlow` to support the transition detection.

**Files:**
- `utils/SongNotificationHelper.kt` (or NEW `social/InviteNotificationHelper.kt` —
  helper object keeps all channels together; choose SongNotificationHelper for cohesion):
  - `LT_INVITE_CHANNEL_ID = "lt_invites"`, **IMPORTANCE_HIGH** (heads-up), channel +
    description strings.
  - `showInviteNotification(context, fromName)`: fixed id `LT_INVITE_NOTIFICATION_ID =
    2800` (one invite at a time by design; repeats replace). Tap → `MainActivity` intent
    with extra `navigate_to = "listen_together_invite"`, `FLAG_ACTIVITY_NEW_TASK |
    FLAG_ACTIVITY_CLEAR_TOP`, `PendingIntent` immutable. Auto-cancel.
- NEW `social/InvitePollWorker.kt` (`@HiltWorker`, unique name `"lt_invite_poll_worker"`):
  - Periodic **15 minutes** (Android's hard floor — documented in the worker KDoc as the
    dead-process safety net; live delivery is the listener), `NetworkType.CONNECTED`.
  - `doWork()`: logged out → success. Read `invites/{myUid}`; missing/expired/`status !=
    "pending"` → also run `cleanupExpiredInvites()` → success. Unexpired + pending → if
    `AppForegroundTracker.isForeground`, skip posting (the banner is showing; the
    foreground→background transition in InviteNotifier handles later notification) →
    success. Otherwise post the notification **only once per invite**: DataStore
    `LT_LAST_NOTIFIED_INVITE_CREATED_AT`; skip if already notified for this `createdAt`.
    Run `cleanupExpiredInvites()`.
- `social/SongListenedNotificationManager.kt`: add `startInvitePollWorker()` /
  `stopInvitePollWorker()` (24/7 KEEP-policy pattern, same as nudge). Wire start/stop into
  the SAME two lifecycle call sites as the nudge worker (`SongListenedRealTimeNotifier`
  auth listener + `AuthViewModel`).
- `social/InviteNotifier.kt` (NEW, `@Singleton`): owns `observeIncomingInvite()` while the
  process is alive. On each emission:
  - doc present + pending + unexpired:
    - `AppForegroundTracker.isForeground` → emit to the in-app banner StateFlow
      (`val bannerInvite: StateFlow<ListenTogetherInvite?>`) — UI (Phase 3) collects it.
    - else → post the system notification (tap routes to join UI, D13), setting
      `LT_LAST_NOTIFIED_INVITE_CREATED_AT` (shared dedupe with the poll). No banner.
  - doc absent / expired / status != pending → clear banner StateFlow.
  - **Foreground→background transition**: also observe `AppForegroundTracker` — if the app
    leaves the foreground while a live pending unexpired invite is still unanswered, post
    the system notification immediately (same dedupe key), so the user is never more than
    seconds away from a re-notification after backgrounding. No 15-min wait for the poll.
  - Started from `App.initializeSocialFeatures()` next to `PartnerHeartbeatMonitor`.
- `MainActivity.kt`: handle `navigate_to == "listen_together_invite"` — navigate to the LT
  tab with the join UI surfaced (same mechanism as the existing `navigate_to` extras; the
  LT tab reads a `showInviteUi` flag). No banner is shown for this entry path (D13).

**Verification:** compile green; worker visible in `adb shell dumpsys jobscheduler` after
login; notification appears in backgrounded state within seconds of a console-written
invite.

---

## 4. Phase 3 — UI + flow wiring (commit ③ `feat(lt-invite): single-button invite UI replacing the join flow`)

**Files:**
- `ui/screens/settings/integrations/ListenTogetherSettings.kt` (the LT tab screen) —
  restructure the entry section:
   - **Idle**: primary button "Invite aswini to listen together" (directional name;
     disabled with "aswini isn't set up yet" (`lt_invite_partner_missing`) if
     `partnerUid == null`).
  - **Waiting** (outgoing pending, unexpired): "Invite sent · waiting…" + Cancel text
    button → `cancelInvite()`.
  - **Incoming** (bannerInvite != null): inline card "eman wants to listen together —
    [Join] [Decline]" (this is the join UI the notification tap lands on).
  - **In-session**: button disabled, "listening together now · End session" (D5).
  - **Declined/accepted feedback**: collect outgoing-invite status → toast
    "aswini declined" / "aswini joined your session"; clear waiting state.
  - Collapsed "Advanced" section: the existing manual username/room-code dialogs, untouched
    (D4).
- **App-wide banner**: NEW composable `ui/component/InviteBanner.kt` — collects
  `InviteNotifier.bannerInvite`; when non-null, renders a dismissible-overlay card at the
  top of the NavHost scaffold (visible on every screen). Join → join flow below; Decline →
  `declineInvite()`. Auto-hides when the invite expires (timer) or doc clears.
- **Join flow** (shared by banner and LT tab): on Join tap —
  1. If LT client reports any active/stale session → `leaveRoom()`/disconnect first (D6).
  2. `viewModel.joinRoom(invite.roomCode, partnerIdentityName)` (D7).
  3. On join success/failure:
     - success → `acceptInvite()` (status + delete), auto-cancel own outgoing invite (D12),
        navigate to the LT screen (D10 — the joiner navigates immediately; the HOST
        navigates when either the outgoing-invite status flips to "accepted" or the
        existing `UserJoined` socket event fires, whichever is first), force suggestion
        auto-approve ON for this session (D8).
     - failure (server unreachable) → toast "couldn't join — try again", invite NOT
       deleted (D11 retry). "The session ended" toast when the room no longer exists (D11).
- **Invite (send) flow**: on Invite tap —
  1. Guard: in-session → button already disabled; `partnerUid == null` → disabled.
  2. Ensure LT client connected → `createRoom(identityName)` → await `RoomCreated` event
     (timeout ~15 s → toast "couldn't create the session", abort).
  3. `sendInvite(roomCode)` → waiting state.
  4. If an outgoing invite is accepted (she joined) → clear waiting, navigate to LT screen.
  5. If an incoming invite arrives while waiting → keep both visible; accepting ours or
     theirs auto-cancels the other (D12).
- **Tab badge**: bottom-nav `ListenTogether` item shows a dot when
  `bannerInvite != null` (collected in `AppNavigation.kt` where bottom-bar items are built).
- **Auto-approve enforcement**: at invite-session start, set the LT auto-approve preference
  ON (restore prior value when session ends — optional; simplest is set-and-leave with a
  log). Locate the existing preference key in `ListenTogetherClient`/settings.
- **Strings** (`metrolist_strings.xml`): `lt_invite_button`, `lt_invite_waiting`,
   `lt_invite_cancel`, `lt_invite_incoming_title` ("Join %1$s?"), `lt_invite_accept`,
   `lt_invite_decline`, `lt_invite_in_session`, `lt_invite_partner_missing`,
   `lt_invite_declined_toast`,
   `lt_invite_accepted_toast`, `lt_invite_expired_toast`, `lt_invite_busy_toast`,
   `lt_invite_room_gone_toast`, `lt_invite_create_failed_toast`, `lt_invite_join_failed_toast`,
   `lt_invite_notification_title/_body`, `lt_invites_channel_name/_description`,
   `lt_invite_advanced` — ALL with `%1$s` name placeholders where names appear (D15).

**Verification:** full `:app:assembleFossDebug`; two-device checklist in §7.

---

## 5. Commit plan (D16)

| # | Message | Contents |
|---|---------|----------|
| 1 | `feat(lt-invite): invite repository and firestore rules` | §2 — models, repository, rules block |
| 2 | `feat(lt-invite): banner, notification and background poll delivery` | §3 — channel, worker, InviteNotifier, lifecycle wiring, MainActivity routing |
| 3 | `feat(lt-invite): single-button invite UI replacing the join flow` | §4 — LT tab redesign, banner, badge, join/send flows, auto-approve, strings |

Each commit passes `:app:compileFossDebugKotlin`; full `assembleFossDebug` before device
install.

---

## 6. Deployment step (user action)

```bash
firebase deploy --only firestore:rules
```

REQUIRED before any two-device testing of Phase 2/3 — the deployed ruleset has no
`invites` match yet, so every invite write/read fails permission-denied until deployed.

---

## 7. Verification checklist (BOTH phones)

- [ ] eman taps Invite → room created, button flips to "Invite sent · waiting… [Cancel]"
- [ ] aswini (app foreground) → banner on ANY screen + LT tab badge; Join/Decline both work
- [ ] aswini (app backgrounded) → system notification; tap → lands in LT tab join UI, no banner
- [ ] Banner ignored, app backgrounded with invite still pending → notification fires
      immediately (not up to 15 min later)
- [ ] Poll never posts a notification while the app is in the foreground (no double delivery)
- [ ] aswini (app fully closed) → notification within ~15 min (poll); on next open within
      15 min → join UI waiting in LT tab
- [ ] Join → both phones land on the LT screen, playback synced
- [ ] Both sides can add songs to the queue with NO approval prompt (auto-approve forced)
- [ ] Decline → eman gets "aswini declined" toast; his waiting state clears
- [ ] Cancel → aswini's banner disappears immediately
- [ ] Expired invite: tap → "invite expired" toast; banner auto-clears at 15 min
- [ ] Mutual invites → accepting one cancels the other; single session only
- [ ] Join with server unreachable → toast, invite survives, retry works
- [ ] Host leaves before guest joins → guest gets "the session ended" toast
- [ ] Mid-session: invite button disabled ("listening together now · End session")
- [ ] Stale-session Join: cleans up old state, joins cleanly (D6)
- [ ] Hidden manual join still functional under Advanced
- [ ] Firebase console: invite doc created with correct fields; deleted after accept/decline/cancel
- [ ] Free-tier sanity: no listener/worker storms in Firebase usage dashboard after a day

## 8. Risks / notes

- The LT tab screen (`ListenTogetherSettings.kt`) is large (~39 KB) — Phase 3 edits are
  confined to the entry/join section; session UI below it is untouched.
- `ListenTogetherManager` join paths are reused, not modified; the only LT-code touch is
  reading the auto-approve preference key.
- Poll-vs-expiry race: an invite created seconds before a poll may be at ~15 min age when
  the poll reads it — the worker accepts anything `now − createdAt < EXPIRY_MS`, so the
  window is as wide as the expiry itself.
- Clock skew is neutralized by judging expiry on the receiver's clock (D2).
