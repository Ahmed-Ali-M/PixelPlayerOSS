# Playlist search — implementation plan

Status: proposed
Scope: filter songs **inside one playlist** (`PlaylistDetailScreen`), local / Navidrome /
Jellyfin / folder playlists alike.

---

## 1. Goal

When a playlist has 2000 songs, finding one means scrolling through 2000 rows. Add a search
field on the playlist screen that narrows the visible rows as the user types, so a song can be
located in seconds.

**Search is a locator, not a mode.** It changes what is *shown*, never what *plays*: Play,
Shuffle and tapping a result all keep using the full playlist (§6). That keeps the feature tiny
and removes every playback edge case.

Non-goals:

- No change to the global `SearchScreen` (bottom-nav tab) or its history/filters.
- No new playlist-name search in the Library playlists tab (`searchPlaylists` already backs
  the add-to-playlist sheet).
- No change to playback, queue or play-count semantics.
- No fuzzy/relevance ranking, no lyrics search, no saved query, no persistence across
  navigation.

---

## 2. How the playlist screen works today

| Concern | Where |
| --- | --- |
| Screen scaffold, app bar, actions | `presentation/screens/PlaylistDetailScreen.kt:169` |
| Loaded, already-sorted songs | `uiState.currentPlaylistSongs` → `PlaylistDetailScreen.kt:214` |
| Display list (index-keyed copy) | `localReorderableEntries` / `localReorderableSongs` — `:256-261` |
| Drag-to-reorder | `rememberReorderableLazyListState` — `:270`, persisted through `PlaylistViewModel.reorderSongsInPlaylist` (`PlaylistViewModel.kt:624`) |
| App bar title/cover/subtitle | `:312`, count + duration at `:343-353` |
| App bar actions (sort, options) | `:369-392` |
| Play / Shuffle | `:432`, `:474` — both pass `localReorderableSongs` and `currentPlaylist.id` |
| Empty state | `:726` |
| Song rows | `items(...) key = { it.index }` — `:775`, row click plays at `:809` |
| Sort sheet | `:1172-1220` → `PlaylistViewModel.sortPlaylistSongs` (`:684`) |

Two properties of that layout drive the whole design:

1. The list is a **presentation-order copy**, and reordering persists by **position**
   (`reorderSongsInPlaylist(playlistId, fromIndex, toIndex)`), while removal persists by
   **song id** (`removeSongFromPlaylist(playlistId, songId)`).
2. Every song the playlist can play is already in memory, so filtering needs no I/O.

---

## 3. Decision: filter in memory, not through the repository

| Option | Verdict |
| --- | --- |
| Reuse `SearchStateHolder` + `MusicRepository.searchSongs` (FTS, `SEARCH_RESULTS_LIMIT = 100`, `MusicRepositoryImpl.kt:99`) and intersect with playlist ids | Rejected: capped at 100 hits, misses Navidrome/Jellyfin songs that are not in the local `songs` table, drops duplicate occurrences, and re-sorts by relevance instead of playlist order |
| New DAO query `WHERE playlist_id = ? AND title LIKE ?` | Rejected: three cloud/local sources, no per-source playlist join in one query, and it duplicates the source of truth already loaded in the ViewModel |
| In-memory `List<Song>.filter` over `currentPlaylistSongs` | **Chosen**: works offline and for cloud songs, preserves sort/manual order and duplicates, O(n) on an already-resident list, and stays testable as a pure function |

In-memory matching also matches what the app already does in three other sheets:
`QueueBottomSheet.kt:1521-1532`, `SongPickerBottomSheet.kt:294-321`,
`PlaylistBottomSheet.kt:74-80`. This plan only adds the multi-field/token behaviour those spots
lack.

---

## 4. UX

**Entry point** — a `Search` icon in the playlist app-bar `actions` row, left of the existing
sort icon (`PlaylistDetailScreen.kt:369`). Same `IconButton` treatment as sort, so the app bar
does not grow. `contentDescription` from a new string.

**Active state** — a pill search field slides in directly under the app bar, above the
Play/Shuffle row:

```
┌──────────────────────────────────┐
│ ←   Playlist name            🔍 ⋮ │   ← search icon toggles
│ n songs · 3 h 12 m               │
├──────────────────────────────────┤
│ 🔍  Search songs in playlist  ✕  │   ← new, focused, IME open
├──────────────────────────────────┤
│ [   Play   ] [  Shuffle  ]       │
│ … filtered rows …                │
└──────────────────────────────────┘
```

- The field reuses the app's existing search-field styling (`OutlinedTextField` with
  `surfaceContainerHigh`, transparent indicators, `CircleShape`, leading `Search`, trailing
  `Clear`) — the exact block at `SongPickerBottomSheet.kt:435-470`. Factor it into
  `presentation/components/InlineSearchField.kt` and reuse it from the playlist screen, so a
  later cleanup can point the two sheets at the same composable instead of three copies.
