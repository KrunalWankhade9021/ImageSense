package com.nlphotos.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.nlphotos.scan.MediaItem
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PhotoViewerScreen(
    items: List<MediaItem>,
    startIndex: Int,
    onDismiss: () -> Unit,
    onFindSimilar: (Long) -> Unit,
    onDelete: (photoId: Long, uri: String) -> Unit,
) {
    if (items.isEmpty()) return
    val context = LocalContext.current
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, items.lastIndex)) { items.size }
    var showInfo by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                var scale by remember { mutableFloatStateOf(1f) }
                var ox by remember { mutableFloatStateOf(0f) }
                var oy by remember { mutableFloatStateOf(0f) }
                val ts = rememberTransformableState { z, p, _ ->
                    scale = (scale * z).coerceIn(1f, 5f); ox += p.x; oy += p.y
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    AsyncImage(
                        model = Uri.parse(items[page].uri),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = ox, translationY = oy)
                            .transformable(ts),
                    )
                }
            }

            // Top close
            Box(
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                    .background(Color(0x66000000), MaterialTheme.shapes.small)
                    .clickable(onClick = onDismiss).padding(horizontal = 14.dp, vertical = 8.dp),
            ) { Text("Close", color = Color.White, style = MaterialTheme.typography.labelLarge) }

            // Bottom action bar
            val current = items[pager.currentPage]
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Color(0x99000000)).navigationBarsPadding().padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "image/*"
                        putExtra(Intent.EXTRA_STREAM, Uri.parse(current.uri))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, "Share photo"))
                }) { Text("Share", color = Color.White) }
                TextButton(onClick = { showInfo = true }) { Text("Info", color = Color.White) }
                TextButton(onClick = { onFindSimilar(current.photoId); onDismiss() }) {
                    Text("Find similar", color = Color.White)
                }
                TextButton(onClick = { onDelete(current.photoId, current.uri); onDismiss() }) {
                    Text("Delete", color = Color(0xFFFF6E6E))
                }
            }

            if (showInfo) {
                ModalBottomSheet(onDismissRequest = { showInfo = false }) {
                    val it = items[pager.currentPage]
                    Column(Modifier.fillMaxWidth().padding(24.dp)) {
                        Text("Details", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        Text("Date: ${DateFormat.getDateTimeInstance().format(Date(it.dateModified * 1000))}")
                        Text("ID: ${it.photoId}")
                        Text("URI: ${it.uri}", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}
