package com.example.uwbtag

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.* // 💡 确保 runtime 的  delegation 正常工作
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val qnisServiceUuid = UUID.fromString("2E938FD0-6A61-11ED-A1EB-0242AC120002")
    private val bleManager by lazy { UwbBleManager(this) }
    private val feedbackManager by lazy { FeedbackManager(this) }
    private var bluetoothAdapter: BluetoothAdapter? = null
    private val scannedDevices = mutableStateListOf<ScanResult>()
    private val isScanning = mutableStateOf(false)
    private val handler = Handler(Looper.getMainLooper())

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (!permissions.all { it.value }) {
            Toast.makeText(this, "需要授权才可使用雷达寻物", Toast.LENGTH_LONG).show()
        } else {
            startFindPhoneScanService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (checkAndRequestPermissions()) {
            startFindPhoneScanService()
        }

        setContent {
            val currentMac by bleManager.connectedMac.collectAsState()
            val connState by bleManager.connectionState.collectAsState()
            val debugLogs = remember { mutableStateListOf<String>() }

            // 💡 修复点 2：这里原本因为缺少 import runtime.* 导致报错，现已恢复
            val uwbData by bleManager.uwbDataFlow.collectAsState(initial = UwbRealData(0.0f, 0.0f))
            val findingStatus by bleManager.findingStatus.collectAsState()

            LaunchedEffect(Unit) {
                bleManager.testLogFlow.collect { message ->
                    debugLogs.add(0, message)
                    if (debugLogs.size > 80) {
                        debugLogs.removeAt(debugLogs.lastIndex)
                    }
                }
            }

            LaunchedEffect(Unit) {
                bleManager.findPhoneEventFlow.collect {
                    val started = feedbackManager.toggleFindPhoneAlert()
                    val message = if (started) "Tag 按键已触发手机响铃" else "Tag 按键已取消手机响铃"
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                }
            }

            LaunchedEffect(uwbData.distanceMeters, connState, findingStatus) {
                if (connState == "已连接" && findingStatus == FindingStatus.SUCCESS && uwbData.distanceMeters > 0.05f) {
                    feedbackManager.triggerDistanceFeedback(uwbData.distanceMeters)
                } else {
                    feedbackManager.stopDistanceFeedback()
                }
            }

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0A0F0D)) {
                    if (currentMac.isEmpty()) {
                        BleScanScreen(
                            devices = scannedDevices,
                            isScanning = isScanning.value,
                            onStartScan = { startLeScan() },
                            onDeviceClick = { mac ->
                                stopLeScan()
                                bleManager.connectToDevice(mac)
                            }
                        )
                    } else {
                        AirTagRadarScreen(
                            connState = connState,
                            uwbData = uwbData,
                            findingStatus = findingStatus,
                            debugLogs = debugLogs,
                            onStartFindingClick = {
                                bleManager.sendStartFindingCmd()
                            },
                            onDisconnect = {
                                feedbackManager.stopAll()
                                bleManager.disconnect()
                            }
                        )
                    }
                }
            }
        }
    }

    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val deviceAddress = result.device.address
            if (!scannedDevices.any { it.device.address == deviceAddress }) {
                scannedDevices.add(result)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLeScan() {
        if (isScanning.value || bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) return
        scannedDevices.clear()
        isScanning.value = true
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ stopLeScan() }, 5000)
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(qnisServiceUuid))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        bluetoothAdapter!!.bluetoothLeScanner?.startScan(filters, settings, leScanCallback)
    }

    @SuppressLint("MissingPermission")
    private fun stopLeScan() {
        if (!isScanning.value) return
        isScanning.value = false
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(leScanCallback)
    }

    private fun checkAndRequestPermissions(): Boolean {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            buildList {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.UWB_RANGING)
                add(Manifest.permission.VIBRATE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }.toTypedArray()
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.VIBRATE)
        }
        val missing = required.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
        if (missing.isNotEmpty()) {
            requestPermissionLauncher.launch(missing)
            return false
        }
        return true
    }

    private fun startFindPhoneScanService() {
        val intent = Intent(this, FindPhoneScanService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        feedbackManager.stopAll()
        bleManager.disconnect()
    }
}