- Focus is requested on open (`FocusRequester` + `LaunchedEffect { delay(150); requestFocus() }`,
  as `QueueBottomSheet.kt:1553-1557` does) so the keyboard opens immediately.
- While the field is open, the Add / Remove / Reorder chip row (`:568-711`) is **hidden**. It is
  the row that would otherwise be squeezed off-screen on short/landscape windows, and reorder is
  illegal during a filtered view anyway (§6).
- `BackHandler(enabled = isSearchOpen)` closes the field instead of leaving the playlist.
  Closing always clears the query.
- The query survives configuration changes (`rememberSaveable`) and resets when `playlistId`
  changes, so no stale filter follows you into another playlist.

**While filtering**

- Subtitle becomes `"%1$d of %2$d songs · %3$s"` (matched count, total, matched duration), so the
  header still says how much of the playlist is on screen. Use the same `formatSongCount` /
  `formatTotalDuration` helpers, fed the filtered list.
- Empty result: keep the list area, show `SearchOff` icon + `No songs match "<query>"` +
  `Clear search` text button. This is deliberately distinct from the playlist-empty state at
  `:726` (which offers Add songs) — an empty playlist and an empty filter are different stories.
- Changing the query scrolls to the top of the filtered list (same `LaunchedEffect` + `delay(100)
  + scrollToItem(0)` guard the sort sheet uses at `:1206`).

---

## 5. Matching rules

New pure object: `utils/PlaylistSongSearch.kt`.

```kotlin
package com.lostf1sh.pixelplayeross.utils

import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.playlist.nlp.NlpText

/**
 * Client-side matcher for the playlist search field.
 *
 * The playlist screen already holds every song it can play, so the query is matched in memory
 * and the playlist's own order is kept: no ranking, no result cap, no DB round-trip.
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

    fun filter(songs: List<Song>, query: String): List<Song> =
        if (query.isBlank()) songs else songs.filter { matches(it, query) }

    /** Metadata a user actually indexes by. The file path is deliberately left out: it adds
     *  noise (remote ids for cloud songs) without helping anyone find a track by name. */
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
```

Rules, explicitly:

- Blank / whitespace-only query returns the untouched list (no filtering cost on entry).
- Case-insensitive and diacritic-insensitive: `"melancolico"` matches `"Melancólico"`, `"cafe"`
  matches `"Café"`.
- All tokens must match (AND), each token against any field (OR across fields).
- Substring match, not word-prefix: `"art"` matches `"Artist"`. Consistent with the other
  in-app filters; no fuzzy matching, no typo tolerance.
- Playlist order is preserved — no relevance ranking — and duplicate occurrences of the same
  song stay separate rows.
- Metadata only: title, artist, `displayArtist`, every `artists[]` entry, album, album artist,
  genre. File name / path is **not** matched.
- `NlpText` is reused rather than copied (`data/playlist/nlp/NlpText.kt:30`), but **not** its
  stemmer: stemming is right for mood queries and wrong for title search.

---

## 6. Interaction matrix

| Toggle | Behaviour while a query is active | Why |
| --- | --- | --- |
| Search icon | Closes field, clears query, restores full list | One-tap escape |
| Back gesture | Same as above (`BackHandler`) | Expected on a focused field |
| Reorder mode | Disabled and force-off when search opens; drag handles hidden while filtering | `ReorderableItem(key = entry.index)` and `reorderSongsInPlaylist(from, to)` address **display positions**; a filtered list would persist the wrong permutation |
| Remove mode | Allowed; removal still uses song ids | `removeSongFromPlaylist(playlistId, song.id)` (`:875`) is id-based, so it stays correct |
| Play | **Unchanged** — always plays the whole playlist, filtered or not | Search is a locator; play state must not depend on what is on screen |
| Shuffle | **Unchanged** — shuffles the whole playlist | Same reasoning |
| Tapping a result row | **Unchanged** — plays the whole playlist starting at that song, so playback continues past the matches | The tapped song is guaranteed to be a member of the full list, so the existing call needs no change |
| Sort | Allowed; sorts the underlying playlist, list re-derives, scrolls to top | Filter and sort are orthogonal |
| Add songs / playlist options | Unaffected (chip row hidden, app-bar overflow still available) | No coupling |
| Song info sheet → remove from list | Removes by id; if it was the only match the empty-filter state appears | Consistent |
| Cloud download of playlist | `CloudOfflineRepository.downloadCandidates(songsInPlaylist)` keeps using the **full** list | Downloading should not silently narrow to matches |
| Leaving the screen | Query dropped | No hidden filter on return |

