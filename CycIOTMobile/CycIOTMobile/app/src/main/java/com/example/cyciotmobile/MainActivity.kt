package com.example.cyciotmobile

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    val viewModel: MainViewModel by viewModels()
    private val TAG = "cyciot-MainActivity"

    private lateinit var statusText: TextView
    private lateinit var scanButton: Button
    private lateinit var analyzeButton: Button
    private lateinit var accelXText: TextView
    private lateinit var accelYText: TextView
    private lateinit var accelZText: TextView
    private lateinit var gyroXText: TextView
    private lateinit var gyroYText: TextView
    private lateinit var gyroZText: TextView
    private lateinit var distanceLeftText: TextView
    private lateinit var distanceRightText: TextView
    private lateinit var bpmText: TextView
    private lateinit var rawDataText: TextView

    @SuppressLint("MissingPermission")
    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            checkBluetoothEnabled()
        } else {
            Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("MissingPermission")
    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    )  { result ->
        if (result.resultCode == RESULT_OK) {

            viewModel.startScan()
        } else {
            Toast.makeText(this, "Bluetooth must be enabled", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        setupClickListeners()
        observeViewModel()
        checkPermissions()
    }

    private fun initializeViews() {
        statusText = findViewById(R.id.statusText)
        scanButton = findViewById(R.id.scanButton)
        analyzeButton = findViewById(R.id.analyzeButton)
        accelXText = findViewById(R.id.accelXText)
        accelYText = findViewById(R.id.accelYText)
        accelZText = findViewById(R.id.accelZText)
        gyroXText = findViewById(R.id.gyroXText)
        gyroYText = findViewById(R.id.gyroYText)
        gyroZText = findViewById(R.id.gyroZText)
        distanceLeftText = findViewById(R.id.distanceLeftText)
        distanceRightText = findViewById(R.id.distanceRightText)
        bpmText = findViewById(R.id.bpmText)
        rawDataText = findViewById(R.id.rawDataText)
    }

    private fun setupClickListeners() {
        scanButton.setOnClickListener {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return@setOnClickListener
            }
            checkBluetoothEnabled()
        }
        
        analyzeButton.setOnClickListener {
            startActivity(Intent(this, AnalysisActivity::class.java))
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                updateUI(state)
            }
        }
    }

    private fun updateUI(state: UiState) {
        when (state) {
            is UiState.Initial -> {
                statusText.text = "Ready to scan"
                scanButton.isEnabled = true
            }
            is UiState.Disconnected -> {
                statusText.text = "Disconnected"
                scanButton.isEnabled = true
            }
            is UiState.Scanning -> {
                statusText.text = "Scanning..."
                scanButton.isEnabled = false
            }
            is UiState.Connecting -> {
                statusText.text = "Connecting..."
                scanButton.isEnabled = false
            }
            is UiState.Connected -> {
                statusText.text = "Connected"
                scanButton.isEnabled = true
            }
            is UiState.BluetoothDisabled -> {
                statusText.text = "Bluetooth disabled"
                scanButton.isEnabled = true
            }
            is UiState.SensorData -> {
                accelXText.text = "X: %.2f".format(state.accelX)
                accelYText.text = "Y: %.2f".format(state.accelY)
                accelZText.text = "Z: %.2f".format(state.accelZ)
                gyroXText.text = "Gyro X: %.2f".format(state.gyroX)
                gyroYText.text = "Gyro Y: %.2f".format(state.gyroY)
                gyroZText.text = "Gyro Z: %.2f".format(state.gyroZ)
                distanceLeftText.text = "Left: %.2f cm".format(state.distanceLeft)
                distanceRightText.text = "Right: %.2f cm".format(state.distanceRight)
                bpmText.text = "BPM: %d".format(state.bpm)
                rawDataText.text = "Raw Data: ${state.rawData}"
            }
        }
    }

    private fun checkPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(

                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        if (permissions.all { permission ->
                ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
            }) {
            checkBluetoothEnabled()
        } else {
            bluetoothPermissionLauncher.launch(permissions)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun checkBluetoothEnabled() {
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        if (bluetoothManager.adapter?.isEnabled == true) {
            viewModel.startScan()
        } else {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            bluetoothEnableLauncher.launch(enableBtIntent)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onDestroy() {
        super.onDestroy()
        viewModel.disconnect()
    }
}