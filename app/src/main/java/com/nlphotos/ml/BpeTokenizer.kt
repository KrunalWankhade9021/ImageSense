package com.nlphotos.ml

import java.io.InputStream

/**
 * CLIP BPE tokenizer — exact port of open_clip's SimpleTokenizer.
 * Accuracy-critical: produces identical token ids to open_clip for any input.
 */
class BpeTokenizer private constructor(
    private val encoder: Map<String, Int>,
    private val bpeRanks: Map<Pair<String, String>, Int>,
    private val contextLength: Int,
) {
    private val sot = (encoder["<|startoftext|>"] ?: encoder["<start_of_text>"])!!   // 49406
    private val eot = (encoder["<|endoftext|>"] ?: encoder["<end_of_text>"])!!     // 49407

    // Cache for BPE results
    private val bpeCache = HashMap<String, List<String>>()

    // open_clip regex: match contractions, letters, numbers, non-whitespace, whitespace
    private val pat = Regex("""<\|startoftext\|>|<\|endoftext\|>|<start_of_text>|<end_of_text>|'s|'t|'re|'ve|'m|'ll|'d|[\p{L}]+|[\p{N}]|[^\s\p{L}\p{N}]+""",
        setOf(RegexOption.IGNORE_CASE))

    fun encode(text: String): IntArray {
        // 1. Lowercase and clean whitespace (open_clip also strips HTML and collapses whitespace)
        val cleaned = text.lowercase()
            .replace(Regex("""<[^>]+>"""), " ")   // strip HTML tags
            .replace(Regex("""\s+"""), " ")
            .trim()

        // 2. Tokenize with regex, apply BPE, collect token ids
        val tokens = mutableListOf<Int>()
        for (match in pat.findAll(cleaned)) {
            val word = match.value
            // encode each character through byte-to-unicode, then apply BPE
            val wordBpe = bpe(word)
            for (tok in wordBpe) {
                encoder[tok]?.let { tokens.add(it) }
            }
        }

        // 3. Truncate content so EOT always fits at position contextLength-1
        val maxContent = contextLength - 2  // reserve slot 0 for SOT, last for EOT
        val truncated = if (tokens.size > maxContent) tokens.subList(0, maxContent) else tokens

        // 4. Build output: [SOT, ...content..., EOT, 0, 0, ...]
        val result = IntArray(contextLength)
        result[0] = sot
        for (i in truncated.indices) result[i + 1] = truncated[i]
        result[truncated.size + 1] = eot
        // rest stays 0
        return result
    }

    /**
     * Apply BPE to a single word. The word's final character gets the </w> suffix.
     * Returns the list of BPE tokens (strings, with </w> markers) for the word.
     */
    private fun bpe(token: String): List<String> {
        bpeCache[token]?.let { return it }

        // Convert raw bytes to unicode characters via byteToUnicode mapping
        val unicodeWord = token.encodeToByteArray().map { b -> byteEncoder[b.toInt() and 0xFF]!! }.joinToString("")

        // Represent word as list of characters, last one with </w>
        if (unicodeWord.isEmpty()) {
            bpeCache[token] = emptyList()
            return emptyList()
        }

        // Build initial symbol list: individual chars, last char gets </w>
        var symbols: List<String> = if (unicodeWord.length == 1) {
            listOf(unicodeWord + "</w>")
        } else {
            val chars = unicodeWord.map { it.toString() }.toMutableList()
            chars[chars.size - 1] = chars[chars.size - 1] + "</w>"
            chars
        }

        // Greedily merge using ranks
        while (symbols.size > 1) {
            // Find the pair with the lowest rank
            var bestRank = Int.MAX_VALUE
            var bestIdx = -1
            for (i in 0 until symbols.size - 1) {
                val rank = bpeRanks[Pair(symbols[i], symbols[i + 1])] ?: Int.MAX_VALUE
                if (rank < bestRank) {
                    bestRank = rank
                    bestIdx = i
                }
            }
            if (bestIdx == -1 || bestRank == Int.MAX_VALUE) break

            // Merge pair at bestIdx
            val merged = symbols[bestIdx] + symbols[bestIdx + 1]
            val newSymbols = mutableListOf<String>()
            var i = 0
            while (i < symbols.size) {
                if (i == bestIdx) {
                    newSymbols.add(merged)
                    i += 2
                } else {
                    newSymbols.add(symbols[i])
                    i++
                }
            }
            symbols = newSymbols
        }

        bpeCache[token] = symbols
        return symbols
    }

    companion object {
        /**
         * Build the byte-to-unicode mapping exactly as open_clip does.
         */
        private val byteEncoder: Map<Int, String> by lazy {
            buildByteToUnicode()
        }

        private fun buildByteToUnicode(): Map<Int, String> {
            // Printable ASCII range (excl. space) + Latin-1 supplement ranges
            val bs = mutableListOf<Int>()
            // '!' (33) to '~' (126)
            for (b in '!'.code..'~'.code) bs.add(b)
            // '¡' (161) to '¬' (172)
            for (b in '¡'.code..'¬'.code) bs.add(b)
            // '®' (174) to 'ÿ' (255)
            for (b in '®'.code..'ÿ'.code) bs.add(b)

            val cs = bs.map { it }.toMutableList()
            var n = 0
            for (b in 0..255) {
                if (b !in bs) {
                    bs.add(b)
                    cs.add(256 + n)
                    n++
                }
            }
            return bs.zip(cs.map { it.toChar().toString() }).toMap()
        }

        fun fromResources(vocab: InputStream, merges: InputStream, contextLength: Int): BpeTokenizer {
            // Parse vocab.json: token -> id using simple JVM-compatible parser
            val vocabText = vocab.bufferedReader().readText()
            val encoder = parseJsonStringIntMap(vocabText)

            // Parse merges.txt: skip header line, parse pairs with rank = line index
            val bpeRanks = HashMap<Pair<String, String>, Int>()
            val lines = merges.bufferedReader().readLines()
            var rank = 0
            for (line in lines) {
                if (line.startsWith("#")) continue
                val parts = line.split(" ")
                if (parts.size == 2) {
                    bpeRanks[Pair(parts[0], parts[1])] = rank++
                }
            }

            return BpeTokenizer(encoder, bpeRanks, contextLength)
        }

        fun fromAssets(ctx: android.content.Context, d: com.nlphotos.model.ModelDescriptor): BpeTokenizer =
            fromResources(ctx.assets.open(d.vocabAsset), ctx.assets.open(d.mergesAsset), d.contextLength)

        /**
         * Parse a flat JSON object of the form {"string": integer, ...} without
         * depending on org.json (which is not available in pure JVM unit tests).
         */
        internal fun parseJsonStringIntMap(json: String): HashMap<String, Int> {
            val map = HashMap<String, Int>()
            // Regex: capture JSON string key and integer value
            val pattern = Regex(""""((?:[^"\\]|\\.)*)"\s*:\s*(-?\d+)""")
            for (match in pattern.findAll(json)) {
                val rawKey = match.groupValues[1]
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\/", "/")
                    .replace("\\n", "\n")
                    .replace("\\r", "\r")
                    .replace("\\t", "\t")
                val value = match.groupValues[2].toInt()
                map[rawKey] = value
            }
            return map
        }

        /**
         * Parse a flat JSON object of the form {"string": [int, int, ...], ...}.
         */
        internal fun parseJsonStringIntArrayMap(json: String): Map<String, IntArray> {
            val map = LinkedHashMap<String, IntArray>()
            // Match key then array
            val keyPat = Regex(""""((?:[^"\\]|\\.)*)"\s*:\s*\[([^\]]*)\]""")
            for (match in keyPat.findAll(json)) {
                val rawKey = match.groupValues[1]
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                val arrStr = match.groupValues[2]
                val ints = arrStr.split(",").map { it.trim().toInt() }.toIntArray()
                map[rawKey] = ints
            }
            return map
        }
    }
}
