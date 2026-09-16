package com.learntoplay.app.ui.child

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.learntoplay.app.data.db.AppDatabase
import com.learntoplay.app.data.db.entities.GatedAppEntity
import com.learntoplay.app.data.db.entities.QuestionEntity
import com.learntoplay.app.data.repository.QuizRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class QuizUiState(
    val curriculumLabel: String = "",
    val questions: List<QuestionEntity> = emptyList(),
    val currentIndex: Int = 0,
    val correctCount: Int = 0,
    val isComplete: Boolean = false,
    val scorePercent: Int = 0,
    val minutesAwarded: Int = 0,
    /** Total time-bank balance after this attempt, not just what this attempt earned — shown
     * on the completion screen so the child sees the running total, not a confusing partial number. */
    val totalMinutesRemaining: Int = 0,
    /** Apps the child can open right now. Populated on completion so the completion screen can
     * launch them directly instead of leaving the child stuck looking at Learn to Play with no
     * obvious next step. */
    val unlockedApps: List<GatedAppEntity> = emptyList(),
    /** True when no parent has selected a curriculum yet — lets the screen show a way back
     * instead of a dead end with no navigation. */
    val noCurriculumSelected: Boolean = false
)

class QuizViewModel(private val db: AppDatabase) : ViewModel() {
    private val quizRepo = QuizRepository(db)

    private val _state = MutableStateFlow(QuizUiState())
    val state: StateFlow<QuizUiState> = _state

    fun start() {
        viewModelScope.launch {
            val curriculum = quizRepo.observeSelectedCurriculum().first()
            if (curriculum == null) {
                _state.value = QuizUiState(
                    curriculumLabel = "No curriculum selected yet — ask a parent to set one up in Admin Mode.",
                    noCurriculumSelected = true
                )
                return@launch
            }
            val questions = quizRepo.getQuizQuestions(curriculum.id)
            _state.value = QuizUiState(
                curriculumLabel = "${curriculum.subject} • ${curriculum.chapterTitle}",
                questions = questions
            )
        }
    }

    fun answer(selectedOption: String) {
        val s = _state.value
        val question = s.questions.getOrNull(s.currentIndex) ?: return
        val isCorrect = question.correctOption == selectedOption
        val nextIndex = s.currentIndex + 1
        val nextCorrect = s.correctCount + if (isCorrect) 1 else 0

        // Recorded as its own launch, independent of quiz completion below, so the question's
        // selection-weighting stats (see QuizRepository.getQuizQuestions) update right away
        // rather than only if the child finishes the whole quiz.
        viewModelScope.launch { quizRepo.recordAnswer(question.id, isCorrect) }

        if (nextIndex < s.questions.size) {
            _state.value = s.copy(currentIndex = nextIndex, correctCount = nextCorrect)
        } else {
            viewModelScope.launch {
                val curriculum = quizRepo.observeSelectedCurriculum().first() ?: return@launch
                val result = quizRepo.submitQuizAndAwardTime(curriculum.id, nextCorrect, s.questions.size)
                val totalMinutes = quizRepo.getTimeBankMinutesRemaining()
                val unlockedApps = if (totalMinutes > 0) quizRepo.getEnabledGatedApps() else emptyList()
                _state.value = s.copy(
                    currentIndex = nextIndex,
                    correctCount = nextCorrect,
                    isComplete = true,
                    scorePercent = result.scorePercent,
                    minutesAwarded = result.minutesAwarded,
                    totalMinutesRemaining = totalMinutes,
                    unlockedApps = unlockedApps
                )
            }
        }
    }
}
