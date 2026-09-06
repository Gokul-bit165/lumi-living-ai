package com.livingai.app.focus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.livingai.app.focus.FocusSession
import com.livingai.app.focus.FocusSessionStatus
import com.livingai.app.focus.Goal

/** Minimal, single-purpose focus screen: goal, timer, status, a few controls. No dashboard. */
@Composable
fun FocusScreen(
    goal: Goal?,
    session: FocusSession?,
    elapsedMs: Long,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onEnd: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = onBack) { Text("< Home") }

        Text(text = goal?.title ?: "No active goal", style = MaterialTheme.typography.headlineSmall)

        Text(text = formatElapsed(elapsedMs), style = MaterialTheme.typography.displayMedium)

        Text(text = "Status: ${session?.status ?: FocusSessionStatus.ENDED}")

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when (session?.status) {
                FocusSessionStatus.RUNNING -> Button(onClick = onPause) { Text("Pause") }
                FocusSessionStatus.PAUSED -> Button(onClick = onResume) { Text("Resume") }
                else -> Unit
            }
            if (session?.status == FocusSessionStatus.RUNNING || session?.status == FocusSessionStatus.PAUSED) {
                Button(onClick = onEnd) { Text("End session") }
            }
        }
    }
}

private fun formatElapsed(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
