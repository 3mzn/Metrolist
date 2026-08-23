# Comprehensive Testing Checklist

## Overview
This document consolidates ALL testing needed for the song sharing and notification features, plus general app stability testing.

---

## SECTION 1: Song Sharing Feature (Send to Friend)

### 1.1 Basic Functionality (Single Device)
- [ ] Open "Send to Friends" dialog from song menu
- [ ] Dialog displays list of friends
- [ ] Can select/deselect multiple friends
- [ ] "Send" button is disabled when no friends selected
- [ ] "Send" button is enabled when friends selected
- [ ] Song appears in recipient's "To Listen" playlist (verify in Firebase)
- [ ] Sent song has correct metadata (title, artist, duration, thumbnail)
- [ ] Can send same song to multiple friends at once
- [ ] Can send different songs to same friend

### 1.2 UI/UX (Single Device)
- [ ] Dialog closes after sending
- [ ] Success message/toast appears
- [ ] Can send from song menu (3-dot menu)
- [ ] Can send from selection mode (bulk send)
- [ ] Dialog shows friend names correctly
- [ ] Friend list updates if friends are added/removed
- [ ] No crashes when sending

### 1.3 Error Handling (Single Device)
- [ ] Graceful handling if no friends exist
- [ ] Graceful handling if network is offline
- [ ] Graceful handling if Firebase is unavailable
- [ ] Error message displayed to user
- [ ] App doesn't crash on error

---

## SECTION 2: "To Listen" Playlist (Receiver Side)

### 2.1 Playlist Immutability ✅ (Already Tested)
- [x] Playlist name is NOT editable
- [x] Playlist is NOT deletable
- [x] Songs cannot be manually removed
- [x] Debug clear option works (Settings → Experimental → Developer Settings)

### 2.2 Song Addition (Single Device)
- [ ] Received songs appear in "To Listen" playlist
- [ ] Songs appear in correct order (by sentAt timestamp)
- [ ] Duplicate songs can be added (same song sent twice = two entries)
- [ ] Songs from multiple friends appear together
- [ ] Playlist updates in real-time when new songs arrive
- [ ] No duplicate entries if same song received twice simultaneously

### 2.3 Song Removal on Completion ✅ (Already Tested)
- [x] Songs removed after 100% playback completion
- [x] Seeking backwards doesn't break tracking
- [x] PlaybackProgressTracker detects milestones correctly
- [x] Firestore `completedAt` is set correctly

### 2.4 Database Consistency (Single Device)
- [ ] Room database reflects Firebase state
- [ ] Songs with `completedAt != null` are filtered out
- [ ] Songs with `listenedAt != null` but `completedAt == null` remain in playlist
- [ ] Logout/login preserves "To Listen" playlist
- [ ] App restart preserves "To Listen" playlist

---

## SECTION 3: Playback Progress Tracking

### 3.1 Milestone Detection ✅ (Already Tested)
- [x] 50% milestone triggers `listenedAt` update in Firebase
- [x] 100% completion triggers `completedAt` update in Firebase
- [x] Seeking backwards doesn't break tracking
- [x] Max progress tracking prevents false completions

### 3.2 Edge Cases (Single Device)
- [ ] Pausing and resuming doesn't reset progress
- [ ] Skipping to next song doesn't mark as completed
- [ ] Skipping backwards doesn't reset progress
- [ ] Seeking to 99% then to 50% doesn't trigger completion
- [ ] Seeking to 100% manually triggers completion
- [ ] Rapid seeking doesn't cause multiple completions
- [ ] Progress tracking works with different audio qualities
- [ ] Progress tracking works with local files
- [ ] Progress tracking works with YouTube Music songs

### 3.3 Playback Scenarios (Single Device)
- [ ] Playing song from "To Listen" playlist
- [ ] Playing song from other playlists
- [ ] Playing song from search results
- [ ] Playing song from artist/album view
- [ ] Playing song in queue
- [ ] Playing song with repeat enabled
- [ ] Playing song with shuffle enabled

---

## SECTION 4: Real-Time Notifications (Requires 2 Devices)

### 4.1 Notification Delivery (App Open)
- [ ] Sender receives notification when receiver reaches 50%
- [ ] Notification appears within 2-3 seconds
- [ ] Notification shows correct friend name
- [ ] Notification shows correct song title
- [ ] Notification format: "[Friend Name] listened to [Song Title]"
- [ ] Notification has correct icon
- [ ] Notification sound/vibration works (if enabled)
- [ ] Multiple notifications don't stack (or stack correctly)

### 4.2 Notification Actions (App Open)
- [ ] Tapping notification opens app
- [ ] Notification opens Social screen (or relevant screen)
- [ ] Notification can be dismissed
- [ ] Notification can be swiped away
- [ ] Notification action doesn't crash app

