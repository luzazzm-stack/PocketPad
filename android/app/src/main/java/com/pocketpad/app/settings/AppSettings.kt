package com.pocketpad.app.settings

import android.content.SharedPreferences
import org.json.JSONObject
import com.pocketpad.app.ui.defaultLayout

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
        private const val VERSION = 4

        /**
         * Starting size per control, chosen so a full pad fits the shortest
         * landscape phone this supports (360 dp tall). See PAD_SPECS for the
         * column arithmetic these numbers satisfy — raising any of them
         * overlaps the column it belongs to on a small screen.
         */
        private val DEFAULT_SCALE = mapOf(
            PadElement.DPAD to 0.58f,
            PadElement.FACE to 0.75f,
            PadElement.LSTICK to 0.88f,
            PadElement.RSTICK to 0.88f,
        )

        /**
         * Stick clicks are niche — most games never need them, and on a phone
         * screen every control costs room. They start hidden; the layout
         * editor's Show button brings either back.
         */
        private val DEFAULT_HIDDEN = setOf(PadElement.L3, PadElement.R3)

        fun defaultFor(e: PadElement) = ElementLayout(
            scale = DEFAULT_SCALE[e] ?: 1f,
            visible = e !in DEFAULT_HIDDEN,
        )

        /**
         * The oldest schema [parseCurrent] can read.
         *
         * v3 has the same JSON *shape* as v4, and that is exactly the trap: the
         * commit that introduced v4 also moved every anchor from
         * centre-relative to edge-relative, so a stored offset means something
         * different now. A v3 player who put the left stick 60 dp below centre
         * gets 60 dp measured down from a bottom edge instead — the same
         * "offsets drop controls into empty space" problem that v1/v2 are
         * discarded for. Sharing a schema is not the same as sharing a meaning.
         *
         * Keep this as its own constant: tying the decision to VERSION made
         * "silently wipe every saved layout" the automatic consequence of any
         * future version bump.
         */
        private const val OLDEST_READABLE = 4

        fun fromJson(s: String?): PadLayout {
            if (s.isNullOrBlank()) return PadLayout()
            return runCatching {
                val o = JSONObject(s)
                val v = o.optInt("v", 1)
                when {
                    v in OLDEST_READABLE..VERSION -> parseCurrent(o)
                    // Written by a newer build (sideload, then downgrade).
                    // Reading it would drop the keys this build does not know
                    // and rewrite the blob lossily on the next save.
                    v > VERSION -> PadLayout()
                    else -> parseLegacy()
                }
            }.getOrDefault(PadLayout())
        }

        private fun parseCurrent(o: JSONObject): PadLayout {
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
                    visible = j.optBoolean("on", true),
                )
            }
            return PadLayout(map)
        }

        /**
         * v1 and v2 described a pad with one left control and no right stick,
         * arranged completely differently. Their offsets would drop controls
         * into empty space, and v2's either/or "stick" flag left whichever the
         * player had NOT chosen switched off — which hid the left stick from
         * everyone who was on the cross.
         *
         * So those reset to defaults rather than being half-translated. Losing
         * a layout once beats a pad with controls missing or stacked, and the
         * player could not have positioned the new controls anyway.
         */
        private fun parseLegacy(): PadLayout = PadLayout()
    }
}

/** Everything the Settings screen controls. */
data class AppSettings(
    val layout: PadLayout = defaultLayout(),
    val haptics: Boolean = true,
    val hapticPercent: Int = 75, // 0–100% vibration power
    val mouseSensitivity: Float = 1.5f, // 0.5 slow … 3.0 fast
    val lookSensitivity: Float = 1.0f, // right-stick response curve, 0.5 … 2.0
)

class SettingsStore(private val prefs: SharedPreferences) {

    fun load(): AppSettings = AppSettings(
        // Nothing saved yet = fresh install: start on Layout 1.
        layout = prefs.getString("layout", null)?.let(PadLayout::fromJson) ?: defaultLayout(),
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
