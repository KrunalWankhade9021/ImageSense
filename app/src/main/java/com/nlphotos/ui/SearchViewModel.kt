package com.nlphotos.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nlphotos.data.IndexStore
import com.nlphotos.data.RecentSearchStore
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

/** A zero-query "Explore" card: a category label plus its best-matching photo. */
data class ExploreItem(val label: String, val coverUri: String)

// Categories probed against the index to build the Explore cards: the short
// label shown to the user, and a descriptive prompt CLIP actually matches well
// (single words like "Night" match poorly).
private val EXPLORE_CATEGORIES = mapOf(
    "People" to "a group photo of people",
    "Selfies" to "a selfie portrait of a person",
    "Documents" to "a document with printed text and tables",
    "Screenshots" to "a screenshot of a phone app screen",
    "Tickets" to "a ticket with a QR code",
    "Receipts" to "a paper receipt or bill",
    "Food" to "a photo of food on a plate",
    "Nature" to "a landscape photo of nature",
    "Pets" to "a photo of a pet dog or cat",
    "Cars" to "a photo of a car",
    "Buildings" to "a photo of a building",
    "Night" to "a photo taken outdoors at night",
    "Shoes" to "a photo of shoes",
)
private const val MAX_EXPLORE_ITEMS = 9

// Query label shown while viewing "find similar" results; never saved as a recent.
private const val SIMILAR_TAG = "Similar photos"

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
    private val recentStore = RecentSearchStore.create(application)

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

    /** The query whose results are currently shown ("" if none was submitted). */
    private val _submittedQuery = MutableStateFlow("")
    val submittedQuery: StateFlow<String> = _submittedQuery.asStateFlow()

    private val _explore = MutableStateFlow<List<ExploreItem>>(emptyList())
    val explore: StateFlow<List<ExploreItem>> = _explore.asStateFlow()

    private val _recentSearches = MutableStateFlow<List<String>>(emptyList())
    val recentSearches: StateFlow<List<String>> = _recentSearches.asStateFlow()

    // Last computed Explore cards, so they show instantly on the next launch
    // instead of waiting for the text encoder's cold start.
    private val exploreCache = application.getSharedPreferences("explore_cache", android.content.Context.MODE_PRIVATE)

    init {
        loadRecentSearches()
        _explore.value = exploreCache.getString("items", null).orEmpty()
            .lines()
            .mapNotNull { line -> line.split('\t').takeIf { it.size == 2 }?.let { ExploreItem(it[0], it[1]) } }
    }

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

    /**
     * Called after a photo was deleted from the device (via the system delete
     * flow). Removes it from the index DB, refreshes the in-memory buffer and
     * the current result/gallery lists so it disappears everywhere immediately.
     */
    fun onPhotoDeleted(photoId: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.delete(listOf(photoId)) }
            val records = withContext(Dispatchers.IO) { store.allRecords() }
            buffer.load(records)
            _indexedCount.value = buffer.size
            _results.value = _results.value.filterNot { it.photoId == photoId }
            loadGallery()
            loadExplore() // a deleted photo may have been an Explore cover
        }
    }

    /** (Re)loads the in-memory buffer from the persisted store. */
    fun loadBuffer() {
        viewModelScope.launch {
            val records = withContext(Dispatchers.IO) { store.allRecords() }
            buffer.load(records)
            _indexedCount.value = buffer.size
            // Refresh any submitted query against the freshly loaded buffer.
            if (_submittedQuery.value.isNotBlank() && _submittedQuery.value == _query.value) {
                runSearch(_query.value)
            }
            loadExplore()
        }
    }

    /**
     * Builds the Explore cards. Every photo is assigned to the single category it
     * matches best; a category becomes a card only if it owns at least one photo,
     * using its strongest owned photo as the cover. This keeps e.g. a portrait
     * from being the "Documents" cover just because it was that query's top hit.
     */
    private fun loadExplore() {
        if (buffer.size == 0) return
        viewModelScope.launch {
            try {
                val items = withContext(Dispatchers.Default) {
                    val scores = EXPLORE_CATEGORIES.mapValues { (_, prompt) ->
                        searchEngine.search(prompt, topN = buffer.size)
                    }
                    // photoId -> (category, hit) with the highest score across categories
                    val owner = mutableMapOf<Long, Pair<String, SearchHit>>()
                    scores.forEach { (label, hits) ->
                        hits.forEach { hit ->
                            val cur = owner[hit.photoId]
                            if (cur == null || hit.score > cur.second.score) owner[hit.photoId] = label to hit
                        }
                    }
                    owner.values
                        .groupBy({ it.first }, { it.second })
                        .map { (label, hits) -> label to hits.maxBy { it.score } }
                        .sortedByDescending { (_, cover) -> cover.score }
                        .take(MAX_EXPLORE_ITEMS)
                        .map { (label, cover) -> ExploreItem(label, cover.uri) }
                }
                _explore.value = items
                exploreCache.edit()
                    .putString("items", items.joinToString("\n") { "${it.label}\t${it.coverUri}" })
                    .apply()
            } catch (e: Exception) {
                android.util.Log.w("SearchViewModel", "Explore build failed", e)
            }
        }
    }

    /** Leaves search: clears the query and any shown results. */
    fun clearSearch() {
        _query.value = ""
        _submittedQuery.value = ""
        _results.value = emptyList()
        _searching.value = false
    }

    fun removeRecentSearch(query: String) {
        viewModelScope.launch { recentStore.removeSearch(query) }
    }

    fun onQueryChange(text: String) {
        _query.value = text
        if (text.isBlank()) {
            _results.value = emptyList()
        }
    }

    /** Runs [text] (or its Explore prompt, for a category label) against the index. */
    private fun runSearch(text: String) {
        val prompt = EXPLORE_CATEGORIES[text] ?: text
        viewModelScope.launch {
            _searching.value = true
            try {
                val hits = withContext(Dispatchers.Default) { searchEngine.search(prompt) }
                // Ignore stale results if the query changed while we were running.
                if (_query.value == text) _results.value = hits
            } finally {
                if (_query.value == text) _searching.value = false
            }
        }
    }

    private fun loadRecentSearches() {
        viewModelScope.launch {
            recentStore.recentSearches.collect { list ->
                _recentSearches.value = list.filterNot { it == SIMILAR_TAG }
            }
        }
    }

    private fun saveRecentSearch(query: String) {
        if (query.isNotBlank() && query != SIMILAR_TAG) {
            viewModelScope.launch {
                recentStore.addSearch(query)
            }
        }
    }

    fun search(text: String = _query.value) {
        _query.value = text
        _submittedQuery.value = text
        if (text.isBlank()) {
            _results.value = emptyList()
            return
        }
        saveRecentSearch(text)
        runSearch(text)
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
        val tag = SIMILAR_TAG
        viewModelScope.launch {
            _query.value = tag
            _submittedQuery.value = tag
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
