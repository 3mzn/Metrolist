# Requirements Document: OuterTune to Metrolist Feature Port

## Introduction

This document specifies requirements for porting all OuterTune modifications to Metrolist, a different fork of InnerTune. The port involves integrating social features (song sharing system), JSON playlist import functionality, audio visualizer, and UI/UX improvements into Metrolist's existing codebase while respecting its architecture and avoiding conflicts with existing features like Listen Together.

OuterTune (package: `com.dd3boh.outertune`) and Metrolist (package: `com.metrolist`) both derive from InnerTune but have evolved different features. This port aims to bring OuterTune's unique features into Metrolist without breaking Metrolist's existing functionality.

## Glossary

- **OuterTune**: Source application with modifications to be ported
- **Metrolist**: Target application receiving the port
- **InnerTune**: Common ancestor application of both forks
- **Social_System**: Song sharing feature set including friend management, song sharing, and notifications
- **To_Listen_Playlist**: Special immutable playlist that automatically receives songs shared by friends
- **Playback_Progress_Tracker**: Component that monitors song playback progress for shared songs
- **JSON_Import_Feature**: Feature allowing playlist import from JSON files with YouTube Music matching
- **Firebase**: Backend services (Firestore, Auth, Messaging) for social features
- **Audio_Visualizer**: Visual representation of audio waveforms in the player UI
- **Package_Namespace**: Root package identifier (com.dd3boh.outertune vs com.metrolist)
- **Listen_Together**: Existing Metrolist feature for real-time synchronized music listening with friends
- **WorkManager**: Android background task scheduler
- **Firestore_Listener**: Real-time database observer for incoming data changes
- **FCM**: Firebase Cloud Messaging for push notifications
- **Hilt**: Dependency injection framework used in both applications
- **Room_Database**: Local SQLite database abstraction layer
- **Media3**: ExoPlayer-based media playback framework
- **Material3**: Google's Material Design 3 UI framework
- **Compose**: Jetpack Compose declarative UI framework

## Requirements

### Requirement 1: Package Namespace Migration

**User Story:** As a developer, I want all OuterTune code to be migrated to Metrolist's package namespace, so that the code integrates seamlessly into the Metrolist codebase.

#### Acceptance Criteria

1. THE Package_Migration_Tool SHALL rename all package declarations from `com.dd3boh.outertune` to `com.metrolist`
2. THE Package_Migration_Tool SHALL update all import statements to reference `com.metrolist` package
3. THE Package_Migration_Tool SHALL preserve subpackage structure (e.g., `social`, `playback`, `sync`)
4. WHEN a package name collision is detected, THEN THE Package_Migration_Tool SHALL report the conflict with file paths
5. THE Package_Migration_Tool SHALL update AndroidManifest.xml service and activity declarations with new package names
6. THE Package_Migration_Tool SHALL verify no hardcoded package name strings remain in code

### Requirement 2: Social Features - Core Data Models

**User Story:** As a developer, I want social feature data models defined in Metrolist, so that song sharing metadata can be stored and synchronized.

#### Acceptance Criteria

1. THE Social_System SHALL define a `SentSong` data class with fields: songId, songTitle, songArtist, songDuration, thumbnailUrl, fromUid, fromUsername, toUid, sentAt, listenedAt, completedAt, notificationSent
2. THE Social_System SHALL define a `JsonTrack` data class with fields: title, artist
3. THE Social_System SHALL define an `ImportResult` sealed class with variants: Success, Duplicate, Error
4. THE Social_System SHALL define an `AddSongResult` enum with values: SUCCESS, DUPLICATE, ERROR
5. WHEN serializing `SentSong` to Firestore, THE Social_System SHALL convert timestamps to Firestore Timestamp format
6. WHEN deserializing `SentSong` from Firestore, THE Social_System SHALL handle null timestamp fields gracefully

### Requirement 3: Social Features - Friend Management Integration

**User Story:** As a user, I want to send songs to my friends, so that I can share music recommendations.

#### Acceptance Criteria

1. THE Social_System SHALL integrate with Metrolist's existing friend list functionality
2. THE Social_System SHALL display a "Send to Friends" button in the multi-select song menu
3. WHEN the user clicks "Send to Friends", THEN THE Social_System SHALL open a dialog showing all friends with checkboxes
4. WHEN the user selects friends and confirms, THEN THE Social_System SHALL create Firestore documents in `sentSongs` collection for each friend-song combination
5. THE Social_System SHALL include sender's Firebase UID and username in each sent song document
6. WHEN Firebase is unavailable, THEN THE Social_System SHALL show an error message and retry up to 3 times with exponential backoff
7. THE Social_System SHALL support sending multiple songs to multiple friends in a single operation

