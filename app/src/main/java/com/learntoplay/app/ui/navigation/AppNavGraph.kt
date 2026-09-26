package com.learntoplay.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.learntoplay.app.data.db.AppDatabase
import com.learntoplay.app.ui.admin.*
import com.learntoplay.app.ui.child.ChildHomeScreen
import com.learntoplay.app.ui.child.QuizScreen
import com.learntoplay.app.ui.child.QuizViewModel
import com.learntoplay.app.ui.remote.RemoteMonitorScreen
import com.learntoplay.app.util.PendingShare

private object Routes {
    const val CHILD_HOME = "child_home"
    const val QUIZ = "quiz"
    const val ADMIN_PIN = "admin_pin"
    const val ADMIN_DASHBOARD = "admin_dashboard"
    const val ADMIN_CURRICULUM = "admin_curriculum"
    const val ADMIN_SCORE_TIME = "admin_score_time"
    const val ADMIN_GATED_APPS = "admin_gated_apps"
    const val ADMIN_HISTORY = "admin_history"
    const val ADMIN_MANAGE_QUESTIONS = "admin_manage_questions"
    const val ADMIN_DEVICE_SETTINGS = "admin_device_settings"
    const val REMOTE_MONITOR = "remote_monitor"
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
    onOpenQuizConsumed: () -> Unit = {},
    // Set when MainActivity was launched via another app's share sheet (see PendingShare) —
    // e.g. a parent sharing a teacher's WhatsApp message into LearnToPlay. Routes the parent
    // through the PIN gate and a curriculum picker before the share ever reaches
    // ManageQuestionsScreen, same as any other admin-only, content-adding action; consumed
    // there (not here) once it's actually been handed off, so it survives the PIN + curriculum
    // navigation steps in between.
    pendingShare: PendingShare? = null,
    onPendingShareConsumed: () -> Unit = {}
) {
    val appContext = LocalContext.current.applicationContext
    val adminViewModel: AdminViewModel =
        viewModel(factory = simpleFactory { AdminViewModel(db, appContext) })
    val settings by db.adminSettingsDao().observe().collectAsState(initial = null)

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

    // A share-sheet hand-off always starts the same way regardless of what's currently on
    // screen: the parent has to authenticate first, since it's about to add content to the
    // question bank. AdminPinScreen's onUnlocked below is what continues this to the
    // curriculum picker rather than the normal Admin Dashboard.
    androidx.compose.runtime.LaunchedEffect(pendingShare) {
        if (pendingShare != null) {
            navController.navigate(Routes.ADMIN_PIN) {
                popUpTo(Routes.CHILD_HOME) { inclusive = false }
            }
        }
    }

    NavHost(navController = navController, startDestination = Routes.CHILD_HOME) {
        composable(Routes.CHILD_HOME) {
            ChildHomeScreen(
                timeBankSecondsRemaining = settings?.timeBankSecondsRemaining ?: 0L,
                allowedHours = settings
                    ?.takeIf { it.allowedWindowEnabled }
                    ?.let { it.allowedWindowStartMinute to it.allowedWindowEndMinute },
                onStartQuiz = { navController.navigate(Routes.QUIZ) },
                onOpenAdmin = { navController.navigate(Routes.ADMIN_PIN) }
            )
        }
        composable(Routes.QUIZ) {
            val quizViewModel: QuizViewModel =
                viewModel(factory = simpleFactory { QuizViewModel(db, appContext) })
            androidx.compose.runtime.LaunchedEffect(Unit) { quizViewModel.start() }
            QuizScreen(quizViewModel) { navController.popBackStack() }
        }
        composable(Routes.ADMIN_PIN) {
            AdminPinScreen(
                adminViewModel,
                onUnlocked = {
                    // A pending share skips the dashboard and goes straight to picking which
                    // curriculum it belongs to — the dashboard has nothing to do with a share
                    // that's already in flight.
                    if (pendingShare != null) {
                        navController.navigate(Routes.ADMIN_CURRICULUM)
                    } else {
                        navController.navigate(Routes.ADMIN_DASHBOARD)
                    }
                },
                onViewRemoteDevice = { navController.navigate(Routes.REMOTE_MONITOR) }
            )
        }
        composable(Routes.ADMIN_DASHBOARD) {
            AdminDashboardScreen(
                adminViewModel = adminViewModel,
                onManageCurriculum = { navController.navigate(Routes.ADMIN_CURRICULUM) },
                onManageScoreTimeRules = { navController.navigate(Routes.ADMIN_SCORE_TIME) },
                onManageGatedApps = { navController.navigate(Routes.ADMIN_GATED_APPS) },
                onViewHistory = { navController.navigate(Routes.ADMIN_HISTORY) },
                onOpenDeviceSettings = { navController.navigate(Routes.ADMIN_DEVICE_SETTINGS) },
                onExitAdmin = { navController.popBackStack(Routes.CHILD_HOME, inclusive = false) }
            )
        }
        composable(Routes.ADMIN_CURRICULUM) {
            CurriculumSelectScreen(
                viewModel = adminViewModel,
                onManageQuestions = { curriculumId ->
                    navController.navigate("${Routes.ADMIN_MANAGE_QUESTIONS}/$curriculumId")
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            route = "${Routes.ADMIN_MANAGE_QUESTIONS}/{curriculumId}",
            arguments = listOf(navArgument("curriculumId") { type = NavType.StringType })
        ) { backStackEntry ->
            val curriculumId = backStackEntry.arguments?.getString("curriculumId")
            if (curriculumId != null) {
                ManageQuestionsScreen(
                    viewModel = adminViewModel,
                    curriculumId = curriculumId,
                    incomingShare = pendingShare,
                    onIncomingShareConsumed = onPendingShareConsumed,
                    onBack = { navController.popBackStack() }
                )
            }
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
        composable(Routes.ADMIN_DEVICE_SETTINGS) {
            DeviceSettingsScreen(adminViewModel, onBack = { navController.popBackStack() })
        }
        composable(Routes.REMOTE_MONITOR) {
            RemoteMonitorScreen(onBack = { navController.popBackStack() })
        }
    }
}

/** Tiny inline ViewModelProvider.Factory helper so screens don't need Hilt for the MVP. */
private fun <T : androidx.lifecycle.ViewModel> simpleFactory(create: () -> T) =
    object : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <U : androidx.lifecycle.ViewModel> create(modelClass: Class<U>): U = create() as U
    }
