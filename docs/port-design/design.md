# Technical Design Document: OuterTune to Metrolist Feature Port

## Overview

This design document specifies the technical architecture and implementation approach for porting four major feature sets from OuterTune to Metrolist:

1. **Social Features**: Complete song sharing system with friend management, Firebase backend, real-time and background notifications
2. **JSON Playlist Import**: File-based playlist import with YouTube Music matching
3. **Audio Visualizer**: Real-time waveform visualization integrated with Media3 playback
4. **UI Improvements**: Blur effects, navigation animations, button feedback, and spacing refinements

### Design Goals

- **Non-Invasive Integration**: Port features without breaking Metrolist's existing functionality (especially Listen Together)
- **Package Namespace Consistency**: Migrate all code from `com.dd3boh.outertune` to `com.metrolist`
- **Architecture Alignment**: Follow Metrolist's existing MVVM + Compose patterns
- **Firebase Integration**: Leverage Firestore, Auth, and FCM for social features
- **Performance**: Maintain app responsiveness, target <500ms startup impact
- **Testability**: Design for unit and integration testing with Firebase emulators

### Key Technical Constraints

- **Android SDK**: Min 24 (Oreo), Target/Compile 36
- **Kotlin**: 2.x with JVM target 21
- **UI Framework**: Jetpack Compose with Material3
- **Dependency Injection**: Hilt
- **Database**: Room with SQLite
- **Media Playback**: Media3 (ExoPlayer)
- **Async**: Kotlin Coroutines + Flow
- **Build System**: Gradle with Kotlin DSL

---

## Architecture

### High-Level System Architecture

The port introduces four parallel feature modules that integrate into Metrolist's existing architecture:

```
┌─────────────────────────────────────────────────────────────────┐
│                        Metrolist App                             │
│                                                                   │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │                     Presentation Layer                       │ │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐     │ │
│  │  │  Social UI   │  │  Sync UI     │  │  Player UI   │     │ │
│  │  │ (Send Songs) │  │(JSON Import) │  │(Visualizer)  │     │ │
│  │  └──────────────┘  └──────────────┘  └──────────────┘     │ │
│  └────────────────────────────────────────────────────────────┘ │
│                           ▲                                       │
│                           │ StateFlow                             │
│                           │                                       │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │                    ViewModel Layer                           │ │
│  │  ┌──────────────────────┐       ┌───────────────────────┐  │ │
│  │  │ (Existing ViewModels)│       │    SyncViewModel      │  │ │
│  │  └──────────────────────┘       └───────────────────────┘  │ │
│  └────────────────────────────────────────────────────────────┘ │
│                           ▲                                       │
│                           │                                       │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │                   Repository Layer                           │ │
│  │  ┌────────────────────┐  ┌────────────────────────────────┐│ │
│  │  │ SongSharing       │  │  Notification Management       ││ │
│  │  │ Repository        │  │  (Manager + Worker + Notifier) ││ │
│  │  └────────────────────┘  └────────────────────────────────┘│ │
│  └────────────────────────────────────────────────────────────┘ │
│           ▲           ▲                    ▲                      │
│           │           │                    │                      │
│    ┌──────┴──────┐    └──────────┬────────┘                      │
│    │ Firestore   │          ┌────▼────┐                          │
│    │ (Cloud DB)  │          │WorkMgr  │                          │
│    └─────────────┘          └─────────┘                          │
│           ▲                                                       │
│  ┌────────┴─────────────────────────────────────────────────┐   │
│  │              Local Data Layer (Room Database)             │   │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────────────────┐   │   │
│  │  │  Songs   │  │ Playlists│  │ PlaylistSongMap      │   │   │
│  │  └──────────┘  └──────────┘  └──────────────────────┘   │   │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                   │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │                  Playback Layer                              │ │
│  │  ┌────────────────┐  ┌──────────────────────────────────┐  │ │
│  │  │  MusicService  │  │  PlaybackProgressTracker         │  │ │
│  │  │  (Media3)      │◄─┤  (50%/100% milestone tracking)   │  │ │
│  │  └────────────────┘  └──────────────────────────────────┘  │ │
│  └────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

### Feature Module Breakdown

#### 1. Social Features Module

**Components**:
- `SongSharingRepository`: Core business logic for sending/receiving songs, Firestore sync
- `PlaybackProgressTracker`: Monitors playback to detect 50% and 100% milestones
- `SongListenedRealTimeNotifier`: Real-time notification listener (app foreground)
- `SongListenedNotificationWorker`: Background notification worker (app closed)
- `SongListenedNotificationManager`: Lifecycle manager for notification worker

**Data Flow**:
```
User selects songs → Send to Friends Dialog → SongSharingRepository
                                                    ↓
                                            Create Firestore docs
                                                    ↓
Friend's Device ← Firestore Listener ← observeIncomingSongs()
      ↓
addSongToToListenPlaylist()
      ↓
Song added to "To Listen" playlist
      ↓
User plays song → PlaybackProgressTracker
      ↓                     ↓
  50% reached         100% reached
      ↓                     ↓
markSongAsListened()   markSongAsCompleted()
      ↓                     ↓
Update Firestore       Update Firestore + Remove from playlist
      ↓
Sender's Device ← Notification (Real-time or Background)
```

#### 2. JSON Import Module

**Components**:
- `SyncViewModel.startJsonImport()`: Orchestrates import process
- `JsonTrack` data class: Represents track from JSON file
- YouTube Music search integration: Matches tracks via innertube API

**Data Flow**:
```
User selects JSON file → File picker → parseJsonFile()
                              ↓
                        List<JsonTrack>
                              ↓
                    User provides playlist name
                              ↓
                  findOrCreatePlaylist() → PlaylistEntity
                              ↓
            performJsonImport() processes each track:
                ↓                           ↓
        matchJsonTrack()            Check for duplicates
                ↓                           ↓
        Get SongItem            Already in playlist? → Skip
                ↓                           
        toMediaMetadata()                   
                ↓                           
        Insert song + Add to playlist       
                ↓                           
        Report progress (N/Total)          
                              ↓
                    Show failed imports dialog
```

#### 3. Audio Visualizer Module

**Components**:
- `AudioVisualizer` Composable: Canvas-based waveform renderer
- Media3 audio processor integration: Extracts PCM audio samples
- Player background style setting: User preference for blur/color/visualizer

**Data Flow**:
```
MusicService playback → Media3 AudioProcessor
                              ↓
                        Extract audio samples
                              ↓
                        AudioVisualizer Composable
                              ↓
                    Canvas API renders waveform
                              ↓
                        Frame rate limiting (30-60 FPS)