### Requirement 4: Social Features - To Listen Playlist Creation

**User Story:** As a user, I want a dedicated playlist for songs sent to me, so that I can easily find and play recommendations from friends.

#### Acceptance Criteria

1. THE Social_System SHALL create a playlist with ID `to_listen_playlist_id` on first app launch after user login
2. THE To_Listen_Playlist SHALL have properties: name="To Listen", isEditable=false, isLocal=true, bookmarkedAt=current timestamp
3. THE Social_System SHALL prevent deletion of the To_Listen_Playlist through UI
4. THE Social_System SHALL prevent manual addition or removal of songs from To_Listen_Playlist through UI
5. WHEN a song is received from a friend, THEN THE Social_System SHALL add it to position 0 (top) of To_Listen_Playlist
6. WHEN a duplicate song is received, THEN THE Social_System SHALL skip adding it and mark the Firestore document as completed
7. THE Social_System SHALL persist To_Listen_Playlist across app restarts

### Requirement 5: Social Features - Incoming Song Synchronization

**User Story:** As a user, I want received songs to automatically appear in my To Listen playlist, so that I don't have to manually check for new recommendations.

#### Acceptance Criteria

1. WHEN the user logs into Firebase, THEN THE Social_System SHALL start a Firestore listener for `sentSongs` collection filtered by current user's UID
2. THE Firestore_Listener SHALL observe documents WHERE toUid equals current user's UID
3. THE Firestore_Listener SHALL sort documents by sentAt timestamp in descending order
4. WHEN a new song document is detected, THEN THE Social_System SHALL fetch metadata from YouTube Music if not already in local database
5. WHEN YouTube Music search fails, THEN THE Social_System SHALL retry up to 3 times with delays of 1 second, 2 seconds, 3 seconds
6. WHEN song metadata is obtained, THEN THE Social_System SHALL insert the song into Room_Database and add to To_Listen_Playlist
7. THE Social_System SHALL stop the Firestore_Listener when user logs out
8. WHEN the Firestore_Listener detects an error, THEN THE Social_System SHALL log the error and attempt to restart the listener after 5 seconds

### Requirement 6: Social Features - Playback Progress Tracking

**User Story:** As a user, I want the app to notify my friends when I listen to songs they sent me, so that they know I appreciated their recommendations.

#### Acceptance Criteria

1. THE Playback_Progress_Tracker SHALL register as a listener with the Media3 player in MusicService
2. WHEN a media item transition occurs, THE Playback_Progress_Tracker SHALL verify the current playlist ID equals `to_listen_playlist_id`
3. IF the playlist ID does not match `to_listen_playlist_id`, THEN THE Playback_Progress_Tracker SHALL not track progress
4. WHEN playing from To_Listen_Playlist, THE Playback_Progress_Tracker SHALL calculate progress percentage every 1 second
5. WHEN progress reaches 50 percent AND listenedAt is null, THEN THE Playback_Progress_Tracker SHALL update Firestore document with listenedAt timestamp
6. WHEN progress reaches 100 percent, THEN THE Playback_Progress_Tracker SHALL remove the song from To_Listen_Playlist
7. WHEN removing a song at position N, THE Playback_Progress_Tracker SHALL shift all songs with position greater than N up by 1
8. THE Playback_Progress_Tracker SHALL update Firestore document with completedAt timestamp when song finishes
9. THE Playback_Progress_Tracker SHALL handle repeat mode by only removing song after first complete playback

### Requirement 7: Social Features - Real-time Notification System

**User Story:** As a user, I want to be notified when friends listen to songs I sent them, so that I know my recommendations were heard.

#### Acceptance Criteria

1. WHEN the app is open AND a friend reaches 50 percent playback, THEN THE Social_System SHALL show a local Android notification within 5 seconds
2. THE Social_System SHALL use a Firestore listener to observe `sentSongs` WHERE fromUid equals current user's UID AND listenedAt is not null AND notificationSent is false
3. WHEN a notification is shown, THE Social_System SHALL update the Firestore document with notificationSent=true
4. THE Social_System SHALL create a notification with title "Friend listened to your song!" and message "[Friend Name] listened to [Song Title]"
5. WHEN tapping the notification, THE Social_System SHALL open the app and navigate to the Social screen
6. THE Social_System SHALL filter notifications to only songs sent within the last 30 days
7. WHEN friend name is missing, THE Social_System SHALL display "A friend" as fallback
8. WHEN song title is missing, THE Social_System SHALL display "a song you sent" as fallback

### Requirement 8: Social Features - Background Notification Worker

**User Story:** As a user, I want to receive notifications even when the app is closed, so that I stay informed about friends listening to my recommendations.

