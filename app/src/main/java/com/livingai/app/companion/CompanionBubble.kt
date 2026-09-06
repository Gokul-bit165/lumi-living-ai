package com.livingai.app.companion

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The "small floating companion" from the product spec: a compact circle near the screen edge
 * that expands into a short speech bubble on tap. Deliberately never fullscreen.
 */
@Composable
fun CompanionBubble(
    state: CompanionState,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    onBackToFocus: (() -> Unit)? = null
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End
    ) {
        AnimatedVisibility(visible = state.expanded && state.message != null) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .width(220.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(text = state.message.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                    if (state.activity == CompanionActivity.WARNING && onBackToFocus != null) {
                        Row { TextButton(onClick = onBackToFocus) { Text("Back to focus") } }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .size(64.dp)
                .clickable(onClick = onTap)
                .background(color = state.activity.color(), shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(text = state.activity.emoji(), style = MaterialTheme.typography.headlineSmall)
        }
    }
}

private fun CompanionActivity.color(): Color = when (this) {
    CompanionActivity.SLEEPING -> Color(0xFF9575CD)
    CompanionActivity.IDLE -> Color(0xFF6C4DFF)
    CompanionActivity.WALKING -> Color(0xFF4CAF50)
    CompanionActivity.WARNING -> Color(0xFFE53935)
    CompanionActivity.HAPPY -> Color(0xFFFFC107)
    CompanionActivity.DETERMINED -> Color(0xFFFF7043)
    CompanionActivity.STUDYING -> Color(0xFF3F51B5)
    CompanionActivity.RESTING -> Color(0xFF26A69A)
    CompanionActivity.CELEBRATING -> Color(0xFFEC407A)
}

private fun CompanionActivity.emoji(): String = when (this) {
    CompanionActivity.SLEEPING -> "😴"
    CompanionActivity.IDLE -> "👻"
    CompanionActivity.WALKING -> "🚶"
    CompanionActivity.WARNING -> "😾"
    CompanionActivity.HAPPY -> "😊"
    CompanionActivity.DETERMINED -> "💪"
    CompanionActivity.STUDYING -> "📚"
    CompanionActivity.RESTING -> "😌"
    CompanionActivity.CELEBRATING -> "🎉"
}
