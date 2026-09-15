package com.learntoplay.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.learntoplay.app.data.db.AppDatabase
import com.learntoplay.app.ui.admin.*
import com.learntoplay.app.ui.child.ChildHomeScreen
import com.learntoplay.app.ui.child.QuizScreen
import com.learntoplay.app.ui.child.QuizViewModel

private object Routes {
    const val CHILD_HOME = "child_home"
    const val QUIZ = "quiz"
    const val ADMIN_PIN = "admin_pin"
    const val ADMIN_DASHBOARD = "admin_dashboard"
    const val ADMIN_CURRICULUM = "admin_curriculum"
    const val ADMIN_SCORE_TIME = "admin_score_time"
    const val ADMIN_GATED_APPS = "admin_gated_apps"
    const val ADMIN_HISTORY = "admin_history"
}

/**
 * Single NavHost covering both Child Mode (default, no PIN) and Admin Mode (PIN-gated).
 * There's no OS-level profile split — "who has control" is entirely in-app navigation state,
 * per the single-Android-profile constraint.
 */
@Composable
fun AppNavGraph(
    db: AppDatabase,
    navController: NavHostController = rememberNavController(),
    openQuizOnStart: Boolean = false,
    onOpenQuizConsumed: () -> Unit = {}
) {
    val adminViewModel: AdminViewModel = viewModel(factory = simpleFactory { AdminViewModel(db) })
    val settings by db.adminSettingsDao().observe().collectAsState(initial = null)
    val minutesRemaining = ((settings?.timeBankSecondsRemaining ?: 0L) / 60L).toInt()

    // Overlay's "Start quiz" button relaunches MainActivity with EXTRA_OPEN_QUIZ; jump straight
    // to the quiz screen instead of making the child tap through Child Home again.
    androidx.compose.runtime.LaunchedEffect(openQuizOnStart) {
        if (openQuizOnStart) {
            navController.navigate(Routes.QUIZ) {
                popUpTo(Routes.CHILD_HOME) { inclusive = false }
            }
            onOpenQuizConsumed()
        }
    }

    NavHost(navController = navController, startDestination = Routes.CHILD_HOME) {
        composable(Routes.CHILD_HOME) {
            ChildHomeScreen(
                timeBankMinutesRemaining = minutesRemaining,
                onStartQuiz = { navController.navigate(Routes.QUIZ) },
                onOpenAdmin = { navController.navigate(Routes.ADMIN_PIN) }
            )
        }
        composable(Routes.QUIZ) {
            val quizViewModel: QuizViewModel = viewModel(factory = simpleFactory { QuizViewModel(db) })
            androidx.compose.runtime.LaunchedEffect(Unit) { quizViewModel.start() }
            QuizScreen(quizViewModel) { navController.popBackStack() }
        }
        composable(Routes.ADMIN_PIN) {
            AdminPinScreen(adminViewModel) { navController.navigate(Routes.ADMIN_DASHBOARD) }
        }
        composable(Routes.ADMIN_DASHBOARD) {
            AdminDashboardScreen(
                onManageCurriculum = { navController.navigate(Routes.ADMIN_CURRICULUM) },
                onManageScoreTimeRules = { navController.navigate(Routes.ADMIN_SCORE_TIME) },
                onManageGatedApps = { navController.navigate(Routes.ADMIN_GATED_APPS) },
                onViewHistory = { navController.navigate(Routes.ADMIN_HISTORY) },
                onExitAdmin = { navController.popBackStack(Routes.CHILD_HOME, inclusive = false) }
            )
        }
        composable(Routes.ADMIN_CURRICULUM) {
            CurriculumSelectScreen(adminViewModel) { navController.popBackStack() }
        }
        composable(Routes.ADMIN_SCORE_TIME) {
            ScoreTimeMappingScreen(adminViewModel) { navController.popBackStack() }
        }
        composable(Routes.ADMIN_GATED_APPS) {
            GatedAppsScreen(adminViewModel) { navController.popBackStack() }
        }
        composable(Routes.ADMIN_HISTORY) {
            QuizHistoryScreen(adminViewModel) { navController.popBackStack() }
        }
    }
}

/** Tiny inline ViewModelProvider.Factory helper so screens don't need Hilt for the MVP. */
private fun <T : androidx.lifecycle.ViewModel> simpleFactory(create: () -> T) =
    object : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <U : androidx.lifecycle.ViewModel> create(modelClass: Class<U>): U = create() as U
    }