#### Acceptance Criteria

1. THE Social_System SHALL register a periodic WorkManager task with 15-minute execution interval
2. THE WorkManager task SHALL only execute when device has internet connectivity
3. WHEN the WorkManager task runs, THE Social_System SHALL query Firestore for listened songs needing notification using same filters as real-time system
4. WHEN listened songs are found, THE Social_System SHALL show local Android notifications for each song
5. THE Social_System SHALL mark each notified song with notificationSent=true in Firestore
6. THE WorkManager task SHALL start automatically when user logs into Firebase
7. THE WorkManager task SHALL stop when user logs out of Firebase
8. THE WorkManager task SHALL use ExistingPeriodicWorkPolicy.KEEP to prevent duplicate workers
9. WHEN the worker encounters an error, THE Social_System SHALL log the error and retry on next scheduled execution

### Requirement 9: Social Features - Notification Channel Configuration

**User Story:** As a user, I want to customize notification settings for friend listening alerts, so that I can control notification behavior.

#### Acceptance Criteria

1. THE Social_System SHALL create a notification channel with ID "song_listened_notifications"
2. THE notification channel SHALL have name "Friend Listened Notifications"
3. THE notification channel SHALL have description "Notifications when friends listen to songs you sent"
4. THE notification channel SHALL have importance level DEFAULT
5. THE Social_System SHALL create the notification channel before showing any notifications
6. THE notification SHALL auto-cancel when tapped
7. THE notification SHALL use a pending intent with FLAG_IMMUTABLE and FLAG_UPDATE_CURRENT

### Requirement 10: Social Features - Listen Together Compatibility

**User Story:** As a developer, I want the song sharing system to coexist with Listen Together, so that both features work without conflicts.

#### Acceptance Criteria

1. THE Social_System SHALL not modify any Listen_Together code or data structures
2. THE Playback_Progress_Tracker SHALL not track progress when user is in a Listen_Together session
3. THE Social_System SHALL use a separate Firestore collection (`sentSongs`) from Listen_Together collections
4. THE Social_System SHALL use distinct notification channel IDs to avoid conflicts with Listen_Together notifications
5. WHEN both features attempt to observe Firebase Auth state, THE Social_System SHALL use separate observers
6. THE Social_System SHALL not interfere with Listen_Together's playback controls or queue management
7. WHEN user is in Listen_Together session AND plays from To_Listen_Playlist, THE Playback_Progress_Tracker SHALL prioritize Listen_Together behavior

### Requirement 11: JSON Playlist Import - File Selection

**User Story:** As a user, I want to select a JSON file from my device, so that I can import playlists from external sources.

#### Acceptance Criteria

1. THE JSON_Import_Feature SHALL add an "Import from JSON" button to the Sync screen
2. WHEN the user clicks "Import from JSON", THEN THE JSON_Import_Feature SHALL launch Android's file picker with MIME type "application/json"
3. WHEN the user selects a file, THEN THE JSON_Import_Feature SHALL read the file content
4. WHEN file reading fails, THEN THE JSON_Import_Feature SHALL show an error message with failure reason
5. THE JSON_Import_Feature SHALL support files up to 10 MB in size
6. WHEN file size exceeds 10 MB, THEN THE JSON_Import_Feature SHALL show an error message "File too large"

### Requirement 12: JSON Playlist Import - JSON Parsing

**User Story:** As a developer, I want to parse JSON playlist files with validation, so that invalid data is rejected gracefully.

#### Acceptance Criteria

1. THE JSON_Import_Feature SHALL parse JSON as an array of track objects using kotlinx.serialization
2. THE JSON_Import_Feature SHALL require each track to have fields: "title" (string) and "artist" (string)
3. THE JSON_Import_Feature SHALL ignore unknown fields in track objects
4. WHEN JSON parsing fails, THEN THE JSON_Import_Feature SHALL show an error message "Invalid JSON format"
5. WHEN JSON array is empty, THEN THE JSON_Import_Feature SHALL show an error message "No tracks found"
6. WHEN a track is missing required fields, THEN THE JSON_Import_Feature SHALL add it to the failed imports list with reason "Missing required fields"
7. THE JSON_Import_Feature SHALL use lenient JSON parsing to tolerate formatting variations

### Requirement 13: JSON Playlist Import - Playlist Name Selection

**User Story:** As a user, I want to specify a playlist name for imported songs, so that I can organize my music library.

#### Acceptance Criteria