```

#### 4. UI Improvements Module

**Components**:
- Blur effect renderer: RenderEffect or RenderScript fallback
- Navigation animations: Material3 motion specifications
- Button press animations: Scale transformations with Compose
- Spacing system: Material3 4dp base unit

### Listen Together Compatibility Strategy

To prevent conflicts with Metrolist's existing Listen Together feature:

1. **Separate Collections**: Social features use `sentSongs` collection, Listen Together uses its own
2. **Playlist Detection**: `PlaybackProgressTracker` checks current playlist ID before tracking
3. **Independent Observers**: Separate Firebase auth state listeners
4. **Notification Channels**: Distinct channel IDs for each feature
5. **Session Detection**: Check if user is in Listen Together session before tracking

**Compatibility Check Logic**:
```kotlin
fun shouldTrackProgress(currentPlaylistId: String?, isInListenTogetherSession: Boolean): Boolean {
    return currentPlaylistId == PlaylistEntity.TO_LISTEN_PLAYLIST_ID 
           && !isInListenTogetherSession
}
```

---

## Components and Interfaces

### Social Features Components

#### SongSharingRepository

**Purpose**: Central repository for all song sharing operations, Firebase sync, and local playlist management.

**Key Methods**:

```kotlin
@Singleton
class SongSharingRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val database: MusicDatabase
) {
    // Initialization
    suspend fun initializeToListenPlaylist()
    
    // Sending songs
    suspend fun sendSongsToFriends(
        songs: List<MediaMetadata>,
        friendUids: List<String>,
        friendProfiles: Map<String, UserProfile>
    ): Int
    
    // Receiving songs
    fun observeIncomingSongs(): Flow<List<SentSong>>
    suspend fun addSongToToListenPlaylist(
        sentSong: SentSong,
        metadata: MediaMetadata
    ): AddSongResult
    
    // Progress tracking
    suspend fun markSongAsListened(sentSongId: String)
    suspend fun markSongAsCompleted(sentSongId: String, songId: String)
    suspend fun getSentSongBySongId(songId: String): SentSong?
    
    // Notifications
    suspend fun markNotificationSent(sentSongId: String)
    suspend fun getListenedSongsNeedingNotification(
        fromUid: String,
        since: Long
    ): List<SentSong>
    fun observeListenedSongsNeedingNotification(
        fromUid: String,
        since: Long
    ): Flow<List<SentSong>>
    
    // Cleanup
    suspend fun clearToListenPlaylist()
}
```

**Error Handling**: Implements exponential backoff retry (1s, 2s, 4s) for Firestore operations. Logs errors with stack traces and re-throws for caller handling.

**Thread Safety**: All suspend functions use `withContext(Dispatchers.IO)`. Database transactions ensure atomic multi-step operations.

#### PlaybackProgressTracker

**Purpose**: Monitors playback progress for songs from "To Listen" playlist, triggers milestone actions.

**Key Methods**:

```kotlin
@Singleton
class PlaybackProgressTracker @Inject constructor(
    private val songSharingRepository: SongSharingRepository,
    private val database: MusicDatabase
) {
    // Lifecycle hooks
    fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
        currentPlaylistId: String?
    )
    fun onPlaybackStateChanged(playbackState: Int)
    
    // Core tracking
    fun trackProgress(
        player: Player,
        scope: CoroutineScope,
        currentPlaylistId: String?
    )
    
    // User actions
    fun onSeekPerformed()
    fun onPlaybackRestarted()
    
    // Cleanup
    fun cleanup()
}
```

**State Management**:
- `currentTrackingSongId`: Song ID being tracked (null if not tracking)
- `currentSentSongId`: Firestore document ID for the song
- `has50PercentTriggered`: Boolean flag to prevent duplicate 50% actions
- `has100PercentTriggered`: Boolean flag to prevent duplicate 100% actions
- `maxProgressReached`: Float tracking highest progress (handles seeking backward)
- `trackingInitialized`: AtomicBoolean for thread-safe initialization

**Tracking Algorithm**:
```
Every 1 second (called by MusicService):
  1. Check if tracking is active (currentTrackingSongId != null)
  2. Verify mediaId matches currentTrackingSongId
  3. Calculate progress: (currentPosition / duration) * 100
  4. Update maxProgressReached if progress higher
  5. If maxProgressReached >= 50% AND !has50PercentTriggered:
       - Set has50PercentTriggered = true
       - Launch coroutine: markSongAsListened()
  6. If maxProgressReached >= 95% AND !has100PercentTriggered:
       - Set has100PercentTriggered = true
       - Set has50PercentTriggered = true (ensure both marked)
       - Launch coroutine: markSongAsCompleted() + remove from playlist
```

**Retry Logic**: If `currentSentSongId` is null when milestone reached, retry initialization synchronously before proceeding.

#### Notification System

**Three-Part Architecture**:

1. **SongListenedNotificationManager**: Lifecycle manager
   - `startWorker()`: Schedules periodic work on user login
   - `stopWorker()`: Cancels work on user logout
   - `triggerWorkerNow()`: Manual trigger for testing

2. **SongListenedNotificationWorker**: Background worker (@HiltWorker)
   - Runs every 15 minutes when device has internet
   - Queries Firestore for listened songs needing notification
   - Filters to songs listened within last 24 hours
   - Shows local notifications via NotificationHelper
   - Marks songs as notified in Firestore

3. **SongListenedRealTimeNotifier**: Foreground listener (@Singleton)
   - Monitors Firebase Auth state changes
   - Starts Firestore listener when user logs in
   - Observes `observeListenedSongsNeedingNotification()` Flow
   - Same filtering and notification logic as worker
   - Stops listener when user logs out

**Notification Channel**:
```kotlin
Channel ID: "song_listened_notifications"
Name: "Friend Listened Notifications"
Importance: DEFAULT
Auto-cancel: true
Pending Intent: Navigate to Social screen
```

**Notification Content**:
```kotlin
Title: "Friend listened to your song!"
Message: "[fromUsername] listened to [songTitle]"
Fallbacks: "A friend" / "a song you sent" if data missing
```

### JSON Import Components

#### SyncViewModel.startJsonImport()

**Purpose**: Orchestrate JSON playlist import with progress reporting and error handling.

**Signature**:
```kotlin
fun startJsonImport(uri: Uri, playlistName: String)
```

**Implementation Steps**:
1. Cancel any existing import job
2. Set state to `SyncState.Syncing()`
3. Parse JSON file → `List<JsonTrack>`
4. Validate non-empty track list
5. Find or create playlist by name
6. Process each track:
   - Update progress UI (N/Total)
   - Match with YouTube Music (retry up to 2 times)
   - Check for duplicates in playlist
   - Insert song into database
   - Add to playlist (append at end)
7. Show failed imports dialog if any failures
8. Set state to `SyncState.Success`

**Error Handling**:
- `SerializationException`: "Invalid JSON format"
- `IllegalArgumentException`: "Cannot open file"
- Empty track list: "No tracks found in JSON file"
- Generic exceptions: "Import failed: {message}"
- Cancellation: Set state to `SyncState.Cancelled`

**Progress Reporting**:
```kotlin
_statusText.value = "Matching [current]/[total]: [title] - [artist]"
_progress.value = (current.toFloat() / total.toFloat())
```

#### JsonTrack Data Class

**Purpose**: Represent a track from JSON file for deserialization.

```kotlin
@Serializable
data class JsonTrack(
    val title: String,
    val artist: String
) {
    fun toSearchQuery(): String = "$title $artist"
}
```

**JSON Format**:
```json
[
  {
    "title": "Song Title",
    "artist": "Artist Name"
  },
  ...
]
```

#### YouTube Music Matching

**Algorithm**:
```kotlin
private suspend fun matchJsonTrackWithRetry(
    track: JsonTrack,
    maxAttempts: Int = 2
): SongItem? {
    repeat(maxAttempts) { attempt ->
        try {
            val result = matchJsonTrack(track)
            if (result != null) return result
        } catch (e: Exception) {
            if (attempt == maxAttempts - 1) throw e
            delay(1000 * (attempt + 1)) // 1s, 2s
        }
    }
    return null
}

private suspend fun matchJsonTrack(track: JsonTrack): SongItem? {
    val query = track.toSearchQuery()
    return YouTube.search(
        query,
        filter = YouTube.SearchFilter.FILTER_SONG
    ).map { page ->
        page.items.filterIsInstance<SongItem>().firstOrNull()
    }.getOrNull()
}
```

**Duplicate Detection**:
```kotlin
val existingSongs = database.playlistSongs(playlistId).firstOrNull() ?: emptyList()
val isDuplicate = existingSongs.any { it.song.id == songId }
if (isDuplicate) {
    failed.add(ImportResult.Failed(track, "Already in playlist"))
    songsSkipped++
    return@forEachIndexed
}
```

### Audio Visualizer Components

#### AudioVisualizer Composable

**Purpose**: Render real-time audio waveform visualization using Canvas API.

**Interface**:
```kotlin
@Composable
fun AudioVisualizer(
    audioData: FloatArray,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        // Waveform rendering logic
    }
}
```

**Rendering Algorithm**:
1. Divide canvas width into bars (e.g., 64 bars)
2. Map audio samples to bar heights (normalize to 0-1 range)
3. Apply smoothing via exponential moving average
4. Draw bars with spacing (4dp between bars)
5. Apply color gradient (primary → primary.copy(alpha=0.3))

**Performance Optimizations**:
- Limit to 30-60 FPS via frame callback throttling
- Use hardware-accelerated Canvas when available
- Downsample audio data to reduce computational load
- Release resources when visualizer disabled

#### Media3 Integration

**Audio Processor Setup**:
```kotlin
val audioProcessor = object : AudioProcessor {
    override fun configure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        // Configure PCM float output
        return AudioProcessor.AudioFormat(
            sampleRate = inputAudioFormat.sampleRate,
            channelCount = 1, // Mono
            encoding = C.ENCODING_PCM_FLOAT
        )
    }
    
    override fun process(inputBuffer: ByteBuffer) {
        // Extract samples and emit to visualizer
        val samples = FloatArray(inputBuffer.remaining() / 4)
        inputBuffer.asFloatBuffer().get(samples)
        onAudioSamplesReceived(samples)
    }
}

