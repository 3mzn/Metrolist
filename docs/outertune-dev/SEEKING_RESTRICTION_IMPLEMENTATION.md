# Seeking Restriction Implementation for "To Listen" Playlist

## Overview
Implemented seeking restrictions for the "To Listen" playlist to prevent users from spoofing progress milestones (50% and 100%) by manually seeking.

---

## Security Rationale

### Problem
Users could cheat the system by:
1. **Seeking to 50%** → Triggers `listenedAt` milestone → Sender gets notification (without actually listening)
2. **Seeking to 100%** → Triggers `completedAt` milestone → Song removed from playlist (without actually listening)

### Solution
Disable all seeking functionality when playing songs from the "To Listen" playlist.

---

## Implementation Details

### Files Modified

#### 1. `app/src/main/java/com/dd3boh/outertune/ui/player/Player.kt`
**Changes:**
- Added `isToListenPlaylist` check using `queueBoard.getCurrentQueue()?.playlistId`
- Disabled seek slider when `isToListenPlaylist == true`
- Disabled seek forward button (Fast Forward)
- Disabled seek backward button (Fast Rewind)

**Code Added:**
```kotlin
// Check if current playlist is "To Listen" - seeking should be disabled
val isToListenPlaylist = remember(queueBoard) {
    derivedStateOf {
        queueBoard.getCurrentQueue()?.playlistId == com.dd3boh.outertune.db.entities.PlaylistEntity.TO_LISTEN_PLAYLIST_ID
    }
}.value
```

**Slider:**
```kotlin
// Wrap slider in Box with pointerInput blocker
Box(
    modifier = Modifier
        .fillMaxWidth()
        .then(
            if (isToListenPlaylist) {
                Modifier.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            } else {
                Modifier
            }
        )
) {
    Slider(
        // ...
        enabled = !isLiveMode && !isToListenPlaylist,
        onValueChange = {
            if (!isLiveMode && !isToListenPlaylist) {
                sliderPosition = it.toLong()
            }
        },
        onValueChangeFinished = {
            if (!isLiveMode && !isToListenPlaylist) {
                // seek logic
            }
        }
    )
}
```

**Seek Buttons:**
```kotlin
ResizableIconButton(
    icon = Icons.Rounded.FastRewind,
    enabled = playerConnection.player.currentMediaItem != null && !isToListenPlaylist,
    // ...
)

ResizableIconButton(
    icon = Icons.Rounded.FastForward,
    enabled = playerConnection.player.currentMediaItem != null && !isToListenPlaylist,
    // ...
)
```

---

#### 2. `app/src/main/java/com/dd3boh/outertune/ui/player/Queue.kt`
**Changes:**
- Added `isToListenPlaylist` check in `QueueContent` composable
- Disabled seek forward button in queue view
- Disabled seek backward button in queue view

**Code Added:**
```kotlin
// Check if current playlist is "To Listen" - seeking should be disabled
val isToListenPlaylist = remember(qb) {
    derivedStateOf {
        qb.getCurrentQueue()?.playlistId == com.dd3boh.outertune.db.entities.PlaylistEntity.TO_LISTEN_PLAYLIST_ID
    }
}.value
```

**Seek Buttons:**
```kotlin
ResizableIconButton(
    icon = Icons.Rounded.FastRewind,
    enabled = !isToListenPlaylist,
    // ...
)

ResizableIconButton(
    icon = Icons.Rounded.FastForward,
    enabled = !isToListenPlaylist,
    // ...
)
```

---

#### 3. `app/src/main/java/com/dd3boh/outertune/ui/component/Lyrics.kt`
**Changes:**
- Added `isToListenPlaylist` check in `Lyrics` composable
- Disabled lyrics clicking (which triggers seeking)

**Code Added:**
```kotlin
// Check if current playlist is "To Listen" - seeking should be disabled
val qb by playerConnection.service.queueBoard.collectAsState()
val isToListenPlaylist = remember(qb) {
    derivedStateOf {
        qb.getCurrentQueue()?.playlistId == com.dd3boh.outertune.db.entities.PlaylistEntity.TO_LISTEN_PLAYLIST_ID
    }
}.value
```

**Lyrics Clicking:**
```kotlin
.clickable(enabled = isSynced && lyricsClickable && !isToListenPlaylist) {
    playerConnection.player.seekTo(item.start.toLong())
    // ...
}
```

---

## What's Disabled

When playing from "To Listen" playlist:

### Player Screen
- ❌ Seek slider (progress bar)
- ❌ Fast forward button (⏩)
- ❌ Fast rewind button (⏪)
- ✅ Play/pause still works
- ✅ Skip next/previous still works