1. WHEN JSON parsing succeeds, THEN THE JSON_Import_Feature SHALL show a dialog prompting for playlist name
2. THE dialog SHALL display an explanation: "If playlist exists, songs will be appended. Otherwise, a new playlist will be created."
3. THE dialog SHALL have a text input field for playlist name with character limit of 100
4. THE dialog SHALL have "Cancel" and "Import" buttons
5. WHEN the user clicks "Cancel", THEN THE JSON_Import_Feature SHALL abort the import operation
6. WHEN the user clicks "Import" with empty name, THEN THE JSON_Import_Feature SHALL show validation error "Playlist name required"
7. WHEN the user clicks "Import" with valid name, THEN THE JSON_Import_Feature SHALL proceed to YouTube Music matching

### Requirement 14: JSON Playlist Import - YouTube Music Matching

**User Story:** As a user, I want the app to automatically find songs on YouTube Music, so that I don't have to search manually.

#### Acceptance Criteria

1. THE JSON_Import_Feature SHALL search YouTube Music for each track using query format: "[title] [artist]"
2. THE JSON_Import_Feature SHALL apply search filter: YouTube Music songs only (no videos)
3. THE JSON_Import_Feature SHALL select the first result from search results
4. WHEN no search results are found, THEN THE JSON_Import_Feature SHALL add the track to failed imports list with reason "No match found on YouTube Music"
5. WHEN YouTube Music search returns an error, THEN THE JSON_Import_Feature SHALL add the track to failed imports list with the error message
6. THE JSON_Import_Feature SHALL process tracks sequentially (one at a time)
7. WHEN a song already exists in the library, THE JSON_Import_Feature SHALL reuse the existing database entry

### Requirement 15: JSON Playlist Import - Playlist Creation and Update

**User Story:** As a user, I want imported songs added to a playlist, so that I can listen to them together.

#### Acceptance Criteria

1. THE JSON_Import_Feature SHALL query the database for playlists matching the user-provided name
2. WHEN a playlist with matching name exists, THEN THE JSON_Import_Feature SHALL append songs to the existing playlist
3. WHEN no playlist with matching name exists, THEN THE JSON_Import_Feature SHALL create a new local playlist with properties: name=user input, isLocal=true, isEditable=true, bookmarkedAt=current timestamp
4. THE JSON_Import_Feature SHALL add matched songs to the playlist in the order they appear in the JSON file
5. THE JSON_Import_Feature SHALL insert songs into Room_Database if not already present
6. WHEN database insertion fails, THEN THE JSON_Import_Feature SHALL add the track to failed imports list with the database error message
7. THE JSON_Import_Feature SHALL not download songs (streaming-only by default)

### Requirement 16: JSON Playlist Import - Progress Tracking

**User Story:** As a user, I want to see import progress, so that I know the operation is working and how long it will take.

#### Acceptance Criteria

1. THE JSON_Import_Feature SHALL display a progress bar showing percentage complete (0 to 100 percent)
2. THE JSON_Import_Feature SHALL display status text: "Matching [current count]/[total count]: [song title] - [artist]"
3. THE JSON_Import_Feature SHALL update progress after processing each track
4. THE JSON_Import_Feature SHALL set sync state to "Syncing" during import operation
5. THE JSON_Import_Feature SHALL prevent starting another import while one is in progress
6. THE JSON_Import_Feature SHALL maintain progress state even if user navigates away from Sync screen
7. WHEN import completes, THE JSON_Import_Feature SHALL set sync state to "Success"

### Requirement 17: JSON Playlist Import - Failed Imports Reporting

**User Story:** As a user, I want to see which songs failed to import and why, so that I can manually add them or fix the source data.

#### Acceptance Criteria

1. THE JSON_Import_Feature SHALL maintain a list of failed imports during the import process
2. WHEN import completes AND failed imports exist, THEN THE JSON_Import_Feature SHALL display a "Failed Imports" dialog
3. THE Failed_Imports_Dialog SHALL show each failed track as "[title] - [artist]"
4. THE Failed_Imports_Dialog SHALL show the failure reason below each track
5. THE Failed_Imports_Dialog SHALL have a "Dismiss" button to close the dialog
6. WHEN the user dismisses the dialog, THE JSON_Import_Feature SHALL clear the failed imports list
7. WHEN import completes with zero failures, THE JSON_Import_Feature SHALL not show the Failed_Imports_Dialog

### Requirement 18: JSON Playlist Import - Background Processing

**User Story:** As a user, I want imports to continue even if I leave the screen, so that I can use other app features during long imports.

#### Acceptance Criteria

1. THE JSON_Import_Feature SHALL execute import operations on Dispatchers.IO coroutine context
2. THE JSON_Import_Feature SHALL use viewModelScope for coroutine lifecycle management
3. THE import operation SHALL continue if user navigates to another screen
4. THE import operation SHALL continue if user minimizes the app
5. WHEN user returns to Sync screen, THE JSON_Import_Feature SHALL display current progress state
6. WHEN import is interrupted by app process termination, THE JSON_Import_Feature SHALL not resume automatically (user must restart)
7. THE JSON_Import_Feature SHALL not use wake locks or prevent device sleep

