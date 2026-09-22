package com.learntoplay.app.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerMatcherTest {

    @Test
    fun exactMatch_isCorrect() {
        assertTrue(AnswerMatcher.isCorrect("Paris", "Paris"))
    }

    @Test
    fun caseInsensitive() {
        assertTrue(AnswerMatcher.isCorrect("paris", "Paris"))
        assertTrue(AnswerMatcher.isCorrect("PARIS", "Paris"))
    }

    @Test
    fun ignoresLeadingTrailingWhitespace() {
        assertTrue(AnswerMatcher.isCorrect("  Paris  ", "Paris"))
    }

    @Test
    fun collapsesInternalWhitespace() {
        assertTrue(AnswerMatcher.isCorrect("New   York", "New York"))
    }

    @Test
    fun ignoresPunctuation() {
        assertTrue(AnswerMatcher.isCorrect("Paris.", "Paris"))
        assertTrue(AnswerMatcher.isCorrect("Paris,", "Paris"))
        assertTrue(AnswerMatcher.isCorrect("don't", "dont"))
    }

    @Test
    fun wrongAnswer_isIncorrect() {
        assertFalse(AnswerMatcher.isCorrect("London", "Paris"))
    }

    @Test
    fun partialAnswer_isIncorrect() {
        assertFalse(AnswerMatcher.isCorrect("Par", "Paris"))
    }

    @Test
    fun emptyTypedAnswer_isIncorrect() {
        assertFalse(AnswerMatcher.isCorrect("", "Paris"))
        assertFalse(AnswerMatcher.isCorrect("   ", "Paris"))
    }

    @Test
    fun blankCorrectAnswer_neverMatches() {
        // Guards against a mis-saved FILL_IN question with no correct answer text ever
        // silently "passing" on an empty typed answer.
        assertFalse(AnswerMatcher.isCorrect("", ""))
        assertFalse(AnswerMatcher.isCorrect("anything", ""))
    }
}