### Queue Screen
- ❌ Fast forward button (⏩)
- ❌ Fast rewind button (⏪)
- ✅ Play/pause still works
- ✅ Skip next/previous still works

### Lyrics View
- ❌ Clicking on lyrics to seek
- ✅ Lyrics still display and scroll

---

## Technical Approach

### Playlist Detection
Uses `queueBoard.getCurrentQueue()?.playlistId` to check if current playlist matches `PlaylistEntity.TO_LISTEN_PLAYLIST_ID` ("LP_TO_LISTEN").

### Reactive Updates
Uses `derivedStateOf` to automatically update when the queue changes, ensuring the restriction applies/removes dynamically.

### UI Feedback
Disabled buttons appear grayed out, providing visual feedback that seeking is not available.

---

## Testing Checklist

### Basic Functionality
- [ ] Play song from "To Listen" playlist
- [ ] Verify seek slider is disabled (grayed out)
- [ ] Verify fast forward button is disabled
- [ ] Verify fast rewind button is disabled
- [ ] Verify lyrics clicking is disabled
- [ ] Verify play/pause still works
- [ ] Verify skip next/previous still works

### Milestone Tracking
- [ ] Play song to 50% without seeking
- [ ] Verify `listenedAt` is set in Firebase
- [ ] Verify notification is sent to sender
- [ ] Play song to 100% without seeking
- [ ] Verify `completedAt` is set in Firebase
- [ ] Verify song is removed from playlist

### Edge Cases
- [ ] Switch from "To Listen" to another playlist → Seeking re-enabled
- [ ] Switch from another playlist to "To Listen" → Seeking disabled
- [ ] Play song from "To Listen" in queue view → Seeking disabled
- [ ] Play song from "To Listen" with lyrics open → Clicking disabled

### User Experience
- [ ] Disabled buttons are visually distinct (grayed out)
- [ ] No crashes when attempting to interact with disabled controls
- [ ] Smooth transition when switching playlists

---

## Security Impact

### Before Implementation
- ⚠️ Users could seek to 50% → Fake notification
- ⚠️ Users could seek to 100% → Fake completion
- ⚠️ Users could skip listening entirely

### After Implementation
- ✅ Users must listen to at least 50% to trigger notification
- ✅ Users must listen to 100% to complete song
- ✅ Seeking is completely disabled for "To Listen" playlist
- ✅ Lyrics clicking (which seeks) is also disabled

---

## Limitations

### What's Still Possible
- ✅ Users can skip to next song (but that song won't be marked as completed)
- ✅ Users can pause and resume (progress tracking continues)
- ✅ Users can adjust volume, playback speed, etc.

### What's NOT Possible
- ❌ Seeking forward to skip ahead
- ❌ Seeking backward to replay
- ❌ Clicking lyrics to jump to specific time
- ❌ Using seek slider to manually set position

---

## Future Considerations

### Potential Enhancements
1. **Visual indicator**: Show a lock icon or message explaining why seeking is disabled
2. **Toast message**: Display a message when user tries to seek: "Seeking is disabled for To Listen playlist"
3. **Settings toggle**: Allow users to disable this restriction (if desired)
4. **Android Auto**: Verify seeking is also disabled in Android Auto interface

### Known Issues
- None currently identified

---

## Related Files

- `PlaybackProgressTracker.kt` - Tracks 50% and 100% milestones
- `SongSharingRepository.kt` - Manages "To Listen" playlist
- `PlaylistEntity.kt` - Defines `TO_LISTEN_PLAYLIST_ID` constant

---

**Implementation Date:** March 8, 2026
**Status:** Complete - FIXED (Slider blocking issue resolved)
**Security Level:** HIGH - Prevents milestone spoofing

---

## Bug Fix: Slider Still Allowing Seeking

### Issue Discovered
After initial implementation, the slider was still allowing manual seeking despite `enabled = false` and the `pointerInput` blocker.

### Root Cause
The `pointerInput` modifier was calling `awaitPointerEvent()` but not consuming the events. This allowed touch events to pass through to the Slider component.

### Solution
Modified the `pointerInput` blocker to consume all pointer events:

```kotlin
Box(
    modifier = Modifier
        .fillMaxWidth()
        .then(
            if (isToListenPlaylist) {
                Modifier.pointerInput(Unit) {
                    // Block all touch events when in To Listen playlist
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            // Consume all pointer events to prevent Slider from receiving them
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            } else {
                Modifier
            }
        )
) {
    Slider(...)
}
```

### Key Change
- **Before:** `awaitPointerEvent()` (events passed through)
- **After:** `event.changes.forEach { it.consume() }` (events blocked)

This ensures that when in "To Listen" playlist, the Slider receives no touch events at all, making it completely non-interactive.
