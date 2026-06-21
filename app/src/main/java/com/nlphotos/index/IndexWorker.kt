package com.nlphotos.index

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.nlphotos.data.IndexStore
import com.nlphotos.data.PhotoRecord
import com.nlphotos.ml.EmbeddingEngine
import com.nlphotos.ml.ImagePreprocessor
import com.nlphotos.ml.OnnxEmbeddingEngine
import com.nlphotos.model.Models
import com.nlphotos.scan.MediaItem
import com.nlphotos.scan.PhotoScanner
import com.nlphotos.scan.computeDiff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * WorkManager-backed indexer. Scans the gallery, diffs against the store,
 * encodes new/changed photos in batches, upserts them, deletes removed ones,
 * and reports progress.
 *
 * The actual indexing logic lives in the top-level [runIndexing] function so it
 * can be tested directly with fakes; [doWork] just wires the real collaborators.
 */
class IndexWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val descriptor = Models.CLIP_VIT_B32
        val scanner = PhotoScanner(applicationContext)
        val preprocessor = ImagePreprocessor(descriptor)
        val store = IndexStore.create(applicationContext)
        val engine = OnnxEmbeddingEngine(applicationContext, descriptor)

        engine.use {
            runIndexing(
                scan = { scanner.scan() },
                engine = engine,
                store = store,
                decode = { item ->
                    preprocessor.decodeDownsampled(
                        applicationContext,
                        Uri.parse(item.uri),
                    )
                },
                setProgress = { done, total ->
                    setProgress(workDataOf(PROGRESS_DONE to done, PROGRESS_TOTAL to total))
                },
            )
        }
        Result.success()
    }

    companion object {
        const val PROGRESS_DONE = "done"
        const val PROGRESS_TOTAL = "total"
        const val UNIQUE_WORK_NAME = "nlphotos-index"
        const val BATCH_SIZE = 16

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<IndexWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}

/**
 * Core indexing logic, decoupled from WorkManager and Android collaborators for testing.
 *
 * Resumability: completed items are upserted per batch, so a re-run computes a
 * smaller diff (via [computeDiff] against the store) and re-encodes only the
 * remainder — no extra state needed.
 *
 * @param scan returns the current set of media items on the device.
 * @param engine encodes a decoded bitmap to an embedding vector.
 * @param store persists records and provides the indexed id/dateModified map.
 * @param decode decodes a [MediaItem] to a bitmap to be encoded.
 * @param setProgress reports progress after each batch.
 */
suspend fun runIndexing(
    scan: () -> List<MediaItem>,
    engine: EmbeddingEngine,
    store: IndexStore,
    decode: (MediaItem) -> android.graphics.Bitmap,
    setProgress: suspend (done: Int, total: Int) -> Unit,
    batchSize: Int = IndexWorker.BATCH_SIZE,
) {
    val current = scan()
    val diff = computeDiff(current, store.allIds())

    val total = diff.toEncode.size
    var done = 0
    setProgress(done, total)

    for (batch in diff.toEncode.chunked(batchSize)) {
        val records = ArrayList<PhotoRecord>(batch.size)
        for (item in batch) {
            val bitmap = decode(item)
            val vector = try {
                engine.encodeImage(bitmap)
            } finally {
                bitmap.recycle()
            }
            records.add(
                PhotoRecord(
                    photoId = item.photoId,
                    uri = item.uri,
                    dateModified = item.dateModified,
                    vector = vector,
                ),
            )
        }
        store.upsertAll(records)
        done += batch.size
        setProgress(done, total)
        yield()
    }

    if (diff.toDelete.isNotEmpty()) {
        store.delete(diff.toDelete)
    }
}