Playback consequence: none. The queue snapshot, play counts, transitions and the last-playlist
tile all keep seeing the full playlist, because every playback call keeps receiving
`localReorderableSongs` (`:432`, `:474`, `:809`). Those three call sites must **not** be touched
by this feature — that is the check that the locator semantics stayed intact in review.

Also: Play/Shuffle stay enabled while a query is active, even with zero matches. Disabling them
would imply playback follows the filter, which it does not.

---

## 7. State and data flow

Keep the query **screen-local**; do not add it to `PlaylistUiState`:

- `PlaylistUiState` is read by the library screen and the add-to-playlist sheet; a query there
  would leak into other screens.
- Screen-local `rememberSaveable` survives config changes, and its default on `playlistId`
  change gives the reset-on-navigate behaviour for free.

```kotlin
// PlaylistDetailScreen.kt
var isSearchOpen by rememberSaveable(playlistId) { mutableStateOf(false) }
var searchQuery by rememberSaveable(playlistId) { mutableStateOf("") }

val filteredSongs = remember(searchQuery, localReorderableSongs) {
    if (searchQuery.isBlank()) localReorderableSongs
    else PlaylistSongSearch.filter(localReorderableSongs, searchQuery)
}

val displayedEntries = remember(filteredSongs) {
    filteredSongs.mapIndexed { index, song -> index to song }
}
```

Then:

- `items(displayedEntries, key = { it.first })` replaces `items(localReorderableEntries, key = {
  it.index })`. Index keys stay unique with duplicate songs, exactly as today.
- `LaunchedEffect(isSearchOpen) { if (isSearchOpen) { isReorderModeEnabled = false; isRemoveModeEnabled = false } }`.
- `remember(searchQuery, localReorderableSongs)` is the only place `filter` runs, and no
  `.toImmutableList()` is added on a per-frame path (CONTRIBUTING: `toImmutableList()` is O(n)).
  Nothing new enters `@Composable` parameters except the local `List<Song>`, which the screen
  already passes around.
- Reorder commit path (`:281-290`) is left untouched, but guarded by `!isSearchOpen`.

---

## 8. Implementation steps

1. **`utils/PlaylistSongSearch.kt`** (new, ~50 lines) — matcher above, with KDoc.
2. **`presentation/components/InlineSearchField.kt`** (new, ~40 lines) — the pill
   `OutlinedTextField` extracted from `SongPickerSearchField` (`SongPickerBottomSheet.kt:435`),
   parameterized by value / onValueChange / hint / modifier. Reused by the playlist screen now;
   the two existing call sites can adopt it later without behaviour change.
3. **`PlaylistDetailScreen.kt`** (~70 changed lines, all additive):
   - search `IconButton` in `actions` before sort (`:369`);
   - `BackHandler` + focus/IME handling + `InlineSearchField` row between `Column {` (`:383`) and
     the Play/Shuffle row;
   - hide the chip row while `isSearchOpen`, force off remove/reorder modes;
   - `filteredSongs` / `displayedEntries`; swap the `items(...)` source and its `key` (`:775`);
   - **no change** to the play/shuffle/row-tap call sites (`:432`, `:474`, `:809`) — they keep
     receiving `localReorderableSongs`;
   - subtitle count uses filtered/total (`:343-353`);
   - filtered-empty branch inside the existing `localReorderableSongs.isEmpty()` area (`:726`),
     checked **after** it so a genuinely empty playlist keeps its Add-songs copy.