### Requirement 19: Audio Visualizer - Component Implementation

**User Story:** As a user, I want to see audio visualizations in the player, so that I have engaging visual feedback while listening to music.

#### Acceptance Criteria

1. THE Audio_Visualizer SHALL render real-time audio waveform visualization using Canvas API
2. THE Audio_Visualizer SHALL integrate with Media3 audio processor to receive audio sample data
3. THE Audio_Visualizer SHALL display visualization with frame rate between 30 and 60 FPS
4. THE Audio_Visualizer SHALL use Material3 color scheme for visualization colors
5. THE Audio_Visualizer SHALL automatically start visualizing when playback starts
6. THE Audio_Visualizer SHALL automatically stop visualizing when playback pauses or stops
7. THE Audio_Visualizer SHALL support both light and dark themes with appropriate color adjustments

### Requirement 20: Audio Visualizer - Player Integration

**User Story:** As a user, I want the visualizer to appear as a player background option, so that I can choose my preferred player appearance.

#### Acceptance Criteria

1. THE Audio_Visualizer SHALL be added as a player background style option in settings
2. THE player background style options SHALL include: Blur, Color, Visualizer
3. WHEN user selects "Visualizer" style, THEN THE player SHALL display the Audio_Visualizer component behind player controls
4. WHEN user selects a different style, THEN THE Audio_Visualizer SHALL be hidden and resources released
5. THE Audio_Visualizer SHALL not overlap player controls or make text unreadable
6. THE Audio_Visualizer SHALL apply blur effect to improve text readability on visualizer background
7. THE user's player background style preference SHALL persist across app restarts

### Requirement 21: Audio Visualizer - Performance Optimization

**User Story:** As a developer, I want the visualizer to perform efficiently, so that it doesn't drain battery or cause audio stuttering.

#### Acceptance Criteria

1. THE Audio_Visualizer SHALL limit CPU usage to less than 5 percent on typical devices when active
2. THE Audio_Visualizer SHALL not cause audio buffer underruns or playback stuttering
3. THE Audio_Visualizer SHALL release audio processor resources when visualization is disabled
4. THE Audio_Visualizer SHALL use hardware acceleration for rendering when available
5. WHEN device is in battery saver mode, THE Audio_Visualizer SHALL reduce frame rate to 15 FPS
6. THE Audio_Visualizer SHALL use efficient memory allocation patterns to minimize garbage collection
7. THE Audio_Visualizer SHALL dispose of resources properly when player is destroyed

### Requirement 22: UI Improvements - Blur Effects

**User Story:** As a user, I want blur effects on UI components, so that the app has a modern polished appearance.

#### Acceptance Criteria

1. THE UI_System SHALL apply blur effect to player background when blur style is selected
2. THE blur effect SHALL use RenderScript or RenderEffect API with radius between 10 and 25 pixels
3. THE blur effect SHALL preserve image aspect ratio and color accuracy
4. THE blur effect SHALL cache blurred images to avoid redundant processing
5. THE blur effect SHALL update when album artwork changes
6. WHEN RenderScript is unavailable, THE UI_System SHALL fall back to color extraction with gradient background
7. THE blur effect SHALL not introduce visible lag when changing tracks

### Requirement 23: UI Improvements - Navigation Animations

**User Story:** As a user, I want smooth navigation transitions, so that the app feels responsive and polished.

#### Acceptance Criteria

1. THE UI_System SHALL apply slide animations to screen transitions with duration between 250 and 350 milliseconds
2. THE navigation animations SHALL use Material3 motion specifications
3. THE navigation animations SHALL support both forward and backward transitions with appropriate direction
4. THE animations SHALL not delay user interaction beyond animation duration
5. WHEN user has enabled reduced motion accessibility setting, THE UI_System SHALL use fade transitions instead of slide
6. THE animations SHALL not cause dropped frames on typical devices
7. THE UI_System SHALL maintain Material3 shared element transitions where applicable

### Requirement 24: UI Improvements - Button Press Animations

**User Story:** As a user, I want visual feedback when pressing buttons, so that I know my taps are registered.

#### Acceptance Criteria

1. THE UI_System SHALL apply scale animation to button press with scale factor 0.95
2. THE button press animation SHALL have duration between 100 and 150 milliseconds
3. THE button press animation SHALL use ease-in-out interpolation
4. THE animation SHALL trigger on press down and reverse on press release
5. THE animation SHALL apply to playback control buttons (play, pause, skip, previous)
6. THE animation SHALL not interfere with button click event handling
7. THE animation SHALL respect Material3 state layer behavior for accessibility

