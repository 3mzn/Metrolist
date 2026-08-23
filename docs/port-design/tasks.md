# Implementation Plan: OuterTune to Metrolist Feature Port

## Overview

This implementation plan guides the porting of four major feature sets from OuterTune to Metrolist:
1. **Social Features**: Complete song sharing system with Firebase backend
2. **JSON Playlist Import**: File-based playlist import with YouTube Music matching
3. ~~**Audio Visualizer**: Real-time waveform visualization~~ — **cancelled by the user; not ported**
4. **UI Improvements**: Blur effects, animations, spacing refinements

Both applications share the same architecture (InnerTune forks using Kotlin, MVVM, Compose, Hilt, Room, Media3), enabling direct code adaptation with package namespace migration from `com.dd3boh.outertune` to `com.metrolist`.

## Status legend

- `[x]` done
- `[~]` still required
- `[-]` closed without porting — the decision and its reason are recorded inline

## Tasks

- [x] 1. Set up Firebase integration and configuration
  - Copy `google-services.json` from OuterTune to Metrolist's `app/` directory
  - Add Firebase dependencies to `app/build.gradle.kts`: `firebase-bom`, `firebase-auth`, `firebase-firestore`, `firebase-messaging`
  - Add Google Services plugin to build configuration
  - Initialize Firebase in Application class with Firestore offline persistence enabled
  - _Requirements: 26_

- [x] 2. Implement social features data models and Room database extensions
  - [x] 2.1 Create SentSong data class with Firestore serialization
    - Create `com.metrolist.models.SentSong` with fields: id, songId, songTitle, songArtist, songDuration, thumbnailUrl, albumId, albumName, fromUid, fromUsername, toUid, sentAt, listenedAt, completedAt, notificationSent
    - Implement `toMap()` and `fromMap()` methods for Firestore serialization
    - Add `Serializable` interface for Intent extras
    - _Requirements: 2.1, 2.5_

  - [x] 2.2 Create AddSongResult and FriendSelection models
    - Create `com.metrolist.models.AddSongResult` enum: SUCCESS, DUPLICATE, ERROR
    - Create `com.metrolist.models.FriendSelection` data class for UI state
    - _Requirements: 2.4_

  - [x] 2.3 Extend PlaylistEntity with TO_LISTEN_PLAYLIST_ID constant
    - Add companion object constant: `TO_LISTEN_PLAYLIST_ID = "to_listen_playlist_id"`
    - Add `isEditable: Boolean = true` field to PlaylistEntity if not present
    - Create database migration if needed to add `isEditable` column
    - _Requirements: 28.1, 28.2_

- [x] 3. Implement SongSharingRepository
  - [x] 3.1 Create SongSharingRepository class with dependency injection
    - Create `com.metrolist.social.SongSharingRepository` as `@Singleton`
    - Inject FirebaseFirestore, FirebaseAuth, MusicDatabase
    - Implement initialization logic for "To Listen" playlist
    - _Requirements: 3.1, 4.1_

  - [x] 3.2 Implement sendSongsToFriends method
    - Accept List<MediaMetadata>, friendUids, friendProfiles as parameters
    - Create Firestore documents in `sentSongs` collection for each friend-song pair
    - Implement exponential backoff retry (1s, 2s, 4s) for Firestore writes
    - Return count of successfully sent songs
    - _Requirements: 3.4, 3.5, 3.6_

  - [x] 3.3 Implement incoming song synchronization
    - Implement `observeIncomingSongs()` returning Flow<List<SentSong>>
    - Create Firestore snapshot listener filtering by `toUid == currentUser.uid`
    - Sort by `sentAt` descending
    - Implement `addSongToToListenPlaylist()` with duplicate detection
    - Insert at position 0 (top of playlist)
    - _Requirements: 5.1, 5.2, 5.3, 5.6_

  - [x] 3.4 Implement progress tracking methods
    - Implement `markSongAsListened(sentSongId)` updating `listenedAt` timestamp
    - Implement `markSongAsCompleted(sentSongId, songId)` updating `completedAt` and removing from playlist
    - Implement `getSentSongBySongId(songId)` for playback integration
    - Use `withContext(Dispatchers.IO)` for all database operations
    - _Requirements: 6.5, 6.6, 6.8_

  - [x] 3.5 Implement notification query methods
    - Implement `getListenedSongsNeedingNotification(fromUid, since)` for background worker
    - Implement `observeListenedSongsNeedingNotification(fromUid, since)` for real-time notifications
    - Filter to songs with `listenedAt != null`, `notificationSent == false`, `sentAt > since`
    - Implement `markNotificationSent(sentSongId)`
    - _Requirements: 7.2, 7.3, 8.3_

