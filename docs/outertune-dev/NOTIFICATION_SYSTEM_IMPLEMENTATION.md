# Song Listened Notification System Implementation

## Overview
Hybrid pull-based notification system that notifies users when friends listen to songs they sent, working both when the app is open and closed.

## Components Implemented

### 1. Data Model Updates
**File:** `app/src/main/java/com/dd3boh/outertune/social/SongShareModels.kt`
- Added `notificationSent: Boolean = false` field to `SentSong` model
- Updated `toMap()` and `fromMap()` methods to include the new field

### 2. Repository Methods
**File:** `app/src/main/java/com/dd3boh/outertune/social/SongSharingRepository.kt`
- `getListenedSongsNeedingNotification()`: Fetches songs listened to but not yet notified (for background worker)
- `observeListenedSongsNeedingNotification()`: Real-time Flow for instant notifications (when app is open)
- `markNotificationSent()`: Marks a song as notified to prevent duplicates
- Both methods filter by:
  - `fromUid == currentUser.uid` (songs sent by current user)
  - `listenedAt != null` (friend reached 50% milestone)
  - `notificationSent == false` (not yet notified)
  - `sentAt >= (now - 30 days)` (only recent songs)

### 3. Background Worker
**File:** `app/src/main/java/com/dd3boh/outertune/social/SongListenedNotificationWorker.kt`
- Hilt-injected Worker that runs every 15 minutes
- Only runs when device has internet connection
- Fetches listened songs and shows local Android notifications
- Marks songs as notified after showing notification
- Handles edge cases:
  - Missing song data: silently skip
  - Missing friend name: show "A friend"
  - Missing song title: show "a song you sent"

### 4. Real-time Notification ViewModel
**File:** `app/src/main/java/com/dd3boh/outertune/viewmodels/SongListenedNotificationViewModel.kt`
- Monitors Firebase Auth state changes
- Starts Firestore listener when user logs in
- Stops listener when user logs out
- Shows instant notifications when app is open
- Uses same notification channel as background worker

### 5. Worker Manager
**File:** `app/src/main/java/com/dd3boh/outertune/social/SongListenedNotificationManager.kt`
- Manages WorkManager lifecycle
- `startWorker()`: Schedules periodic work (15-minute interval)
- `stopWorker()`: Cancels work when user logs out
- Uses `ExistingPeriodicWorkPolicy.KEEP` to avoid duplicate workers

### 6. Integration Points

#### App.kt
- Injects `SongListenedNotificationManager`
- Starts worker on app launch if user is logged in
- Stops worker in `forgetAccount()` when user logs out

#### AuthViewModel.kt
- Injects `SongListenedNotificationManager`
- Starts worker when user logs in (via auth state listener)
- Stops worker when user logs out

#### MainActivity.kt
- Initializes `SongListenedNotificationViewModel` to handle real-time notifications
- ViewModel lifecycle tied to activity lifecycle

### 7. String Resources
**File:** `app/src/main/res/values/strings-ot.xml`
- `friend_listened_notification_title`: "Friend listened to your song!"
- `song_listened_channel_name`: "Friend Listened Notifications"
- `song_listened_channel_description`: "Notifications when friends listen to songs you sent"

## How It Works

### When App is Open (Real-time)
1. `SongListenedNotificationViewModel` starts Firestore listener on user login
2. Listener observes songs where `listenedAt != null` and `notificationSent == false`
3. When detected, shows local notification immediately
4. Marks song as `notificationSent = true` in Firestore
5. Listener stops when user logs out

### When App is Closed (Background)
1. `SongListenedNotificationWorker` runs every 15 minutes
2. Checks if user is logged in (skips if not)
3. Queries Firestore for same conditions as real-time listener
4. Shows notifications for all unnotified songs
5. Marks each as `notificationSent = true`
6. Worker continues until user logs out

### Duplicate Prevention
- Both systems check `notificationSent` flag before showing notification
- Both systems mark flag as `true` after showing notification
- Firestore acts as single source of truth
- No race conditions possible

## Notification Behavior

### Notification Content
- **Title:** "Friend listened to your song!"
- **Message:** "[Friend Name] listened to [Song Title]"
- **Edge Cases:**
  - No friend name: "A friend listened to [Song Title]"
  - No song title: "[Friend Name] listened to a song you sent"
  - No data: Silently skip (no notification)

### Notification Action
- Tapping notification opens app to Social screen
- Uses `PendingIntent` with `navigate_to=social` extra

### Notification Channel
- **ID:** `song_listened_notifications`
- **Name:** "Friend Listened Notifications"
- **Importance:** Default
- **Auto-cancel:** Yes

## Testing Instructions

### Test Real-time Notifications (App Open)
1. User A: Log in and keep app open
2. User B: Log in on different device
3. User A: Send song to User B
4. User B: Play song to 50% completion
5. User A: Should receive notification instantly

### Test Background Notifications (App Closed)
1. User A: Log in, then close app completely
2. User B: Log in on different device
3. User A's friend: Send song to User A
4. User B: Play song to 50% completion
5. Wait up to 15 minutes
6. User A: Should receive notification even with app closed

### Test Worker Lifecycle
1. Log in: Worker should start
2. Check logs for "Starting background notification worker"
3. Log out: Worker should stop
4. Check logs for "Stopping background notification worker"

## Dependencies Required
- WorkManager (already in project via Media3)
- Hilt Worker support (needs to be added to build.gradle.kts)

## Build.gradle.kts Changes Needed
Add to dependencies:
```kotlin
implementation("androidx.hilt:hilt-work:1.1.0")
ksp("androidx.hilt:hilt-compiler:1.1.0")
```

## Notes
- Worker respects Android's battery optimization
- Minimum interval is 15 minutes (Android limitation)
- Worker only runs when device has internet
- Worker survives app restarts and device reboots
- Real-time listener provides instant notifications when app is open
- No Firebase Cloud Functions required (free tier compatible)
