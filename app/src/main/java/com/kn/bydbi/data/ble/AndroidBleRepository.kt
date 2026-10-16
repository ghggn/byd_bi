package com.kn.bydbi.data.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattConnectionSettings
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.kn.bydbi.data.protocol.BatteryBlockDecoder
import com.kn.bydbi.data.protocol.F403NotificationAssembler
import com.kn.bydbi.data.protocol.FrameEvent
import com.kn.bydbi.data.protocol.NusProtocol
import com.kn.bydbi.data.protocol.ResponseFrame
import com.kn.bydbi.domain.model.BatteryDevice
import com.kn.bydbi.domain.model.BatteryUiState
import com.kn.bydbi.domain.model.ConnectionPhase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

class AndroidBleRepository(context: Context) {
  private val appContext = context.applicationContext
  private val preferences =
    appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
  private val savedRefreshIntervalMs = preferences.getLong(
    KEY_REFRESH_INTERVAL_MS,
    DEFAULT_POLL_INTERVAL_MS,
  ).coerceIn(MIN_POLL_INTERVAL_MS, MAX_POLL_INTERVAL_MS)
  private val savedAutoScanEnabled = preferences.getBoolean(KEY_AUTO_SCAN_ENABLED, false)
  private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
  private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter
  private val callbackThread = HandlerThread("byd-ble-callbacks").apply { start() }
  private val callbackHandler = Handler(callbackThread.looper)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val mutableState = MutableStateFlow(
    BatteryUiState(
      refreshIntervalMs = savedRefreshIntervalMs,
      autoScanEnabled = savedAutoScanEnabled,
    )
  )
  val state: StateFlow<BatteryUiState> = mutableState.asStateFlow()
  private val autoConnectChannel = Channel<BatteryDevice>(capacity = Channel.BUFFERED)
  val autoConnectRequests = autoConnectChannel.receiveAsFlow()
  private val pollIntervalChanges = Channel<Long>(capacity = Channel.CONFLATED)

  private val scanDevices = ConcurrentHashMap<String, BatteryDevice>()
  private val assembler = F403NotificationAssembler()
  private val readMutex = Mutex()
  private val scanning = AtomicBoolean(false)

  @Volatile
  private var gatt: BluetoothGatt? = null

  @Volatile
  private var writeCharacteristic: BluetoothGattCharacteristic? = null

  @Volatile
  private var notifyCharacteristic: BluetoothGattCharacteristic? = null

  @Volatile
  private var selectedDevice: BatteryDevice? = null

  @Volatile
  private var pendingRead: PendingRead? = null

  @Volatile
  private var pollJob: Job? = null

  @Volatile
  private var mtuFallback: Runnable? = null

  @Volatile
  private var descriptorTimeout: Runnable? = null

  @Volatile
  private var closed = false

  @Volatile
  private var lastConnectedAddress: String? = preferences.getString(KEY_LAST_DEVICE_ADDRESS, null)

  @Volatile
  private var autoConnectAttemptedThisScan = false

  private data class PendingRead(
    val request: NusProtocol.ReadRequest,
    val result: CompletableDeferred<ResponseFrame>,
  )

  private val scanCallback = object : ScanCallback() {
    override fun onScanResult(callbackType: Int, result: ScanResult) = handleScanResult(result)

    override fun onBatchScanResults(results: MutableList<ScanResult>) {
      results.forEach(::handleScanResult)
    }

    override fun onScanFailed(errorCode: Int) {
      scanning.set(false)
      mutableState.update {
        it.copy(
          scanning = false,
          phase = ConnectionPhase.ERROR,
          statusMessage = "蓝牙扫描失败（$errorCode）"
        )
      }
    }
  }

