package com.kn.bydbi.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.kn.bydbi.data.ble.AndroidBleRepository

class DashboardViewModel(
  private val repository: AndroidBleRepository,
) : ViewModel() {
  val state = repository.state
  val autoConnectRequests = repository.autoConnectRequests

  fun scan() = repository.startScan()
  fun stopScan() = repository.stopScan()
  fun refresh() = repository.refresh()
  fun setRefreshInterval(intervalMs: Long) = repository.setRefreshInterval(intervalMs)
  fun setAutoScanEnabled(enabled: Boolean) = repository.setAutoScanEnabled(enabled)

  class Factory(private val repository: AndroidBleRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      require(modelClass.isAssignableFrom(DashboardViewModel::class.java))
      return DashboardViewModel(repository) as T
    }
  }
}
