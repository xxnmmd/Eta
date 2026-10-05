package io.github.mangi.eta.agent.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualDisplaySpecTest {
    @Test
    fun defaultsAndClampingKeepTheDisplayUsable() {
        assertEquals(
            VirtualDisplaySpec.Spec(720, 1280, 320),
            VirtualDisplaySpec.normalize(null, null, null),
        )
        assertEquals(
            VirtualDisplaySpec.Spec(VirtualDisplaySpec.MIN_SIZE, VirtualDisplaySpec.MAX_HEIGHT, VirtualDisplaySpec.MAX_DENSITY),
            VirtualDisplaySpec.normalize(1, 99_999, 9_999),
        )
    }

    @Test
    fun settingsValueMatchesThePlatformFormat() {
        assertEquals(
            "1080x2400/440",
            VirtualDisplaySpec.settingsValue(VirtualDisplaySpec.Spec(1080, 2400, 440)),
        )
    }

    @Test
    fun parseReadsExistingEntriesAndIgnoresUnknownKeywords() {
        assertEquals(
            listOf(VirtualDisplaySpec.Spec(720, 1280, 320)),
            VirtualDisplaySpec.parse("720x1280/320"),
        )
        assertEquals(
            listOf(VirtualDisplaySpec.Spec(720, 1280, 320), VirtualDisplaySpec.Spec(1080, 1920, 480)),
            VirtualDisplaySpec.parse("720x1280/320,1080x1920/480"),
        )
        assertTrue(VirtualDisplaySpec.parse("null").isEmpty())
        assertTrue(VirtualDisplaySpec.parse("auto").isEmpty())
        assertTrue(VirtualDisplaySpec.parse(null).isEmpty())
    }
}
