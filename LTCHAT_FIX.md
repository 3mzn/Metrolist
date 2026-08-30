# LTCHAT_FIX — Bugfix Spec (from LTCHAT_AUDIT.md)

> Organized into dependency-ordered phases. Each phase lists what to fix, why, and the exact
> file:line references from the audit. Fix top-to-bottom; later phases depend on earlier ones.
> Do NOT modify files outside the listed scope per phase. Build after each phase:
> `cd C:\musicapp\metrolist && ./gradlew :app:compileFossDebugKotlin -q && echo COMPILE_OK`.

---

## Phase 1 — Security & Data Layer (CRITICAL)

> These MUST be fixed and rules+indexes deployed before anything else is safe to test.
> No point fixing app logic if the data layer rejects legitimate operations.

### F1.1 — Presence read denies on nonexistent doc (C1)
- **Files:** `firestore.rules` (presence `allow read`)
- **What:** `isPresenceMember() = auth.uid in resource.data.member_uids` errors when `resource` is null (doc doesn't exist yet). First-time chat users get `PERMISSION_DENIED` on the presence listener → typing + read receipts dead.
- **Fix:** Change presence read rule to `allow read: if request.auth != null && (resource == null || isPresenceMember())`.
- **After fix:** deploy rules.

### F1.2 — Validate `member_uids` in messages + presence rules (C2)
- **Files:** `firestore.rules` (messages `allow create`, presence `allow create`)
- **What:** `member_uids` is trusted from client. Attacker can inject extra UIDs (grant third party read) or omit partner (lock them out).
- **Fix:** In both create rules, enforce:
  - `request.resource.data.member_uids.size() == 2`
  - `request.resource.data.member_uids.hasAll([uidA, uidB])` where `uidA, uidB` are derived from `couple_id` split
  - `couple_id == uidA + "_" + uidB` (sorted)
- **After fix:** deploy rules.

### F1.3 — Add composite index for prune query (C3)
- **Files:** `firestore.indexes.json`
- **What:** `LtChatRepository.kt:235-238` runs `whereEqualTo("couple_id", ...).whereLessThan("created_at", ...)`. Only `couple_id ASC, created_at DESC` index exists. Prune throws `FAILED_PRECONDITION`.
- **Fix:** Add index `(couple_id ASC, created_at ASC)` to `lt_chat_messages`.
- **After fix:** deploy indexes.

### F1.4 — Make `observeMessages` query member-scoped (C4)
- **Files:** `LtChatRepository.kt:90-91`, `LtChatRepository.kt:235-238` (prune)
- **What:** Query filters `couple_id` only, but rule requires `auth.uid in member_uids`. Firestore can't prove the query is member-scoped → listen may be denied.
- **Fix:** Add `.whereArrayContains("member_uids", myUid)` to both `observeMessages` and the prune query.
- **After fix:** deploy rules (if changed). Combined with F1.2 + F1.3.

---

## Phase 2 — Data Integrity & Reliability (MAJOR)

> Fixes data corruption, loss, and silent failures. Depends on Phase 1 index + rules being deployed.

### F2.1 — Prune pagination + race safety (M6)
- **Files:** `LtChatRepository.kt:230-264`
- **What:** `get()` pulls ALL old docs at once (1000+ over 90 days → OOM risk). Two devices pruning concurrently double-delete.
- **Fix:** Paginate with `.limit(400).orderBy("created_at")` loop. Start from oldest, delete batch, continue until no more match cutoff.
- **Depends on:** F1.3 (index), F1.4 (query member-scope).

### F2.2 — Restrict presence mutable fields (M7)
- **Files:** `firestore.rules` (presence `allow update`)
- **What:** Rule `auth.uid == resource.data.user_uid` allows overwriting `member_uids`, `couple_id`, `user_uid`, `last_read_at`.
- **Fix:** Restrict update to only `is_typing`, `last_seen`, `last_read_at`. Enforce `last_read_at` monotonic (>= previous).
- **Depends on:** F1.2 (member_uids validation).
- **After fix:** deploy rules.

### F2.3 — Decouple `setTyping` from `sendMessage` (M5)
- **Files:** `LtChatRepository.kt:173-175, 208-224`
- **What:** `add(...).await(); setTyping(false)` in one try. If `setTyping` throws, send returns failure despite successful write.
- **Fix:** Wrap `setTyping(false)` in its own try/catch (best-effort), outside the `add` try.

### F2.4 — Error vs empty distinction (M11)
- **Files:** `LtChatRepository.kt:83-107` (`observeMessages`)
- **What:** `trySend(emptyList())` on error is suppressed by `distinctUntilChanged` when previous was already empty. UI can't distinguish "no messages" from "permission denied."
- **Fix:** Remove `distinctUntilChanged` OR emit `Result<List<LtChatMessage>>` with separate error channel.

---

## Phase 3 — Lifecycle & State Management (MAJOR)

> Fixes tied to app lifecycle, auth transitions, and derived state correctness.

### F3.1 — Typing cleanup on app-kill + TTL (M1)
- **Files:** `LtChatViewModel.kt:149-163`, `LtChatRepository.kt:199-224`
- **What:** Process death or scope cancel before 3s debounce fires → `is_typing=true` forever.
- **Fix:** Override `onCleared()` → best-effort `setTyping(false)`. Client-side: show typing only if `is_typing && now - lastSeenMs < 10_000`.

### F3.2 — Reactive `coupleIdFlow` (M2)
- **Files:** `LtChatViewModel.kt:54-92, 101, 184`
- **What:** `coupleIdFlow = partnerIdentity.map{ myUidOrNull()?.let{ coupleIdOf(...) }}` reads `auth.currentUser` synchronously inside `map`, not as a `Flow`. Auth change without `partnerIdentity` change won't re-emit → queries wrong couple.
- **Fix:** `coupleIdFlow = combine(partnerIdentity, authUidFlow()){ ident, uid -> ... }.distinctUntilChanged()`.

### F3.3 — Read/unread off-by-one with pending `createdAtMs==0` (M3)
- **Files:** `LtChatViewModel.kt:94-99, 125-128`
- **What:** Pending `createdAtMs=0` excluded from both unread count and read check. Sender's own message never shows ✓ until server ack; unread badge doesn't count pending.
- **Fix:** Explicit `message.createdAtMs != 0L && message.createdAtMs <= partnerReadAt` for read check. For unread, count pending (sender != me OR createdAtMs == 0).
- **Depends on:** F3.2 (correct coupleId).

### F3.4 — Debounce `markChatOpened` + throttle prune (M10)
- **Files:** `ChatBox.kt:134-141`, `LtChatRepository.kt:230-264`
- **What:** `LaunchedEffect(Unit)` + `LaunchedEffect(messages.size)` fire 2 `markRead` on load + one per new message. Each open triggers prune.
- **Fix:** Debounce `markChatOpened` (300ms + distinct on last message id). Throttle prune to once/hour per client (DataStore timestamp gate).

---

## Phase 4 — UI/UX & Visual Polish (MAJOR + MINOR)

> User-facing fixes. Depends on Phase 1-3 for underlying correctness.

### F4.1 — Palette cache LRU cap (M4)
- **Files:** `ChatBox.kt:92, 94-126`
- **What:** `remember { mutableMapOf<String, List<Color>>() }` grows without bound.
- **Fix:** LRU cap 20 entries. Rely on `LaunchedEffect` cancellation for decode cleanup.

### F4.2 — Overlay scrim + BackHandler (M8)
- **Files:** `ListenTogetherScreen.kt:574-604`, `ChatBox.kt`
- **What:** No scrim behind expanded chat, no back button to close, taps pass through to content behind.
- **Fix:** Add scrim `Box(fillMaxSize, Modifier.clickable{ chatExpanded=false })` with `0.32` alpha. `BackHandler(enabled=chatExpanded){ chatExpanded=false }`.

### F4.3 — Swipe-to-reply gesture consolidation (M9)
- **Files:** `ChatMessage.kt:114-154`
- **What:** Duplicated `dragX` (outer never rendered). `combinedClickable` + `detectHorizontalDragGestures` compete with list scroll → swipe rarely fires.
- **Fix:** Single `dragX`. Use `swipeable` or `anchoredDraggable` for reply gesture. Separate click vs drag modifiers.

### F4.4 — Pending key collision (m1)
- **Files:** `ChatBox.kt:218, 271`
- **What:** `items(key={ id.ifEmpty{"pending_${text.hashCode()}"}})` — same text twice = duplicate key crash.
- **Fix:** Pre-generate Firestore `document().id` before `add()`, or use `pending_${hash}_${senderUid}_${createdAtMs}`.

### F4.5 — Online dot + contentDescription (m5)
- **Files:** `ChatBubble.kt:29-60`
- **What:** Dot always green (never reflects actual online state). Empty circle when name missing. No contentDescription.
- **Fix:** Drive dot from `lastSeenMs` freshness (< 2min = online). Fallback `"?"` initial. Add `contentDescription = stringResource(R.string.lt_chat_open, partnerName)`.

### F4.6 — Timestamp locale + relative time (m4)
- **Files:** `ChatBox.kt:218-238`
- **What:** `DateTimeFormatter.ofPattern("HH:mm")` loses date for older messages.
- **Fix:** Locale-aware. Show date when `createdAtMs < startOfDay`, else time.

### F4.7 — Empty partner name fallback (m12)
- **Files:** `ListenTogetherScreen.kt:575-576`
- **What:** `partnerName.orEmpty()` → blank header when name null but partnerUid present.
- **Fix:** `ifBlank{ partnerUid.take(6) ?: stringResource(R.string.partner) }`.

### F4.8 — IME send action + maxLines (m8)
- **Files:** `ChatBox.kt:256-285`
- **What:** No "Send" on keyboard. No line limit.
- **Fix:** `KeyboardOptions(imeAction=ImeAction.Send)` on input. `maxLines = 4`.

### F4.9 — Quote preview truncation (m10)
- **Files:** `QuotedReply.kt:75-88`
- **What:** 1k-char text still lays out with `maxLines=1`.
- **Fix:** `text.take(120)` in preview.

### F4.10 — messageTypeFor punctuation fix (m11)
- **Files:** `LtChatRepository.kt:276`
- **What:** `"???"` classified as emoji (no letters/digits).
- **Fix:** Require at least one emoji codepoint, or keep as `text` for punctuation-only.

### F4.11 — Message length validation (m3)
- **Files:** `firestore.rules` (messages create), `LtChatRepository.kt` (client)
- **What:** No max length — 1MB message passes.
- **Fix:** Rule: `request.resource.data.text.size() < 4000`. Client: `trimmed.length in 1..1000`.

### F4.12 — Bubble contrast for dark theme (m7)
- **Files:** `ChatMessage.kt:228-239`
- **What:** `lerp(base, White, 0.45)` near-white incoming on light surface = low contrast.
- **Fix:** Blend via `MaterialTheme.colorScheme` for adaptive contrast.

---

## Phase 5 — Nits & Cleanup

> Style, dead code, and discoverability. Zero risk.

### F5.1 — Dead code removal
- `ChatMessage.kt:74` — unused `bubbleShape`, remove.
- `LtChatViewModel.kt:106-122` — redundant `myUidInternal` flow + `AuthStateListener` leak (use single `authUidFlow`).

### F5.2 — Constant deduplication
- `ListenTogetherSettings.kt:121` — default `30` duplicates `LtChatRepository.DEFAULT_AUTO_DELETE_DAYS`, import the const.

### F5.3 — Accessibility wiring
- `metrolist_strings.xml:1130-1131` — `lt_chat_open`/`lt_chat_collapse` defined but ChatBubble/ChatBox use `contentDescription=null`. Wire them.

### F5.4 — Import cleanup
- `ChatBox.kt:58` — duplicate `Dispatchers` import, remove.

### F5.5 — Delete rule tradeoff (m2) — DOCUMENT ONLY
- `firestore.rules` (messages `allow delete`) — either member can delete any message, violating "permanent." But prune needs delete. **Do not change.** Add a comment in rules: `// NOTE: delete allowed for auto-delete prune; 2-user trust assumed.`

### F5.6 — Disallow presence delete (m6)
- `firestore.rules` (presence) — deleting own presence resets `lastReadAtMs` to 0 → unread count jumps. Change `allow delete` to `if false`.

---

## Dependency Graph

```
Phase 1 (Security)
  ├─ F1.1 presence read rule
  ├─ F1.2 member_uids validation
  ├─ F1.3 prune index ──────────┐
  └─ F1.4 query member-scope ───┤
                               ▼
Phase 2 (Data Integrity)       │
  ├─ F2.1 prune pagination ◄───┘ (needs F1.3, F1.4)
  ├─ F2.2 presence fields ◄────── (needs F1.2)
  ├─ F2.3 decouple typing
  └─ F2.4 error vs empty
                               ▼
Phase 3 (Lifecycle)            │
  ├─ F3.1 typing onCleared     │
  ├─ F3.2 reactive coupleId ────┤
  ├─ F3.3 read/unread fix ◄─────┘ (needs F3.2)
  └─ F3.4 debounce markRead
                               ▼
Phase 4 (UI/UX)                │
  ├─ F4.1 palette cache        │
  ├─ F4.2 scrim + BackHandler  │
  ├─ F4.3 swipe gesture        │
  └─ F4.4-F4.12 polish

Phase 5 (Nits) — independent, anytime
```

## Deploy checklist (after Phase 1 + 2)
- [ ] `firebase deploy --only firestore:rules`
- [ ] `firebase deploy --only firestore:indexes`
- [ ] Verify `lt_chat_messages(couple_id ASC, created_at ASC)` index created
- [ ] Test new couple: both users open chat → no `PERMISSION_DENIED` in logcat
