# Playlist search — implementation plan

Status: proposed
Approach: **a search sheet that finds a song and jumps to it in the playlist**
Scope: any playlist detail screen (`PlaylistDetailScreen`), local / Navidrome / Jellyfin / folder /
smart playlists alike.

---

## 1. Goal

When a playlist has 2000 songs, finding one means scrolling. The search icon opens a sheet in the
app's existing search-dialog style; typing filters the playlist's songs into a short result list;
tapping a result closes the sheet and takes the user to that song in the playlist.

Search is a **locator**. It changes nothing about the playlist, the queue, the sort order or the
playback state — it only moves the viewport to a row.

---

## 2. Decision record

| Option | Verdict |
| --- | --- |
| **Search sheet → dismiss → scroll to the row** (this plan) | **Chosen.** No playlist state is touched, so nothing can disagree with reorder, sort, removal, smart refresh or playback. Directly delivers "locate the song". Built from the app's existing sheet + pill field + song row. |
| Filter the playlist list in place, list stays filtered while the query is active | Rejected. More surface area than the goal needs: filtered list state, "X of Y" header, filtered-empty state, chip-row layout, `imePadding`, plus a reorder contract change (see below). Worse at the actual goal: after filtering, the user still has to clear the query to see the song in context. |
| `MusicRepository.searchSongs` + intersect with playlist ids | Rejected. FTS with a 100-hit cap (`MusicRepositoryImpl.kt:99`), misses Navidrome/Jellyfin songs absent from the local `songs` table, and re-ranks by relevance instead of playlist order. |

Kept from the rejected design because it is needed either way: the pure matcher (§4), the shared
pill search field (§6), and the matching/a11y/i18n rules.

Preserved finding, in case in-place filtering is ever revisited: `mergeVisiblePlaylistOrder`
(`data/playlist/PlaylistOrderMerge.kt`) already persists a *subset* order — stored slots held by
the given ids take the new order, hidden ids keep their positions, duplicates compared as a
multiset, covered by `PlaylistPreferencesRepositoryTest.kt:58`. The only thing that could not
express a filtered reorder was the ViewModel's `(fromIndex, toIndex)` API. That work is **not
needed here** and is dropped.

---

## 3. How the playlist screen works today

| Concern | Where |
| --- | --- |
| Screen + app bar + actions | `presentation/screens/PlaylistDetailScreen.kt:169` |
| Loaded, already-sorted songs | `uiState.currentPlaylistSongs` → `:214` |
| Display list, index-carried | `localReorderableEntries` / `localReorderableSongs` — `:256-261` |
| App bar actions (sort, overflow) | `:369-392` |
| List | `LazyColumn(state = listState)`, `items(..., key = { it.index })` — `:760`, `:775` |
| Scroll target | `listState` (already created at `:263`) |
| Song rows | `QueuePlaylistSongItem` (`QueueBottomSheet.kt:1892`) |

Two facts make this feature small: every playlist song is already in memory, and the list is
indexed by `IndexedValue.index`, so a result can name its exact row.

---

## 4. Matching rules

New pure object `utils/PlaylistSongSearch.kt`, used by the sheet only:

```kotlin
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

    fun filterEntries(entries: List<IndexedValue<Song>>, query: String): List<IndexedValue<Song>> =
        if (query.isBlank()) entries else entries.filter { matches(it.value, query) }

    /** Metadata a user indexes by. The file path is left out: it adds noise (remote ids for
     *  cloud songs) without helping anyone find a track by name. */
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

Rules:

- Blank query shows the whole playlist (so the sheet doubles as a scrollable jump list).
- Case- and diacritic-insensitive: `"melancolico"` matches `"Melancólico"`.
- Tokens AND together, each token matches any field.
- Substring match (`"art"` → `"Artist"`); no fuzzy matching, no typo tolerance.
- Playlist order preserved, no relevance ranking, no result cap.
- Metadata only: title, artist, `displayArtist`, every `artists[]` name, album, album artist,
  genre. File path excluded.
- `NlpText` is reused (`data/playlist/nlp/NlpText.kt:30`) but **not** its stemmer: stemming is
  right for mood queries, wrong for title search.

---

## 5. UX

**Entry** — a `Search` icon in the playlist app-bar `actions` row, left of the existing sort icon
(`:369`). Same `IconButton` treatment as sort, so the app bar does not grow.

**Sheet** — a `ModalBottomSheet` with `skipPartiallyExpanded = true`, matching
`SongPickerBottomSheet`: the playlist stays dimly visible behind it, and the pill field + result
rows are exactly the design already used for picking songs. (The full-screen `Dialog` variant in
`QueueBottomSheet.kt:1500-1900` is the fallback if a phone-height sheet feels cramped.)

```
┌──────────────────────────────────┐
│         Find in playlist         │
│ 🔍  Search songs…            ✕  │   focused, IME open
│ ┌──────────────────────────────┐ │
│ │ [art] Song title             │ │   ← tap: close + jump
│ │       Artist · Album         │ │
│ └──────────────────────────────┘ │
│ …                                │
└──────────────────────────────────┘
```

- Field: the pill `OutlinedTextField` from `SongPickerBottomSheet.kt:435-470` (`surfaceContainerHigh`,
  transparent indicators, `CircleShape`, leading `Search`, trailing `Clear`), extracted to
  `presentation/components/InlineSearchField.kt` and reused. Focus requested on open.
- Rows: `EnhancedSongListItem` (`components/subcomps/EnhancedSongListItem.kt:123`) with
  `showMoreOptionsButton = false`, so the sheet is a pure locator.
- Empty result: `SearchOff` icon + `No songs match "<query>"`, in the style of the existing dialog
  empty state (`QueueBottomSheet.kt:1810-1830`).
- Back / dismissal closes without moving the list.

**On tap — the part that makes it work**

1. Sheet calls `onSongSelected(entry)` with the row's carried index and song id.
2. The sheet hides (`sheetState.hide()`), then the screen scrolls the playlist list.
3. Because a scroll in a 2000-row list is disorienting on its own, the target row **flashes a
   tint** for ~1.5s so the user sees which row is theirs.

```kotlin
// PlaylistDetailScreen.kt
var pendingScrollIndex by remember { mutableStateOf<Int?>(null) }
var highlightedIndex by remember { mutableStateOf<Int?>(null) }

