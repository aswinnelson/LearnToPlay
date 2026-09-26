package com.learntoplay.app.ui.child

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.learntoplay.app.data.db.AppDatabase
import com.learntoplay.app.data.db.entities.GatedAppEntity
import com.learntoplay.app.data.db.entities.QuestionEntity
import com.learntoplay.app.data.db.entities.QuestionType
import com.learntoplay.app.data.repository.AnswerMatcher
import com.learntoplay.app.data.repository.QuizRepository
import com.learntoplay.app.remote.FamilySyncRepository
import com.learntoplay.app.util.AllowedWindowChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class QuizUiState(
    val curriculumLabel: String = "",
    val questions: List<QuestionEntity> = emptyList(),
    val currentIndex: Int = 0,
    val correctCount: Int = 0,
    /** For each multiple-choice question id, the order its options are shown in, e.g.
     * ["C", "A", "D", "B"]. Shuffled once per quiz so the correct answer isn't always in the
     * slot the parent happened to type it into (in practice nearly always A). Correctness is
     * still checked against the original letter, so shuffling can't change a result. */
    val optionOrder: Map<Long, List<String>> = emptyMap(),
    /** True from the moment the final answer is tapped until the result is saved — the screen
     * disables the answer buttons meanwhile. */
    val isSubmitting: Boolean = false,
    val isComplete: Boolean = false,
    val scorePercent: Int = 0,
    val minutesAwarded: Int = 0,
    /** Total time-bank balance after this attempt, in seconds — shown on the completion screen
     * so the child sees the running total, not a confusing partial number. */
    val secondsRemaining: Long = 0L,
    /** Apps the child can open right now. Populated on completion so the completion screen can
     * launch them directly instead of leaving the child stuck looking at Learn to Play with no
     * obvious next step. Empty outside Allowed Hours. */
    val unlockedApps: List<GatedAppEntity> = emptyList(),
    /** Set when the quiz finished outside the parent's Allowed Hours: explains when the earned
     * time can actually be used, instead of offering "Play now" buttons that would only hit the
     * "Not right now" lock. */
    val allowedHoursMessage: String? = null,
    /** Set when a quiz can't run at all (no curriculum chosen, or the chosen one has no
     * questions yet). The screen shows it with a Back button instead of a dead end. */
    val blockedMessage: String? = null
)

class QuizViewModel(private val db: AppDatabase, context: Context) : ViewModel() {
    private val appContext = context.applicationContext
    private val quizRepo = QuizRepository(db)

    private val _state = MutableStateFlow(QuizUiState())
    val state: StateFlow<QuizUiState> = _state

    /** The curriculum this quiz was started with — kept, rather than re-read at submit time, so
     * a parent switching the active curriculum mid-quiz can't misattribute the result. */
    private var curriculumId: String? = null

    /** Plain field (not UI state) so it's set synchronously on the main thread, before any
     * coroutine runs — see advance(). */
    private var submitting = false

    fun start() {
        viewModelScope.launch {
            val curriculum = quizRepo.observeSelectedCurriculum().first()
            if (curriculum == null) {
                _state.value = QuizUiState(
                    blockedMessage = "No curriculum selected yet — ask a parent to set one up in Parent Settings."
                )
                return@launch
            }
            val questions = quizRepo.getQuizQuestions(curriculum.id)
            if (questions.isEmpty()) {
                // Used to leave the child on a screen showing only the chapter name, with no
                // question and no way back — easy to hit in the MVP, where a parent creates a
                // curriculum and selects it before typing any questions into it.
                _state.value = QuizUiState(
                    blockedMessage = "There are no questions in ${curriculum.subject} — " +
                        "${curriculum.chapterTitle} yet. Ask a parent to add some in Parent Settings."
                )
                return@launch
            }
            curriculumId = curriculum.id
            submitting = false
            _state.value = QuizUiState(
                curriculumLabel = "${curriculum.subject} • ${curriculum.chapterTitle}",
                questions = questions,
                optionOrder = questions
                    .filter { it.questionType != QuestionType.FILL_IN }
                    .associate { it.id to OPTION_KEYS.shuffled() }
            )
        }
    }

