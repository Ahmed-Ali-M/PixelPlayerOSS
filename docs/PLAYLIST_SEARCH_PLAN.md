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
- The Add / Remove / Reorder chip row (`:568-711`) stays visible and usable while searching.
  Filtering is orthogonal to reordering and removing (§6), so nothing has to be taken away. On
  windows shorter than ~480dp the chip row may collapse while the IME is open, purely for
  vertical space.
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
| Reorder mode | **Works while filtering.** A drop reports the new order of the displayed songs; the stored slots held by those songs take that order and every hidden song keeps its position | Reordering a subset is already the documented contract of `mergeVisiblePlaylistOrder` (`PlaylistOrderMerge.kt`), tested at `PlaylistPreferencesRepositoryTest.kt:58`; the index-based call was the only thing that could not express it |
| Remove mode | Allowed; removal still uses song ids | `removeSongFromPlaylist(playlistId, song.id)` (`:875`) is id-based, so it stays correct |
| Play | **Unchanged** — always plays the whole playlist, filtered or not | Search is a locator; play state must not depend on what is on screen |
| Shuffle | **Unchanged** — shuffles the whole playlist | Same reasoning |
| Tapping a result row | **Unchanged** — plays the whole playlist starting at that song, so playback continues past the matches | The tapped song is guaranteed to be a member of the full list, so the existing call needs no change |
| Sort | Allowed; sorts the underlying playlist, list re-derives, scrolls to top | Filter and sort are orthogonal |
| Add songs / playlist options | Unaffected; the chip row stays visible, the app-bar overflow still works | No coupling |
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

// localReorderableEntries is already List<IndexedValue<Song>>: the carried index is the row's
// position in the full playlist, so it is a stable identity through filtering and dragging.
val visibleEntries = remember(searchQuery, localReorderableEntries) {
    if (searchQuery.isBlank()) localReorderableEntries
    else localReorderableEntries.filter { PlaylistSongSearch.matches(it.value, searchQuery) }
}
var displayedEntries by remember(visibleEntries) { mutableStateOf(visibleEntries) }
var hasPendingReorder by remember { mutableStateOf(false) }

