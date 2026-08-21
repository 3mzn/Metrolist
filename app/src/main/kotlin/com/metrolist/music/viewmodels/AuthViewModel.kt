/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.metrolist.music.social.SongListenedNotificationManager
import com.metrolist.music.social.UserProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Owns the Firebase session behind the social features: sign in / register / sign out, and the
 * Firestore `users/{uid}` document that other people discover you by.
 *
 * The notification worker that polls for "your friend listened to the song you sent" is started and
 * stopped alongside the session, so it never runs without a signed-in user.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val songListenedNotificationManager: SongListenedNotificationManager,
) : ViewModel() {

    private val _user = MutableStateFlow(auth.currentUser)
    val user: StateFlow<FirebaseUser?> = _user.asStateFlow()

    private val _profile = MutableStateFlow<UserProfile?>(null)
    val profile: StateFlow<UserProfile?> = _profile.asStateFlow()

    private val authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        val currentUser = firebaseAuth.currentUser
        _user.value = currentUser

        if (currentUser != null) {
            viewModelScope.launch {
                ensureUserProfileExists(currentUser)
                songListenedNotificationManager.startWorker()
            }
        } else {
            _profile.value = null
            songListenedNotificationManager.stopWorker()
        }
    }

    init {
        auth.addAuthStateListener(authStateListener)

        // Load the profile for an already signed-in user on startup.
        auth.currentUser?.let { currentUser ->
            viewModelScope.launch {
                ensureUserProfileExists(currentUser)
            }
        }
    }

    override fun onCleared() {
        auth.removeAuthStateListener(authStateListener)
        super.onCleared()
    }

    fun login(email: String, pass: String, onResult: (Boolean, Exception?) -> Unit) {
        if (email.isBlank() || pass.isBlank()) {
            onResult(false, IllegalArgumentException("Email and password cannot be empty."))
            return
        }
        auth.signInWithEmailAndPassword(email.trim(), pass)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    auth.currentUser?.let { currentUser ->
                        viewModelScope.launch {
                            ensureUserProfileExists(currentUser)
                        }
                    }
                }
                onResult(task.isSuccessful, task.exception)
            }
    }

    fun register(email: String, pass: String, onResult: (Boolean, Exception?) -> Unit) {
        if (email.isBlank() || pass.isBlank()) {
            onResult(false, IllegalArgumentException("Email and password cannot be empty."))
            return
        }
        auth.createUserWithEmailAndPassword(email.trim(), pass)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    auth.currentUser?.let { currentUser ->
                        viewModelScope.launch {
                            createInitialProfile(currentUser)
                        }
                    }
                }
                onResult(task.isSuccessful, task.exception)
            }
    }

    fun logout() {
        auth.signOut()
    }

    /**
     * Claims [newUsername] for the current user. Usernames are how friends find each other, so this
     * rejects a name already held by a different account.
     */
    fun updateUsername(newUsername: String, onResult: (Boolean, String?) -> Unit) {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            onResult(false, "Not signed in")
            return
        }
        val trimmed = newUsername.trim()
        if (trimmed.isEmpty()) {
            onResult(false, "Username cannot be empty")
            return
        }

        firestore.collection(USERS_COLLECTION)
            .whereEqualTo("username", trimmed)
            .get()
            .addOnSuccessListener { querySnapshot ->
                val takenByAnotherUser = querySnapshot.documents.any { it.id != currentUser.uid }
                if (takenByAnotherUser) {
                    onResult(false, "Username is already taken")
                    return@addOnSuccessListener
                }

                firestore.collection(USERS_COLLECTION)
                    .document(currentUser.uid)
                    .set(
                        mapOf(
                            "username" to trimmed,
                            "updatedAt" to Timestamp.now(),
                        ),
                        SetOptions.merge(),
                    )
                    .addOnSuccessListener {
                        _profile.value = _profile.value?.copy(username = trimmed)
                            ?: UserProfile(
                                uid = currentUser.uid,
                                email = currentUser.email.orEmpty(),
                                username = trimmed,
                            )
                        onResult(true, null)
                    }
                    .addOnFailureListener { e ->
                        onResult(false, e.message ?: "Failed to update username")
                    }
            }
            .addOnFailureListener { e ->
                onResult(false, e.message ?: "Failed to check username")
            }
    }

    private fun ensureUserProfileExists(user: FirebaseUser) {
        firestore.collection(USERS_COLLECTION)
            .document(user.uid)
            .get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    _profile.value = UserProfile(
                        uid = snapshot.id,
                        email = snapshot.getString("email") ?: user.email.orEmpty(),
                        username = snapshot.getString("username").orEmpty(),
                        photoUrl = snapshot.getString("photoUrl"),
                    )
                } else {
                    createInitialProfile(user)
                }
            }
        // On failure keep whatever profile we already have; the next auth state change retries.
    }

    /**
     * Writes the discoverable `users/{uid}` document with an empty username — the UI then routes to
     * profile setup, which fills it in.
     */
    private fun createInitialProfile(user: FirebaseUser) {
        val profile = UserProfile(
            uid = user.uid,
            email = user.email.orEmpty(),
            username = "",
        )

        firestore.collection(USERS_COLLECTION)
            .document(user.uid)
            .set(
                mapOf(
                    "email" to profile.email,
                    "username" to profile.username,
                    "photoUrl" to profile.photoUrl,
                    "createdAt" to Timestamp.now(),
                    "updatedAt" to Timestamp.now(),
                ),
            )
            .addOnSuccessListener {
                _profile.value = profile
            }
        // On failure the user can retry from profile setup.
    }

    companion object {
        private const val USERS_COLLECTION = "users"
    }
}
