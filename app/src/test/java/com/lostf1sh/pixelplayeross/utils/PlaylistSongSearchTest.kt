package com.lostf1sh.pixelplayeross.utils

import com.lostf1sh.pixelplayeross.data.model.ArtistRef
import com.lostf1sh.pixelplayeross.data.model.Song
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaylistSongSearchTest {

    private fun song(
        id: String,
        title: String = "Song $id",
        artist: String = "Artist $id",
        album: String = "Album $id",
        albumArtist: String? = null,
        genre: String? = null,
        artists: List<ArtistRef> = emptyList(),
        path: String = "/music/$id.flac",
    ): Song = Song.emptySong().copy(
        id = id,
        title = title,
        artist = artist,
        album = album,
        albumArtist = albumArtist,
        genre = genre,
        artists = artists,
        path = path,
    )

    private fun entries(vararg songs: Song): List<IndexedValue<Song>> =
        songs.toList().withIndex().toList()

    @Test
    fun `blank queries keep every entry`() {
        val list = entries(song("1"), song("2"))

        assertEquals(list, PlaylistSongSearch.filterEntries(list, ""))
        assertEquals(list, PlaylistSongSearch.filterEntries(list, "   \t "))
    }

    @Test
    fun `title matching ignores case and accents`() {
        val song = song("1", title = "Café Melancólico")

        assertTrue(PlaylistSongSearch.matches(song, "cafe melancolico"))
        assertTrue(PlaylistSongSearch.matches(song, "CAFÉ"))
        assertFalse(PlaylistSongSearch.matches(song, "cafe zappa"))
    }

    @Test
    fun `every token must match one of the fields`() {
        val song = song("1", title = "Kind of Blue", artist = "Miles Davis")

        assertTrue(PlaylistSongSearch.matches(song, "miles kind"))
        assertFalse(PlaylistSongSearch.matches(song, "miles zappa"))
    }

    @Test
    fun `artist album album artist and genre are searchable`() {
        val song = song(
            "1",
            artist = "Radiohead",
            album = "In Rainbows",
            albumArtist = "Radiohead",
            genre = "Art Rock",
        )

        listOf("radiohead", "rainbows", "art rock").forEach { query ->
            assertTrue(PlaylistSongSearch.matches(song, query), query)
        }
    }

    @Test
    fun `any contributing artist matches`() {
        val song = song(
            "1",
            artist = "Primary",
            artists = listOf(
                ArtistRef(id = 1, name = "Primary", isPrimary = true),
                ArtistRef(id = 2, name = "Guest Star"),
            ),
        )

        assertTrue(PlaylistSongSearch.matches(song, "guest"))
        assertTrue(PlaylistSongSearch.matches(song, "primary guest"))
    }

    @Test
    fun `a query that only appears in the file path does not match`() {
        val song = song("1", title = "Plain", path = "/music/hidden-name.flac")

        assertFalse(PlaylistSongSearch.matches(song, "hidden-name"))
    }

    @Test
    fun `filtered entries keep the playlist index they came from`() {
        val list = entries(
            song("1", title = "One"),
            song("2", title = "Two"),
            song("3", title = "Three"),
        )

        val result = PlaylistSongSearch.filterEntries(list, "three")

        assertEquals(listOf(2), result.map { it.index })
        assertEquals("3", result.single().value.id)
    }

    @Test
    fun `a repeated song keeps one entry per occurrence`() {
        val twice = song("1", title = "Twice")
        val list = entries(twice, song("2", title = "Other"), twice)

        val result = PlaylistSongSearch.filterEntries(list, "twice")

        assertEquals(listOf(0, 2), result.map { it.index })
    }

    @Test
    fun `no match yields an empty result without touching the input`() {
        val list = entries(song("1"), song("2"))
        val snapshot = list.toList()

        assertTrue(PlaylistSongSearch.filterEntries(list, "zzz").isEmpty())
        assertEquals(snapshot, list)
    }
}
