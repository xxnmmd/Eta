package io.github.mangi.eta.agent.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayTargetPolicyTest {
    @Test
    fun missingOrNegativeDisplayFallsBackToThePrimaryDisplay() {
        assertEquals(0, DisplayTargetPolicy.normalize(null))
        assertEquals(0, DisplayTargetPolicy.normalize(-1))
        assertEquals(0, DisplayTargetPolicy.normalize(0))
        assertEquals(25, DisplayTargetPolicy.normalize(25))
    }

    @Test
    fun onlyThePrimaryDisplayUsesAccessibilityGestures() {
        assertFalse(DisplayTargetPolicy.usesRootInput(0))
        assertTrue(DisplayTargetPolicy.usesRootInput(25))
    }

    @Test
    fun coordinatesAreValidatedAgainstTheTargetDisplay() {
        assertTrue(DisplayTargetPolicy.pointWithin(0, 0, 720, 1280))
        assertTrue(DisplayTargetPolicy.pointWithin(719, 1279, 720, 1280))
        assertFalse(DisplayTargetPolicy.pointWithin(720, 0, 720, 1280))
        assertFalse(DisplayTargetPolicy.pointWithin(0, 1280, 720, 1280))
        assertFalse(DisplayTargetPolicy.pointWithin(-1, 10, 720, 1280))
        assertFalse(DisplayTargetPolicy.pointWithin(10, 10, 0, 0))
    }
}
