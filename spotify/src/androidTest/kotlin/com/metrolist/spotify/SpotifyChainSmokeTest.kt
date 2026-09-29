package com.metrolist.spotify

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Headless proof that the ported Spotify client works, run before any UI exists.
 *
 * WHY THIS EXISTS
 * ---------------
 * Phase 1 proved the copy compiles and is byte-faithful. It proved nothing about behaviour, and
 * Phases 2-4 all assume the Spotify chain works. This runs the whole risky path on a real
 * device with no UI, no DataStore and no login screen.
 *
 * It has already earned its place: it caught a genuine mistake that would otherwise have been
 * discovered only after the login screen, navigation and settings wiring were finished. See
 * "The trap" below.
 *
 * THE TRAP
 * --------
 * Phase 0's confirmed-track list is **YouTube video IDs**. Spotify's canvas endpoint wants a
 * **Spotify track ID**. Feeding one to the other returns a perfectly well-formed HTTP 200 with
 * `Content-Type: application/protobuf` and a 3-byte body meaning "no canvas for that id" --
 * which looks exactly like a broken protobuf mapping, and is not one.
 *
 * The real chain, copied from SimpMusic, is:
 *   "title artist" -> searchSpotifyTrack() -> match on duration -> Spotify track ID -> canvas
 *
 * Cookie comes from -e sp_dc <cookie>. Tests that need it SKIP when absent, so the suite stays
 * green without a Spotify session.
 */
class SpotifyChainSmokeTest {
    private val spdc: String? by lazy {
        runCatching {
            androidx.test.platform.app.InstrumentationRegistry.getArguments()?.getString("sp_dc")
        }.getOrNull()
            ?: System.getenv("SPOTIFY_SPDC")
            ?: System.getProperty("spotify.spdc")
    }

    /** Query used to reach a known-canvas track. From the Phase 0 list: zrW87-xUvt4 = Dat Bad. */
    private val knownCanvasQuery = "Dat Bad"

    /**
     * Account-independent: no cookie, no TOTP. If this fails, the port is broken or Spotify
     * changed the contract -- it is never a stale session.
     */
    @Test
    fun clientToken_isObtainableWithoutAnyCredentials(): Unit = runBlockingCompat {
        val clientToken = Spotify().getClientToken().getOrNull()
        assertNotNull(
            "getClientToken() failed. This endpoint needs no cookie and no TOTP, so a failure " +
                "here means the port is broken or Spotify changed the contract. It does NOT " +
                "mean the sp_dc cookie expired.",
            clientToken,
        )
        assertTrue("Client token was blank", clientToken!!.grantedToken.token.isNotBlank())
    }

    /**
     * Fetched from a third party's GitHub repo: the component we control least and expect to
     * break first. If this dies, no client-side work can make login work.
     */
    @Test
    fun totpSecret_isFetchable(): Unit = runBlockingCompat {
        val auth = com.metrolist.spotify.auth.SpotifyAuth(SpotifyClient())
        val fetched = auth.getTotpSecret().getOrNull()
        assertTrue(
            "Could not fetch the TOTP secret from the external GitHub repo. If it is down, " +
                "moved or changed shape, the login flow cannot work at all.",
            fetched == true,
        )
        assertNotNull("TOTP secret parsed as null", auth.totpSecret)
    }

    /**
     * The full authenticated token exchange. Asserts the session is *authenticated*, not
     * merely that a response came back: Spotify hands out anonymous tokens that look valid
     * and carry no Canvas entitlement, which is how a stale cookie masquerades as success.
     */
    @Test
    fun personalToken_isObtainableAndAuthenticated(): Unit = runBlockingCompat {
        val cookie = spdc
        assumeTrue("No sp_dc cookie supplied; skipping the live-session test", !cookie.isNullOrBlank())

        val token = Spotify().getPersonalTokenWithTotp(cookie!!).getOrNull()
        assertNotNull(
            "refreshToken() failed. Re-login on SimpMusic and re-copy the cookie before " +
                "concluding the port is at fault.",
            token,
        )
        assertTrue(
            "Token came back isAnonymous=true, so the sp_dc cookie is no longer " +
                "authenticated and carries no Canvas entitlement. Re-login on SimpMusic.",
            !token!!.isAnonymous,
        )
        // No fixed length is asserted. SimpMusic hardcodes 374, but a live token measured on
        // 2026-09-30 was 395, so that constant is not a contract and asserting it produced a
        // false failure during the Phase 1.5 run.
        assertTrue("Personal token was implausibly short", token.accessToken.length > 100)
    }

    /**
     * The whole chain, ending in a real Canvas URL over protobuf. This is the test that proves
     * Phase 3 is worth building, and the one that would have caught the YouTube/Spotify ID
     * confusion.
     */
    @Test
    fun canvas_isFetchableForAKnownCanvasTrack(): Unit = runBlockingCompat {
        val cookie = spdc
        assumeTrue("No sp_dc cookie supplied; skipping the live-session test", !cookie.isNullOrBlank())

        val spotify = Spotify()
        val personal = spotify.getPersonalTokenWithTotp(cookie!!).getOrNull()
        assertNotNull("Could not obtain a personal token", personal)
        val clientToken = spotify.getClientToken().getOrNull()!!.grantedToken.token

        // Step 1: YouTube "title artist" -> Spotify track. SimpMusic matches on duration as
        // well; the smoke test only needs a track that is known to have a Canvas, so it takes
        // the first hit. Phase 3 must port the duration matching too.
        val search = spotify
            .searchSpotifyTrack(knownCanvasQuery, personal!!.accessToken, clientToken)
            .getOrNull()
        assertNotNull("Spotify search failed", search)
        val spotifyTrackId = search!!.data?.searchV2?.tracksV2?.items
            ?.firstNotNullOfOrNull { it.item?.data?.id }
        assertNotNull(
            "Spotify search returned no tracks for '$knownCanvasQuery'.",
            spotifyTrackId,
        )

        // Step 2: canvas for the real Spotify track id.
        val canvas = spotify
            .getSpotifyCanvas(spotifyTrackId!!, personal.accessToken, clientToken)
            .getOrNull()
        assertNotNull("Canvas fetch failed for Spotify track $spotifyTrackId", canvas)
        assertTrue(
            "Canvas response was empty. With a valid authenticated token and a real Spotify " +
                "track id this indicates protobuf field mapping: check @ProtoNumber is " +
                "intact (spec 14.1) and Content-Type is application/protobuf.",
            canvas!!.canvases.isNotEmpty(),
        )
        assertTrue(
            "Canvas entry had no video URL",
            canvas.canvases.first().canvas_url.isNotBlank(),
        )
    }
}

private fun runBlockingCompat(block: suspend () -> Unit) = kotlinx.coroutines.runBlocking { block() }
