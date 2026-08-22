/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.dialog

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.metrolist.music.R

/**
 * Confirmation for sending [songCount] songs to the single partner. There is no list to pick from —
 * exactly one person will ever receive these.
 */
@Composable
fun SendToFriendsDialog(
    songCount: Int,
    partnerName: String,
    onDismiss: () -> Unit,
    onSend: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text =
                    pluralStringResource(
                        R.plurals.send_n_songs_to_friends,
                        songCount,
                        songCount,
                        partnerName,
                    ),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        confirmButton = {
            Button(onClick = onSend) {
                Text(stringResource(R.string.send))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
