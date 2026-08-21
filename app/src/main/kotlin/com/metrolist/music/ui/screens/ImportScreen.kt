/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.AppBarHeight
import com.metrolist.music.ui.dialog.FailedImportsDialog
import com.metrolist.music.ui.dialog.JsonImportFlow
import com.metrolist.music.viewmodels.JsonImportViewModel
import com.metrolist.music.viewmodels.JsonImportViewModel.SyncState

/**
 * Dedicated tab for bringing playlists in from a file.
 *
 * The import itself lives in [JsonImportFlow], which the Library playlists screen also drives, so
 * there is only ever one import implementation. This screen adds what a buried action could not: a
 * stable place to find the feature, the expected file format, and the outcome of the last run.
 */
@Composable
fun ImportScreen(
    navController: NavController,
    viewModel: JsonImportViewModel = hiltViewModel(),
) {
    val windowInsets = LocalPlayerAwareWindowInsets.current

    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val statusText by viewModel.statusText.collectAsStateWithLifecycle()
    val failedImports by viewModel.failedImports.collectAsStateWithLifecycle()

    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var showFailuresAgain by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pickedUri = uri
    }

    // Progress is rendered inline below, so the flow's own progress dialog would only cover it.
    JsonImportFlow(
        fileUri = pickedUri,
        onUriConsumed = { pickedUri = null },
        showProgressDialog = false,
        viewModel = viewModel,
    )

    // The flow clears its failure list once dismissed; this lets the user reopen the last report
    // from the summary card without re-running the import.
    if (showFailuresAgain && failedImports.isNotEmpty()) {
        FailedImportsDialog(
            failedImports = failedImports,
            onDismiss = { showFailuresAgain = false },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = windowInsets.asPaddingValues().calculateTopPadding() + 16.dp,
            bottom = windowInsets.asPaddingValues().calculateBottomPadding() + 16.dp + AppBarHeight,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("picker") {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        text = stringResource(R.string.import_json_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.import_json_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { filePicker.launch(arrayOf(MIME_TYPE_JSON)) },
                        enabled = syncState !is SyncState.Syncing,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.import_playlist_outlined),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.import_choose_file))
                    }
                }
            }
        }

        item("status") {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        text = stringResource(R.string.import_last_run),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))

                    when (val state = syncState) {
                        is SyncState.Idle -> Text(
                            text = stringResource(R.string.import_status_idle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        is SyncState.Syncing -> {
                            Text(
                                text = statusText.ifBlank { stringResource(R.string.import_in_progress) },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator(
                                progress = { progress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(onClick = { viewModel.cancelJsonImport() }) {
                                    Text(stringResource(android.R.string.cancel))
                                }
                            }
                        }

                        is SyncState.Success -> {
                            Text(
                                text = statusText.ifBlank { stringResource(R.string.import_status_idle) },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (failedImports.isNotEmpty()) {
                                Spacer(Modifier.height(12.dp))
                                OutlinedButton(onClick = { showFailuresAgain = true }) {
                                    Text(
                                        stringResource(
                                            R.string.import_view_failures,
                                            failedImports.size,
                                        ),
                                    )
                                }
                            }
                        }

                        is SyncState.Cancelled -> Text(
                            text = statusText.ifBlank { stringResource(R.string.import_status_idle) },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        is SyncState.Error -> Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        item("format") {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.import_expected_format),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.import_expected_format_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = EXAMPLE_JSON,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private const val MIME_TYPE_JSON = "application/json"

private val EXAMPLE_JSON = """
    [
      { "title": "Song name", "artist": "Artist name" },
      { "title": "Another song", "artist": "Someone else" }
    ]
""".trimIndent()
