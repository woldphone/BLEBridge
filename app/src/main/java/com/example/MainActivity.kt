package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.MyApplicationTheme
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme(darkTheme = false) { // Use modern clean light aesthetic matching requested spec
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFFFDFBFF) // Clean Slate Light Background
                ) {
                    BleBridgeDashboard()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BleBridgeDashboard() {
    val context = LocalContext.current

    // Collect states dynamically from BleBridgeService's companion object
    val isRunning by BleBridgeService.isServerRunning.collectAsStateWithLifecycle()
    val serverPort by BleBridgeService.serverPort.collectAsStateWithLifecycle()
    val wifiIp by BleBridgeService.wifiIp.collectAsStateWithLifecycle()
    val connectedClients by BleBridgeService.connectedClients.collectAsStateWithLifecycle()
    val bleState by BleBridgeService.bleConnectionState.collectAsStateWithLifecycle()
    val bleAddress by BleBridgeService.bleDeviceAddress.collectAsStateWithLifecycle()
    val currentLogLevel by BleBridgeService.logLevel.collectAsStateWithLifecycle()
    val logs by BleBridgeService.logs.collectAsStateWithLifecycle()
    val updateAvailable by BleBridgeService.updateAvailable.collectAsStateWithLifecycle()

    var portInput by remember { mutableStateOf(serverPort.toString()) }
    var dropdownExpanded by remember { mutableStateOf(false) }
    var showInstallDialog by remember { mutableStateOf(false) }

    // Sync input field when configuration state changes externally
    LaunchedEffect(serverPort) {
        if (portInput != serverPort.toString()) {
            portInput = serverPort.toString()
        }
    }

    // Permission handle definitions
    val permissionsToRequest = remember {
        mutableListOf<String>().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    var permissionsGranted by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val ok = results.all { it.value }
        permissionsGranted = ok
        if (!ok) {
            Toast.makeText(context, "Bluetooth Scanning, Connecting, and Notification permissions are required to operate the bridge.", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        val allOk = permissionsToRequest.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!allOk) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            permissionsGranted = true
        }
        BleBridgeService.checkForUpdates()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFFD6E2FF)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Logo",
                                tint = Color(0xFF001A40),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "BLE Bridge",
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF1A1C1E),
                                    fontSize = 18.sp,
                                    fontFamily = FontFamily.SansSerif
                                )
                                if (updateAvailable) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(RoundedCornerShape(50))
                                            .background(Color(0xFFBA1A1A))
                                    )
                                }
                            }
                            Text(
                                text = if (isRunning) "SERVICE ACTIVE" else "SERVICE INACTIVE",
                                fontWeight = FontWeight.Bold,
                                color = if (isRunning) Color(0xFF2E7D32) else Color(0xFF74777F),
                                fontSize = 10.sp,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                },
                actions = {
                    if (updateAvailable) {
                        IconButton(onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/woldphone/BLEBridge/releases/latest"))
                            context.startActivity(intent)
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Update Available", tint = Color(0xFFBA1A1A))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFFFDFBFF),
                    titleContentColor = Color(0xFF1A1C1E)
                )
            )
        },
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF3F3FA))
                    .border(width = 1.dp, color = Color(0xFFE1E2EC), shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val bleIndicatorColor = when (bleState) {
                            BleBridgeService.Companion.BleConnectionState.DISCONNECTED -> Color(0xFFBA1A1A)
                            BleBridgeService.Companion.BleConnectionState.CONNECTING -> Color(0xFFE65100)
                            BleBridgeService.Companion.BleConnectionState.CONNECTED -> Color(0xFF2E7D32)
                        }
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(RoundedCornerShape(50))
                                .background(bleIndicatorColor)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when (bleState) {
                                BleBridgeService.Companion.BleConnectionState.DISCONNECTED -> "BLE: DISCONNECTED"
                                BleBridgeService.Companion.BleConnectionState.CONNECTING -> "BLE: CONNECTING..."
                                BleBridgeService.Companion.BleConnectionState.CONNECTED -> "BLE: CONNECTED"
                            },
                            color = Color(0xFF1A1C1E),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.5.sp
                        )
                    }

                    if (bleState == BleBridgeService.Companion.BleConnectionState.CONNECTED) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFFD6E2FF))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = bleAddress ?: "UNKNOWN",
                                color = Color(0xFF001A40),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets.statusBars
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            
            // --- Connection Manager Card ---
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF3F3FA)),
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFFE1E2EC), RoundedCornerShape(28.dp))
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column {
                            Text(
                                text = "SERVER CONFIGURATION",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF44474F),
                                fontSize = 11.sp,
                                letterSpacing = 0.5.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "IP: $wifiIp",
                                color = Color(0xFF1A1C1E),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        
                        // ON/OFF toggle switch for the network server
                        Switch(
                            checked = isRunning,
                            onCheckedChange = { start ->
                                if (!permissionsGranted) {
                                    Toast.makeText(context, "Permissions are required to launch the service", Toast.LENGTH_SHORT).show()
                                    return@Switch
                                }
                                val portVal = portInput.toIntOrNull() ?: 8080
                                val intent = Intent(context, BleBridgeService::class.java).apply {
                                    if (start) {
                                        action = BleBridgeService.ACTION_START_SERVER
                                        putExtra(BleBridgeService.EXTRA_PORT, portVal)
                                    } else {
                                        action = BleBridgeService.ACTION_STOP_SERVER
                                    }
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    context.startForegroundService(intent)
                                } else {
                                    context.startService(intent)
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF0056D2),
                                uncheckedThumbColor = Color(0xFF74777F),
                                uncheckedTrackColor = Color(0xFFE1E2EC)
                            ),
                            modifier = Modifier.testTag("server_toggle")
                        )
                    }

                    HorizontalDivider(color = Color(0xFFE1E2EC))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // TCP Port input
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "TCP PORT",
                                color = Color(0xFF44474F),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                            )
                            
                            TextField(
                                value = portInput,
                                onValueChange = { input ->
                                    if (input.all { it.isDigit() } && input.length <= 5) {
                                        portInput = input
                                    }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = TextFieldDefaults.colors(
                                    focusedTextColor = Color(0xFF1A1C1E),
                                    unfocusedTextColor = Color(0xFF1A1C1E),
                                    disabledTextColor = Color(0xFF74777F),
                                    focusedContainerColor = Color(0xFFE1E2EC),
                                    unfocusedContainerColor = Color(0xFFE1E2EC),
                                    disabledContainerColor = Color(0xFFE1E2EC),
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent
                                ),
                                singleLine = true,
                                enabled = !isRunning, // Cannot modify port while running
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("server_port_input")
                            )
                        }

                        // Verbosity selector dropdown
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "VERBOSITY",
                                color = Color(0xFF44474F),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                            )
                            
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFFE1E2EC))
                                    .clickable { dropdownExpanded = true }
                                    .padding(horizontal = 12.dp)
                                    .testTag("log_level_dropdown"),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = when (currentLogLevel) {
                                            BleBridgeService.Companion.LogLevel.LEVEL1 -> "LEVEL 1"
                                            BleBridgeService.Companion.LogLevel.LEVEL2 -> "LEVEL 2"
                                            BleBridgeService.Companion.LogLevel.LEVEL3 -> "LEVEL 3"
                                        },
                                        color = Color(0xFF1A1C1E),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "▼",
                                        color = Color(0xFF44474F),
                                        fontSize = 10.sp
                                    )
                                }

                                DropdownMenu(
                                    expanded = dropdownExpanded,
                                    onDismissRequest = { dropdownExpanded = false },
                                    modifier = Modifier.background(Color(0xFFF3F3FA))
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("LEVEL 1: Errors Only", color = Color(0xFF1A1C1E), fontSize = 12.sp) },
                                        onClick = {
                                            BleBridgeService.setLogLevel(BleBridgeService.Companion.LogLevel.LEVEL1)
                                            dropdownExpanded = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("LEVEL 2: Errors & Connect Status", color = Color(0xFF1A1C1E), fontSize = 12.sp) },
                                        onClick = {
                                            BleBridgeService.setLogLevel(BleBridgeService.Companion.LogLevel.LEVEL2)
                                            dropdownExpanded = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("LEVEL 3: Full Telemetry", color = Color(0xFF1A1C1E), fontSize = 12.sp) },
                                        onClick = {
                                            BleBridgeService.setLogLevel(BleBridgeService.Companion.LogLevel.LEVEL3)
                                            dropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = Color(0xFFE1E2EC))

                    Button(
                        onClick = { showInstallDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD6E2FF), contentColor = Color(0xFF001A40)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Setup Python Tools", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }

            if (showInstallDialog) {
                AlertDialog(
                    onDismissRequest = { showInstallDialog = false },
                    title = { Text("Termux Setup") },
                    text = {
                        Column {
                            Text("Copy and paste this command into Termux to install the BLE Bridge scripts:", fontSize = 14.sp)
                            Spacer(modifier = Modifier.height(12.dp))
                            Surface(
                                color = Color(0xFF1C1B1F),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "curl -L https://raw.githubusercontent.com/woldphone/BLEBridge/main/ble_tui.py -o ble_tui.py && curl -L https://raw.githubusercontent.com/woldphone/BLEBridge/main/uuids.json -o uuids.json",
                                    color = Color(0xFF00FF00),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showInstallDialog = false }) {
                            Text("DONE")
                        }
                    }
                )
            }

            // --- Connected Clients (Terminal) Section ---
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.45f)
                    .border(1.dp, Color(0xFFE1E2EC), RoundedCornerShape(28.dp))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Active Clients",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1A1C1E),
                                fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF0056D2))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = connectedClients.size.toString(),
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        if (connectedClients.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    connectedClients.forEach { client ->
                                        BleBridgeService.disconnectClientExternal(client.id)
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "CLEAR ALL",
                                    color = Color(0xFF0056D2),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    if (connectedClients.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFFF3F3FA), RoundedCornerShape(16.dp))
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No TCP sockets listening.\nConnect with 'nc $wifiIp $serverPort' in Termux.",
                                color = Color(0xFF44474F),
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 16.sp
                            )
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(connectedClients) { client ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFFF3F3FA), RoundedCornerShape(12.dp))
                                        .border(1.dp, Color(0xFFE1E2EC), RoundedCornerShape(12.dp))
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = client.id,
                                            color = Color(0xFF1A1C1E),
                                            fontSize = 13.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Connected: ${formatTime(client.connectedAt)}",
                                            color = Color(0xFF74777F),
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }

                                    Button(
                                        onClick = { BleBridgeService.disconnectClientExternal(client.id) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFFFFDAD6),
                                            contentColor = Color(0xFF410002)
                                        ),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier
                                            .height(32.dp)
                                            .testTag("boot_client_button_${client.id}")
                                    ) {
                                        Text(
                                            text = "BOOT",
                                            color = Color(0xFF410002),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // --- Console Log Section ---
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1B1F)),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.55f)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "CONSOLE OUTPUT",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFC4C6D0),
                                fontSize = 10.sp,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "LIVE",
                                color = Color(0xFF4CAF50),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Clear logs icon
                        IconButton(
                            onClick = { BleBridgeService.clearLogs() },
                            modifier = Modifier
                                .size(24.dp)
                                .testTag("clear_logs_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Clear logs",
                                tint = Color(0xFFC4C6D0),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    val listState = rememberLazyListState()
                    // Auto-scroll logs to bottom if they change
                    LaunchedEffect(logs.size) {
                        if (logs.isNotEmpty()) {
                            listState.animateScrollToItem(logs.size - 1)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF1C1B1F))
                            .padding(top = 4.dp)
                    ) {
                        if (logs.isEmpty()) {
                            Text(
                                text = "System logs are empty.",
                                color = Color(0xFF74777F),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(logs) { msg ->
                                    val logColor = when (msg.level) {
                                        BleBridgeService.Companion.LogLevel.LEVEL1 -> Color(0xFFFFB4AB) // light elegant red
                                        BleBridgeService.Companion.LogLevel.LEVEL2 -> Color(0xFFD6E2FF) // elegant light blue
                                        BleBridgeService.Companion.LogLevel.LEVEL3 -> Color(0xFFFFE082) // warm elegant amber
                                    }
                                    Text(
                                        text = "[${formatTimeHMS(msg.timestamp)}] ${msg.message}",
                                        color = logColor,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        lineHeight = 15.sp,
                                        modifier = Modifier.padding(vertical = 1.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(timestamp: Long): String {
    val formatter = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    return formatter.format(Date(timestamp))
}

private fun formatTimeHMS(timestamp: Long): String {
    val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    return formatter.format(Date(timestamp))
}