- [x] 4. Checkpoint - Verify SongSharingRepository compiles and core methods work
  - Ensure all tests pass, ask the user if questions arise.

- [x] 5. Implement PlaybackProgressTracker
  - [x] 5.1 Create PlaybackProgressTracker class with state management
    - Create `com.metrolist.playback.PlaybackProgressTracker` as `@Singleton`
    - Initialize state variables: `currentTrackingSongId`, `currentSentSongId`, `has50PercentTriggered`, `has100PercentTriggered`, `maxProgressReached`, `trackingInitialized`
    - Inject SongSharingRepository and MusicDatabase
    - _Requirements: 6.1_

  - [x] 5.2 Implement media item transition handling
    - Implement `onMediaItemTransition(mediaItem, reason, currentPlaylistId)`
    - Check if `currentPlaylistId == TO_LISTEN_PLAYLIST_ID`
    - Initialize tracking for new media items from "To Listen" playlist
    - Query SentSong document from Firestore using songId
    - Reset flags: `has50PercentTriggered = false`, `has100PercentTriggered = false`, `maxProgressReached = 0f`
    - _Requirements: 6.2, 6.3_

  - [x] 5.3 Implement progress tracking logic
    - Implement `trackProgress(player, scope, currentPlaylistId)` called every 1 second
    - Calculate progress: `(currentPosition / duration) * 100`
    - Update `maxProgressReached` if current progress is higher
    - Trigger 50% milestone: if `maxProgressReached >= 50 && !has50PercentTriggered`
    - Trigger 100% milestone: if `maxProgressReached >= 95 && !has100PercentTriggered`
    - _Requirements: 6.4, 6.5_

  - [x] 5.4 Implement milestone actions
    - On 50% milestone: call `markSongAsListened()`, set `has50PercentTriggered = true`
    - On 100% milestone: call `markSongAsCompleted()` and remove from playlist, set both flags to true
    - Handle playlist position shifting when removing song
    - Implement retry logic if `currentSentSongId` is null
    - _Requirements: 6.6, 6.7, 6.8_

  - [x] 5.5 Integrate with MusicService
    - Add PlaybackProgressTracker injection to MusicService
    - Call `onMediaItemTransition()` in Media3 Player.Listener
    - Call `trackProgress()` every 1 second from playback coroutine
    - Call `cleanup()` when service is destroyed
    - _Requirements: 6.1, 6.9_

