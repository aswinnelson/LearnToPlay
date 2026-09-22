package com.learntoplay.app.ai

/** Flags when two question prompts are close enough to count as duplicates rather than two
 * genuinely different questions on the same topic — used by [QuestionAiGenerator] to warn a
 * parent when an AI-drafted question looks like one already in the question bank. Easy to hit
 * in practice: a parent scanning the same textbook page a second time, or running "From Topic"
 * again on a subject they've already generated questions for.
 *
 * Two questions sharing a topic naturally share a couple of words ("photosynthesis", "plant")
 * without being duplicates — what actually distinguishes a near-duplicate is that MOST of their
 * significant words overlap, not just one or two. Jaccard similarity over each prompt's
 * significant-word set captures that: two differently-worded questions on the same topic score
 * low (most of each prompt's words differ), while a reworded repeat of essentially the same
 * question scores high (nearly every word is shared). Pure and dependency-free so it's plain
 * JUnit-testable, same as AnswerMatcher/ScoreTimeCalculator.
 *
 * This only ever flags a draft for the parent to look at — see
 * [QuestionAiGenerator.DraftQuestion.isLikelyDuplicate] — it never silently drops a question. A
 * heuristic this simple will have false positives on genuinely distinct questions that happen
 * to share most of their wording, and the parent (who can see both questions side by side) is a
 * better judge of that than an automatic drop would be. */
object QuestionSimilarity {

    /** Below this Jaccard score, two prompts are treated as different questions even if they
     * share some words — an independent question on the same topic typically lands well under
     * this; a reworded repeat of the same question typically lands well over it. */
    private const val DUPLICATE_THRESHOLD = 0.6

    /** Words common enough to show up in almost any question regardless of what it's actually
     * asking — excluded so they don't inflate every pair's overlap score equally. Deliberately
     * short; the goal is filtering grammatical noise, not building a real stopword list (see
     * QuestionAiGenerator.MATCH_STOPWORDS for the same idea applied to page-image matching). */
    private val STOPWORDS = setOf(
        "the", "a", "an", "is", "are", "was", "were", "of", "in", "on", "at", "to", "for",
        "and", "or", "how", "what", "which", "does", "did", "do", "many", "much", "this",
        "that", "these", "those", "with", "from", "you", "your"
    )

    /** True when [a] and [b] are similar enough to likely be the same question asked two
     * different ways. Order doesn't matter — the comparison is symmetric. */
    fun isLikelyDuplicate(a: String, b: String): Boolean {
        val tokensA = significantWords(a)
        val tokensB = significantWords(b)
        if (tokensA.isEmpty() || tokensB.isEmpty()) return false
        val union = tokensA.union(tokensB)
        if (union.isEmpty()) return false
        val intersection = tokensA.intersect(tokensB).size
        return intersection.toDouble() / union.size >= DUPLICATE_THRESHOLD
    }

    /** True when [prompt] is a likely duplicate of any question text in [existing]. */
    fun isLikelyDuplicateOfAny(prompt: String, existing: List<String>): Boolean =
        existing.any { isLikelyDuplicate(prompt, it) }

    private fun significantWords(text: String): Set<String> =
        Regex("""[A-Za-z0-9]+""").findAll(text.lowercase())
            .map { it.value }
            .filter { it.length > 2 && it !in STOPWORDS }
            .toSet()
}
