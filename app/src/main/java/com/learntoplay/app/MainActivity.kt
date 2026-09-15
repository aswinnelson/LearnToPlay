package com.learntoplay.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.learntoplay.app.ui.navigation.AppNavGraph

class MainActivity : ComponentActivity() {

    // Read once at launch/new-intent time; AppNavGraph consumes it and navigates to the quiz.
    private var openQuizOnStart by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openQuizOnStart = intent?.getBooleanExtra(EXTRA_OPEN_QUIZ, false) ?: false
        val db = (application as LearnToPlayApp).database
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavGraph(
                        db = db,
                        openQuizOnStart = openQuizOnStart,
                        onOpenQuizConsumed = { openQuizOnStart = false }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The overlay's "Start quiz" button relaunches this activity with singleTask, which
        // routes here instead of onCreate() if MainActivity is already in the back stack.
        setIntent(intent)
        openQuizOnStart = intent.getBooleanExtra(EXTRA_OPEN_QUIZ, false)
    }

    companion object {
        const val EXTRA_OPEN_QUIZ = "com.learntoplay.app.EXTRA_OPEN_QUIZ"
    }
}
