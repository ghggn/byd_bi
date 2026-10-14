package com.kn.bydbi

import android.app.Application
import com.kn.bydbi.data.ble.AndroidBleRepository

class BydBatteryApplication : Application() {
  val batteryRepository: AndroidBleRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    AndroidBleRepository(this)
  }
}