  @SuppressLint("MissingPermission")
  fun startScan() {
    callbackHandler.post {
      if (closed) return@post
      if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN) ||
        !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
      ) {
        setError("需要蓝牙扫描和连接权限")
        return@post
      }
      val leScanner = adapter?.bluetoothLeScanner
      if (adapter?.isEnabled != true || leScanner == null) {
        setError("请打开手机蓝牙后重试")
        return@post
      }
      stopScanInternal(leScanner)
      scanDevices.clear()
      autoConnectAttemptedThisScan = false
      mutableState.update {
        it.copy(
          devices = emptyList(),
          scanning = true,
          phase = ConnectionPhase.SCANNING,
          statusMessage = "正在搜索附近电池…"
        )
      }
      try {
        leScanner.startScan(
          emptyList<ScanFilter>(),
          ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
          scanCallback,
        )
        scanning.set(true)
        callbackHandler.postDelayed(scanTimeout, SCAN_WINDOW_MS)
      } catch (_: SecurityException) {
        setError("蓝牙权限已撤销，请重新授权")
      } catch (error: RuntimeException) {
        setError("无法启动蓝牙扫描：${error.message ?: "未知错误"}")
      }
    }
  }

  fun stopScan() {
    callbackHandler.post {
      val scanner = adapter?.bluetoothLeScanner
      if (scanner != null) stopScanInternal(scanner)
      mutableState.update {
        if (it.phase == ConnectionPhase.SCANNING) it.copy(
          phase = ConnectionPhase.IDLE,
          scanning = false,
          statusMessage = "扫描已停止",
        ) else it.copy(scanning = false)
      }
    }
  }

  @SuppressLint("MissingPermission")
  fun connect(device: BatteryDevice) {
    callbackHandler.post {
      if (closed) return@post
      if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
        setError("需要蓝牙连接权限")
        return@post
      }
      val btAdapter = adapter
      if (btAdapter?.isEnabled != true) {
        setError("请打开手机蓝牙后重试")
        return@post
      }
      btAdapter.bluetoothLeScanner?.let(::stopScanInternal)
      closeGattInternal()
      selectedDevice = device
      assembler.reset()
      mutableState.update {
        it.copy(
          phase = ConnectionPhase.CONNECTING,
          scanning = false,
          selectedDevice = device,
          snapshot = null,
          isRefreshing = false,
          readFailed = false,
          statusMessage = "正在连接 ${device.name}…",
        )
      }
      try {
        val remote = btAdapter.getRemoteDevice(device.address)
        gatt = connectGattCompat(remote)
        if (gatt == null) setError("Android 未创建 GATT 连接")
      } catch (_: SecurityException) {
        setError("蓝牙连接权限已撤销")
      } catch (_: IllegalArgumentException) {
        setError("设备地址无效")
      } catch (error: RuntimeException) {
        setError("连接启动失败：${error.message ?: "未知错误"}")
      }
    }
  }

  fun disconnect() {
    callbackHandler.post {
      pollJob?.cancel()
      pollJob = null
      pendingRead?.result?.completeExceptionally(IllegalStateException("Disconnected"))
      pendingRead = null
      closeGattInternal()
      selectedDevice = null
      mutableState.update {
        it.copy(
          phase = ConnectionPhase.IDLE,
          scanning = false,
          selectedDevice = null,
          snapshot = null,
          isRefreshing = false,
          readFailed = false,
          statusMessage = "已断开连接",
        )
      }
    }
  }

  fun refresh() {
    scope.launch {
      readLiveBlock()
    }
  }

  fun setRefreshInterval(intervalMs: Long) {
    require(intervalMs in MIN_POLL_INTERVAL_MS..MAX_POLL_INTERVAL_MS) {
      "刷新间隔必须在 $MIN_POLL_INTERVAL_MS–$MAX_POLL_INTERVAL_MS ms 之间"
    }
    preferences.edit { putLong(KEY_REFRESH_INTERVAL_MS, intervalMs) }
    mutableState.update { it.copy(refreshIntervalMs = intervalMs) }
    pollIntervalChanges.trySend(intervalMs)
  }

  fun setAutoScanEnabled(enabled: Boolean) {
    preferences.edit { putBoolean(KEY_AUTO_SCAN_ENABLED, enabled) }
    mutableState.update { it.copy(autoScanEnabled = enabled) }
  }

  private val scanTimeout = Runnable {
    adapter?.bluetoothLeScanner?.let(::stopScanInternal)
    mutableState.update {
      if (it.phase == ConnectionPhase.SCANNING) it.copy(
        phase = ConnectionPhase.IDLE,
        scanning = false,
        statusMessage = if (it.devices.isEmpty()) "未发现设备，请靠近电池后重试" else "扫描完成",
      ) else it.copy(scanning = false)
    }
  }

  private fun handleScanResult(result: ScanResult) {
    if (ContextCompat.checkSelfPermission(
        appContext,
        Manifest.permission.BLUETOOTH_CONNECT
      ) != PackageManager.PERMISSION_GRANTED
    ) {
      setError("蓝牙连接权限已撤销，请重新授权")
      return
    }
    val record = result.scanRecord
    val name = record?.deviceName?.takeIf(String::isNotBlank)
      ?: runCatching { result.device.name }.getOrNull()?.takeIf(String::isNotBlank)
      ?: return
    if (!name.startsWith("FD", ignoreCase = true)) return

    val device = BatteryDevice(
      address = result.device.address,
      name = name,
      rssi = result.rssi,
    )
    scanDevices[device.address] = device
    mutableState.update { state ->
      state.copy(devices = scanDevices.values.sortedByDescending(BatteryDevice::rssi))
    }
    if (!autoConnectAttemptedThisScan && device.address == lastConnectedAddress) {
      autoConnectAttemptedThisScan = true
      autoConnectChannel.trySend(device)
    }
  }

  @SuppressLint("MissingPermission")
  private fun stopScanInternal(scanner: BluetoothLeScanner) {
    callbackHandler.removeCallbacks(scanTimeout)
    if (!scanning.getAndSet(false)) return
    runCatching { scanner.stopScan(scanCallback) }
  }

  private val gattCallback = object : BluetoothGattCallback() {
    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
      if (this@AndroidBleRepository.gatt !== gatt) return
      if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
        if (ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.BLUETOOTH_CONNECT
          ) != PackageManager.PERMISSION_GRANTED
        ) {
          failConnection("蓝牙连接权限已撤销，请重新授权")
          return
        }
        mutableState.update {
          it.copy(
            phase = ConnectionPhase.DISCOVERING,
            statusMessage = "已连接，正在发现 NUS 服务…"
          )
        }
        val requested = runCatching { gatt.requestMtu(247) }.getOrDefault(false)
        if (requested) {
          val fallback = Runnable { discoverServices(gatt) }
          mtuFallback = fallback
          callbackHandler.postDelayed(fallback, MTU_TIMEOUT_MS)
        } else {
          discoverServices(gatt)
        }
      } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
        pollJob?.cancel()
        pollJob = null
        pendingRead?.result?.completeExceptionally(IllegalStateException("BLE disconnected"))
        pendingRead = null
        closeGattInternal()
        mutableState.update {
          it.copy(
            phase = ConnectionPhase.DISCONNECTED,
            selectedDevice = selectedDevice,
            statusMessage = if (status == BluetoothGatt.GATT_SUCCESS) "电池已断开" else "连接中断（GATT $status）",
          )
        }
      } else if (status != BluetoothGatt.GATT_SUCCESS) {
        setError("GATT 连接失败（$status）")
      }
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
      if (this@AndroidBleRepository.gatt !== gatt) return
      mtuFallback?.let(callbackHandler::removeCallbacks)
      mtuFallback = null
      discoverServices(gatt)
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
      if (this@AndroidBleRepository.gatt !== gatt) return
      if (status != BluetoothGatt.GATT_SUCCESS) {
        failConnection("服务发现失败（GATT $status）")
        return
      }
      val service = gatt.getService(NusProtocol.SERVICE_UUID)
      if (service == null) {
        failConnection("没有发现 NUS 服务 6E400001")
        return
      }
      val write = service.getCharacteristic(NusProtocol.WRITE_UUID)
      val notify = service.getCharacteristic(NusProtocol.NOTIFY_UUID)
        ?: service.characteristics.firstOrNull {
          it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        }
      if (write == null || write.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_WRITE) == 0) {
        failConnection("NUS 写入特征不可用")
        return
      }
      if (notify == null || notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) {
        failConnection("NUS 通知特征不可用")
        return
      }
      writeCharacteristic = write
      notifyCharacteristic = notify
      enableNotifications(gatt, notify)
    }

    override fun onDescriptorWrite(
      gatt: BluetoothGatt,
      descriptor: BluetoothGattDescriptor,
      status: Int
    ) {
      if (this@AndroidBleRepository.gatt !== gatt || descriptor.uuid != NusProtocol.CLIENT_CONFIGURATION_UUID) return
      descriptorTimeout?.let(callbackHandler::removeCallbacks)
      descriptorTimeout = null
      if (status != BluetoothGatt.GATT_SUCCESS) {
        failConnection("开启电池通知失败（GATT $status）")
        return
      }
      selectedDevice?.let { device ->
        lastConnectedAddress = device.address
        preferences.edit { putString(KEY_LAST_DEVICE_ADDRESS, device.address) }
      }
      mutableState.update { it.copy(phase = ConnectionPhase.READY, statusMessage = "已连接") }
      startPolling()
    }

    @Deprecated("Android 12 compatibility callback")
    @Suppress("DEPRECATION")
    override fun onCharacteristicChanged(
      gatt: BluetoothGatt,
      characteristic: BluetoothGattCharacteristic
    ) {
      characteristic.value?.let(::handleNotification)
    }

    override fun onCharacteristicChanged(
      gatt: BluetoothGatt,
      characteristic: BluetoothGattCharacteristic,
      value: ByteArray,
    ) {
      handleNotification(value)
    }

    override fun onCharacteristicWrite(
      gatt: BluetoothGatt,
      characteristic: BluetoothGattCharacteristic,
      status: Int
    ) {
      if (this@AndroidBleRepository.gatt !== gatt || characteristic.uuid != NusProtocol.WRITE_UUID) return
      if (status != BluetoothGatt.GATT_SUCCESS) {
        pendingRead?.result?.completeExceptionally(IllegalStateException("BLE 写入失败（GATT $status）"))
      }
    }
  }

  private fun discoverServices(connection: BluetoothGatt) {
    if (this.gatt !== connection || mutableState.value.phase == ConnectionPhase.ERROR) return
    if (ContextCompat.checkSelfPermission(
        appContext,
        Manifest.permission.BLUETOOTH_CONNECT
      ) != PackageManager.PERMISSION_GRANTED
    ) {
      failConnection("蓝牙连接权限已撤销，请重新授权")
      return
    }
    mutableState.update {
      it.copy(
        phase = ConnectionPhase.DISCOVERING,
        statusMessage = "正在发现 NUS 服务…"
      )
    }
    if (!runCatching { connection.discoverServices() }.getOrDefault(false)) {
      failConnection("Android 未能启动服务发现")
    }
  }

  @SuppressLint("MissingPermission")
  private fun enableNotifications(
    connection: BluetoothGatt,
    characteristic: BluetoothGattCharacteristic
  ) {
    mutableState.update {
      it.copy(
        phase = ConnectionPhase.SUBSCRIBING,
        statusMessage = "正在订阅电池通知…"
      )
    }
    if (!runCatching {
        connection.setCharacteristicNotification(
          characteristic,
          true
        )
      }.getOrDefault(false)) {
      failConnection("无法启用通知特征")
      return
    }
    val descriptor = characteristic.getDescriptor(NusProtocol.CLIENT_CONFIGURATION_UUID)
    if (descriptor == null) {
      failConnection("通知特征缺少 CCCD 描述符")
      return
    }
    val started = try {
      if (Build.VERSION.SDK_INT >= 33) {
        connection.writeDescriptor(
          descriptor,
          BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        ) == BluetoothStatusCodes.SUCCESS
      } else {
        @Suppress("DEPRECATION")
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        @Suppress("DEPRECATION")
        connection.writeDescriptor(descriptor)
      }
    } catch (_: SecurityException) {
      false
    }
    if (!started) {
      failConnection("无法写入 CCCD")
      return
    }
    val timeout = Runnable {
      descriptorTimeout = null
      failConnection("等待通知订阅确认超时")
    }
    descriptorTimeout = timeout
    callbackHandler.postDelayed(timeout, DESCRIPTOR_TIMEOUT_MS)
  }

  private fun handleNotification(value: ByteArray) {
    val currentPending = pendingRead ?: return
    assembler.append(value).forEach { event ->
      when (event) {
        is FrameEvent.Invalid -> currentPending.result.completeExceptionally(
          IllegalArgumentException(event.reason)
        )

        is FrameEvent.Complete -> {
          if (event.frame.byteCount != currentPending.request.byteCount) {
            currentPending.result.completeExceptionally(
              IllegalArgumentException("响应长度 ${event.frame.byteCount}，请求 ${currentPending.request.byteCount}"),
            )
          } else {
            currentPending.result.complete(event.frame)
          }
        }
      }
      if (currentPending.result.isCompleted) {
        pendingRead = null
        assembler.reset()
      }
    }
  }

  private fun startPolling() {
    if (pollJob?.isActive == true) return
    pollJob = scope.launch {
      delay(INITIAL_READ_DELAY_MS.milliseconds)
      while (isActive && gatt != null) {
        readLiveBlock()
        waitForNextPoll()
      }
    }
  }

  private suspend fun waitForNextPoll() {
    var intervalMs = mutableState.value.refreshIntervalMs
    while (true) {
      val intervalChanged = withTimeoutOrNull(intervalMs.milliseconds) {
        pollIntervalChanges.receive()
        true
      } ?: false
      if (!intervalChanged) return
      intervalMs = mutableState.value.refreshIntervalMs
    }
  }

  private suspend fun readLiveBlock() {
    if (!readMutex.tryLock()) return
    try {
      if (gatt == null || mutableState.value.phase in setOf(
          ConnectionPhase.DISCONNECTED,
          ConnectionPhase.ERROR
        )
      ) return
      val isInitialRead = mutableState.value.snapshot == null
      mutableState.update { old ->
        old.copy(
          phase = if (isInitialRead) ConnectionPhase.READING else old.phase,
          statusMessage = if (isInitialRead) "正在读取数据…" else old.statusMessage,
          isRefreshing = true,
          readFailed = false,
        )
      }
      val block = ByteArray(124)
      var offset = 0
      for (request in NusProtocol.ReadRequest.LIVE_BLOCK) {
        val frame = readRequest(request)
        if (frame.payload.size != request.byteCount) error("读取段长度不匹配")
        frame.payload.copyInto(block, offset)
        offset += frame.payload.size
        delay(100.milliseconds)
      }
      val snapshot = BatteryBlockDecoder.decode(block)
      mutableState.update { old ->
        old.copy(
          phase = ConnectionPhase.READY,
          statusMessage = "已连接",
          snapshot = snapshot,
          isRefreshing = false,
          readFailed = false,
        )
      }
    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
      mutableState.update { old ->
        old.copy(
          phase = if (old.snapshot == null) ConnectionPhase.READY else old.phase,
          statusMessage = if (old.snapshot == null) "读取超时" else old.statusMessage,
          isRefreshing = false,
          readFailed = true,
        )
      }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      mutableState.update { old ->
        old.copy(
          phase = if (old.snapshot == null) ConnectionPhase.READY else old.phase,
          statusMessage = if (old.snapshot == null) "读取失败" else old.statusMessage,
          isRefreshing = false,
          readFailed = true,
        )
      }
    } finally {
      readMutex.unlock()
    }
  }

  private suspend fun readRequest(request: NusProtocol.ReadRequest): ResponseFrame {
    var retries = 0
    while (true) {
      try {
        return readRequestOnce(request)
      } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
        if (retries >= READ_RETRY_COUNT) throw timeout
        retries += 1
        delay(READ_RETRY_DELAY_MS.milliseconds)
      } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        if (retries >= READ_RETRY_COUNT) throw error
        retries += 1
        delay(READ_RETRY_DELAY_MS.milliseconds)
      }
    }
  }

  private suspend fun readRequestOnce(request: NusProtocol.ReadRequest): ResponseFrame {
    val result = CompletableDeferred<ResponseFrame>()
    try {
      return withTimeout(READ_TIMEOUT_MS.milliseconds) {
        callbackHandler.post {
          val connection = gatt
          val characteristic = writeCharacteristic
          if (connection == null || characteristic == null || notifyCharacteristic == null) {
            result.completeExceptionally(IllegalStateException("NUS 尚未就绪"))
            return@post
          }
          if (pendingRead != null) {
            result.completeExceptionally(IllegalStateException("已有读取请求等待响应"))
            return@post
          }
          assembler.reset()
          pendingRead = PendingRead(request, result)
          val accepted = try {
            writeNusCommand(connection, characteristic, request.asciiHex())
          } catch (_: SecurityException) {
            false
          }
          if (!accepted) {
            pendingRead = null
            result.completeExceptionally(IllegalStateException("Android 拒绝了 NUS 写入"))
          }
        }
        result.await()
      }
    } finally {
      callbackHandler.post {
        if (pendingRead?.result === result) {
          pendingRead = null
          assembler.reset()
        }
      }
    }
  }

  @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
  @Suppress("DEPRECATION")
  private fun connectGattCompat(device: BluetoothDevice): BluetoothGatt? {
    return if (Build.VERSION.SDK_INT >= 37) {
      val settings = BluetoothGattConnectionSettings.Builder()
        .setAutoConnectEnabled(false)
        .setAutomaticMtuEnabled(false)
        .setTransport(BluetoothDevice.TRANSPORT_LE)
        .build()
      device.connectGatt(
        settings,
        { command -> callbackHandler.post(command) },
        gattCallback,
      )
    } else {
      // Android 12–16 only expose the context-based overload; use it only on those releases.
      device.connectGatt(
        appContext,
        false,
        gattCallback,
        BluetoothDevice.TRANSPORT_LE,
        BluetoothDevice.PHY_LE_1M_MASK,
        callbackHandler,
      )
    }
  }

  @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
  @Suppress("DEPRECATION")
  private fun writeNusCommand(
    connection: BluetoothGatt,
    characteristic: BluetoothGattCharacteristic,
    value: ByteArray,
  ): Boolean = if (Build.VERSION.SDK_INT >= 33) {
    connection.writeCharacteristic(
      characteristic,
      value,
      BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
    ) == BluetoothStatusCodes.SUCCESS
  } else {
    // Android 12–12L require the mutable characteristic API for GATT writes.
    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
    characteristic.value = value
    connection.writeCharacteristic(characteristic)
  }

  @SuppressLint("MissingPermission")
  private fun closeGattInternal() {
    mtuFallback?.let(callbackHandler::removeCallbacks)
    descriptorTimeout?.let(callbackHandler::removeCallbacks)
    mtuFallback = null
    descriptorTimeout = null
    val old = gatt
    gatt = null
    writeCharacteristic = null
    notifyCharacteristic = null
    assembler.reset()
    old?.let { connection ->
      runCatching { connection.disconnect() }
      runCatching { connection.close() }
    }
  }

  private fun failConnection(message: String) {
    pollJob?.cancel()
    pollJob = null
    pendingRead?.result?.completeExceptionally(IllegalStateException(message))
    pendingRead = null
    closeGattInternal()
    mutableState.update { it.copy(phase = ConnectionPhase.ERROR, statusMessage = message) }
  }

  private fun setError(message: String) {
    mutableState.update {
      it.copy(
        phase = ConnectionPhase.ERROR,
        scanning = false,
        statusMessage = message
      )
    }
  }

  private fun hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(
      appContext,
      permission
    ) == PackageManager.PERMISSION_GRANTED

  companion object {
    private const val PREFERENCES_NAME = "battery_connection"
    private const val KEY_LAST_DEVICE_ADDRESS = "last_connected_address"
    private const val KEY_REFRESH_INTERVAL_MS = "refresh_interval_ms"
    private const val KEY_AUTO_SCAN_ENABLED = "auto_scan_enabled"
    private const val MIN_POLL_INTERVAL_MS = 500L
    private const val MAX_POLL_INTERVAL_MS = 10_000L
    private const val DEFAULT_POLL_INTERVAL_MS = 2_000L
    private const val SCAN_WINDOW_MS = 12_000L
    private const val MTU_TIMEOUT_MS = 1_500L
    private const val DESCRIPTOR_TIMEOUT_MS = 4_000L
    private const val READ_TIMEOUT_MS = 3_500L
    private const val READ_RETRY_COUNT = 2
    private const val READ_RETRY_DELAY_MS = 150L
    private const val INITIAL_READ_DELAY_MS = 800L
  }
}
