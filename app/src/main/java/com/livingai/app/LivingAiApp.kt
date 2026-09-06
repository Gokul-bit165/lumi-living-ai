package com.livingai.app

import android.app.Application
import com.livingai.app.companion.CompanionOverlayController
import com.livingai.app.companion.CompanionStateMachine
import com.livingai.app.companion.InAppCompanionOverlayController
import com.livingai.app.context.ContextEngine
import com.livingai.app.context.ContextEngineImpl
import com.livingai.app.core.BatteryMonitor
import com.livingai.app.core.PermissionManager
import com.livingai.app.core.ThermalMonitor
import com.livingai.app.focus.AttentionManager
import com.livingai.app.focus.DataStoreGoalRepository
import com.livingai.app.focus.DistractionDetector
import com.livingai.app.focus.FocusEngine
import com.livingai.app.focus.FocusSessionManager
import com.livingai.app.focus.GoalRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

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

    override fun onCreate() {
        super.onCreate()

        permissionManager = PermissionManager(this)
        batteryMonitor = BatteryMonitor(this, appScope)
        thermalMonitor = ThermalMonitor(this, appScope)
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

        contextEngine.start()
        focusEngine.start()
    }
}
