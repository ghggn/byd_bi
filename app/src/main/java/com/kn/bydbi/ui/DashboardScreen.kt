package com.kn.bydbi.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kn.bydbi.R
import com.kn.bydbi.domain.model.BatteryDevice
import com.kn.bydbi.domain.model.BatterySnapshot
import com.kn.bydbi.domain.model.BatteryUiState
import com.kn.bydbi.domain.model.ChargeState
import com.kn.bydbi.domain.model.ConnectionPhase
import kotlinx.coroutines.flow.Flow
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
  state: kotlinx.coroutines.flow.StateFlow<BatteryUiState>,
  autoConnectRequests: Flow<BatteryDevice>,
  onScan: () -> Unit,
  onStopScan: () -> Unit,
  onConnect: (BatteryDevice) -> Unit,
  onDisconnect: () -> Unit,
  onRefresh: () -> Unit,
  onSetRefreshInterval: (Long) -> Unit,
) {
  val uiState by state.collectAsStateWithLifecycle()
  var showRefreshSettings by remember { mutableStateOf(false) }
  var intervalDraft by remember(uiState.refreshIntervalMs) {
    mutableFloatStateOf(uiState.refreshIntervalMs.toFloat())
  }
  LaunchedEffect(autoConnectRequests) {
    autoConnectRequests.collect(onConnect)
  }
  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text(stringResource(id = R.string.app_name), fontWeight = FontWeight.SemiBold)
            Text(
              "本地蓝牙 · 只读",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        },
        actions = {
          TextButton(
            modifier = Modifier.width(92.dp),
            onClick = onRefresh,
            enabled = uiState.phase == ConnectionPhase.READY && !uiState.isRefreshing,
          ) {
            Text(
              if (uiState.isRefreshing) "刷新中" else "刷新",
              maxLines = 1,
              fontWeight = FontWeight.Bold,
              color = when {
                uiState.readFailed -> MaterialTheme.colorScheme.error
                uiState.isRefreshing -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
              },
            )
          }
          TextButton(onClick = { showRefreshSettings = true }) {
            Text("设置", fontWeight = FontWeight.Medium)
          }
        },
      )
    },
    containerColor = MaterialTheme.colorScheme.background,
  ) { padding ->
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(
        start = 16.dp,
        end = 16.dp,
        top = 8.dp,
        bottom = 28.dp
      ),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item {
        ConnectionCard(
          phase = uiState.phase,
          message = uiState.statusMessage,
          device = uiState.selectedDevice,
          scanning = uiState.scanning,
          onScan = onScan,
          onStopScan = onStopScan,
          onDisconnect = onDisconnect,
        )
      }

      val connected = uiState.phase in setOf(ConnectionPhase.READY, ConnectionPhase.READING)
      if (!connected && (uiState.scanning || uiState.devices.isNotEmpty())) {
        item { SectionTitle("附近的电池") }
        if (uiState.scanning && uiState.devices.isEmpty()) {
          item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
              Row(
                Modifier
                  .fillMaxWidth()
                  .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                  "正在搜索附近电池…",
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
            }
          }
        }
        items(uiState.devices, key = BatteryDevice::address) { device ->
          DeviceCard(device = device, onConnect = { onConnect(device) })
        }
      }

      uiState.snapshot?.let { snapshot ->
        item { SocCard(snapshot) }
        item { DetailCard(snapshot) }
        item { HealthCard(snapshot) }
      } ?: item {
        EmptyDashboard(phase = uiState.phase, message = uiState.statusMessage)
      }
    }
  }
  if (showRefreshSettings) {
    AlertDialog(
      onDismissRequest = { showRefreshSettings = false },
      title = { Text("刷新频率") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("刷新间隔：${intervalDraft.toInt()} ms")
          Slider(
            value = intervalDraft,
            onValueChange = { intervalDraft = it },
            valueRange = MIN_REFRESH_INTERVAL_MS.toFloat()..MAX_REFRESH_INTERVAL_MS.toFloat(),
            steps = REFRESH_INTERVAL_STEPS,
          )
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
              "500 ms",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
              "10000 ms",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }
      },
      confirmButton = {
        TextButton(
          onClick = {
            onSetRefreshInterval(intervalDraft.toLong())
            showRefreshSettings = false
          },
        ) { Text("保存") }
      },
      dismissButton = {
        TextButton(onClick = { showRefreshSettings = false }) { Text("取消") }
      },
    )
  }
}

