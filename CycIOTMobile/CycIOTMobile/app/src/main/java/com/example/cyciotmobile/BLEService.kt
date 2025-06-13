package com.example.cyciotmobile

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BLEService private constructor(private val context: Context) {
    companion object {
        private const val TAG = "cyciot-BLEService"
        const val SERVICE_UUID = "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
        const val CHARACTERISTIC_UUID = "beb5483e-36e1-4688-b7f5-ea07361b26a8"
        const val DEVICE_NAME = "SensorBLE"

        @Volatile
        private var instance: BLEService? = null

        fun getInstance(context: Context): BLEService {
            return instance ?: synchronized(this) {
                instance ?: BLEService(context.applicationContext).also { instance = it }
            }
        }
    }

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var bluetoothGatt: BluetoothGatt? = null
    private var characteristic: BluetoothGattCharacteristic? = null

    private val _sensorData = MutableStateFlow<SensorData?>(null)
    val sensorData: StateFlow<SensorData?> = _sensorData.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _rawData = MutableStateFlow("")
    val rawData: StateFlow<String> = _rawData.asStateFlow()

    private val scanCallback = object : ScanCallback() {
        @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN])
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            val device = result.device
            if (device.name == DEVICE_NAME) {
                Log.d(TAG, "Found target device: ${device.name}")
                stopScan()
                connectToDevice(device)
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun startScan() {
        if (bluetoothAdapter?.isEnabled == true) {
            _connectionState.value = ConnectionState.SCANNING
            bluetoothAdapter.bluetoothLeScanner.startScan(scanCallback)
            Log.d(TAG, "Started scanning for BLE devices")
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun stopScan() {
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        Log.d(TAG, "Stopped scanning")
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun connectToDevice(device: BluetoothDevice) {
        _connectionState.value = ConnectionState.CONNECTING
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            Log.d(TAG, "Using TRANSPORT_LE for connectGatt")
            bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            bluetoothGatt = device.connectGatt(context, false, gattCallback)
        }
        Log.d(TAG, "Connecting to device: ${device.name}")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange: status=$status, newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to GATT server")
                    _connectionState.value = ConnectionState.CONNECTED
                    gatt.requestMtu(100)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from GATT server")
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU changed: $mtu, status: $status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                gatt.discoverServices()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(java.util.UUID.fromString(SERVICE_UUID))
                characteristic = service?.getCharacteristic(java.util.UUID.fromString(CHARACTERISTIC_UUID))
                characteristic?.let {
                    gatt.setCharacteristicNotification(it, true)
                    Log.d(TAG, "Service and characteristic discovered")
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value.toString(Charsets.UTF_8)
            Log.d(TAG, "Received data: $data (raw bytes: ${characteristic.value.contentToString()})")
            _rawData.value = data
            parseSensorData(data)?.let { _sensorData.value = it }
        }
    }

    private fun parseSensorData(data: String): SensorData? {
        return try {
            when {
                data.startsWith("AX:") -> {
                    val ax = Regex("AX:([-0-9.]+)").find(data)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: 0f
                    val ay = Regex("AY:([-0-9.]+)").find(data)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: 0f
                    val az = Regex("AZ:([-0-9.]+)").find(data)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: 0f
                    val gx = Regex("GX:([-0-9.]+)").find(data)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: 0f
                    val gy = Regex("GY:([-0-9.]+)").find(data)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: 0f
                    val gz = Regex("GZ:([-0-9.]+)").find(data)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: 0f
                    val dl = Regex("DL:([-0-9.]+)").find(data)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: 0f
                    val dr = Regex("DR:([-0-9.]+)").find(data)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: 0f
                    val bpm = Regex("BPM:(\\d+)").find(data)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                    SensorData.AllInOne(ax, ay, az, gx, gy, gz, dl, dr, bpm)
                }
                data.startsWith("X:") -> {
                    val parts = data.split("Y:", "Z:")
                    val x = parts.getOrNull(0)?.replace("X:", "")?.toFloatOrNull() ?: 0f
                    val y = parts.getOrNull(1)?.toFloatOrNull() ?: 0f
                    val z = parts.getOrNull(2)?.toFloatOrNull() ?: 0f
                    SensorData.Accelerometer(x, y, z)
                }
                data.startsWith("DL:") || data.startsWith("DR:") -> {
                    var left = 0f
                    var right = 0f
                    if (data.contains("DL:")) {
                        val dl = Regex("DL:([-0-9.]+)").find(data)?.groupValues?.getOrNull(1)
                        left = dl?.toFloatOrNull() ?: 0f
                    }
                    if (data.contains("DR:")) {
                        val dr = Regex("DR:([-0-9.]+)").find(data)?.groupValues?.getOrNull(1)
                        right = dr?.toFloatOrNull() ?: 0f
                    }
                    SensorData.Distance(left, right)
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing sensor data: ${e.message}")
            null
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        characteristic = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }
}

sealed class SensorData {
    data class AllInOne(
        val accelX: Float,
        val accelY: Float,
        val accelZ: Float,
        val gyroX: Float,
        val gyroY: Float,
        val gyroZ: Float,
        val distanceLeft: Float,
        val distanceRight: Float,
        val bpm: Int = 0
    ) : SensorData()
    data class Accelerometer(val x: Float, val y: Float, val z: Float) : SensorData()
    data class Distance(val left: Float, val right: Float) : SensorData()
}

enum class ConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED
} 