package com.learntoplay.app.ui.admin

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.learntoplay.app.data.db.entities.ScoreTimeRuleEntity
import kotlinx.coroutines.launch

/**
 * The core "privilege" screen: the parent defines the reward curve
 * (e.g. 50-69% -> 15 min, 70-89% -> 30 min, 90-100% -> 45 min).
 * MVP keeps this to a fixed 3-band editor rather than a free-form rule builder.
 */
@Composable
fun ScoreTimeMappingScreen(viewModel: AdminViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var bands by remember {
        mutableStateOf(
            listOf(
                ScoreTimeRuleEntity(50, 69, 15),
                ScoreTimeRuleEntity(70, 89, 30),
                ScoreTimeRuleEntity(90, 100, 45)
            )
        )
    }

    LaunchedEffect(Unit) {
        val existing = viewModel.getScoreTimeRules()
        if (existing.isNotEmpty()) bands = existing
    }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Score → Time Rules", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        bands.forEachIndexed { i, rule ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("${rule.minScorePercent}–${rule.maxScorePercent}%", modifier = Modifier.width(90.dp))
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = rule.minutesAwarded.toString(),
                    onValueChange = { v ->
                        val minutes = v.toIntOrNull() ?: rule.minutesAwarded
                        bands = bands.toMutableList().also { it[i] = rule.copy(minutesAwarded = minutes) }
                    },
                    label = { Text("Minutes") },
                    modifier = Modifier.width(120.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            scope.launch { viewModel.saveScoreTimeRules(bands) }
            onBack()
        }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}