- [x] 6. Implement notification system
  - [x] 6.1 Create SongListenedRealTimeNotifier
    - Create `com.metrolist.social.SongListenedRealTimeNotifier` as `@Singleton`
    - Inject SongSharingRepository, FirebaseAuth, NotificationHelper
    - Observe auth state changes: start listener on login, stop on logout
    - Observe `observeListenedSongsNeedingNotification()` Flow
    - Show notifications and mark as sent
    - _Requirements: 7.1, 7.2, 7.3, 7.6_

  - [x] 6.2 Create SongListenedNotificationWorker with Hilt
    - Create `com.metrolist.social.SongListenedNotificationWorker` as `@HiltWorker`
    - Inject SongSharingRepository, FirebaseAuth, NotificationHelper
    - Implement `doWork()`: query listened songs, show notifications, mark as sent
    - Filter to songs sent within last 24 hours
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5_

  - [x] 6.3 Create SongListenedNotificationManager
    - Create `com.metrolist.social.SongListenedNotificationManager` as `@Singleton`
    - Inject WorkManager
    - Implement `startWorker()`: schedule periodic work (15-minute interval, requires network)
    - Implement `stopWorker()`: cancel work by unique name
    - Use `ExistingPeriodicWorkPolicy.KEEP`
    - _Requirements: 8.1, 8.6, 8.7, 8.8_

  - [x] 6.4 Create notification channel and helper methods
    - Add notification channel creation in Application class: ID "song_listened_notifications", name "Friend Listened Notifications", importance DEFAULT
    - Create notification content: title "Friend listened to your song!", message "[fromUsername] listened to [songTitle]"
    - Implement pending intent to navigate to Social screen
    - Add fallback text: "A friend" / "a song you sent" if data missing
    - _Requirements: 9.1, 9.2, 9.3, 9.6, 9.7, 7.4, 7.7, 7.8_

  - [x] 6.5 Wire notification lifecycle to Firebase auth
    - Start SongListenedRealTimeNotifier listener on user login
    - Start SongListenedNotificationManager worker on user login
    - Stop both on user logout
    - _Requirements: 8.6, 8.7_

- [~] 7. Checkpoint - Test social features end-to-end
  - Code complete and building; the send/receive/notify round trip still needs two real accounts
    on-device. Tracked together with 16.1.
  - Ensure all tests pass, ask the user if questions arise.

- [x] 8. Implement UI for sending songs to friends
  - [x] 8.1 Create SendToFriendsDialog composable
    - Create `com.metrolist.ui.dialog.SendToFriendsDialog` composable
    - Display friend list with checkboxes using LazyColumn
    - Show friend profile pictures, usernames
    - Add "Select All" / "Deselect All" buttons
    - Add "Cancel" and "Send" buttons
    - _Requirements: 3.3_

  - [x] 8.2 Integrate dialog with multi-select song menu
    - Add "Send to Friends" menu item to multi-select menu
    - Show SendToFriendsDialog when clicked
    - Pass selected songs to SongSharingRepository on confirm
    - Show success/error toast messages
    - _Requirements: 3.2, 3.7_

- [x] 9. Implement JSON playlist import feature
  - [x] 9.1 Add JSON import UI to Sync screen
    - Add "Import from JSON" button to existing Sync screen
    - Launch file picker with MIME type "application/json" on click
    - Validate file size (max 10 MB)
    - _Requirements: 11.1, 11.2, 11.5, 11.6_

  - [x] 9.2 Create JsonTrack data class and parsing logic
    - Create `com.metrolist.models.JsonTrack` with `@Serializable` annotation
    - Add fields: title (String), artist (String)
    - Implement `toSearchQuery()` returning "$title $artist"
    - Create `parseJsonFile(uri)` using kotlinx.serialization JSON parser with lenient mode
    - _Requirements: 2.2, 12.1, 12.2, 12.3, 12.7_

  - [x] 9.3 Implement playlist name selection dialog
    - Show dialog after successful JSON parsing
    - Display explanation: "If playlist exists, songs will be appended. Otherwise, a new playlist will be created."
    - Add text input field with 100 character limit
    - Add validation: prevent empty names
    - Add "Cancel" and "Import" buttons
    - _Requirements: 13.1, 13.2, 13.3, 13.4, 13.5, 13.6, 13.7_

  - [x] 9.4 Implement YouTube Music matching with retry
    - Create `matchJsonTrack(track)` using YouTube.search() with FILTER_SONG
    - Implement `matchJsonTrackWithRetry(track, maxAttempts=2)` with 1s, 2s delays
    - Return first SongItem from search results or null
    - _Requirements: 14.1, 14.2, 14.3, 14.4, 14.5, 14.7_

  - [x] 9.5 Implement playlist creation and song insertion
    - Query database for playlist by name
    - Create new playlist if not found: `PlaylistEntity(name=userInput, isLocal=true, isEditable=true)`
    - Check for duplicate songs in target playlist before adding
    - Insert matched songs into database and add to playlist
    - Append songs in JSON order
    - _Requirements: 15.1, 15.2, 15.3, 15.4, 15.5, 15.6, 15.7_

  - [x] 9.6 Implement progress tracking and error reporting
    - Update progress StateFlow: `progress = current.toFloat() / total`
    - Update status text: "Matching [current]/[total]: [title] - [artist]"
    - Collect failed imports: track, reason (no match, already in playlist, database error)
    - Show Failed Imports Dialog on completion if failures exist
    - Display each failed track with reason
    - _Requirements: 16.1, 16.2, 16.3, 16.7, 17.1, 17.2, 17.3, 17.4, 17.5, 17.6, 17.7_

  - [x] 9.7 Add startJsonImport to SyncViewModel
    - Create `startJsonImport(uri: Uri, playlistName: String)` method
    - Execute on `viewModelScope` with `Dispatchers.IO`
    - Set sync state to `SyncState.Syncing(progress)` during import
    - Set sync state to `SyncState.Success` on completion
    - Handle errors: SerializationException, IllegalArgumentException, generic exceptions
    - Support cancellation and state persistence
    - _Requirements: 18.1, 18.2, 18.3, 18.4, 18.5, 18.6, 18.7_

