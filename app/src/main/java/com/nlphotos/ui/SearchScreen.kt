package com.nlphotos.ui

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.nlphotos.search.SearchHit

/**
 * The top of the app, shared by Home and Search so the search bar never jumps:
 * on Home a brand header sits above the bar; when search is active the header
 * collapses away and the bar slides up into its place.
 */
@Composable
fun TopSearchArea(
    searchActive: Boolean,
    query: String,
    indexedCount: Int,
    indexing: Boolean,
    indexDone: Int,
    indexTotal: Int,
    onQueryChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onActivate: () -> Unit,
    onBack: () -> Unit,
    onVoiceSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
    ) {
        AnimatedVisibility(
            visible = !searchActive,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            HomeHeader(indexedCount, indexing, indexDone, indexTotal)
        }
        Spacer(Modifier.height(8.dp))
        SearchField(
            searchActive = searchActive,
            query = query,
            onQueryChange = onQueryChange,
            onSubmit = onSubmit,
            onActivate = onActivate,
            onBack = onBack,
            onVoiceSearch = onVoiceSearch,
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun HomeHeader(indexedCount: Int, indexing: Boolean, done: Int, total: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "ImageSense",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = when {
                    indexing && total > 0 -> "Getting photos ready · $done of $total"
                    indexing -> "Getting photos ready…"
                    else -> "$indexedCount photos searchable"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                "On-device",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun SearchField(
    searchActive: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onActivate: () -> Unit,
    onBack: () -> Unit,
    onVoiceSearch: () -> Unit,
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused) onActivate() },
        placeholder = {
            Text(
                if (searchActive) "Describe a photo…" else "Search your photos",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingIcon = {
            AnimatedContent(
                targetState = searchActive,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "leadingIcon",
            ) { active ->
                if (active) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Close search",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                } else {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Clear query",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                IconButton(onClick = onVoiceSearch) {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = "Voice search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = MaterialTheme.colorScheme.primary,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) onSubmit(query) }),
    )
}

/**
 * Everything below the search bar while search is active:
 *  - empty query       → Recent searches + Explore cards built from the library
 *  - typing (unsent)   → matching recents / categories, plus "Search for …"
 *  - submitted         → searching spinner, no-match message, or results grid
 */
@Composable
fun SearchContent(
    query: String,
    submittedQuery: String,
    results: List<SearchHit>,
    searching: Boolean,
    indexing: Boolean,
    recentSearches: List<String>,
    explore: List<ExploreItem>,
    onSubmit: (String) -> Unit,
    onRemoveRecent: (String) -> Unit,
    onDelete: (photoId: Long, uri: String) -> Unit,
    onShare: (photoId: Long, uri: String) -> Unit,
) {
    var fullScreen by remember { mutableStateOf<SearchHit?>(null) }

    val mode = when {
        query.isBlank() -> SearchMode.ZeroQuery
        query != submittedQuery -> SearchMode.Typing
        searching -> SearchMode.Searching
        results.isEmpty() -> SearchMode.NoMatches
        else -> SearchMode.Results
    }

    AnimatedContent(
        targetState = mode,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        modifier = Modifier.fillMaxSize(),
        label = "searchContent",
    ) { target ->
        when (target) {
            SearchMode.ZeroQuery -> ZeroQuery(recentSearches, explore, indexing, onSubmit, onRemoveRecent)
            SearchMode.Typing -> Suggestions(query, recentSearches, explore, onSubmit, onRemoveRecent)
            SearchMode.Searching -> SearchingState()
            SearchMode.NoMatches -> CenterMessage(
                title = "No matches",
                subtitle = "Nothing matched “$submittedQuery”. Try describing it differently.",
            )
            SearchMode.Results -> LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
            ) {
                sectionLabel("Best matches")
                items(results, key = { it.photoId }) { hit ->
                    PhotoTile(
                        uri = hit.uri,
                        onClick = { fullScreen = hit },
                        onShare = { onShare(hit.photoId, hit.uri) },
                    )
                }
            }
        }
    }

    fullScreen?.let { hit ->
        FullScreenViewer(
            uri = hit.uri,
            onDismiss = { fullScreen = null },
            onDelete = {
                onDelete(hit.photoId, hit.uri)
                fullScreen = null
            },
        )
    }
}

private enum class SearchMode { ZeroQuery, Typing, Searching, NoMatches, Results }

private const val MAX_RECENTS_SHOWN = 4

@Composable
private fun ZeroQuery(
    recentSearches: List<String>,
    explore: List<ExploreItem>,
    indexing: Boolean,
    onSubmit: (String) -> Unit,
    onRemoveRecent: (String) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        if (recentSearches.isNotEmpty()) {
            sectionLabel("Recent")
            items(recentSearches.take(MAX_RECENTS_SHOWN), span = { GridItemSpan(maxLineSpan) }) { q ->
                SuggestionRow(text = q, recent = true, onClick = { onSubmit(q) }, onRemove = { onRemoveRecent(q) })
            }
        }
        if (explore.isNotEmpty()) {
            sectionLabel("Explore your photos")
            items(explore, key = { it.label }) { item ->
                ExploreCard(item, onClick = { onSubmit(item.label) })
            }
        } else if (recentSearches.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    if (indexing) "Your photos are being prepared for search. Explore will appear here shortly."
                    else "Describe what's in a photo — “receipt”, “dog on the beach”, “birthday cake”.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun Suggestions(
    query: String,
    recentSearches: List<String>,
    explore: List<ExploreItem>,
    onSubmit: (String) -> Unit,
    onRemoveRecent: (String) -> Unit,
) {
    val q = query.trim()
    val recents = recentSearches.filter { it.contains(q, ignoreCase = true) && !it.equals(q, ignoreCase = true) }
    val categories = explore.map { it.label }
        .filter { it.contains(q, ignoreCase = true) && it !in recents && !it.equals(q, ignoreCase = true) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(1),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        item { SuggestionRow(text = "Search for “$q”", recent = false, onClick = { onSubmit(q) }) }
        items(recents.take(MAX_RECENTS_SHOWN)) { r ->
            SuggestionRow(text = r, recent = true, onClick = { onSubmit(r) }, onRemove = { onRemoveRecent(r) })
        }
        items(categories) { c -> SuggestionRow(text = c, recent = false, onClick = { onSubmit(c) }) }
    }
}

private fun LazyGridScope.sectionLabel(text: String) {
    item(span = { GridItemSpan(maxLineSpan) }) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        )
    }
}

@Composable
private fun SuggestionRow(
    text: String,
    recent: Boolean,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (recent) Icons.Outlined.History else Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 10.dp),
        )
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Remove “$text” from recents",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun ExploreCard(item: ExploreItem, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = Uri.parse(item.coverUri),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // Bottom scrim keeps the label legible on any photo.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.45f to Color.Transparent,
                        1f to Color(0xCC000000),
                    ),
                ),
        )
        Text(
            item.label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp),
        )
    }
}