### Requirement 25: UI Improvements - Spacing and Layout Adjustments

**User Story:** As a user, I want consistent spacing throughout the UI, so that the app looks cohesive and professional.

#### Acceptance Criteria

1. THE UI_System SHALL use Material3 spacing scale (4dp base unit) for all padding and margins
2. THE UI_System SHALL apply consistent spacing between list items (8dp to 16dp)
3. THE UI_System SHALL maintain minimum touch target size of 48dp for all interactive elements
4. THE UI_System SHALL use consistent card elevation (0dp, 1dp, 2dp, 4dp, 8dp)
5. THE UI_System SHALL align text baselines consistently across similar components
6. THE UI_System SHALL apply appropriate content padding (16dp horizontal, 8dp vertical minimum)
7. THE UI_System SHALL adapt spacing for tablet and foldable devices using adaptive layout guidelines

### Requirement 26: Firebase Configuration Integration

**User Story:** As a developer, I want Firebase integrated into Metrolist, so that social features can access cloud services.

#### Acceptance Criteria

1. THE Social_System SHALL use Metrolist's existing Firebase project configuration
2. WHEN Metrolist does not have Firebase configured, THE developer SHALL add google-services.json to app module
3. THE Firebase_SDK SHALL include dependencies: firebase-auth, firebase-firestore, firebase-messaging
4. THE Firebase_SDK SHALL use BoM (Bill of Materials) for version consistency
5. THE Social_System SHALL initialize Firebase in the Application class onCreate method
6. THE Firebase_SDK SHALL enable Firestore offline persistence
7. THE Firebase_SDK SHALL configure Firestore with settings: sslEnabled=true, cacheSizeBytes=100MB

### Requirement 27: Firebase Security Rules Configuration

**User Story:** As a developer, I want Firestore security rules defined, so that user data is protected from unauthorized access.

#### Acceptance Criteria

1. THE Firestore_Rules SHALL allow read access to `sentSongs` WHERE toUid equals authenticated user's UID
2. THE Firestore_Rules SHALL allow create access to `sentSongs` WHERE fromUid equals authenticated user's UID
3. THE Firestore_Rules SHALL allow update access to `sentSongs` WHERE toUid equals authenticated user's UID
4. THE Firestore_Rules SHALL require authentication for all `sentSongs` operations
5. THE Firestore_Rules SHALL validate sentSongs documents contain required fields: songId, songTitle, fromUid, toUid, sentAt
6. THE Firestore_Rules SHALL prevent users from modifying fromUid or toUid fields after creation
7. THE Firestore_Rules SHALL allow users to set listenedAt and completedAt timestamps only for songs sent to them

### Requirement 28: Database Schema Extensions

**User Story:** As a developer, I want database schema updated to support social features, so that social data persists locally.

#### Acceptance Criteria

1. THE Room_Database SHALL add TO_LISTEN_PLAYLIST_ID constant with value "to_listen_playlist_id"
2. THE PlaylistEntity SHALL support isEditable=false for immutable playlists
3. THE Room_Database SHALL create the To_Listen_Playlist with appropriate properties on first launch
4. THE Room_Database migration SHALL be idempotent (safe to run multiple times)
5. THE Room_Database SHALL maintain referential integrity between songs and playlists
6. WHEN database migration fails, THE Social_System SHALL log the error and not crash the app
7. THE Room_Database SHALL use transactions for multi-step operations (add song + update positions)

### Requirement 29: Dependency Version Compatibility

**User Story:** As a developer, I want all dependencies compatible with Metrolist's existing versions, so that there are no version conflicts.

#### Acceptance Criteria

1. THE port SHALL use Firebase BoM version compatible with Metrolist's existing Firebase dependencies
2. THE port SHALL use WorkManager version matching Metrolist's Media3 dependencies
3. THE port SHALL use kotlinx.serialization version compatible with Metrolist's Kotlin version
4. THE port SHALL use Hilt version matching Metrolist's existing Hilt configuration
5. WHEN dependency versions conflict, THE port SHALL update to the higher compatible version
6. THE port SHALL not downgrade any existing Metrolist dependencies
7. THE port SHALL document any required dependency version changes in migration notes

### Requirement 30: Code Style and Convention Adherence

**User Story:** As a developer, I want ported code to follow Metrolist's conventions, so that the codebase remains maintainable.

#### Acceptance Criteria

