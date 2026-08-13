package com.example.telepathytag

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FeedbackManager(context: Context) {

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var findPhoneRingtone: Ringtone? = null

    private var isDistanceVibrating = false
    private val _isFindPhoneAlertActive = MutableStateFlow(false)
    val isFindPhoneAlertActive: StateFlow<Boolean> = _isFindPhoneAlertActive

    fun triggerDistanceFeedback(distanceMeters: Float) {
        if (_isFindPhoneAlertActive.value) {
            return
        }

        if (distanceMeters <= 0.0f) {
            stopDistanceFeedback()
            return
        }

        if (distanceMeters < 1.0f) {
            if (!isDistanceVibrating) {
                isDistanceVibrating = true
                val timings = longArrayOf(0, 40, 120)
                val amplitudes = intArrayOf(0, 180, 0)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, 0))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(timings, 0)
                }
            }
        } else {
            stopDistanceFeedback()
        }
    }

    fun stopDistanceFeedback() {
        if (isDistanceVibrating && !_isFindPhoneAlertActive.value) {
            vibrator.cancel()
        }
        isDistanceVibrating = false
    }

    fun toggleFindPhoneAlert(): Boolean {
        return if (_isFindPhoneAlertActive.value) {
            stopFindPhoneAlert()
            false
        } else {
            startFindPhoneAlert()
            true
        }
    }

    fun setFindPhoneAlertActive(active: Boolean) {
        if (active) {
            if (!_isFindPhoneAlertActive.value) {
                startFindPhoneAlert()
            }
        } else {
            stopFindPhoneAlert()
        }
    }

    private fun startFindPhoneAlert() {
        stopDistanceFeedback()
        _isFindPhoneAlertActive.value = true

        val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        findPhoneRingtone = ringtoneUri?.let { RingtoneManager.getRingtone(appContext, it) }

        findPhoneRingtone?.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            findPhoneRingtone?.isLooping = true
        }
        findPhoneRingtone?.play()

        val timings = longArrayOf(0, 250, 120, 250, 500)
        val amplitudes = intArrayOf(0, 255, 0, 220, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(timings, 0)
        }

        handler.postDelayed({ stopFindPhoneAlert() }, 120_000)
    }

    fun stopFindPhoneAlert() {
        handler.removeCallbacksAndMessages(null)
        findPhoneRingtone?.stop()
        findPhoneRingtone = null
        isFindPhoneAlertActive = false
        _isFindPhoneAlertActive.value = false
        isDistanceVibrating = false
        vibrator.cancel()
    }

    fun stopAll() {
        stopDistanceFeedback()
        stopFindPhoneAlert()
    }
}
