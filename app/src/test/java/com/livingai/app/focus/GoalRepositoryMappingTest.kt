package com.livingai.app.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoalRepositoryMappingTest {

    @Test
    fun `missing id or title maps to null (no goal set yet)`() {
        assertNull(DataStoreGoalRepository.mapToGoal(null, "Exam prep", null, null, null))
        assertNull(DataStoreGoalRepository.mapToGoal("id-1", null, null, null, null))
    }

    @Test
    fun `valid stored fields map back to the same goal`() {
        val goal = DataStoreGoalRepository.mapToGoal("id-1", "Prepare for tomorrow's exam", 123L, "HIGH", 0.5f)
        assertEquals("id-1", goal?.id)
        assertEquals("Prepare for tomorrow's exam", goal?.title)
        assertEquals(123L, goal?.deadline)
        assertEquals(GoalPriority.HIGH, goal?.priority)
        assertEquals(0.5f, goal?.progress)
    }

    @Test
    fun `deadline of zero or negative is treated as no deadline`() {
        val goal = DataStoreGoalRepository.mapToGoal("id-1", "Title", -1L, "MEDIUM", 0f)
        assertNull(goal?.deadline)
    }

    @Test
    fun `unrecognized priority falls back to MEDIUM instead of crashing`() {
        val goal = DataStoreGoalRepository.mapToGoal("id-1", "Title", null, "not_a_real_priority", 0f)
        assertEquals(GoalPriority.MEDIUM, goal?.priority)
    }
}
