/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.social.FriendRequest
import com.metrolist.music.social.RelationshipState
import com.metrolist.music.social.SocialRepository
import com.metrolist.music.social.UserProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SocialViewModel @Inject constructor(
    private val socialRepository: SocialRepository,
) : ViewModel() {

    private val _users = MutableStateFlow<List<UserProfile>>(emptyList())
    val users: StateFlow<List<UserProfile>> = _users.asStateFlow()

    private val _relationships =
        MutableStateFlow(RelationshipState(emptyMap(), emptyMap(), emptySet()))
    val relationships: StateFlow<RelationshipState> = _relationships.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        viewModelScope.launch {
            socialRepository.getAllUsers().collectLatest { profiles ->
                _users.value = profiles
            }
        }
        viewModelScope.launch {
            socialRepository.observeRelationships().collectLatest { state ->
                _relationships.value = state
            }
        }
    }

    fun sendFriendRequest(toUid: String) {
        socialRepository.sendFriendRequest(toUid) { success, message ->
            _error.value = if (!success) message ?: "Failed to send request" else null
        }
    }

    fun acceptFriendRequest(request: FriendRequest) {
        socialRepository.acceptFriendRequest(request) { success, message ->
            _error.value = if (!success) message ?: "Failed to accept request" else null
        }
    }

    fun rejectFriendRequest(requestId: String) {
        socialRepository.rejectFriendRequest(requestId) { success, message ->
            _error.value = if (!success) message ?: "Failed to reject request" else null
        }
    }

    fun removeFriend(otherUid: String) {
        socialRepository.removeFriend(otherUid) { success, message ->
            _error.value = if (!success) message ?: "Failed to remove friend" else null
        }
    }
}