@Composable
private fun ConnectionCard(
  phase: ConnectionPhase,
  message: String,
  device: BatteryDevice?,
  scanning: Boolean,
  onScan: () -> Unit,
  onStopScan: () -> Unit,
  onDisconnect: () -> Unit,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    shape = RoundedCornerShape(22.dp),
  ) {
    Column(
      Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        val connected = phase == ConnectionPhase.READY || phase == ConnectionPhase.READING
        Surface(
          shape = CircleShape,
          color = if (connected) Color(0xFFE4F4EC) else Color(0xFFFFF3DD),
        ) {
          Text(
            if (connected) "已连接" else phase.label(),
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            color = if (connected) Color(0xFF16734C) else Color(0xFF8B5A00),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
          )
        }
        Spacer(Modifier.width(10.dp))
        Text(
          message,
          modifier = Modifier.weight(1f),
          style = MaterialTheme.typography.bodyMedium,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis
        )
      }
      if (device != null) {
        Text(
          device.name,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold
        )
        Text(
          "RSSI ${device.rssi} dBm",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (scanning) {
          OutlinedButton(onClick = onStopScan) { Text("停止扫描") }
        } else if (phase in setOf(
            ConnectionPhase.IDLE,
            ConnectionPhase.DISCONNECTED,
            ConnectionPhase.ERROR
          )
        ) {
          Button(onClick = onScan) { Text("扫描电池") }
        }
        if (device != null && phase !in setOf(
            ConnectionPhase.IDLE,
            ConnectionPhase.DISCONNECTED
          )
        ) {
          OutlinedButton(onClick = onDisconnect) { Text("断开") }
        }
      }
    }
  }
}

@Composable
private fun DeviceCard(device: BatteryDevice, onConnect: () -> Unit) {
  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
    Row(
      Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(Modifier.weight(1f)) {
        Text(
          device.name,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold
        )
        Text(
          "RSSI ${device.rssi} dBm",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
      Button(onClick = onConnect) { Text("连接") }
    }
  }
}

@Composable
private fun SocCard(snapshot: BatterySnapshot) {
  Card(
    colors = CardDefaults.cardColors(containerColor = chargeColor(snapshot.chargeState)),
    shape = RoundedCornerShape(24.dp),
  ) {
    Row(
      Modifier
        .fillMaxWidth()
        .padding(18.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
      SocGauge(snapshot.socPercent, Color.White, Modifier.size(142.dp))
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          snapshot.currentA?.let { "电流 ${format(it, decimals = 2)} A" } ?: "电流 - A",
          style = MaterialTheme.typography.headlineSmall,
          color = Color.White
        )
        Text(
          snapshot.powerW?.let { "功率 ${format(it, decimals = 2)} W" } ?: "功率 - W",
          style = MaterialTheme.typography.headlineSmall,
          color = Color.White
        )
        Text(
          snapshot.voltageDiffV?.let { "压差 ${format(it * 1000.0, 0)} mV" }
            ?: "压差 - mV",
          style = MaterialTheme.typography.headlineSmall,
          color = Color.White
        )
      }
    }
  }
}

@Composable
private fun SocGauge(value: Double?, activeColor: Color, modifier: Modifier = Modifier) {
  val valueColor = if (value == null) MaterialTheme.colorScheme.outline else activeColor
  Box(modifier, contentAlignment = Alignment.Center) {
    Canvas(
      Modifier
        .fillMaxSize()
        .padding(7.dp)
    ) {
      val strokeWidth = 14.dp.toPx()
      drawArc(
        color = Color.DarkGray,
        startAngle = 135f,
        sweepAngle = 270f,
        useCenter = false,
        style = Stroke(strokeWidth, cap = StrokeCap.Round),
      )
      if (value != null) {
        drawArc(
          color = valueColor,
          startAngle = 135f,
          sweepAngle = 270f * (value.coerceIn(0.0, 100.0) / 100.0).toFloat(),
          useCenter = false,
          style = Stroke(strokeWidth, cap = StrokeCap.Round),
        )
      }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text(value?.let { "${format(it, 1)}%" } ?: "--",
        fontSize = 26.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White)
      Text("SOC", style = MaterialTheme.typography.labelMedium, color = Color.White)
    }
  }
}


