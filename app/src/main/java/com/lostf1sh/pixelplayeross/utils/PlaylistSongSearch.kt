package com.lostf1sh.pixelplayeross.utils

import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.playlist.nlp.NlpText

/**
 * Matcher for the playlist search sheet.
 *
 * The playlist screen already holds every song it can play, so the query is matched in memory and
 * the playlist's own order is kept: no ranking, no result cap, no DB round-trip.
 *
 * A query is split into whitespace-separated tokens and every token must match the song, so
 * "miles kind" finds "Kind of Blue" by "Miles Davis". Matching is case- and accent-insensitive
 * through [NlpText.normalize], which the offline NLP engine already uses for the same reason.
 */
object PlaylistSongSearch {

    fun matches(song: Song, query: String): Boolean {
        val tokens = NlpText.normalize(query).split(' ', '\t', '\n').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return true
        val fields = song.searchableFields()
        return tokens.all { token -> fields.any { it.contains(token) } }
    }

    /**
     * Filters index-carrying entries, so every match keeps the row it came from. The sheet hands
     * that index back to the screen as the scroll target, which is why the entry type is kept
     * instead of mapping to bare songs.
     */
    fun filterEntries(entries: List<IndexedValue<Song>>, query: String): List<IndexedValue<Song>> =
        if (query.isBlank()) entries else entries.filter { matches(it.value, query) }

    /**
     * Metadata a user indexes by. The file path is left out on purpose: it adds noise (remote ids
     * for cloud songs) without helping anyone find a track by name.
     */
    private fun Song.searchableFields(): List<String> = buildList {
        add(NlpText.normalize(title))
        add(NlpText.normalize(artist))
        add(NlpText.normalize(displayArtist))
        artists.forEach { add(NlpText.normalize(it.name)) }
        add(NlpText.normalize(album))
        add(NlpText.normalize(albumArtist.orEmpty()))
        add(NlpText.normalize(genre.orEmpty()))
    }
}
