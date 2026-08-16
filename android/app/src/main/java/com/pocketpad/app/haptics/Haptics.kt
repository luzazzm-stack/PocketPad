package com.pocketpad.app.haptics

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Direct vibrator access for button ticks.
 *
 * Compose's LocalHapticFeedback routes through View.performHapticFeedback,
 * which many OEMs (realme/ColorOS included) silently suppress when the system
 * "touch feedback" setting is off. Driving the Vibrator service directly works
 * regardless of that setting — the VIBRATE permission is all it needs.
 */
class Haptics(context: Context) {

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private val clickEffect: VibrationEffect? = when {
        vibrator == null || !vibrator.hasVibrator() -> null
        Build.VERSION.SDK_INT >= 29 ->
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
        else ->
            VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE)
    }

    /** One short tick — a button registered. */
    fun tick() {
        val fx = clickEffect ?: return
        val v = vibrator ?: return
        when {
            // Plain vibrate() gets USAGE_TOUCH attributes, and OEMs scale touch
            // vibration by the system "touch feedback" intensity — observed at
            // 0.00 on realme/ColorOS, i.e. silenced. Tag as game/media instead,
            // like every mobile game with rumble does.
            Build.VERSION.SDK_INT >= 33 -> v.vibrate(
                fx,
                VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_MEDIA)
                    .build(),
            )
            else -> @Suppress("DEPRECATION") v.vibrate(
                fx,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .build(),
            )
        }
    }
}

/** Provided once in MainActivity; screens call LocalHaptics.current.tick(). */
val LocalHaptics = staticCompositionLocalOf<Haptics> {
    error("Haptics not provided")
}
