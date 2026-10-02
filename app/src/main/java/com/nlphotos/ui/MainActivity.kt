package com.nlphotos.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.nlphotos.index.IndexWorker

private val PHOTO_PERMISSION: String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

// Android 14+ exposes a "select more photos" flow; request the user-selected
// permission alongside the full one so re-requesting re-opens the picker.
private val PHOTO_PERMISSIONS: Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
    } else {
        arrayOf(PHOTO_PERMISSION)
    }

/**
 * True if we can read at least some photos. On Android 14+ "limited access"
 * grants only READ_MEDIA_VISUAL_USER_SELECTED (NOT READ_MEDIA_IMAGES), so we
 * must accept either — otherwise partial access looks like a denial.
 */
private fun hasPhotoAccess(context: android.content.Context): Boolean {
    fun ok(p: String) = ContextCompat.checkSelfPermission(context, p) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    return PHOTO_PERMISSIONS.any { ok(it) }
}

// "Calm private vault" — deep slate so photos pop, one warm amber accent.
private val NlPhotosColors = darkColorScheme(
    primary = Color(0xFFFFB74D),            // amber accent
    onPrimary = Color(0xFF3A2400),
    primaryContainer = Color(0xFF2A2118),   // amber-tinted pill background
    onPrimaryContainer = Color(0xFFFFD9A0),
    background = Color(0xFF101116),          // deep slate
    onBackground = Color(0xFFECEDF1),
    surface = Color(0xFF181A22),
    onSurface = Color(0xFFECEDF1),
    surfaceVariant = Color(0xFF242732),      // search bar / tiles
    onSurfaceVariant = Color(0xFFA9AEBC),
    outline = Color(0xFF3A3E4B),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = NlPhotosColors) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val vm: SearchViewModel = viewModel()

    var granted by remember { mutableStateOf(hasPhotoAccess(context)) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        // Accept partial access too: any granted permission means we can read photos.
        granted = results.values.any { it } || hasPhotoAccess(context)
    }

    // "Add photos": re-open the system picker (Android 14+ "select more photos")
    // and force a fresh index so newly selected/captured images get searchable.
    val reselectLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results.values.any { it }) {
            granted = true
            // Force a fresh index; the work-completion observer below reloads the
            // buffer when it finishes, so newly selected photos become searchable
            // without an app restart. (No immediate loadBuffer — the worker hasn't
            // indexed the new photos yet at this point.)
            IndexWorker.enqueue(context.applicationContext, force = true)
        }
    }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(PHOTO_PERMISSIONS)
    }

    // Once granted: kick off indexing and load whatever is already indexed.
    LaunchedEffect(granted) {
        if (granted) {
            IndexWorker.enqueue(context.applicationContext)
            vm.loadBuffer()
            vm.warmUp() // build the text encoder ahead of the first search
            vm.loadGallery()
        }
    }

    // Observe indexing progress and reload buffer when it finishes.
    val workInfos by WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkFlow(IndexWorker.UNIQUE_WORK_NAME)
        .collectAsState(initial = emptyList())

    val info = workInfos.firstOrNull()
    val indexing = info?.state == WorkInfo.State.RUNNING || info?.state == WorkInfo.State.ENQUEUED
    val done = info?.progress?.getInt(IndexWorker.PROGRESS_DONE, 0) ?: 0
    val total = info?.progress?.getInt(IndexWorker.PROGRESS_TOTAL, 0) ?: 0

    LaunchedEffect(info?.state) {
        if (info?.state == WorkInfo.State.SUCCEEDED) { vm.loadBuffer(); vm.loadGallery() }
    }

    if (!granted) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Button(onClick = { launcher.launch(PHOTO_PERMISSIONS) }) {
                Text("Grant photo access")
            }
        }
        return
    }

    val query by vm.query.collectAsState()
    val results by vm.results.collectAsState()
    val indexedCount by vm.indexedCount.collectAsState()
    val searching by vm.searching.collectAsState()
    val gallery by vm.gallery.collectAsState()
    val recentSearches by vm.recentSearches.collectAsState()

    var viewer by remember { mutableStateOf<Pair<Int, Int>?>(null) } // (sectionIdx, itemIdx)
    var showSearchOverlay by remember { mutableStateOf(false) }

    // Delete flow: on Android 11+ the OS shows its own confirm dialog (via the
    // IntentSender returned by MediaStore.createDeleteRequest); on success we
    // purge the photo from the index. Pre-R deletes synchronously.
    var pendingDelete by remember { mutableStateOf<Long?>(null) }
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            pendingDelete?.let { vm.onPhotoDeleted(it) }
        }
        pendingDelete = null
    }
    val onDelete: (Long, String) -> Unit = { photoId, uri ->
        pendingDelete = photoId
        val deletedNow = requestPhotoDelete(context, android.net.Uri.parse(uri), deleteLauncher)
        if (deletedNow) { vm.onPhotoDeleted(photoId); pendingDelete = null }
    }

    val onShare: (Long, String) -> Unit = { _, uri ->
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share via"))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Pinned search bar at top
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clip(RoundedCornerShape(28.dp))
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = if (query.isEmpty()) "Search your photos…" else query,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (query.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { vm.onQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Clear query",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        IconButton(onClick = { showSearchOverlay = true }) {
                            Icon(
                                imageVector = Icons.Filled.Mic,
                                contentDescription = "Voice search",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // Gallery content
            GalleryScreen(
                sections = gallery, indexing = indexing, indexDone = done, indexTotal = total,
                onOpen = { s, i -> viewer = s to i },
                onShare = onShare,
            )
        }

        // Search overlay
        if (showSearchOverlay) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xCC000000))
                    .clickable { showSearchOverlay = false },
                contentAlignment = Alignment.TopCenter,
            ) {
                SearchScreen(
                    query = query,
                    onQueryChange = { text ->
                        vm.onQueryChange(text)
                        if (text.isNotEmpty()) showSearchOverlay = false
                    },
                    onSubmit = { text ->
                        vm.search(text)
                        showSearchOverlay = false
                    },
                    results = results,
                    indexedCount = indexedCount,
                    indexing = indexing,
                    indexDone = done,
                    indexTotal = total,
                    searching = searching,
                    onReindex = { reselectLauncher.launch(PHOTO_PERMISSIONS) },
                    onDelete = onDelete,
                    onShare = onShare,
                    recentSearches = recentSearches,
                )
            }
        }
    }

    viewer?.let { (s, i) ->
        val flat = gallery.getOrNull(s)?.items ?: emptyList()
        if (flat.isNotEmpty()) {
            PhotoViewerScreen(
                items = flat, startIndex = i, onDismiss = { viewer = null },
                onFindSimilar = { id -> vm.findSimilar(id); showSearchOverlay = true },
                onDelete = { id, uri -> onDelete(id, uri); viewer = null },
            )
        }
    }
}

/**
 * Requests deletion of [uri]. Returns true if the photo was deleted synchronously
 * (pre-Android-11); on Android 11+ it launches the system delete-confirm dialog
 * via [launcher] and returns false (the result is handled by the launcher callback).
 */
private fun requestPhotoDelete(
    context: android.content.Context,
    uri: android.net.Uri,
    launcher: androidx.activity.result.ActivityResultLauncher<androidx.activity.result.IntentSenderRequest>,
): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val pi = android.provider.MediaStore.createDeleteRequest(
            context.contentResolver, listOf(uri),
        )
        launcher.launch(androidx.activity.result.IntentSenderRequest.Builder(pi.intentSender).build())
        false
    } else {
        context.contentResolver.delete(uri, null, null)
        true
    }
}
