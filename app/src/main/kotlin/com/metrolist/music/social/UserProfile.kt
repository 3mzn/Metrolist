/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.social

/**
 * User profile stored in Firestore for social features.
 */
data class UserProfile(
    val uid: String = "",
    val email: String = "",
    val username: String = "",
    val photoUrl: String? = null,
)
