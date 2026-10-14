package com.kn.bydbi.data.protocol

import com.kn.bydbi.domain.model.BatterySnapshot
import com.kn.bydbi.domain.model.CellReading
import com.kn.bydbi.domain.model.TemperatureReading

/**
 * Candidate 124-byte layout ported from the 迪子电量 NUS default adapter.
 * It is marked unverified until values are checked against this battery model.
 */
object BatteryBlockDecoder {
  private const val EXPECTED_SIZE = 124
  private const val MAX_CELL_COUNT = 24

  private val controls = listOf(
    listOf(
      "允许放电",
      "允许充电",
      "关闭SOC过低保护",
      "使用常规均衡",
      "开放充电",
      "开放放电",
      "禁熔保险丝",
      "启用LED"
    ),
    listOf(
      "禁用防拆故障",
      "关闭数据统计",
      "停止LOG",
      "CAN主动发送",
      "保留",
      "保留",
      "保留",
      "保留"
    ),
    listOf(
      "强制关机",
      "4G模块强制断电",
      "RS485透传",
      "4G模块串口透传",
      "AFE调试",
      "强制休眠",
      "保留",
      "强制均衡"
    ),
    List(8) { "保留" },
  )
  private val protections = listOf(
    listOf(
      "电芯一级欠压",
      "NTC开路",
      "电芯二级欠压",
      "二级电芯过压",
      "充电过流",
      "一级放电过流",
      "二级放电过流",
      "短路"
    ),
    listOf(
      "充电低温",
      "放电低温",
      "芯片内部低温",
      "NTC短路",
      "充电高温",
      "放电高温",
      "芯片内部高温",
      "FET高温"
    ),
    listOf(
      "温差",
      "一级电芯过压",
      "预充超时",
      "充电过流锁定",
      "满充锁定",
      "放电过流锁定",
      "短路锁定",
      "三级放电过流"
    ),
    listOf(
      "压差",
      "组过压",
      "组欠压",
      "一级充电过流",
      "二级充电过流",
      "环境温度过低",
      "环境温度过高",
      "AFE通信失效"
    ),
  )
  private val warnings = listOf(
    listOf(
      "放电高温",
      "充电高温",
      "放电低温",
      "充电低温",
      "环境高温",
      "环境低温",
      "MOS高温",
      "SOC过低"
    ),
    listOf(
      "放电过流",
      "外部进水",
      "电芯过压",
      "电芯欠压",
      "AFE配置出错",
      "组过压",
      "组欠压",
      "一级充电过流"
    ),
    listOf(
      "热失控",
      "保留",
      "保留",
      "RTC停止",
      "外部Flash失效",
      "片内Flash失效",
      "防拆故障",
      "压差告警"
    ),
  )
  private val faults = listOf(
    "电芯温度高",
    "电芯失效",
    "充电MOS故障",
    "放电MOS故障",
    "内包进水",
    "断线",
    "保留",
    "电芯电压高"
  )
  private val states = listOf(
    listOf(
      "放电开关",
      "充电开关",
      "预放电",
      "预充电",
      "充电器状态",
      "负载状态",
      "防拆状态",
      "APP合法状态"
    ),
    listOf(
      "允许OTA",
      "在充电柜内",
      "FLASH写授权",
      "4G模块供电状态",
      "4G模块与信息通信状态",
      "4G模块与WAKE_UP电压",
      "RTC被设置",
      "预放电错误"
    ),
    listOf(
      "允许回滚",
      "OCV校准",
      "非法充电",
      "满充校准",
      "SOC低压修正",
      "SOC高压修正",
      "保留",
      "保留"
    ),
    List(8) { "保留" },
  )

  fun decode(block: ByteArray): BatterySnapshot {
    require(block.size == EXPECTED_SIZE) { "Expected 124 bytes, got ${block.size}" }
    val cellCount = block[63].toInt() and 0xFF
    require(cellCount in 1..MAX_CELL_COUNT) { "Invalid cell count: $cellCount" }

    val rawSoc = u16(block, 48) / 10.0
    val remainingAh = u32(block, 52) / 1000.0
    val ratedAh = u32(block, 56) / 1000.0
    val soc = if (rawSoc == 0.0 && ratedAh > 0.0) remainingAh / ratedAh * 100.0 else rawSoc

    val balanceMask = u32(block, 64)
    val cells = List(cellCount) { index ->
      CellReading(
        index = index + 1,
        voltageV = u16(block, 68 + index * 2) / 1000.0,
        balancing = balanceMask and (1L shl index) != 0L,
      )
    }
    val temperatures = (0 until 4).mapNotNull { channel ->
      val raw = i16(block, 116 + channel * 2)
      val celsius = raw / 10.0
      if (celsius !in -100.0..100.0 || (channel >= 2 && celsius == 0.0)) null
      else TemperatureReading(channel + 1, celsius)
    }

    val current = i16(block, 26) / 100.0
    val voltage = u16(block, 20) / 100.0
    return BatterySnapshot(
      voltageV = voltage.takeIf { it in 0.0..150.0 },
      averageCurrentA = (i16(block, 24) / 100.0).takeIf { it in -1000.0..1000.0 },
      currentA = current.takeIf { it in -1000.0..1000.0 },
      powerW = (voltage * current).takeIf { kotlin.math.abs(it) < 100_000.0 },
      socPercent = soc.takeIf { it in 0.0..100.0 },
      sohPercent = (u16(block, 50) / 10.0).takeIf { it in 0.0..100.0 },
      remainingCapacityAh = remainingAh.takeIf { it < 100_000.0 },
      ratedCapacityAh = ratedAh.takeIf { it < 100_000.0 },
      cycleCount = u16(block, 60),
      cellCount = cellCount,
      maxCellVoltageV = (u16(block, 34) / 1000.0).takeIf { it in 0.0..10.0 },
      minCellVoltageV = (u16(block, 36) / 1000.0).takeIf { it in 0.0..10.0 },
      voltageDiffV = (u16(block, 40) / 1000.0).takeIf { it in 0.0..10.0 },
      cellTemperatureC = (i16(block, 28) / 10.0).takeIf { it in -100.0..200.0 },
      environmentTemperatureC = (i16(block, 30) / 10.0).takeIf { it in -100.0..200.0 },
      mosTemperatureC = (i16(block, 32) / 10.0).takeIf { it in -100.0..200.0 },
      extraTemperatures = temperatures,
      cells = cells,
      controls = activeBits(block, 0, controls),
      protections = activeBits(block, 4, protections),
      faults = activeBits(block, 8, listOf(faults)),
      warnings = activeBits(block, 9, warnings),
      states = activeBits(block, 12, states),
    )
  }

  private fun activeBits(data: ByteArray, offset: Int, banks: List<List<String>>): List<String> =
    banks.flatMapIndexed { bankIndex, labels ->
      val value = data[offset + bankIndex].toInt() and 0xFF
      labels.mapIndexedNotNull { bit, label ->
        if (label != "保留" && value and (1 shl bit) != 0) label else null
      }
    }

  private fun u16(data: ByteArray, offset: Int): Int =
    (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

  private fun i16(data: ByteArray, offset: Int): Int {
    val value = u16(data, offset)
    return if (value and 0x8000 != 0) value - 0x10000 else value
  }

  private fun u32(data: ByteArray, offset: Int): Long =
    (data[offset].toLong() and 0xFF) or
        ((data[offset + 1].toLong() and 0xFF) shl 8) or
        ((data[offset + 2].toLong() and 0xFF) shl 16) or
        ((data[offset + 3].toLong() and 0xFF) shl 24)
}
