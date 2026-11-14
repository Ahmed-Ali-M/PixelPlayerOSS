package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.components.subcomps.EnhancedSongListItem
import com.lostf1sh.pixelplayeross.ui.theme.RoundedSans
import com.lostf1sh.pixelplayeross.utils.PlaylistSongSearch
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Finds a song in the open playlist.
 *
 * This sheet only locates: it filters the playlist's own entries and hands the tapped entry back
 * with the index it came from, which the playlist screen turns into a scroll target. Nothing here
 * touches playback, ordering or the playlist itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistSongSearchSheet(
    entries: ImmutableList<IndexedValue<Song>>,
    onDismiss: () -> Unit,
    onSongSelected: (IndexedValue<Song>) -> Unit,
) {
    val sheetState = rememberModalSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(query, entries) { PlaylistSongSearch.filterEntries(entries, query) }

    LaunchedEffect(Unit) {
        delay(250)
        focusRequester.requestFocus()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
        ) {
            Text(
                text = stringResource(R.string.playlist_search_title),
                style = MaterialTheme.typography.displaySmall,
                fontFamily = RoundedSans,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp)
            )
            InlineSearchField(
                value = query,
                onValueChange = { query = it },
                label = stringResource(R.string.playlist_search_hint),
                clearContentDescription = stringResource(R.string.playlist_search_clear),
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .focusRequester(focusRequester)
            )
            if (results.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SearchOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    )
                    Text(
                        text = stringResource(R.string.presentation_batch_e_no_songs_match_query, query),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = stringResource(R.string.playlist_search_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 440.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(results, key = { it.index }) { entry ->
                        EnhancedSongListItem(
                            modifier = Modifier.fillMaxWidth(),
                            song = entry.value,
                            isPlaying = false,
                            showMoreOptionsButton = false,
                            onMoreOptionsClick = {},
                            onClick = {
                                // Let the sheet slide away first so the jump is visible behind it.
                                scope.launch { sheetState.hide() }.invokeOnCompletion {
                                    if (!sheetState.isVisible) onSongSelected(entry)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