@Composable
fun AirTagRadarScreen(
    connState: String,
    uwbData: UwbRealData,
    findingStatus: FindingStatus,
    debugLogs: List<String>,
    onStartFindingClick: () -> Unit,
    onDisconnect: () -> Unit
) {
    val animatedAngle by animateFloatAsState(
        targetValue = if (findingStatus == FindingStatus.SUCCESS) uwbData.azimuthDegrees else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "Angle"
    )
    val animatedElevation by animateFloatAsState(
        targetValue = if (findingStatus == FindingStatus.SUCCESS && uwbData.hasElevation) {
            (uwbData.elevationDegrees * 0.65f).coerceIn(-28.0f, 28.0f)
        } else {
            0.0f
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "Elevation"
    )

    val targetBgColor = when (findingStatus) {
                    FindingStatus.SUCCESS -> Color(0xFF00E676)
        FindingStatus.FAILED, FindingStatus.TIMEOUT, FindingStatus.UWB_ERROR -> Color(0x33FF5252)
        else -> Color(0xFF1E1E1E)
    }
    val animatedBgColor by animateColorAsState(targetValue = targetBgColor, label = "BgColor")

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(text = "基站: $connState", color = Color.Gray, fontSize = 14.sp)
            TextButton(onClick = onDisconnect) {
                Text("断开连接", color = Color(0xFFFF5252))
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(260.dp)
                    .clip(CircleShape)
                    .background(animatedBgColor),
                contentAlignment = Alignment.Center
            ) {
                if (findingStatus == FindingStatus.SUCCESS) {
                    SpatialRadarTarget(
                        azimuthDegrees = animatedAngle,
                        elevationDegrees = animatedElevation,
                        hasElevation = uwbData.hasElevation,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (
                    findingStatus == FindingStatus.SENDING ||
                    findingStatus == FindingStatus.BOARD_STARTING ||
                    findingStatus == FindingStatus.BOARD_STARTED ||
                    findingStatus == FindingStatus.RANGING
                ) {
                    CircularProgressIndicator(color = Color(0xFF00B0FF), modifier = Modifier.size(50.dp))
                } else {
                    Text("💤", fontSize = 48.sp)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = when (findingStatus) {
                    FindingStatus.IDLE -> "等待 OOB 握手"
                    FindingStatus.SENDING -> "正在发送 OOB..."
                    FindingStatus.BOARD_STARTING -> "板端启动中..."
                    FindingStatus.BOARD_STARTED -> "板端已启动"
                    FindingStatus.RANGING -> "Android UWB 测距中..."
                    FindingStatus.SUCCESS -> String.format("%.2f 米", uwbData.distanceMeters)
                    FindingStatus.FAILED -> "OOB 写入错误"
                    FindingStatus.TIMEOUT -> "OOB 响应超时"
                    FindingStatus.UWB_ERROR -> "UWB 测距失败"
                },
                color = if (findingStatus == FindingStatus.FAILED || findingStatus == FindingStatus.TIMEOUT || findingStatus == FindingStatus.UWB_ERROR) Color(0xFFFF5252) else Color.White,
                fontSize = if (findingStatus == FindingStatus.SUCCESS) 48.sp else 32.sp,
                fontWeight = FontWeight.Black
            )

            Spacer(modifier = Modifier.height(16.dp))

            RangingMetricsRow(
                uwbData = uwbData,
                isActive = findingStatus == FindingStatus.SUCCESS
            )

            Text(
                text = when (findingStatus) {
                    FindingStatus.IDLE -> "请点击下方按钮发送 Android START_SESSION 参数"
                    FindingStatus.SENDING -> "手机正向 998A 写入真实 Android UWB OOB 参数..."
                    FindingStatus.BOARD_STARTING -> "Android UWB backend 不可用或未使用，正在等待板端 FiRa 启动诊断"
                    FindingStatus.BOARD_STARTED -> "板端 FiRa responder 已启动；当前为板端验证模式，不显示手机 UWB 距离"
                    FindingStatus.RANGING -> "手机已启动 Jetpack UWB ranging，等待 RangingResult"
                    FindingStatus.SUCCESS -> if (uwbData.hasElevation) {
                        String.format(
                            "3D 箭头使用滤波俯仰角；原始值仍在下方日志中\n俯仰 %.1f°，空间距离 %.2f 米",
                            uwbData.elevationDegrees,
                            uwbData.distanceMeters
                        )
                    } else {
                        "距离/方向来自 Android UWB ranging result\n当前设备或会话未返回俯仰角"
                    }
                    FindingStatus.FAILED -> "Android协议栈拒绝，请重启蓝牙重试"
                    FindingStatus.TIMEOUT -> "板子没有回传 OOB ACK，请检查固件版本和 Notify 通道"
                    FindingStatus.UWB_ERROR -> "OOB 已通过，UWB 空口失败；请查看下方诊断码"
                },
                color = Color.Gray,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        DebugLogPanel(debugLogs)

        Button(
            onClick = onStartFindingClick,
            enabled = (findingStatus != FindingStatus.SENDING),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(bottom = 8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = when (findingStatus) {
                    FindingStatus.SUCCESS -> Color(0xFF00E676)
                    FindingStatus.FAILED, FindingStatus.TIMEOUT, FindingStatus.UWB_ERROR -> Color(0xFFFF5252)
                    FindingStatus.BOARD_STARTED -> Color(0xFF607D8B)
                    else -> Color(0xFF00B0FF)
                },
                disabledContainerColor = Color(0xFF2D2D2D)
            ),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(
                text = when (findingStatus) {
                    FindingStatus.IDLE -> "发送 OOB START_SESSION"
                    FindingStatus.SENDING -> "正在等待 OOB ACK..."
                    FindingStatus.BOARD_STARTING -> "正在等待板端启动..."
                    FindingStatus.BOARD_STARTED -> "板端已启动：重新发送"
                    FindingStatus.RANGING -> "正在等待手机 UWB 结果..."
                    FindingStatus.SUCCESS -> "重新测距"
                    FindingStatus.FAILED -> "写入失败：重新发送"
                    FindingStatus.TIMEOUT -> "ACK 超时：重新发送"
                    FindingStatus.UWB_ERROR -> "测距失败：重新发送"
                },
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun RangingMetricsRow(uwbData: UwbRealData, isActive: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        RangingMetric(
            value = if (isActive) String.format("%.2fm", uwbData.horizontalDistanceMeters) else "-",
            label = "水平距离",
            color = Color(0xFF40C4FF)
        )
        RangingMetric(
            value = if (isActive) String.format("%.1f°", uwbData.azimuthDegrees) else "-",
            label = "方位角",
            color = Color(0xFF00E676)
        )
        RangingMetric(
            value = if (isActive && uwbData.hasElevation) String.format("%+.2fm", uwbData.relativeHeightMeters) else "-",
            label = "高度差",
            color = when {
                !isActive || !uwbData.hasElevation -> Color(0xFFB0BEC5)
                uwbData.relativeHeightMeters > 0.08f -> Color(0xFF40C4FF)
                uwbData.relativeHeightMeters < -0.08f -> Color(0xFFFFAB40)
                else -> Color.White
            }
        )
    }
}

@Composable
fun RangingMetric(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(text = label, color = Color(0xFF8A948F), fontSize = 12.sp)
    }
}

@Composable
fun DebugLogPanel(logs: List<String>) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp),
        color = Color(0xFF111816),
        shape = RoundedCornerShape(8.dp)
    ) {
        LazyColumn(
            modifier = Modifier.padding(10.dp),
            reverseLayout = false
        ) {
            items(logs) { line ->
                Text(
                    text = line,
                    color = Color(0xFFC8D6D0),
                    fontSize = 11.sp,
                    lineHeight = 14.sp
                )
            }
        }
    }
}

@Composable
fun SpatialRadarTarget(
    azimuthDegrees: Float,
    elevationDegrees: Float,
    hasElevation: Boolean,
    modifier: Modifier = Modifier
) {
    val heightColor = when {
        !hasElevation -> Color(0xFFECEFF1)
        elevationDegrees > 8.0f -> Color(0xFF40C4FF)
        elevationDegrees < -8.0f -> Color(0xFFFFAB40)
        else -> Color.White
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = androidx.compose.ui.geometry.Offset(size.width / 2.0f, size.height / 2.0f)
            drawCircle(
                color = Color(0x3300B0FF),
                radius = size.minDimension * 0.36f,
                center = center
            )
            drawCircle(
                color = Color(0x22000000),
                radius = size.minDimension * 0.24f,
                center = androidx.compose.ui.geometry.Offset(center.x, center.y + size.height * 0.20f)
            )
        }

        Box(
            modifier = Modifier
                .size(142.dp)
                .graphicsLayer {
                    cameraDistance = 9.0f * density
                    rotationZ = azimuthDegrees
                    rotationX = -elevationDegrees * 0.85f
                    rotationY = elevationDegrees * 0.18f
                    translationY = -elevationDegrees * density * 0.22f
                    shadowElevation = 18.0f
                    scaleX = 1.0f + kotlin.math.abs(elevationDegrees) / 360.0f
                    scaleY = 1.0f - kotlin.math.abs(elevationDegrees) / 620.0f
                },
            contentAlignment = Alignment.Center
        ) {
            ArrowPointer(
                modifier = Modifier.fillMaxSize(),
                accentColor = heightColor
            )
        }

        if (hasElevation) {
            val markerOffset = (-elevationDegrees).coerceIn(-58.0f, 58.0f)
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 30.dp)
                    .width(5.dp)
                    .height(118.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0x3340C4FF))
            )
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 25.dp)
                    .offset(y = markerOffset.dp)
                    .size(15.dp)
                    .clip(CircleShape)
                    .background(heightColor)
            )
        }
    }
}

