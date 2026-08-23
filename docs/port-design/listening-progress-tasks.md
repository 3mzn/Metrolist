# Implementation Plan

- [x] 1. Write bug condition exploration test
  - **Property 1: Bug Condition** - Race Condition in Async Initialization
  - **CRITICAL**: This test MUST FAIL on unfixed code - failure confirms the bug exists
  - **DO NOT attempt to fix the test or the code when it fails**
  - **NOTE**: This test encodes the expected behavior - it will validate the fix when it passes after implementation
  - **GOAL**: Surface counterexamples that demonstrate the race condition exists
  - **Scoped PBT Approach**: For deterministic bugs, scope the property to the concrete failing case(s) to ensure reproducibility
  - Test scenario: Simulate rapid trackProgress() calls before checkAndStartTracking() completes
  - Assert that currentSentSongId is properly set when 50% milestone is reached
  - The test should verify: when 50% is reached before async check completes, milestone handler should still update Firestore
  - Run test on UNFIXED code
  - **EXPECTED OUTCOME**: Test FAILS (this is correct - it proves the race condition exists)
  - Document counterexamples found (e.g., "handle50PercentMilestone() returns early because currentSentSongId is null")
  - _Requirements: 1.1, 1.2_

- [x] 2. Write preservation property tests (BEFORE implementing fix)
  - **Property 2: Preservation** - Non-Buggy Input Behavior
  - **IMPORTANT**: Follow observation-first methodology
  - Observe behavior on UNFIXED code for non-buggy inputs (normal playback without race conditions)
  - Write property-based tests capturing observed behavior patterns from Preservation Requirements
  - Test that for songs NOT from "To Listen" playlist, no tracking occurs
  - Test that for songs from "To Listen" playlist with valid SentSong, tracking works correctly
  - Test that blacklist prevents redundant Firestore queries for failed songs
  - Property-based testing generates many test cases for stronger guarantees
  - Run tests on UNFIXED code
  - **EXPECTED OUTCOME**: Tests PASS (this confirms baseline behavior to preserve)
  - Mark task complete when tests are written, run, and passing on unfixed code
  - _Requirements: 2.1, 2.2_

- [x] 3. Fix race conditions in PlaybackProgressTracker

  - [x] 3.1 Fix async initialization race condition with AtomicBoolean semaphore
    - Add `AtomicBoolean trackingInitialized` to track initialization state atomically
    - Replace `isChecking` flag with proper atomic operations
    - Use compareAndSet for thread-safe initialization check
    - _Bug_Condition: isBugCondition(input) where trackProgress() races with checkAndStartTracking()_
    - _Expected_Behavior: expectedBehavior(result) where currentSentSongId is always set before milestone handlers execute_
    - _Preservation: Preservation Requirements from design_
    - _Requirements: 1.1, 1.2_

  - [x] 3.2 Clear blacklist on seek/replay events
    - Add `onSeekPerformed()` method to clear `lastFailedSongId` when user seeks
    - Add `onPlaybackRestarted()` method to clear blacklist on replay
    - Call these methods from MusicService when seek or replay is detected
    - _Bug_Condition: isBugCondition(input) where lastFailedSongId prevents legitimate tracking after seek_
    - _Expected_Behavior: expectedBehavior(result) where blacklist is cleared on user interaction_
    - _Preservation: Preservation Requirements from design_
    - _Requirements: 1.3_

  - [x] 3.3 Set isChecking flag before async work begins
    - Move `isChecking = true` to BEFORE launching the coroutine in trackProgress()
    - Ensure flag is set in the same synchronous block that checks the condition
    - This prevents duplicate tracking attempts during the async window
    - _Bug_Condition: isBugCondition(input) where isChecking is set after coroutine launch_
    - _Expected_Behavior: expectedBehavior(result) where flag is set synchronously before async work_
    - _Preservation: Preservation Requirements from design_
    - _Requirements: 1.4_

  - [x] 3.4 Add retry logic for milestone handlers when currentSentSongId is null
    - Modify `handle50PercentMilestone()` to retry tracking initialization if currentSentSongId is null
    - Add retry logic that calls checkAndStartTracking() synchronously within the handler
    - Implement exponential backoff or single retry with timeout
    - Add retry counter to prevent infinite loops
    - _Bug_Condition: isBugCondition(input) where handle50PercentMilestone() returns early due to null currentSentSongId_
    - _Expected_Behavior: expectedBehavior(result) where milestone handlers retry and eventually update Firestore_
    - _Preservation: Preservation Requirements from design_
    - _Requirements: 1.5_

  - [x] 3.5 Verify bug condition exploration test now passes
    - **Property 1: Expected Behavior** - Race Condition Fix Verification
    - **IMPORTANT**: Re-run the SAME test from task 1 - do NOT write a new test
    - The test from task 1 encodes the e    xpected behavior
    - When this test passes, it confirms the expected behavior is satisfied
    - Run bug condition exploration test from step 1
    - **EXPECTED OUTCOME**: Test PASSES (confirms bug is fixed)
    - _Requirements: Expected Behavior Properties from design_

  - [x] 3.6 Verify preservation tests still pass
    - **Property 2: Preservation** - Non-Buggy Input Behavior Verification
    - **IMPORTANT**: Re-run the SAME tests from task 2 - do NOT write new tests
    - Run preservation property tests from step 2
    - **EXPECTED OUTCOME**: Tests PASS (confirms no regressions)
    - Confirm all tests still pass after fix (no regressions)

- [x] 4. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.