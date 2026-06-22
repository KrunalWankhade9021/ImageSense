package com.nlphotos.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
            IndexWorker.enqueue(context.applicationContext, force = true)
            vm.loadBuffer()
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
        if (info?.state == WorkInfo.State.SUCCEEDED) vm.loadBuffer()
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

    SearchScreen(
        query = query,
        onQueryChange = vm::onQueryChange,
        onSubmit = { vm.search(it) },
        results = results,
        indexedCount = indexedCount,
        indexing = indexing,
        indexDone = done,
        indexTotal = total,
        searching = searching,
        onReindex = { reselectLauncher.launch(PHOTO_PERMISSIONS) },
    )
}