@Composable
private fun PhotoTile(uri: String, onClick: () -> Unit, onShare: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { _ -> onClick() },
                    onLongPress = { _ -> onShare() }
                )
            },
    ) {
        AsyncImage(
            model = Uri.parse(uri),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun SearchingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text(
                "Searching your photos…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun CenterMessage(title: String, subtitle: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun FullScreenViewer(uri: String, onDismiss: () -> Unit, onDelete: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offsetX by remember { mutableFloatStateOf(0f) }
        var offsetY by remember { mutableFloatStateOf(0f) }
        val transformState = rememberTransformableState { zoomChange, panChange, _ ->
            scale = (scale * zoomChange).coerceIn(1f, 5f)
            offsetX += panChange.x
            offsetY += panChange.y
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = Uri.parse(uri),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY,
                    )
                    .transformable(transformState),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0x66000000))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = null,
                        tint = Color.White,
                    )
                    Text("Close", color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
            }

            // Delete action — the OS shows its own confirmation dialog before deleting.
            // Fixed bottom offset (insets aren't dispatched inside a Dialog window,
            // so navigationBarsPadding would be 0 and the button would clip behind
            // the gesture bar).
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 24.dp, end = 24.dp, bottom = 56.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xCCB00020))
                    .clickable(onClick = onDelete)
                    .padding(horizontal = 28.dp, vertical = 14.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        tint = Color.White,
                    )
                    Text("Delete", color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
