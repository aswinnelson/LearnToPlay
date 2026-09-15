package com.learntoplay.app.data.seed

import com.learntoplay.app.data.db.entities.CurriculumEntity
import com.learntoplay.app.data.db.entities.QuestionEntity
import com.learntoplay.app.data.db.entities.ScoreTimeRuleEntity

/**
 * MVP scope: a small bundled question bank for one board/grade slice (CBSE Grade 5, two subjects),
 * enough to validate the core "quiz unlocks time" loop end-to-end before investing in a bigger
 * content pipeline or the photo/OCR fast-follow.
 */
object PresetCurriculumSeed {

    val curricula = listOf(
        CurriculumEntity(
            id = "cbse-g5-sci-ch3",
            board = "CBSE",
            grade = 5,
            subject = "Science",
            chapterTitle = "Chapter 3: Food and Nutrition"
        ),
        CurriculumEntity(
            id = "cbse-g5-math-ch2",
            board = "CBSE",
            grade = 5,
            subject = "Mathematics",
            chapterTitle = "Chapter 2: Fractions"
        )
    )

    val questions = listOf(
        QuestionEntity(
            curriculumId = "cbse-g5-sci-ch3",
            prompt = "Which nutrient is the body's main source of quick energy?",
            optionA = "Carbohydrates",
            optionB = "Vitamins",
            optionC = "Minerals",
            optionD = "Water",
            correctOption = "A"
        ),
        QuestionEntity(
            curriculumId = "cbse-g5-sci-ch3",
            prompt = "A balanced diet mainly helps the body to:",
            optionA = "Grow taller only",
            optionB = "Stay healthy and function properly",
            optionC = "Sleep less",
            optionD = "Avoid drinking water",
            correctOption = "B"
        ),
        QuestionEntity(
            curriculumId = "cbse-g5-sci-ch3",
            prompt = "Which of these is a protein-rich food?",
            optionA = "Rice",
            optionB = "Butter",
            optionC = "Lentils (dal)",
            optionD = "Sugar",
            correctOption = "C"
        ),
        QuestionEntity(
            curriculumId = "cbse-g5-math-ch2",
            prompt = "What is 1/2 + 1/4?",
            optionA = "1/6",
            optionB = "3/4",
            optionC = "2/6",
            optionD = "1/4",
            correctOption = "B"
        ),
        QuestionEntity(
            curriculumId = "cbse-g5-math-ch2",
            prompt = "Which fraction is the largest?",
            optionA = "1/3",
            optionB = "1/5",
            optionC = "1/2",
            optionD = "1/8",
            correctOption = "C"
        )
    )

    val defaultScoreTimeRules = listOf(
        ScoreTimeRuleEntity(minScorePercent = 50, maxScorePercent = 69, minutesAwarded = 15),
        ScoreTimeRuleEntity(minScorePercent = 70, maxScorePercent = 89, minutesAwarded = 30),
        ScoreTimeRuleEntity(minScorePercent = 90, maxScorePercent = 100, minutesAwarded = 45)
    )
}