1. THE ported code SHALL follow Metrolist's existing Kotlin coding style
2. THE ported code SHALL use Metrolist's existing naming conventions for ViewModels, Repositories, and UI components
3. THE ported code SHALL place files in appropriate package structure matching Metrolist's organization
4. THE ported code SHALL use Metrolist's existing error handling patterns
5. THE ported code SHALL use Metrolist's existing logging practices
6. THE ported code SHALL include KDoc comments for public APIs matching Metrolist's documentation style
7. THE ported code SHALL pass Metrolist's existing lint rules without warnings

### Requirement 31: AndroidManifest Integration

**User Story:** As a developer, I want manifest entries added for social features, so that services and permissions are properly declared.

#### Acceptance Criteria

1. THE AndroidManifest SHALL declare SongListenedMessagingService as a FirebaseMessagingService
2. THE AndroidManifest SHALL request permission INTERNET for Firebase access
3. THE AndroidManifest SHALL request permission POST_NOTIFICATIONS for Android 13+ notification support
4. THE AndroidManifest SHALL declare WorkManager initialization provider for background tasks
5. THE AndroidManifest SHALL not duplicate any existing Metrolist manifest entries
6. THE AndroidManifest SHALL place new entries in appropriate sections (permissions, services, receivers)
7. THE AndroidManifest SHALL include intent filters for notification deep linking

### Requirement 32: Testing Infrastructure - Unit Tests

**User Story:** As a developer, I want unit tests for ported features, so that functionality is verified and regressions are prevented.

#### Acceptance Criteria

1. THE test suite SHALL include unit tests for SongSharingRepository with mocked Firebase dependencies
2. THE test suite SHALL include unit tests for JSON parsing with valid and invalid inputs
3. THE test suite SHALL include unit tests for PlaybackProgressTracker progress calculation logic
4. THE test suite SHALL include unit tests for notification formatting with various data combinations
5. THE test suite SHALL include unit tests for playlist position shifting logic
6. THE test suite SHALL achieve minimum 70 percent code coverage for new repository classes
7. THE test suite SHALL use MockK or Mockito for dependency mocking

### Requirement 33: Testing Infrastructure - Integration Tests

**User Story:** As a developer, I want integration tests for critical flows, so that end-to-end functionality is verified.

#### Acceptance Criteria

1. THE test suite SHALL include integration test for sending song to friend (UI to Firestore)
2. THE test suite SHALL include integration test for receiving song and adding to To_Listen_Playlist
3. THE test suite SHALL include integration test for JSON import with small playlist (5 tracks)
4. THE test suite SHALL include integration test for playback progress tracking to 50 percent and 100 percent
5. THE test suite SHALL include integration test for notification display on song listened event
6. THE integration tests SHALL use Firebase Emulator Suite for Firestore testing
7. THE integration tests SHALL clean up test data after execution

### Requirement 34: Error Handling and Recovery

**User Story:** As a user, I want the app to handle errors gracefully, so that temporary issues don't break functionality.

#### Acceptance Criteria

1. WHEN Firestore operations fail, THE Social_System SHALL log the error with stack trace
2. WHEN Firestore operations fail, THE Social_System SHALL retry up to 3 times with exponential backoff (1s, 2s, 4s)
3. WHEN YouTube Music search fails, THE JSON_Import_Feature SHALL add track to failed imports instead of crashing
4. WHEN database operations fail, THE Social_System SHALL show user-friendly error message
5. WHEN notification display fails, THE Social_System SHALL log the error but not crash
6. WHEN Firebase authentication expires, THE Social_System SHALL prompt user to re-login
7. WHEN network is unavailable, THE Social_System SHALL queue operations for retry when connectivity returns

### Requirement 35: Offline Support and Synchronization

**User Story:** As a user, I want offline functionality, so that temporary network issues don't prevent me from using the app.

#### Acceptance Criteria

1. THE Social_System SHALL enable Firestore offline persistence for `sentSongs` collection
2. WHEN offline, sent songs SHALL queue locally and sync when connectivity returns
3. WHEN offline, received songs SHALL be available in To_Listen_Playlist after initial sync
4. THE Social_System SHALL indicate synchronization status in UI (syncing, synced, offline)
5. WHEN coming back online, THE Social_System SHALL automatically sync pending operations within 10 seconds
6. THE Room_Database SHALL serve as the source of truth for playlist and song data
7. THE Social_System SHALL handle conflict resolution using "last write wins" strategy for duplicate operations

### Requirement 36: Performance Requirements

**User Story:** As a user, I want the app to remain responsive, so that social features don't slow down the experience.

#### Acceptance Criteria

