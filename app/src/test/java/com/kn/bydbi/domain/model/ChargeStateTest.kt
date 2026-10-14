package com.kn.bydbi.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ChargeStateTest {
  @Test
  fun powerWithinPlusOrMinusTenWattsIsIdle() {
    assertEquals(ChargeState.IDLE, snapshot(powerW = 10.0).chargeState)
    assertEquals(ChargeState.IDLE, snapshot(powerW = -10.0).chargeState)
    assertEquals(ChargeState.IDLE, snapshot(powerW = 7.5).chargeState)
  }

  @Test
  fun signedPowerDeterminesChargeAndDischarge() {
    assertEquals(ChargeState.CHARGING, snapshot(powerW = 10.1).chargeState)
    assertEquals(ChargeState.DISCHARGING, snapshot(powerW = -10.1).chargeState)
  }

  private fun snapshot(powerW: Double) = BatterySnapshot(
    voltageV = 50.0,
    averageCurrentA = null,
    currentA = null,
    powerW = powerW,
    socPercent = null,
    sohPercent = null,
    remainingCapacityAh = null,
    ratedCapacityAh = null,
    cycleCount = null,
    cellCount = null,
    maxCellVoltageV = null,
    minCellVoltageV = null,
    voltageDiffV = null,
    cellTemperatureC = null,
    environmentTemperatureC = null,
    mosTemperatureC = null,
    extraTemperatures = emptyList(),
    cells = emptyList(),
    controls = emptyList(),
    protections = emptyList(),
    faults = emptyList(),
    warnings = emptyList(),
    states = emptyList(),
  )
}
