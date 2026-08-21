/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.dialog

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.ui.component.TextFieldDialog

/**
 * Prompts the user for a playlist name when importing a JSON playlist.
 */
@Composable
fun ImportJsonPlaylistDialog(
    fileUri: Uri,
    onDismiss: () -> Unit,
    onConfirm: (playlistName: String) -> Unit,
) {
    TextFieldDialog(
        icon = { Icon(painter = painterResource(R.drawable.upload), contentDescription = null) },
        title = { Text(text = stringResource(R.string.import_from_json)) },
        initialTextFieldValue = TextFieldValue(""),
        onDismiss = onDismiss,
        onDone = { playlistName ->
            if (playlistName.isNotBlank()) {
                onConfirm(playlistName)
            }
        },
        extraContent = {
            Column(
                modifier = Modifier.padding(vertical = 16.dp, horizontal = 40.dp),
            ) {
                Text(
                    text = stringResource(R.string.enter_playlist_name),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.import_playlist_name_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        },
    )
}