- [~] 10. Checkpoint - Test JSON import end-to-end
  - Code complete and building; needs a real JSON file and network YouTube Music lookups on-device.
    Tracked together with 16.2.
  - Ensure all tests pass, ask the user if questions arise.

- [-] 11. Implement audio visualizer
  - **Cancelled by the user: "skip visualizer. dont port it to metrolist."** No visualizer,
    `PlayerBackgroundStyle.VISUALIZER`, or Media3 `AudioProcessor` work was carried over.
    Metrolist keeps its existing player background.
  - [-] 11.1 Create AudioVisualizer composable
    - Create `com.metrolist.ui.component.AudioVisualizer` composable
    - Accept parameters: `audioData: FloatArray`, `modifier: Modifier`, `color: Color`
    - Use Canvas API to render waveform
    - Divide canvas into bars (e.g., 64 bars)
    - Map audio samples to bar heights with normalization (0-1 range)
    - Apply smoothing via exponential moving average
    - _Requirements: 19.1, 19.2, 19.4_

  - [-] 11.2 Integrate Media3 audio processor
    - Create AudioProcessor implementation in MusicService
    - Configure for PCM float output, mono channel
    - Extract audio samples in `process(inputBuffer)`
    - Emit samples to visualizer via StateFlow or callback
    - Apply downsampling to reduce computational load
    - _Requirements: 19.2_

  - [-] 11.3 Add player background style setting
    - Add `PlayerBackgroundStyle` enum to preferences: BLUR, COLOR, VISUALIZER
    - Add UI setting in player settings
    - Implement conditional rendering: show AudioVisualizer when style is VISUALIZER
    - Show blur effect when style is BLUR
    - Show color gradient when style is COLOR
    - Persist preference across restarts
    - _Requirements: 20.1, 20.2, 20.3, 20.4, 20.7_

  - [-] 11.4 Optimize visualizer performance
    - Limit frame rate to 30-60 FPS using frame callback throttling
    - Use hardware-accelerated Canvas when available
    - Release audio processor resources when visualizer disabled
    - Reduce frame rate to 15 FPS in battery saver mode
    - Dispose resources properly on player destruction
    - _Requirements: 19.3, 21.1, 21.2, 21.3, 21.4, 21.5, 21.6, 21.7_

  - [-] 11.5 Ensure visualizer accessibility
    - Apply blur to visualizer background for text readability
    - Ensure player controls remain visible and readable
    - Support both light and dark themes
    - _Requirements: 19.7, 20.5, 20.6_