player.setAudioProcessor(audioProcessor)
```

#### Player Background Style

**Preference Options**:
```kotlin
enum class PlayerBackgroundStyle {
    BLUR,    // Blur album artwork
    COLOR,   // Solid color from artwork
    VISUALIZER  // Real-time waveform
}
```

**Implementation**:
```kotlin
when (backgroundStyle) {
    PlayerBackgroundStyle.BLUR -> BlurredBackground(artwork)
    PlayerBackgroundStyle.COLOR -> ColoredBackground(dominantColor)
    PlayerBackgroundStyle.VISUALIZER -> AudioVisualizer(audioData)
}
```

### UI Improvements Components

#### Blur Effect

**Implementation using RenderEffect (Android 12+)**:
```kotlin
@Composable
fun BlurredImage(
    imageUrl: String,
    blurRadius: Dp = 15.dp,
    modifier: Modifier = Modifier
) {
    AsyncImage(
        model = imageUrl,
        contentDescription = null,
        modifier = modifier
            .graphicsLayer {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    renderEffect = RenderEffect.createBlurEffect(
                        blurRadius.toPx(),
                        blurRadius.toPx(),
                        Shader.TileMode.CLAMP
                    )
                }
            }
    )
}
```

**Fallback for Android 8-11**:
- Use color extraction from artwork
- Apply gradient background with extracted colors
- Cache extracted colors to avoid redundant computation

#### Navigation Animations

**Material3 Slide Transitions**:
```kotlin
@Composable
fun MaterialSlideTransition(
    targetState: Screen,
    content: @Composable (Screen) -> Unit
) {
    AnimatedContent(
        targetState = targetState,
        transitionSpec = {
            slideInHorizontally(
                initialOffsetX = { fullWidth -> fullWidth },
                animationSpec = tween(300, easing = FastOutSlowInEasing)
            ) togetherWith slideOutHorizontally(
                targetOffsetX = { fullWidth -> -fullWidth },
                animationSpec = tween(300, easing = FastOutSlowInEasing)
            )
        }
    ) { screen ->
        content(screen)
    }
}
```

**Accessibility Support**:
```kotlin
val reduceMotion = LocalAccessibilityManager.current?.isEnabled ?: false
val animationSpec = if (reduceMotion) {
    tween(100) // Faster, less motion
} else {
    tween(300, easing = FastOutSlowInEasing)
}
```

#### Button Press Animations

**Scale Animation**:
```kotlin
@Composable
fun AnimatedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(100)
    )
    
    Box(
        modifier = modifier
            .scale(scale)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        tryAwaitRelease()
                        isPressed = false
                    }
                )
            }
            .clickable(onClick = onClick)
    ) {
        content()
    }
}
```

#### Spacing System

**Material3 4dp Base Unit**:
```kotlin
object Spacing {
    val XXS = 2.dp    // 0.5x
    val XS = 4.dp     // 1x
    val S = 8.dp      // 2x
    val M = 12.dp     // 3x
    val L = 16.dp     // 4x
    val XL = 24.dp    // 6x
    val XXL = 32.dp   // 8x
}
```

**Touch Target Size**:
```kotlin
Modifier.minimumInteractiveComponentSize() // 48dp minimum
```

---

## Data Models

### Social Features Data Models

#### SentSong

**Purpose**: Represent a song sent from one user to another, synchronized via Firestore.

```kotlin
data class SentSong(
    val id: String = "",              // Firebase document ID
    val songId: String = "",          // YouTube/Local song ID
    val songTitle: String = "",
    val songArtist: String = "",
    val songDuration: Int = 0,        // seconds
    val thumbnailUrl: String? = null,
    val albumId: String? = null,
    val albumName: String? = null,
    val fromUid: String = "",         // Sender Firebase UID
    val fromUsername: String = "",    // Sender display name
    val toUid: String = "",           // Recipient Firebase UID
    val sentAt: Long = System.currentTimeMillis(),
    val listenedAt: Long? = null,     // 50% milestone timestamp
    val completedAt: Long? = null,    // 100% milestone timestamp
    val notificationSent: Boolean = false
) : Serializable {
    
    fun toMap(): Map<String, Any?> = mapOf(
        "songId" to songId,
        "songTitle" to songTitle,
        "songArtist" to songArtist,
        "songDuration" to songDuration,
        "thumbnailUrl" to thumbnailUrl,
        "albumId" to albumId,
        "albumName" to albumName,
        "fromUid" to fromUid,
        "fromUsername" to fromUsername,
        "toUid" to toUid,
        "sentAt" to sentAt,
        "listenedAt" to listenedAt,
        "completedAt" to completedAt,
        "notificationSent" to notificationSent
    )
    
    companion object {
        fun fromMap(id: String, map: Map<String, Any?>): SentSong {
            return SentSong(
                id = id,
                songId = map["songId"] as? String ?: "",
                songTitle = map["songTitle"] as? String ?: "",
                songArtist = map["songArtist"] as? String ?: "",
                songDuration = (map["songDuration"] as? Long)?.toInt() ?: 0,
                thumbnailUrl = map["thumbnailUrl"] as? String,
                albumId = map["albumId"] as? String,
                albumName = map["albumName"] as? String,
                fromUid = map["fromUid"] as? String ?: "",
                fromUsername = map["fromUsername"] as? String ?: "",
                toUid = map["toUid"] as? String ?: "",
                sentAt = map["sentAt"] as? Long ?: System.currentTimeMillis(),
                listenedAt = map["listenedAt"] as? Long,
                completedAt = map["completedAt"] as? Long,
                notificationSent = map["notificationSent"] as? Boolean ?: false
            )
        }
    }
}
```

**Firestore Schema**:
```
sentSongs (collection)
├── {documentId} (auto-generated)
│   ├── songId: string
│   ├── songTitle: string
│   ├── songArtist: string
│   ├── songDuration: number
│   ├── thumbnailUrl: string | null
│   ├── albumId: string | null
│   ├── albumName: string | null
│   ├── fromUid: string (indexed)
│   ├── fromUsername: string
│   ├── toUid: string (indexed)
│   ├── sentAt: timestamp (indexed)
│   ├── listenedAt: timestamp | null
│   ├── completedAt: timestamp | null
│   └── notificationSent: boolean
```

**Firestore Indexes Required**:
- Composite: `toUid ASC, completedAt ASC, sentAt DESC`
- Composite: `fromUid ASC, sentAt DESC`
- Composite: `toUid ASC, songId ASC` (for duplicate detection)

#### AddSongResult

**Purpose**: Enum representing the outcome of adding a song to "To Listen" playlist.

```kotlin
enum class AddSongResult {
    SUCCESS,   // Song added successfully
    DUPLICATE, // Song already in playlist
    ERROR      // Database or Firestore error
}
```

#### FriendSelection

**Purpose**: UI model for friend selection in "Send to Friends" dialog.

```kotlin
data class FriendSelection(
    val uid: String,
    val username: String,
    val photoUrl: String?,
    val isSelected: Boolean = false
)
```

### JSON Import Data Models

#### JsonTrack

**Purpose**: Represent a track from JSON file for deserialization and search.

```kotlin
@Serializable
data class JsonTrack(
    val title: String,
    val artist: String
) {
    fun toSearchQuery(): String = "$title $artist"
}
```

#### ImportResult

**Purpose**: Sealed class representing the result of importing a single track.

```kotlin
sealed class ImportResult {
    data class Success(val track: JsonTrack, val songId: String) : ImportResult()
    data class Duplicate(val track: JsonTrack) : ImportResult()
    data class Failed(val track: JsonTrack, val reason: String) : ImportResult()
}
```

**Usage**:
```kotlin
val failed = mutableListOf<ImportResult.Failed>()
// On failure:
failed.add(ImportResult.Failed(track, "No match found on YouTube Music"))
// Display to user:
failed.forEach { failure ->
    println("${failure.track.title} - ${failure.track.artist}")
    println("  Reason: ${failure.reason}")
}
```

### Room Database Extensions

#### PlaylistEntity Extensions

**Purpose**: Add support for immutable "To Listen" playlist.

```kotlin
companion object {
    const val TO_LISTEN_PLAYLIST_ID = "to_listen_playlist_id"
}

