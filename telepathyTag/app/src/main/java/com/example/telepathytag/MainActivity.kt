package com.example.telepathytag

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate as rotateDraw
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.telepathytag.ui.theme.*
import java.util.UUID
import kotlin.math.*

class MainActivity : ComponentActivity() {
    private val qnisServiceUuid = UUID.fromString("2E938FD0-6A61-11ED-A1EB-0242AC120002")
    private val bleManager by lazy { UwbBleManager(this) }
    private val feedbackManager by lazy { FeedbackManager(this) }
    private val tagRepository by lazy { TagRepository(this) }
    private var bluetoothAdapter: BluetoothAdapter? = null
    private val scannedDevices = mutableStateListOf<ScanResult>()
    private val isScanning = mutableStateOf(false)
    private val handler = Handler(Looper.getMainLooper())

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (!permissions.all { it.value }) {
            Toast.makeText(this, "需要授权才可使用雷达寻物", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        checkAndRequestPermissions()

        setContent {
            TelepathyTagTheme {
                val currentMac by bleManager.connectedMac.collectAsState()
                val connState by bleManager.connectionState.collectAsState()
                val debugLogs = remember { mutableStateListOf<String>() }
                val tagBindings by tagRepository.bindings.collectAsState()

                val uwbData by bleManager.uwbDataFlow.collectAsState(initial = UwbRealData(0.0f, 0.0f))
                val findingStatus by bleManager.findingStatus.collectAsState()

                // Collect BLE debug logs
                LaunchedEffect(Unit) {
                    bleManager.testLogFlow.collect { message ->
                        debugLogs.add(0, message)
                        if (debugLogs.size > 80) {
                            debugLogs.removeAt(debugLogs.lastIndex)
                        }
                    }
                }

                // Haptic feedback when ranging succeeds and distance < 1m
                LaunchedEffect(uwbData.distanceMeters, connState, findingStatus) {
                    if (connState == "已连接" && findingStatus == FindingStatus.SUCCESS && uwbData.distanceMeters > 0.05f) {
                        feedbackManager.triggerDistanceFeedback(uwbData.distanceMeters)
                    } else {
                        feedbackManager.stop()
                    }
                }

                Surface(modifier = Modifier.fillMaxSize(), color = BackgroundBlack) {
                    if (currentMac.isEmpty()) {
                        BleScanScreen(
                            devices = scannedDevices,
                            isScanning = isScanning.value,
                            tagBindings = tagBindings,
                            onStartScan = { startLeScan() },
                            onDeviceClick = { mac ->
                                stopLeScan()
                                bleManager.connectToDevice(mac)
                            }
                        )
                    } else {
                        HaloTagRadarScreen(
                            connState = connState,
                            connectedMac = currentMac,
                            uwbData = uwbData,
                            findingStatus = findingStatus,
                            debugLogs = debugLogs,
                            tagBindings = tagBindings,
                            onStartFindingClick = {
                                bleManager.sendStartFindingCmd()
                            },
                            onDisconnect = {
                                feedbackManager.stop()
                                bleManager.disconnect()
                            },
                            onConnectSavedDevice = { mac ->
                                feedbackManager.stop()
                                bleManager.connectToDevice(mac)
                            },
                            onAddBinding = { mac, name ->
                                tagRepository.addBinding(mac, name)
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

    private fun checkAndRequestPermissions() {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.UWB_RANGING,
                Manifest.permission.VIBRATE
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.VIBRATE)
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (missing.isNotEmpty()) requestPermissionLauncher.launch(missing)
    }

    override fun onDestroy() {
        super.onDestroy()
        feedbackManager.stop()
        bleManager.disconnect()
    }
}

// ============================================================
// BLE SCAN SCREEN (Section 5)
// ============================================================

@SuppressLint("MissingPermission")
@Composable
fun BleScanScreen(
    devices: List<ScanResult>,
    isScanning: Boolean,
    tagBindings: List<TagBinding>,
    onStartScan: () -> Unit,
    onDeviceClick: (String) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(GradientDarkGreen, GradientMid, GradientBlack)
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(top = 48.dp)
        ) {
            // Title
            Text(
                text = "Halo Tag",
                style = MaterialTheme.typography.headlineLarge,
                color = TextOnDark,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Subtitle
            Text(
                text = "Find your items...",
                style = MaterialTheme.typography.bodyLarge,
                color = TextOnDark.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Scan Button
            val buttonScale by animateFloatAsState(
                targetValue = if (isScanning) 0.97f else 1.0f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                label = "buttonScale"
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .scale(buttonScale)
                    .shadow(8.dp, RoundedCornerShape(18.dp))
                    .clip(RoundedCornerShape(18.dp))
                    .background(CardDark)
                    .clickable(enabled = !isScanning) { onStartScan() }
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isScanning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = PrimaryGreen,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Scanning for tags...",
                            style = MaterialTheme.typography.labelLarge,
                            color = PrimaryGreen,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                } else {
                    Text(
                        text = "🔍 Scan for Tags",
                        style = MaterialTheme.typography.labelLarge,
                        color = PrimaryGreen,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Status text
            Text(
                text = when {
                    isScanning && devices.isEmpty() -> "Searching..."
                    devices.isNotEmpty() -> "${devices.size} device(s)"
                    else -> "Tap scan to search for nearby tags"
                },
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.5f)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Device list or empty state
            if (devices.isEmpty() && !isScanning) {
                EmptyState()
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(devices) { index, result ->
                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn(animationSpec = tween(400)) +
                                slideInVertically(
                                    animationSpec = tween(400),
                                    initialOffsetY = { it / 2 }
                                )
                        ) {
                            val existingBinding = tagBindings.find { it.mac == result.device.address }
                            DeviceCard(
                                result = result,
                                index = index,
                                customName = existingBinding?.name,
                                onClick = { onDeviceClick(result.device.address) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "📡",
                fontSize = 48.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "No devices found",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.5f)
            )
            Text(
                text = "Tap scan to search for nearby tags",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.35f)
            )
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun DeviceCard(
    result: ScanResult,
    index: Int,
    customName: String?,
    onClick: () -> Unit
) {
    val rssi = result.rssi
    val name = customName ?: result.scanRecord?.deviceName ?: result.device.name ?: "Unknown Device"
    val mac = result.device.address
    val isBound = customName != null
    val signalColor = when {
        rssi > -60 -> SignalStrong
        rssi > -75 -> SignalMedium
        else -> SignalWeak
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(16.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardGlass)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Signal dot
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(signalColor)
            )

            Spacer(modifier = Modifier.width(14.dp))

            // Name
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isBound) PrimaryGreen else TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                if (isBound) {
                    Text(
                        text = "Bound tag",
                        style = MaterialTheme.typography.bodySmall,
                        color = PrimaryGreen.copy(alpha = 0.6f)
                    )
                }
            }

            // RSSI + Signal bars
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "$rssi dBm",
                    style = MaterialTheme.typography.labelSmall,
                    color = signalColor,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                SignalBars(rssi = rssi)
            }
        }
    }
}

@Composable
fun SignalBars(rssi: Int) {
    val activeBars = when {
        rssi > -55 -> 4
        rssi > -65 -> 3
        rssi > -75 -> 2
        rssi > -85 -> 1
        else -> 0
    }
    val barColor = when {
        rssi > -65 -> SignalStrong
        rssi > -75 -> SignalMedium
        else -> SignalWeak
    }
    val inactiveColor = Color(0xFF2A332F)

    Canvas(modifier = Modifier.width(22.dp).height(21.dp)) {
        val barWidth = 4.dp.toPx()
        val gap = 2.dp.toPx()
        val heights = listOf(6.dp.toPx(), 11.dp.toPx(), 16.dp.toPx(), 21.dp.toPx())
        val totalW = 4 * barWidth + 3 * gap
        val startX = (size.width - totalW) / 2

        heights.forEachIndexed { i, h ->
            val x = startX + i * (barWidth + gap)
            val y = size.height - h
            drawRoundRect(
                color = if (i < activeBars) barColor else inactiveColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
            )
        }
    }
}

// ============================================================
// HALO TAG RADAR SCREEN (Section 6)
// ============================================================

@Composable
fun HaloTagRadarScreen(
    connState: String,
    connectedMac: String,
    uwbData: UwbRealData,
    findingStatus: FindingStatus,
    debugLogs: List<String>,
    tagBindings: List<TagBinding>,
    onStartFindingClick: () -> Unit,
    onDisconnect: () -> Unit,
    onConnectSavedDevice: (String) -> Unit,
    onAddBinding: (mac: String, name: String) -> Unit
) {
    val isSuccess = findingStatus == FindingStatus.SUCCESS
    val isNearby = isSuccess && uwbData.distanceMeters <= 1.0f
    val isSearching = findingStatus in setOf(
        FindingStatus.SENDING, FindingStatus.BOARD_STARTING,
        FindingStatus.BOARD_STARTED, FindingStatus.RANGING
    )
    val isError = findingStatus in setOf(FindingStatus.FAILED, FindingStatus.TIMEOUT, FindingStatus.UWB_ERROR)
    val isFinding = findingStatus == FindingStatus.SENDING

    // Secondary button scale animations
    val playScale by animateFloatAsState(
        targetValue = if (isFinding) 0.97f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "playScale"
    )
    val dirScale by animateFloatAsState(
        targetValue = if (isFinding) 0.97f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "dirScale"
    )

    // Arrow rotation animation
    val animatedAngle by animateFloatAsState(
        targetValue = if (isSuccess) uwbData.azimuthDegrees else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "arrowAngle"
    )

    // Radar scale animation
    val radarScale by animateFloatAsState(
        targetValue = if (isSuccess) 1.0f else 0.95f,
        animationSpec = tween(500, easing = EaseOutCubic),
        label = "radarScale"
    )

    // Auto-dialog for first-time binding
    val existingBinding = tagBindings.find { it.mac == connectedMac }
    var showBindDialog by remember { mutableStateOf(false) }
    var bindName by remember { mutableStateOf("") }

    // Show dialog automatically if this MAC is not yet bound
    LaunchedEffect(connectedMac) {
        if (connectedMac.isNotEmpty() && existingBinding == null) {
            showBindDialog = true
        }
    }

    // Bind dialog
    if (showBindDialog) {
        AlertDialog(
            onDismissRequest = { showBindDialog = false },
            containerColor = CardDark,
            title = {
                Text("New Tag Found!", color = PrimaryGreen, fontWeight = FontWeight.SemiBold)
            },
            text = {
                Column {
                    Text(
                        text = "What is this tag attached to?",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = bindName,
                        onValueChange = { bindName = it },
                        label = { Text("Item name", color = TextSecondary) },
                        placeholder = { Text("e.g. My Keys, Wallet...", color = TextMuted) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = PrimaryGreen,
                            unfocusedBorderColor = TextMuted
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = bindName.trim().ifEmpty { "My Tag" }
                        onAddBinding(connectedMac, name)
                        showBindDialog = false
                        bindName = ""
                    }
                ) {
                    Text("Save", color = PrimaryGreen, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBindDialog = false; bindName = "" }) {
                    Text("Skip", color = TextSecondary)
                }
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // ---- Top Bar ----
        TopBar(connState = connState, onDisconnect = onDisconnect)

        // ---- Scrollable Content ----
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // Radar Display
            item {
                Box(
                    modifier = Modifier
                        .size(260.dp)
                        .scale(radarScale),
                    contentAlignment = Alignment.Center
                ) {
                    val isActive = isSearching || isSuccess
                    val pulseFast = isNearby
                    RadarDisplay(isActive = isActive, pulseFast = pulseFast)

                    // Arrow or icon overlay
                    when {
                        isSuccess -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .rotate(animatedAngle),
                                contentAlignment = Alignment.Center
                            ) {
                                ArrowPointer(modifier = Modifier.size(100.dp))
                            }
                        }
                        isSearching -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(40.dp),
                                color = PrimaryGreen,
                                strokeWidth = 3.dp
                            )
                        }
                        isError -> {
                            Text("🔑", fontSize = 40.sp)
                        }
                        else -> {
                            Text("🔑", fontSize = 40.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Status text
                val statusLabel = when {
                    findingStatus == FindingStatus.IDLE -> "READY"
                    isSearching -> "SEARCHING..."
                    isNearby -> "NEARBY"
                    isSuccess -> "TRACKING"
                    findingStatus == FindingStatus.FAILED -> "WRITE ERROR"
                    else -> "TIMEOUT"
                }
                val statusColor = when {
                    isNearby || isSuccess -> PrimaryGreen
                    isError -> StatusLost
                    else -> TextMuted
                }
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor,
                    letterSpacing = 3.sp
                )

                Spacer(modifier = Modifier.height(16.dp))
            }

            // Data Display (distance / angle / direction)
            if (isSuccess) {
                item {
                    DataDisplay(uwbData = uwbData, isNearby = isNearby)
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }

            // InfoCard
            item {
                InfoCard(
                    deviceName = existingBinding?.name ?: "Halo Tag",
                    uwbData = uwbData,
                    findingStatus = findingStatus
                )
                Spacer(modifier = Modifier.height(20.dp))
            }

            // Action Buttons (Play Sound + Direction — secondary)
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Play Sound
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .scale(playScale)
                            .shadow(6.dp, RoundedCornerShape(18.dp))
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(ButtonGreenStart, ButtonGreenEnd)
                                )
                            )
                            .clickable(enabled = !isFinding) { onStartFindingClick() }
                            .height(56.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "🔊 Play Sound",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White
                        )
                    }
                    // Direction
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .scale(dirScale)
                            .shadow(6.dp, RoundedCornerShape(18.dp))
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(ButtonDarkStart, ButtonDarkEnd)
                                )
                            )
                            .clickable(enabled = !isFinding) { onStartFindingClick() }
                            .height(56.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "🧭 Direction",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // ---- MAIN ACTION BUTTON (state-dependent, primary CTA) ----
            item {
                val isSuccessState = findingStatus == FindingStatus.SUCCESS
                val mainBtnColor = when (findingStatus) {
                    FindingStatus.SUCCESS -> PrimaryGreen
                    FindingStatus.FAILED, FindingStatus.TIMEOUT, FindingStatus.UWB_ERROR -> StatusLost
                    FindingStatus.BOARD_STARTED -> Color(0xFF607D8B)
                    else -> PrimaryGreen
                }
                val mainBtnText = when (findingStatus) {
                    FindingStatus.IDLE -> "Start Finding"
                    FindingStatus.SENDING -> "Waiting for OOB ACK..."
                    FindingStatus.BOARD_STARTING -> "Waiting for board startup..."
                    FindingStatus.BOARD_STARTED -> "Board Ready: Restart"
                    FindingStatus.RANGING -> "Waiting for UWB result..."
                    FindingStatus.SUCCESS -> String.format("✅ Found It!  (%.1f m)", uwbData.distanceMeters)
                    FindingStatus.FAILED -> "Write Failed: Retry"
                    FindingStatus.TIMEOUT -> "ACK Timeout: Retry"
                    FindingStatus.UWB_ERROR -> "Range Failed: Retry"
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .shadow(8.dp, RoundedCornerShape(14.dp))
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (isFinding) SurfaceDark else mainBtnColor
                        )
                        .clickable(enabled = !isFinding) {
                            if (isSuccessState) onDisconnect() else onStartFindingClick()
                        }
                        .height(56.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = mainBtnText,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            // MY DEVICES section
            item {
                Text(
                    text = "MY DEVICES",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (tagBindings.isEmpty()) {
                item {
                    Text(
                        text = "No saved tags. Scan and tap ＋ to bind a tag.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            } else {
                for (binding in tagBindings) {
                    val isConnected = binding.mac == connectedMac
                    item {
                        MyDeviceItem(
                            name = binding.name,
                            isCurrent = isConnected,
                            status = "",
                            onClick = {
                                if (!isConnected) onConnectSavedDevice(binding.mac)
                            }
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}

// ---- Top Bar (Section 6.3) ----

@Composable
fun TopBar(connState: String, onDisconnect: () -> Unit) {
    val isConnected = connState == "已连接"
    val dotColor = when {
        isConnected -> StatusDotGreen
        connState.contains("连接") -> StatusDotOrange
        else -> StatusDotRed
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(GradientDarkGreen, GradientBlack)
                )
            )
            .padding(top = 48.dp)
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Connection indicator
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column {
                Text(
                    text = "Halo Tag",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White.copy(alpha = 0.7f)
                )
                Text(
                    text = connState,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            TextButton(onClick = onDisconnect) {
                Text(
                    text = "Disconnect",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        }
    }
}

// ---- Radar Display (Section 6.4) ----

@Composable
fun RadarDisplay(isActive: Boolean, pulseFast: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "radar")

    // Scan arc rotation
    val scanAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scanArc"
    )

    // Pulse ripple 1
    val pulseDuration = if (pulseFast) 1000 else 1800
    val pulse1Radius by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(pulseDuration, easing = EaseOutCubic),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse1"
    )
    val pulse1Alpha = (0.5f * (1f - pulse1Radius)).coerceIn(0f, 0.5f)

    // Pulse ripple 2 (offset by half period)
    val pulse2Radius by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(pulseDuration, easing = EaseOutCubic),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse2"
    )
    val pulse2Alpha = (0.45f * (1f - pulse2Radius)).coerceIn(0f, 0.45f)

    val pulseColor = if (pulseFast) PrimaryGreen else AccentGreen

    Canvas(modifier = Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2, size.height / 2)
        val maxRadius = size.minDimension / 2

        // Background circle
        drawCircle(
            color = RadarBgCircle,
            radius = maxRadius,
            center = center
        )

        // Outer ring
        drawCircle(
            color = Color(0xFF1A3328),
            radius = maxRadius,
            center = center,
            style = Stroke(width = 1.5.dp.toPx())
        )

        // 3 concentric rings
        for (i in 1..3) {
            drawCircle(
                color = RadarRingGreen,
                radius = maxRadius * i / 3,
                center = center,
                style = Stroke(width = 0.5.dp.toPx())
            )
        }

        // Cross lines
        val crossAlpha = 0.15f
        drawLine(
            color = Color.White.copy(alpha = crossAlpha),
            start = Offset(center.x - maxRadius, center.y),
            end = Offset(center.x + maxRadius, center.y),
            strokeWidth = 0.5.dp.toPx()
        )
        drawLine(
            color = Color.White.copy(alpha = crossAlpha),
            start = Offset(center.x, center.y - maxRadius),
            end = Offset(center.x, center.y + maxRadius),
            strokeWidth = 0.5.dp.toPx()
        )

        // 12 tick marks
        for (i in 0 until 12) {
            val angleDeg = i * 30f
            val isCardinal = i % 3 == 0
            val tickLength = if (isCardinal) 12.dp.toPx() else 6.dp.toPx()
            val tickColor = if (isCardinal) Color.White.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.12f)
            val tickWidth = if (isCardinal) 1.2.dp.toPx() else 0.6.dp.toPx()

            val rad = Math.toRadians(angleDeg.toDouble())
            val innerX = center.x + (maxRadius - tickLength) * cos(rad).toFloat()
            val innerY = center.y + (maxRadius - tickLength) * sin(rad).toFloat()
            val outerX = center.x + maxRadius * cos(rad).toFloat()
            val outerY = center.y + maxRadius * sin(rad).toFloat()

            drawLine(
                color = tickColor,
                start = Offset(innerX, innerY),
                end = Offset(outerX, outerY),
                strokeWidth = tickWidth
            )
        }

        // Scan arc
        rotateDraw(scanAngle, center) {
            drawArc(
                brush = Brush.sweepGradient(
                    0.00f to PrimaryGreen.copy(alpha = 0.4f),
                    0.15f to PrimaryGreen.copy(alpha = 0.15f),
                    1.00f to Color.Transparent
                ),
                startAngle = -10f,
                sweepAngle = 65f,
                useCenter = false,
                topLeft = Offset(center.x - maxRadius, center.y - maxRadius),
                size = Size(maxRadius * 2, maxRadius * 2),
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        // Pulse ripples
        if (isActive) {
            drawCircle(
                color = pulseColor.copy(alpha = pulse1Alpha),
                radius = maxRadius * pulse1Radius,
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )
            drawCircle(
                color = pulseColor.copy(alpha = pulse2Alpha),
                radius = maxRadius * pulse2Radius,
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )
        }

        // Center dot (outer)
        drawCircle(
            color = if (isActive) PrimaryGreen else Color(0xFF1A3328),
            radius = 6.dp.toPx(),
            center = center
        )
        // Center dot (inner)
        drawCircle(
            color = Color(0xFF060A08),
            radius = 3.dp.toPx(),
            center = center
        )
    }
}

// ---- Arrow Pointer (Section 6.6) ----

@Composable
fun ArrowPointer(modifier: Modifier = Modifier) {
    val arrowGreen = PrimaryGreen
    val arrowGreenDark = PrimaryGreenDark

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // === Upper half (points to target) ===
        val upperPath = Path().apply {
            moveTo(0.50f * w, 0.00f * h)  // tip
            lineTo(0.72f * w, 0.38f * h)  // right shoulder
            lineTo(0.58f * w, 0.50f * h)  // right waist
            lineTo(0.50f * w, 0.50f * h)  // center
            close()
        }
        drawPath(
            path = upperPath,
            brush = Brush.verticalGradient(
                colors = listOf(arrowGreen, arrowGreenDark, Color(0xFF009624))
            ),
            style = androidx.compose.ui.graphics.drawscope.Fill
        )
        drawPath(
            path = upperPath,
            color = arrowGreen.copy(alpha = 0.5f),
            style = Stroke(width = 1.5.dp.toPx())
        )

        // === Lower half (tail) ===
        val lowerPath = Path().apply {
            moveTo(0.45f * w, 0.50f * h)  // upper-left waist
            lineTo(0.37f * w, 0.63f * h)  // left tail tip
            lineTo(0.46f * w, 0.58f * h)  // left inner notch
            lineTo(0.50f * w, 0.72f * h)  // tail bottom
            lineTo(0.54f * w, 0.58f * h)  // right inner notch
            lineTo(0.63f * w, 0.63f * h)  // right tail tip
            lineTo(0.55f * w, 0.50f * h)  // upper-right waist
            close()
        }
        drawPath(
            path = lowerPath,
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFF3A3F3C), Color(0xFF2A2F2C), Color(0xFF1A1F1C))
            ),
            style = androidx.compose.ui.graphics.drawscope.Fill
        )
        drawPath(
            path = lowerPath,
            color = Color.White.copy(alpha = 0.15f),
            style = Stroke(width = 1.2.dp.toPx())
        )

        // === Center rivet ===
        val rivetRadius = 0.09f * w
        drawCircle(
            color = Color(0xFF1A1F1C),
            radius = rivetRadius,
            center = Offset(0.50f * w, 0.50f * h)
        )
        drawCircle(
            color = Color(0xFF2A332F),
            radius = rivetRadius,
            center = Offset(0.50f * w, 0.50f * h),
            style = Stroke(width = 1.5.dp.toPx())
        )
        drawCircle(
            color = arrowGreen.copy(alpha = 0.4f),
            radius = 0.04f * w,
            center = Offset(0.50f * w, 0.50f * h)
        )
    }
}

// ---- Data Display ----

@Composable
fun DataDisplay(uwbData: UwbRealData, isNearby: Boolean) {
    val distanceColor = if (isNearby) PrimaryGreen else TextPrimary
    val directionLabel = azimuthToDirection(uwbData.azimuthDegrees)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Distance row
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = String.format("%.1f", uwbData.distanceMeters),
                style = MaterialTheme.typography.displayLarge,
                color = distanceColor
            )
            Text(
                text = " m",
                style = MaterialTheme.typography.headlineSmall,
                color = distanceColor,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Angle row
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "${uwbData.azimuthDegrees.toInt()}",
                style = MaterialTheme.typography.displaySmall,
                color = PrimaryGreen
            )
            Text(
                text = "°",
                style = MaterialTheme.typography.headlineSmall,
                color = PrimaryGreen,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        // Direction label
        Text(
            text = directionLabel,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            fontWeight = FontWeight.Medium
        )
    }
}

fun azimuthToDirection(degrees: Float): String {
    val deg = ((degrees % 360) + 360) % 360
    return when {
        deg >= 337.5f || deg < 22.5f -> "N ↑"
        deg >= 22.5f && deg < 67.5f -> "NE ↗"
        deg >= 67.5f && deg < 112.5f -> "E →"
        deg >= 112.5f && deg < 157.5f -> "SE ↘"
        deg >= 157.5f && deg < 202.5f -> "S ↓"
        deg >= 202.5f && deg < 247.5f -> "SW ↙"
        deg >= 247.5f && deg < 292.5f -> "W ←"
        else -> "NW ↖"
    }
}

// ---- InfoCard (Section 6.7) ----

@Composable
fun InfoCard(
    deviceName: String,
    uwbData: UwbRealData,
    findingStatus: FindingStatus
) {
    val statusText = when (findingStatus) {
        FindingStatus.IDLE -> "Ready to search"
        FindingStatus.SENDING, FindingStatus.BOARD_STARTING -> "Waking base station..."
        FindingStatus.BOARD_STARTED, FindingStatus.RANGING -> "Starting ranging..."
        FindingStatus.SUCCESS -> "Near (Approx. ${String.format("%.1f", uwbData.distanceMeters)} m)"
        FindingStatus.FAILED -> "Write error — retry"
        FindingStatus.TIMEOUT -> "No response from tag"
        FindingStatus.UWB_ERROR -> "UWB ranging failure"
    }
    val statusColor = when {
        findingStatus == FindingStatus.SUCCESS && uwbData.distanceMeters <= 1.0f -> PrimaryGreen
        findingStatus == FindingStatus.SUCCESS -> StatusFar
        findingStatus in setOf(FindingStatus.FAILED, FindingStatus.TIMEOUT, FindingStatus.UWB_ERROR) -> StatusLost
        else -> TextSecondary
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .shadow(3.dp, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardGlass)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF0F1A15)),
                contentAlignment = Alignment.Center
            ) {
                Text("🔑", fontSize = 22.sp)
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column {
                Text(
                    text = deviceName,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "STATUS: $statusText",
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "Last Seen: Home (2 min ago)",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
        }
    }
}

// ---- Debug Log Panel ----

@Composable
fun DebugLogPanel(logs: List<String>) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .height(150.dp),
        color = SurfaceDark,
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

// ---- MyDeviceItem (Section 6.9) ----

private fun itemIcon(name: String): String {
    val lower = name.lowercase()
    return when {
        "key" in lower || "钥匙" in lower -> "🔑"
        "wallet" in lower || "钱包" in lower -> "👛"
        "backpack" in lower || "背包" in lower || "bag" in lower -> "🎒"
        "car" in lower || "车" in lower -> "🚗"
        "phone" in lower || "手机" in lower -> "📱"
        "watch" in lower || "手表" in lower -> "⌚"
        "glass" in lower || "眼镜" in lower -> "👓"
        "umbrella" in lower || "伞" in lower -> "☂️"
        "bike" in lower || "自行车" in lower -> "🚲"
        "luggage" in lower || "行李" in lower -> "🧳"
        "cat" in lower || "猫" in lower -> "🐱"
        "dog" in lower || "狗" in lower -> "🐶"
        "headphone" in lower || "耳机" in lower -> "🎧"
        "book" in lower || "书" in lower -> "📖"
        "remote" in lower || "遥控" in lower -> "📡"
        else -> "🏷️"
    }
}

@Composable
fun MyDeviceItem(name: String, isCurrent: Boolean, status: String, onClick: () -> Unit = {}) {
    val bgColor = if (isCurrent) CardGlass else CardDark
    val dotColor = if (isCurrent) StatusDotGreen else Color(0xFF2A332F)
    val shadowElevation = if (isCurrent) 2.dp else 0.dp

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp)
            .shadow(shadowElevation, RoundedCornerShape(14.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Item icon
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF0F1A15)),
                contentAlignment = Alignment.Center
            ) {
                Text(text = itemIcon(name), fontSize = 18.sp)
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = if (isCurrent) "Connected" else "Tap to connect",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isCurrent) StatusNear else TextMuted
                )
            }

            // Status dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }
    }
}
