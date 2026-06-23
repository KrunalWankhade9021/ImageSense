# Gallery-first home (P0) — Design Spec

**Date:** 2026-06-23
**Status:** Approved for planning
**Scope:** P0 only. Collections, find-similar-as-a-tab, date scrubber, pinch-to-resize
grid, filters, and Delete are explicitly **out of scope** (later milestones).

## Product vision

> "Your private gallery that finds anything." A beautiful, offline photo gallery
> you can talk to — your photos never leave your phone.

Today the app opens to an empty search box, which reads as a prototype. This
milestone reframes it as a **gallery-first** app: open to your photos, browsable
immediately; search is the accelerator you reach for at the top, not the entry
point. The differentiation (semantic understanding) shows up as **Find similar**
in the viewer.

## Naming

The visible app name standardizes on **ImageSense** (matches the renamed repo).
This spec updates the launcher label and the in-app header from "NLPhotos" to
"ImageSense". Package/namespace (`com.nlphotos`) stays unchanged to avoid an
install-breaking applicationId change.

## Invariants (must not regress)

- **No INTERNET permission.** Privacy by construction stays. Share uses a
  `FileProvider` content URI (user-initiated), which needs no network and no
  dangerous permission.
- **No media-write capability.** Delete is deferred; the app remains read-only
  with respect to the user's photos.
- **Accuracy bar unchanged.** Embedding parity test (cosine ≥ 0.83) stays green.
- **Offline indexing/search unchanged.** The existing ONNX + WorkManager
  pipeline is reused as-is.

## Information architecture

A `Scaffold` with a **bottom navigation bar**, two destinations plus a
full-screen overlay:

1. **Photos** (default home) — time-grouped gallery of all accessible photos,
   read directly from MediaStore.
2. **Search** — the existing NL search experience (rounded search bar,
   suggestion chips, results grid), lightly refreshed and moved under this tab.
3. **Photo viewer** — full-screen overlay (NOT a nav tab), reachable from either
   tab. Swipe between photos, pinch-zoom, and an action bar.

Selected tab survives configuration changes. Back from the viewer returns to the
originating tab/scroll position.

## Components

Each unit has one purpose, a clear interface, and is testable in isolation.

### GalleryRepository
- **Does:** Queries MediaStore for all accessible images (id, content URI,
  dateTaken/dateModified), newest-first.
- **Interface:** `suspend fun loadAll(): List<MediaItem>` (reuse the existing
  `MediaItem`/`PhotoScanner` query path; extend rather than duplicate).
- **Depends on:** ContentResolver. Independent of the embedding index.

### GallerySection (pure model)
- **Does:** Buckets a photo list into date sections with human labels
  ("Today", "Yesterday", "June 2024").
- **Interface:** `fun groupByDate(items: List<MediaItem>, now: Long): List<GallerySection>`
  where `GallerySection(label: String, items: List<MediaItem>)`.
- **Depends on:** nothing Android — pure function, fully unit-testable.

### GalleryScreen
- **Does:** Renders sections in a `LazyVerticalGrid` with **sticky date
  headers**, adaptive columns, Coil thumbnails with `surfaceVariant`
  placeholders. Tapping a tile opens the viewer at that photo.
- **Depends on:** gallery sections flow from the VM, Coil.

### PhotoViewerScreen
- **Does:** Full-screen viewer over a list of photos. `HorizontalPager` to swipe
  between them; pinch-zoom/pan (reuse existing `rememberTransformableState`); an
  action bar with **Share**, **Info**, **Find similar**; tap-to-dismiss / close.
- **Interface:** `PhotoViewer(items: List<MediaItem>, startIndex: Int, onDismiss,
  onFindSimilar: (photoId) -> Unit)`.
- **Depends on:** Coil, FileProvider (Share), VM (Find similar).

### AppScaffold
- **Does:** Hosts the bottom nav (Photos/Search), keeps selected-tab state, and
  renders the viewer overlay above the tab content.

### ViewModel additions
- **Gallery:** expose `gallerySections: StateFlow<List<GallerySection>>`, loaded
  from `GalleryRepository` off the main thread.
- **Find similar:** `findSimilar(photoId)` — looks up that photo's vector in the
  existing in-memory `VectorBuffer` and runs `buffer.search(vector)`. Reuses the
  current search result plumbing.

## Data flow

- **Photos tab** ← MediaStore directly. Shows the full library the instant
  permission is granted, completely independent of indexing. This is the
  mechanism that makes indexing feel *additive, not blocking*.
- **Search tab** ← existing path: text → ONNX text encoder → `VectorBuffer`
  search → results.
- **Find similar** ← look up the tapped photo's vector in `VectorBuffer` →
  `buffer.search(vector)` → present as search-style results. If the photo is not
  yet indexed, show "Still indexing this photo — try again shortly" rather than a
  dead end.

## P0 UX fixes (bundled, because they define "feels proper")

1. **Indexing transparency.** Replace the blocking-feeling indexing banner with a
   non-blocking pill: **"Search ready for X / Y"**. Browsing the Photos tab never
   waits on indexing; only Search/Find-similar depend on it.
2. **Add-photos reload.** After a forced re-index (the "Add photos" flow), the
   existing work-completion observer reloads the `VectorBuffer`. Verify both the
   gallery (MediaStore-backed, refreshes on resume) and search (buffer-backed)
   reflect newly added photos without an app restart. (Addresses the prior code
   review's P0 bug.)
3. **Honest empty/zero states.** Gallery with no photos, search still indexing,
   and no-match all explain *why* and offer a next step — never a dead end.

## Share implementation

- Add a `FileProvider` (`<provider>` entry + `res/xml/file_paths.xml`).
- Share action builds an `ACTION_SEND` intent with the photo's content URI and
  `image/*`. No new permission; nothing leaves the device unless the user picks a
  target app.

## Testing

- **Unit (JVM, no device):**
  - `GallerySection.groupByDate` — bucketing/labels across Today/Yesterday/month
    boundaries and empty input.
  - `findSimilar` ranking against a fake `VectorBuffer` (including the
    not-yet-indexed path).
- **Regression:** existing embedding-parity (≥0.83) and manifest-privacy
  (no INTERNET) tests stay green.

## Out of scope (future milestones)

- Collections tab + auto-collection shelves (P1)
- Find-similar as its own browsing surface (beyond the viewer entry point)
- Delete / media-write, bulk select (later)
- Date scrubber, pinch-to-resize grid, filters, recent searches (P2 polish)
