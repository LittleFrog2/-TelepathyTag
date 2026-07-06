package com.example.uwbtag // 注意：修改为你的实际包名

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.random.Random

class UwbSimulator {
    // 模拟防丢器数据流（10Hz 更新率，匹配 AR 丝滑流畅度）
    fun startSimulation(): Flow<UwbData> = flow {
        var currentDistance = 5.0f // 初始距离 5 米
        var currentAzimuth = 0.0f  // 初始角度 0 度

        while (true) {
            // 模拟人在走动，距离和角度发生随机微小偏移
            currentDistance += Random.nextFloat() * 0.4f - 0.2f
            currentDistance = currentDistance.coerceIn(0.1f, 10.0f) // 限制在 0.1-10 米内

            currentAzimuth += Random.nextFloat() * 30.0f - 15.0f
            // 限制在 -180° 到 180° 之间
            if (currentAzimuth > 180f) currentAzimuth -= 360f
            if (currentAzimuth < -180f) currentAzimuth += 360f

            emit(UwbData(currentDistance, currentAzimuth))
            delay(100) // 100ms 对应 10Hz 更新频率
        }
    }
}