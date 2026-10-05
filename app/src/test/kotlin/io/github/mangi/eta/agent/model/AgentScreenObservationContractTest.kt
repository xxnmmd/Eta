package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.device.DisplayTargetPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentScreenObservationContractTest {
    @Test
    fun emptyArgumentsPreferUiTreeWithoutScreenshot() {
        val options = AgentScreenObservationContract.resolve(JSONObject())

        assertFalse(options.includeScreenshot)
        assertTrue(options.includeUiTree)
        assertEquals(60, options.maxNodes)
        assertEquals(0, options.displayId)
    }

    @Test
    fun explicitScreenshotKeepsUiTreeEnabledByDefault() {
        val options = AgentScreenObservationContract.resolve(
            JSONObject().put("include_screenshot", true),
        )

        assertTrue(options.includeScreenshot)
        assertTrue(options.includeUiTree)
        assertEquals(60, options.maxNodes)
    }

    @Test
    fun explicitArgumentsOverrideEveryDefault() {
        val options = AgentScreenObservationContract.resolve(
            JSONObject()
                .put("include_screenshot", true)
                .put("include_ui_tree", false)
                .put("max_nodes", 120)
                .put("display_id", 25),
        )

        assertTrue(options.includeScreenshot)
        assertFalse(options.includeUiTree)
        assertEquals(120, options.maxNodes)
        assertEquals(25, options.displayId)
    }

    @Test
    fun negativeDisplayFallsBackToTheDefaultDisplay() {
        val options = AgentScreenObservationContract.resolve(
            JSONObject().put("display_id", -3),
        )

        assertEquals(-3, options.displayId)
        assertEquals(0, DisplayTargetPolicy.normalize(options.displayId))
    }
}
