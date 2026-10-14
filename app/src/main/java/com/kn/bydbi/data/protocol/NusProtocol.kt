package com.kn.bydbi.data.protocol

import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

object NusProtocol {
  val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
  val WRITE_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
  val NOTIFY_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
  val CLIENT_CONFIGURATION_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

  /** These are read requests only. No generic opcode/string is exposed to the UI. */
  enum class ReadRequest(val address: Int, val byteCount: Int) {
    LIVE_1000(0x1000, 60),
    LIVE_103C(0x103C, 60),
    LIVE_1078(0x1078, 4);

    fun asciiHex(): ByteArray = readCommand(address, byteCount)
      .toByteArray(StandardCharsets.US_ASCII)

    companion object {
      val LIVE_BLOCK = listOf(LIVE_1000, LIVE_103C, LIVE_1078)
    }
  }

  fun readCommand(address: Int, byteCount: Int): String {
    require(address in 0..0xFFFF) { "Address out of range" }
    require(byteCount in 1..0xFF) { "Read length out of range" }
    val body = byteArrayOf(
      0xF4.toByte(), 0x03,
      ((address ushr 8) and 0xFF).toByte(),
      (address and 0xFF).toByte(),
      byteCount.toByte(),
    )
    return (body + crc16Modbus(body)).toHex()
  }

  fun crc16Modbus(bytes: ByteArray): ByteArray {
    var crc = 0xFFFF
    bytes.forEach { value ->
      crc = crc xor (value.toInt() and 0xFF)
      repeat(8) {
        crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
      }
    }
    return byteArrayOf((crc and 0xFF).toByte(), ((crc ushr 8) and 0xFF).toByte())
  }

  fun parseResponse(frameText: String): ResponseFrame {
    val normalized = frameText.filterNot(Char::isWhitespace).uppercase(Locale.ROOT)
    require(normalized.length >= 10 && normalized.startsWith("F403")) { "Not an F403 response" }
    require(normalized.length % 2 == 0) { "Odd-length response" }
    val bytes = normalized.hexToBytes()
    val count = bytes[2].toInt() and 0xFF
    val expectedSize = count + 5
    require(bytes.size == expectedSize) {
      "Response length mismatch: header=$count bytes, actual=${bytes.size - 5} bytes"
    }
    val expectedCrc = crc16Modbus(bytes.copyOfRange(0, bytes.size - 2))
    require(bytes[bytes.size - 2] == expectedCrc[0] && bytes.last() == expectedCrc[1]) {
      "CRC-16/MODBUS mismatch"
    }
    return ResponseFrame(
      byteCount = count,
      payload = bytes.copyOfRange(3, 3 + count),
    )
  }
}

data class ResponseFrame(
  val byteCount: Int,
  val payload: ByteArray,
)

sealed interface FrameEvent {
  data class Complete(val frame: ResponseFrame) : FrameEvent
  data class Invalid(val reason: String) : FrameEvent
}

/** Reassembles the ASCII-hex byte stream emitted through NUS notifications. */
class F403NotificationAssembler {
  private val pending = StringBuilder()

  fun reset() = pending.clear()

  fun append(notification: ByteArray): List<FrameEvent> {
    val chunk = notification.toString(StandardCharsets.US_ASCII).filterNot(Char::isWhitespace)
    if (chunk.any { it.digitToIntOrNull(16) == null }) {
      reset()
      return listOf(FrameEvent.Invalid("Notification contains non-hex text"))
    }
    pending.append(chunk.uppercase(Locale.ROOT))
    val frames = mutableListOf<FrameEvent>()

    while (true) {
      val start = pending.indexOf("F403")
      if (start < 0) {
        val keep = minOf(pending.length, 3)
        if (pending.length > keep) pending.delete(0, pending.length - keep)
        break
      }
      if (start > 0) pending.delete(0, start)
      if (pending.length < 6) break

      val payloadLength = pending.substring(4, 6).toIntOrNull(16)
      if (payloadLength == null || payloadLength > 200) {
        frames += FrameEvent.Invalid("Invalid F403 response length")
        pending.delete(0, 2)
        continue
      }
      val frameHexLength = (payloadLength + 5) * 2
      if (pending.length < frameHexLength) break

      val frameText = pending.substring(0, frameHexLength)
      pending.delete(0, frameHexLength)
      try {
        frames += FrameEvent.Complete(NusProtocol.parseResponse(frameText))
      } catch (error: IllegalArgumentException) {
        frames += FrameEvent.Invalid(error.message ?: "Invalid response")
      }
    }
    return frames
  }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it.toInt() and 0xFF) }

internal fun String.hexToBytes(): ByteArray {
  require(length % 2 == 0) { "Hex text length must be even" }
  return ByteArray(length / 2) { index ->
    substring(index * 2, index * 2 + 2).toInt(16).toByte()
  }
}