@Composable
fun ArrowPointer(modifier: Modifier = Modifier, accentColor: Color = Color.White) {
    Canvas(modifier = modifier) {
        val shadow = Path().apply {
            moveTo(size.width / 2, size.height * 0.07f)
            lineTo(size.width * 0.93f, size.height * 0.88f)
            lineTo(size.width * 0.5f, size.height * 0.69f)
            lineTo(size.width * 0.07f, size.height * 0.88f)
            close()
        }
        val path = Path().apply {
            moveTo(size.width / 2, 0f)
            lineTo(size.width, size.height * 0.85f)
            lineTo(size.width * 0.5f, size.height * 0.65f)
            lineTo(0f, size.height * 0.85f)
            close()
        }
        drawPath(path = shadow, color = Color(0x66000000))
        drawPath(
            path = path,
            brush = Brush.verticalGradient(colors = listOf(Color.White, accentColor, Color(0xFFECEFF1)))
        )
    }
}

@SuppressLint("MissingPermission")
@Composable
fun BleScanScreen(devices: List<ScanResult>, isScanning: Boolean, onStartScan: () -> Unit, onDeviceClick: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("AirTag 雷达寻物终端", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onStartScan, enabled = !isScanning, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))) {
            Text(if (isScanning) "正在追踪基站信号..." else "扫描 UWB 节点", color = Color.Black, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(16.dp))
        LazyColumn {
            items(devices) { result ->
                val name = result.scanRecord?.deviceName ?: result.device.name ?: "未知基站"
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { onDeviceClick(result.device.address) }, colors = CardDefaults.cardColors(containerColor = Color(0xFF151B18))) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text(text = name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text(text = "MAC: ${result.device.address}", color = Color.DarkGray, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