### 4.3 Real-Time Behavior (App Open)
- [ ] Notification appears while app is in foreground
- [ ] Notification appears while app is in background (but running)
- [ ] Multiple songs reaching 50% generate multiple notifications
- [ ] Notifications appear in correct order
- [ ] Notifications don't appear for songs already completed

---

## SECTION 5: Background Notifications (Requires 2 Devices)

### 5.1 Worker Execution (App Closed)
- [ ] Worker runs every 15 minutes
- [ ] Notification appears within 15 minutes of 50% milestone
- [ ] Notification appears even with app completely closed
- [ ] Worker doesn't run if user is logged out
- [ ] Worker doesn't run if internet is offline
- [ ] Worker resumes after internet reconnects

### 5.2 Worker Lifecycle
- [ ] Worker starts on login
- [ ] Worker stops on logout
- [ ] Worker survives app restart
- [ ] Worker survives device reboot (if testable)
- [ ] Worker doesn't run multiple times simultaneously
- [ ] Worker cleans up properly on app uninstall

### 5.3 Background Notification Content
- [ ] Notification shows correct friend name
- [ ] Notification shows correct song title
- [ ] Notification format matches real-time notifications
- [ ] Notification has correct icon
- [ ] Notification is clickable and opens app

---

## SECTION 6: Cross-Device Synchronization (Requires 2 Devices)

### 6.1 Song Sharing Flow
- [ ] Device A sends song to Device B user
- [ ] Song appears in Device B's "To Listen" playlist
- [ ] Device B plays song to 50%
- [ ] Device A receives notification
- [ ] Device B completes song (100%)
- [ ] Song removed from Device B's "To Listen" playlist
- [ ] Device A receives second notification (optional, depends on design)
- [ ] Firebase shows correct `listenedAt` and `completedAt` timestamps

### 6.2 Multiple Songs
- [ ] Send 5 songs from Device A to Device B
- [ ] All 5 appear in Device B's "To Listen" playlist
- [ ] Device B plays each to 50%
- [ ] Device A receives 5 notifications
- [ ] Device B completes each song
- [ ] All 5 removed from Device B's "To Listen" playlist
- [ ] Firebase shows correct data for all 5

### 6.3 Multiple Friends
- [ ] Device A sends song to Device B and Device C
- [ ] Song appears in both Device B and Device C "To Listen" playlists
- [ ] Device B reaches 50% → Device A notified
- [ ] Device C reaches 50% → Device A notified (separate notification)
- [ ] Device B completes → removed from Device B's playlist
- [ ] Device C still has song in playlist
- [ ] Device C completes → removed from Device C's playlist

---

## SECTION 7: Edge Cases & Error Scenarios

### 7.1 Duplicate Sends (Requires 2 Devices)
- [ ] Send same song twice to same friend
- [ ] Both appear as separate entries in "To Listen" playlist
- [ ] Completing one doesn't remove the other
- [ ] Each generates separate notifications

### 7.2 Re-Send After Completion (Requires 2 Devices)
- [ ] Device B completes song, it's removed from playlist
- [ ] Device A sends same song again
- [ ] Song reappears in Device B's "To Listen" playlist
- [ ] Can be completed again
- [ ] Generates new notifications

### 7.3 Logout/Login Scenarios (Requires 2 Devices)
- [ ] Device B receives songs, logs out, logs back in
- [ ] "To Listen" playlist persists after logout/login
- [ ] Songs still show correct state (completed vs. not completed)
- [ ] Notifications still work after re-login
- [ ] Worker restarts after re-login

### 7.4 Missing Data Handling (Single Device)
- [ ] Missing friend name → Shows "A friend"
- [ ] Missing song title → Shows "a song you sent"
- [ ] Missing thumbnail → Shows placeholder
- [ ] Missing duration → Shows "0:00" or similar
- [ ] Corrupted Firebase data → Graceful handling

### 7.5 Network Issues (Requires 2 Devices)
- [ ] Send song while offline → Queued or error shown
- [ ] Receive notification while offline → Appears when online
- [ ] Worker runs when offline → Retries when online
- [ ] Rapid network changes don't break functionality

---

## SECTION 8: Firebase Data Integrity

### 8.1 Sent Songs Collection
- [ ] Document structure is correct
- [ ] All required fields present: songId, songTitle, songArtist, songDuration, thumbnailUrl, fromUid, fromUsername, toUid, sentAt, listenedAt, completedAt, notificationSent
- [ ] Data types are correct
- [ ] Timestamps are accurate
- [ ] No duplicate documents

