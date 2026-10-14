package com.kn.bydbi.domain.model

enum class ConnectionPhase {
  IDLE,
  SCANNING,
  CONNECTING,
  DISCOVERING,
  SUBSCRIBING,
  READY,
  READING,
  DISCONNECTED,
  ERROR,
}

data class BatteryDevice(
  val address: String,
  val name: String,
  val rssi: Int,
)

data class CellReading(
  val index: Int,
  val voltageV: Double,
  val balancing: Boolean,
)

data class TemperatureReading(
  val channel: Int,
  val temperatureC: Double,
)

data class BatterySnapshot(
  val voltageV: Double?,
  val averageCurrentA: Double?,
  val currentA: Double?,
  val powerW: Double?,
  val socPercent: Double?,
  val sohPercent: Double?,
  val remainingCapacityAh: Double?,
  val ratedCapacityAh: Double?,
  val cycleCount: Int?,
  val cellCount: Int?,
  val maxCellVoltageV: Double?,
  val minCellVoltageV: Double?,
  val voltageDiffV: Double?,
  val cellTemperatureC: Double?,
  val environmentTemperatureC: Double?,
  val mosTemperatureC: Double?,
  val extraTemperatures: List<TemperatureReading>,
  val cells: List<CellReading>,
  val controls: List<String>,
  val protections: List<String>,
  val faults: List<String>,
  val warnings: List<String>,
  val states: List<String>,
) {
  val chargeState: ChargeState
    get() {
      val power = powerW ?: return ChargeState.IDLE
      return when {
        power > 10.0 -> ChargeState.CHARGING
        power < -10.0 -> ChargeState.DISCHARGING
        else -> ChargeState.IDLE
      }
    }
}

enum class ChargeState(val label: String) {
  CHARGING("充电中"),
  DISCHARGING("放电中"),
  IDLE("静置"),
}

data class BatteryUiState(
  val phase: ConnectionPhase = ConnectionPhase.IDLE,
  val statusMessage: String = "点击扫描附近的电池",
  val scanning: Boolean = false,
  val isRefreshing: Boolean = false,
  val readFailed: Boolean = false,
  val devices: List<BatteryDevice> = emptyList(),
  val selectedDevice: BatteryDevice? = null,
  val snapshot: BatterySnapshot? = null,
  val refreshIntervalMs: Long = 2_000L,
)