4. **Strings** — new keys in `res/values/strings_presentation_batch_b.xml` (where the rest of
   this screen's copy lives), plus the same keys in all 13 translated `values-*/` folders: the
   repo currently holds ~2066 strings per locale, so parity is expected. Keys:
   `playlist_search_open`, `playlist_search_hint`, `playlist_search_clear`,
   `playlist_search_empty_title`, `playlist_search_empty_body`,
   `playlist_search_match_count` (`%1$d of %2$d songs`, as a `<plurals>` on the total count).
5. **Tests** (below).
6. **Docs** — no README change; the CHANGELOG is release-generated, so leave it alone.

---

## 9. Accessibility

- Search icon: `contentDescription = playlist_search_open`; 48dp touch target via the existing
  `IconButton` sizing.
- Field: `label` text (not placeholder-only) so TalkBack announces it after the value; IME
  action `Search` on the keyboard.
- Clear button: `contentDescription = playlist_search_clear`.
- Result count in the subtitle gets
  `semantics { liveRegion = LiveRegionMode.Polite }` so TalkBack reads "3 of 214 songs" as the
  query changes instead of the user having to swipe back to the header.
- The filtered-empty state is text, not just an icon, and the clear action is a real button.
- Focus is explicitly requested on open and cleared on close (no orphaned focus indicator).

---

## 10. Tests

Unit — new `app/src/test/java/.../utils/PlaylistSongSearchTest.kt`, JUnit Jupiter
(`org.junit.jupiter.api.Test`, per CONTRIBUTING). The matcher is the only new logic, so this is
where the coverage is; the screen change is wiring and gets the Compose test below:

- blank and whitespace-only queries return the input list unchanged (same instance or equal)
- case-insensitive title match; accented query matches unaccented song and vice versa
- multi-token AND: `"miles kind"` matches "Kind of Blue" / "Miles Davis"; `"miles zappa"` does not
- artist, album, album artist and genre match each on their own
- multi-artist songs match on any `artists[].name` and on `displayArtist`
- a query that only appears in the file path does **not** match
- duplicate songs keep every occurrence and original order
- no match returns an empty list; a fresh `Song` list is not mutated

Compose — `app/src/androidTest/.../presentation/screens/PlaylistSearchFieldTest.kt`, using the
`createComposeRule()` + Truth pattern from `CreatePlaylistDialogTest.kt`:

- typing filters a stub list; clearing restores all rows
- empty-result state shows the query and the clear button returns the full list

Manual QA matrix (worth listing in the PR): local playlist, folder playlist, smart playlist,
Navidrome playlist, Jellyfin playlist, playlist with duplicate songs, 1000+ song playlist,
RTL (Arabic), landscape, large font scale, with keyboard open.

---

## 11. Performance

- Filtering is O(n·tokens·fields) over an in-memory list: ~214 songs is nothing; a 5000-song
  playlist is still sub-millisecond to a few ms on a low-end device and runs inside `remember`,
  not on every frame.
- No debounce is needed (the 300 ms debounce in `SearchStateHolder` exists for SQLite FTS).
  Typing recomposes the list container; rows come from `QueuePlaylistSongItem`, which is already
  keyed and skippable.
- Do **not** add the filtered list to `PlaylistUiState`, and do **not** call `toImmutableList()`
  inside the composable body on every keystroke.
- If profiling later shows keystroke jank on huge playlists, move the filter into a
  `snapshotFlow(query).debounce(120).mapLatest { ... }` feeding a `StateFlow` in a small
  screen-scoped holder — the matcher itself does not change.

---

## 12. Risks and edge cases

| Risk | Mitigation |
| --- | --- |
| Reordering while filtered corrupts playlist order | Reorder disabled during search (§6); the commit path also checks the flag |
| Index-based LazyColumn keys collide after filtering | Keys remain indices of the **displayed** list, which are unique and stable per query |
| Keyboard covers the mini player / last rows under edge-to-edge (`MainActivity.kt:225`) | Field is top-anchored; add `Modifier.imePadding()` to the list's bottom `contentPadding` when the field is focused |
| A user expects Play to respect the filter | Play/Shuffle are above the field and never change state while filtering; the filtered-empty state still offers Play, which reads as "these buttons are the playlist's, not the search's" |
| Smart playlists refresh and drop a matched song | `refreshSmartPlaylistIfNeeded` (`PlaylistViewModel.kt:338`) re-derives the list; the query re-filters, so the view self-corrects |
| Translated strings drifting from parity | Add all keys to all 14 `values*/` files in the same commit; flag non-English wording for native review in the PR |

---

## 13. Delivery

- Branch: `feature/playlist-search`
- Commits, one logical step each:
  1. `Add a pure playlist song matcher` (matcher + unit tests)
  2. `Extract the pill search field into a shared composable`
  3. `Filter songs in the playlist screen with a search field` (screen wiring + strings + compose test)
- PR body: what changed, the interaction matrix above, and the output of
  `./gradlew :app:compileDebugKotlin :app:lintDebug :app:testDebugUnitTest`. No `Co-Authored-By`
  trailer (CONTRIBUTING).

## 14. Decisions

Resolved with the maintainer before implementation:

1. **Query resets on navigation.** Leaving the playlist or switching to another one drops the
   query; `rememberSaveable(playlistId)` gives that for free. No `SavedStateHandle` work.
2. **Play/Shuffle always use the full playlist.** Search only filters the visible rows. Row taps
   also play the full playlist from the tapped song. The existing playback call sites stay
   untouched.
3. **File-name matching dropped.** Metadata only (title/artist/album/album artist/genre); a
   path match is noise for cloud songs and nobody indexes a playlist by file name.

Remaining design detail left to implementation: whether the field sits under the app bar
(current proposal) or replaces the Add/Remove/Reorder chip row. Both are one-line layout
choices; the chip row is hidden while searching either way.