LaunchedEffect(pendingScrollIndex) {
    val target = pendingScrollIndex ?: return@LaunchedEffect
    // Resolve against the current list: a smart playlist may have refreshed while the sheet was
    // open, and a duplicate id must land on the occurrence that was tapped.
    val index = localReorderableEntries.getOrNull(target)?.takeIf { it.index == target }?.index
        ?: localReorderableEntries.indexOfFirst { it.value.id == selectedSongId }
    if (index >= 0) {
        listState.scrollToItem(index)          // instant; the flash explains where we landed
        highlightedIndex = index
        delay(1_500)
        highlightedIndex = null
    }
    pendingScrollIndex = null
}
```

`scrollToItem` rather than `animateScrollToItem`: Compose animates a long jump as a visible
fly-through, which is slower and no clearer than landing with a flash. One constant to tune if it
feels abrupt.

Row highlight is a wrapping `Modifier.background(animatedColor, shape)` around
`QueuePlaylistSongItem` inside the existing `ReorderableItem` (`:790-870`), driven by
`highlightedIndex == entry.index`. No signature change to the row composable.

---

## 6. State and data flow

No `PlaylistUiState` change, no ViewModel change, no repository change. Everything lives in the
screen:

```kotlin
var isSearchSheetVisible by rememberSaveable(playlistId) { mutableStateOf(false) }
```

- The sheet receives `entries: ImmutableList<IndexedValue<Song>>` (built from
  `localReorderableEntries`, which is already index-carrying) and keeps its own query with
  `rememberSaveable` inside the sheet, so rotation does not lose typing.
- The query dies with the sheet: reopening starts empty.
- A query does not survive navigation between playlists, by construction.
- `isSearchSheetVisible` is `rememberSaveable(playlistId)` so switching playlists closes it.

---

## 7. Edge cases

| Case | Behaviour |
| --- | --- |
| Duplicate song ids in a playlist | Result carries the exact occurrence index, and the resolver prefers that index before falling back to the first id match |
| Smart playlist refreshes while the sheet is open | Resolver checks the tapped index still holds the tapped song id, otherwise falls back to id lookup, otherwise no scroll |
| Song removed from the playlist while the sheet is open | Not found → sheet closes, list does not move, no error |
| Reorder / remove mode active | Untouched. Jumping scrolls to the row with its drag handle or remove button visible |
| Sort changes while the sheet is open | Sheet's list is a snapshot; the jump resolves against the live list |
| Folder / cloud playlists | Same code path; they populate `currentPlaylistSongs` identically |
| Playlist shorter than the sheet | Field + rows still work; the sheet simply shows the whole playlist |
| Keyboard | Sheet owns IME handling (`imePadding`, as `QueueBottomSheet.kt:1805`), the playlist list is untouched |

---

## 8. Implementation steps

1. **`utils/PlaylistSongSearch.kt`** (new, ~45 lines) — matcher above, with unit tests.
2. **`presentation/components/InlineSearchField.kt`** (new, ~40 lines) — pill field extracted from
   `SongPickerBottomSheet.kt:435`; parameterized by value / onValueChange / hint / modifier.
3. **`presentation/components/PlaylistSongSearchSheet.kt`** (new, ~150 lines) — `ModalBottomSheet`,
   field, `EnhancedSongListItem` results, empty state, `onSongSelected(entry)`.
4. **`PlaylistDetailScreen.kt`** (~35 changed lines):
   - search `IconButton` in `actions` before sort (`:369`);
   - `isSearchSheetVisible` + sheet call site next to the other sheets (`:1000+`);
   - `pendingScrollIndex` / `highlightedIndex` resolution block (§5);
   - highlight background around `QueuePlaylistSongItem` (`:790-870`).
   - Play / Shuffle / row tap / sort / reorder / remove call sites are **not** touched.
5. **Strings** — new keys in `res/values/strings_presentation_batch_b.xml` (this screen's copy) and
   the same keys in all 13 translated `values-*/` folders, which currently sit at parity (~2066
   strings each): `playlist_search_title`, `playlist_search_hint`, `playlist_search_clear`,
   `playlist_search_empty_title`, `playlist_search_empty_body`. A match-count string is not needed
   — the row list speaks for itself.
6. **Tests** (§10).
7. **Docs** — no README change; CHANGELOG is release-generated.

---

## 9. Accessibility

- Search icon: `contentDescription = playlist_search_open` (reuse the existing
  `cd_search_icon`-style wording), 48dp target via `IconButton`.
- Field: real `label`, IME action Search.
- Clear button: `contentDescription = playlist_search_clear`.
- Result rows announce title, artist and album through `EnhancedSongListItem`'s existing semantics.
- On empty result, the message includes the query so TalkBack reads back what was typed.
- After the jump, move focus to the landed row and set
  `semantics { liveRegion = LiveRegionMode.Polite }` on nothing less than the row itself, so
  TalkBack says which song the viewport moved to. The flash is redundant under TalkBack; the
  announcement is not.
- Flash tint keeps ≥ 4.5:1 contrast with the row text (use `tertiaryContainer` at reduced alpha,
  not a saturated fill).

---

## 10. Tests

Unit — `app/src/test/java/.../utils/PlaylistSongSearchTest.kt`, JUnit Jupiter
(`org.junit.jupiter.api.Test`, per CONTRIBUTING):

- blank / whitespace-only query returns the input entries unchanged
- case-insensitive title match; accented query ↔ unaccented song
- multi-token AND: `"miles kind"` matches, `"miles zappa"` does not
- artist, album, album artist, genre match independently
- multi-artist songs match on any `artists[].name` and on `displayArtist`
- a query that only appears in the file path does **not** match
- filtered entries keep their original `index`, i.e. the jump target survives filtering
- duplicate ids produce separate entries with distinct indices
- no match returns an empty list; the input list is not mutated

Compose — `app/src/androidTest/.../presentation/components/PlaylistSongSearchSheetTest.kt`,
`createComposeRule()` + Truth, following `CreatePlaylistDialogTest.kt`:

- typing narrows the list; clearing restores it
- tapping a row reports the tapped entry's index and song id
- empty state shows the query

Manual QA matrix: 2000-song playlist, duplicate songs, folder/smart/Navidrome/Jellyfin playlist,
RTL (Arabic), landscape, large font scale, keyboard open, jump while reorder mode is on.

---

## 11. Performance

- Filtering is O(n·tokens·fields) over an in-memory list inside `remember(query, entries)`; a
  2000-song playlist is sub-millisecond to a few ms. No debounce needed.
- `.toImmutableList()` is not introduced on a per-keystroke path (CONTRIBUTING: it is O(n)).
- `scrollToItem` composes only the visible window; the flash animates one row's background.
- The playlist `LazyColumn` keeps its existing keys, so the jump does not rebuild rows.

---

## 12. Risks

| Risk | Mitigation |
| --- | --- |
| Tapped index is stale (smart refresh / removal while sheet open) | Resolver validates index→id, then falls back to id lookup, then no-op (§5) |
| Flash is missed on a slow device | 1.5s hold is generous; TalkBack gets a live-region announcement regardless |
| `scrollToItem` feels abrupt after ~1000 rows | One constant to switch to `animateScrollToItem`; the flash was chosen over motion on purpose |
| A third copy of the pill search field | Extract `InlineSearchField` here and let the two existing sheets adopt it later, rather than adding copy #4 |
| Translating 5 new keys | Add all 14 locales in the same commit so parity holds; flag non-English wording for native review |

---

## 13. Delivery

- Branch: `feature/playlist-search`
- Commits:
  1. `Add a pure playlist song matcher` (matcher + unit tests)
  2. `Extract the pill search field into a shared composable`
  3. `Find a song in a playlist and jump to it` (sheet + screen wiring + strings + Compose test)
- PR body: what changed, the tap→scroll→flash flow, and the output of
  `./gradlew :app:compileDebugKotlin :app:lintDebug :app:testDebugUnitTest`. No `Co-Authored-By`
  trailer (CONTRIBUTING).

## 14. Decisions

1. **Search is a locator.** It never filters the playlist, never changes playback, never touches
   the queue snapshot. The only effect is the viewport.
2. **Query resets** when the sheet closes and when the playlist changes.
3. **Play/Shuffle and row taps stay as they are**; picking a search result scrolls, it does not
   play.
4. **Metadata-only matching**, no file path.
5. **In-place filtering rejected** (§2) — and with it the ViewModel reorder-contract change that
   the review had produced.
