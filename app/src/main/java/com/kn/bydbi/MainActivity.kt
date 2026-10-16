package com.kn.bydbi

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kn.bydbi.data.ble.BatteryConnectionService
import com.kn.bydbi.domain.model.BatteryDevice
import com.kn.bydbi.presentation.DashboardViewModel
import com.kn.bydbi.ui.DashboardScreen

class MainActivity : ComponentActivity() {
  private val repository get() = (application as BydBatteryApplication).batteryRepository
  private var pendingBleAction: (() -> Unit)? = null
  private var pendingNotificationAction: (() -> Unit)? = null

  private val blePermissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions(),
  ) {
    val action = pendingBleAction
    pendingBleAction = null
    if (hasBlePermissions()) action?.invoke()
    else Toast.makeText(this, "需要蓝牙扫描和连接权限才能读取电池", Toast.LENGTH_LONG).show()
  }

  private val notificationPermissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) {
    // BLE can still be used in the foreground if notification permission is denied.
    pendingNotificationAction?.invoke()
    pendingNotificationAction = null
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).apply {
      isAppearanceLightStatusBars = true
      isAppearanceLightNavigationBars = true
    }

    setContent {
      val colors = lightColorScheme(
        primary = Color(0xFF147D8D),
        onPrimary = Color.White,
        secondary = Color(0xFF1F8B68),
        background = Color(0xFFF4F8FA),
        surface = Color.White,
        error = Color(0xFFB3261E),
      )
      MaterialTheme(colorScheme = colors) {
        val dashboardViewModel: DashboardViewModel = viewModel(
          factory = DashboardViewModel.Factory(repository),
        )
        DashboardScreen(
          state = dashboardViewModel.state,
          autoConnectRequests = dashboardViewModel.autoConnectRequests,
          onScan = { withBlePermissions(dashboardViewModel::scan) },
          onStopScan = dashboardViewModel::stopScan,
          onConnect = { device -> connectWithPermissions(device) },
          onDisconnect = { disconnectService() },
          onRefresh = dashboardViewModel::refresh,
                    onSetRefreshInterval = dashboardViewModel::setRefreshInterval,
          onSetAutoScanEnabled = dashboardViewModel::setAutoScanEnabled,
        )
      }
    }
  }

  private fun withBlePermissions(action: () -> Unit) {
    if (hasBlePermissions()) {
      action()
      return
    }
    pendingBleAction = action
    blePermissionLauncher.launch(
      arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
    )
  }

  private fun hasBlePermissions(): Boolean = ContextCompat.checkSelfPermission(
    this, Manifest.permission.BLUETOOTH_SCAN
  ) == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(
    this, Manifest.permission.BLUETOOTH_CONNECT
  ) == PackageManager.PERMISSION_GRANTED

  private fun connectWithPermissions(device: BatteryDevice) {
    withBlePermissions {
      val start: () -> Unit = {
        val intent = BatteryConnectionService.connectIntent(this, device)
        ContextCompat.startForegroundService(this, intent)
      }
      if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
          this, Manifest.permission.POST_NOTIFICATIONS
        ) != PackageManager.PERMISSION_GRANTED
      ) {
        pendingNotificationAction = start
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
      } else {
        start()
      }
    }
  }

  private fun disconnectService() {
    startService(
      android.content.Intent(this, BatteryConnectionService::class.java)
        .setAction(BatteryConnectionService.ACTION_DISCONNECT),
    )
  }
}
