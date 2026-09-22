package com.learntoplay.app.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionSimilarityTest {

    @Test
    fun identicalQuestions_areDuplicates() {
        assertTrue(
            QuestionSimilarity.isLikelyDuplicate(
                "What is the capital of France?",
                "What is the capital of France?"
            )
        )
    }

    @Test
    fun rewordedSameQuestion_isDuplicate() {
        assertTrue(
            QuestionSimilarity.isLikelyDuplicate(
                "What is the capital city of France?",
                "Which city is the capital of France?"
            )
        )
    }

    @Test
    fun caseAndPunctuationDontPreventDetection() {
        assertTrue(
            QuestionSimilarity.isLikelyDuplicate(
                "What is the capital of France?",
                "WHAT IS THE CAPITAL OF FRANCE"
            )
        )
    }

    @Test
    fun differentQuestionsOnSameTopic_areNotDuplicates() {
        assertFalse(
            QuestionSimilarity.isLikelyDuplicate(
                "What is the capital of France?",
                "What river flows through Paris?"
            )
        )
    }

    @Test
    fun unrelatedQuestions_areNotDuplicates() {
        assertFalse(
            QuestionSimilarity.isLikelyDuplicate(
                "What is the capital of France?",
                "How many legs does a spider have?"
            )
        )
    }

    @Test
    fun blankInput_isNeverADuplicate() {
        assertFalse(QuestionSimilarity.isLikelyDuplicate("", "What is the capital of France?"))
        assertFalse(QuestionSimilarity.isLikelyDuplicate("", ""))
    }

    @Test
    fun isLikelyDuplicateOfAny_findsMatchAnywhereInList() {
        val existing = listOf(
            "How many legs does a spider have?",
            "What is the capital city of France?",
            "What river flows through Paris?"
        )
        assertTrue(
            QuestionSimilarity.isLikelyDuplicateOfAny("Which city is the capital of France?", existing)
        )
    }

    @Test
    fun isLikelyDuplicateOfAny_falseWhenNoMatch() {
        val existing = listOf(
            "How many legs does a spider have?",
            "What river flows through Paris?"
        )
        assertFalse(
            QuestionSimilarity.isLikelyDuplicateOfAny("What is the capital of France?", existing)
        )
    }

    @Test
    fun isLikelyDuplicateOfAny_emptyExistingList_isFalse() {
        assertFalse(QuestionSimilarity.isLikelyDuplicateOfAny("What is the capital of France?", emptyList()))
    }
}
