package com.example.uwbtag

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class FeedbackManager(context: Context) {

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private var isVibrating = false

    // 🎯 动作 4：当距离 < 1米 时触发的高级触觉反馈
    fun triggerDistanceFeedback(distanceMeters: Float) {
        if (distanceMeters <= 0.0f) {
            stop()
            return
        }

        if (distanceMeters < 1.0f) {
            if (!isVibrating) {
                isVibrating = true
                // 模拟 AirTag 连续清脆“哒哒哒”敲击：震动 40ms，停 120ms
                val timings = longArrayOf(0, 40, 120)
                val amplitudes = intArrayOf(0, 180, 0) // 180 为中等偏强的舒适振幅

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, 0)) // 0 代表循环
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(timings, 0)
                }
            }
        } else {
            // 远离 1 米后自动安静
            stop()
        }
    }

    fun stop() {
        if (isVibrating) {
            vibrator.cancel()
            isVibrating = false
        }
    }
}