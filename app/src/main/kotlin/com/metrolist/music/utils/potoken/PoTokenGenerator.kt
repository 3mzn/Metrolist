package com.metrolist.music.utils.potoken

import android.webkit.CookieManager
import com.metrolist.music.utils.cipher.CipherDeobfuscator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber

class PoTokenGenerator {
    private val TAG = "PoTokenGenerator"

    private val webViewSupported by lazy { runCatching { CookieManager.getInstance() }.isSuccess }
    private var webViewBadImpl = false // whether the system has a bad WebView implementation

    private val webPoTokenGenLock = Mutex()
    private var webPoTokenSessionId: String? = null
    private var webPoTokenStreamingPot: String? = null
    private var webPoTokenGenerator: PoTokenWebView? = null

    fun getWebClientPoToken(videoId: String, sessionId: String): PoTokenResult? =
        runPoTokenGuarded { getWebClientPoToken(videoId, sessionId, forceRecreate = false) }

    /**
     * Session-level player-request PoToken WITHOUT minting a per-video token.
     *
     * Metadata-only player calls send only [PoTokenResult.playerRequestPoToken] — the session
     * streaming pot — and discard the per-video token, so minting one per song serializes every
     * fetch through the single PoToken WebView for no wire-visible difference. This returns the
     * same cached session pot the per-video path would have sent. Callers that need the
     * per-video streaming token (playback) must keep using [getWebClientPoToken].
     */
    fun getSessionPoToken(sessionId: String): String? =
        runPoTokenGuarded { ensureSessionGenerator(sessionId, forceRecreate = false).second }

