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

    fun folderOpen(context: Context, view: View) {
        val settings = AppSettingsStore.settings.value
        if (!settings.hapticsEnabled || !settings.hapticFolderOpen) return
        triggerClick(context, view)
    }

    fun pathChipNav(context: Context, view: View) {
        val settings = AppSettingsStore.settings.value
        if (!settings.hapticsEnabled || !settings.hapticPathChipNav) return
        triggerClick(context, view)
    }

    fun monolithMenuOpen(context: Context, view: View) {
        val settings = AppSettingsStore.settings.value
        if (!settings.hapticsEnabled || !settings.hapticMonolithMenuOpen) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            triggerPredefined(context, view, VibrationEffect.EFFECT_DOUBLE_CLICK, HapticFeedbackConstants.CONTEXT_CLICK)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        }
    }

    fun cornerMenuOpen(context: Context, view: View) {
        val settings = AppSettingsStore.settings.value
        if (!settings.hapticsEnabled || !settings.hapticCornerMenuOpen) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            triggerPredefined(context, view, VibrationEffect.EFFECT_TICK, HapticFeedbackConstants.CLOCK_TICK)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    fun dragLift(view: View) {
        val settings = AppSettingsStore.settings.value
        if (!settings.hapticsEnabled || !settings.hapticDragLift) return
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    fun launchItem(context: Context) {
        val settings = AppSettingsStore.settings.value
        if (!settings.hapticsEnabled || !settings.hapticLaunchItem) return
        vibrateWaveform(
            context = context,
            timings = longArrayOf(0L, 14L, 28L, 22L, 32L, 30L),
            amplitudes = intArrayOf(0, 90, 0, 165, 0, 255),
        )
    }

    fun refreshDetent(context: Context, view: View) {
        val settings = AppSettingsStore.settings.value
        if (!settings.hapticsEnabled || !settings.hapticPullRefresh) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            triggerPredefined(context, view, VibrationEffect.EFFECT_HEAVY_CLICK, HapticFeedbackConstants.CLOCK_TICK)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    fun refreshComplete(context: Context, view: View) {
        val settings = AppSettingsStore.settings.value
        if (!settings.hapticsEnabled || !settings.hapticPullRefresh) return
        val pulsed = vibrateWaveform(
            context = context,
            timings = longArrayOf(0L, 12L, 35L, 16L),
            amplitudes = intArrayOf(0, 110, 0, 200),
        )
        if (!pulsed) {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        }
    }

    fun switchToggle(context: Context, view: View, checked: Boolean = true) {
        val settings = AppSettingsStore.settings.value
        if (!settings.hapticsEnabled || !settings.hapticSwitchToggle) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val effect = if (checked) VibrationEffect.EFFECT_HEAVY_CLICK else VibrationEffect.EFFECT_TICK
            val fallback = if (checked) HapticFeedbackConstants.CONTEXT_CLICK else HapticFeedbackConstants.CLOCK_TICK
            triggerPredefined(context, view, effect, fallback)
        } else {
            view.performHapticFeedback(
                if (checked) HapticFeedbackConstants.CONTEXT_CLICK else HapticFeedbackConstants.CLOCK_TICK,
            )
        }
    }

    private fun triggerClick(context: Context, view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            triggerPredefined(context, view, VibrationEffect.EFFECT_CLICK, HapticFeedbackConstants.CONTEXT_CLICK)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        }
    }

    private fun triggerPredefined(
        context: Context,
        view: View,
        effectId: Int,
        fallbackConstant: Int,
    ) {
        if (!vibratePredefined(context, effectId)) {
            view.performHapticFeedback(fallbackConstant)
        }
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