@Composable
private fun DetailCard(snapshot: BatterySnapshot) {
  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
    Column(
      Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      SectionTitle("电池详细信息")
      DetailRow("电压", snapshot.voltageV?.let { "${format(it, 2)} V" } ?: "— V")
      DetailRow("SOH", snapshot.sohPercent?.let { "${format(it, 1)}%" } ?: "—")
      DetailRow(
        "剩余容量",
        snapshot.remainingCapacityAh?.let { "${format(it, 2)} Ah" } ?: "—")
      DetailRow("额定容量", snapshot.ratedCapacityAh?.let { "${format(it, 2)} Ah" } ?: "—")
      DetailRow("循环次数", snapshot.cycleCount?.toString() ?: "—")
      DetailRow("平均电流", snapshot.averageCurrentA?.let { "${format(it, 2)} A" } ?: "—")
      DetailRow("电芯温度", snapshot.cellTemperatureC?.let { "${format(it, 1)} °C" } ?: "—")
      DetailRow(
        "环境温度",
        snapshot.environmentTemperatureC?.let { "${format(it, 1)} °C" } ?: "—")
      DetailRow("MOS 温度", snapshot.mosTemperatureC?.let { "${format(it, 1)} °C" } ?: "—")
      DetailRow("压差", snapshot.voltageDiffV?.let { "${format(it * 1000.0, 0)} mV" } ?: "—")
      HorizontalDivider()
      Row(verticalAlignment = Alignment.CenterVertically) {
        SectionTitle("单体电压")
        Spacer(Modifier.width(8.dp))
        Text(
          "${snapshot.cells.size} 节",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
      val cellRows = snapshot.cells.chunked(4)
      cellRows.forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          row.forEach { cell ->
            Surface(
              modifier = Modifier.weight(1f),
              shape = RoundedCornerShape(10.dp),
              color = if (cell.balancing) Color(0xFFE7F4EC) else MaterialTheme.colorScheme.surfaceVariant,
            ) {
              Column(Modifier.padding(horizontal = 8.dp, vertical = 7.dp)) {
                Text(
                  "#${cell.index}",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                  format(cell.voltageV, 3),
                  style = MaterialTheme.typography.labelLarge,
                  fontWeight = FontWeight.SemiBold
                )
                if (cell.balancing) Text(
                  "均衡",
                  style = MaterialTheme.typography.labelSmall,
                  color = Color(0xFF16734C)
                )
              }
            }
          }
          repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
        }
      }
      if (snapshot.cells.isEmpty()) Text(
        "暂无单体电压数据",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      if (snapshot.extraTemperatures.isNotEmpty()) {
        DetailRow(
          "辅助温度",
          snapshot.extraTemperatures.joinToString(" · ") {
            "T${it.channel} ${
              format(
                it.temperatureC,
                1
              )
            }°C"
          })
      }
    }
  }
}

@Composable
private fun HealthCard(snapshot: BatterySnapshot) {
  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
    Column(
      Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      SectionTitle("保护与状态")
      DetailRow("保护", snapshot.protections.joinToString("、").ifBlank { "无" })
      DetailRow("告警", snapshot.warnings.joinToString("、").ifBlank { "无" })
      DetailRow("故障", snapshot.faults.joinToString("、").ifBlank { "无" })
      DetailRow("BMS 状态", snapshot.states.joinToString("、").ifBlank { "无" })
    }
  }
}

@Composable
private fun EmptyDashboard(phase: ConnectionPhase, message: String) {
  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
    Column(
      Modifier
        .fillMaxWidth()
        .padding(22.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      if (phase in setOf(
          ConnectionPhase.CONNECTING,
          ConnectionPhase.DISCOVERING,
          ConnectionPhase.SUBSCRIBING,
          ConnectionPhase.READING
        )
      ) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
      }
      Text(
        if (phase == ConnectionPhase.ERROR) "暂时无法连接" else "等待电池数据",
        style = MaterialTheme.typography.titleMedium
      )
      Text(
        message,
        modifier = Modifier
          .fillMaxWidth()
          .height(22.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun SectionTitle(text: String) {
  Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun DetailRow(label: String, value: String) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(
      label,
      modifier = Modifier.width(108.dp),
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.width(12.dp))
    Text(
      value,
      modifier = Modifier.weight(1f),
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.Medium,
      textAlign = TextAlign.End
    )
  }
}

private fun ConnectionPhase.label(): String = when (this) {
  ConnectionPhase.IDLE -> "未连接"
  ConnectionPhase.SCANNING -> "扫描中"
  ConnectionPhase.CONNECTING -> "连接中"
  ConnectionPhase.DISCOVERING -> "发现服务"
  ConnectionPhase.SUBSCRIBING -> "启用通知"
  ConnectionPhase.READY -> "已连接"
  ConnectionPhase.READING -> "读取中"
  ConnectionPhase.DISCONNECTED -> "已断开"
  ConnectionPhase.ERROR -> "异常"
}

private fun format(value: Double, decimals: Int): String =
  String.format(Locale.US, "%.${decimals}f", value)

private fun chargeColor(state: ChargeState): Color = when (state) {
  ChargeState.CHARGING -> Color(0xFF557B62)
  ChargeState.DISCHARGING -> Color(0xFF965F63)
  ChargeState.IDLE -> Color(0xFF46545A)
}

private const val MIN_REFRESH_INTERVAL_MS = 500L
private const val MAX_REFRESH_INTERVAL_MS = 10_000L
private const val REFRESH_INTERVAL_STEPS = 18
