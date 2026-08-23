# Spotify Public Playlist Import Feature - Problems Encountered

## Overview

This document details all the technical challenges and problems encountered while attempting to implement a Spotify public playlist import feature without using the official Spotify API. The feature was ultimately abandoned due to insurmountable technical barriers.

## Feature Goal

The intended feature was to:
1. Accept a Spotify public playlist URL from the user
2. Scrape the playlist data (name, cover, track listings) without authentication
3. Match each track with YouTube Music equivalents
4. Create a new playlist in OuterTune with the same name and cover
5. Add all matched songs to the new playlist

## Problems Encountered

### 1. No Embedded JSON in HTML Response

**Problem**: Spotify removed all easily parseable JSON data from their HTML responses.

**Details**:
- Previous scraping methods relied on `__NEXT_DATA__` (Next.js hydration state)
- Also tried `initial-state`, `Spotify.Entity`, and other embedded JSON patterns
- All of these have been removed or obfuscated in recent Spotify updates
- The initial HTML response now contains minimal data

**Attempted Solutions**:
- Searched for multiple JSON embedding patterns using regex
- Tried different URL formats (web player, embed, mobile)
- All failed to find parseable track data

**Impact**: Cannot extract playlist data from a simple HTTP GET request

---

### 2. JavaScript-Required Dynamic Loading

**Problem**: All playlist data is loaded dynamically via JavaScript/React after the initial page load.

**Details**:
- Spotify uses a React-based Single Page Application (SPA)
- Track listings are fetched via background XHR/Fetch requests
- Initial HTML contains only the app shell, no content
- Data is rendered client-side after JavaScript execution

**Attempted Solutions**:
- Tried fetching the embed page (`/embed/playlist/{id}`)
- Tried the oEmbed API endpoint
- Neither provided track listings

**Impact**: Simple HTTP clients cannot access the data without JavaScript execution

---

### 3. TLS Fingerprinting & Bot Detection

**Problem**: Spotify uses advanced TLS fingerprinting (JA3/JA4) to detect and block non-browser clients.

**Details**:
- Standard HTTP clients (Ktor, OkHttp, Python requests) have distinct TLS signatures
- Spotify's CDN/WAF detects these signatures and returns 403 Forbidden
- Even with correct User-Agent headers, requests are blocked
- This is a sophisticated anti-bot measure

**Attempted Solutions**:
- Custom OkHttp client with TLS 1.3/1.2 configuration
- User-Agent rotation (Chrome Windows, Chrome Android)
- Custom cipher suite ordering
- All still resulted in 403 errors

**Technical Details**:
```kotlin
// Attempted TLS configuration
val spec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
    .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
    .cipherSuites(/* modern cipher suites */)
    .build()
```

**Impact**: Cannot make successful HTTP requests to Spotify's web endpoints from Android

---

### 4. oEmbed API Limitations

**Problem**: Spotify's oEmbed endpoint only returns basic metadata, not track listings.

**Details**:
- Endpoint: `https://open.spotify.com/oembed?url=...`
- Returns: Playlist name, thumbnail URL, embed HTML
- Does NOT return: Track listings, artist info, album info

**Response Example**:
```json
{
  "title": "My Playlist",
  "thumbnail_url": "https://...",
  "html": "<iframe src='...'></iframe>"
}
```

**Impact**: Cannot use oEmbed as a data source for track listings

---

### 5. Embed API Limitations

**Problem**: The embed page doesn't expose track data in a parseable format.

**Details**:
- Embed URL: `https://open.spotify.com/embed/playlist/{id}`
- Returns an iframe-embeddable player
- Track data is loaded dynamically via JavaScript
- No `<script id="resource">` or similar JSON data tags found

**Attempted Solutions**:
- Searched for embedded JSON in various script tags
- Looked for data attributes on HTML elements
- Tried parsing the iframe source
- All failed to find track data

**Impact**: Embed page is not a viable data source

---

### 6. Requires Headless Browser

**Problem**: The only reliable solution is to use a full headless browser (Puppeteer, Playwright, Selenium).

**Why This Is Required**:
- Need JavaScript execution to load dynamic content
- Need to bypass TLS fingerprinting (browsers have legitimate signatures)
- Need to intercept XHR/Fetch requests to capture JSON responses
- Need to handle potential CAPTCHA challenges

**Why This Wasn't Implemented**:

**Complexity**:
- Requires embedding a full browser engine (Chromium) in the Android app
- Adds 50-100+ MB to APK size
- Complex setup and configuration

**Performance**:
- Very slow (5-10+ seconds per playlist)
- High CPU usage
- High memory usage (200+ MB per browser instance)
- Battery drain on mobile devices

