# SPEC_LT_CHAT — Listen Together Chat (Instagram-style DM)

> Plan document. Not yet implemented.

## What it is
A persistent couple chat accessible from the Listen Together tab, replicating basic Instagram DM functionality. Always available (not tied to active rooms), with themed bubbles that shift color based on the currently playing song.

## Core Features

| Feature | Behavior |
|---|---|
| **Text + emoji** | Free-text messages with Unicode emoji |
| **Quoted replies** | Swipe right or long-press a message → reply with quote preview |
| **Typing indicators** | "eman is typing…" shown in real-time |
| **Read receipts** | Small checkmark/avatar on sent messages when read |
| **Unread badge** | Count badge on the floating bubble |
| **Permanent messages** | No edit, no unsend — messages stay forever |
| **No reactions** | Simpler than Instagram — no emoji on messages |
| **Themed bubbles** | Bubble colors match the dynamic song palette |

## UI Design

### Floating bubble (bottom-right corner, above player bar)
- Shows partner's avatar with online indicator
- Unread count badge (red circle, top-right)
- Click → expands to chat box
- Collapse button (X or swipe down) → back to bubble

### Chat box (expanded panel, ~70% screen height)
- Header: partner name + typing indicator
- Message list (scrollable, newest at bottom)
- Each message: avatar + bubble + timestamp
- Quoted reply: small preview above input field
- Input: text field + emoji picker + send button

### Message bubbles
- Incoming (partner): left-aligned, tinted by song palette (lighter shade)
- Outgoing (you): right-aligned, tinted by song palette (darker shade)
- Avatars on incoming messages
- Timestamp below each message
- Reply quote shown as small bordered preview above replied message

## Data Model (Firestore)

### Collection: `lt_chat_messages`
```
Document ID: auto-id
Fields:
  sender_uid: string (Firebase Auth UID)
  sender_name: string
  sender_avatar: string | null (URL)
  text: string
  reply_to: string | null (document ID of referenced message)
  created_at: timestamp (server timestamp)
  type: "text" | "emoji" | "system"
```

### Collection: `lt_chat_presence` (typing indicators)
```
Document ID: auto-id
Fields:
  couple_id: string (sorted pair of UIDs, e.g. "eman_aswini")
  user_uid: string
  is_typing: boolean
  last_seen: timestamp (server timestamp)
```

### Indexes
- `lt_chat_messages(couple_id, created_at)` — for fetching chat history ordered by time
- `lt_chat_presence(couple_id, user_uid)` — for looking up typing state

### Persistence
Messages auto-delete after 30 days (Cloud Function cron or client-side filter on read). Configurable by user.

## Architecture

### New files
- `ltchat/LtChatViewModel.kt` — manages messages, typing, read receipts, Firestore snapshot listeners
- `ltchat/LtChatRepository.kt` — Firestore queries + realtime listeners (follows the same callbackFlow/flatMapLatest pattern as ListenTogetherInviteRepository and SharedPlaylistRepository)
- `ui/components/ChatBubble.kt` — floating bubble composable
- `ui/components/ChatBox.kt` — expanded chat panel
- `ui/components/ChatMessage.kt` — individual message with dynamic theming
- `ui/components/QuotedReply.kt` — reply preview component

### Modified files
- `ListenTogetherScreen.kt` — add ChatBubble overlay (Box wrapper, BottomEnd alignment)
- `di/AppModule.kt` — inject LtChatRepository
- NO new dependencies needed — uses existing Firebase Firestore

### Auth
Uses existing Firebase Auth UIDs (no new auth system needed). Security rules extend the existing firestore.rules pattern already used for invites, sentSongs, and sharedPlaylists.

## Dynamic Theming Integration

The app already extracts palette colors from the current song (used in player + partner widget). The chat will:
1. Observe the existing palette StateFlow
2. Apply lighter shade to incoming bubbles, darker shade to outgoing
3. Update in real-time as the song changes
4. Fall back to default Material 3 colors when no song is playing

## Real-time Flow

1. **Sending:** User types → send → add document to `lt_chat_messages` → Firestore snapshot listener pushes to partner
2. **Receiving:** Partner's client receives snapshot update → adds to message list → updates read receipt
3. **Typing:** User focuses input → update `lt_chat_presence(is_typing=true)` → partner sees indicator → debounce 3s → set false
4. **Read receipts:** When chat box opens / new message viewed → update read status

### Firestore pattern (matches existing codebase)
The app already uses this pattern for invites, sentSongs, and sharedPlaylists:
- `callbackFlow` + `addSnapshotListener` for real-time updates
- `flatMapLatest` to cancel previous listeners when parameters change
- Example: `ListenTogetherInviteRepository.kt` and `SharedPlaylistRepository.kt`

## Implementation Order

1. **Firestore setup** — create collections, indexes, security rules (extend existing firestore.rules)
2. **LtChatRepository** — basic CRUD + snapshot listener (follows existing callbackFlow pattern)
3. **ChatMessage + QuotedReply** — UI components with dynamic theming
4. **ChatBox** — expanded panel with message list + input
5. **ChatBubble** — floating bubble with unread badge
6. **Typing indicators** — presence collection + snapshot listener
7. **Read receipts** — track viewed messages
8. **Integration** — overlay bubble on ListenTogetherScreen (Box wrapper, BottomEnd alignment)
9. **Persistence config** — 30-day auto-delete + user setting

## Open Questions

1. **Avatar source:** Use existing Firebase user profile photos or initials?
2. **Emoji picker:** Custom bottom sheet or system emoji keyboard?
3. **Message search:** Search within chat history (Instagram has this)?
4. **Chat scope:** Always visible in LT tab, or only inside active rooms? (Current plan: always in LT tab)
5. **Media sharing:** Keep text-only for now, or plan for photos/voice later?