    /**
     * Shared blocking/timeout/cleanup wrapper for the public entry points. Behavior is the
     * pre-refactor behavior of [getWebClientPoToken]: WebView availability gate, 8s cap with
     * generator teardown on timeout, permanent disable on [BadWebViewException], rethrow of
     * anything else (e.g. [PoTokenException]).
     */
    private fun <T : Any> runPoTokenGuarded(action: suspend () -> T): T? {
        Timber.tag(TAG).d("runPoTokenGuarded called")
        Timber.tag(TAG).d("WebView state: supported=$webViewSupported, badImpl=$webViewBadImpl")
        if (!webViewSupported || webViewBadImpl) {
            Timber.tag(TAG).d("WebView not available: supported=$webViewSupported, badImpl=$webViewBadImpl")
            return null
        }

        return try {
            Timber.tag(TAG).d("Calling runBlocking to generate poToken (timeout=${POTOKEN_TIMEOUT_MS}ms)...")
            runBlocking {
                withTimeout(POTOKEN_TIMEOUT_MS) {
                    action()
                }
            }
        } catch (e: TimeoutCancellationException) {
            // The WebView's sandboxed process can be culled by the OS (storage pressure, low
            // memory, etc.) which leaves the PoToken WebView call hung indefinitely. Cap it so
            // playerResponseForPlayback can fall through to non-PoToken fallback clients (e.g.
            // ANDROID_VR) instead of blocking the entire playback path.
            Timber.tag(TAG).w("poToken generation timed out after ${POTOKEN_TIMEOUT_MS}ms; proceeding without PoToken")
            runBlocking {
                webPoTokenGenLock.withLock {
                    try {
                        withContext(Dispatchers.Main) {
                            webPoTokenGenerator?.close()
                        }
                    } catch (closeEx: Exception) {
                        Timber.tag(TAG).e(closeEx, "Exception closing PoTokenWebView during timeout cleanup")
                    }
                    webPoTokenGenerator = null
                    webPoTokenStreamingPot = null
                    webPoTokenSessionId = null
                }
            }
            null
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "poToken generation exception: ${e.javaClass.simpleName}: ${e.message}")
            when (e) {
                is BadWebViewException -> {
                    Timber.tag(TAG).e(e, "Could not obtain poToken because WebView is broken")
                    webViewBadImpl = true
                    null
                }
                else -> throw e // includes PoTokenException
            }
        }
    }

    private companion object {
        // Healthy cold-start (WebView spin-up + botguard JS + token gen) is ~2–5s in practice;
        // 8s leaves slack for a slow device without making the user wait too long before the
        // fallback chain (ANDROID_VR, etc.) takes over when the WebView hangs.
        const val POTOKEN_TIMEOUT_MS = 8_000L
    }

    /**
     * @param forceRecreate whether to force the recreation of [webPoTokenGenerator], to be used in
     * case the current [webPoTokenGenerator] threw an error last time
     * [PoTokenWebView.generatePoToken] was called
     */
    private suspend fun getWebClientPoToken(videoId: String, sessionId: String, forceRecreate: Boolean): PoTokenResult {
        Timber.tag(TAG).d("Web poToken requested: videoId=$videoId, sessionId=$sessionId")

        val (poTokenGenerator, streamingPot, hasBeenRecreated) =
            ensureSessionGenerator(sessionId, forceRecreate)

        val playerPot = try {
            poTokenGenerator.generatePoToken(videoId)
        } catch (throwable: Throwable) {
            if (hasBeenRecreated) {
                // the poTokenGenerator has just been recreated (and possibly this is already the
                // second time we try), so there is likely nothing we can do
                throw throwable
            } else {
                // retry, this time recreating the [webPoTokenGenerator] from scratch;
                // this might happen for example if the app goes in the background and the WebView
                // content is lost
                Timber.tag(TAG).e(throwable, "Failed to obtain poToken, retrying")
                return getWebClientPoToken(videoId = videoId, sessionId = sessionId, forceRecreate = true)
            }
        }

        Timber.tag(TAG).d("poToken generated successfully: session=${streamingPot.take(20)}..., video=${playerPot.take(20)}...")

        return PoTokenResult(
            playerRequestPoToken = streamingPot,
            streamingDataPoToken = playerPot,
        )
    }

    /**
     * Ensures a live session generator and returns (generator, session streaming pot,
     * recreatedNow). Recreation covers: forced, first use, expiry, dead renderer, and session
     * change. Committed state is cleared BEFORE the fallible creation steps so a failure
     * leaves shouldRecreate=true for the next call instead of a half-updated session.
     */
    private suspend fun ensureSessionGenerator(
        sessionId: String,
        forceRecreate: Boolean,
    ): Triple<PoTokenWebView, String, Boolean> =
        webPoTokenGenLock.withLock {
            val shouldRecreate =
                forceRecreate || webPoTokenGenerator == null || webPoTokenGenerator!!.isExpired ||
                    // Renderer died (OOM kill) — recreate proactively instead of letting the
                    // first post-crash generatePoToken() fail against the dead instance.
                    webPoTokenGenerator!!.isDead ||
                    webPoTokenSessionId != sessionId

            if (shouldRecreate) {
                Timber.tag(TAG).d("Creating new PoTokenWebView (forceRecreate=$forceRecreate)")

                withContext(Dispatchers.Main) {
                    webPoTokenGenerator?.close()
                }

                // Clear the committed state BEFORE the fallible steps below: if creation or
                // the streaming-pot mint throws, the next call must compute
                // shouldRecreate=true instead of pairing the already-updated sessionId with
                // a null/stale streaming pot at the Triple below.
                webPoTokenGenerator = null
                webPoTokenStreamingPot = null
                webPoTokenSessionId = null

                val newGenerator = PoTokenWebView.getNewPoTokenGenerator(CipherDeobfuscator.appContext)

                // The streaming poToken needs to be generated exactly once before generating
                // any other (player) tokens.
                val newStreamingPot = try {
                    newGenerator.generatePoToken(sessionId)
                } catch (t: Throwable) {
                    // Don't leak the freshly created WebView (close() hops to Main itself).
                    runCatching { newGenerator.close() }
                    throw t
                }

                webPoTokenGenerator = newGenerator
                webPoTokenStreamingPot = newStreamingPot
                webPoTokenSessionId = sessionId
                Timber.tag(TAG).d("Streaming poToken generated for sessionId=${sessionId.take(20)}...")
            }

            Triple(webPoTokenGenerator!!, webPoTokenStreamingPot!!, shouldRecreate)
        }
}
