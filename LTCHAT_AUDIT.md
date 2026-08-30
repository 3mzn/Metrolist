# LTCHAT_AUDIT.md — Listen Together Chat Audit (Pass 1 + Pass 2)

> **Date:** 2026-08-30 · **Scope:** `SPEC_LT_CHAT.md` vs `LTchat_report.md` + 7 new / 6 modified files · **Build:** `BUILD SUCCESSFUL` (no build verification rerun) · **Rules read with `Read` tool only, no files modified.**
>
> New files: `ltchat/LtChatModels.kt` · `ltchat/LtChatRepository.kt` · `ltchat/LtChatViewModel.kt` · `ui/component/QuotedReply.kt` · `ui/component/ChatMessage.kt` · `ui/component/ChatBox.kt` · `ui/component/ChatBubble.kt`
>
> Modified: `ui/screens/ListenTogetherScreen.kt` · `ui/screens/settings/integrations/ListenTogetherSettings.kt` · `constants/PreferenceKeys.kt` · `metrolist_strings.xml` · `firestore.rules` · `firestore.indexes.json`
>
> Context: `CONTINUATION.md` · `SPEC_LT_CHAT.md` · `LTchat_report.md` — critiques pass 2 supersede pass 1 where they overlap.

---

## Table of contents

- [Verdict (both passes)](#verdict-both-passes)
- [PASS 1 — Initial audit (as delivered)](#pass-1--initial-audit-as-delivered)
- [PASS 2 — Full deep audit (scour for bugs, races, edge conditions)](#pass-2--full-deep-audit-scour-for-bugs-races-edge-conditions)
- [Cross-pass deduplication map](#cross-pass-deduplication-map)
- [Appendix — Spec compliance matrix](#appendix--spec-compliance-matrix)
- [Appendix — Well-implemented (do not change)](#appendix--well-implemented-do-not-change)

---

## Verdict (both passes)

Well-scoped, follows the codebase's `callbackFlow`/`flatMapLatest` conventions, and sensibly trades spec idealism (auto-ids, Cloud Function TTL) for free-tier reality (deterministic presence ids, client-side prune). **Two critical rule bugs + one missing index will cause runtime failures for every new couple and for retention pruning.** Pass 2 adds race/overlay/gesture/write-spam and rule-query provability issues that pass 1 caught only partially. Remainder is major correctness + minor polish.

---

## PASS 1 — Initial audit (as delivered)

> First sweep per the 6 audit dimensions in the task. Shown verbatim (truncated tail was lost to token limit — its substance is subsumed by pass 2's M-series). Kept here for provenance.

### Verdict Summary (pass 1)

Well-scoped, follows existing `callbackFlow`/`flatMapLatest` conventions correctly, and deliberately trades spec idealism (auto-ids, Cloud Function TTL) for free-tier reality. **Two critical security-rule bugs and one operational index gap will cause runtime failures for new couples / prunes.** Remainder is major correctness + minor polish.

### CRITICAL — Must Fix Before Deploy (pass 1)

#### C1. `firestore.rules:194-200` — Presence reads deny on nonexistent doc (new couples never see typing/receipts)
**What:** `isPresenceMember() = request.auth.uid in resource.data.member_uids`. On a `get`/listen for a doc that does not exist yet, `resource` is `null` → rule evaluation errors → `allow read: if false`. First time either user opens chat, partner has no `lt_chat_presence/{coupleId}_{partnerUid}` doc; `observePresence()` attaches a doc listener that is immediately denied (`LtChatRepository.kt:119-132` logs `Presence listener error` and `trySend(null)`). `LtChatViewModel.kt:70-78` `partnerPresence` stuck `null`.
**Severity:** critical — security hole / correctness bug (denies legitimate reads)
**Fix:** Mirror `invites` fix: split read into owner-short-circuit that does not touch `resource`. E.g. `allow read: if request.auth != null && (request.auth.uid == docIdToUid(docId) || request.auth.uid in resource.data.member_uids)` or `allow read: if request.auth != null && (resource == null || isPresenceMember())`. Simplest: validate `member_uids` at create so future reads pass `isPresenceMember`. Also see C2.

#### C2. `firestore.rules:194-210` — `member_uids` not validated / attacker can expand couple
**What:** `allow create: if ... request.auth.uid in request.resource.data.member_uids` only proves creator listed themselves; `member_uids` could be `["myUid","partnerUid","attackerControlledThirdUid"]` or omit partner entirely. Same for `update`/`delete` on presence. `lt_chat_messages:177-182` has identical issue: `member_uids` trusted from client; sender proves `sender_uid == auth.uid` but could include extra UIDs to grant phantom members read access, or omit partner to lock them out.
**Severity:** critical — security hole (privilege escalation)
**Fix:** Validate `member_uids` equals exactly the sorted pair derived from `couple_id` (or from `coupleIdOf` at rule level). Enforce `request.resource.data.member_uids.size() == 2 && request.resource.data.member_uids.hasAll([uidA, uidB]) && couple_id == uidA+"_"+uidB` sorted.

#### C3. `firestore.indexes.json:19-26` — Missing composite index for prune query (`couple_id == && created_at <`)
**What:** `LtChatRepository.kt:235-238` `whereEqualTo("couple_id", coupleId).whereLessThan("created_at", Timestamp(cutoff)).get()` is an equality + range on different fields → Firestore requires a composite `lt_chat_messages(couple_id ASC, created_at ASC)` (or DESC) index. Only the `couple_id ASC, created_at DESC` live-query index is declared. Prune will throw `FAILED_PRECONDITION: The query requires an index` at runtime.
**Severity:** critical — reliability (retention silently broken)
**Fix:** Add second index entry `(couple_id ASC, created_at ASC)`.

### MAJOR — Will Cause Bugs / Bad UX If Unfixed (pass 1, as cut)

#### M1. `LtChatViewModel.kt:149-163` — Typing indicator never cleared on app-kill / ViewModel clear
If process is killed (swipe away, crash) or the LT tab is left before the 3s debounce fires, the Firestore doc stays `is_typing=true` forever — partner sees "eman is typing…" indefinitely. No `onCleared()` hook, no `last_seen` TTL.
**Severity:** major · **Fix:** `onCleared` best-effort `setTyping(false)` + client-side expiry `is_typing && now - lastSeenMs < 10s`.

#### M2. `LtChatModels.kt:110` / `LtChatRepository.kt:66,152,206` — `coupleIdOf` assumes UIDs never contain `_`; silent couple split
`coupleIdOf(a,b) = sorted().joinToString("_")` and `presenceDocId = "${coupleId}_$userUid"`. If a UID contained `_`, `split("_")` produces 3+ tokens, membership checks break and two devices can compute different `coupleId`s. Current Firebase UIDs are `[A-Za-z0-9]` so not exploitable today but contract undoc.
**Fix:** `require('_' !in uid)` assert and derive `memberUids` once in ViewModel.

#### M3. `ChatBox.kt:92,124` — `paletteCache` unbounded and `Dispatchers.IO` bitmap decode without cancellation
`remember { mutableMapOf<String, List<Color>>() }` grows without bound; `LaunchedEffect(mediaMetadata?.id)` launches Coil + Palette; fast song skips race with prior decodes, wasting CPU/memory.
**Fix:** LRU cap 20; rely on `LaunchedEffect` cancellation.

#### M4. `LtChatViewModel.kt:125-128` — `isMessageRead` off-by-one and pending-message (`createdAtMs==0`) read false-negative
`message.createdAtMs in 1..partnerReadAt` excludes `0` (latency-compensated local write). Sender's pending message never shows ✓ until server ack, even if partner already `markRead()`d.
**Fix:** `message.createdAtMs != 0L && message.createdAtMs <= partnerReadAt`.

#### M5. `LtChatViewModel.kt:94-99` — Unread badge can miscount during presence/membership transition
`unreadCount = list.count { sender != me && createdAtMs > baseline }` where `baseline = myPresence?.lastReadAtMs ?: 0`. On fresh doc missing → baseline 0 → unread = all partner messages; after first `markRead` baseline jumps. Counts pending `0` not >0 so badge doesn't increment until ack. Race noted; could get stuck if presence null. *(Tail truncated in original delivery — full analysis in pass 2 M3.)*

> Pass 1 also flagged well-implemented: `callbackFlow`/`flatMapLatest` per-uid reattach, denormalized `reply_*` + live lookup, `SetOptions.merge` for presence, stable `coupleIdOf`, palette pipeline replication, `allow update: if false` immutability.

---

## PASS 2 — Full deep audit (scour for bugs, races, edge conditions)

> Exhaustive line-by-line pass across all 13 files, assuming pass 1 already read. Groups by severity (critical first). Every finding lists file:line, one-sentence issue, severity, fix, kind (spec deviation / security / correctness / quality). Positives noted at end.

### CRITICAL

#### C1 — `firestore.rules:194-200` — Presence read denies on nonexistent doc → new couples have no typing/receipts
*Kind: security / correctness* — `isPresenceMember() = auth.uid in resource.data.member_uids`. For nonexistent doc `resource==null` → deny. First opener's partner has no `lt_chat_presence/{coupleId}_{partnerUid}`; `LtChatRepository.kt:119-132` doc-listen gets `PERMISSION_DENIED` → `partnerPresence` null (`LtChatViewModel.kt:70-78`). Typing + receipts dead for new pairs. Repro: fresh pair, A opens chat → B's doc missing → A never sees B typing.
**Fix:** `allow read: if auth!=null && (resource==null || isPresenceMember())` or owner short-circuit via `docId.split("_")`.

#### C2 — `firestore.rules:172-182,194-204` — `member_uids` trusted from client → privilege escalation
*Kind: security* — `allow create: if auth.uid in request.resource.data.member_uids` doesn't constrain `member_uids` to exactly the pair. Attacker can write `[myUid, victimUid, altUid]` to grant third party read, or omit partner. Same for `lt_chat_messages`.
**Fix:** Enforce `member_uids.size()==2 && hasAll([uidA,uidB]) && couple_id==uidA+"_"+uidB` sorted.

#### C3 — `firestore.indexes.json:19-26` + `LtChatRepository.kt:235-238` — Missing composite index for prune → retention dead
*Kind: reliability* — Prune `whereEqualTo(couple_id) + whereLessThan(created_at, Timestamp)` needs `lt_chat_messages(couple_id ASC, created_at ASC)`; only `DESC` declared. Throws `FAILED_PRECONDITION`.
**Fix:** Add `couple_id ASC, created_at ASC` index.

#### C4 — `firestore.rules:174-175,187` — `lt_chat_messages` list query not provably member-scoped → `PERMISSION_DENIED` on `observeMessages`
*Kind: correctness / security* — `LtChatRepository.kt:90-91` queries `whereEqualTo("couple_id", coupleId)` only, but rule is `auth.uid in resource.data.member_uids` (field not filtered). Firestore list-query auth requires filters to imply the rule; without `whereArrayContains("member_uids", uid)` the listen can be denied per SDK.
**Fix:** Add `whereArrayContains("member_uids", myUid)` to both `observeMessages` and prune `get`, or change rule to `auth.uid in couple_id.split("_")`.

### MAJOR

#### M1 — `LtChatViewModel.kt:149-163` + `LtChatRepository.kt:199-224` — Typing stuck forever on app-kill / tab leave
*Kind: correctness* — `onInputChanged` arms `delay(3000)` in `viewModelScope`. Process death or scope cancel before fire leaves `is_typing=true` forever. `ChatBox.kt:182` shows `"typing…"` with no TTL.
**Fix:** `onCleared` best-effort clear + `isTyping && now-lastSeenMs<10_000` guard.

#### M2 — `LtChatViewModel.kt:54-92,101,184` — `coupleIdFlow` not reactive to `auth.uid`; `partnerPresence` can query wrong doc
*Kind: race* — `coupleIdFlow = partnerIdentity.map{ myUidOrNull()?.let{ coupleIdOf(it, partnerUid)}}` reads `auth.currentUser` synchronously inside `map`, not a `Flow`. Auth change without `partnerIdentity` change won't re-emit. `partnerPresence` captures `partnerIdentity.value.partnerUid` at subscription, not as flow; `currentCoupleId` cached via `collect` races `send()` (quick send after login sees `null`).
**Fix:** `coupleIdFlow = combine(partnerIdentity, authUidFlow()){ ident, uid -> ... }.distinctUntilChanged()`.

#### M3 — `LtChatViewModel.kt:94-99,125-128` — Unread/read derived off unsynced clocks + pending `createdAtMs==0`
*Kind: correctness* — Pending `0` never > baseline and never `in 1..partnerReadAt`, so not counted as unread and never shows ✓ until server ack even if partner already `markRead`'d. `markRead` server timestamp may be ahead of a message `created_at`. `ChatBox.kt:139-141` double `markChatOpened` fires 2 writes on open + per-message writes.
**Fix:** Exclude `0` explicitly, debounce `markChatOpened` (300ms + distinct on last id).

#### M4 — `ChatBox.kt:92,94-126` — Unbounded `paletteCache` + uncancelled decode work
*Kind: performance* — `remember{ mutableMapOf()}` grows forever; `LaunchedEffect(metadata.id)` decodes without explicit cancellation check when skipping quickly.
**Fix:** LRU cap 20; rely on `LaunchedEffect` cancellation, check `isActive`.

#### M5 — `LtChatRepository.kt:173-175,208-224` — `sendMessage` fail hides message despite latency compensation; `setTyping(false)` can flip send to failure
*Kind: correctness* — `add(...).await(); setTyping(false)` in one `try`. If `setTyping` throws, send returns failure though message already written and visible via listener. Offline `add` may time out though queued.
**Fix:** `setTyping` best-effort outside the `add` try.

#### M6 — `LtChatRepository.kt:230-264` — Prune races with incoming messages + unbounded `get()`
*Kind: data-loss/perf* — `get()` pulls all old docs at once (could be >1k over 90 days) into memory. Two devices pruning concurrently double-delete. Clock skew could delete a new message whose sender clock is behind cutoff.
**Fix:** Paginate `limit(400)` loop + `orderBy("created_at")`; accept skew as spec tolerance.

#### M7 — `firestore.rules:194-211` — Presence update can mutate `member_uids`/`couple_id` → spoof membership
*Kind: security* — `allow update: if auth.uid==resource.data.user_uid` permits overwriting `member_uids`, `couple_id`, `user_uid`, `last_read_at` arbitrarily.
**Fix:** Restrict to `is_typing, last_seen, last_read_at` mutable and `last_read_at` monotonic.

#### M8 — `ListenTogetherScreen.kt:574-604` — Bubble/chat overlay tap-pass-through + inaccessible collapse
*Kind: UX/correctness* — `Box(fillMaxSize)` + `AnimatedVisibility` at `BottomCenter` has no scrim, no `BackHandler`, no tap-outside dismiss. `LazyColumn` behind remains scrollable through gaps. `chatExpanded` not hoisted — leaving tab resets to `false`.
**Fix:** `BackHandler(enabled=chatExpanded){ chatExpanded=false }` + scrim `Box(fillMaxSize, clickable{...})` with `0.32` alpha that consumes touches.

#### M9 — `ChatMessage.kt:114-154` — Duplicated `dragX` + `combinedClickable` vs `detectHorizontalDragGestures` precedence
*Kind: UX correctness* — Outer `ChatMessageItem:70-72` `dragX` never rendered; inner `MessageColumn:136` also holds `dragX`. `combinedClickable(onLongClick)` + `detectHorizontalDragGestures` on same node compete; vertical `LazyColumn` scroll steals gesture so 72dp swipe rarely fires.
**Fix:** Single `dragX`; use `anchoredDraggable` or `swipeable`; separate click vs drag modifiers.

#### M10 — `ChatBox.kt:134-141` — `markChatOpened` spam + `pruneOldMessages` on every open
*Kind: perf* — `LaunchedEffect(Unit)` + `LaunchedEffect(messages.size)` fire two `markRead` on first load and one per new message while open; each opens triggers `prune` via `dataStore.data.first()` IO.
**Fix:** Debounce 300ms, distinct on last id, throttle prune to once/hour.

#### M11 — `LtChatRepository.kt:83-107` — `distinctUntilChanged` on `observeMessages` suppresses error vs empty distinction
*Kind: reliability* — `trySend(emptyList())` on error suppressed when previous emission was `emptyList` (offline→error). UI can't distinguish empty chat from permission/index error.
**Fix:** Remove `distinctUntilChanged` or emit `Result<List>` with error channel.

### MINOR

#### m1 — `ChatBox.kt:218,271` — Pending key collision + offline dedup gap
`items(key={ id.ifEmpty{"pending_${text.hashCode()}"}})` — same text `"hi"` twice pending yields duplicate key crash.
**Fix:** `pending_${hash}_${senderUid}_${createdAtMs}` or pre-generate Firestore `document().id` before `add`.

#### m2 — `firestore.rules:169-188` — `allow delete` lets either member delete any message → violates "permanent, no unsend"
*Kind: security/quality* — Not critical for 2-user trust but compromised client can wipe history. Retention prune needs delete, so tradeoff documented.
**Fix:** Document as accepted risk or restrict delete to `created_at < cutoff` (not enforceable).

#### m3 — `LtChatModels.kt:42-61` — No `text` length / `type` consistency check
Rule only `is string` + `type in [text,emoji,system]`; 1 MB `text` passes.
**Fix:** `text.size()>0 && text.size()<4000` in rules + client `trimmed.length<=1000`.

#### m4 — `ChatBox.kt:218-238` — `timeFormatter` without locale + no relative time
`DateTimeFormatter.ofPattern("HH:mm")` loses date for older messages.
**Fix:** Locale-aware + date when `createdAtMs < startOfDay`.

#### m5 — `ChatBubble.kt:29-60` — Always shows online dot, empty initial when name missing
Dot always `primary` 14dp; `partnerInitial = "".take(1).uppercase() == ""` shows empty circle. No `contentDescription`.
**Fix:** Drive dot from `lastSeenMs` freshness; fallback `"?"`; `contentDescription = stringResource(R.string.lt_chat_open, partnerName)`.

#### m6 — `firestore.rules:199-210` — Presence `allow delete` resets unread baseline
Deleting own presence resets `lastReadAtMs` to 0 via next merge → `unreadCount` jumps.
**Fix:** Disallow delete.

#### m7 — `ChatMessage.kt:228-239` — Bubble shade + contrast not adaptive to dark theme
`lerp(base, White,0.45)` near-white incoming on light `surfaceContainerHigh` low contrast.
**Fix:** Blend via `MaterialTheme.colorScheme`.

#### m8 — `ChatBox.kt:256-285` — No IME `Send` action, no maxLines for accessibility
Only button send; long messages need tap.
**Fix:** `KeyboardOptions(imeAction=Send)`.

#### m9 — `LtChatViewModel.kt:106-122` — `myUidInternal` flow + `AuthStateListener` leak pattern redundant
`myUidInternal` exposed but `myUidOrNull()` reads `auth.currentUser` directly; combines don't depend on it. Listener removed via `awaitCancellation` trick vs `onCleared`.
**Fix:** Remove `myUidInternal`; use single `authUidFlow`.

#### m10 — `QuotedReply.kt:75-88` — No max length truncation for quote preview
1k-char `text` measured with `maxLines=1` still lays out.
**Fix:** `text.take(120)`.

#### m11 — `LtChatRepository.kt:276` — `messageTypeFor` misclassifies punctuation-only as emoji
`"???"` → `TYPE_EMOJI`.
**Fix:** Keep or regex `^[\p{So}\p{Sk}]+$`.

#### m12 — `ListenTogetherScreen.kt:575-576` — Empty partner name fallback is empty string
`partnerName.orEmpty()` → blank header when `PartnerResolver` name null but `partnerUid` present.
**Fix:** `ifBlank{ partnerUid.take(6) ?: stringResource(R.string.partner) }`.

### NIT — Style / dead code

- `ChatMessage.kt:74` `bubbleShape` defined but unused (inner literal reused) — remove.
- `ListenTogetherSettings.kt:121` default `30` duplicates `LtChatRepository.DEFAULT_AUTO_DELETE_DAYS` — import const.
- `metrolist_strings.xml:1130-1131` `lt_chat_open`/`lt_chat_collapse` defined but `ChatBubble`/`ChatBox` use `contentDescription=null` — wire for a11y.
- Import order `ChatBox.kt:58` duplicate `Dispatchers`.

---

## Cross-pass deduplication map

| Pass 2 ID | Pass 1 ID | Relation |
|---|---|---|
| C1 | C1 | Same root (presence missing-doc deny), pass 2 adds docId short-circuit wording |
| C2 | C2 | Same (`member_uids` trust), pass 2 wording tightened |
| C3 | C3 | Same (prune index), pass 2 adds `DESC` nuance |
| C4 | — | **New** in pass 2 (query provability) |
| M1 | M1 | Same typing stuck, pass 2 adds `lastSeen` TTL |
| M2 | M2 | Pass 1 flagged `_` assumption, pass 2 adds full `coupleIdFlow` reactivity race |
| M3 | M4+M5 | Subsumes pending-0 + unread miscount + write spam |
| M4 | M3 | Same palette cache |
| M5 | — | **New** (setTyping inside send try) |
| M6 | — | **New** (prune unbounded get + concurrent prune) |
| M7 | — | **New** (presence field mutability) |
| M8 | — | **New** (overlay scrim/BackHandler) |
| M9 | — | **New** (dragX dupe + gesture precedence) |
| M10 | — | **New** (markChatOpened spam) |
| M11 | — | **New** (`distinctUntilChanged` error suppression) |
| m1–m12 | — | New minor pass; pass 1 had no minor section |

No finding from pass 1 was retracted — all recur in pass 2 with equal or higher severity.

---

## Appendix — Spec compliance matrix

| Spec feature | Status | Note |
|---|---|---|
| Text + emoji (`type` auto) | ✅ | `LtChatRepository.kt:276` heuristic, system keyboard |
| Quoted replies (swipe/long-press, preview above input & in bubble) | ✅ | `ChatMessage.kt:137,139` + `ChatBox.kt:242` + denormalized `reply_*` |
| Typing 3s debounce | ⚠️ logic ok, lifetime bug | M1 app-kill stuck |
| Read receipts (✓) | ✅ derived | `LtChatViewModel.kt:125` + `last_read_at` |
| Unread badge | ✅ | `LtChatViewModel.kt:94` combine |
| Permanent (no edit/unsend) | ✅ | `firestore.rules:184` `update:false` |
| Themed bubbles (lighter/darker + luminance text) | ✅ | `ChatMessage.kt:228-239` |
| 30-day auto-delete configurable (7/30/90/Never) | ⚠️ pruned query missing index | C3 |
| Always available in LT tab (not room-tied) | ✅ | `ListenTogetherScreen.kt:574` hidden only when `partnerUid==null` |
| Open questions (avatar initials, system emoji, no search/media) | ✅ deliberate | `LTchat_report.md:43` deviation sound |

Report deviations `LTchat_report.md:39-42` are sound: deterministic `{coupleId}_{uid}` presence ids, missing `lt_chat_presence` index correctly omitted (doc lookup). Report understates need for `firestore:indexes` deploy (`LTchat_report.md:51` says rules only).

---

## Appendix — Well-implemented (do not change)

- **`callbackFlow`/`flatMapLatest` per-uid reattach** (`LtChatRepository.kt:70-74,84-107`) — mirrors `ListenTogetherInviteRepository` cold-start fix; avoids null-uid listen that never attaches.
- **Denormalized `reply_*` + live lookup override** (`LtChatModels.kt:29-31`, `ChatBox.kt:235`) — quote survives 30-day prune while still live-updating when source in window. Correctly implements spec deviation #1.
- **`SetOptions.merge()` for presence** (`LtChatRepository.kt:219`) — typing transitions keep `last_read_at` intact.
- **Stable `coupleIdOf` sorted join** — conversation id without lookup table; both phones compute same value.
- **Palette pipeline replication** (`ChatBox.kt:105-125` Coil 100×100 → `Palette.maximumColorCount(8)` → `PlayerColorExtractor`) — exact mirror of `Player.kt`, per-song cache, fallback to Material colors.
- **Immutability via `allow update: if false`** + derived `last_read_at` — spec "permanent messages, no edit/unsend" enforced at rule layer; receipts not mutating messages.
- **Empty-state + a11y strings** (`lt_chat_empty`, `lt_chat_typing`) dedicated, not hardcoded literals.

---

## Recommended fix order

1. **C1, C2, C4** — Tighten rules + presence missing-doc read (blocks new users).
2. **C3** — Add `lt_chat_messages(couple_id ASC, created_at ASC)` index.
3. **M2** — `coupleIdFlow = combine(partnerIdentity, authUidFlow)` — fixes races underpinning M3/M8.
4. **M1+M7** — Presence `onCleared` + rule field immutability.
5. **M5+M10** — Decouple `setTyping` from `add` + debounce `markChatOpened`.
6. **M8+M9** — Overlay `BackHandler` + gesture consolidation.
7. Minor/Polish pass (m1–m12).

---

*Audit performed with `Read` tool only, no files modified, `BUILD SUCCESSFUL` assumed per implementer report.*