- [~] 12. Implement UI improvements
  - Scoped by the standing instruction to always respect metrolist's UI style: only changes that
    add something metrolist lacks were ported. Where metrolist already has its own solution, the
    OuterTune version was deliberately not adopted.

  - [-] 12.1 Implement blur effects
    - **Not ported.** Metrolist already has its own blur treatment and the user chose to keep it.
    - Create `BlurredImage` composable using `graphicsLayer` with `RenderEffect.createBlurEffect`
    - Check `Build.VERSION.SDK_INT >= S` for RenderEffect support
    - Implement fallback for Android 8-11: color extraction + gradient background
    - Cache blurred images to avoid redundant processing
    - Apply blur radius 10-25 pixels
    - _Requirements: 22.1, 22.2, 22.3, 22.4, 22.6, 22.7_

  - [-] 12.2 Implement navigation animations
    - **Nothing to port.** Metrolist already animates navigation with directional slide + fade at
      `tween(200)`–`tween(250)`, in `MainActivity.kt` (NavHost) and `NavigationBuilder.kt`.
      Replacing it with OuterTune's 250–350ms `FastOutSlowInEasing` spec would change metrolist's
      existing feel for no gain.
    - Use `AnimatedContent` with `slideInHorizontally` and `slideOutHorizontally`
    - Set animation duration 250-350ms with `FastOutSlowInEasing`
    - Check for reduced motion accessibility setting
    - Use fade transitions instead of slide if reduced motion enabled
    - _Requirements: 23.1, 23.2, 23.3, 23.5_

  - [x] 12.3 Implement button press animations
    - **Done as `ResizableIconButton` press-scale** (`ui/component/IconButton.kt`), which is the only
      button OuterTune actually scales. Written in metrolist's own idiom — `animateFloatAsState` +
      `spring` + `graphicsLayer`, matching `AddToPlaylistDialog` — rather than OuterTune's instant
      `Modifier.scale()`. The ripple state layer is preserved, so accessibility feedback is intact.
      Metrolist's own `IconButton` is left untouched; it is already the better implementation.
    - Create `AnimatedButton` composable with scale animation
    - Use `pointerInput` with `detectTapGestures` for press detection
    - Animate scale from 1.0f to 0.95f on press with 100-150ms duration
    - Use ease-in-out interpolation
    - Apply to playback control buttons
    - Respect Material3 state layer for accessibility
    - _Requirements: 24.1, 24.2, 24.3, 24.4, 24.5, 24.7_

  - [-] 12.4 Apply consistent spacing system
    - **Not ported.** Metrolist has its own layout scale in `constants/Dimensions.kt`
      (`ListItemHeight`, `ListThumbnailSize`, `AppBarHeight`, …) that every screen is built against.
      Introducing a second parallel `Spacing` object would give the app two competing systems, and
      retrofitting every list to OuterTune's numbers would restyle screens that are out of scope.
    - Define Spacing object with Material3 4dp base unit: XXS=2dp, XS=4dp, S=8dp, M=12dp, L=16dp, XL=24dp, XXL=32dp
    - Update list item spacing to 8-16dp
    - Ensure minimum touch target size 48dp for all interactive elements
    - Apply consistent card elevations: 0dp, 1dp, 2dp, 4dp, 8dp
    - Update content padding: 16dp horizontal, 8dp vertical minimum
    - _Requirements: 25.1, 25.2, 25.3, 25.4, 25.6_

- [x] 13. Package namespace migration verification
  - **Verified.** A repo-wide search of `.kt`/`.xml`/`.kts`/`.json`/`.pro` returns zero
    `com.dd3boh.outertune` hits. The only remaining "OuterTune" strings are GPL attribution comments
    (`SyncUtils.kt`, `ThumbnailSnapUtils.kt`, `NetworkConnectivityObserver.kt`, `SongShareModels.kt`,
    `Player.kt`), which must stay, plus the `outertune-social` Firebase project id in `.firebaserc`,
    which is the user's real project name.
  - [x] 13.1 Verify all package declarations updated
    - Search codebase for `com.dd3boh.outertune` references
    - Verify all changed to `com.metrolist`
    - Check import statements, AndroidManifest.xml, build files
    - _Requirements: 1.1, 1.2, 1.5_

  - [x] 13.2 Verify no hardcoded package strings remain
    - Search for string literals containing old package name
    - Update any hardcoded references in code or resources
    - _Requirements: 1.6_

