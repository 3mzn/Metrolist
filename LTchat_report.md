# LT Chat — Implementation Report

LT Chat (SPEC_LT_CHAT) is fully implemented and building.

---

## What was implemented

### New files (7, ~1,285 lines)

| File | Lines | Purpose |
|---|---|---|
| `ltchat/LtChatModels.kt` | 110 | `LtChatMessage`, `LtChatPresence`, `coupleIdOf()` |
| `ltchat/LtChatRepository.kt` | 278 | Firestore CRUD + realtime listeners (callbackFlow/flatMapLatest pattern) |
| `ltchat/LtChatViewModel.kt` | 188 | Hilt ViewModel — message/presence/unread state, typing debounce, read marking |
| `ui/component/QuotedReply.kt` | 103 | Reply preview (accent bar + sender + snippet), reusable in-bubble and above-input |
| `ui/component/ChatMessage.kt` | 239 | Themed bubble, timestamps, read-check, long-press + swipe-right reply |
| `ui/component/ChatBox.kt` | 289 | Expanded panel: header/typing, message list, reply preview, input row |
| `ui/component/ChatBubble.kt` | 76 | Floating bubble: avatar + online dot + unread badge |

### Modified (6)
- **`ListenTogetherScreen.kt`** (+49) — wrapped the sibling LazyColumn + TopAppBar in `Box(fillMaxSize())`; the bubble floats at `BottomEnd` with padding = `windowInsets.calculateBottomPadding() + 16.dp` (clears nav bar **and** mini player — `LocalPlayerAwareWindowInsets` already includes the 64dp player when visible). Chat is **always available** in the tab (not tied to rooms), hidden only while signed out / partner unresolved.
- **`ListenTogetherSettings.kt`** (+55) — "Auto-delete chat messages" integration-setting with a choice dialog (7/30/90 days / Never).
- **`PreferenceKeys.kt`** (+1) — `LtChatAutoDeleteDaysKey` (DataStore int, default 30).
- **`metrolist_strings.xml`** (+13) — all chat strings in the default English file.
- **`firestore.rules`** (+49) — `lt_chat_messages` (create-only, immutable, member-scoped) + `lt_chat_presence` (owner-write, member-read) blocks.
- **`firestore.indexes.json`** (+8) — `lt_chat_messages(couple_id ASC, created_at DESC)` composite index.

## Feature coverage vs spec
- **Text + emoji** — free text, system keyboard; `type` auto-set to `"text"`/`"emoji"` (no letters/digits → emoji).
- **Quoted replies** — long-press **and** swipe-right (72dp threshold, rightward-only so vertical scroll is unaffected); quote denormalized (`reply_to`/`reply_text`/`reply_sender_name`) so previews survive the 30-day prune.
- **Typing indicators** — `is_typing` presence upsert on keystroke, 3s debounce auto-clear.
- **Read receipts** — derived: partner's `last_read_at` ≥ message `createdAt` → ✓ shown. No mutation to immutable messages.
- **Unread badge** — `message.createdAtMs > my last_read_at` count, updates live.
- **Permanent messages** — rules `allow update: if false`; no edit/unsend UI.
- **Themed bubbles** — replicates Player.kt's pipeline exactly (Coil 100×100 → `Palette.maximumColorCount(8)` → `PlayerColorExtractor.extractGradientColors` → per-song cache). Incoming = lighter shade, outgoing = darker shade, luminance-based on-text color. Falls back to Material colors when nothing plays.
- **30-day persistence** — foreground prune runs when the chat opens, honoring the configurable setting (0 = never); batched deletes (400/batch).

## Deviations from spec (deliberate)
1. **`lt_chat_presence` doc-id is deterministic** `{coupleId}_{userUid}` instead of auto-id — needed so each user has exactly one own-doc that can be upserted with merge without a query. This is also why the spec's `lt_chat_presence(couple_id, user_uid)` index isn't needed (doc-id lookups / single-field queries only) — **I did not add it** to the index file. Verify against your deploy workflow.
2. **Empty-state text** and the "is typing…" label use dedicated strings (`lt_chat_empty`, `lt_chat_typing`) rather than hardcoded literals.
3. **Avatar** = initial-letter circle (matches the existing `UserAvatar` style) since `sender_avatar` is written as `null`; **emoji picker** = system keyboard (no custom bottom sheet); **search** = skipped; **media** = text-only. These were the spec's open questions 1/2/3/5 — I chose the zero-dependency answers. Easy to swap later.

## Build result
- `./gradlew :app:assembleFossDebug` → **BUILD SUCCESSFUL** (4m 12s).
- Final incremental `:app:compileFossDebugKotlin` after the clean-up → **BUILD SUCCESSFUL** (1m 30s).
- Only warnings are pre-existing deprecations in `AppUpdateNotifier.kt` (not touched).
- **Not committed / not pushed** (per your rules).

## What needs testing on device
1. **Deploy rules + index** (the messages composite index is a hard requirement for the query — the app will fail reads until it's created; rules must be published). `firebase deploy --only firestore:rules`.
2. Send/receive across both devices (real-time via snapshot listener).
3. Typing indicator, read ✓ on sent messages, unread badge counts/clears.
4. Swipe-right + long-press reply; quote preview in message and above input.
5. Bubble color shifts when the playing song changes; fallback when nothing plays.
6. Auto-delete setting (7/30/90/Never) + prune behavior (backdate a message in the console to test).
7. Chat box opens above the mini player with correct bottom clearance on the Xiaomi.

## Blockers encountered
- **`callbackFlow` type inference** in `observePresence` resolved to `Nothing?` on two sends — fixed with an explicit `callbackFlow<LtChatPresence?>` type argument.
- Missing `height` import in ChatBox — fixed.
- Gradle builds exceed the 30s tool timeout, so I ran them detached and polled logs (final success confirmed).

One heads-up: the spec listed `di/AppModule.kt` as a modification point, but **none was needed** — `@Singleton @Inject constructor` + Hilt's just-in-time binding handles the repository, and the existing `FirebaseFirestore`/`FirebaseAuth` providers make it resolvable. `LtChatViewModel` is `@HiltViewModel` and obtained via the codebase's standard `hiltViewModel()`. Add an explicit binder only if you prefer it for discoverability.