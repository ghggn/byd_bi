package com.kn.bydbi.data.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.kn.bydbi.BydBatteryApplication
import com.kn.bydbi.MainActivity
import com.kn.bydbi.R
import com.kn.bydbi.domain.model.BatteryDevice
import com.kn.bydbi.domain.model.ConnectionPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BatteryConnectionService : Service() {
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private lateinit var repository: AndroidBleRepository
  private var stateJob: Job? = null
  private var isForeground = false

  override fun onCreate() {
    super.onCreate()
    repository = (application as BydBatteryApplication).batteryRepository
    createNotificationChannel()
    stateJob = serviceScope.launch {
      repository.state.collect { state ->
        if (isForeground) updateNotification(state.phase, state.selectedDevice?.name)
        if (state.phase == ConnectionPhase.DISCONNECTED || state.phase == ConnectionPhase.ERROR) {
          stopForeground(STOP_FOREGROUND_REMOVE)
          isForeground = false
          stopSelf()
        }
      }
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_CONNECT -> {
        val address = intent.getStringExtra(EXTRA_ADDRESS)
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        if (address.isNullOrBlank()) {
          stopSelf(startId)
          return START_NOT_STICKY
        }
        startAsForeground("正在连接 $name")
        repository.connect(
          BatteryDevice(
            address = address,
            name = name,
            rssi = intent.getIntExtra(EXTRA_RSSI, 0),
          ),
        )
      }

      ACTION_DISCONNECT -> {
        repository.disconnect()
        stopForeground(STOP_FOREGROUND_REMOVE)
        isForeground = false
        stopSelf(startId)
      }
    }
    return START_NOT_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    stateJob?.cancel()
    repository.disconnect()
    serviceScope.cancel()
    super.onDestroy()
  }

  private fun startAsForeground(message: String) {
    val notification = buildNotification(message)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
      )
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
    isForeground = true
  }

  private fun updateNotification(phase: ConnectionPhase, name: String?) {
    val label = when (phase) {
      ConnectionPhase.CONNECTING -> "正在连接 ${name.orEmpty()}"
      ConnectionPhase.DISCOVERING -> "正在发现电池服务"
      ConnectionPhase.SUBSCRIBING -> "正在启用电池通知"
      ConnectionPhase.READY, ConnectionPhase.READING -> "已连接 ${name.orEmpty()} · 正在只读采集"
      else -> "电池连接已停止"
    }
    getSystemService(NotificationManager::class.java).notify(
      NOTIFICATION_ID,
      buildNotification(label)
    )
  }

  private fun buildNotification(text: String): Notification {
    val openIntent = PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val disconnectIntent = PendingIntent.getService(
      this,
      1,
      Intent(this, BatteryConnectionService::class.java).setAction(ACTION_DISCONNECT),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.mipmap.icon)
      .setContentTitle(getString(R.string.app_name))
      .setContentText(text.ifBlank { "电池蓝牙连接" })
      .setContentIntent(openIntent)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .addAction(0, "断开", disconnectIntent)
      .build()
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        CHANNEL_ID,
        getString(R.string.notification_channel_name),
        NotificationManager.IMPORTANCE_LOW,
      ).apply {
        description = getString(R.string.notification_channel_description)
      }
      getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
  }

  companion object {
    const val ACTION_CONNECT = "com.kn.bydbi.action.CONNECT"
    const val ACTION_DISCONNECT = "com.kn.bydbi.action.DISCONNECT"
    const val EXTRA_ADDRESS = "address"
    const val EXTRA_NAME = "name"
    const val EXTRA_RSSI = "rssi"

    private const val CHANNEL_ID = "battery_connection"
    private const val NOTIFICATION_ID = 7101

    fun connectIntent(
      service: android.content.Context,
      device: BatteryDevice,
    ) = Intent(service, BatteryConnectionService::class.java)
      .setAction(ACTION_CONNECT)
      .putExtra(EXTRA_ADDRESS, device.address)
      .putExtra(EXTRA_NAME, device.name)
      .putExtra(EXTRA_RSSI, device.rssi)
  }
}
