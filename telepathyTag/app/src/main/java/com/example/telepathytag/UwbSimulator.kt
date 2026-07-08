package com.example.telepathytag

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.random.Random

class UwbSimulator {
    fun startSimulation(): Flow<UwbData> = flow {
        var currentDistance = 5.0f
        var currentAzimuth = 0.0f

        while (true) {
            currentDistance += Random.nextFloat() * 0.4f - 0.2f
            currentDistance = currentDistance.coerceIn(0.1f, 10.0f)

            currentAzimuth += Random.nextFloat() * 30.0f - 15.0f
            if (currentAzimuth > 180f) currentAzimuth -= 360f
            if (currentAzimuth < -180f) currentAzimuth += 360f

            emit(UwbData(currentDistance, currentAzimuth))
            delay(100)
        }
    }
}
