package com.livingai.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.livingai.app.companion.CompanionBubble
import com.livingai.app.context.UserContext
import com.livingai.app.core.PermissionManager

class MainActivity : ComponentActivity() {

    private val requestActivityRecognition = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* re-checked reactively via PermissionManager on next composition pass */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as LivingAiApp

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !app.permissionManager.hasActivityRecognition()
        ) {
            requestActivityRecognition.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }

        setContent {
            MaterialTheme {
                LivingAiHomeScreen(app)
            }
        }
    }
}

@Composable
private fun LivingAiHomeScreen(app: LivingAiApp) {
    val context by app.contextEngine.currentContext.collectAsState()
    val companionState by app.companionStateMachine.state.collectAsState()
    var showDebug by remember { mutableStateOf(true) }

    LaunchedEffect(context) {
        app.companionStateMachine.onContextChanged(context)
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Living AI", style = MaterialTheme.typography.headlineMedium)
                Text(text = "Your phone doesn't just respond to you. It understands your day.")

                if (!app.permissionManager.hasUsageAccess()) {
                    Button(
                        onClick = { app.startActivity(app.permissionManager.usageAccessSettingsIntent()) },
                        modifier = Modifier.padding(top = 12.dp)
                    ) {
                        Text("Grant usage access (for focus/app awareness)")
                    }
                }

                Button(onClick = { showDebug = !showDebug }, modifier = Modifier.padding(top = 8.dp)) {
                    Text(if (showDebug) "Hide debug panel" else "Show debug panel")
                }

                if (showDebug) {
                    DebugPanel(context, app.permissionManager)
                }
            }

            CompanionBubble(
                state = companionState,
                onTap = { app.companionStateMachine.onTap() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp)
            )
        }
    }
}

@Composable
private fun DebugPanel(context: UserContext, permissionManager: PermissionManager) {
    Column(
        modifier = Modifier.padding(top = 16.dp).width(320.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(text = "DEBUG (developer only)", style = MaterialTheme.typography.labelLarge)
        Text(text = "movementState: ${context.movementState}")
        Text(text = "currentApp: ${context.currentApp ?: "unknown (grant usage access)"}")
        Text(text = "focusState: ${context.focusState}")
        Text(text = "battery: ${context.deviceState.batteryLevel}% charging=${context.deviceState.charging}")
        Text(text = "thermal: ${context.deviceState.thermal}")
        Text(text = "sourceSignals: ${context.sourceSignals.joinToString()}")
        Text(text = "activityRecognitionGranted: ${permissionManager.hasActivityRecognition()}")
        Text(text = "usageAccessGranted: ${permissionManager.hasUsageAccess()}")
    }
}