- [x] 14. Firebase security rules configuration
  - [x] 14.1 Create Firestore security rules file
    - **Done.** `firestore.rules` covers `users`, `friends`, `friendRequests` and `sentSongs`:
      sender-only create with required-field type checks, both parties may update, and
      `fromUid`/`toUid`/`songId` are immutable after creation. `firestore.indexes.json` holds the
      four composite indexes. `firebase.json` + `.firebaserc` were added so the pair is deployable
      with one command. Every query in `SocialRepository` and `SongSharingRepository` was traced to
      both a satisfying rule disjunct and a covering index.
    - Create `firestore.rules` file with authentication requirement
    - Allow read access to `sentSongs` WHERE `toUid == request.auth.uid`
    - Allow create access WHERE `fromUid == request.auth.uid`
    - Allow update access WHERE `toUid == request.auth.uid`
    - Validate required fields: songId, songTitle, fromUid, toUid, sentAt
    - Prevent modification of fromUid and toUid after creation
    - _Requirements: 27.1, 27.2, 27.3, 27.4, 27.5, 27.6, 27.7_

  - [~] 14.2 Deploy Firestore rules and create indexes
    - **Deliberately not deployed from this repo.** The rules currently live on the project were
      pasted into the Firebase console by the user and are known to work; `firestore.rules` here is a
      reconstruction of them. Pushing it would overwrite working rules with an unverified file, so the
      deploy is left as an explicit user action:
      `cd metrolist && firebase deploy --only firestore:rules,firestore:indexes`
      (requires the Firebase CLI and an interactive `firebase login`, neither available here).
    - Deploy rules using Firebase CLI or console
    - Create composite index: `toUid ASC, completedAt ASC, sentAt DESC`
    - Create composite index: `fromUid ASC, sentAt DESC`
    - Create composite index: `toUid ASC, songId ASC`
    - _Requirements: 27_

- [x] 15. Listen Together compatibility verification
  - [x] 15.1 Verify no conflicts with Listen Together
    - **Verified by code audit, not by device testing.** Tracking is gated on
      `currentPlaylistId == LP_TO_LISTEN`, and `currentPlaylistId` reads `currentQueue.sourcePlaylistId`,
      which is non-null only for queues built from a local playlist (`PlaylistMenu`,
      `LocalPlaylistScreen`). A guest joining a session is switched to a `YouTubeQueue`, whose
      `sourcePlaylistId` falls back to the `Queue` interface default of `null`, so tracking cannot run
      during a synced session. The two features also share no backend at all: Listen Together is an
      OkHttp WebSocket client, the social feature is Firestore. Channels are distinct —
      `listen_together_channel` vs `song_listened_notifications`.
    - Test that PlaybackProgressTracker doesn't track during Listen Together sessions
    - Verify separate Firestore collections used
    - Test both features can be used independently
    - Verify distinct notification channels
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7_

- [~] 16. Integration testing and final verification
  - Unit-level coverage is in place (14 tests for `PlaybackProgressTracker`, all passing). The
    remaining items below need the app installed on a device with two signed-in accounts.
  - [~] 16.1 Test social features complete workflow
    - Test sending songs to friends
    - Test receiving songs and To Listen playlist creation
    - Test playback progress tracking (50%, 100%)
    - Test real-time and background notifications
    - Test notification channel configuration
    - _Requirements: 3, 4, 5, 6, 7, 8, 9_

  - [~] 16.2 Test JSON import complete workflow
    - Test file selection and parsing
    - Test playlist name input and validation
    - Test YouTube Music matching with retries
    - Test duplicate detection
    - Test progress reporting
    - Test failed imports dialog
    - _Requirements: 11, 12, 13, 14, 15, 16, 17, 18_

  - [-] 16.3 Test audio visualizer
    - Test visualizer rendering with different audio
    - Test player background style switching
    - Test performance on different devices
    - Test resource cleanup
    - _Requirements: 19, 20, 21_

  - [~] 16.4 Test UI improvements
    - Reduced to verifying the `ResizableIconButton` press-scale on-device; 12.1/12.2/12.4 were
      closed without porting, so there is nothing new to test there.
    - Test blur effects on different Android versions
    - Test navigation animations with reduced motion
    - Test button press animations
    - Test spacing consistency
    - _Requirements: 22, 23, 24, 25_

