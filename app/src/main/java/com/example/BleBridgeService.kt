package com.example

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONException
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class BleBridgeService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val activeClients = ConcurrentHashMap<String, ClientHandler>()

    // Sequential GATT task queue
    private val taskChannel = Channel<BleTask>(Channel.UNLIMITED)
    private var activeTaskDeferred: CompletableDeferred<Boolean>? = null

    private var isScanning = false
    private var scanCallbackInternal: ScanCallback? = null

    private val companionBleState: Companion.BleConnectionState
        get() = _bleConnectionState.value

    private val companionBleAddress: String?
        get() = _bleDeviceAddress.value

    override fun onCreate() {
        super.onCreate()
        instance = this
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        // Initialize state
        _wifiIp.value = getLocalIpAddress()
        
        // Start processing sequential BLE task queue
        startQueueProcessor()
        
        log(LogLevel.LEVEL2, "BLE Bridge Service created.")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // Started service, not bound
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannelAndStartForeground()

        val action = intent?.action
        if (action == ACTION_START_SERVER) {
            val port = intent.getIntExtra(EXTRA_PORT, 8080)
            startTcpServer(port)
        } else if (action == ACTION_STOP_SERVER) {
            stopTcpServer()
            closeGattClean()
            stopBleScan()
            stopSelf()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        log(LogLevel.LEVEL2, "BLE Bridge Service destroying...")
        cleanupServer()
        closeGattClean()
        stopBleScan()
        serviceScope.cancel()
        instance = null
        super.onDestroy()
    }

    // --- Foreground Service Notification ---

    private fun createNotificationChannelAndStartForeground() {
        val channelId = "BleBridgeServiceChannel"
        val channelName = "BLE Bridge Background Service"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(chan)
        }

        val stopIntent = Intent(this, BleBridgeService::class.java).apply {
            action = ACTION_STOP_SERVER
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setContentTitle("BLE to Network Bridge")
            .setContentText("Listening socket & BLE state engines active")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Gateway", stopPendingIntent)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notificationBuilder.build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notificationBuilder.build())
            }
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is android.app.ForegroundServiceStartNotAllowedException) {
                log(LogLevel.LEVEL1, "Foreground service start not allowed: ${e.message}")
            } else {
                log(LogLevel.LEVEL1, "Error starting foreground service: ${e.message}")
            }
        }
    }

    // --- TCP Socket Server Support ---

    private fun startTcpServer(port: Int) {
        if (serverJob != null && serverJob?.isActive == true) {
            log(LogLevel.LEVEL1, "Server already running on port ${_serverPort.value}")
            return
        }

        _serverPort.value = port
        _wifiIp.value = getLocalIpAddress()

        serverJob = serviceScope.launch(Dispatchers.IO) {
            try {
                val ssocket = ServerSocket(port)
                serverSocket = ssocket
                _isServerRunning.value = true
                log(LogLevel.LEVEL2, "TCP Gateway Server started on 0.0.0.0:$port (Wifi IP: ${getLocalIpAddress()})")

                while (isActive) {
                    val clientSocket = ssocket.accept()
                    val clientIp = clientSocket.inetAddress.hostAddress ?: "Unknown"
                    val clientPort = clientSocket.port
                    val clientId = "$clientIp:$clientPort"

                    val handler = ClientHandler(clientId, clientSocket)
                    activeClients[clientId] = handler
                    handler.start()
                }
            } catch (e: Exception) {
                log(LogLevel.LEVEL2, "TCP Server socket closed: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) {
                    cleanupServer()
                }
            }
        }
    }

    private fun stopTcpServer() {
        log(LogLevel.LEVEL2, "Stopping TCP Gateway Server...")
        cleanupServer()
    }

    private fun cleanupServer() {
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        serverSocket = null

        activeClients.values.forEach { client ->
            client.close()
        }
        activeClients.clear()
        updateClientsList()

        _isServerRunning.value = false
        serverJob?.cancel()
        serverJob = null
        log(LogLevel.LEVEL2, "TCP Gateway Server stopped.")
    }

    private fun updateClientsList() {
        val list = activeClients.values.map { client ->
            ClientInfo(id = client.id, ip = client.ip, port = client.port)
        }
        _connectedClients.value = list
    }

    fun bootClientInternal(clientId: String) {
        val client = activeClients[clientId]
        if (client != null) {
            log(LogLevel.LEVEL2, "Booting network client by request: $clientId")
            client.close()
        }
    }

    fun broadcastJson(jsonString: String) {
        activeClients.values.forEach { client ->
            client.send(jsonString)
        }
    }

    // --- BLE Scan Implementation ---

    fun startBleScan(client: ClientHandler) {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            client.send(errorResponse("Bluetooth LE Scanner not available. Verify BT is ON."))
            log(LogLevel.LEVEL1, "Scan failed: Bluetooth LE Scanner is template null")
            return
        }
        if (isScanning) {
            client.send(errorResponse("BLE Scan already in progress."))
            return
        }

        isScanning = true
        log(LogLevel.LEVEL2, "Starting BLE discovery scan (10 seconds)...")

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = device.name ?: "Unknown"
                val address = device.address
                val rssi = result.rssi

                // Enhanced device type detection
                var deviceType = "Generic BLE"
                result.scanRecord?.let { record ->
                    val manufacturerData = record.manufacturerSpecificData
                    if (manufacturerData.size() > 0) {
                        val firstKey = manufacturerData.keyAt(0)
                        deviceType = when (firstKey) {
                            0x004C -> "Apple Device"
                            0x0006 -> "Microsoft/Windows"
                            0x0075 -> "Samsung"
                            0x00E0 -> "Google/Android"
                            else -> "Manufacturer ID: 0x${Integer.toHexString(firstKey).uppercase()}"
                        }
                    }
                }

                val payload = JSONObject().apply {
                    put("status", "scan_result")
                    put("timestamp", System.currentTimeMillis())
                    put("device_name", name)
                    put("device_address", address)
                    put("rssi", rssi)
                    put("device_type", deviceType)
                }
                broadcastJson(payload.toString())
                log(LogLevel.LEVEL3, "Scan Result -> Name: $name, Addr: $address, RSSI: $rssi")
            }

            override fun onScanFailed(errorCode: Int) {
                log(LogLevel.LEVEL1, "Scan failed with hardware error code: $errorCode")
                isScanning = false
                broadcastJson(errorResponse("BLE Scan failed with hardware error: $errorCode"))
            }
        }

        scanCallbackInternal = callback
        try {
            scanner.startScan(callback)
            serviceScope.launch {
                delay(10000L)
                if (isScanning) {
                    stopBleScan()
                }
            }
        } catch (e: SecurityException) {
            log(LogLevel.LEVEL1, "Security Exception during scan setup: ${e.message}")
            isScanning = false
            client.send(errorResponse("Security Exception: BLE Scan permission is missing or revoked"))
        }
    }

    fun stopBleScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        val callback = scanCallbackInternal
        if (scanner != null && callback != null && isScanning) {
            try {
                scanner.stopScan(callback)
                log(LogLevel.LEVEL2, "BLE Scan stopped.")
            } catch (e: SecurityException) {
                log(LogLevel.LEVEL1, "Security Exception stopping scan: ${e.message}")
            }
        }
        isScanning = false
        scanCallbackInternal = null
    }

    // --- BLE Connection & GATT Core ---

    fun connectBle(address: String, client: ClientHandler) {
        if (companionBleState != Companion.BleConnectionState.DISCONNECTED) {
            client.send(errorResponse("Already connecting or connected to ${companionBleAddress ?: "host"}"))
            return
        }

        val device = bluetoothAdapter?.getRemoteDevice(address)
        if (device == null) {
            client.send(errorResponse("Invalid MAC address or remote hardware: $address"))
            return
        }

        log(LogLevel.LEVEL2, "Connecting to BLE peripheral: $address")
        setBleConnectionState(BleConnectionState.CONNECTING, address)

        try {
            // Initiate GATT handle
            bluetoothGatt = device.connectGatt(this, false, gattCallback)
        } catch (e: SecurityException) {
            log(LogLevel.LEVEL1, "Security exception connecting: Bluetooth connect permission missing")
            setBleConnectionState(BleConnectionState.DISCONNECTED, null)
            client.send(errorResponse("Security Exception: Bluetooth connect permission missing"))
        } catch (e: Exception) {
            log(LogLevel.LEVEL1, "Unexpected connection failure: ${e.message}")
            setBleConnectionState(BleConnectionState.DISCONNECTED, null)
            client.send(errorResponse("Connection error: ${e.message}"))
        }
    }

    fun disconnectBle(client: ClientHandler) {
        if (companionBleState == Companion.BleConnectionState.DISCONNECTED) {
            client.send(errorResponse("No active BLE peripheral is connected"))
            return
        }
        log(LogLevel.LEVEL2, "Disconnect request generated by client: ${client.id}")
        closeGattClean()
    }

    fun closeGattClean() {
        val gatt = bluetoothGatt
        if (gatt != null) {
            log(LogLevel.LEVEL2, "Executing GATT leak protection & handle teardown...")
            try {
                gatt.disconnect()
            } catch (e: SecurityException) {
                log(LogLevel.LEVEL1, "Security Exception in gatt.disconnect: ${e.message}")
            } catch (e: Exception) {
                log(LogLevel.LEVEL1, "Error calling disconnect: ${e.message}")
            }

            try {
                gatt.close()
            } catch (e: Exception) {
                log(LogLevel.LEVEL1, "Error calling close: ${e.message}")
            }
            bluetoothGatt = null
        }

        setBleConnectionState(BleConnectionState.DISCONNECTED, null)
        broadcastJson(createTelemetryJson("disconnected", null, null, null, null))
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val addr = gatt.device.address
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    log(LogLevel.LEVEL2, "BLE state connected! Initiating Auto-MTU Negotiation...")
                    setBleConnectionState(BleConnectionState.CONNECTED, addr)

                    // Broadcast connected event to socket clients
                    val telemetry = createTelemetryJson("connected", addr, null, null, null)
                    broadcastJson(telemetry)

                    // Auto-MTU Negotiation immediately
                    try {
                        log(LogLevel.LEVEL2, "Requesting hardware MTU of 512...")
                        val ok = gatt.requestMtu(512)
                        if (!ok) {
                            log(LogLevel.LEVEL1, "MTU request could not be pre-scheduled. Discovering services with default MTU...")
                            gatt.discoverServices()
                        }
                    } catch (e: SecurityException) {
                        log(LogLevel.LEVEL1, "Permission error requesting MTU: ${e.message}")
                        gatt.discoverServices()
                    }
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    log(LogLevel.LEVEL2, "BLE peripheral disconnected cleanly.")
                    closeGattClean()
                }
            } else {
                log(LogLevel.LEVEL1, "GATT status error: $status (newState=$newState). Safe disconnect triggered.")
                closeGattClean()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log(LogLevel.LEVEL2, "Negotiated packet MTU successfully: $mtu bytes")
            } else {
                log(LogLevel.LEVEL1, "MTU auto-negotiation failed with status: $status")
            }

            // Discover services always after MTU change (success or fail)
            try {
                log(LogLevel.LEVEL2, "Initiating service discovery...")
                gatt.discoverServices()
            } catch (e: SecurityException) {
                log(LogLevel.LEVEL1, "Security Exception in discoverServices: ${e.message}")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log(LogLevel.LEVEL2, "BLE GATT services discovered successfully!")

                val servicesArray = org.json.JSONArray()
                gatt.services.forEach { service ->
                    val sObj = JSONObject()
                    sObj.put("uuid", service.uuid.toString())

                    val charsArray = org.json.JSONArray()
                    service.characteristics.forEach { char ->
                        val cObj = JSONObject()
                        cObj.put("uuid", char.uuid.toString())
                        cObj.put("properties", getCharPropertiesString(char.properties))
                        charsArray.put(cObj)
                    }
                    sObj.put("characteristics", charsArray)
                    servicesArray.put(sObj)
                }

                val payload = JSONObject().apply {
                    put("status", "services_discovered")
                    put("timestamp", System.currentTimeMillis())
                    put("device", gatt.device.address)
                    put("services", servicesArray)
                }
                broadcastJson(payload.toString())

                // Dump telemetry to logs
                gatt.services.forEach { service ->
                    log(LogLevel.LEVEL3, "Service: ${service.uuid}")
                    service.characteristics.forEach { char ->
                        val props = getCharPropertiesString(char.properties)
                        log(LogLevel.LEVEL3, "  -> Char: ${char.uuid} ($props)")
                    }
                }
            } else {
                log(LogLevel.LEVEL1, "Service discovery failed with status $status")
                broadcastJson(errorResponse("Service discovery failed with status $status"))
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            handleCharacteristicRead(gatt, characteristic, value, status)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            handleCharacteristicRead(gatt, characteristic, characteristic.value ?: byteArrayOf(), status)
        }

        private fun handleCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            val ok = (status == BluetoothGatt.GATT_SUCCESS)
            log(LogLevel.LEVEL3, "GATT Read Callback -> Status=$status, Size=${value.size}")

            if (ok) {
                val sUuid = characteristic.service?.uuid?.toString() ?: ""
                val cUuid = characteristic.uuid?.toString() ?: ""
                val telemetry = createTelemetryJson("read_success", gatt.device.address, sUuid, cUuid, value)
                broadcastJson(telemetry)
            }

            activeTaskDeferred?.complete(ok)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicChanged(gatt, characteristic, value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleCharacteristicChanged(gatt, characteristic, characteristic.value ?: byteArrayOf())
        }

        private fun handleCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val sUuid = characteristic.service?.uuid?.toString() ?: ""
            val cUuid = characteristic.uuid?.toString() ?: ""
            log(LogLevel.LEVEL3, "GATT Telemetry Stream Rx from [$cUuid]")

            val telemetry = createTelemetryJson("notification_received", gatt.device.address, sUuid, cUuid, value)
            broadcastJson(telemetry)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val ok = (status == BluetoothGatt.GATT_SUCCESS)
            log(LogLevel.LEVEL3, "GATT Write Callback -> Status=$status")

            if (ok) {
                val sUuid = characteristic.service?.uuid?.toString() ?: ""
                val cUuid = characteristic.uuid?.toString() ?: ""
                val telemetry = createTelemetryJson("write_success", gatt.device.address, sUuid, cUuid, byteArrayOf())
                broadcastJson(telemetry)
            }

            activeTaskDeferred?.complete(ok)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val ok = (status == BluetoothGatt.GATT_SUCCESS)
            log(LogLevel.LEVEL3, "GATT Descriptor Write Callback -> Status=$status")
            activeTaskDeferred?.complete(ok)
        }

        override fun onServiceChanged(gatt: BluetoothGatt) {
            log(LogLevel.LEVEL2, "GATT Service Changed. Rediscovering...")
            try {
                gatt.discoverServices()
            } catch (e: SecurityException) {
                log(LogLevel.LEVEL1, "Security Exception in discoverServices: ${e.message}")
            }
        }
    }

    // --- Sequential BLE Hardware Queue Processing ---

    private fun startQueueProcessor() {
        serviceScope.launch(Dispatchers.Default) {
            for (task in taskChannel) {
                executeTask(task)
            }
        }
    }

    private suspend fun executeTask(task: BleTask) {
        val deferred = CompletableDeferred<Boolean>()
        activeTaskDeferred = deferred

        val initiated = when (task) {
            is BleTask.Read -> performRead(task)
            is BleTask.Write -> performWrite(task)
            is BleTask.RegisterNotify -> performNotify(task)
        }

        if (!initiated) {
            task.onResult(false, null)
            activeTaskDeferred = null
            return
        }

        // Wait for async callback with 3-second timeout fallback
        try {
            withTimeout(3000L) {
                deferred.await()
            }
            task.onResult(true, null)
        } catch (e: TimeoutCancellationException) {
            log(LogLevel.LEVEL1, "BLE Hardware task timeout (3s) triggered for: ${task.charUuid}")
            task.onResult(false, null)
        } finally {
            activeTaskDeferred = null
        }
    }

    private fun performRead(task: BleTask.Read): Boolean {
        val gatt = bluetoothGatt ?: return false
        val s = gatt.getService(task.serviceUuid) ?: return false
        val c = s.getCharacteristic(task.charUuid) ?: return false

        return try {
            log(LogLevel.LEVEL3, "Initiating hardware characteristic read: ${task.charUuid}")
            gatt.readCharacteristic(c)
        } catch (e: SecurityException) {
            log(LogLevel.LEVEL1, "SecurityException initiating read: ${e.message}")
            false
        } catch (e: Exception) {
            log(LogLevel.LEVEL1, "Error initiating gatt.readCharacteristic: ${e.message}")
            false
        }
    }

    private fun performWrite(task: BleTask.Write): Boolean {
        val gatt = bluetoothGatt ?: return false
        val s = gatt.getService(task.serviceUuid) ?: return false
        val c = s.getCharacteristic(task.charUuid) ?: return false

        return try {
            log(LogLevel.LEVEL3, "Initiating hardware characteristic write: ${task.charUuid}")
            c.writeType = if (task.withResponse) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val code = gatt.writeCharacteristic(c, task.data, c.writeType)
                code == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.value = task.data
                @Suppress("DEPRECATION")
                val ok = gatt.writeCharacteristic(c)
                ok
            }
        } catch (e: SecurityException) {
            log(LogLevel.LEVEL1, "SecurityException initiating write: ${e.message}")
            false
        } catch (e: Exception) {
            log(LogLevel.LEVEL1, "Error initiating gatt.writeCharacteristic: ${e.message}")
            false
        }
    }

    private fun performNotify(task: BleTask.RegisterNotify): Boolean {
        val gatt = bluetoothGatt ?: return false
        val s = gatt.getService(task.serviceUuid) ?: return false
        val c = s.getCharacteristic(task.charUuid) ?: return false

        return try {
            log(LogLevel.LEVEL3, "Enabling locally notifications for: ${task.charUuid} -> ${task.enable}")
            val okLocally = gatt.setCharacteristicNotification(c, task.enable)
            if (!okLocally) {
                log(LogLevel.LEVEL1, "GATT setCharacteristicNotification locally returned failure")
                return false
            }

            val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
            val desc = c.getDescriptor(cccdUuid)
            if (desc == null) {
                log(LogLevel.LEVEL1, "CCCD descriptor not found on char: ${task.charUuid}")
                return false
            }

            val value = if (task.enable) {
                if ((c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                } else {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                }
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val code = gatt.writeDescriptor(desc, value)
                code == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                desc.value = value
                @Suppress("DEPRECATION")
                val ok = gatt.writeDescriptor(desc)
                ok
            }
        } catch (e: SecurityException) {
            log(LogLevel.LEVEL1, "SecurityException writing CCCD descriptor: ${e.message}")
            false
        } catch (e: Exception) {
            log(LogLevel.LEVEL1, "Error writing CCCD descriptor: ${e.message}")
            false
        }
    }

    // --- JSON Protocol Handlers ---

    fun handleClientCommand(client: ClientHandler, jsonLine: String) {
        try {
            val json = JSONObject(jsonLine)
            val command = json.optString("command", "").lowercase()
            if (command.isEmpty()) {
                client.send(errorResponse("Missing mandatory 'command' field"))
                return
            }

            when (command) {
                "scan" -> startBleScan(client)
                "connect" -> {
                    val address = json.optString("address", "")
                    if (address.isEmpty()) {
                        client.send(errorResponse("Connect command requires 'address' field"))
                    } else {
                        connectBle(address, client)
                    }
                }
                "disconnect" -> disconnectBle(client)
                "read" -> {
                    val service = json.optString("service", "")
                    val characteristic = json.optString("characteristic", "")
                    if (service.isEmpty() || characteristic.isEmpty()) {
                        client.send(errorResponse("Read command requires 'service' and 'characteristic' UUIDs"))
                    } else {
                        readCharacteristicInline(service, characteristic, client)
                    }
                }
                "write" -> {
                    val service = json.optString("service", "")
                    val characteristic = json.optString("characteristic", "")
                    val payload = json.optString("payload_hex", "")
                    val withDesc = json.optBoolean("with_response", true)
                    if (service.isEmpty() || characteristic.isEmpty() || payload.isEmpty()) {
                        client.send(errorResponse("Write command requires 'service', 'characteristic', and 'payload_hex'"))
                    } else {
                        writeCharacteristicInline(service, characteristic, payload, withDesc, client)
                    }
                }
                "notify" -> {
                    val service = json.optString("service", "")
                    val characteristic = json.optString("characteristic", "")
                    val enable = json.optBoolean("enable", true)
                    if (service.isEmpty() || characteristic.isEmpty()) {
                        client.send(errorResponse("Notify command requires 'service' and 'characteristic' UUIDs"))
                    } else {
                        toggleNotifyInline(service, characteristic, enable, client)
                    }
                }
                "reset_bluetooth" -> resetBluetoothDevice(client)
                "ping" -> client.send(JSONObject().apply {
                    put("status", "pong")
                    put("timestamp", System.currentTimeMillis())
                }.toString())
                "request_mtu" -> {
                    val mtu = json.optInt("mtu", 512)
                    requestMtuInline(mtu, client)
                }
                "discover" -> {
                    val g = bluetoothGatt
                    if (g == null || companionBleState != Companion.BleConnectionState.CONNECTED) {
                        client.send(errorResponse("BLE peripheral is currently not connected"))
                    } else {
                        log(LogLevel.LEVEL2, "Manual service discovery requested.")
                        try {
                            g.discoverServices()
                            client.send(JSONObject().apply {
                                put("status", "command_success")
                                put("command", "discover")
                                put("timestamp", System.currentTimeMillis())
                            }.toString())
                        } catch (e: SecurityException) {
                            client.send(errorResponse("Security Exception: Bluetooth connect permission missing"))
                        }
                    }
                }
                else -> client.send(errorResponse("Unrecognized command parameter: $command"))
            }
        } catch (e: JSONException) {
            client.send(errorResponse("JSON parsing syntax error: ${e.message}"))
        } catch (e: Exception) {
            client.send(errorResponse("Fatal command handling exception: ${e.message}"))
        }
    }

    private fun readCharacteristicInline(serviceStr: String, characteristicStr: String, client: ClientHandler) {
        val g = bluetoothGatt
        if (g == null || companionBleState != Companion.BleConnectionState.CONNECTED) {
            client.send(errorResponse("BLE peripheral is currently not connected"))
            return
        }

        try {
            val sUuid = UUID.fromString(serviceStr)
            val cUuid = UUID.fromString(characteristicStr)
            val s = g.getService(sUuid)
            if (s == null) {
                client.send(errorResponse("ServiceUUID not found on peripheral: $serviceStr"))
                return
            }
            val c = s.getCharacteristic(cUuid)
            if (c == null) {
                client.send(errorResponse("CharacteristicUUID not found in service: $characteristicStr"))
                return
            }

            val task = BleTask.Read(sUuid, cUuid) { ok, _ ->
                if (ok) {
                    client.send(JSONObject().apply {
                        put("status", "command_success")
                        put("command", "read")
                        put("timestamp", System.currentTimeMillis())
                    }.toString())
                } else {
                    client.send(errorResponse("Read operation failed on BLE hardware or timed out."))
                }
            }
            taskChannel.trySend(task)
            log(LogLevel.LEVEL3, "Read request queued for $characteristicStr")
        } catch (e: Exception) {
            client.send(errorResponse("Unexpected read preparation exception: ${e.message}"))
        }
    }

    private fun writeCharacteristicInline(
        serviceStr: String,
        characteristicStr: String,
        hexPayload: String,
        withDesc: Boolean,
        client: ClientHandler
    ) {
        val g = bluetoothGatt
        if (g == null || companionBleState != Companion.BleConnectionState.CONNECTED) {
            client.send(errorResponse("BLE peripheral is currently not connected"))
            return
        }

        try {
            val sUuid = UUID.fromString(serviceStr)
            val cUuid = UUID.fromString(characteristicStr)
            val s = g.getService(sUuid)
            if (s == null) {
                client.send(errorResponse("ServiceUUID not found on peripheral: $serviceStr"))
                return
            }
            val c = s.getCharacteristic(cUuid)
            if (c == null) {
                client.send(errorResponse("CharacteristicUUID not found in service: $characteristicStr"))
                return
            }

            val data = hexToBytes(hexPayload)
            val task = BleTask.Write(sUuid, cUuid, data, withDesc) { ok, _ ->
                if (ok) {
                    client.send(JSONObject().apply {
                        put("status", "command_success")
                        put("command", "write")
                        put("timestamp", System.currentTimeMillis())
                    }.toString())
                } else {
                    client.send(errorResponse("Write operation failed on BLE hardware or timed out."))
                }
            }
            taskChannel.trySend(task)
            log(LogLevel.LEVEL3, "Write request queued for $characteristicStr with response=$withDesc")
        } catch (e: Exception) {
            client.send(errorResponse("Unexpected write preparation exception: ${e.message}"))
        }
    }

    private fun toggleNotifyInline(
        serviceStr: String,
        characteristicStr: String,
        enable: Boolean,
        client: ClientHandler
    ) {
        val g = bluetoothGatt
        if (g == null || companionBleState != Companion.BleConnectionState.CONNECTED) {
            client.send(errorResponse("BLE peripheral is currently not connected"))
            return
        }

        try {
            val sUuid = UUID.fromString(serviceStr)
            val cUuid = UUID.fromString(characteristicStr)
            val s = g.getService(sUuid)
            if (s == null) {
                client.send(errorResponse("ServiceUUID not found on peripheral: $serviceStr"))
                return
            }
            val c = s.getCharacteristic(cUuid)
            if (c == null) {
                client.send(errorResponse("CharacteristicUUID not found in service: $characteristicStr"))
                return
            }

            val task = BleTask.RegisterNotify(sUuid, cUuid, enable) { ok, _ ->
                if (ok) {
                    client.send(JSONObject().apply {
                        put("status", "command_success")
                        put("command", if (enable) "notify_enable" else "notify_disable")
                        put("timestamp", System.currentTimeMillis())
                    }.toString())
                } else {
                    client.send(errorResponse("Notification toggle operation failed or timed out."))
                }
            }
            taskChannel.trySend(task)
            log(LogLevel.LEVEL3, "Notify register queued for $characteristicStr enable=$enable")
        } catch (e: Exception) {
            client.send(errorResponse("Unexpected notification toggle preparation exception: ${e.message}"))
        }
    }

    private fun resetBluetoothDevice(client: ClientHandler) {
        log(LogLevel.LEVEL2, "Clearing local gateway Bluetooth stack indices...")
        closeGattClean()
        stopBleScan()
        client.send(JSONObject().apply {
            put("status", "reset_success")
            put("timestamp", System.currentTimeMillis())
            put("message", "Bluetooth GATT adapters/handles closed cleanly.")
        }.toString())
    }

    private fun requestMtuInline(mtu: Int, client: ClientHandler) {
        val g = bluetoothGatt
        if (g == null || companionBleState != Companion.BleConnectionState.CONNECTED) {
            client.send(errorResponse("BLE peripheral is currently not connected"))
            return
        }
        try {
            log(LogLevel.LEVEL2, "Requesting hardware MTU of $mtu...")
            val ok = g.requestMtu(mtu)
            if (ok) {
                client.send(JSONObject().apply {
                    put("status", "command_success")
                    put("command", "request_mtu")
                    put("mtu", mtu)
                    put("timestamp", System.currentTimeMillis())
                }.toString())
            } else {
                client.send(errorResponse("MTU request could not be pre-scheduled."))
            }
        } catch (e: SecurityException) {
            client.send(errorResponse("Security Exception: Bluetooth connect permission missing"))
        }
    }

    // --- Static state mappings & UI contracts ---

    companion object {
        const val ACTION_START_SERVER = "com.example.action.START_SERVER"
        const val ACTION_STOP_SERVER = "com.example.action.STOP_SERVER"
        const val EXTRA_PORT = "com.example.extra.PORT"
        const val NOTIFICATION_ID = 10101

        private var instance: BleBridgeService? = null

        enum class BleConnectionState {
            DISCONNECTED,
            CONNECTING,
            CONNECTED
        }

        enum class LogLevel {
            LEVEL1, // Errors Only
            LEVEL2, // Standard
            LEVEL3  // Full Telemetry
        }

        data class ClientInfo(val id: String, val ip: String, val port: Int, val connectedAt: Long = System.currentTimeMillis())
        data class LogMessage(val timestamp: Long, val level: LogLevel, val message: String)

        private val _isServerRunning = MutableStateFlow(false)
        val isServerRunning: StateFlow<Boolean> = _isServerRunning.asStateFlow()

        private val _serverPort = MutableStateFlow(8080)
        val serverPort: StateFlow<Int> = _serverPort.asStateFlow()

        private val _wifiIp = MutableStateFlow("Disconnected / No Wifi")
        val wifiIp: StateFlow<String> = _wifiIp.asStateFlow()

        private val _connectedClients = MutableStateFlow<List<ClientInfo>>(emptyList())
        val connectedClients: StateFlow<List<ClientInfo>> = _connectedClients.asStateFlow()

        private val _bleConnectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
        val bleConnectionState: StateFlow<BleConnectionState> = _bleConnectionState.asStateFlow()

        private val _bleDeviceAddress = MutableStateFlow<String?>(null)
        val bleDeviceAddress: StateFlow<String?> = _bleDeviceAddress.asStateFlow()

        private val _logLevel = MutableStateFlow(LogLevel.LEVEL2)
        val logLevel: StateFlow<LogLevel> = _logLevel.asStateFlow()

        private val logsList = Collections.synchronizedList(LinkedList<LogMessage>())
        private val _logs = MutableStateFlow<List<LogMessage>>(emptyList())
        val logs: StateFlow<List<LogMessage>> = _logs.asStateFlow()

        private val _updateAvailable = MutableStateFlow(false)
        val updateAvailable: StateFlow<Boolean> = _updateAvailable.asStateFlow()

        fun checkForUpdates() {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val client = okhttp3.OkHttpClient()
                    val request = okhttp3.Request.Builder()
                        .url("https://raw.githubusercontent.com/woldphone/BLEBridge/main/version.json")
                        .build()

                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val json = JSONObject(body)
                        val remoteVersionCode = json.optInt("versionCode", 0)

                        // Current version is 1 (as defined in app/build.gradle.kts)
                        if (remoteVersionCode > 1) {
                            _updateAvailable.value = true
                        }
                    }
                } catch (e: Exception) {}
            }
        }

        fun setLogLevel(level: LogLevel) {
            _logLevel.value = level
            updateLogsFlow()
        }

        fun clearLogs() {
            logsList.clear()
            updateLogsFlow()
        }

        fun addLogMessage(level: LogLevel, message: String) {
            val msg = LogMessage(System.currentTimeMillis(), level, message)
            logsList.add(msg)
            while (logsList.size > 200) {
                logsList.removeAt(0)
            }
            updateLogsFlow()
        }

        private fun updateLogsFlow() {
            val filterLvl = _logLevel.value
            val listCopy = synchronized(logsList) { ArrayList(logsList) }
            val filtered = listCopy.filter { msg ->
                when (filterLvl) {
                    LogLevel.LEVEL1 -> msg.level == LogLevel.LEVEL1
                    LogLevel.LEVEL2 -> msg.level == LogLevel.LEVEL1 || msg.level == LogLevel.LEVEL2
                    LogLevel.LEVEL3 -> true
                }
            }
            _logs.value = filtered
        }

        fun disconnectClientExternal(id: String) {
            instance?.bootClientInternal(id)
        }

        fun getLocalIpAddress(): String {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                if (interfaces != null) {
                    for (intf in Collections.list(interfaces)) {
                        val addrs = intf.inetAddresses
                        for (addr in Collections.list(addrs)) {
                            if (!addr.isLoopbackAddress) {
                                val sAddr = addr.hostAddress ?: continue
                                if (!sAddr.contains(':')) {
                                    return sAddr
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
            return "127.0.0.1"
        }
    }

    private fun setBleConnectionState(state: BleConnectionState, address: String?) {
        _bleConnectionState.value = state
        _bleDeviceAddress.value = address
    }

    private fun log(level: LogLevel, valString: String) {
        addLogMessage(level, valString)
    }

    private fun errorResponse(msg: String): String {
        return JSONObject().apply {
            put("status", "error")
            put("timestamp", System.currentTimeMillis())
            put("message", msg)
        }.toString()
    }

    private fun createTelemetryJson(
        status: String,
        deviceAddress: String?,
        service: String?,
        characteristic: String?,
        payload: ByteArray?
    ): String {
        return JSONObject().apply {
            put("status", status)
            put("timestamp", System.currentTimeMillis())
            put("device", deviceAddress ?: "N/A")
            put("service", service ?: "N/A")
            put("characteristic", characteristic ?: "N/A")
            put("hex_payload", payload?.let { bytesToHex(it) } ?: "")
            put("ascii_payload", payload?.let { bytesToAscii(it) } ?: "")
        }.toString()
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = "0123456789ABCDEF".toCharArray()
        val hex = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            hex[i * 2] = hexChars[v ushr 4]
            hex[i * 2 + 1] = hexChars[v and 0x0F]
        }
        return String(hex)
    }

    private fun bytesToAscii(bytes: ByteArray): String {
        val sb = java.lang.StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 32..126) {
                sb.append(c.toChar())
            } else {
                sb.append('.')
            }
        }
        return sb.toString()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val cleanHex = hex.replace(" ", "").replace(":", "")
        val len = cleanHex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(cleanHex[i], 16) shl 4) + Character.digit(cleanHex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun getCharPropertiesString(properties: Int): String {
        val list = mutableListOf<String>()
        if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) list.add("READ")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) list.add("WRITE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) list.add("WRITE_NO_RESPONSE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) list.add("NOTIFY")
        if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) list.add("INDICATE")
        return if (list.isEmpty()) "NONE" else list.joinToString("|")
    }

    // --- Innner Client Socket Handler Class ---

    inner class ClientHandler(val id: String, private val socket: Socket) {
        val ip: String = socket.inetAddress.hostAddress ?: "Unknown"
        val port: Int = socket.port
        private val handlerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val outputStream: OutputStream = socket.getOutputStream()
        private val inputStream: InputStream = socket.inputStream

        fun start() {
            log(LogLevel.LEVEL2, "Socket client connected: $id")
            updateClientsList()

            handlerScope.launch {
                try {
                    val reader = inputStream.bufferedReader()
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        if (line.isNotBlank()) {
                            log(LogLevel.LEVEL3, "TCP Command Received from [$id]: $line")
                            handleClientCommand(this@ClientHandler, line)
                        }
                    }
                } catch (e: Exception) {
                    log(LogLevel.LEVEL3, "Socket Rx loop stopped for $id: ${e.message}")
                } finally {
                    close()
                }
            }
        }

        fun send(message: String) {
            handlerScope.launch {
                try {
                    synchronized(outputStream) {
                        outputStream.write((message + "\n").toByteArray(Charsets.UTF_8))
                        outputStream.flush()
                    }
                    log(LogLevel.LEVEL3, "TCP Broadcast Send to [$id] payload size: ${message.length} bytes")
                } catch (e: Exception) {
                    log(LogLevel.LEVEL1, "Failed sending to TCP client $id: ${e.message}")
                    close()
                }
            }
        }

        fun close() {
            if (handlerScope.isActive) {
                handlerScope.cancel()
            }
            try {
                socket.close()
            } catch (e: Exception) {}
            activeClients.remove(id)
            log(LogLevel.LEVEL2, "Socket client disconnected: $id")
            updateClientsList()
        }
    }
}

// --- Sequential BLE task queue model ---

sealed class BleTask(val charUuid: UUID, val onResult: (Boolean, ByteArray?) -> Unit) {
    class Read(val serviceUuid: UUID, charUuid: UUID, onResult: (Boolean, ByteArray?) -> Unit) : BleTask(charUuid, onResult)
    class Write(val serviceUuid: UUID, charUuid: UUID, val data: ByteArray, val withResponse: Boolean, onResult: (Boolean, ByteArray?) -> Unit) : BleTask(charUuid, onResult)
    class RegisterNotify(val serviceUuid: UUID, charUuid: UUID, val enable: Boolean, onResult: (Boolean, ByteArray?) -> Unit) : BleTask(charUuid, onResult)
}
