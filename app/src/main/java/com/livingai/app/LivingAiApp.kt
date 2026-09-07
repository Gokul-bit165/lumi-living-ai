package com.livingai.app

import android.app.Application
import android.content.Context
import com.livingai.app.ai.inference.LocalTextModel
import com.livingai.app.ai.inference.MediaPipeLocalTextModel
import com.livingai.app.ai.inference.OpenAiCompatRemoteTextModel
import com.livingai.app.ai.inference.RemoteTextModel
import com.livingai.app.ai.routing.InferenceRouter
import com.livingai.app.ai.settings.RemoteAiSettingsRepository
import com.livingai.app.ai.vision.LocalVisionModel
import com.livingai.app.ai.vision.LocalVisionModelProvider
import com.livingai.app.camera.CameraCaptureController
import com.livingai.app.companion.CompanionOverlayController
import com.livingai.app.companion.CompanionStateMachine
import com.livingai.app.companion.InAppCompanionOverlayController
import com.livingai.app.context.ContextEngine
import com.livingai.app.context.ContextEngineImpl
import com.livingai.app.core.BatteryMonitor
import com.livingai.app.core.PerformanceMonitor
import com.livingai.app.core.PermissionManager
import com.livingai.app.core.ThermalMonitor
import com.livingai.app.focus.AttentionManager
import com.livingai.app.focus.DataStoreGoalRepository
import com.livingai.app.focus.DistractionDetector
import com.livingai.app.focus.FocusEngine
import com.livingai.app.focus.FocusSessionManager
import com.livingai.app.focus.GoalRepository
import com.livingai.app.voice.AndroidSpeechRecognizer
import com.livingai.app.voice.AndroidTextToSpeech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * V1 uses a plain service locator instead of Hilt/Dagger: for a small hackathon app the
 * annotation-processor overhead isn't worth it, and every dependency here is a singleton wired
 * once at process start. Revisit if the graph grows past this.
 */
class LivingAiApp : Application() {

    val appScope = CoroutineScope(SupervisorJob())

    lateinit var permissionManager: PermissionManager
        private set
    lateinit var batteryMonitor: BatteryMonitor
        private set
    lateinit var thermalMonitor: ThermalMonitor
        private set
    lateinit var performanceMonitor: PerformanceMonitor
        private set
    lateinit var contextEngine: ContextEngine
        private set
    lateinit var companionStateMachine: CompanionStateMachine
        private set
    lateinit var companionOverlayController: CompanionOverlayController
        private set
    lateinit var goalRepository: GoalRepository
        private set
    lateinit var focusSessionManager: FocusSessionManager
        private set
    lateinit var focusEngine: FocusEngine
        private set
    lateinit var textModel: LocalTextModel
        private set
    lateinit var visionModel: LocalVisionModel
        private set
    lateinit var remoteAiSettingsRepository: RemoteAiSettingsRepository
        private set
    lateinit var remoteModel: RemoteTextModel
        private set
    lateinit var inferenceRouter: InferenceRouter
        private set
    lateinit var cameraCaptureController: CameraCaptureController
        private set
    lateinit var speechRecognizer: AndroidSpeechRecognizer
        private set
    lateinit var textToSpeech: AndroidTextToSpeech
        private set

    override fun onCreate() {
        super.onCreate()

        permissionManager = PermissionManager(this)
        batteryMonitor = BatteryMonitor(this, appScope)
        thermalMonitor = ThermalMonitor(this, appScope)
        performanceMonitor = PerformanceMonitor(batteryMonitor, thermalMonitor, appScope)
        contextEngine = ContextEngineImpl(
            context = this,
            scope = appScope,
            permissionManager = permissionManager,
            batteryMonitor = batteryMonitor,
            thermalMonitor = thermalMonitor
        )
        companionStateMachine = CompanionStateMachine(appScope)
        companionOverlayController = InAppCompanionOverlayController()

        goalRepository = DataStoreGoalRepository(this)
        focusSessionManager = FocusSessionManager(appScope)
        focusEngine = FocusEngine(
            scope = appScope,
            contextEngine = contextEngine,
            goalRepository = goalRepository,
            sessionManager = focusSessionManager,
            distractionDetector = DistractionDetector(),
            attentionManager = AttentionManager(),
            companionStateMachine = companionStateMachine
        )

        textModel = MediaPipeLocalTextModel(this)
        visionModel = LocalVisionModelProvider(this)
        remoteAiSettingsRepository = RemoteAiSettingsRepository(this)
        remoteModel = OpenAiCompatRemoteTextModel(remoteAiSettingsRepository)
        inferenceRouter = InferenceRouter(
            textModel = textModel,
            visionModel = visionModel,
            remoteModel = remoteModel,
            networkAvailable = { isNetworkAvailable(this) },
            runtimeStatus = { performanceMonitor.runtimeStatus.value }
        )
        cameraCaptureController = CameraCaptureController(this)
        speechRecognizer = AndroidSpeechRecognizer(this)
        textToSpeech = AndroidTextToSpeech(this)

        contextEngine.start()
        focusEngine.start()
        appScope.launch { textModel.initialize() }
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