### 8.2 Data Consistency
- [ ] `sentAt` is always set
- [ ] `listenedAt` is null until 50% milestone
- [ ] `completedAt` is null until 100% completion
- [ ] `notificationSent` is false initially, true after notification
- [ ] `fromUid` and `toUid` are different
- [ ] `fromUsername` matches sender's username
- [ ] `songId` is valid YouTube Music ID

### 8.3 Query Performance
- [ ] Queries for songs with `completedAt == null` are fast
- [ ] Queries for songs with `notificationSent == false` are fast
- [ ] No N+1 query problems
- [ ] Indexes are used correctly

---

## SECTION 9: UI/UX Polish

### 9.1 Visual Feedback
- [ ] Loading states shown during send
- [ ] Success/error messages clear and helpful
- [ ] Notification badges update in real-time
- [ ] "To Listen" playlist shows song count
- [ ] Empty state shown when no songs in "To Listen"

### 9.2 Accessibility
- [ ] All buttons have proper labels
- [ ] Notifications are readable by screen readers
- [ ] Touch targets are adequate size
- [ ] Colors have sufficient contrast
- [ ] No flashing or rapid animations

### 9.3 Performance
- [ ] Sending song doesn't freeze UI
- [ ] Receiving notification doesn't freeze UI
- [ ] Scrolling "To Listen" playlist is smooth
- [ ] No memory leaks during extended use
- [ ] Battery usage is reasonable

---

## SECTION 10: General App Stability

### 10.1 Crash Testing
- [ ] No crashes when sending songs
- [ ] No crashes when receiving notifications
- [ ] No crashes when playing "To Listen" songs
- [ ] No crashes on logout
- [ ] No crashes on app restart
- [ ] No crashes on device rotation

### 10.2 Memory & Performance
- [ ] No memory leaks after sending 100 songs
- [ ] No memory leaks after receiving 100 notifications
- [ ] App doesn't slow down over time
- [ ] Battery drain is acceptable
- [ ] Data usage is reasonable

### 10.3 Compatibility
- [ ] Works on Android 8 (API 24)
- [ ] Works on Android 12 (API 31)
- [ ] Works on Android 14 (API 34)
- [ ] Works on Android 15 (API 35)
- [ ] Works on different device sizes (phone, tablet)
- [ ] Works with different screen orientations

---

## SECTION 11: Documentation & Code Quality

### 11.1 Code Review
- [ ] Code follows OuterTune conventions
- [ ] Comments explain complex logic
- [ ] No dead code or TODOs
- [ ] Error handling is comprehensive
- [ ] Logging is appropriate (not too verbose)

### 11.2 Documentation
- [ ] Feature is documented in README
- [ ] API changes are documented
- [ ] Database schema changes are documented
- [ ] Firebase rules are documented
- [ ] Testing procedures are documented

---

## Testing Execution Plan

### Phase 1: Single Device (Can do now)
- Sections 1.1-1.3 (Basic functionality, UI/UX, error handling)
- Sections 2.1-2.4 (Playlist immutability, addition, removal, consistency)
- Sections 3.1-3.3 (Progress tracking)
- Sections 7.4 (Missing data handling)
- Sections 9.1-9.3 (UI/UX polish)
- Sections 10.1-10.3 (Stability)
- Sections 11.1-11.2 (Code quality)

### Phase 2: Two Devices (When ready)
- Sections 4.1-4.3 (Real-time notifications)
- Sections 5.1-5.3 (Background notifications)
- Sections 6.1-6.3 (Cross-device sync)
- Sections 7.1-7.3 (Edge cases)
- Sections 7.5 (Network issues)
- Sections 8.1-8.3 (Firebase data integrity)

---

## Test Results Template

For each test, record:
- **Test ID**: Section.Subsection.Number
- **Description**: What was tested
- **Result**: ✅ PASS / ❌ FAIL / ⚠️ PARTIAL
- **Notes**: Any observations or issues
- **Device**: Device model and Android version
- **Date**: When test was performed

Example:
```
Test ID: 4.1.1
Description: Sender receives notification when receiver reaches 50%
Result: ✅ PASS
Notes: Notification appeared within 2 seconds
Device: Pixel 6 Pro, Android 14
Date: 2026-03-08
```

---

## Known Issues & Workarounds

### Issue: Debug clear only clears Room DB, not Firebase
- **Workaround**: Manually delete Firebase documents if needed
- **Status**: Documented, not a bug

### Issue: Scrolling performance in large playlists
- **Workaround**: None yet (see PENDINGFIXES.md)
- **Status**: Pending fix

---

## Sign-Off

- [ ] All Phase 1 tests completed
- [ ] All Phase 2 tests completed
- [ ] No critical issues found
- [ ] Feature ready for release

---

**Last Updated:** March 8, 2026
**Status:** Ready for testing