1. THE Social_System SHALL complete Firestore write operations within 2 seconds on typical network
2. THE JSON_Import_Feature SHALL process each track within 2 seconds (search + database insert)
3. THE Playback_Progress_Tracker SHALL update progress with maximum 100 milliseconds latency
4. THE Audio_Visualizer SHALL maintain minimum 30 FPS frame rate on devices with Android 8+
5. THE Social_System SHALL limit memory usage to less than 50 MB additional heap space
6. THE notification system SHALL display notifications within 5 seconds of triggering event
7. THE app startup time SHALL not increase by more than 500 milliseconds due to social feature initialization

### Requirement 37: Accessibility Requirements

**User Story:** As a user with accessibility needs, I want ported features to be accessible, so that I can use all app functionality.

#### Acceptance Criteria

1. THE Send_To_Friends_Dialog SHALL provide content descriptions for all checkboxes
2. THE Import_From_JSON button SHALL have semantic label "Import playlist from JSON file"
3. THE Audio_Visualizer SHALL not be the only way to convey playback information (provide text alternative)
4. THE notification SHALL use priority and category appropriate for user attention level
5. THE To_Listen_Playlist SHALL be navigable using TalkBack screen reader
6. THE playback progress tracking SHALL not interfere with assistive technology media session access
7. THE UI animations SHALL respect system reduced motion accessibility setting

### Requirement 38: Localization and Internationalization

**User Story:** As a user who speaks a non-English language, I want ported features available in my language, so that I can use them comfortably.

#### Acceptance Criteria

1. THE Social_System SHALL define all user-facing strings in strings.xml resource files
2. THE string resources SHALL include keys: send_to_friends, to_listen_playlist, friend_listened_notification_title, import_from_json, failed_imports_dialog_title
3. THE Social_System SHALL use string formatting for messages with dynamic content (friend name, song title)
4. THE Social_System SHALL handle right-to-left (RTL) languages in dialog layouts
5. THE date and time formatting SHALL use user's locale preferences
6. THE error messages SHALL be localized for common languages supported by Metrolist
7. THE string resources SHALL be compatible with Metrolist's existing translation workflow (Weblate)

### Requirement 39: Migration Documentation

**User Story:** As a developer, I want comprehensive migration documentation, so that I can understand and maintain the ported code.

#### Acceptance Criteria

1. THE migration documentation SHALL list all files created in Metrolist
2. THE migration documentation SHALL list all files modified in Metrolist with change descriptions
3. THE migration documentation SHALL provide package namespace mapping table (OuterTune to Metrolist)
4. THE migration documentation SHALL document Firebase configuration steps
5. THE migration documentation SHALL document required gradle dependency additions
6. THE migration documentation SHALL document AndroidManifest changes with explanations
7. THE migration documentation SHALL include troubleshooting section for common migration issues

### Requirement 40: Backward Compatibility

**User Story:** As an existing Metrolist user, I want my data preserved after the update, so that I don't lose playlists or settings.

#### Acceptance Criteria

1. THE Social_System SHALL not modify existing Metrolist database schema in breaking ways
2. THE Room_Database migration SHALL preserve all existing playlist data
3. THE Social_System SHALL not interfere with existing Metrolist features (Music Recognition, Podcast support, Advanced Lyrics)
4. WHEN upgrading from version without social features, THE Social_System SHALL initialize gracefully without data loss
5. THE To_Listen_Playlist creation SHALL be additive (not modify existing playlists)
6. THE user preferences SHALL remain unchanged after adding social features
7. THE Social_System SHALL support rolling back to previous version without database corruption

## Notes

### OuterTune Features Not Included in Port

The following OuterTune features are intentionally excluded from this port as they conflict with or duplicate existing Metrolist features:

- **Spotify Integration**: Metrolist may have its own sync mechanism
- **Custom Tag Extractor**: Metrolist may use different metadata extraction
- **Specific UI Theme Variants**: Preserve Metrolist's existing theming

### Critical Integration Points

1. **Listen Together Compatibility**: The Playback_Progress_Tracker MUST check if user is in a Listen_Together session and disable tracking to avoid conflicts
2. **Firebase Project**: Use Metrolist's Firebase project if it exists, otherwise create new configuration
3. **Package Namespace**: All references to `com.dd3boh.outertune` must be converted to `com.metrolist`
4. **WorkManager Configuration**: Verify compatibility with Metrolist's existing WorkManager usage

### Performance Considerations

- JSON imports with 500+ tracks may take 15-25 minutes
- Firestore listeners should be lifecycle-aware to prevent memory leaks
- Audio visualizer should degrade gracefully on low-end devices
- Background notification worker runs every 15 minutes (Android minimum)

### Security Considerations

- Firestore security rules must be deployed before production release
- FCM notifications should not contain sensitive user data
- User authentication state must be verified before all social operations
- Firestore offline cache should have size limit to prevent excessive storage usage
