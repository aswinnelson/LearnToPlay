package com.learntoplay.app.ui.child

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.learntoplay.app.data.db.AppDatabase
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
    val minutesAwarded: Int = 0
)

class QuizViewModel(private val db: AppDatabase) : ViewModel() {
    private val quizRepo = QuizRepository(db)

    private val _state = MutableStateFlow(QuizUiState())
    val state: StateFlow<QuizUiState> = _state

    fun start() {
        viewModelScope.launch {
            val curriculum = quizRepo.observeSelectedCurriculum().first()
            if (curriculum == null) {
                _state.value = QuizUiState(curriculumLabel = "No curriculum selected yet — ask a parent to set one up in Admin Mode.")
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

        if (nextIndex < s.questions.size) {
            _state.value = s.copy(currentIndex = nextIndex, correctCount = nextCorrect)
        } else {
            viewModelScope.launch {
                val curriculum = quizRepo.observeSelectedCurriculum().first() ?: return@launch
                val result = quizRepo.submitQuizAndAwardTime(curriculum.id, nextCorrect, s.questions.size)
                _state.value = s.copy(
                    currentIndex = nextIndex,
                    correctCount = nextCorrect,
                    isComplete = true,
                    scorePercent = result.scorePercent,
                    minutesAwarded = result.minutesAwarded
                )
            }
        }
    }
}
