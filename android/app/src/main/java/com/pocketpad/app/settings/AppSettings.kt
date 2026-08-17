package com.pocketpad.app.settings

import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Every control the player can move, resize and hide.
 *
 * DPAD, LSTICK and RSTICK are independent: 3D games need the left stick to
 * move, the right stick to look, and the d-pad for the hotbar or weapon wheel,
 * all at once.
 */
enum class PadElement { DPAD, LSTICK, RSTICK, FACE, LB, RB, LT, RT, L3, R3, BACK, START }

/**
 * One control's placement: a dp offset from its default anchor, a size
 * multiplier, and whether it is on screen at all.
 */
data class ElementLayout(
    val x: Float = 0f,
    val y: Float = 0f,
    val scale: Float = 1f,
    val visible: Boolean = true,
)

/**
 * Where every control sits, how large it is and whether it is shown. Stored as
 * one JSON blob so adding a control later needs no preferences migration.
 */
data class PadLayout(
    val elements: Map<PadElement, ElementLayout> = emptyMap(),
) {
    fun of(e: PadElement): ElementLayout = elements[e] ?: defaultFor(e)

    fun with(e: PadElement, block: (ElementLayout) -> ElementLayout): PadLayout =
        copy(elements = elements + (e to block(of(e))))

    fun toJson(): String {
        val els = JSONObject()
        for ((e, l) in elements) {
            els.put(
                e.name,
                JSONObject().put("x", l.x).put("y", l.y).put("s", l.scale).put("on", l.visible),
            )
        }
        return JSONObject().put("v", VERSION).put("els", els).toString()
    }

    companion object {
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 1.6f
        private const val VERSION = 3

        /**
         * Starting size per control. A full 12-control pad has to fit a phone in
         * landscape, so the cross and the face cluster start smaller than they
         * do as the only control on their side.
         */
        private val DEFAULT_SCALE = mapOf(
            PadElement.DPAD to 0.62f,
            PadElement.FACE to 0.82f,
        )

        fun defaultFor(e: PadElement) = ElementLayout(scale = DEFAULT_SCALE[e] ?: 1f)

        fun fromJson(s: String?): PadLayout {
            if (s.isNullOrBlank()) return PadLayout()
            return runCatching {
                val o = JSONObject(s)
                when {
                    o.optInt("v", 1) >= 3 -> parseV3(o)
                    o.optInt("v", 1) == 2 -> parseV2(o)
                    else -> parseV1(o)
                }
            }.getOrDefault(PadLayout())
        }

        private fun readElements(o: JSONObject, withVisibility: Boolean): MutableMap<PadElement, ElementLayout> {
            val els = o.optJSONObject("els") ?: JSONObject()
            val map = mutableMapOf<PadElement, ElementLayout>()
            val keys = els.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val e = runCatching { PadElement.valueOf(k) }.getOrNull() ?: continue
                val j = els.optJSONObject(k) ?: continue
                map[e] = ElementLayout(
                    x = j.optDouble("x", 0.0).toFloat(),
                    y = j.optDouble("y", 0.0).toFloat(),
                    scale = j.optDouble("s", 1.0).toFloat().coerceIn(MIN_SCALE, MAX_SCALE),
                    visible = if (withVisibility) j.optBoolean("on", true) else true,
                )
            }
            return map
        }

        private fun parseV3(o: JSONObject) = PadLayout(readElements(o, withVisibility = true))

        /**
         * v2 had one left control that was either the cross or a stick, chosen
         * by a "stick" flag. Carry that choice over as visibility, and let the
         * stick inherit the position and size the player gave the cross.
         */
        private fun parseV2(o: JSONObject): PadLayout {
            // v2's TOGGLE entry no longer names a control; readElements drops
            // unknown names, so it disappears on its own.
            val map = readElements(o, withVisibility = false)
            val stick = o.optBoolean("stick", false)
            val left = map[PadElement.DPAD] ?: defaultFor(PadElement.DPAD)
            map[PadElement.DPAD] = left.copy(visible = !stick)
            map[PadElement.LSTICK] = ElementLayout(
                x = left.x, y = left.y, scale = 1f, visible = stick,
            )
            return PadLayout(map)
        }

        /** v1 could only move the d-pad and the face cluster; keep those. */
        private fun parseV1(o: JSONObject): PadLayout = PadLayout(
            mapOf(
                PadElement.DPAD to ElementLayout(
                    x = o.optDouble("dx", 0.0).toFloat(),
                    y = o.optDouble("dy", 0.0).toFloat(),
                    scale = o.optDouble("ds", 1.0).toFloat().coerceIn(MIN_SCALE, MAX_SCALE),
                ),
                PadElement.FACE to ElementLayout(
                    x = o.optDouble("fx", 0.0).toFloat(),
                    y = o.optDouble("fy", 0.0).toFloat(),
                    scale = o.optDouble("fs", 1.0).toFloat().coerceIn(MIN_SCALE, MAX_SCALE),
                ),
            )
        )
    }
}

/** Everything the Settings screen controls. */
data class AppSettings(
    val layout: PadLayout = PadLayout(),
    val haptics: Boolean = true,
    val hapticPercent: Int = 75, // 0–100% vibration power
    val mouseSensitivity: Float = 1.5f, // 0.5 slow … 3.0 fast
    val lookSensitivity: Float = 1.0f, // right-stick response curve, 0.5 … 2.0
)

class SettingsStore(private val prefs: SharedPreferences) {

    fun load(): AppSettings = AppSettings(
        layout = PadLayout.fromJson(prefs.getString("layout", null)),
        haptics = prefs.getBoolean("haptics", true),
        hapticPercent = when {
            prefs.contains("hapticPct") -> prefs.getInt("hapticPct", 75)
            // migrate from the old 3-level setting
            prefs.contains("hapticStr") -> when (prefs.getInt("hapticStr", 1)) {
                0 -> 37; 2 -> 100; else -> 75
            }
            else -> 75
        }.coerceIn(0, 100),
        mouseSensitivity = prefs.getFloat("mouseSens", 1.5f).coerceIn(0.5f, 3f),
        lookSensitivity = prefs.getFloat("lookSens", 1f).coerceIn(0.5f, 2f),
    )

    fun save(s: AppSettings) {
        prefs.edit()
            .putString("layout", s.layout.toJson())
            .putBoolean("haptics", s.haptics)
            .putInt("hapticPct", s.hapticPercent)
            .putFloat("mouseSens", s.mouseSensitivity)
            .putFloat("lookSens", s.lookSensitivity)
            .apply()
    }
}
