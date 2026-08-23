# Playback Progress Tracker - Coroutine Scope Fix

## Problem Summary

**Bug**: Song sharing milestone handlers (50% and 95% completion) were not executing, causing Firestore updates to fail. The feature worked only on the first song, then stopped working for subsequent songs.

**Symptoms**:
- 50% milestone triggered but `listenedAt` field remained NULL in Firestore
- 95% milestone triggered but `completedAt` field remained NULL and song didn't disappear from playlist
- Only worked on first song, failed on all subsequent songs
- No error logs - handlers simply weren't executing

**Root Cause**: Milestone handlers were launched using `scope.launch(Dispatchers.IO)` where `scope` was passed from MusicService with `Dispatchers.Main`. Even though `Dispatchers.IO` was specified in the launch call, the parent scope's Main dispatcher prevented proper execution of IO operations.

## Solution

### The Fix

**File**: `app/src/main/java/com/dd3boh/outertune/playback/PlaybackProgressTracker.kt`

**Lines**: 220-235 (milestone handler launches)

**Change**: Create independent `CoroutineScope(Dispatchers.IO)` instead of using the passed scope.

```kotlin
// BEFORE (BROKEN):
if (!has100PercentTriggered && maxProgressReached >= 95f) {
    has100PercentTriggered = true
    has50PercentTriggered = true
    Log.i(TAG, "🏁 95% Completion Triggered for $currentTrackingSongId")
    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
        handle100PercentCompletion()
    }
} 
else if (!has50PercentTriggered && maxProgressReached >= 50f) {
    has50PercentTriggered = true
    Log.i(TAG, "🎯 50% Milestone Triggered for $currentTrackingSongId")
    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
        handle50PercentMilestone()
    }
}

// AFTER (FIXED):
if (!has100PercentTriggered && maxProgressReached >= 95f) {
    has100PercentTriggered = true
    has50PercentTriggered = true
    Log.i(TAG, "� 95% Completion Triggered for $currentTrac/kingSongId")
    // Use independent IO scope to avoid Main dispatcher issues
    CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
        handle100PercentCompletion()
    }
} 
else if (!has50PercentTriggered && maxProgressReached >= 50f) {
    has50PercentTriggered = true
    Log.i(TAG, "🎯 50% Milestone Triggered for $currentTrackingSongId")
    // Use independent IO scope to avoid Main dispatcher issues
    CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
        handle50PercentMilestone()
    }
}
```

## Why This Works

1. **Independent Scope**: Creating a new `CoroutineScope(Dispatchers.IO)` creates a completely independent coroutine context on the IO dispatcher
2. **No Parent Dispatcher Interference**: The new scope is not bound to MusicService's Main dispatcher, so IO operations execute properly
3. **Proper Async Execution**: Firestore operations (which are IO-bound) can now execute without being blocked by the Main thread

## Testing

**Test Scenario**:
1. Send song from User A to User B
2. User B plays song from "To Listen" playlist
3. Monitor logcat for milestone handler execution

**Expected Logs**:
```
03-19 02:32:38.385  [50% Milestone Triggered]
03-19 02:32:38.386  [50% Handler] Starting Firestore update
03-19 02:32:38.386  [50% Handler] Proceeding with Firestore update
03-19 02:32:38.578  [50% Handler] Firestore update completed successfully
```

**Verification**:
- ✅ `listenedAt` field updates in Firestore at 50%
- ✅ `completedAt` field updates in Firestore at 95%
- ✅ Song disappears from "To Listen" playlist after 95%
- ✅ Works consistently for multiple songs

## Related Code

**Handler Functions**:
- `handle50PercentMilestone()` - Lines 242-283
- `handle100PercentCompletion()` - Lines 285-330

**Calling Context**:
- `trackProgress()` - Lines 150-235 (where milestone handlers are launched)
- MusicService scope definition - `private val scope = CoroutineScope(Dispatchers.Main)` (line 166 in MusicService.kt)

## Prevention

To prevent similar issues in the future:

1. **Never rely on parent scope dispatcher** when launching IO operations
2. **Always create independent scopes** for IO-bound operations that need to execute reliably
3. **Use appropriate dispatchers**:
   - `Dispatchers.Main` - UI updates only
   - `Dispatchers.IO` - Network, database, file operations
   - `Dispatchers.Default` - CPU-intensive work

## Debugging Tips

If this breaks again, check:

1. **Logcat for handler execution**: Search for `[50% Handler]` or `[100% Handler]` logs
2. **Milestone trigger logs**: Verify `50% Milestone Triggered` or `95% Completion Triggered` appears
3. **Firestore updates**: Check if `listenedAt` and `completedAt` fields are being updated
4. **Scope dispatcher**: Verify the scope being used is on `Dispatchers.IO`, not `Dispatchers.Main`

## Commit Info

- **Date**: March 19, 2026
- **Files Modified**: `app/src/main/java/com/dd3boh/outertune/playback/PlaybackProgressTracker.kt`
- **Lines Changed**: 220-235 (milestone handler launches)