// Existing PlaylistEntity with new field:
data class PlaylistEntity(
    @PrimaryKey val id: String = generatePlaylistId(),
    val name: String,
    val browseId: String? = null,
    val bookmarkedAt: LocalDateTime = LocalDateTime.now(),
    val isEditable: Boolean = true, // NEW: false for "To Listen"
    val isLocal: Boolean = true
)
```

**Database Migration** (if isEditable doesn't exist):
```kotlin
val MIGRATION_X_Y = object : Migration(X, Y) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE playlist ADD COLUMN isEditable INTEGER NOT NULL DEFAULT 1"
        )
    }
}
```

### UI State Models

#### SyncState

**Purpose**: Represent the state of sync operations (JSON import, Spotify sync).

```kotlin
sealed class SyncState {
    object Idle : SyncState()
    data class Syncing(val progress: Float = 0f) : SyncState()
    object Success : SyncState()
    data class Error(val message: String) : SyncState()
    object Cancelled : SyncState()
}
```

#### PlayerBackgroundStyle

**Purpose**: User preference for player background appearance.

```kotlin
enum class PlayerBackgroundStyle {
    BLUR,
    COLOR,
    VISUALIZER
}
```

---

## Error Handling

### Firestore Operation Error Handling

**Retry Strategy**:
```kotlin
suspend fun <T> retryFirestoreOperation(
    maxAttempts: Int = 3,
    initialDelay: Long = 1000L,
    operation: suspend () -> T
): T {
    repeat(maxAttempts) { attempt ->
        try {
            return operation()
        } catch (e: Exception) {
            if (attempt == maxAttempts - 1) throw e
            val delay = initialDelay * (1 shl attempt) // Exponential backoff
            Log.w(TAG, "Firestore operation failed (attempt ${attempt + 1}/$maxAttempts), retrying in ${delay}ms", e)
            delay(delay)
        }
    }
    throw IllegalStateException("Retry logic failed")
}
```

**Error Categories**:

1. **Network Errors**: FirebaseNetworkException
   - Retry with exponential backoff
   - Show "Connection issue, retrying..." to user
   
2. **Auth Errors**: FirebaseAuthException
   - Prompt user to re-login
   - Clear local auth state
   
3. **Permission Errors**: FirebaseFirestoreException (PERMISSION_DENIED)
   - Log error with user UID for debugging
   - Show "Permission denied" error to user
   
4. **Database Errors**: SQLiteException
   - Log full stack trace
   - Show generic "Database error" to user
   - Do not retry (likely persistent issue)

### YouTube Music Search Error Handling

**Failure Modes**:
1. No results found → Add to failed imports with reason "No match found"
2. Network timeout → Retry up to 2 times with 1s delay
3. Quota exceeded → Show error, stop import, log to analytics
4. Invalid response → Log error, add to failed imports with reason "Search error"

### Playback Tracking Error Handling

**Race Conditions**:
- **Multiple devices playing same song**: Firestore atomic updates prevent double-processing
- **Rapid seeking**: Use `maxProgressReached` to track highest progress, not current
- **Song restart**: Clear tracking state on `onMediaItemTransition()`

**Async Initialization**:
- If `currentSentSongId` is null at milestone, retry initialization synchronously
- Use `AtomicBoolean` flag to prevent race conditions on initialization

### UI Error Handling

**Error Display Patterns**:
```kotlin
// Snackbar for transient errors
LaunchedEffect(errorMessage) {
    errorMessage?.let {
        snackbarHostState.showSnackbar(
            message = it,
            duration = SnackbarDuration.Short
        )
    }
}

// Dialog for critical errors requiring user action
if (showErrorDialog) {
    AlertDialog(
        onDismissRequest = { showErrorDialog = false },
        title = { Text("Error") },
        text = { Text(errorMessage) },
        confirmButton = {
            TextButton(onClick = { showErrorDialog = false }) {
                Text("OK")
            }
        }
    )
}
```

---

## Testing Strategy

### Unit Testing

**Social Features**:
```kotlin
@Test
fun `sendSongsToFriends creates Firestore documents`() = runTest {
    // Mock FirebaseAuth, FirebaseFirestore, MusicDatabase
    val mockFirestore = mockk<FirebaseFirestore>()
    val mockAuth = mockk<FirebaseAuth>()
    val mockDatabase = mockk<MusicDatabase>()
    
    // Setup: Current user logged in
    every { mockAuth.currentUser } returns mockk {
        every { uid } returns "user123"
    }
    
    // Setup: Firestore collection mock
    val mockCollection = mockk<CollectionReference>()
    every { mockFirestore.collection("sentSongs") } returns mockCollection
    
    val mockDocRef = mockk<DocumentReference>()
    coEvery { mockCollection.add(any()) } returns Tasks.forResult(mockDocRef)
    
    // Execute
    val repository = SongSharingRepository(mockFirestore, mockAuth, mockDatabase)
    val songs = listOf(createTestMediaMetadata())
    val result = repository.sendSongsToFriends(
        songs,
        listOf("friend456"),
        mapOf("friend456" to UserProfile("friend456", "FriendName", null))
    )
    
    // Verify
    assertEquals(1, result)
    coVerify { mockCollection.add(match { map ->
        map["songId"] == "testSong123" &&
        map["fromUid"] == "user123" &&
        map["toUid"] == "friend456"
    }) }
}

@Test
fun `PlaybackProgressTracker triggers 50 percent milestone`() = runTest {
    // Mock repository and database
    val mockRepository = mockk<SongSharingRepository>(relaxed = true)
    val mockDatabase = mockk<MusicDatabase>(relaxed = true)
    
    val tracker = PlaybackProgressTracker(mockRepository, mockDatabase)
    
    // Setup: Start tracking
    tracker.onMediaItemTransition(
        createTestMediaItem("song123"),
        Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
        PlaylistEntity.TO_LISTEN_PLAYLIST_ID
    )
    
    // Mock: Song exists in database and Firestore
    coEvery { mockDatabase.isSongInPlaylistSync(any(), any()) } returns 1
    coEvery { mockRepository.getSentSongBySongId("song123") } returns SentSong(
        id = "firestore123",
        songId = "song123",
        toUid = "user123"
    )
    
    // Setup: Mock player at 50% progress
    val mockPlayer = mockk<Player> {
        every { currentMediaItem?.mediaId } returns "song123"
        every { currentPosition } returns 30000L // 30 seconds
        every { duration } returns 60000L // 60 seconds
        every { isPlaying } returns true
    }
    
    // Execute: Track progress
    tracker.trackProgress(mockPlayer, this, PlaylistEntity.TO_LISTEN_PLAYLIST_ID)
    
    // Wait for async operations
    advanceUntilIdle()
    
    // Verify: markSongAsListened called
    coVerify { mockRepository.markSongAsListened("firestore123") }
}
```

**JSON Import**:
```kotlin
@Test
fun `parseJsonFile handles valid JSON`() = runTest {
    val viewModel = SyncViewModel(...)
    
    // Create temporary JSON file
    val jsonContent = """
        [
          {"title": "Song 1", "artist": "Artist 1"},
          {"title": "Song 2", "artist": "Artist 2"}
        ]
    """.trimIndent()
    
    val uri = createTempFileUri(jsonContent)
    
    // Execute
    val tracks = viewModel.parseJsonFile(uri)
    
    // Verify
    assertEquals(2, tracks.size)
    assertEquals("Song 1", tracks[0].title)
    assertEquals("Artist 1", tracks[0].artist)
}