// onMove mutates displayedEntries and sets hasPendingReorder; the drop reports the new order:
//   playlistViewModel.reorderPlaylistSongs(playlistId, displayedEntries.map { it.value.id })
```

The `hasPendingReorder` flag replaces `lastMovedFrom` / `lastMovedTo` and matters: the ViewModel
writes `MANUAL_ORDER_MODE` after a successful reorder, so a drag that ends where it started must
not call it — otherwise touching a row would silently unsort a sorted playlist.

Then:

- `items(displayedEntries, key = { it.index })` keeps today's key expression; the index is the
  row's full-playlist position, so it stays unique with duplicate songs and stable during a drag.
- No mode is force-off. Nothing in `LaunchedEffect(isSearchOpen)` is needed.
- `remember(searchQuery, localReorderableEntries)` is the only place `filter` runs, and no
  `.toImmutableList()` is added on a per-frame path (CONTRIBUTING: `toImmutableList()` is O(n)).
  Nothing new enters `@Composable` parameters except the local `List<Song>`, which the screen
  already passes around.
- Play / Shuffle / row taps keep reading `localReorderableSongs` (§6), unaffected by filtering.

### 7.1 Why reorder must not be disabled

`PlaylistPreferencesRepository.reorderSongsInPlaylist(playlistId, newSongOrderIds)` does **not**
expect a full permutation. `mergeVisiblePlaylistOrder` (`data/playlist/PlaylistOrderMerge.kt`)
walks the stored ids, fills the slots held by ids that appear in the caller's list, and leaves
every other id where it is; its KDoc describes the detail screen's resolved-only list as the
intended caller. A search result is the same shape — a visible subset — so the repository can
already persist it. There is a test for exactly this (`PlaylistPreferencesRepositoryTest.kt:58`):
stored `[a, hidden, b]`, visible `[b, a]` → `[b, hidden, a]`.

What cannot express it is the ViewModel's `(fromIndex, toIndex)` contract, which applies the move
to the *full* list and therefore only makes sense for an unfiltered view. So the change is to the
call contract, not to the feature's availability:

```kotlin
// PlaylistViewModel.kt — replaces reorderSongsInPlaylist(playlistId, fromIndex, toIndex)
fun reorderPlaylistSongs(playlistId: String, newVisibleOrder: List<String>) {
    if (isFolderPlaylistId(playlistId)) return
    val currentPlaylist = _uiState.value.currentPlaylistDetails ?: return
    if (currentPlaylist.id != playlistId || currentPlaylist.isSmartPlaylist) return
    viewModelScope.launch {
        val currentSongs = _uiState.value.currentPlaylistSongs
        val fullOrder = playlistPreferencesRepository
            .reorderSongsInPlaylist(playlistId, newVisibleOrder) ?: return@launch reload(playlistId)
        // fullOrder is a permutation of the stored ids; currentPlaylistSongs holds the resolved
        // subset, so rebuild it by occurrence (a playlist may hold the same id twice).
        val pool = currentSongs.groupBy { it.id }.mapValues { it.value.toMutableList() }
        val reordered = fullOrder.mapNotNull { id -> pool[id]?.removeFirstOrNull() }
        // …then the existing optimistic state update + MANUAL_ORDER_MODE write, with `reordered`
    }
}
```

Example: stored `[A,B,C,D,E]`, query matches `B` and `D`, drag `D` above `B` → displayed
`[D,B]` → stored `[A,D,C,B,E]`. `C` never moves, and the filtered view shows exactly the drop.
Unfiltered reorder is the case where the visible order is the whole list, so both paths collapse
into one and the index arithmetic (`removeAt`/`add`, `lastMovedFrom`/`lastMovedTo`) is deleted.

---

## 8. Implementation steps

1. **`utils/PlaylistSongSearch.kt`** (new, ~50 lines) — matcher above, with KDoc.
2. **`presentation/components/InlineSearchField.kt`** (new, ~40 lines) — the pill
   `OutlinedTextField` extracted from `SongPickerSearchField` (`SongPickerBottomSheet.kt:435`),
   parameterized by value / onValueChange / hint / modifier. Reused by the playlist screen now;
   the two existing call sites can adopt it later without behaviour change.
3. **`PlaylistViewModel.reorderSongsInPlaylist` → `reorderPlaylistSongs(playlistId,
   newVisibleOrder)`** (§7.1). One caller (`PlaylistDetailScreen.kt:286`), no other consumers, and
   it replaces index arithmetic with the repository's existing subset merge. Net deletion.
4. **`PlaylistDetailScreen.kt`** (~70 changed lines, mostly additive):
   - search `IconButton` in `actions` before sort (`:369`);
   - `BackHandler` + focus/IME handling + `InlineSearchField` row between `Column {` (`:383`) and
     the Play/Shuffle row;
   - `visibleEntries` / `displayedEntries`; swap the `items(...)` source (`:775`) — the key
     expression is unchanged;
   - drop handler reports `displayedEntries.map { it.value.id }` instead of from/to indices, and
     `lastMovedFrom` / `lastMovedTo` are replaced by a single `hasPendingReorder` flag;
   - the unfiltered drag path uses the same state, so drag handling stops mutating
     `localReorderableEntries` and both modes share one code path;
   - **no change** to the play/shuffle/row-tap call sites (`:432`, `:474`, `:809`) — they keep
     receiving `localReorderableSongs`;
   - subtitle count uses filtered/total (`:343-353`);
   - filtered-empty branch inside the existing `localReorderableSongs.isEmpty()` area (`:726`),
     checked **after** it so a genuinely empty playlist keeps its Add-songs copy.
5. **Strings** — new keys in `res/values/strings_presentation_batch_b.xml` (where the rest of
   this screen's copy lives), plus the same keys in all 13 translated `values-*/` folders: the
   repo currently holds ~2066 strings per locale, so parity is expected. Keys:
   `playlist_search_open`, `playlist_search_hint`, `playlist_search_clear`,
   `playlist_search_empty_title`, `playlist_search_empty_body`,
   `playlist_search_match_count` (`%1$d of %2$d songs`, as a `<plurals>` on the total count).
6. **Tests** (below).
7. **Docs** — no README change; the CHANGELOG is release-generated, so leave it alone.

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

Unit — reorder under a filter. Existing coverage in
`data/playlist/PlaylistOrderMergeTest.kt` and `PlaylistPreferencesRepositoryTest.kt:58` already
asserts that a subset order leaves hidden ids in place, which is the whole mechanism (§7.1). Add:

- the plan's example: stored `[A,B,C,D,E]`, visible `[D,B]` → `[A,D,C,B,E]`
- a duplicate id that is only partly visible is refused (exists at
  `PlaylistPreferencesRepositoryTest.kt:66`; keep it as the regression guard)
- the ViewModel's rebuild step, if extracted as a pure `rebuildResolvedSongs(fullOrder, currentSongs)`
  helper — same id twice with one occurrence unresolved → occurrence order preserved

Compose — `app/src/androidTest/.../presentation/screens/PlaylistSearchFieldTest.kt`, using the
`createComposeRule()` + Truth pattern from `CreatePlaylistDialogTest.kt`:

- typing filters a stub list; clearing restores all rows
- empty-result state shows the query and the clear button returns the full list
- reorder mode and search field are usable at the same time (no mode is force-off)

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
| Reordering while filtered writes the wrong permutation | It cannot: the drop reports an id order and `mergeVisiblePlaylistOrder` fills only the slots those ids already hold, refusing anything that is not a permutation of the visible multiset. Worth an explicit test with a hidden song and with a duplicate |
| The reorder contract change breaks another caller | `reorderSongsInPlaylist` has exactly one caller (`PlaylistDetailScreen.kt:286`) and no test references the index API |
| LazyColumn keys collide after filtering | Keys stay the row's full-playlist index, which is unique and travels with the row through filtering and dragging |
| Sorted-mode reorder behaves oddly | Pre-existing: a drop materializes the current visible order into the stored slots and flips the playlist to manual order. Filtering simply narrows which slots are touched — no new behaviour, but call it out in the PR |
| Keyboard covers the mini player / last rows under edge-to-edge (`MainActivity.kt:225`) | Field is top-anchored; add `Modifier.imePadding()` to the list's bottom `contentPadding` when the field is focused |
| A user expects Play to respect the filter | Play/Shuffle are above the field and never change state while filtering; the filtered-empty state still offers Play, which reads as "these buttons are the playlist's, not the search's" |
| Smart playlists refresh and drop a matched song | `refreshSmartPlaylistIfNeeded` (`PlaylistViewModel.kt:338`) re-derives the list; the query re-filters, so the view self-corrects |
| Translated strings drifting from parity | Add all keys to all 14 `values*/` files in the same commit; flag non-English wording for native review in the PR |

---

## 13. Delivery

- Branch: `feature/playlist-search`
- Commits, one logical step each:
  1. `Add a pure playlist song matcher` (matcher + unit tests)
  2. `Reorder playlist songs by visible order` (ViewModel contract change, index arithmetic removed,
     subset merge tests) — lands first and is behaviour-preserving for the unfiltered screen
  3. `Extract the pill search field into a shared composable`
  4. `Filter songs in the playlist screen with a search field` (screen wiring + strings + compose test)
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
4. **Reorder stays available while filtering.** Disabling it was a workaround for an index-based
   reorder API; the repository's subset merge already expresses the operation, so the plan now
   moves the ViewModel to a visible-order contract instead (§7.1).

Remaining design detail left to implementation: whether the field sits under the app bar
(current proposal) or replaces the Add/Remove/Reorder chip row. Both are one-line layout
choices; the chip row is hidden while searching either way.
