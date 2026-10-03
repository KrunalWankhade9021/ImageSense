package com.nlphotos.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nlphotos.gallery.GallerySection

@Composable
fun GalleryScreen(
    sections: List<GallerySection>,
    onOpen: (Int, Int) -> Unit,
    onShare: (photoId: Long, uri: String) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (sections.isEmpty()) {
            EmptyGallery(); return
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(112.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            // Last row scrolls clear of the gesture bar (we draw edge-to-edge).
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        ) {
            sections.forEachIndexed { sIdx, section ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SectionHeader(section.label, section.items.size, first = sIdx == 0)
                }
                items(section.items.size) { iIdx ->
                    val item = section.items[iIdx]
                    val uri = item.uri
                    val photoId = item.photoId
                    Box(
                        Modifier
                            .aspectRatio(1f)
                            .clip(TileShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { _ -> onOpen(sIdx, iIdx) },
                                    onLongPress = { _ -> onShare(photoId, uri) }
                                )
                            },
                    ) {
                        AsyncImage(
                            model = Uri.parse(uri), contentDescription = null,
                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                        )
                        TileOutline()
                    }
                }
            }
        }
    }
}

/** Small corners + tight gaps so the grid reads as one block, like a camera roll. */
internal val TileShape = RoundedCornerShape(4.dp)

/**
 * Hairline edge drawn over a tile so dark photos (screenshots, night shots)
 * still read as a distinct square against the dark background.
 */
@Composable
internal fun TileOutline() {
    Box(Modifier.fillMaxSize().border(1.dp, Color.White.copy(alpha = 0.08f), TileShape))
}

/** Date section title with a muted count; more air above than below so it binds to its photos. */
@Composable
internal fun SectionHeader(title: String, count: Int? = null, first: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (first) 4.dp else 24.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (count != null) {
            Text(
                "  ·  $count",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyGallery() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Icon(
                imageVector = Icons.Filled.PhotoLibrary,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(64.dp).height(64.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "No photos yet",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Photos you grant access to will appear here.",
                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