@Test
fun `performJsonImport handles duplicates`() = runTest {
    // Mock: Playlist already contains song
    val mockDatabase = mockk<MusicDatabase>()
    coEvery { 
        mockDatabase.playlistSongs("playlist123").firstOrNull()
    } returns listOf(
        createTestPlaylistSong("existingSongId")
    )
    
    val viewModel = SyncViewModel(..., mockDatabase)
    
    val tracks = listOf(JsonTrack("Existing Song", "Artist"))
    
    // Mock: YouTube search returns existing song
    mockkObject(YouTube)
    coEvery { YouTube.search(any(), any()) } returns Result.success(
        SearchPage(items = listOf(SongItem(id = "existingSongId", ...)))
    )
    
    // Execute
    viewModel.performJsonImport(tracks, "playlist123", "Test Playlist")
    
    // Verify: Song not added again
    coVerify(exactly = 0) { 
        mockDatabase.query { insert(any<PlaylistSongMap>()) }
    }
    
    // Verify: Failed imports contains duplicate
    val failed = viewModel.failedImports.value
    assertTrue(failed.any { it.reason.contains("Already in playlist") })
}
```

### Integration Testing

**Firestore Integration** (using Firebase Emulator):
```kotlin
@Test
fun `end-to-end send and receive song flow`() = runTest {
    // Setup Firebase Emulator
    val firestore = FirebaseFirestore.getInstance().apply {
        useEmulator("localhost", 8080)
    }
    val auth = FirebaseAuth.getInstance().apply {
        useEmulator("localhost", 9099)
    }
    
    // Setup: Two test users
    val sender = auth.createUserWithEmailAndPassword("sender@test.com", "password").await().user!!
    val receiver = auth.createUserWithEmailAndPassword("receiver@test.com", "password").await().user!!
    
    // Setup: Sender logged in
    auth.signInWithEmailAndPassword("sender@test.com", "password").await()
    
    val repository = SongSharingRepository(firestore, auth, mockDatabase)
    
    // Execute: Send song
    val result = repository.sendSongsToFriends(
        listOf(createTestMediaMetadata()),
        listOf(receiver.uid),
        mapOf(receiver.uid to UserProfile(receiver.uid, "Receiver", null))
    )
    
    assertEquals(1, result)
    
    // Verify: Document exists in Firestore
    val docs = firestore.collection("sentSongs")
        .whereEqualTo("toUid", receiver.uid)
        .get()
        .await()
    
    assertEquals(1, docs.size())
    assertEquals("testSong123", docs.documents[0].get("songId"))
    
    // Setup: Receiver logged in
    auth.signInWithEmailAndPassword("receiver@test.com", "password").await()
    
    // Execute: Observe incoming songs
    val songs = repository.observeIncomingSongs().first()
    
    assertEquals(1, songs.size)
    assertEquals("testSong123", songs[0].songId)
    
    // Cleanup
    docs.documents.forEach { it.reference.delete().await() }
}
```

**Progress Tracking Integration**:
```kotlin
@Test
fun `playback progress triggers Firestore updates`() = runTest {
    val firestore = FirebaseFirestore.getInstance().apply {
        useEmulator("localhost", 8080)
    }
    val auth = FirebaseAuth.getInstance().apply {
        useEmulator("localhost", 9099)
    }
    
    // Setup: User logged in
    auth.signInWithEmailAndPassword("user@test.com", "password").await()
    
    // Setup: Pre-populate Firestore with sent song
    val sentSongRef = firestore.collection("sentSongs").add(
        SentSong(
            songId = "song123",
            toUid = auth.currentUser!!.uid,
            fromUid = "sender123"
        ).toMap()
    ).await()
    
    val repository = SongSharingRepository(firestore, auth, realDatabase)
    val tracker = PlaybackProgressTracker(repository, realDatabase)
    
    // Setup: Add song to "To Listen" playlist
    repository.addSongToToListenPlaylist(
        SentSong(id = sentSongRef.id, songId = "song123"),
        createTestMediaMetadata("song123")
    )
    
    // Setup: Start playback
    val player = createTestExoPlayer()
    player.setMediaItem(MediaItem.fromUri("song123"))
    player.prepare()
    player.play()
    
    tracker.onMediaItemTransition(
        player.currentMediaItem,
        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
        PlaylistEntity.TO_LISTEN_PLAYLIST_ID
    )
    
    // Execute: Simulate playback to 50%
    player.seekTo(30000L) // 30s out of 60s
    tracker.trackProgress(player, this, PlaylistEntity.TO_LISTEN_PLAYLIST_ID)
    
    advanceUntilIdle()
    
    // Verify: listenedAt timestamp set in Firestore
    val doc = sentSongRef.get().await()
    assertNotNull(doc.get("listenedAt"))
    
    // Cleanup
    sentSongRef.delete().await()
}
```

### Target Coverage

- **Repository Layer**: 70%+ coverage
- **ViewModels**: 60%+ coverage (UI state logic)
- **Critical Paths**: 90%+ coverage (progress tracking, Firestore sync)

---

## Performance Considerations

### Startup Impact

**Initialization Overhead**:
- Firebase SDK initialization: ~200ms
- Firestore offline persistence setup: ~100ms
- WorkManager setup: ~50ms
- Total target: <500ms additional startup time

**Optimization Strategies**:
- Lazy initialization of social features (only when user logs in)
- Background initialization of non-critical components
- Avoid main thread blocking during Firebase setup

### Memory Footprint

**Target**: <50MB additional heap usage

**Components**:
- Firebase SDK: ~15MB
- Audio visualizer buffers: ~5MB (downsample audio data)
- Firestore offline cache: ~20MB (configurable)
- UI image cache (blur effects): ~10MB

**Cache Management**:
```kotlin
FirebaseFirestore.getInstance().firestoreSettings = firestoreSettings {
    cacheSizeBytes = 100 * 1024 * 1024 // 100MB max
    isPersistenceEnabled = true
}
```

### Network Usage

**Firestore Sync**:
- Initial sync: ~100KB for 50 songs
- Incremental updates: ~2KB per song
- Real-time listener: Persistent WebSocket connection (~1KB/hour idle)

**JSON Import**:
- YouTube Music search: ~5KB per track
- Thumbnail downloads: ~10KB per thumbnail (optional)
- Total for 100 tracks: ~500KB

**Optimization**:
- Use Firestore offline persistence to reduce redundant fetches
- Batch Firestore writes (up to 500 operations)
- Debounce real-time listeners (update UI max once per second)

### Audio Visualizer Performance

**Target**: <5% CPU usage, 30-60 FPS

**Optimizations**:
1. **Downsampling**: Reduce audio samples from 44.1kHz to 60 samples/frame
2. **Frame Rate Limiting**: Cap at 60 FPS, reduce to 15 FPS in battery saver mode
3. **Hardware Acceleration**: Use Canvas hardware layer
4. **Lazy Rendering**: Skip frame rendering if no audio changes detected

**Battery Saver Mode Detection**:
```kotlin
val powerManager = context.getSystemService<PowerManager>()
val isBatterySaverMode = powerManager?.isPowerSaveMode == true

val targetFps = if (isBatterySaverMode) 15 else 60
```

### Database Transaction Optimization

**Batch Operations**:
```kotlin
database.runTransaction {
    // All operations in single transaction
    val songs = playlistSongsSync(playlistId)
    songs.forEach { update(it.map.copy(position = it.map.position + 1)) }
    insert(PlaylistSongMap(songId, playlistId, 0))
}
```

**Indexing Strategy**:
- Index on `PlaylistSongMap.playlistId` for fast playlist queries
- Index on `Song.id` for fast duplicate detection
- Composite index on `(playlistId, position)` for position updates

---

## Migration and Deployment

### Package Namespace Migration

**Automated Refactoring Steps**:

1. **Find and Replace** (IntelliJ IDEA / Android Studio):
   ```
   Find: com.dd3boh.outertune
   Replace: com.metrolist
   Scope: Project Files
   File Mask: *.kt, *.java, *.xml
   ```

2. **Update AndroidManifest.xml**:
   - Replace package name in `<manifest>` tag
   - Update service/activity/receiver declarations
   - Update authorities for ContentProviders

3. **Update Gradle Build Files**:
   ```kotlin
   namespace = "com.metrolist"
   applicationId = "com.metrolist"
   ```

4. **Move Source Directories**:
   ```
   app/src/main/java/com/dd3boh/outertune/ → app/src/main/java/com/metrolist/
   ```

5. **Verify No Hardcoded Strings**:
   ```bash
   grep -r "com.dd3boh.outertune" app/src/
   ```

### Firebase Configuration

**Prerequisites**:
1. Firebase project exists for Metrolist (or create new)
2. Add Android app to Firebase project with package name `com.metrolist`
3. Download `google-services.json` and place in `app/` directory
4. Enable Firestore, Authentication, and Cloud Messaging in Firebase Console

**Gradle Dependencies**:
```kotlin
// Firebase BoM for version consistency
implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
implementation("com.google.firebase:firebase-auth-ktx")
implementation("com.google.firebase:firebase-firestore-ktx")
implementation("com.google.firebase:firebase-messaging-ktx")
```

**Firestore Security Rules** (deploy before release):
```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    // Sent songs collection
    match /sentSongs/{document} {
      // Users can read songs sent to them
      allow read: if request.auth != null 
                  && request.auth.uid == resource.data.toUid;
      
      // Users can create songs from themselves
      allow create: if request.auth != null 
                    && request.auth.uid == request.resource.data.fromUid
                    && request.resource.data.keys().hasAll([
                      'songId', 'songTitle', 'fromUid', 'toUid', 'sentAt'
                    ]);
      
      // Users can update songs sent to them (progress tracking)
      allow update: if request.auth != null 
                    && request.auth.uid == resource.data.toUid
                    && !request.resource.data.diff(resource.data)
                       .affectedKeys().hasAny(['fromUid', 'toUid', 'songId']);
    }
  }
}
```

### Database Migration

**Room Database Schema Update**:
```kotlin
@Database(
    entities = [...],
    version = 21, // Increment version
    exportSchema = true
)
abstract class MusicDatabase : RoomDatabase() {
    // Add migration
    companion object {
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add isEditable column if doesn't exist
                database.execSQL(
                    "ALTER TABLE playlist ADD COLUMN isEditable INTEGER NOT NULL DEFAULT 1"
                )
            }
        }
    }
}

// Register migration in DI module
@Provides
@Singleton
fun provideMusicDatabase(@ApplicationContext context: Context): MusicDatabase {
    return Room.databaseBuilder(
        context,
        MusicDatabase::class.java,
        "music.db"
    )
    .addMigrations(MIGRATION_20_21)
    .build()
}
```

### AndroidManifest Updates

**New Entries**:
```xml
<!-- Permissions -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<!-- Services -->
<service
    android:name=".services.SongListenedMessagingService"
    android:exported="false">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT" />
    </intent-filter>
</service>

<!-- WorkManager -->
<provider
    android:name="androidx.startup.InitializationProvider"
    android:authorities="com.metrolist.androidx-startup"
    android:exported="false"
    tools:node="merge">
    <meta-data
        android:name="androidx.work.WorkManagerInitializer"
        android:value="androidx.startup" />
