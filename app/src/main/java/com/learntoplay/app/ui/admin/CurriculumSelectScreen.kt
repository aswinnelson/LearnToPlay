package com.learntoplay.app.ui.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** MVP scope: pick from the bundled preset list only. Photo/OCR-of-syllabus is a fast-follow. */
@Composable
fun CurriculumSelectScreen(viewModel: AdminViewModel, onBack: () -> Unit) {
    val curricula by viewModel.curricula.collectAsState(initial = emptyList())

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Select Curriculum", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        LazyColumn {
            items(curricula) { c ->
                ListItem(
                    headlineContent = { Text("${c.subject} — ${c.chapterTitle}") },
                    supportingContent = { Text("${c.board} • Grade ${c.grade}") },
                    trailingContent = { if (c.isSelectedByAdmin) Text("Selected") },
                    modifier = Modifier.clickable { viewModel.selectCurriculum(c.id) }
                )
                HorizontalDivider()
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}
