# Pending Tests

## ✅ COMPLETED TESTS (Single Device)

- **PLAYLIST IMMUTABILITY** ✅
  - ✅ Playlist name is NOT editable
  - ✅ Playlist is NOT deletable
  - ✅ Users cannot manually remove songs
  - ✅ Debug clear works (with Firebase caveat)

- **SONG REMOVAL ON LISTEN** ✅
  - ✅ Songs removed after 100% completion
  - ✅ PlaybackProgressTracker detects milestones correctly
  - ✅ Seeking backwards doesn't break tracking (max progress tracking)
  - ✅ markSongAsCompleted() removes songs from playlist

- **DATABASE UPDATES** ✅
  - ✅ Firestore `listenedAt` set at 50% milestone
  - ✅ Firestore `completedAt` set at 100% completion
  - ✅ Songs with `completedAt != null` are filtered out

---

## ⏳ PENDING TESTS (Require 2 Devices)

### 1. Real-time Notifications (App Open)
**Device A (Sender):**
- Log in and keep app open
- Send song to Device B user

**Device B (Receiver):**
- Log in
- Play song to 50% completion
- Check logcat: `PlaybackProgressTracker: 🎯 50% milestone triggered`

**Device A (Sender):**
- Should receive notification **instantly**
- Check logcat: `SongListenedNotifVM: 🔔 Showing notification`
- Verify notification shows: "[Friend Name] listened to [Song Title]"
- Tap notification → Should open Social screen

**What to verify:**
- Notification appears immediately (within seconds)
- Correct notification content
- Notification action works

---

### 2. Background Notifications (App Closed)
**Device A (Sender):**
- Log in
- Send song to Device B user
- **Close app completely** (swipe away from recent apps)

**Device B (Receiver):**
- Log in
- Play song to 50% completion

**Device A (Sender):**
- Wait up to 15 minutes
- Should receive notification even with app closed
- Check logcat: `SongListenedWorker: Notification shown`

**What to verify:**
- Notification appears within 15 minutes
- Worker runs even when app is closed
- Same notification format as real-time

---

### 3. Cross-Device Song Sharing & Completion
**Device A (Sender):**
- Send multiple songs to Device B

**Device B (Receiver):**
- Verify all songs appear in "To Listen" playlist
- Play songs to 100% completion
- Verify songs are removed from playlist automatically

**Device A (Sender):**
- Check Firebase for correct `listenedAt` and `completedAt` values
- Verify notifications received for each song at 50%

**What to verify:**
- Multiple songs handled correctly
- Playlist updates in real-time
- Firebase data is accurate

---

### 4. Edge Cases
**Test scenarios:**
- Send same song twice → Should appear twice in playlist
- Send song, complete it, send again → Should appear again (new instance)
- Logout/login → "To Listen" playlist should persist
- Multiple friends sending songs → All should appear in correct order
- Missing friend name → Shows "A friend"
- Missing song title → Shows "a song you sent"

---

### 5. Worker Lifecycle
**Test scenarios:**
- Worker starts on login
- Worker stops on logout
- Worker survives app restart
- Worker survives device reboot (if possible)
- Worker only runs with internet connection

**Check logcat for:**
- "Starting background notification worker" (on login)
- "Stopping background notification worker" (on logout)

---

## 📝 Logcat Commands for Testing

### Monitor Real-time Notifications (Device A):
```powershell
adb -s <device_A_serial> logcat | Select-String -Pattern "SongListenedNotifVM"
```

### Monitor Background Worker (Device A):
```powershell
adb -s <device_A_serial> logcat | Select-String -Pattern "SongListenedWorker"
```

### Monitor Playback Tracking (Device B):
```powershell
adb -s <device_B_serial> logcat | Select-String -Pattern "PlaybackProgressTracker"
```

### Check Recent Logs:
```powershell
adb logcat -d | Select-String -Pattern "SongListenedNotifVM|SongListenedWorker|PlaybackProgressTracker" | Select-Object -Last 50
```

---

## 📚 Reference Documents

- `NOTIFICATION_TESTING_GUIDE.md` - Detailed testing instructions
- `NOTIFICATION_SYSTEM_IMPLEMENTATION.md` - Technical implementation details
- `SEND_TO_FRIEND_IMPLEMENTATION.md` - Song sharing feature details

---

**Status:** All single-device tests complete. Ready for 2-device testing.
