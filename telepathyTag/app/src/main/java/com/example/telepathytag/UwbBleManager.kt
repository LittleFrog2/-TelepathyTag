package com.example.telepathytag

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.*

enum class FindingStatus {
    IDLE,
    SENDING,
    BOARD_STARTING,
    BOARD_STARTED,
    RANGING,
    SUCCESS,
    FAILED,
    TIMEOUT,
    UWB_ERROR
}

data class UwbRealData(
    val distanceMeters: Float,
    val azimuthDegrees: Float
)

class UwbBleManager(private val context: Context) {

    private val SERVICE_UUID = UUID.fromString("2E938FD0-6A61-11ED-A1EB-0242AC120002")
    private val WRITE_CHARACTERISTIC_UUID = UUID.fromString("2E93998A-6A61-11ED-A1EB-0242AC120002")
    private val NOTIFY_CHARACTERISTIC_UUID = UUID.fromString("2E939AF2-6A61-11ED-A1EB-0242AC120002")
    private val CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bluetoothGatt: BluetoothGatt? = null

    private val _connectionState = MutableStateFlow("未连接")
    val connectionState: StateFlow<String> = _connectionState.asStateFlow()

    private val _connectedMac = MutableStateFlow("")
    val connectedMac: StateFlow<String> = _connectedMac.asStateFlow()

    private val _uwbDataFlow = MutableSharedFlow<UwbRealData>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val uwbDataFlow: SharedFlow<UwbRealData> = _uwbDataFlow.asSharedFlow()

    private val _findingStatus = MutableStateFlow(FindingStatus.IDLE)
    val findingStatus: StateFlow<FindingStatus> = _findingStatus.asStateFlow()

