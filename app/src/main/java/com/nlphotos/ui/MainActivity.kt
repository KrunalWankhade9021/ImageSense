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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
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

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, PHOTO_PERMISSION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        granted = isGranted
    }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(PHOTO_PERMISSION)
    }

    // Once granted: kick off indexing and load whatever is already indexed.
    LaunchedEffect(granted) {
        if (granted) {
            IndexWorker.enqueue(context.applicationContext)
            vm.loadBuffer()
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
            Button(onClick = { launcher.launch(PHOTO_PERMISSION) }) {
                Text("Grant photo access")
            }
        }
        return
    }

    val query by vm.query.collectAsState()
    val results by vm.results.collectAsState()
    val indexedCount by vm.indexedCount.collectAsState()

    SearchScreen(
        query = query,
        onQueryChange = vm::onQueryChange,
        onSearch = { vm.search() },
        results = results,
        indexedCount = indexedCount,
        indexing = indexing,
        indexDone = done,
        indexTotal = total,
    )
}