</provider>
```

### Deployment Checklist

- [ ] Package namespace migrated to `com.metrolist`
- [ ] `google-services.json` added to `app/` directory
- [ ] Firebase dependencies added to `build.gradle.kts`
- [ ] Firestore security rules deployed
- [ ] Firestore indexes created (via Firebase Console or CLI)
- [ ] Database migration tested (upgrade from previous version)
- [ ] AndroidManifest permissions and services declared
- [ ] Unit tests passing (70%+ coverage on repositories)
- [ ] Integration tests passing (Firebase Emulator)
- [ ] Accessibility testing completed (TalkBack verification)
- [ ] Performance profiling completed (startup time, memory, CPU)
- [ ] Localization strings added for all languages
- [ ] Listen Together compatibility verified (no conflicts)

### Rollback Plan

**If Critical Issues Found**:
1. Revert to previous APK version via Play Store rollback
2. Disable social features via remote config flag:
   ```kotlin
   val socialFeaturesEnabled = remoteConfig.getBoolean("social_features_enabled")
   if (!socialFeaturesEnabled) {
       // Hide social UI elements
       // Disable Firestore listeners
       // Stop WorkManager tasks
   }
   ```
3. Fix issues in hotfix branch
4. Deploy patched version within 24 hours

---


## Testing Strategy

### Testing Approach

This port does **not use property-based testing** because it primarily involves:
- **Infrastructure as Code**: Firebase configuration, package migration
- **Integration Work**: Porting existing implementations
- **UI Rendering**: Compose components and animations
- **Side-Effect Operations**: Database writes, Firestore sync, notifications
- **External Service Integration**: Firebase, YouTube Music API

Instead, the testing strategy focuses on:
1. **Unit Tests**: Business logic with mocked dependencies
2. **Integration Tests**: End-to-end flows with Firebase Emulator
3. **Snapshot Tests**: UI components
4. **Manual Testing**: Accessibility, performance, edge cases

### Unit Testing Strategy

**Target Coverage**: 70%+ for repository classes, 60%+ for ViewModels

**Test Structure**:
```
app/src/test/kotlin/com/metrolist/
├── social/
│   ├── SongSharingRepositoryTest.kt
│   ├── PlaybackProgressTrackerTest.kt
│   └── NotificationManagerTest.kt
├── viewmodels/
│   └── SyncViewModelTest.kt
└── ui/
    └── AudioVisualizerTest.kt
```

**Key Test Cases**:

**SongSharingRepository**:
- ✓ `sendSongsToFriends` creates Firestore documents with correct fields
- ✓ `sendSongsToFriends` handles auth failures gracefully
- ✓ `observeIncomingSongs` filters by current user UID
- ✓ `observeIncomingSongs` excludes completed songs
- ✓ `addSongToToListenPlaylist` detects duplicates correctly
- ✓ `addSongToToListenPlaylist` shifts playlist positions atomically
- ✓ `markSongAsListened` updates Firestore with timestamp
- ✓ `markSongAsCompleted` removes from local playlist and updates Firestore
- ✓ Firestore operations retry on network errors (exponential backoff)

**PlaybackProgressTracker**:
- ✓ Tracking starts only when playlist ID matches "to_listen_playlist_id"
- ✓ Tracking stops when user navigates away from To Listen playlist
- ✓ 50% milestone triggers `markSongAsListened` exactly once
- ✓ 100% milestone triggers `markSongAsCompleted` exactly once
- ✓ `maxProgressReached` handles seeking backward correctly
- ✓ `has50PercentTriggered` flag prevents duplicate notifications
- ✓ Tracking state resets on media item transition
- ✓ Handles `currentSentSongId` null case with retry logic

**SyncViewModel (JSON Import)**:
- ✓ `parseJsonFile` correctly deserializes valid JSON
- ✓ `parseJsonFile` throws SerializationException for invalid JSON
- ✓ `findOrCreatePlaylist` reuses existing playlist by name
- ✓ `findOrCreatePlaylist` creates new playlist if not exists
- ✓ `performJsonImport` detects duplicates and skips
- ✓ `performJsonImport` adds failed tracks to failed imports list
- ✓ `matchJsonTrackWithRetry` retries up to 2 times on failure
- ✓ `matchJsonTrackWithRetry` delays between retries (1s, 2s)
- ✓ Import progress updates correctly (N/Total)
- ✓ `cancelJsonImport` cancels in-progress import

**Notification Components**:
- ✓ `SongListenedNotificationManager.startWorker` schedules WorkManager task
- ✓ `SongListenedNotificationManager.stopWorker` cancels WorkManager task
- ✓ `SongListenedRealTimeNotifier` starts listener on user login
- ✓ `SongListenedRealTimeNotifier` stops listener on user logout
- ✓ `SongListenedNotificationWorker.doWork` queries Firestore correctly
- ✓ `SongListenedNotificationWorker.doWork` filters to last 24 hours
- ✓ Notification content includes song title and friend name
- ✓ Notification content handles missing data with fallbacks

**Testing Tools**:
- **Mocking**: MockK for Kotlin
- **Coroutines**: `kotlinx-coroutines-test` for testing suspend functions
- **Firebase**: Firebase Test SDK with in-memory Firestore

**Example Unit Test**:
```kotlin
@Test
fun `markSongAsListened updates Firestore with timestamp`() = runTest {
    // Arrange
    val mockFirestore = mockk<FirebaseFirestore>()
    val mockCollection = mockk<CollectionReference>()
    val mockDocument = mockk<DocumentReference>()
    
    every { mockFirestore.collection("sentSongs") } returns mockCollection
    every { mockCollection.document("doc123") } returns mockDocument
    
    val updateTask = mockk<Task<Void>>()
    every { mockDocument.update(any<Map<String, Any>>()) } returns updateTask
    coEvery { updateTask.await() } returns null
    
    val repository = SongSharingRepository(
        mockFirestore,
        mockk(relaxed = true),
        mockk(relaxed = true)
    )
    
    // Act
    repository.markSongAsListened("doc123")
    
    // Assert
    verify {
        mockDocument.update(match { map ->
            map["listenedAt"] != null && map["listenedAt"] is Long
        })
    }
}
```

### Integration Testing Strategy

**Environment**: Firebase Emulator Suite (Firestore, Auth, Messaging)

**Setup**:
```kotlin
@Before
fun setupEmulator() {
    FirebaseFirestore.getInstance().apply {
        useEmulator("localhost", 8080)
        firestoreSettings = firestoreSettings {
            isPersistenceEnabled = false // In-memory for tests
        }
    }
    
    FirebaseAuth.getInstance().apply {
        useEmulator("localhost", 9099)
    }
}