    /** MCQ path — [selectedOption] is the question's original letter ("A".."D"), whatever
     * position it was shown in. */
    fun answer(questionId: Long, selectedOption: String) {
        val question = currentQuestionIfMatches(questionId) ?: return
        advance(question, question.correctOption == selectedOption)
    }

    /** FILL_IN path — the child typed a free-text answer, matched leniently via AnswerMatcher
     * so formatting slip-ups (case, spacing, punctuation) don't cost them, but they still have
     * to actually know the answer — no multiple-choice luck involved. */
    fun answerFillIn(questionId: Long, typedText: String) {
        val question = currentQuestionIfMatches(questionId) ?: return
        val isCorrect = AnswerMatcher.isCorrect(typedText, question.correctAnswerText ?: "")
        advance(question, isCorrect)
    }

    /** The question currently on screen — but only if [questionId] is that question. A second
     * tap that lands after the quiz already moved on (a double-tap) is ignored rather than
     * counted as an answer to the NEXT question. Also null once the final answer is being
     * submitted or the quiz is complete. */
    private fun currentQuestionIfMatches(questionId: Long): QuestionEntity? {
        if (submitting) return null
        val s = _state.value
        if (s.isComplete) return null
        return s.questions.getOrNull(s.currentIndex)?.takeIf { it.id == questionId }
    }

    private fun advance(question: QuestionEntity, isCorrect: Boolean) {
        val s = _state.value
        val nextIndex = s.currentIndex + 1
        val nextCorrect = s.correctCount + if (isCorrect) 1 else 0

        // Recorded as its own launch, independent of quiz completion below, so the question's
        // selection-weighting stats (see QuizRepository.getQuizQuestions) update right away
        // rather than only if the child finishes the whole quiz.
        viewModelScope.launch { quizRepo.recordAnswer(question.id, isCorrect) }

        if (nextIndex < s.questions.size) {
            _state.value = s.copy(currentIndex = nextIndex, correctCount = nextCorrect)
            return
        }

        val id = curriculumId ?: return
        // Set synchronously, BEFORE the coroutine below suspends: a quick second tap on the
        // last answer arrives on the main thread before anything has been written, and used to
        // submit the quiz — and award its minutes — twice.
        submitting = true
        _state.value = s.copy(isSubmitting = true)

        viewModelScope.launch {
            val result = quizRepo.submitQuizAndAwardTime(id, nextCorrect, s.questions.size)
            val secondsLeft = quizRepo.getTimeBankSecondsRemaining()
            val outsideWindow = quizRepo.getAllowedHoursIfCurrentlyOutside()
            val unlockedApps =
                if (secondsLeft > 0 && outsideWindow == null) quizRepo.getEnabledGatedApps() else emptyList()
            _state.value = s.copy(
                currentIndex = nextIndex,
                correctCount = nextCorrect,
                isSubmitting = false,
                isComplete = true,
                scorePercent = result.scorePercent,
                minutesAwarded = result.minutesAwarded,
                secondsRemaining = secondsLeft,
                unlockedApps = unlockedApps,
                allowedHoursMessage = outsideWindow?.let { (start, end) ->
                    "Apps can be used between ${AllowedWindowChecker.format12Hour(start)} and " +
                        "${AllowedWindowChecker.format12Hour(end)}. Your time is saved until then."
                }
            )
            // So a parent checking remotely sees a just-finished quiz without waiting for
            // the periodic sync from TimeBankTrackerService (which only runs while a
            // gated app is open). Best-effort/silent — see FamilySyncRepository.
            FamilySyncRepository.pushSnapshot(appContext, db)
        }
    }

    private companion object {
        val OPTION_KEYS = listOf("A", "B", "C", "D")
    }
}
