# Pending Fixes

## Fix #1: Optimize Download State Flow Collectors (Bottleneck #1)

**Priority:** 🟡 MEDIUM

**Status:** Not Started

**Issue:** Severe scrolling lag in large playlists with many downloaded songs

---

## Problem Summary

When scrolling fast in playlists with 100+ songs and 50+ downloaded items, the app experiences severe lag and framerate drops. The primary culprit is **per-item Flow collectors** for download state checking.

### Root Cause
Every visible song item creates its own Flow collector:
```kotlin
val download by LocalDownloadUtil.current.getDownload(song.id).collectAsState(initial = null)
```

With 50+ items visible during fast scrolling:
- 50+ active coroutines running simultaneously
- Each subscribes to the global `downloads` StateFlow
- When ANY download changes, ALL collectors get notified and recompose
- Constant creation/disposal during scroll creates "thrashing"

### Performance Impact
- **Current frame time**: 250-450ms (with 50 items visible)
- **Target frame time**: 16ms (60fps) or 11ms (90fps)
- **Result**: 15-40x over budget = severe lag
- **Estimated fix impact**: 60-70% performance improvement

---

## Solution: Derived State Pattern

### Approach (Recommended)
Replace per-item Flow collectors with a single derived state that updates once per frame.

### Implementation Strategy

**Step 1: Create derived state in LocalPlaylistScreen**
```kotlin
val downloadStates by remember {
    derivedStateOf {
        LocalDownloadUtil.current.downloads.collectAsState(initial = emptyMap()).value
    }
}
```

**Step 2: Pass to SongListItem as parameter**
```kotlin
SongListItem(
    song = song.song,
    downloadState = downloadStates[song.id],  // Pass instead of collecting
    // ... other params
)
```

**Step 3: Update SongListItem signature**
```kotlin
fun SongListItem(
    song: Song,
    downloadState: LocalDateTime? = null,  // New parameter
    // ... other params
)
```

**Step 4: Use parameter instead of collecting**
```kotlin
if (showDownloadIcon && !song.song.isLocal) {
    Icon.Download(downloadState)  // Use parameter, no Flow collector
}
```

**Step 5: Update SongGridItem similarly**
- Apply same pattern to grid view rendering
- Ensure consistency across all song item renderings

### Files to Modify
1. `app/src/main/java/com/dd3boh/outertune/ui/screens/playlist/LocalPlaylistScreen.kt`
   - Add derived state for download states
   - Pass to SongListItem

2. `app/src/main/java/com/dd3boh/outertune/ui/component/items/SongItems.kt`
   - Update SongListItem signature to accept downloadState parameter
   - Remove Flow collector from badges
   - Use parameter instead

3. `app/src/main/java/com/dd3boh/outertune/ui/component/items/Items.kt`
   - Update SongGridItem similarly
   - Update MediaMetadataListItem if applicable

---

## Risk Assessment

### Risk Level: 🟢 LOW

| Aspect | Rating | Notes |
|--------|--------|-------|
| Implementation Complexity | 🟢 LOW | Straightforward parameter passing |
| Testing Effort | 🟡 MEDIUM | Need to verify real-time updates |
| Reversibility | 🟢 EASY | Can revert in minutes if issues arise |
| Likelihood of Bugs | 🟢 LOW | Using proven Compose patterns |

### Potential Complications

1. **Stale State Risk** ⚠️
   - Items might show outdated download status
   - **Mitigation**: `derivedStateOf` automatically tracks dependencies
   - **Testing**: Verify download status updates in real-time while scrolling

2. **Recomposition Behavior Changes** ⚠️
   - All items might recompose when ANY download state changes
   - **Mitigation**: Use `key` properly (already done) to minimize recomposition
   - **Testing**: Profile with Compose compiler reports

3. **Memory Leaks** ⚠️
   - If Flow collectors aren't properly disposed
   - **Mitigation**: `derivedStateOf` and `collectAsState` handle disposal automatically
   - **Testing**: Monitor memory usage during long scrolling sessions

4. **Race Conditions** ⚠️
   - Download state might change between reading and rendering
   - **Mitigation**: This is already a problem with current approach
   - **Testing**: Stress test with rapid downloads/deletions

---

## Testing Strategy

### Pre-Implementation Testing
- [ ] Baseline performance: Profile current scrolling with Android Profiler
- [ ] Measure frame time with 200+ song playlist, 50+ downloaded

### Post-Implementation Testing
- [ ] Scroll fast in large playlist with many downloaded songs
- [ ] Verify download icons update in real-time
- [ ] Check frame rate with Android Profiler (should improve 60-70%)
- [ ] Monitor memory usage during long scrolling sessions
- [ ] Test download/delete operations during scroll
- [ ] Verify on device rotation (configuration changes)
- [ ] Test with different playlist sizes (50, 100, 200, 500 songs)

### Logcat Verification
```bash
# Monitor download state updates
adb logcat | grep -i "download"

# Check for excessive recompositions
adb logcat | grep -i "recompose"
```

### Android Profiler Checks
- Frame time should drop from 250-450ms to ~100-150ms
- CPU usage should decrease during scroll
- Memory allocations should be more stable

---

## Why This Fix Is Worth It

✅ **High Impact**: Fixes 60-70% of the scrolling performance issue
✅ **Low Risk**: Proven Compose pattern, easy to revert
✅ **Manageable Scope**: Localized changes to a few files
✅ **Easy Testing**: Can verify with profiler and visual inspection
✅ **User Experience**: Dramatically improves usability in large playlists

---

## Implementation Checklist

- [ ] Create derived state in LocalPlaylistScreen
- [ ] Update SongListItem signature and implementation
- [ ] Update SongGridItem signature and implementation
- [ ] Update MediaMetadataListItem if applicable
- [ ] Search for other places using `getDownload().collectAsState()`
- [ ] Build and test on device
- [ ] Profile with Android Profiler
- [ ] Verify real-time download status updates
- [ ] Test edge cases (rapid downloads, deletions, etc.)
- [ ] Document changes in code comments

---

## Related Documentation

- `SCROLLING_PERFORMANCE_ANALYSIS.md` - Full technical analysis of all three bottlenecks
- `DownloadUtil.kt` - Download state management implementation
- `SongItems.kt` - Current SongListItem implementation

---

## Notes

- This fix addresses only Bottleneck #1 (per-item Flow collectors)
- Bottleneck #2 (image loading) and #3 (layout complexity) remain for future optimization
- Combined with other fixes, could achieve acceptable scrolling performance
- This is a core architectural improvement that benefits all playlist screens

---

**Last Updated:** March 8, 2026
**Status:** Ready for implementation when approved
