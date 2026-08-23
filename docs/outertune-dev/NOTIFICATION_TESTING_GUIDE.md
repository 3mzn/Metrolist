# Notification System Testing Guide

## Can You Test Notifications with 2 Accounts on Same Device?

**Short Answer:** Yes, but only the **background notifications** (15-minute worker), not real-time notifications.

## Why Real-time Notifications Don't Work on Same Device

The real-time notification system (`SongListenedNotificationViewModel`) requires the app to be **open and logged in** to receive notifications. When you switch accounts on the same device:

1. You log out of Account A → Real-time listener stops
2. You log in to Account B → Account A's listener is no longer running
3. Account B plays song to 50% → Account A can't receive notification (not logged in)

**Real-time notifications require 2 separate devices.**

## How to Test Background Notifications on Same Device

The background worker runs every 15 minutes and checks Firebase even when the app is closed or you're logged out.

### Test Steps:

1. **Account A (Sender):**
   - Log in
   - Send song to Account B
   - **Stay logged in** (don't log out yet)

2. **Account B (Receiver) - Use Firebase Console:**
   - Go to Firebase Console → Firestore → `sentSongs` collection
   - Find the song sent to Account B
   - Manually set `listenedAt` to current timestamp (e.g., `1772994253979`)
   - This simulates Account B playing the song to 50%

3. **Account A (Sender):**
   - Wait up to 15 minutes
   - Should receive notification from background worker
   - Check logcat: `SongListenedWorker: Notification shown`

### Alternative: Force Worker to Run

To test immediately without waiting 15 minutes:

1. After setting `listenedAt` in Firebase
2. Force-stop the app completely
3. Reopen the app
4. The worker should run on app startup
5. Check for notification

## Testing with 2 Devices (Recommended)

### Real-time Notifications (App Open):

**Device A (Sender):**
1. Log in and keep app open
2. Send song to Device B user

**Device B (Receiver):**
1. Log in
2. Play song to 50% completion
3. Check logcat: `PlaybackProgressTracker: 🎯 50% milestone triggered`

**Device A (Sender):**
4. Should receive notification **instantly**
5. Check logcat: `SongListenedNotifVM: 🔔 Showing notification`
6. Notification should show: "[Friend Name] listened to [Song Title]"

### Background Notifications (App Closed):

**Device A (Sender):**
1. Log in
2. Send song to Device B user
3. **Close app completely** (swipe away from recent apps)

**Device B (Receiver):**
1. Log in
2. Play song to 50% completion

**Device A (Sender):**
3. Wait up to 15 minutes
4. Should receive notification even though app was closed
5. Check logcat: `SongListenedWorker: Notification shown`

## Logcat Commands

### Monitor Real-time Notifications:
```powershell
C:\Users\emanf\AppData\Local\Android\Sdk\platform-tools\adb.exe logcat | Select-String -Pattern "SongListenedNotifVM"
```

### Monitor Background Worker:
```powershell
C:\Users\emanf\AppData\Local\Android\Sdk\platform-tools\adb.exe logcat | Select-String -Pattern "SongListenedWorker"
```

### Monitor Playback Tracking:
```powershell
C:\Users\emanf\AppData\Local\Android\Sdk\platform-tools\adb.exe logcat | Select-String -Pattern "PlaybackProgressTracker"
```

### Check Recent Logs:
```powershell
C:\Users\emanf\AppData\Local\Android\Sdk\platform-tools\adb.exe logcat -d | Select-String -Pattern "SongListenedNotifVM|SongListenedWorker" | Select-Object -Last 50
```

## Expected Notification Behavior

### Notification Content:
- **Title:** "Friend listened to your song!"
- **Message:** "[Friend Name] listened to [Song Title]"

### Edge Cases:
- Missing friend name → "A friend listened to [Song Title]"
- Missing song title → "[Friend Name] listened to a song you sent"
- Missing both → Silently skipped (no notification)

### Notification Action:
- Tapping notification opens app to Social screen

## Troubleshooting

### Notifications Not Appearing:

1. **Check Firebase:**
   - Verify `listenedAt` is set (not null)
   - Verify `notificationSent` is false
   - Verify `sentAt` is within last 30 days

2. **Check Logcat:**
   - Look for errors in `SongListenedWorker` or `SongListenedNotifVM`
   - Verify worker is running: "Starting background notification worker"

3. **Check Android Settings:**
   - Notifications enabled for OuterTune
   - Battery optimization set to "Unrestricted" (recommended)

4. **Check Login Status:**
   - Worker only runs when user is logged in
   - Real-time listener only works when app is open

### Worker Not Running:

1. Check if user is logged in
2. Check if internet connection is available
3. Force-stop and reopen app to trigger worker
4. Check logcat for "Starting background notification worker"

## Summary

| Test Type | Same Device | 2 Devices | Notes |
|-----------|-------------|-----------|-------|
| Real-time Notifications | ❌ No | ✅ Yes | Requires app to be open |
| Background Notifications | ✅ Yes* | ✅ Yes | *Use Firebase Console to simulate |
| Full End-to-End | ❌ No | ✅ Yes | Best for complete testing |

**Recommendation:** Use 2 devices for complete testing. For same-device testing, manually set `listenedAt` in Firebase Console to test background worker.
