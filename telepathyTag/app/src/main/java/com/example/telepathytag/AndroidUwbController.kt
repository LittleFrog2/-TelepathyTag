package com.example.telepathytag

import android.content.Context
import androidx.core.uwb.RangingParameters
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbControllerSessionScope
import androidx.core.uwb.UwbDevice
import androidx.core.uwb.UwbManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class AndroidUwbController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val onPosition: (UwbRealData) -> Unit,
    private val onError: (String) -> Unit
) {
    private var uwbManager: UwbManager? = null
    private var controllerSessionScope: UwbControllerSessionScope? = null
    private var rangingJob: Job? = null

    suspend fun prepareOobSession(): UwbOobSession {
        log("🧪 创建 UwbManager...")
        val manager = UwbManager.createInstance(context)
        log("🧪 请求 Android UWB controllerSessionScope...")
        val sessionScope = manager.controllerSessionScope()
        uwbManager = manager
        controllerSessionScope = sessionScope

        log("🧪 读取 Android UWB localAddress/complexChannel...")
        val complexChannel = sessionScope.uwbComplexChannel
        val oobSession = UwbOobSession.createFromAndroidController(
            context = context,
            localAddress = sessionScope.localAddress,
            complexChannel = complexChannel
        )

        log(
            "✅ Android UWB Controller ready: local=${sessionScope.localAddress}, " +
                "channel=${complexChannel.channel}, preamble=${complexChannel.preambleIndex}"
        )
        return oobSession
    }

    fun startRanging(oobSession: UwbOobSession): Boolean {
        if (rangingJob?.isActive == true) return true

        val sessionScope = controllerSessionScope ?: run {
            onError("Android UWB Controller session 尚未准备好，无法启动 ranging")
            return false
        }

        val parameters = RangingParameters(
            uwbConfigType = RangingParameters.CONFIG_UNICAST_DS_TWR,
            sessionId = oobSession.sessionId.toInt(),
            subSessionId = 0,
            sessionKeyInfo = oobSession.staticStsKeyInfo(),
            subSessionKeyInfo = null,
            complexChannel = oobSession.toUwbComplexChannel(),
            peerDevices = listOf(UwbDevice(oobSession.tagUwbAddress())),
            updateRateType = RangingParameters.RANGING_UPDATE_RATE_AUTOMATIC,
            uwbRangeDataNtfConfig = null,
            slotDurationMillis = RangingParameters.RANGING_SLOT_DURATION_2_MILLIS,
            isAoaDisabled = false
        )

        rangingJob = scope.launch {
            try {
                log("🛰️ Android UWB ranging 已启动，等待手机侧结果...")
                sessionScope.prepareSession(parameters).collect { result ->
                    handleRangingResult(result)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                onError("Android UWB ranging 权限被拒绝: ${e.message}")
            } catch (e: Exception) {
                onError("Android UWB ranging 失败: ${e.message}")
            }
        }
        return true
    }

    fun stop() {
        rangingJob?.cancel()
        rangingJob = null
        controllerSessionScope = null
        uwbManager = null
    }

    private fun handleRangingResult(result: RangingResult) {
        when (result) {
            is RangingResult.RangingResultInitialized -> {
                log("🛰️ Android UWB session initialized: ${result.device.address}")
            }
            is RangingResult.RangingResultPosition -> {
                val distanceMeters = result.position.distance?.value ?: 0.0f
                val azimuthDegrees = result.position.azimuth?.value ?: 0.0f
                onPosition(UwbRealData(distanceMeters, azimuthDegrees))
                log(
                    "🎯【Android UWB】距离=${String.format("%.2f", distanceMeters)}m, " +
                        "方位=${String.format("%.1f", azimuthDegrees)}°"
                )
            }
            is RangingResult.RangingResultPeerDisconnected -> {
                onError("Android UWB peer disconnected: ${result.device.address}")
            }
            is RangingResult.RangingResultFailure -> {
                onError("Android UWB ranging failure: $result")
            }
        }
    }
}
