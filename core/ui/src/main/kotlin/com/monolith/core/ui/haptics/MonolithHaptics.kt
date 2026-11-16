package com.monolith.core.ui.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import com.monolith.core.common.AppSettingsStore

object MonolithHaptics {

    /**
     * Crisp, non-buzzy mechanical click for chips, folders, back navigation, and buttons.
     */
    fun navClick(context: Context, view: View) {
        val settings = AppSettingsStore.settings.value
        if (!settings.hapticsEnabled || !settings.hapticNavClick) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val clicked = vibratePredefined(context, VibrationEffect.EFFECT_CLICK)
            if (clicked) return
        }
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    /**
     * Subtle detent tick for reordering and precision steps.
     */
    fun tick(context: Context, view: View) {
        val settings = AppSettingsStore.settings.value
        if (!settings.hapticsEnabled || !settings.hapticNavClick) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val ticked = vibratePredefined(context, VibrationEffect.EFFECT_TICK)
            if (ticked) return
        }
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    fun dragLift(view: View) {
        val settings = AppSettingsStore.settings.value
        if (!settings.hapticsEnabled || !settings.hapticDragLift) return
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /**
     * Distinct ascending ramp + crisp kick when launching a media file or viewer.
     */
    fun launchItem(context: Context) {
        val settings = AppSettingsStore.settings.value
        if (!settings.hapticsEnabled || !settings.hapticLaunchItem) return
        vibrateWaveform(
            context = context,
            timings = longArrayOf(0L, 14L, 28L, 22L, 32L, 30L),
            amplitudes = intArrayOf(0, 90, 0, 165, 0, 255),
        )
    }

    fun successPulse(context: Context) {
        val settings = AppSettingsStore.settings.value
        if (!settings.hapticsEnabled || !settings.hapticPulseFeedback) return
        vibrateWaveform(
            context = context,
            timings = longArrayOf(0L, 18L, 45L, 28L),
            amplitudes = intArrayOf(0, 140, 0, 220),
        )
    }

    private fun vibratePredefined(context: Context, effectId: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching {
            val vibrator = resolveVibrator(context) ?: return@runCatching false
            if (!vibrator.hasVibrator()) return@runCatching false
            vibrator.vibrate(VibrationEffect.createPredefined(effectId))
            true
        }.getOrDefault(false)
    }

    private fun vibrateWaveform(
        context: Context,
        timings: LongArray,
        amplitudes: IntArray,
    ): Boolean {
        return runCatching {
            val vibrator = resolveVibrator(context) ?: return@runCatching false
            if (!vibrator.hasVibrator()) return@runCatching false
            val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
            vibrator.vibrate(effect)
            true
        }.getOrDefault(false)
    }

    private fun resolveVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
