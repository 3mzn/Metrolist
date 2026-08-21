/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.dialog

import android.net.Uri
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.R
import com.metrolist.music.ui.component.DefaultDialog
import com.metrolist.music.viewmodels.JsonImportViewModel
import com.metrolist.music.viewmodels.JsonImportViewModel.SyncState

/**
 * JSON playlist import flow driven by an externally-picked [fileUri].
 *
 * @param fileUri The JSON file picked by the user (null when not picking).
 * @param onUriConsumed Called after the flow has consumed the URI, so the caller can clear its state.
 */
@Composable
fun JsonImportFlow(
    fileUri: Uri?,
    onUriConsumed: () -> Unit,
    viewModel: JsonImportViewModel = hiltViewModel(),
) {
    var showNameDialog by remember { mutableStateOf(false) }

    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val failedImports by viewModel.failedImports.collectAsStateWithLifecycle()
    val isSyncing = syncState is SyncState.Syncing

    // When a new URI arrives, prompt for a playlist name
    LaunchedEffect(fileUri) {
        if (fileUri != null) {
            showNameDialog = true
        }
    }

    // Show progress dialog while syncing
    if (isSyncing) {
        DefaultDialog(
            onDismiss = { },
            title = { Text(stringResource(R.string.importing_playlist)) },
            content = {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                )
            },
        )
    }

    // Show failed imports dialog after completion
    if (syncState == SyncState.Success && failedImports.isNotEmpty()) {
        FailedImportsDialog(
            failedImports = failedImports,
            onDismiss = { viewModel.clearFailedImports() },
        )
    }

    // Show playlist name dialog
    if (showNameDialog && fileUri != null) {
        ImportJsonPlaylistDialog(
            fileUri = fileUri,
            onDismiss = {
                showNameDialog = false
                onUriConsumed()
            },
            onConfirm = { playlistName ->
                showNameDialog = false
                viewModel.startJsonImport(fileUri, playlistName)
                onUriConsumed()
            },
        )
    }
}
