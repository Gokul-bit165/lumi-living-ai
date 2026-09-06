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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.livingai.app.ai.model.AIRequest
import com.livingai.app.ai.model.AIRequestType
import com.livingai.app.ai.ui.ModelSetupScreen
import com.livingai.app.camera.ui.CameraHelpScreen
import com.livingai.app.companion.CompanionBubble
import com.livingai.app.context.UserContext
import com.livingai.app.core.PermissionManager
import com.livingai.app.focus.FocusSessionStatus
import com.livingai.app.focus.GoalPriority
import com.livingai.app.focus.ui.FocusScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val requestActivityRecognition = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* re-checked reactively via PermissionManager on next composition pass */ }

    private val requestCameraAndMic = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* re-checked reactively via PermissionManager on next composition pass */ }

    private val hasUsageAccessState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as LivingAiApp

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !app.permissionManager.hasActivityRecognition()
        ) {
            requestActivityRecognition.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }

        hasUsageAccessState.value = app.permissionManager.hasUsageAccess()

        setContent {
            MaterialTheme {
                LivingAiRoot(
                    app = app,
                    hasUsageAccess = hasUsageAccessState.value,
                    onRequestCameraAndMic = {
                        requestCameraAndMic.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val app = application as LivingAiApp
        hasUsageAccessState.value = app.permissionManager.hasUsageAccess()
    }
}

private enum class Screen { HOME, FOCUS, CAMERA_HELP, MODEL_SETUP }

@Composable
private fun LivingAiRoot(app: LivingAiApp, hasUsageAccess: Boolean, onRequestCameraAndMic: () -> Unit) {
    var screen by remember { mutableStateOf(Screen.HOME) }
    val context by app.contextEngine.currentContext.collectAsState()
    val companionState by app.companionStateMachine.state.collectAsState()
    val goal by app.goalRepository.activeGoal.collectAsState(initial = null)
    val session by app.focusSessionManager.session.collectAsState()
    val elapsedMs by app.focusSessionManager.elapsedMs.collectAsState()
    val modelStatus by app.textModel.status.collectAsState()
    val scope = rememberCoroutineScope()

    var isAiBusy by remember { mutableStateOf(false) }
    var aiResultText by remember { mutableStateOf<String?>(null) }
    var question by remember { mutableStateOf("") }
    var lastAiResponse by remember { mutableStateOf<com.livingai.app.ai.model.AIResponse?>(null) }

    LaunchedEffect(context) {
        app.companionStateMachine.onContextChanged(context)
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (screen) {
                Screen.HOME -> HomeScreen(
                    app = app,
                    context = context,
                    goal = goal,
                    sessionStatus = session?.status,
                    elapsedMs = elapsedMs,
                    hasUsageAccess = hasUsageAccess,
                    onSetGoal = { title ->
                        scope.launch {
                            app.goalRepository.setActiveGoal(title, deadline = null, priority = GoalPriority.MEDIUM)
                            app.companionStateMachine.onGoalSet(title)
                        }
                    },
                    onStartFocus = {
                        goal?.let { app.focusSessionManager.start(it.id) }
                        screen = Screen.FOCUS
                    },
                    onResumeFocus = { screen = Screen.FOCUS },
                    onOpenCameraHelp = {
                        if (!app.permissionManager.hasCamera() || !app.permissionManager.hasRecordAudio()) {
                            onRequestCameraAndMic()
                        }
                        app.companionStateMachine.onCameraOpened()
                        screen = Screen.CAMERA_HELP
                    },
                    onOpenModelSetup = { screen = Screen.MODEL_SETUP },
                    lastAiResponse = lastAiResponse
                )

                Screen.FOCUS -> FocusScreen(
                    goal = goal,
                    session = session,
                    elapsedMs = elapsedMs,
                    onPause = { app.focusSessionManager.pause() },
                    onResume = { app.focusSessionManager.resume() },
                    onEnd = {
                        app.focusSessionManager.end()
                        screen = Screen.HOME
                    },
                    onBack = { screen = Screen.HOME }
                )

                Screen.CAMERA_HELP -> CameraHelpScreen(
                    cameraController = app.cameraCaptureController,
                    isBusy = isAiBusy,
                    resultText = aiResultText,
                    question = question,
                    onQuestionChange = { question = it },
                    onListen = {
                        app.companionStateMachine.onListening()
                        scope.launch {
                            app.speechRecognizer.listenOnce()
                                .onSuccess { transcript -> question = transcript }
                            app.companionStateMachine.onAiCancelled()
                        }
                    },
                    onSubmit = { imageBytes, questionText ->
                        isAiBusy = true
                        app.companionStateMachine.onThinking()
                        scope.launch {
                            val response = app.inferenceRouter.route(
                                AIRequest(
                                    type = if (imageBytes != null) AIRequestType.CAMERA_QUESTION else AIRequestType.VOICE_QUESTION,
                                    userText = questionText,
                                    imageBytes = imageBytes,
                                    goalTitle = goal?.title,
                                    focusActive = session?.status == FocusSessionStatus.RUNNING
                                )
                            )
                            aiResultText = response.text
                            lastAiResponse = response
                            isAiBusy = false
                            app.companionStateMachine.onAiResult(response)
                            runCatching { app.textToSpeech.speak(response.text) }
                        }
                    },
                    onBack = {
                        app.cameraCaptureController.unbind()
                        app.companionStateMachine.onCameraClosed()
                        aiResultText = null
                        question = ""
                        screen = Screen.HOME
                    }
                )

                Screen.MODEL_SETUP -> ModelSetupScreen(
                    status = modelStatus,
                    modelName = app.textModel.modelName,
                    runtimeName = app.textModel.runtimeName,
                    onDownload = { token -> scope.launch { app.textModel.downloadAndInitialize(token) } },
                    onBack = { screen = Screen.HOME }
                )
            }

            CompanionBubble(
                state = companionState,
                onTap = { app.companionStateMachine.onTap() },
                onBackToFocus = {
                    app.companionStateMachine.dismissIntervention()
                    if (session?.status == FocusSessionStatus.RUNNING || session?.status == FocusSessionStatus.PAUSED) {
                        screen = Screen.FOCUS
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp)
            )
        }
    }
}

@Composable
private fun HomeScreen(
    app: LivingAiApp,
    context: UserContext,
    goal: com.livingai.app.focus.Goal?,
    sessionStatus: FocusSessionStatus?,
    elapsedMs: Long,
    hasUsageAccess: Boolean,
    onSetGoal: (String) -> Unit,
    onStartFocus: () -> Unit,
    onResumeFocus: () -> Unit,
    onOpenCameraHelp: () -> Unit,
    onOpenModelSetup: () -> Unit,
    lastAiResponse: com.livingai.app.ai.model.AIResponse?
) {
    var showDebug by remember { mutableStateOf(true) }
    var goalInput by remember { mutableStateOf("") }
    val localContext = LocalContext.current

    Column(modifier = Modifier.padding(16.dp)) {
        Text(text = "Living AI", style = MaterialTheme.typography.headlineMedium)
        Text(text = "Your phone doesn't just respond to you. It understands your day.")

        Column(modifier = Modifier.padding(top = 16.dp)) {
            if (goal == null) {
                OutlinedTextField(
                    value = goalInput,
                    onValueChange = { goalInput = it },
                    label = { Text("What's your goal?") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { if (goalInput.isNotBlank()) { onSetGoal(goalInput); goalInput = "" } },
                    modifier = Modifier.padding(top = 8.dp)
                ) { Text("Set goal") }
            } else {
                Text(text = "Goal: ${goal.title}", style = MaterialTheme.typography.titleMedium)
                when (sessionStatus) {
                    FocusSessionStatus.RUNNING, FocusSessionStatus.PAUSED ->
                        Button(onClick = onResumeFocus, modifier = Modifier.padding(top = 8.dp)) { Text("Resume focus") }
                    FocusSessionStatus.ENDED ->
                        Column {
                            Text("You studied ${elapsedMs / 60000} min ${(elapsedMs / 1000) % 60} sec.")
                            Button(onClick = onStartFocus, modifier = Modifier.padding(top = 8.dp)) { Text("Start Focus") }
                        }
                    null ->
                        Button(onClick = onStartFocus, modifier = Modifier.padding(top = 8.dp)) { Text("Start Focus") }
                }
            }
        }

        Row(modifier = Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onOpenCameraHelp) { Text("Show Lumi") }
            Button(onClick = onOpenModelSetup) { Text("AI brain settings") }
        }

        if (!hasUsageAccess) {
            Column(modifier = Modifier.padding(top = 16.dp)) {
                Text("Distraction detection needs usage access to see which app you're in.")
                Button(
                    onClick = {
                        try {
                            localContext.startActivity(app.permissionManager.usageAccessSettingsIntent())
                        } catch (e: Exception) {
                            com.livingai.app.core.LivingAiLog.event("PERMISSION", "Failed to launch usage access settings: ${e.message}")
                        }
                    },
                    modifier = Modifier.padding(top = 4.dp)
                ) { Text("Grant usage access") }
            }
        }

        Button(onClick = { showDebug = !showDebug }, modifier = Modifier.padding(top = 16.dp)) {
            Text(if (showDebug) "Hide debug panel" else "Show debug panel")
        }

        if (showDebug) {
            DebugPanel(context, app, goal, sessionStatus, elapsedMs, hasUsageAccess, lastAiResponse)
        }
    }
}

@Composable
private fun DebugPanel(
    context: UserContext,
    app: LivingAiApp,
    goal: com.livingai.app.focus.Goal?,
    sessionStatus: FocusSessionStatus?,
    elapsedMs: Long,
    hasUsageAccess: Boolean,
    lastAiResponse: com.livingai.app.ai.model.AIResponse?
) {
    val permissionManager: PermissionManager = app.permissionManager
    val modelStatus by app.textModel.status.collectAsState()
    val runtimeStatus by app.performanceMonitor.runtimeStatus.collectAsState()
    val localContext = LocalContext.current
    val networkOn = remember(context.timestamp) { isNetworkAvailable(localContext) }
    Column(
        modifier = Modifier.padding(top = 16.dp).width(340.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(text = "DEBUG (developer only)", style = MaterialTheme.typography.labelLarge)
        Text(text = "activeGoal: ${goal?.title ?: "none"}")
        Text(text = "focusSessionStatus: ${sessionStatus ?: "none"} elapsedMs=$elapsedMs")
        Text(text = "movementState: ${context.movementState}")
        Text(text = "currentApp: ${context.currentApp ?: "unknown (grant usage access)"}")
        Text(text = "focusState: ${context.focusState}")
        Text(text = "battery: ${context.deviceState.batteryLevel}% charging=${context.deviceState.charging}")
        Text(text = "thermal: ${context.deviceState.thermal}")
        Text(text = "sourceSignals: ${context.sourceSignals.joinToString()}")
        Text(text = "activityRecognitionGranted: ${permissionManager.hasActivityRecognition()}")
        Text(text = "usageAccessGranted: $hasUsageAccess")
        Text(text = "cameraGranted: ${permissionManager.hasCamera()}")
        Text(text = "micGranted: ${permissionManager.hasRecordAudio()}")
        Text(text = "cooldownRemainingMs: ${app.focusEngine.cooldownRemainingMs()}")
        Text(text = "runtimeStatus: $runtimeStatus")
        Text(text = "MODEL: ${app.textModel.modelName}")
        Text(text = "RUNTIME: ${app.textModel.runtimeName}")
        Text(text = "MODEL_STATE: ${modelStatus.state}")
        Text(text = "LOCAL: YES")
        Text(text = "NETWORK: ${if (networkOn) "ON" else "OFF"}")
        if (lastAiResponse != null) {
            Text(text = "LAST_TIER: ${lastAiResponse.tier}")
            Text(text = "LAST_LOAD_MS: ${lastAiResponse.loadMs}")
            Text(text = "LAST_INFERENCE_MS: ${lastAiResponse.inferenceMs}")
            Text(text = "LAST_STRUCTURED: ${lastAiResponse.wasStructured}")
        }
    }
}

private fun isNetworkAvailable(context: android.content.Context): Boolean {
    val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    val network = cm.activeNetwork ?: return false
    val capabilities = cm.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