- [~] 17. Final checkpoint - Complete port verification
  - Code-level work is complete and builds: `:app:assembleFossDebug` succeeds and
    `:app:testFossDebugUnitTest` passes 14/14 for `PlaybackProgressTrackerTest`. What remains is
    on-device verification (16.1, 16.2, 16.4) and the deliberate rules deploy (14.2).
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- **Package Migration**: All code copied from OuterTune must have package namespace changed from `com.dd3boh.outertune` to `com.metrolist`
- **Architecture Reuse**: Both apps use identical tech stacks (Kotlin, MVVM, Compose, Hilt, Room, Media3), enabling direct code adaptation
- **Listen Together Safety**: PlaybackProgressTracker must check for Listen Together sessions to prevent conflicts
- **Firebase Setup**: Requires Firebase project with Firestore, Auth, and FCM enabled
- **Testing Strategy**: Use Firebase emulators for local testing before deploying to production
- **Accessibility**: Ensure reduced motion support for animations and readable text on all backgrounds
- **Spec vs. code**: Where this spec and the OuterTune source disagree, the source wins. Notable
  differences the port follows: `TO_LISTEN_PLAYLIST_ID` is `"LP_TO_LISTEN"` (not
  `"to_listen_playlist_id"`), `JsonTrack` lives in `sync/JsonPlaylistModels.kt`, and the social models
  live in `social/SongShareModels.kt` rather than a `models` package.
- **Deliberately not ported**: OuterTune's `SelectionModeFAB` and `SelectableSongItem` have zero usages
  in OuterTune and are both broken — the FAB shares a hardcoded `emptyList()`, and the item calls
  `togglePlayPause()` instead of playing the tapped song. Metrolist's existing multi-select is used
  instead. `models/FriendSelection` was not created separately because `FriendSelection` already
  exists in `social/SongShareModels.kt`.
- **Spotify import**: explicitly out of scope for this port.

## Task Dependency Graph

```json
{
  "waves": [
    {
      "id": 0,
      "tasks": ["1", "2.1", "2.2"]
    },
    {
      "id": 1,
      "tasks": ["2.3", "3.1"]
    },
    {
      "id": 2,
      "tasks": ["3.2", "3.3", "3.4", "3.5"]
    },
    {
      "id": 3,
      "tasks": ["5.1", "5.2"]
    },
    {
      "id": 4,
      "tasks": ["5.3", "5.4", "6.1", "6.2", "6.3"]
    },
    {
      "id": 5,
      "tasks": ["5.5", "6.4", "6.5", "8.1"]
    },
    {
      "id": 6,
      "tasks": ["8.2", "9.1", "9.2"]
    },
    {
      "id": 7,
      "tasks": ["9.3", "9.4", "9.5"]
    },
    {
      "id": 8,
      "tasks": ["9.6", "9.7", "11.1", "11.2"]
    },
    {
      "id": 9,
      "tasks": ["11.3", "11.4", "11.5", "12.1", "12.2", "12.3", "12.4"]
    },
    {
      "id": 10,
      "tasks": ["13.1", "13.2", "14.1"]
    },
    {
      "id": 11,
      "tasks": ["14.2", "15.1"]
    },
    {
      "id": 12,
      "tasks": ["16.1", "16.2", "16.3", "16.4"]
    }
  ]
}
```
