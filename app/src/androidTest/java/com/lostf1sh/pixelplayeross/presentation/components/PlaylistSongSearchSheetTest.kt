package com.lostf1sh.pixelplayeross.presentation.components

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Song
import kotlinx.collections.immutable.toImmutableList
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The playlist search sheet only locates: it narrows the playlist's own entries and reports the
 * entry that was tapped, index included, so the screen can scroll to it.
 */
@RunWith(AndroidJUnit4::class)
class PlaylistSongSearchSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private var selected: IndexedValue<Song>? = null

    private fun song(id: String, title: String): Song =
        Song.emptySong().copy(id = id, title = title, artist = "Artist $id")

    private fun showSheet(vararg songs: Song) {
        val entries = songs.toList().withIndex().toList().toImmutableList()
        composeRule.setContent {
            PlaylistSongSearchSheet(
                entries = entries,
                onDismiss = {},
                onSongSelected = { selected = it },
            )
        }
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodes(hasSetTextAction() and isFocused())
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun typeQuery(query: String) {
        composeRule.onAllNodes(hasSetTextAction())[0].performTextInput(query)
    }

    private fun rowCount(): Int =
        composeRule.onAllNodesWithText("Artist ", substring = true).fetchSemanticsNodes().size

    @Test
    fun typingNarrowsTheList() {
        showSheet(song("1", "Alpha"), song("2", "Beta"), song("3", "Gamma"))

        typeQuery("Beta")

        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithText("Artist 2").fetchSemanticsNodes().isNotEmpty()
        }
        assertThat(composeRule.onAllNodesWithText("Artist 1").fetchSemanticsNodes()).isEmpty()
        assertThat(composeRule.onAllNodesWithText("Artist 3").fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun clearingTheQueryRestoresEveryEntry() {
        showSheet(song("1", "Alpha"), song("2", "Beta"))

        typeQuery("Beta")
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithText("Artist 1").fetchSemanticsNodes().isEmpty()
        }

        composeRule.onNodeWithContentDescription(context.getString(R.string.playlist_search_clear))
            .performClick()

        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithText("Artist 1").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun tappingAResultReportsItsPlaylistEntry() {
        showSheet(song("1", "Alpha"), song("2", "Beta"), song("3", "Gamma"))

        typeQuery("Gamma")
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithText("Artist 3").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Artist 3")[0].performTouchInput { click() }

        composeRule.waitUntil(TIMEOUT_MS) { selected != null }
        assertThat(selected?.index).isEqualTo(2)
        assertThat(selected?.value?.id).isEqualTo("3")
    }

    @Test
    fun anEmptyResultEchoesTheQuery() {
        showSheet(song("1", "Alpha"))

        typeQuery("nothing-here")

        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithText("nothing-here", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        val message = context.getString(R.string.presentation_batch_e_no_songs_match_query, "nothing-here")
        composeRule.onNodeWithText(message).assertIsDisplayed()
        assertThat(rowCount()).isEqualTo(0)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
