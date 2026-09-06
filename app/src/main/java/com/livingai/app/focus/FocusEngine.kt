package com.livingai.app.focus

import com.livingai.app.companion.CompanionStateMachine
import com.livingai.app.context.ContextEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The vertical slice: real UserContext + active Goal + FocusSession -> DistractionDetector ->
 * AttentionManager -> CompanionStateMachine reaction. This is the only class that knows all of
 * those pieces exist together; context/, focus/, and companion/ stay decoupled from each other.
 */
class FocusEngine(
    private val scope: CoroutineScope,
    private val contextEngine: ContextEngine,
    private val goalRepository: GoalRepository,
    val sessionManager: FocusSessionManager,
    private val distractionDetector: DistractionDetector,
    private val attentionManager: AttentionManager,
    private val companionStateMachine: CompanionStateMachine
) {
    private var latestGoalTitle: String? = null
    private var previousSessionStatus: FocusSessionStatus? = null

    fun start() {
        scope.launch {
            goalRepository.activeGoal.collect { goal -> latestGoalTitle = goal?.title }
        }

        scope.launch {
            sessionManager.session.collect { session ->
                val wasEnded = previousSessionStatus == FocusSessionStatus.ENDED
                val justEnded = session?.status == FocusSessionStatus.ENDED && !wasEnded
                val justStarted = session?.status == FocusSessionStatus.RUNNING &&
                    previousSessionStatus != FocusSessionStatus.RUNNING
                previousSessionStatus = session?.status

                // Always keep the companion's session snapshot fresh, then layer the transient
                // acknowledgement (DETERMINED / CELEBRATING) on top of it.
                companionStateMachine.onFocusSessionChanged(session)
                when {
                    justEnded -> companionStateMachine.onFocusCompleted(latestGoalTitle)
                    justStarted -> companionStateMachine.onFocusStarted()
                }
            }
        }

        scope.launch {
            combine(contextEngine.currentContext, sessionManager.session) { ctx, session -> ctx to session }
                .collect { (ctx, session) ->
                    val focusActive = session?.status == FocusSessionStatus.RUNNING
                    val candidate = distractionDetector.evaluate(ctx, focusActive)
                    val decision = attentionManager.evaluate(candidate, focusActive)
                    if (decision.shouldInterrupt) {
                        companionStateMachine.onInterventionDecision(decision, latestGoalTitle)
                    }
                }
        }
    }

    fun cooldownRemainingMs(): Long = attentionManager.cooldownRemainingMs()
}