@After
fun clearEmulator() {
    // Clear Firestore data
    FirebaseFirestore.getInstance().clearPersistence()
}
```

**Key Integration Tests**:

**End-to-End Send and Receive**:
```kotlin
@Test
fun `song sent by user A appears in user B To Listen playlist`() = runTest {
    // Setup: Create two users
    val userA = createTestUser("userA@test.com")
    val userB = createTestUser("userB@test.com")
    
    // User A sends song
    signIn(userA)
    val repository = getSongSharingRepository()
    repository.sendSongsToFriends(
        listOf(createTestSong("song123")),
        listOf(userB.uid),
        mapOf(userB.uid to UserProfile(userB.uid, "UserB", null))
    )
    
    // User B receives song
    signIn(userB)
    val incomingSongs = repository.observeIncomingSongs().first()
    assertEquals(1, incomingSongs.size)
    assertEquals("song123", incomingSongs[0].songId)
    
    // Verify in local database
    val toListenPlaylist = database.playlistSongs(
        PlaylistEntity.TO_LISTEN_PLAYLIST_ID
    ).first()
    assertTrue(toListenPlaylist.any { it.song.id == "song123" })
}
```

**Progress Tracking to Firestore**:
```kotlin
@Test
fun `playback progress updates Firestore at 50 and 100 percent`() = runTest {
    // Setup: Pre-populate Firestore with sent song
    val sentSongRef = firestore.collection("sentSongs").add(
        SentSong(
            songId = "song123",
            toUid = currentUser.uid,
            fromUid = "sender123"
        ).toMap()
    ).await()
    
    // Add to To Listen playlist
    repository.addSongToToListenPlaylist(
        SentSong(id = sentSongRef.id, songId = "song123"),
        createTestMediaMetadata("song123")
    )
    
    // Start tracking
    val tracker = PlaybackProgressTracker(repository, database)
    tracker.onMediaItemTransition(
        createMediaItem("song123"),
        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
        PlaylistEntity.TO_LISTEN_PLAYLIST_ID
    )
    
    // Simulate 50% progress
    val player = createMockPlayer(
        mediaId = "song123",
        position = 30000L,
        duration = 60000L
    )
    tracker.trackProgress(player, this, PlaylistEntity.TO_LISTEN_PLAYLIST_ID)
    advanceUntilIdle()
    
    // Verify: listenedAt set
    val doc50 = sentSongRef.get().await()
    assertNotNull(doc50.get("listenedAt"))
    assertNull(doc50.get("completedAt"))
    
    // Simulate 100% progress
    player.seekTo(57000L) // 95% (triggers 100% milestone)
    tracker.trackProgress(player, this, PlaylistEntity.TO_LISTEN_PLAYLIST_ID)
    advanceUntilIdle()
    
    // Verify: completedAt set
    val doc100 = sentSongRef.get().await()
    assertNotNull(doc100.get("completedAt"))
    
    // Verify: Song removed from local playlist
    val playlist = database.playlistSongs(PlaylistEntity.TO_LISTEN_PLAYLIST_ID).first()
    assertFalse(playlist.any { it.song.id == "song123" })
}
```

**JSON Import Flow**:
```kotlin
@Test
fun `JSON import creates playlist and adds matched songs`() = runTest {
    // Create test JSON file
    val jsonContent = """
        [
          {"title": "Bohemian Rhapsody", "artist": "Queen"},
          {"title": "Imagine", "artist": "John Lennon"}
        ]
    """.trimIndent()
    val uri = createTempFileUri(jsonContent)
    
    // Mock YouTube Music search
    mockYouTubeMusicSearch(
        "Bohemian Rhapsody Queen" to SongItem(id = "song1", title = "Bohemian Rhapsody"),
        "Imagine John Lennon" to SongItem(id = "song2", title = "Imagine")
    )
    
    // Execute import
    val viewModel = SyncViewModel(...)
    viewModel.startJsonImport(uri, "Test Playlist")
    
    // Wait for completion
    viewModel.syncState.filter { it is SyncState.Success }.first()
    
    // Verify: Playlist created
    val playlists = database.playlists().first()
    val testPlaylist = playlists.find { it.playlist.name == "Test Playlist" }
    assertNotNull(testPlaylist)
    
    // Verify: Songs added
    val playlistSongs = database.playlistSongs(testPlaylist!!.id).first()
    assertEquals(2, playlistSongs.size)
    assertTrue(playlistSongs.any { it.song.id == "song1" })
    assertTrue(playlistSongs.any { it.song.id == "song2" })
}
```

**Notification Delivery**:
```kotlin
@Test
fun `notification shown when friend listens to song`() = runTest {
    // Setup: User A sends song to User B
    signIn(userA)
    repository.sendSongsToFriends(...)
    
    val sentSongRef = firestore.collection("sentSongs")
        .whereEqualTo("fromUid", userA.uid)
        .get().await()
        .documents.first()
    
    // User B listens (mark as listened)
    sentSongRef.reference.update("listenedAt", System.currentTimeMillis()).await()
    
    // Start real-time notifier for User A
    signIn(userA)
    val notifier = SongListenedRealTimeNotifier(context, repository, auth)
    
    // Wait for notification
    delay(2000) // Allow listener to process
    
    // Verify: Notification shown
    val notifications = shadowOf(notificationManager).allNotifications
    assertTrue(notifications.any { 
        it.contentTitle == "Friend listened to your song!"
    })
    
    // Verify: notificationSent marked in Firestore
    val updated = sentSongRef.reference.get().await()
    assertTrue(updated.getBoolean("notificationSent") ?: false)
}
```

### UI Testing Strategy

**Compose Testing**:
```kotlin
@Test
fun `AudioVisualizer renders waveform`() {
    composeTestRule.setContent {
        val audioData = FloatArray(64) { sin(it * 0.1f).toFloat() }
        AudioVisualizer(
            audioData = audioData,
            modifier = Modifier.size(300.dp, 100.dp)
        )
    }
    
    // Verify: Composable rendered without crash
    composeTestRule.onNodeWithTag("AudioVisualizer").assertExists()
    
    // Visual regression test (compare screenshot)
    composeTestRule.onRoot().captureToImage().assertAgainstGolden("audio_visualizer")
}

@Test
fun `Send to Friends dialog shows friend list`() {
    composeTestRule.setContent {
        SendToFriendsDialog(
            friends = listOf(
                FriendSelection("uid1", "Alice", null, false),
                FriendSelection("uid2", "Bob", null, false)
            ),
            onDismiss = {},
            onSend = {}
        )
    }
    
    composeTestRule.onNodeWithText("Alice").assertExists()
    composeTestRule.onNodeWithText("Bob").assertExists()
    composeTestRule.onNodeWithText("Send").assertExists()
}
```

**Accessibility Testing**:
- Manual testing with TalkBack screen reader
- Verify all interactive elements have semantic labels
- Verify touch target sizes >= 48dp
- Verify reduced motion accessibility setting respected

### Performance Testing

**Metrics to Track**:
1. **Startup Time**: App launch to first frame (target: <500ms increase)
2. **Memory Usage**: Heap allocation (target: <50MB increase)
3. **CPU Usage**: Audio visualizer impact (target: <5%)
4. **Frame Rate**: UI animations and visualizer (target: 60 FPS, 30 FPS minimum)
5. **Network Usage**: Firestore sync overhead (target: <1MB/day for typical usage)

**Tools**:
- Android Studio Profiler (CPU, Memory, Network)
- Systrace for frame timing analysis
- Firebase Performance Monitoring

**Test Scenarios**:
1. Cold start with social features enabled vs disabled
2. Memory allocation during JSON import of 100 tracks
3. CPU usage with audio visualizer active vs inactive
4. Network traffic during Firestore sync (50 songs)

### Manual Testing Checklist

**Social Features**:
- [ ] Send song to friend → friend receives in To Listen playlist
- [ ] Play song from To Listen playlist to 50% → sender notified
- [ ] Play song from To Listen playlist to 100% → song removed
- [ ] Background notification worker runs every 15 minutes
- [ ] Real-time notification shown when app is open
- [ ] To Listen playlist cannot be manually edited
- [ ] To Listen playlist cannot be deleted
- [ ] Social features do not interfere with Listen Together

**JSON Import**:
- [ ] Select JSON file from device storage
- [ ] Import creates new playlist with user-provided name
- [ ] Import appends to existing playlist if name matches
- [ ] Progress bar updates during import
- [ ] Failed imports dialog shows after completion
- [ ] Duplicate songs skipped with reason "Already in playlist"
- [ ] Cancel button stops in-progress import

**Audio Visualizer**:
- [ ] Visualizer renders waveform when enabled in settings
- [ ] Visualizer stops when playback paused
- [ ] Visualizer performance acceptable on low-end devices
- [ ] Visualizer color adapts to light/dark theme

**UI Improvements**:
- [ ] Blur effect renders correctly on player background
- [ ] Navigation animations smooth (no janky frames)
- [ ] Button press animations provide visual feedback
- [ ] Spacing consistent throughout UI (4dp base unit)

### Regression Testing

**Ensure No Breaking Changes**:
- [ ] Existing playlists intact after upgrade
- [ ] Existing songs playable after upgrade
- [ ] Existing settings preserved after upgrade
- [ ] Listen Together functionality unaffected
- [ ] Music Recognition feature unaffected
- [ ] Podcast playback unaffected
- [ ] Download manager unaffected

**Compatibility Testing**:
- [ ] Android 8.0 (API 24) - minimum supported version
- [ ] Android 14 (API 34) - current stable
- [ ] Android 15 (API 36) - target version
- [ ] Tablet devices (10" screen)
- [ ] Foldable devices (Galaxy Z Fold, Pixel Fold)

---

## Accessibility

### Semantic Labels

**Social Features**:
```kotlin
// Send to Friends Dialog
Checkbox(
    checked = friend.isSelected,
    onCheckedChange = { ... },
    modifier = Modifier.semantics {
        contentDescription = "Send to ${friend.username}"
    }
)

// To Listen Playlist
Text(
    text = "To Listen",
    modifier = Modifier.semantics {
        contentDescription = "To Listen playlist, contains songs sent by friends"
    }
)
```

**JSON Import**:
```kotlin
Button(
    onClick = { openFilePicker() },
    modifier = Modifier.semantics {
        contentDescription = "Import playlist from JSON file"
    }
) {
    Text("Import from JSON")
}
```

**Audio Visualizer**:
```kotlin
// Visualizer is decorative, provide text alternative
Text(
    text = "Playing: ${song.title}",
    modifier = Modifier.semantics {
        liveRegion = LiveRegionMode.Polite
    }
)
```

### Touch Target Sizes

All interactive elements sized >= 48dp:
```kotlin
IconButton(
    onClick = { ... },
    modifier = Modifier
        .size(48.dp)
        .minimumInteractiveComponentSize()
) {
    Icon(Icons.Default.Send, contentDescription = "Send")
}
```

### Color Contrast

Ensure WCAG AA compliance (4.5:1 for normal text, 3:1 for large text):
```kotlin
Surface(
    color = MaterialTheme.colorScheme.surface,
    contentColor = MaterialTheme.colorScheme.onSurface
) {
    Text("High contrast text")
}
```

### Reduced Motion Support

Respect user's reduced motion preference:
```kotlin
val reduceMotion = LocalAccessibilityManager.current?.isEnabled ?: false

