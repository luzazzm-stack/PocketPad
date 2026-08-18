package com.pocketpad.app.settings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layout blob is the only thing standing between an upgrade and a player's
 * saved pad. Two releases have already shipped a migration that silently threw
 * one away, so the rules are pinned here.
 */
class PadLayoutTest {

    @Test
    fun `round trips through json`() {
        val original = PadLayout()
            .with(PadElement.RSTICK) { it.copy(x = 12f, y = -30f, scale = 1.2f, visible = false) }
            .with(PadElement.DPAD) { it.copy(x = -5f, scale = 0.7f) }

        val restored = PadLayout.fromJson(original.toJson())

        assertEquals(original.of(PadElement.RSTICK), restored.of(PadElement.RSTICK))
        assertEquals(original.of(PadElement.DPAD), restored.of(PadElement.DPAD))
    }

    @Test
    fun `stick clicks ship hidden and everything else shows`() {
        val fresh = PadLayout()
        assertFalse("L3 must default hidden", fresh.of(PadElement.L3).visible)
        assertFalse("R3 must default hidden", fresh.of(PadElement.R3).visible)
        // The move and look sticks disappearing is the exact regression that
        // shipped in 0.5; assert them by name rather than by loop.
        assertTrue("left stick must default visible", fresh.of(PadElement.LSTICK).visible)
        assertTrue("right stick must default visible", fresh.of(PadElement.RSTICK).visible)
        assertTrue("d-pad must default visible", fresh.of(PadElement.DPAD).visible)
        assertTrue("face buttons must default visible", fresh.of(PadElement.FACE).visible)
    }

    @Test
    fun `v3 layouts survive because the schema did not change`() {
        // v3 shipped in 0.5 with the same element map; only the anchors moved.
        val v3 = JSONObject()
            .put("v", 3)
            .put(
                "els",
                JSONObject().put(
                    PadElement.RSTICK.name,
                    JSONObject().put("x", 0).put("y", 0).put("s", 1.1).put("on", false),
                ),
            )
            .toString()

        val loaded = PadLayout.fromJson(v3)

        assertFalse(
            "a v3 player who hid the right stick must keep it hidden",
            loaded.of(PadElement.RSTICK).visible,
        )
        assertEquals(1.1f, loaded.of(PadElement.RSTICK).scale, 0.001f)
    }

    @Test
    fun `v1 and v2 reset because their pad had no right stick`() {
        val v1 = JSONObject().put("dx", 40.0).put("ds", 1.4).toString()
        val v2 = JSONObject()
            .put("v", 2)
            .put("stick", false)
            .put(
                "els",
                JSONObject().put(
                    PadElement.DPAD.name,
                    JSONObject().put("x", 40).put("y", 0).put("s", 1.4),
                ),
            )
            .toString()

        for (blob in listOf(v1, v2)) {
            val loaded = PadLayout.fromJson(blob)
            assertEquals(PadLayout(), loaded)
            // The v2 either/or flag must not leave the move stick switched off.
            assertTrue(loaded.of(PadElement.LSTICK).visible)
        }
    }

    @Test
    fun `a blob from a newer build is discarded rather than read lossily`() {
        val future = JSONObject()
            .put("v", 99)
            .put(
                "els",
                JSONObject().put(
                    PadElement.LSTICK.name,
                    JSONObject().put("x", 7).put("y", 7).put("s", 1.3).put("on", true),
                ),
            )
            .toString()

        assertEquals(PadLayout(), PadLayout.fromJson(future))
    }

    @Test
    fun `garbage falls back to defaults instead of throwing`() {
        assertEquals(PadLayout(), PadLayout.fromJson("not json at all"))
        assertEquals(PadLayout(), PadLayout.fromJson(""))
        assertEquals(PadLayout(), PadLayout.fromJson(null))
    }

    @Test
    fun `scale is clamped on load`() {
        val wild = JSONObject()
            .put("v", 4)
            .put(
                "els",
                JSONObject()
                    .put(PadElement.DPAD.name, JSONObject().put("s", 99.0))
                    .put(PadElement.FACE.name, JSONObject().put("s", -5.0)),
            )
            .toString()

        val loaded = PadLayout.fromJson(wild)
        assertEquals(PadLayout.MAX_SCALE, loaded.of(PadElement.DPAD).scale, 0.001f)
        assertEquals(PadLayout.MIN_SCALE, loaded.of(PadElement.FACE).scale, 0.001f)
    }
}