    private val _testLogFlow = MutableSharedFlow<String>(replay = 10, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val testLogFlow: SharedFlow<String> = _testLogFlow.asSharedFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var serviceDiscoveryStarted = false
    private var isGattReady = false
    private var qnisWriteInProgress = false
    private var currentMtu = 23
    private var pendingWriteLabel = ""
    private var didRunOneByteProbe = false
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val androidUwbController: AndroidUwbController by lazy {
        AndroidUwbController(
            context = context,
            scope = appScope,
            log = ::logToUi,
            onPosition = { uwbData ->
                mainHandler.removeCallbacks(timeoutRunnable)
                timeoutRetryCount = 0
                _uwbDataFlow.tryEmit(uwbData)
                _findingStatus.value = FindingStatus.SUCCESS
            },
            onError = { message ->
                _findingStatus.value = FindingStatus.UWB_ERROR
                logToUi("❌ $message")
            }
        )
    }

    private var timeoutRetryCount = 0
    private val maxTimeoutRetries = 3

    private val timeoutRunnable = Runnable {
        if (
            _findingStatus.value == FindingStatus.SENDING ||
            _findingStatus.value == FindingStatus.BOARD_STARTING ||
            _findingStatus.value == FindingStatus.RANGING
        ) {
            if (timeoutRetryCount < maxTimeoutRetries) {
                timeoutRetryCount++
                logToUi("⏱️ ACK 超时，自动重试 ($timeoutRetryCount/$maxTimeoutRetries)...")
                androidUwbController.stop()
                sendStartFindingCmd()
            } else {
                _findingStatus.value = FindingStatus.TIMEOUT
                androidUwbController.stop()
                timeoutRetryCount = 0
                val mode = if (pendingOobSession?.isAndroidUwbSessionBacked == true) {
                    "Android UWB ranging 结果"
                } else {
                    "板端 ACK/启动诊断"
                }
                logToUi("❌ 超时反馈：OOB 已写入，但 15 秒内没有收到 $mode（已重试 $maxTimeoutRetries 次）")
            }
        }
    }

    private var pendingOobSession: UwbOobSession? = null

    @SuppressLint("MissingPermission")
    fun sendStartFindingCmd(): Boolean {
        val gatt = bluetoothGatt ?: return false
        val service = gatt.getService(SERVICE_UUID) ?: return false
        val characteristic = service.getCharacteristic(WRITE_CHARACTERISTIC_UUID) ?: run {
            logToUi("❌ 错误反馈：未找到写入通道 (2e93998a...)")
            return false
        }
        if (!isGattReady) {
            logToUi("⚠️ QNIS RX 写入被拦截：GATT 尚未 ready，需等待服务发现和 Notify CCCD 写入完成")
            return false
        }
        if (qnisWriteInProgress) {
            logToUi("⚠️ QNIS RX 写入被拦截：上一笔 GATT 写入尚未回调，避免并发写入触发 133")
            return false
        }
        val writeType = selectQnisWriteType(characteristic) ?: run {
            logToUi("❌ QNIS RX 不支持 WRITE/WRITE_NO_RESPONSE: ${characteristic.debugProperties()}")
            return false
        }

        _findingStatus.value = FindingStatus.SENDING
        qnisWriteInProgress = true
        pendingWriteLabel = "OOB START_SESSION"
        androidUwbController.stop()

        appScope.launch {
            try {
                val oobSession = prepareAndroidUwbOobSessionOrFallback()
                pendingOobSession = oobSession
                val payload = oobSession.toStartPacket()
                logToUi("🧾 OOB字段: ${oobSession.summary()}, block=${oobSession.blockDurationMs}ms, rounds=${oobSession.roundDurationSlots}, sts=${oobSession.stsConfig}, keyLen=${oobSession.sessionKey.size}")
                logToUi("🧾 STS keyInfo(vUpper64): ${oobSession.staticStsKeyInfo().toHexString()}")
                logToUi("🧾 OOB原始包(${payload.size}): ${payload.toHexString()}")
                logQnisWriteAttempt(gatt, characteristic, writeType, payload)
                val success = writeStartSessionPacket(gatt, characteristic, payload, writeType)

                if (success) {
                    delay(500)
                    mainHandler.removeCallbacks(timeoutRunnable)
                    mainHandler.postDelayed(timeoutRunnable, 15000)
                    val mode = if (oobSession.isAndroidUwbSessionBacked) "Android ranging 模式" else "板端验证模式"
                    logToUi("📡 OOB START_SESSION 已写入，$mode，${oobSession.summary()}, 手机UWB=${oobSession.isUwbSupported}")
                } else {
                    qnisWriteInProgress = false
                    _findingStatus.value = FindingStatus.FAILED
                    logToUi("❌ 错误反馈：Android协议栈拒绝了本次写入")
                }
            } catch (e: SecurityException) {
                qnisWriteInProgress = false
                _findingStatus.value = FindingStatus.UWB_ERROR
                logToUi("❌ Android UWB 权限缺失：请授予 UWB_RANGING 后重试")
                Log.e("BLE_DEBUG", "Android UWB permission error", e)
            } catch (e: Exception) {
                qnisWriteInProgress = false
                _findingStatus.value = FindingStatus.UWB_ERROR
                logToUi("❌ Android UWB Controller session 创建失败: ${e.javaClass.name}: ${e.message}")
                Log.e("BLE_DEBUG", "Android UWB Controller session create failed", e)
            }
        }

        return true
    }

    fun resetFindingStatus() {
        mainHandler.removeCallbacks(timeoutRunnable)
        timeoutRetryCount = 0
        androidUwbController.stop()
        _findingStatus.value = FindingStatus.IDLE
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(macAddress: String) {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) return
        bluetoothGatt?.close()
        bluetoothGatt = null
        serviceDiscoveryStarted = false
        isGattReady = false
        qnisWriteInProgress = false
        pendingWriteLabel = ""
        didRunOneByteProbe = false
        currentMtu = 23
        _connectionState.value = "正在连接..."
        _connectedMac.value = macAddress
        val device = bluetoothAdapter.getRemoteDevice(macAddress)
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            logToUi("🔗 GATT 状态变化: status=$status, newState=${newState.toGattStateName()}")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isGattReady = false
                _connectionState.value = "已连接，开始发现服务..."
                discoverServicesOnce(gatt)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isGattReady = false
                qnisWriteInProgress = false
                pendingWriteLabel = ""
                _connectionState.value = "未连接"
                _connectedMac.value = ""
                resetFindingStatus()
                serviceDiscoveryStarted = false
                gatt.close()
                bluetoothGatt = null
                logToUi("🔌 链路反馈：蓝牙链路已断开")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                currentMtu = mtu
            }
            logToUi("📐 BLE MTU 协商完成: mtu=$mtu, status=$status")
            enableQnisNotify(gatt)
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            logToUi("🔎 服务发现完成: status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                dumpGattTable(gatt)
                val service = gatt.getService(SERVICE_UUID)
                val notifyCharacteristic = service?.getCharacteristic(NOTIFY_CHARACTERISTIC_UUID)
                val writeCharacteristic = service?.getCharacteristic(WRITE_CHARACTERISTIC_UUID)

                if (service == null) {
                    logToUi("❌ 未发现 QNIS Service: $SERVICE_UUID")
                    _connectionState.value = "未发现 QNIS Service"
                    return
                }

                if (writeCharacteristic != null) {
                    logToUi("🔎 QNIS RX: uuid=${writeCharacteristic.uuid}, ${writeCharacteristic.debugProperties()}, permissions=0x${writeCharacteristic.permissions.toString(16)}")
                } else {
                    logToUi("❌ 未发现 QNIS RX Characteristic: $WRITE_CHARACTERISTIC_UUID")
                }

                if (notifyCharacteristic != null) {
                    logToUi("🔎 QNIS TX: uuid=${notifyCharacteristic.uuid}, ${notifyCharacteristic.debugProperties()}, permissions=0x${notifyCharacteristic.permissions.toString(16)}")
                } else {
                    logToUi("❌ 未发现 QNIS TX Characteristic: $NOTIFY_CHARACTERISTIC_UUID")
                }

                if (writeCharacteristic == null || notifyCharacteristic == null) {
                    _connectionState.value = "QNIS RX/TX 缺失"
                    return
                }

                _connectionState.value = "已发现 QNIS，协商 MTU..."
                val mtuRequested = gatt.requestMtu(247)
                logToUi("📐 请求 BLE MTU=247，结果: $mtuRequested")
                if (!mtuRequested) {
                    enableQnisNotify(gatt)
                }
            } else {
                _connectionState.value = "服务发现失败"
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            if (descriptor?.characteristic?.uuid == NOTIFY_CHARACTERISTIC_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    isGattReady = true
                    _connectionState.value = "已连接"
                    logToUi("✅ [确认点 1] 监听激活反馈：Notify 通道 (2e939af2) 开启成功")
                    runOneByteQnisProbe(gatt)
                } else {
                    isGattReady = false
                    logToUi("❌ 错误反馈：Notify通道开启失败，码: $status")
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            if (characteristic?.uuid == WRITE_CHARACTERISTIC_UUID) {
                val label = pendingWriteLabel.ifEmpty { "UNKNOWN" }
                qnisWriteInProgress = false
                pendingWriteLabel = ""
                logToUi("🧾 QNIS RX 写入回调: label=$label, status=$status, uuid=${characteristic.uuid}, writeType=${characteristic.writeType.toWriteTypeName()}")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    if (label == "BLE_PROBE_1B") {
                        logToUi("✅ [确认点 2] 1 字节 QNIS RX 测试包写入成功，等待板端 TX echo...")
                    } else {
                        logToUi("✅ [确认点 2] OOB START_SESSION 成功写入 998A")
                        logToUi("⏳ 正在等待板端 OOB ACK...")
                    }
                } else {
                    mainHandler.post {
                        _findingStatus.value = FindingStatus.FAILED
                        mainHandler.removeCallbacks(timeoutRunnable)
                    }
                    logToUi("❌ 错误反馈：QNIS RX 物理写入拒绝，错误码: $status")
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == NOTIFY_CHARACTERISTIC_UUID) {
                handleNotifyPayload(characteristic.value ?: return)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == NOTIFY_CHARACTERISTIC_UUID) {
                handleNotifyPayload(value)
            }
        }
    }

    private fun handleNotifyPayload(bytes: ByteArray) {
        val hexString = bytes.joinToString(" ") { "%02X".format(it) }
        logToUi("📬 [通道动态] Notify 通道 (2e939af2) 捕获到报文: [$hexString]")

        if (UwbOobSession.isAck(bytes)) {
            val status = UwbOobSession.ackStatus(bytes)
            val ackSessionId = UwbOobSession.ackSessionId(bytes)
            val expected = pendingOobSession?.sessionId
            val matched = expected == null || expected == ackSessionId
            val statusText = when (status) {
                0 -> "OK"
                1 -> "BAD_LEN"
                2 -> "BAD_FIELD"
                else -> "UNKNOWN"
            }
            mainHandler.post {
                if (status == 0 && matched) {
                    if (pendingOobSession?.isAndroidUwbSessionBacked == true) {
                        _findingStatus.value = FindingStatus.BOARD_STARTING
                        logToUi("🧪 OOB ACK 正常，等待板端 FiRa responder 启动后再启动 Android UWB")
                    } else {
                        _findingStatus.value = FindingStatus.BOARD_STARTING
                        logToUi("🧪 板端验证模式：OOB ACK 正常，不启动 Android UWB ranging，继续等待 FiRa 启动诊断")
                    }
                } else {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    _findingStatus.value = FindingStatus.UWB_ERROR
                }
            }
            logToUi("✅ OOB ACK: status=$status($statusText), session=0x${ackSessionId.toString(16)}, expected=0x${expected?.toString(16)}, matched=$matched")
            return
        }

        if (bytes.size == 1 && bytes[0] == 0x01.toByte()) {
            logToUi("✅ 收到 Relay B 启动回声 [01]，继续等待 4 字节测距数据...")
            return
        }

        if (handleFiraParamDiag(bytes)) {
            return
        }

        if (handleFiraRuntimeDiag(bytes)) {
            return
        }

        if (bytes.size == 4) {
            val distanceCm = bytes.toUInt16Le(0)
            val azimuthDeg = bytes.toInt16Le(2)

            if (distanceCm == 0xFFFE) {
                val startStatus = bytes.toUInt16Le(2)
                val message = when (startStatus) {
                    0 -> "Relay B 的 FiRa session 已启动，继续等待 UWB report..."
                    1 -> "Relay B 初始化 FiRa session 失败"
                    2 -> "Relay B 执行 uwbmac_start 失败"
                    3 -> "Relay B 执行 fira_helper_start_session 失败"
                    4 -> "Relay B 已经处于 UWB started 状态，正在先停止再重启 session"
                    5 -> "Relay B 重启前停止旧 session 失败"
                    0x0100 -> "板端已取到 pending OOB，ACK 已发送"
                    0x0101 -> "ACK 后 500ms 延迟完成，准备 FiRa 初始化"
                    0x0110 -> "开始 fira_niq_app_process_init"
                    0x0111 -> "fira_niq_app_process_init 完成"
                    0x0120 -> "开始 uwbmac_start"
                    0x0121 -> "uwbmac_start 完成"
                    0x0130 -> "开始 fira_helper_start_session"
                    0x0131 -> "fira_helper_start_session 完成"
                    else -> "Relay B 返回未知启动诊断码 $startStatus"
                }
                logToUi("🧪【启动诊断】$message")

                val isProgressStatus = startStatus in setOf(
                    0x0100,
                    0x0101,
                    0x0110,
                    0x0111,
                    0x0120,
                    0x0121,
                    0x0130,
                    0x0131
                )

                if (startStatus == 0 || startStatus == 4) {
                    mainHandler.post {
                        if (pendingOobSession?.isAndroidUwbSessionBacked == true) {
                            _findingStatus.value = FindingStatus.RANGING
                            startAndroidRanging()
                        } else {
                            mainHandler.removeCallbacks(timeoutRunnable)
                            _findingStatus.value = FindingStatus.BOARD_STARTED
                            logToUi("✅ 板端验证模式：FiRa responder 已启动，本轮不启动 Android UWB ranging")
                        }
                    }
                } else if (isProgressStatus) {
                    mainHandler.post {
                        if (
                            _findingStatus.value == FindingStatus.SENDING ||
                            _findingStatus.value == FindingStatus.BOARD_STARTING
                        ) {
                            _findingStatus.value = FindingStatus.BOARD_STARTING
                        }
                    }
                } else {
                    mainHandler.post {
                        mainHandler.removeCallbacks(timeoutRunnable)
                        _findingStatus.value = FindingStatus.UWB_ERROR
                    }
                }
                return
            }

            if (distanceCm == 0xFFFF) {
                val uwbStatus = bytes[2].toInt() and 0xFF
                val statusName = uwbStatus.toUwbStatusName()
                logToUi("🧭【UWB诊断】板端 FiRa 已启动，但空口测距失败，status=$uwbStatus($statusName)")
                return
            }

            val distanceMeters = distanceCm / 100.0f

            logToUi("🎯【板端 UWB 数据】距离=${distanceCm}cm (${String.format("%.2f", distanceMeters)}m), 方位=${azimuthDeg}°")

            mainHandler.post {
                mainHandler.removeCallbacks(timeoutRunnable)
                timeoutRetryCount = 0
                _uwbDataFlow.tryEmit(UwbRealData(distanceMeters, azimuthDeg.toFloat()))
                _findingStatus.value = FindingStatus.SUCCESS
            }
        } else {
            logToUi("⚠️ 提示反馈：未知 Notify 长度 ${bytes.size}，期望 [01] 或 4 字节测距包。")
        }
    }

    @SuppressLint("MissingPermission")
    private fun runOneByteQnisProbe(gatt: BluetoothGatt?) {
        if (didRunOneByteProbe || gatt == null || qnisWriteInProgress) {
            return
        }
        val service = gatt.getService(SERVICE_UUID) ?: run {
            logToUi("❌ 1 字节测试取消：未找到 QNIS Service")
            return
        }
        val characteristic = service.getCharacteristic(WRITE_CHARACTERISTIC_UUID) ?: run {
            logToUi("❌ 1 字节测试取消：未找到 QNIS RX")
            return
        }
        val writeType = selectQnisWriteType(characteristic) ?: run {
            logToUi("❌ 1 字节测试取消：QNIS RX 不支持写入")
            return
        }
        val payload = byteArrayOf(0x01)
        didRunOneByteProbe = true
        qnisWriteInProgress = true
        pendingWriteLabel = "BLE_PROBE_1B"
        logToUi("🧪 开始 1 字节 QNIS RX 测试包")
        logQnisWriteAttempt(gatt, characteristic, writeType, payload)
        val success = writeStartSessionPacket(gatt, characteristic, payload, writeType)
        if (!success) {
            qnisWriteInProgress = false
            pendingWriteLabel = ""
            logToUi("❌ 1 字节 QNIS RX 测试包被 Android 协议栈拒绝")
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeStartSessionPacket(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        payload: ByteArray,
        writeType: Int
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, payload, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = writeType
            characteristic.value = payload
            gatt.writeCharacteristic(characteristic)
        }
    }

    private suspend fun prepareAndroidUwbOobSessionOrFallback(): UwbOobSession {
        return try {
            androidUwbController.prepareOobSession()
        } catch (e: Exception) {
            androidUwbController.stop()
            logToUi("⚠️ Android Jetpack UWB backend 不可用，已切换为板端验证模式: ${e.javaClass.simpleName}: ${e.message}")
            UwbOobSession.createFallbackForBoardOnly(context)
        }
    }

    private fun startAndroidRanging() {
        val oobSession = pendingOobSession ?: run {
            _findingStatus.value = FindingStatus.UWB_ERROR
            logToUi("❌ 缺少 OOB session，无法构造 RangingParameters")
            return
        }
        androidUwbController.startRanging(oobSession)
    }

    @SuppressLint("MissingPermission")
    private fun discoverServicesOnce(gatt: BluetoothGatt) {
        if (serviceDiscoveryStarted) return
        serviceDiscoveryStarted = true
        _connectionState.value = "已连接，开始寻址..."
        gatt.discoverServices()
    }

    private fun ByteArray.toUInt16Le(offset: Int): Int {
        return (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun ByteArray.toInt16Le(offset: Int): Int {
        val raw = toUInt16Le(offset)
        return if ((raw and 0x8000) != 0) raw - 0x10000 else raw
    }

    private fun ByteArray.toUInt32Le(offset: Int): Long {
        return (this[offset].toLong() and 0xFF) or
            ((this[offset + 1].toLong() and 0xFF) shl 8) or
            ((this[offset + 2].toLong() and 0xFF) shl 16) or
            ((this[offset + 3].toLong() and 0xFF) shl 24)
    }

    private fun ByteArray.toInt32Le(offset: Int): Int {
        return (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun ByteArray.toHexString(): String = joinToString(" ") { "%02X".format(it) }

    private fun handleFiraParamDiag(bytes: ByteArray): Boolean {
        if (
            bytes.size != 47 ||
            bytes[0] != 0xFE.toByte() ||
            bytes[1] != 0xFD.toByte() ||
            bytes[2] != 0x01.toByte() ||
            bytes[3] != 0x01.toByte() ||
            bytes[4].toInt() != 42
        ) {
            return false
        }

        val sessionId = bytes.toUInt32Le(5)
        val shortAddr = bytes.toUInt16Le(9)
        val dstAddr = bytes.toUInt16Le(11)
        val deviceType = bytes[13].toInt() and 0xFF
        val deviceRole = bytes[14].toInt() and 0xFF
        val roundUsage = bytes[15].toInt() and 0xFF
        val rframe = bytes[16].toInt() and 0xFF
        val sfd = bytes[17].toInt() and 0xFF
        val channel = bytes[18].toInt() and 0xFF
        val preamble = bytes[19].toInt() and 0xFF
        val slotDuration = bytes.toUInt32Le(20)
        val blockDuration = bytes.toUInt32Le(24)
        val roundSlots = bytes.toUInt32Le(28)
        val hopping = bytes[32].toInt() and 0xFF
        val stsConfig = bytes[33].toInt() and 0xFF
        val stsSegments = bytes[34].toInt() and 0xFF
        val stsLength = bytes[35].toInt() and 0xFF
        val resultReport = bytes[36].toInt() and 0xFF
        val multiNode = bytes[37].toInt() and 0xFF
        val schedule = bytes[38].toInt() and 0xFF
        val vupper64 = bytes.copyOfRange(39, 47).toHexString()

        logToUi(
            "🧾【板端最终FiRa参数】session=0x${sessionId.toString(16)}, " +
                "addr=0x${shortAddr.toString(16)}, dst=0x${dstAddr.toString(16)}, " +
                "type=$deviceType, role=$deviceRole, roundUsage=$roundUsage, rframe=$rframe, " +
                "sfd=$sfd, ch=$channel, preamble=$preamble, slot=${slotDuration}rstu, " +
                "block=${blockDuration}ms, rounds=$roundSlots, hopping=$hopping, " +
                "stsConfig=$stsConfig, stsSegments=$stsSegments, stsLength=$stsLength, resultReport=0x${resultReport.toString(16)}, " +
                "multi=$multiNode, schedule=$schedule, vupper64=$vupper64"
        )
        return true
    }

    private fun handleFiraRuntimeDiag(bytes: ByteArray): Boolean {
        if (
            bytes.size !in setOf(10, 20) ||
            bytes[0] != 0xFE.toByte() ||
            bytes[1] != 0xFC.toByte()
        ) {
            return false
        }

        val type = bytes[2].toInt() and 0xFF
        val seq = bytes[3].toInt() and 0xFF

        when (type) {
            0x01 -> {
                val eventType = bytes[4].toInt() and 0xFF
                logToUi("🧭【板端运行期】helper event: seq=$seq, type=$eventType(${eventType.toFiraHelperEventName()})")
            }

            0x02 -> {
                val stage = bytes[4].toInt() and 0xFF
                val status = bytes[5].toInt() and 0xFF
                val value = bytes.toUInt32Le(6)
                val statusText = if (status == 0) "OK" else "ERR"
                logToUi("🧭【板端运行期】start stage: seq=$seq, stage=0x${stage.toString(16)}(${stage.toFiraStartStageName()}), status=$statusText, value=$value")
            }

            0x10 -> {
                if (bytes.size != 20) {
                    return false
                }
                val measurementCount = bytes[4].toInt() and 0xFF
                val status = bytes[5].toInt() and 0xFF
                val slot = bytes[6].toInt() and 0xFF
                val rssi = bytes[7].toInt() and 0xFF
                val block = bytes.toUInt32Le(8)
                val addr = bytes.toUInt16Le(12)
                val distanceCm = bytes.toInt32Le(14)
                val nlos = bytes[18].toInt() and 0xFF
                val stopped = bytes[19].toInt() and 0xFF
                logToUi(
                    "🧭【板端运行期】range report: seq=$seq, block=$block, count=$measurementCount, " +
                        "addr=0x${addr.toString(16)}, status=$status(${status.toUwbStatusName()}), " +
                        "slot=$slot, rssi=$rssi, distanceCm=$distanceCm, nlos=$nlos, stopped=$stopped"
                )
            }

            else -> logToUi("🧭【板端运行期】unknown diag: seq=$seq, type=0x${type.toString(16)}, raw=${bytes.toHexString()}")
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun enableQnisNotify(gatt: BluetoothGatt) {
        val service = gatt.getService(SERVICE_UUID) ?: run {
            logToUi("❌ 开启 Notify 失败：未找到 QNIS Service")
            return
        }
        val characteristic = service.getCharacteristic(NOTIFY_CHARACTERISTIC_UUID) ?: run {
            logToUi("❌ 开启 Notify 失败：未找到 QNIS TX")
            return
        }

        logToUi("🎧 发现 Notify 特征值 (2e939af2)，正在请求开启监听...")
        val localSet = gatt.setCharacteristicNotification(characteristic, true)
        logToUi("🎧 setCharacteristicNotification 本地结果: $localSet")

        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
        if (descriptor != null) {
            val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            }
            logToUi("📝 写入 TX CCCD(0x2902) 描述符中... 结果: $success")
            if (!success) {
                _connectionState.value = "已连接，Notify 未激活"
            }
        } else {
            logToUi("❌ Notify 特征值缺少 CCCD 描述符")
        }
    }

    private fun dumpGattTable(gatt: BluetoothGatt) {
        logToUi("📋 GATT dump begin: services=${gatt.services.size}")
        gatt.services.forEach { service ->
            logToUi("📋 Service: uuid=${service.uuid}, type=${service.type}")
            service.characteristics.forEach { characteristic ->
                logToUi(
                    "📋   Char: uuid=${characteristic.uuid}, ${characteristic.debugProperties()}, " +
                        "permissions=0x${characteristic.permissions.toString(16)}, " +
                        "read=${characteristic.hasProperty(BluetoothGattCharacteristic.PROPERTY_READ)}, " +
                        "write=${characteristic.hasProperty(BluetoothGattCharacteristic.PROPERTY_WRITE)}, " +
                        "writeNoRsp=${characteristic.hasProperty(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)}, " +
                        "notify=${characteristic.hasProperty(BluetoothGattCharacteristic.PROPERTY_NOTIFY)}, " +
                        "indicate=${characteristic.hasProperty(BluetoothGattCharacteristic.PROPERTY_INDICATE)}"
                )
            }
        }
        logToUi("📋 GATT dump end")
    }

    @SuppressLint("MissingPermission")
    private fun logQnisWriteAttempt(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        writeType: Int,
        payload: ByteArray
    ) {
        val linkState = bluetoothManager.getConnectionState(gatt.device, BluetoothProfile.GATT)
        val maxPayload = currentMtu - 3
        logToUi("🧾 QNIS RX 写入前检查: link=${linkState.toGattStateName()}, service=$SERVICE_UUID, char=${characteristic.uuid}")
        logToUi("🧾 QNIS RX 属性: ${characteristic.debugProperties()}, permissions=0x${characteristic.permissions.toString(16)}, writeType=${writeType.toWriteTypeName()}")
        logToUi("🧾 QNIS RX payload: len=${payload.size}, mtu=$currentMtu, maxPayload=$maxPayload, hex=${payload.toHexString()}")
        if (payload.size > maxPayload) {
            logToUi("⚠️ QNIS RX payload 超过当前 MTU 可承载长度，可能需要分包或重新协商 MTU")
        }
    }

    private fun selectQnisWriteType(characteristic: BluetoothGattCharacteristic): Int? {
        val properties = characteristic.properties
        return when {
            (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 ->
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            (properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ->
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            else -> null
        }
    }

    private fun BluetoothGattCharacteristic.debugProperties(): String {
        val names = mutableListOf<String>()
        if ((properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0) names += "READ"
        if ((properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) names += "WRITE"
        if ((properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) names += "WRITE_NO_RESPONSE"
        if ((properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) names += "NOTIFY"
        if ((properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) names += "INDICATE"
        return "properties=0x${properties.toString(16)}(${names.joinToString("|").ifEmpty { "none" }})"
    }

    private fun BluetoothGattCharacteristic.hasProperty(property: Int): Boolean =
        (properties and property) != 0

    private fun Int.toWriteTypeName(): String = when (this) {
        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT -> "WRITE_TYPE_DEFAULT"
        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE -> "WRITE_TYPE_NO_RESPONSE"
        BluetoothGattCharacteristic.WRITE_TYPE_SIGNED -> "WRITE_TYPE_SIGNED"
        else -> "UNKNOWN($this)"
    }

    private fun Int.toGattStateName(): String = when (this) {
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
        BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
        else -> "UNKNOWN($this)"
    }

    private fun Int.toUwbStatusName(): String = when (this) {
        0x00 -> "OK"
        0x20 -> "TX_FAILED"
        0x21 -> "RX_TIMEOUT"
        0x22 -> "RX_PHY_DEC_FAILED"
        0x23 -> "RX_PHY_TOA_FAILED"
        0x24 -> "RX_PHY_STS_FAILED"
        0x25 -> "RX_MAC_DEC_FAILED"
        0x26 -> "RX_MAC_IE_DEC_FAILED"
        0x27 -> "RX_MAC_IE_MISSING"
        else -> "UNKNOWN"
    }

    private fun Int.toFiraHelperEventName(): String = when (this) {
        0 -> "UNSPEC"
        1 -> "TWR_RANGE_NTF"
        2 -> "OWR_AOA_NTF"
        3 -> "UL_TDOA_NTF"
        4 -> "DL_TDOA_NTF"
        5 -> "SESSION_DATA_CREDIT_NTF"
        6 -> "SESSION_DATA_TRANSFER_STATUS_NTF"
        7 -> "DATA_MESSAGE_RCV"
        8 -> "SESSION_STATUS_NTF"
        9 -> "SESSION_UPDATE_CONTROLLER_MULTICAST_LIST_NTF"
        else -> "UNKNOWN"
    }

    private fun Int.toFiraStartStageName(): String = when (this) {
        0x10 -> "process_init_begin"
        0x11 -> "process_init_done"
        0x20 -> "uwbmac_start"
        0x30 -> "fira_helper_start_session"
        else -> "UNKNOWN"
    }

    private fun logToUi(message: String) {
        Log.i("BLE_DEBUG", message)
        _testLogFlow.tryEmit(message)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        timeoutRetryCount = 0
        _connectionState.value = "未连接"
        _connectedMac.value = ""
        isGattReady = false
        qnisWriteInProgress = false
        resetFindingStatus()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }
}