**Reliability**:
- Breaks whenever Spotify changes their UI/DOM structure
- Requires constant maintenance
- Prone to timeouts and crashes
- Difficult to debug

**User Experience**:
- Long wait times
- Potential app freezes
- Poor mobile experience

**Example Implementation** (not used):
```kotlin
// Would require something like:
// - androidx.webkit.WebView with JavaScript injection
// - Or bundling Chromium via third-party libraries
// Both are impractical for this use case
```

**Impact**: Feature is too complex and unreliable for a mobile app

---

### 7. Anti-Scraping Measures

**Problem**: Spotify actively employs multiple anti-scraping techniques.

**Measures Detected**:
- Rate limiting (429 Too Many Requests)
- IP-based blocking
- CAPTCHA challenges (reCAPTCHA v3)
- Request pattern analysis
- Behavioral analysis

**Attempted Solutions**:
- Exponential backoff with delays (1.5-3.5 seconds)
- Randomized User-Agent rotation
- Request throttling
- Still resulted in blocks after multiple requests

**Impact**: Even if initial requests succeed, sustained scraping is unreliable

---

### 8. No Public API for Unauthenticated Access

**Problem**: Spotify's official API requires OAuth authentication for all playlist data access.

**Details**:
- Cannot fetch public playlist data without user login
- Requires Client ID, Client Secret, and OAuth flow
- No anonymous/public API endpoints for playlist tracks

**Why This Matters**:
- The existing Spotify API sync feature in OuterTune works well for authenticated users
- Users can already sync their liked songs via the official API
- The attempted feature was meant to import ANY public playlist without login
- This use case is explicitly not supported by Spotify

**Impact**: No official, supported way to achieve the feature goal

---

## Alternative Approaches Considered

### 1. Spotify Web API with User Authentication
- **Status**: Already implemented in OuterTune
- **Pros**: Official, reliable, supported
- **Cons**: Requires user login, only works for user's own playlists/liked songs

### 2. Third-Party Scraping Services
- **Status**: Not pursued
- **Pros**: Offloads complexity
- **Cons**: Costs money, privacy concerns, reliability issues, terms of service violations

### 3. User Manual Entry
- **Status**: Not pursued
- **Pros**: Simple, no scraping needed
- **Cons**: Poor UX, time-consuming for users

---

## Technical Implementation Attempted

### Files Created (All Deleted)
1. `SpotifyWebScraperRepository.kt` - Main scraping logic
2. `SpotifyPublicPlaylist.kt` - Data models
3. `ImportSpotifyPlaylistDialog.kt` - UI dialog for URL input

### Code Highlights

**TLS Fingerprinting Bypass Attempt**:
```kotlin
private val client = HttpClient(OkHttp) {
    engine {
        config {
            connectionSpecs(listOf(
                ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                    .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
                    .build()
            ))
        }
    }
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}
```

**User-Agent Rotation**:
```kotlin
private val userAgents = listOf(
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0",
    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile"
)
```

**Embedded JSON Extraction Attempts**:
```kotlin
// Tried multiple patterns:
val nextDataPattern = Regex("""<script id="__NEXT_DATA__" type="application/json">(.+?)</script>""")
val initialStatePattern = Regex("""<script>window\['__INITIAL_STATE__'\]\s*=\s*(.+?);</script>""")
val spotifyEntityPattern = Regex("""Spotify\.Entity\s*=\s*({.+?});""")
// All failed - data not present in HTML
```

---

## Lessons Learned

1. **Modern web scraping is extremely difficult**: Sites like Spotify have sophisticated anti-bot measures that are nearly impossible to bypass reliably.

2. **Mobile constraints matter**: Solutions that work on desktop (headless browsers) are often impractical on mobile due to size, performance, and battery concerns.

3. **Official APIs exist for a reason**: When a company provides an official API, it's usually the only reliable way to access their data.

4. **Scraping is fragile**: Even if scraping works initially, it breaks easily when the target site changes their structure.

5. **Terms of Service**: Web scraping often violates terms of service and can lead to legal issues.

---

## Conclusion

The Spotify public playlist import feature was abandoned due to:
- Technical impossibility without headless browser
- Poor performance and reliability concerns
- Violation of Spotify's terms of service
- Existing official API solution for authenticated users

**Recommendation**: Users should use the existing Spotify API sync feature, which works reliably for their own liked songs and playlists.

**UPDATE**: New feature has been added - JSON import sync. This allows the user to upload a JSON file with song metadata of X number of songs, which the app takes and matches with youtube, and saves. To get this JSON, a script must be injected into the browser tab having the required spotify playlist open. This script is stored in a .txt file in the project directory as "JS SCRAPE SCRIPT.txt"

---

## Date
March 8, 2026

## Status
Feature abandoned, all code removed from codebase