val animationSpec = if (reduceMotion) {
    snap() // No animation
} else {
    tween(300, easing = FastOutSlowInEasing)
}

AnimatedVisibility(
    visible = isVisible,
    enter = fadeIn(animationSpec = animationSpec),
    exit = fadeOut(animationSpec = animationSpec)
) {
    // Content
}
```

---

## Localization

### String Resources

All user-facing strings defined in `strings.xml`:

```xml
<!-- Social Features -->
<string name="send_to_friends">Send to Friends</string>
<string name="to_listen_playlist">To Listen</string>
<string name="friend_listened_notification_title">Friend listened to your song!</string>
<string name="friend_listened_notification_body">%1$s listened to %2$s</string>
<string name="song_sent_success">Sent %1$d songs to %2$d friends</string>
<string name="song_sent_error">Failed to send songs: %1$s</string>

<!-- JSON Import -->
<string name="import_from_json">Import from JSON</string>
<string name="playlist_name_prompt">Enter playlist name</string>
<string name="playlist_name_hint">My Playlist</string>
<string name="import_progress">Matching %1$d/%2$d: %3$s</string>
<string name="failed_imports_dialog_title">Failed Imports</string>
<string name="failed_import_item">%1$s - %2$s\nReason: %3$s</string>

<!-- Audio Visualizer -->
<string name="player_background_blur">Blur</string>
<string name="player_background_color">Color</string>
<string name="player_background_visualizer">Visualizer</string>

<!-- Errors -->
<string name="error_not_logged_in">Please log in to use social features</string>
<string name="error_network">Network error, please try again</string>
<string name="error_invalid_json">Invalid JSON format</string>
<string name="error_no_tracks_found">No tracks found in JSON file</string>
```

### Plurals

```xml
<plurals name="songs_sent">
    <item quantity="one">Sent %d song</item>
    <item quantity="other">Sent %d songs</item>
</plurals>
```

### Date and Time Formatting

Use locale-aware formatting:
```kotlin
val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
    .withLocale(Locale.getDefault())

val formattedDate = LocalDateTime.ofEpochSecond(timestamp, 0, ZoneOffset.UTC)
    .format(formatter)
```

### RTL (Right-to-Left) Support

Ensure layouts work in RTL languages (Arabic, Hebrew):
```kotlin
Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.Start // Auto-flips to End in RTL
) {
    Icon(Icons.Default.Send, contentDescription = null)
    Spacer(Modifier.width(8.dp))
    Text("Send to Friends")
}
```

---

## Security and Privacy

### Firestore Security

**Security Rules** (already defined in Migration section):
- Users can only read songs sent to them (`toUid == auth.uid`)
- Users can only create songs from themselves (`fromUid == auth.uid`)
- Users can only update progress fields, not sender/recipient info
- All operations require authentication

**Data Validation**:
```javascript
function isValidSentSong() {
  return request.resource.data.keys().hasAll([
    'songId', 'songTitle', 'fromUid', 'toUid', 'sentAt'
  ]) && 
  request.resource.data.songTitle is string &&
  request.resource.data.songId is string &&
  request.resource.data.sentAt is timestamp;
}
```

### Authentication

**Token Refresh Handling**:
```kotlin
auth.addAuthStateListener { firebaseAuth ->
    if (firebaseAuth.currentUser == null) {
        // User logged out or token expired
        stopSocialFeatures()
        clearLocalCache()
    }
}
```

**Offline Token Storage**:
- Firebase handles token persistence securely
- Use EncryptedSharedPreferences for any additional sensitive data

### Data Privacy

**User Data Collected**:
- Firebase UID (anonymous identifier)
- Username (user-provided)
- Songs sent/received (metadata only, no playback data)
- Playback progress (50%/100% milestones only)

**Data Retention**:
- Songs marked as `completedAt` can be deleted after 30 days (implement cleanup job)
- Local database cleared on app uninstall
- Firestore data persists until manually deleted by user

**User Control**:
- Users can clear "To Listen" playlist (deletes local + marks completed in Firestore)
- Users can log out (stops all sync, clears local cache)
- Users can delete account (implement Firestore deletion on account delete)

### Network Security

**TLS/SSL**:
- All Firebase connections use TLS 1.2+
- Certificate pinning handled by Firebase SDK

**API Key Protection**:
- `google-services.json` embedded in APK (obfuscated via ProGuard)
- API keys restricted to Android app package name in Firebase Console

---

## Monitoring and Observability

### Logging

**Log Levels**:
```kotlin
Log.v(TAG, "Verbose: Detailed diagnostic info")
Log.d(TAG, "Debug: Development-time debugging")
Log.i(TAG, "Info: Important state changes")
Log.w(TAG, "Warning: Recoverable errors")
Log.e(TAG, "Error: Critical failures", exception)
```

**Key Log Points**:
- Social: Song send/receive, progress tracking, notifications
- JSON Import: Parse start/complete, match success/failure
- Playback: Tracking start/stop, milestone triggers
- Firebase: Connection state, auth state, sync errors

### Firebase Crashlytics

**Crash Reporting**:
```kotlin
FirebaseCrashlytics.getInstance().apply {
    setUserId(auth.currentUser?.uid ?: "anonymous")
    log("SongSharing: Sending ${songs.size} songs")
    recordException(exception)
}
```

### Firebase Performance Monitoring

**Custom Traces**:
```kotlin
val trace = Firebase.performance.newTrace("json_import")
trace.start()
try {
    performJsonImport(tracks, playlistId, playlistName)
    trace.putAttribute("track_count", tracks.size.toString())
    trace.putAttribute("success", "true")
} catch (e: Exception) {
    trace.putAttribute("success", "false")
    throw e
} finally {
    trace.stop()
}
```

**Metrics to Track**:
- `json_import_duration`: Time to complete JSON import
- `song_send_duration`: Time to send songs to Firestore
- `firestore_sync_latency`: Time from Firestore update to local UI update
- `progress_tracking_accuracy`: Difference between target milestone and actual trigger

### User Analytics (Optional)

**Events to Track**:
- `song_sent`: User sends song to friend
- `song_received`: User receives song in To Listen playlist
- `song_listened_50`: User reaches 50% of received song
- `song_completed`: User completes received song
- `json_import_started`: User starts JSON import
- `json_import_completed`: JSON import finishes (include track count)

**Privacy Considerations**:
- Do not log song titles, artist names, or usernames
- Use anonymous user IDs only
- Provide opt-out in settings

---

## Conclusion

This design document provides a comprehensive technical specification for porting OuterTune's social features, JSON playlist import, audio visualizer, and UI improvements to Metrolist. The design prioritizes:

1. **Compatibility**: Non-invasive integration that preserves Metrolist's existing features
2. **Performance**: Minimal impact on startup time, memory, and battery life
3. **Reliability**: Robust error handling, retry logic, and offline support
4. **Testability**: Comprehensive unit and integration testing strategy
5. **Accessibility**: Full support for screen readers, reduced motion, and touch target sizes
6. **Security**: Firestore security rules, authentication handling, and data privacy

The implementation follows Metrolist's existing architecture patterns (MVVM, Compose, Hilt, Room, Media3) and adheres to Material3 design guidelines for a cohesive user experience.

**Next Steps**:
1. Review and approve this design document
2. Create detailed implementation tasks from design sections
3. Set up Firebase project and deploy security rules
4. Begin package namespace migration
5. Implement core social features (SongSharingRepository, PlaybackProgressTracker)
6. Implement JSON import feature (SyncViewModel)
7. Implement audio visualizer and UI improvements
8. Write unit tests (70%+ coverage)
9. Write integration tests (Firebase Emulator)
10. Conduct accessibility and performance testing
11. Deploy to beta testers for validation
12. Release to production

**Estimated Timeline**:
- Phase 1: Setup and Package Migration (1 week)
- Phase 2: Social Features Implementation (3 weeks)
- Phase 3: JSON Import Implementation (1 week)
- Phase 4: Audio Visualizer and UI Improvements (1 week)
- Phase 5: Testing and QA (2 weeks)
- Phase 6: Beta Testing and Bug Fixes (1 week)
- **Total: 9 weeks**

**Risk Mitigation**:
- Parallel development tracks to reduce dependencies
- Feature flags for gradual rollout
- Firebase Emulator for local development and testing
- Comprehensive rollback plan for production issues
