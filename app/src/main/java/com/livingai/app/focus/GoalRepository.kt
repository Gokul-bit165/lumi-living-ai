package com.livingai.app.focus

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.goalDataStore by preferencesDataStore(name = "living_ai_goals")

/**
 * Memory boundary for V1's one piece of durable state that must survive app restart: the
 * active goal. Backed by DataStore rather than Room — a single record doesn't need a database,
 * and this keeps the hackathon build lean. Swap for Room here if V1 grows multiple goals.
 */
interface GoalRepository {
    val activeGoal: Flow<Goal?>
    suspend fun setActiveGoal(title: String, deadline: Long?, priority: GoalPriority): Goal
    suspend fun updateProgress(progress: Float)
    suspend fun clearActiveGoal()
}

class DataStoreGoalRepository(private val context: Context) : GoalRepository {

    private object Keys {
        val ID = stringPreferencesKey("goal_id")
        val TITLE = stringPreferencesKey("goal_title")
        val DEADLINE = longPreferencesKey("goal_deadline")
        val PRIORITY = stringPreferencesKey("goal_priority")
        val PROGRESS = floatPreferencesKey("goal_progress")
    }

    override val activeGoal: Flow<Goal?> = context.goalDataStore.data.map { prefs ->
        mapToGoal(
            id = prefs[Keys.ID],
            title = prefs[Keys.TITLE],
            deadline = prefs[Keys.DEADLINE],
            priorityName = prefs[Keys.PRIORITY],
            progress = prefs[Keys.PROGRESS]
        )
    }

    override suspend fun setActiveGoal(title: String, deadline: Long?, priority: GoalPriority): Goal {
        val goal = Goal(id = UUID.randomUUID().toString(), title = title, deadline = deadline, priority = priority)
        context.goalDataStore.edit { prefs ->
            prefs[Keys.ID] = goal.id
            prefs[Keys.TITLE] = goal.title
            prefs[Keys.DEADLINE] = goal.deadline ?: -1L
            prefs[Keys.PRIORITY] = goal.priority.name
            prefs[Keys.PROGRESS] = 0f
        }
        return goal
    }

    override suspend fun updateProgress(progress: Float) {
        context.goalDataStore.edit { prefs -> prefs[Keys.PROGRESS] = progress }
    }

    override suspend fun clearActiveGoal() {
        context.goalDataStore.edit { it.clear() }
    }

    companion object {
        /** Pure mapping logic, extracted so it's unit-testable without a real DataStore/Context. */
        fun mapToGoal(id: String?, title: String?, deadline: Long?, priorityName: String?, progress: Float?): Goal? {
            if (id == null || title == null) return null
            return Goal(
                id = id,
                title = title,
                deadline = deadline?.takeIf { it > 0 },
                priority = priorityName?.let { runCatching { GoalPriority.valueOf(it) }.getOrNull() } ?: GoalPriority.MEDIUM,
                progress = progress ?: 0f
            )
        }
    }
}
