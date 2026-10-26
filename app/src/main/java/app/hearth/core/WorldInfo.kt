package app.hearth.core

import kotlin.random.Random

data class DepthInjection(val depth: Int, val role: Role, val content: String, val order: Int = 100)

data class WiResult(
    val before: String = "",
    val after: String = "",
    val examplesBefore: String = "",
    val examplesAfter: String = "",
    val depth: List<DepthInjection> = emptyList(),
    val activatedUids: List<String> = emptyList(),
)

/**
 * World Info / lorebook scanner following SillyTavern semantics: constant entries,
 * primary keys (plain or /regex/flags), secondary keys with selective logic,
 * per-entry scan depth, probability, recursion and a token budget.
 */
class WorldInfoScanner(
    private val settings: WiSettings,
    private val tokens: TokenCounter,
    private val random: Random = Random.Default,
) {
    private class Candidate(val book: String, val e: LoreEntry) {
        val key = "$book#${e.uid}"
    }

    /**
     * @param books lorebooks to scan (character's, linked and global)
     * @param historyNewestFirst raw message texts, newest first
     * @param budgetTokens maximum tokens world info may use
     * @param expand macro expansion applied to keys and content
     */
    fun scan(
        books: List<Lorebook>,
        historyNewestFirst: List<String>,
        budgetTokens: Int,
        expand: (String) -> String = { it },
    ): WiResult {
        val all = books.flatMap { b -> b.entries.filter { it.enabled }.map { Candidate(b.id, it) } }
        if (all.isEmpty()) return WiResult()

        val activated = LinkedHashMap<String, Candidate>()
        val bufferCache = HashMap<Int, String>()
        fun buffer(depth: Int): String = bufferCache.getOrPut(depth) {
            historyNewestFirst.take(depth.coerceAtLeast(0)).joinToString("\n")
        }

        // Pass 0: constants and direct matches against chat history.
        for (c in all) {
            if (c.e.constant) { activated[c.key] = c; continue }
            val depth = c.e.scanDepth ?: settings.scanDepth
            if (matches(c.e, buffer(depth), expand)) activated[c.key] = c
        }

        // Recursion: activated content can trigger further entries.
        if (settings.recursive) {
            var step = 0
            var newlyAdded = activated.values.toList()
            while (newlyAdded.isNotEmpty() && step < settings.maxRecursionSteps) {
                val recBuf = newlyAdded.filter { !it.e.preventRecursion }
                    .joinToString("\n") { expand(it.e.content) }
                if (recBuf.isBlank()) break
                val added = ArrayList<Candidate>()
                for (c in all) {
                    if (activated.containsKey(c.key) || c.e.excludeRecursion) continue
                    if (matches(c.e, recBuf, expand)) { activated[c.key] = c; added.add(c) }
                }
                newlyAdded = added
                step++
            }
        }

        // Probability roll happens after matching (as in ST).
        val passed = activated.values.filter { c ->
            !c.e.useProbability || c.e.probability >= 100 || random.nextInt(100) < c.e.probability
        }

        // Budget: constants first, then by order (higher order = higher priority).
        val prioritized = passed.sortedWith(
            compareByDescending<Candidate> { it.e.constant }.thenByDescending { it.e.order },
        )
        var used = 0
        val kept = ArrayList<Candidate>()
        for (c in prioritized) {
            val content = expand(c.e.content)
            if (content.isBlank()) continue
            val cost = tokens.count(content)
            if (used + cost > budgetTokens && kept.isNotEmpty()) continue
            used += cost
            kept.add(c)
        }

        // Output: ascending order, so higher-order entries land closer to the end.
        val sorted = kept.sortedBy { it.e.order }
        fun join(pos: Int) = sorted.filter { it.e.position == pos }.joinToString("\n") { expand(it.e.content) }
        val depth = sorted.filter {
            it.e.position == WiPos.AT_DEPTH || it.e.position == WiPos.AN_TOP || it.e.position == WiPos.AN_BOTTOM
        }.map {
            val d = if (it.e.position == WiPos.AT_DEPTH) it.e.depth else 4
            DepthInjection(d.coerceAtLeast(0), it.e.role, expand(it.e.content), it.e.order)
        }
        return WiResult(
            before = join(WiPos.BEFORE_CHAR),
            after = join(WiPos.AFTER_CHAR),
            examplesBefore = join(WiPos.EXAMPLES_TOP),
            examplesAfter = join(WiPos.EXAMPLES_BOTTOM),
            depth = depth,
            activatedUids = sorted.map { it.key },
        )
    }

    private fun matches(e: LoreEntry, text: String, expand: (String) -> String): Boolean {
        if (text.isEmpty() || e.keys.isEmpty()) return false
        val primary = e.keys.any { k -> keyMatch(expand(k), text, e) }
        if (!primary) return false
        val sec = e.secondaryKeys.filter { it.isNotBlank() }
        if (!e.selective || sec.isEmpty()) return true
        val hits = sec.count { keyMatch(expand(it), text, e) }
        return when (e.selectiveLogic) {
            WiLogic.AND_ANY -> hits > 0
            WiLogic.NOT_ALL -> hits < sec.size
            WiLogic.NOT_ANY -> hits == 0
            WiLogic.AND_ALL -> hits == sec.size
            else -> hits > 0
        }
    }

    internal fun keyMatch(keyRaw: String, text: String, e: LoreEntry): Boolean {
        val key = keyRaw.trim()
        if (key.isEmpty()) return false
        regexKey(key)?.let { return it.containsMatchIn(text) }
        val caseSensitive = e.caseSensitive ?: settings.caseSensitive
        val whole = e.matchWholeWords ?: settings.matchWholeWords
        if (!whole) return text.contains(key, ignoreCase = !caseSensitive)
        val opts = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        val rx = wholeWordCache.getOrPut(key + if (caseSensitive) "\u0001" else "\u0000") {
            Regex("(?:^|\\W)(" + Regex.escape(key) + ")(?:$|\\W)", opts)
        }
        return rx.containsMatchIn(text)
    }

    private val wholeWordCache = HashMap<String, Regex>()
    private val regexCache = HashMap<String, Regex?>()

    private fun regexKey(key: String): Regex? {
        if (key.length < 3 || key[0] != '/') return null
        return regexCache.getOrPut(key) {
            val last = key.lastIndexOf('/')
            if (last <= 0) return@getOrPut null
            val pattern = key.substring(1, last)
            val flags = key.substring(last + 1)
            if (flags.any { it !in "gimsuy" }) return@getOrPut null
            val opts = buildSet {
                if ('i' in flags) add(RegexOption.IGNORE_CASE)
                if ('m' in flags) add(RegexOption.MULTILINE)
                if ('s' in flags) add(RegexOption.DOT_MATCHES_ALL)
            }
            runCatching { Regex(pattern, opts) }.getOrNull()
        }
    }
}
