# SPEC 13 — Partner Mode: eman & aswini

Implementation spec for MAYBE_LATER.md feature #13, extended with two additions
requested on 2026-08-23: **directional labels** (each phone sees the *other*
person's name) and **account deletion** from the Social screen.

Status of all decisions: **FINAL — nothing here needs further sign-off.**

---

## 0. Locked decisions

| # | Decision |
|---|----------|
| D1 | Two identities exist: `eman` and `aswini`. Full lowercase, always. |
| D2 | Labels are **directional**: on eman's phone the received-songs playlist reads `From aswini`; on aswini's phone it reads `From eman`. Mechanism: `if myName == "eman"` style check centralized in PartnerResolver. |
| D3 | Identity heuristic (primary, instant, offline): `auth.currentUser.email.contains("eman")` → I am eman, partner is aswini; otherwise reversed. Username (from `users/{uid}.username`) becomes authoritative once set. |
| D4 | Signup prefill: email contains `eman` → username field prefilled **eman**; anything else → prefilled **aswini**. Field stays editable. |
| D5 | Sent-songs cleanup on account deletion: **NOT done** (rules say `allow delete: if false`). Docs become harmless orphans (option a). Everything else owned by the user IS wiped. |
| D6 | Friend-request machinery (send/accept/reject/remove) is **parked**: stays compiled, all call sites removed. |
| D7 | Dashboard strip: keep login, profile-setup, profile card, logout, delete-account. Remove friends list, requests card, and navigation to `UserListScreen` / `FriendRequestsScreen`. Screen FILES stay compiled; nav routes are removed. |
| D8 | All user-visible strings live in `metrolist_strings.xml` (AGENTS.md rule). Names are injected at runtime → strings become **format strings** with `%1$s` / `%1$d` placeholders. |
| D9 | Three commits, in order, each compiling green: see §7. |

---

## 1. New component: `social/PartnerResolver.kt`

`@Singleton`, Hilt-injected (`FirebaseAuth`, `FirebaseFirestore`,
`@ApplicationContext` for DataStore access via the existing `context.dataStore`
extension).

```kotlin
@Singleton
class PartnerResolver @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    @ApplicationContext private val context: Context,
) {
    private val _identity = MutableStateFlow(PartnerIdentity())
    val identity: StateFlow<PartnerIdentity> = _identity.asStateFlow()

    companion object {
        const val EMAN = "eman"
        const val ASWINI = "aswini"
        private const val PARTNER_UID_KEY = "partner_uid_cached"
    }
}

data class PartnerIdentity(
    val myName: String? = null,      // null while logged out
    val partnerName: String? = null, // null while logged out
    val partnerUid: String? = null,  // null until Firestore lookup succeeds
)
```

Behaviour:
1. **Instant phase (no network)** — in `init`, read `auth.currentUser`. If
   logged in: `myEmail.contains("eman")` → `myName = EMAN`, `partnerName =
   ASWINI`, else reversed. Emit immediately. This alone unblocks labels,
   prefill, and playlist naming even before profiles exist.
2. **Authoritative phase (async)** — fetch `users/{myUid}`; if
   `username` equals EMAN or ASWINI, override `myName` and flip `partnerName`.
   Then resolve `partnerUid`: prefer the `users` doc whose `username ==
   partnerName`; fallback = first doc with `uid != mine`. Cache `partnerUid`
   in DataStore (`PARTNER_UID_KEY`) and seed the StateFlow from cache on app
   start so consumers rarely wait on the network.
3. **Invalidation** — register a `FirebaseAuth.AuthStateListener` (same
   pattern as `SongListenedRealTimeNotifier.init`): on logout reset to
   defaults; on login re-run phases 1–2.
4. Convenience: `suspend fun awaitPartnerUid(): String?` (waits for phase 2
   with a timeout) for callers that truly need the UID before acting.

Consumers inject `PartnerResolver` and collect `identity` — never re-implement
the direction check anywhere else.

---

## 2. Strings (`metrolist_strings.xml`) — format-string conversion

Change values (keys stay stable to minimize diff noise):

```xml
<string name="send_to_friends">Send to %1$s</string>
<string name="select_friends_to_send">DELETE THIS KEY</string>
<string name="no_friends_to_send">%1$s isn\\'t set up yet</string>
<plurals name="send_n_songs_to_friends">
    <item quantity="one">Send %1$d song to %2$s</item>
    <item quantity="other">Send %1$d songs to %2$s</item>
</plurals>
<plurals name="send_to_n_friends">DELETE THIS KEY</plurals>
<string name="sent_n_songs_to_friends">Sent %1$d songs to %2$s</string>

<!-- NEW -->
<string name="from_partner_format">From %1$s</string>          <!-- used for the DB row name -->
<string name="delete_account">Delete account</string>
<string name="delete_account_confirm_title">Delete your account?</string>
<string name="delete_account_confirm_message">Your profile, friendships and pending requests will be removed. Songs already delivered stay on %1$s\'s device.</string>
<string name="delete_account_requires_recent_login">For safety, log out and sign back in, then retry deletion.</string>
<string name="delete_account_failed">Couldn\\'t delete the account. Try again.</string>
<string name="delete">Delete</string>
```

Keep `song_listened_fallback_*`, `selected`/`not_selected` removal happens in
the same commit that removes their last code references (D6/D7 cleanup).
`local_songs_cannot_be_sent` and `send_to_friends_failed` stay as-is.

---

## 3. Send-flow collapse

### `ui/dialog/SendToFriendsDialog.kt` — rewrite
New signature: `(songCount: Int, partnerName: String, onDismiss: () -> Unit,
onSend: () -> Unit)`. Body: title = plural `send_n_songs_to_friends(songCount,
partnerName)`; message = `no_friends_to_send(partnerName)` is NOT shown here
(empty-partner is Host's job); single confirm button labelled
`send_to_n_friends` replacement → plain `R.string.send`. Delete
`FriendSelectionItem`, checkbox state, radio icons (~100 lines).

### `ui/dialog/SendToFriendsHost.kt`
Drop `observeRelationships()` + `getAllUsers()` collectors and
`friendProfiles`. Add `val identity by PartnerResolver.identity…` (obtain via
`EntryPoint` addition — add `fun partnerResolver(): PartnerResolver` to the
existing `SocialRepositoryEntryPoint`). Behaviour:
- `identity.partnerUid == null` → render dialog in disabled/error variant
  showing `no_friends_to_send(partnerName)` and no send button.
- Otherwise render confirm dialog; `onSend` launches
  `songSharingRepository.sendSongsToFriends(songsToSend,
  listOf(partnerUid), mapOf(partnerUid to UserProfile(uid=partnerUid,
  username=partnerName)))` — repository method untouched (minimal churn).
Toast strings updated for new placeholders.

### Menu call sites (`SelectionSongsMenu.kt`, `SongMenu.kt`)
`stringResource(R.string.send_to_friends)` gains the name argument from
resolver identity. Nothing else changes — Host already centralizes behaviour.

---

## 4. Social screen: dashboard strip + delete account

### Strip (`SocialScreen.kt` `SocialDashboard`)
KEEP: `ProfileCard`, logout button. REMOVE: friends LazyColumn section,
pending-request counter card, "Add friend" button, nav to `user_list` /
`friend_requests` routes. In `NavigationBuilder.kt` remove those two route
registrations (screen files remain, unreferenced — D7).

### Delete account
Add to `SocialRepository` (it owns these collections):

```kotlin
suspend fun wipeMyCloudData(uid: String) {
    usersCollection.document(uid).delete().await()
    friendsCollection.whereArrayContains("members", uid).get().await()
        .documents.forEach { it.reference.delete().await() }
    friendRequestsCollection.whereEqualTo("fromUid", uid).get().await()
        .documents.forEach { it.reference.delete().await() }
    friendRequestsCollection.whereEqualTo("toUid", uid).get().await()
        .documents.forEach { it.reference.delete().await() }
    // sentSongs deliberately untouched — D5
}
```

UI flow in SocialDashboard: text button under logout → AlertDialog using the
new `delete_account_confirm_*` strings → on confirm launch coroutine:
1. `wipeMyCloudData(uid)`
2. `auth.currentUser!!.delete()` — catch
   `FirebaseAuthRecentLoginRequiredException` → toast
   `delete_account_requires_recent_login`, abort (user stays logged in);
   other failures → `delete_account_failed` toast.
3. On success: clear DataStore `PARTNER_UID_KEY`; auth-state listeners fire
   everywhere and `SocialScreen`'s `when(user == null)` reactively lands on
   `FirebaseLoginScreen`. No manual navigation needed.

Rules check (verified against deployed `firestore.rules`): owner-delete of own
`users` doc ✅, member-delete of `friends` ✅, party-delete of
`friendRequests` ✅. No rules deployment required.

### Signup prefill (`ProfileSetupScreen`)
Locate the username TextField; initial value =
`if (auth.currentUser?.email?.contains("eman") == true) "eman" else "aswini"`
(D4). Editable before save. If the screen reads from a ViewModel default,
inject the heuristic there instead — keep the heuristic in ONE place
(preferably PartnerResolver exposing `suspend/sync fun suggestedMyName()` and
have the screen consume it).

---

## 5. Directional playlist label + backfill

Inject `@ApplicationContext` and `PartnerResolver` into
`SongSharingRepository` (both already `@Singleton`).

In `initializeToListenPlaylist()`:
```kotlin
val label = context.getString(R.string.from_partner_format,
                              partnerResolver.identity.value.partnerName ?: return-with-old-name)
```
- Creation branch: use `label` instead of `"To Listen"` literal.
- Backfill branch (NEW, idempotent): if row exists and
  `row.name != label` → `database.query { update(row.copy(name = label)) }`.
  Runs on every app start via `SongSharingViewModel.init`, so both phones
  converge after login, and old installs migrate automatically. This is a
  DATA fix — no schema change (AGENTS.md-safe).
- If `partnerName` is still null (logged-out edge), skip silently; next
  launch retries.

Seek-restriction code (`Player.kt`, `Thumbnail.kt`, lyrics files,
`PlayerConnection.kt`, `StorageSettings`, `PlaylistMenu`,
`PlaylistScreenMenus`, `SongMenu`) all keys off `TO_LISTEN_PLAYLIST_ID` —
**untouched**, unaffected by the display-name change.

---

## 6. Notification personalization

- `SongNotificationHelper.showNotification(context, sentSong)` — sender name
  comes from `sentSong.fromUsername` (real names once profiles set). Change
  the empty-fallback from `R.string.song_listened_fallback_friend` to the
  resolver's `partnerName` where a resolver instance is reachable
  (`SongListenedRealTimeNotifier` — inject resolver, pass name down).
- `SongListenedMessagingService` (FCM, separate entry point, background
  thread): replace hardcoded `"A friend"` with cached partner name —
  `runBlocking { /* read PARTNER_UID_KEY-side cached name from DataStore */ }`
  is acceptable here; final fallback stays the existing string resource.
  Simplest robust option: store the resolved **partnerName** (not just uid)
  alongside the uid cache in DataStore and read it here.

---

## 7. Commit plan (D9)

| # | Message | Contents |
|---|---------|----------|
| 1 | `feat(partner): add PartnerResolver with directional eman/aswini identity` | PartnerResolver + DataStore keys + auth-listener invalidation + `suggestedMyName()` consumed by ProfileSetupScreen prefill |
| 2 | `feat(social): one-partner send flow, stripped dashboard, account deletion` | Dialog rewrite, Host rewiring, menu labels, dashboard strip + route removal, SocialRepository.wipeMyCloudData + delete-account UI flow, format-string conversion |
| 3 | `feat(social): directional From-label for the shared-songs playlist` | Repository injections, backfill + rename, notification/FCM personalization |

Each commit must pass `./gradlew :app:compileFossDebugKotlin` before commit;
full `assembleFossDebug` before pushing forward to release build.

---

## 8. Verification checklist (BOTH phones)

- [ ] eman phone: Library playlist shows **From aswini**; aswini phone: **From eman**
- [ ] Pre-existing install upgraded: old `To Listen` row renamed on first launch post-login
- [ ] Her signup screen arrives with `aswini` prefilled; his with `eman`
- [ ] Multi-select menu → confirm dialog reads "Send N songs to <partner>"; toast correct
- [ ] Single-song menu send works identically
- [ ] Dashboard: no friends/requests UI; profile card + logout + delete visible
- [ ] Delete account on a throwaway account: users doc gone (Firebase console),
      friendships/requests wiped, sentSongs intact (orphans, per D5), app lands
      back on login screen
- [ ] Stale-session delete attempt → guidance toast, not a crash
- [ ] Received-song notification on each phone shows the OTHER person's name
- [ ] Seek-restriction still active inside the renamed playlist
- [ ] Clear-To-Listen setting still functional
- [ ] Release build signed + installed on both phones

## 9. Risks / notes for the implementer

- `metrolist_strings.xml` tail churn again = expected Weblate merge friction
  on future upstream syncs (accepted).
- Do NOT touch `ListenTogether*`, `PlaybackProgressTracker`, `MusicService`
  hooks, queue `sourcePlaylistId`, or any Room schema.
- `UserListScreen` / `FriendRequestsScreen` / `SocialViewModel` friend-request
  methods become dead code by design (D6/D7) — leave compiling.
- This spec file itself is user-requested documentation (same exception as
  MAYBE_LATER.md to the AGENTS.md markdown rule).
