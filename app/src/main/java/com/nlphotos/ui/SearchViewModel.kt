package com.nlphotos.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nlphotos.data.IndexStore
import com.nlphotos.ml.OnnxEmbeddingEngine
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

    private val _indexedCount = MutableStateFlow(0)
    val indexedCount: StateFlow<Int> = _indexedCount.asStateFlow()

    private val _results = MutableStateFlow<List<SearchHit>>(emptyList())
    val results: StateFlow<List<SearchHit>> = _results.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

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
            val hits = withContext(Dispatchers.Default) { searchEngine.search(text) }
            _results.value = hits
        }
    }

    override fun onCleared() {
        super.onCleared()
        engine.close()
    }
}
