package com.nlphotos.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nlphotos.data.IndexStore
import com.nlphotos.gallery.GallerySection
import com.nlphotos.gallery.groupByDate
import com.nlphotos.ml.OnnxEmbeddingEngine
import com.nlphotos.scan.PhotoScanner
import com.nlphotos.model.Models
import com.nlphotos.search.SearchEngine
import com.nlphotos.search.SearchHit
import com.nlphotos.search.VectorBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds the in-memory search stack (engine + vector buffer + search engine) and
 * exposes search results / indexed count as observable state.
 */
class SearchViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = OnnxEmbeddingEngine(application, Models.CLIP_VIT_B32)
    private val store = IndexStore.create(application)
    private val buffer = VectorBuffer()
    private val searchEngine = SearchEngine(engine, buffer)
    private val scanner = PhotoScanner(application)

    private val _indexedCount = MutableStateFlow(0)
    val indexedCount: StateFlow<Int> = _indexedCount.asStateFlow()

    private val _gallery = MutableStateFlow<List<GallerySection>>(emptyList())
    val gallery: StateFlow<List<GallerySection>> = _gallery.asStateFlow()

    private val _results = MutableStateFlow<List<SearchHit>>(emptyList())
    val results: StateFlow<List<SearchHit>> = _results.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    /**
     * Builds the text encoder in the background so the first search is fast
     * instead of paying a ~1 min cold-start to load the model + tokenizer.
     */
    fun warmUp() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) { engine.warmUpText() }
            } catch (e: Exception) {
                // Non-fatal: the first real search will just pay the cold-start.
                android.util.Log.w("SearchViewModel", "Text encoder warm-up failed", e)
            }
        }
    }

    /** (Re)loads the in-memory buffer from the persisted store. */
    fun loadBuffer() {
        viewModelScope.launch {
            val records = withContext(Dispatchers.IO) { store.allRecords() }
            buffer.load(records)
            _indexedCount.value = buffer.size
            // Refresh any active query against the freshly loaded buffer.
            if (_query.value.isNotBlank()) runSearch(_query.value)
        }
    }

    fun onQueryChange(text: String) {
        _query.value = text
        if (text.isBlank()) {
            _results.value = emptyList()
        }
    }

    fun search(text: String = _query.value) {
        _query.value = text
        if (text.isBlank()) {
            _results.value = emptyList()
            return
        }
        runSearch(text)
    }

    private fun runSearch(text: String) {
        viewModelScope.launch {
            _searching.value = true
            try {
                val hits = withContext(Dispatchers.Default) { searchEngine.search(text) }
                // Ignore stale results if the query changed while we were running.
                if (_query.value == text) _results.value = hits
            } finally {
                if (_query.value == text) _searching.value = false
            }
        }
    }

    /** Loads all device photos (MediaStore) into time-grouped sections. */
    fun loadGallery() {
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) { scanner.scan() }
            _gallery.value = withContext(Dispatchers.Default) {
                groupByDate(items, System.currentTimeMillis())
            }
        }
    }

    /** Find photos similar to [photoId]; results surface on the Search tab. */
    fun findSimilar(photoId: Long) {
        val tag = "Similar photos"
        viewModelScope.launch {
            _query.value = tag
            _searching.value = true
            try {
                val hits = withContext(Dispatchers.Default) { searchEngine.findSimilar(photoId) }
                // Ignore stale results if the user moved on (typed a query) meanwhile.
                if (_query.value == tag) _results.value = hits
            } finally {
                if (_query.value == tag) _searching.value = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        engine.close()
    }
}
