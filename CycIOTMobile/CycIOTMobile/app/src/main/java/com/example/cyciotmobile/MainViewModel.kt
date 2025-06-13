package com.example.cyciotmobile

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "cyciot-MainViewModel"
    
    val bleService = BLEService.getInstance(application)
    
    private val _uiState = MutableStateFlow<UiState>(UiState.Initial)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            bleService.connectionState.collectLatest { state ->
                _uiState.value = when (state) {
                    ConnectionState.DISCONNECTED -> UiState.Disconnected
                    ConnectionState.SCANNING -> UiState.Scanning
                    ConnectionState.CONNECTING -> UiState.Connecting
                    ConnectionState.CONNECTED -> UiState.Connected
                }
            }
        }

        viewModelScope.launch {
            bleService.sensorData.collectLatest { data ->
                val raw = bleService.rawData.value
                when (data) {
                    is SensorData.AllInOne -> {
                        Log.d(TAG, "AllInOne: AX=${data.accelX}, AY=${data.accelY}, AZ=${data.accelZ}, GX=${data.gyroX}, GY=${data.gyroY}, GZ=${data.gyroZ}, DL=${data.distanceLeft}, DR=${data.distanceRight}, BPM=${data.bpm}")
                        _uiState.value = UiState.SensorData(
                            accelX = data.accelX,
                            accelY = data.accelY,
                            accelZ = data.accelZ,
                            gyroX = data.gyroX,
                            gyroY = data.gyroY,
                            gyroZ = data.gyroZ,
                            distanceLeft = data.distanceLeft,
                            distanceRight = data.distanceRight,
                            bpm = data.bpm,
                            rawData = raw
                        )
                    }
                    is SensorData.Accelerometer -> {
                        Log.d(TAG, "Accel: X=${data.x}, Y=${data.y}, Z=${data.z}")
                        _uiState.value = UiState.SensorData(
                            accelX = data.x,
                            accelY = data.y,
                            accelZ = data.z,
                            rawData = raw
                        )
                    }
                    is SensorData.Distance -> {
                        Log.d(TAG, "Distance: L=${data.left}, R=${data.right}")
                        _uiState.value = UiState.SensorData(
                            distanceLeft = data.left,
                            distanceRight = data.right,
                            rawData = raw
                        )
                    }
                    null -> {
                        _uiState.value = UiState.SensorData(rawData = raw)
                    }
                }
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun startScan() {
        val bluetoothManager = getApplication<Application>().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        if (bluetoothManager.adapter?.isEnabled == true) {
            bleService.startScan()
        } else {
            _uiState.value = UiState.BluetoothDisabled
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnect() {
        bleService.disconnect()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}

sealed class UiState {
    object Initial : UiState()
    object Disconnected : UiState()
    object Scanning : UiState()
    object Connecting : UiState()
    object Connected : UiState()
    object BluetoothDisabled : UiState()
    data class SensorData(
        val accelX: Float = 0f,
        val accelY: Float = 0f,
        val accelZ: Float = 0f,
        val gyroX: Float = 0f,
        val gyroY: Float = 0f,
        val gyroZ: Float = 0f,
        val distanceLeft: Float = 0f,
        val distanceRight: Float = 0f,
        val bpm: Int = 0,
        val rawData: String = ""
    ) : UiState()
} 