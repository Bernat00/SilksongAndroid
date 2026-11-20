package dev.silksong.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layout file, round-tripped.
 *
 * Three separate copies of this format exist -- this one, the game's
 * PadLayoutFile and the values the editor draws -- and the only one that can
 * be tested without a phone is this one. So: what the editor writes has to be
 * what the game reads, and a file that is not a layout has to be refused
 * rather than half-applied.
 */
class PadLayoutTest {

    private val w = 2340
    private val h = 1080

    @Test
    fun defaultsAreAWholePad() {
        val layout = PadLayout.defaults(w, h)
        assertEquals(PadLayout.SLOTS, layout.keys.size)
        for (k in layout.keys) {
            assertTrue("${k.label} x", k.x in 0f..1f)
            assertTrue("${k.label} y", k.y in 0f..1f)
            assertTrue("${k.label} w", k.w > 0f && k.w < 1f)
            assertTrue("${k.label} shape", k.shape == "circle" || k.shape == "pill")
        }
        assertEquals("floating", layout.stick.mode)
    }

    @Test
    fun theDefaultsKeepMapOutOfTheStickZone() {
        // The bug this fixes: Map sat at 0.40 of the height, the zone covers
        // everything below 0.30, and the pad gave the zone every touch in it.
        val layout = PadLayout.defaults(w, h)
        val map = layout.keys.first { it.id == "map" }
        assertTrue("map y=${map.y}", map.y < layout.stick.y0)
    }

    @Test
    fun everythingSurvivesTheRoundTrip() {
        val layout = PadLayout.defaults(w, h)
        layout.keys[2].scale = 1.6f
        layout.keys[2].target = "Action4"
        layout.stick.mode = "pinned"
        val again = PadLayout.parse(layout.toJson(), w, h)
        assertNotNull(again)
        assertEquals(layout.keys.size, again!!.keys.size)
        assertEquals(1.6f, again.keys[2].scale, 0.001f)
        assertEquals("Action4", again.keys[2].target)
        assertEquals("pinned", again.stick.mode)
        for (i in layout.keys.indices) {
            assertEquals(layout.keys[i].x, again.keys[i].x, 0.001f)
            assertEquals(layout.keys[i].y, again.keys[i].y, 0.001f)
            assertEquals(layout.keys[i].label, again.keys[i].label)
        }
    }

    @Test
    fun aFileThatIsNotALayoutIsRefused() {
        val good = PadLayout.defaults(w, h).toJson()
        assertNull(PadLayout.parse("{ nope }", w, h))
        assertNull(PadLayout.parse(good.replace("\"schema\": 1", "\"schema\": 2"), w, h))
        // One key short: not something half-applied.
        val short = PadLayout.parse(good, w, h)!!.let { parsed ->
            parsed.keys.removeAt(0)
            parsed.toJson()
        }
        assertNull(PadLayout.parse(short, w, h))
    }

    @Test
    fun theBuiltInLayoutIsSaveable() {
        // The report that started this: Save refused the default layout,
        // because the face diamond overlaps on purpose (0.155 vmin apart, 0.080
        // radius) and the check compared bounding squares.
        val defaults = PadLayout.defaults(w, h)
        assertNull("defaults clash: ${defaults.firstOverlap(w, h)}", defaults.firstOverlap(w, h))
    }

    @Test
    fun twoKeysInTheSamePlaceAreStillRefused() {
        val layout = PadLayout.defaults(w, h)
        layout.keys[1].x = layout.keys[0].x
        layout.keys[1].y = layout.keys[0].y
        assertNotNull(layout.firstOverlap(w, h))
    }

    @Test
    fun outOfRangeValuesAreClampedNotTrusted() {
        val layout = PadLayout.defaults(w, h)
        layout.keys[0].x = 4f
        layout.keys[0].scale = 9f
        layout.stick.r = 0.9f
        layout.stick.x0 = 0.9f
        layout.stick.x1 = 0.1f
        val again = PadLayout.parse(layout.toJson(), w, h)!!
        assertTrue(again.keys[0].x <= 1.05f)
        assertTrue(again.keys[0].scale <= 2f)
        assertTrue(again.stick.r <= 0.30f)
        assertTrue(again.stick.x0 < again.stick.x1)
    }
}
